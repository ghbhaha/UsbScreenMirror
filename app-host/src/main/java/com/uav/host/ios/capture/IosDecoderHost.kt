package com.uav.host.ios.capture

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.uav.host.ios.media.H264Decoder
import com.uav.host.ios.ui.IosPanel
import com.uav.protocol.ios.AudioParser

/**
 * 「解析出来的 SPS/PPS → 解码器 → Surface」这一段，外加音频播放器与日志。
 *
 * 从 `IosActivity` 里搬出来的是三件互相纠缠的事：
 *
 *  - **解码器寿命**：谁起、谁重建、Surface 没了怎么办（[onCsdArrived] / [suspendRendering] /
 *    [resumeRendering]）；
 *  - **音频**：采集流程建的那个播放器要能暂停/恢复/清空；
 *  - **日志出口**：logcat + 面板，带时间戳与高频行过滤。
 *
 * 它们必须在一起：`pendingCsd` 是重建解码器的**唯一依据**，而它的生命周期由 Surface
 * 回调决定、由 CSD 回调填充、由收尾清空 —— 拆开就会出现"某处忘了保留 CSD"这类
 * 「切后台回来黑屏、只有声音没画面」的经典缺陷（见 [suspendRendering] 的注释）。
 */
internal class IosDecoderHost(private val panel: IosPanel) {

    /** 等 Surface 就绪用的 SPS/PPS（**切后台时绝不能清**）。 */
    private var pendingCsd: ByteArray? = null

    private var decoder: H264Decoder? = null

    /** 当前音频播放器。由采集流程在开始时交进来、结束时清空。 */
    private var audio: PcmAudioPlayer? = null

    /**
     * 日志时间戳：`SimpleDateFormat` 非线程安全，而 [log] 会被收流 / 保活 / 轮询等多条线程同时调用。
     *
     * ★ 不用 `ThreadLocal.withInitial{}`：那是 **API 24** 才有的方法，本模块 minSdk 23、
     *   又没有开 core library desugaring ⇒ 在 API 23 机器上会 `NoSuchMethodError`。
     *   这里继承 `ThreadLocal` 覆写 `initialValue()`（API 1 起就有），行为一致、零兼容风险。
     *   也不选 `DateTimeFormatter`：那要 API 26 + desugaring，同样超出本模块的下限。
     */
    private val logFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    // ───────────────────────────────────────────────────────────── 解码器

    /**
     * 收到（新的）SPS/PPS。
     *
     * 第一次：等 Surface 就绪后起解码器；
     * 之后内容变了（iPad 转屏 ⇒ 分辨率变）：**必须重建解码器**，否则旧 codec 会静默停产。
     */
    fun onCsdArrived(csd: ByteArray) {
        pendingCsd = csd
        if (decoder == null) ensureDecoder() else decoder?.restart(csd)
    }

    /** 采到一帧 Annex-B 数据。解码器还没起来时直接丢（起不来就没画面，日志里已有原因）。 */
    fun onFrame(frame: ByteArray) {
        decoder?.submit(frame)
    }

    /**
     * Surface 消失（切后台 / 熄屏 / Activity 销毁中）→ **停渲染 + 静音，但保持链路**。
     *
     * 完全照 `aoa/session/HostSession.suspendRendering()` 的做法：
     *  - 解码器绑定的是 `configure` 那一刻的 Surface，失效后既不能继续渲染也不能改绑，
     *    所以直接放掉；
     *  - **`pendingCsd` 必须留着** —— 它是重建解码器的唯一依据。
     *
     * ★★ 这里曾经多写了一行 `pendingCsd = null`，那就是「切后台再回来视频黑屏」的根因：
     *   新的 CSD 只在**内容变化**时才回调（`deliverCsd` 去重），所以清掉之后
     *   [ensureDecoder] 会永远 `return`，画面再也不会出现。音频却是独立通道，
     *   于是表现为"只有声音没有画面"。
     *
     * 为什么挂在 Surface 生命周期上而不是 `onPause`：`surfaceDestroyed` 才是
     * "真的没有画面可画了"的权威信号，而且它已经在驱动渲染开关；
     * 再挂一份 `onPause` 会出现"有时暂停、有时不暂停"的双状态（这条同样来自 AOA 10.22）。
     */
    fun suspendRendering() {
        if (decoder == null && audio?.isPaused == true) return
        runCatching { decoder?.stop() }
        decoder = null
        audio?.pauseForBackground()
    }

    /** Surface 回来了 → 用缓存的 CSD 立刻重建解码器，并恢复音频。 */
    fun resumeRendering() {
        log("预览 Surface 就绪")
        audio?.resumeFromBackground()
        ensureDecoder()
    }

    /** 收尾：把解码器与缓存的 CSD 都放掉，画面让回黑底。 */
    fun releaseDecoder() {
        runCatching { decoder?.stop() }
        decoder = null
        pendingCsd = null
    }

    /** Surface 与 SPS/PPS 都到位时启动解码器。 */
    private fun ensureDecoder() {
        val sf = panel.currentSurface() ?: return
        val csd = pendingCsd ?: return
        if (decoder != null) return
        decoder = H264Decoder(sf, ::log) { w, h -> panel.setVideoSize(w, h) }
            .also { it.start(csd) }
    }

    // ───────────────────────────────────────────────────────────── 音频

    /** 采集流程建好播放器后交给这里（之后音频帧由采集流程直接喂进来）。 */
    fun attachAudio(player: PcmAudioPlayer?) {
        audio = player
    }

    /** 采到一包 PCM。 */
    fun onAudio(pcm: ByteArray, fmt: AudioParser.Format) {
        audio?.feed(pcm, fmt)
    }

    // ───────────────────────────────────────────────────────────── 日志

    /** 带时间戳的一行日志（logcat + 面板）。高频行由**产生方**先限流，这里不做取舍。 */
    fun log(msg: String) {
        // 同时打到 logcat：UI 文本要 uiautomator dump 才读得到，logcat 一条命令就能跟
        Log.i(TAG, msg)
        //   （ThreadLocal.get() 在 Kotlin 里是平台类型，initialValue 保证非空，故 !!）
        panel.log("${logFmt.get()!!.format(Date())}  $msg")
    }

    internal companion object {
        const val TAG = "UAV-IosQt"
    }
}