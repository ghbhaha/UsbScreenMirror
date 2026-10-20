package com.uav.host.ios.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.uav.protocol.media.AnnexB
import com.uav.protocol.ios.SpsInfo
import java.nio.ByteBuffer

/**
 * H.264 解码 + 直接渲染到 [Surface]。
 *
 * 数据形态（实测 2026-10-18，552 个 FEED 包）：
 *  - 每个 FEED 的样本体是 `[4B 长度]['tads'][4B 长度前缀的 NAL][4B 长度前缀的 NAL]…`（AVCC）；
 *  - NAL 类型只有 `1`（P 帧）/ `5`（IDR）/ `6`（SEI），**没有 SPS/PPS**——
 *    它们随 `ASYN/sprp` 一起下发，所以必须先等 [start] 拿到 csd 才能解。
 */
internal class H264Decoder(
    private val surface: Surface,
    private val log: (String) -> Unit,
    /** 解码器首次交出真实画面尺寸（含裁剪）时回调 —— 视频视图按它做信箱适配 */
    private val onSize: ((Int, Int) -> Unit)? = null
) {

    private var codec: MediaCodec? = null
    private var ptsUs = 0L
    private var frames = 0

    /** 复用输出缓冲描述（每帧 `drain` 都跑，不该每次都 new 一个）。 */
    private val outInfo = MediaCodec.BufferInfo()

    /** 喂帧失败只在第一次打日志：`submit` 在热路径上，刷屏会把面板 50 条缓冲冲干净。 */
    private var droppedOnce = false

    /**
     * SPS 解出的真实画面尺寸（**暂存**，不一定马上交给视图）。
     *
     * 必须自己解：`MediaFormat` 里的宽高是**我们请求的**，老 OMX 组件会把它原样回显、
     * 也不给 `crop-*`（实测 Amlogic / Android 7.1 商显）。拿它做信箱适配就会把
     * iPad 的 1200x1718 当成 1920x1080 铺满屏幕 ⇒ 画面横向被拉宽。
     */
    private var spsSize: SpsInfo.Size? = null

    /** 这个尺寸是否还没告诉视图（转屏重建时用：等新解码器真出帧了再改布局）。 */
    private var sizePending = false

    /**
     * 用 Annex-B 形式的 SPS+PPS 起解码器。
     *
     * ## csd 注入：先整体、失败再切分
     *
     * 整体塞进 `csd-0` 在绝大多数机型上可用；但部分 legacy OMX 组件会拒绝/忽略合并的
     * `csd-0`（`configure` 失败，或能起但不吐帧），所以整体注入失败时按起始码切成
     * `csd-0`/`csd-1` 再试一次 —— 与 `aoa/video/VideoDecoder.configure` 同一套策略。
     *
     * @param announceSizeNow 是否**立刻**把尺寸交给视图。
     *   - 首次启动：true —— 反正屏幕还是黑的，早点把信箱尺寸摆好；
     *   - 转屏重建：false —— 见 [restart] 的说明。
     */
    fun start(csd: ByteArray, announceSizeNow: Boolean = true): Boolean {
        if (codec != null) return true
        return runCatching {
            // 尺寸优先用 SPS 里的真值，解不出来才退回 1080p 占位
            val sps = SpsInfo.fromCsd(csd)
            spsSize = sps
            val w = sps?.width ?: 1920
            val h = sps?.height ?: 1080
            log(
                "解码器启动中（csd ${csd.size}B，SPS " +
                    (sps?.toString() ?: "未解出⇒按 ${w}x$h 占位") + "）"
            )
            // ★ 尺寸在 configure **之前**定，因为 SurfaceView 改尺寸是异步的：
            //   放在 configure 之后容易赶上"surface 正在改大小"，画面会闪一下黑。
            if (announceSizeNow) {
                sps?.let { onSize?.invoke(it.width, it.height) }
                sizePending = false
            } else {
                sizePending = sps != null
            }
            val c = configureCodec(csd, w, h)
            codec = c
            log("解码器已启动（${w}x$h）")
            true
        }.getOrElse {
            log("✗ 解码器启动失败：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    /**
     * 建一个可用的 codec：先整体注入 `csd-0`，不行再按起始码切成 `csd-0`/`csd-1`。
     *
     * ⚠️ 每一条失败路径都必须把建了一半的 codec `release` 掉：否则每次重试（新 CSD /
     *   Surface 重建 / 重新武装）都漏一个到底层 codec 池，池子耗尽后整个机型的视频都起不来。
     */
    private fun configureCodec(csd: ByteArray, w: Int, h: Int): MediaCodec =
        try {
            tryConfigure(listOf(csd), w, h)
        } catch (whole: Throwable) {
            val nals = AnnexB.split(csd)
            log("✗ 整体注入 csd-0 失败（${whole.message}），回退按起始码切分为 ${nals.size} 段")
            if (nals.isEmpty()) throw whole
            tryConfigure(nals.take(2), w, h)
        }

    private fun tryConfigure(units: List<ByteArray>, w: Int, h: Int): MediaCodec {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            units.forEachIndexed { i, u -> setByteBuffer("csd-$i", ByteBuffer.wrap(u)) }
        }
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(fmt, surface, null, 0)
            c.start()
        } catch (t: Throwable) {
            runCatching { c.stop() }
            runCatching { c.release() }
            throw t
        }
        return c
    }

    /**
     * 分辨率变化（iPad 转屏）时重建解码器。
     *
     * MediaCodec 对**流中途的 SPS 变化**支持并不一致：实测旧 codec 会停在
     * "收得到码流、但不吐帧"的状态（FEED 一直在涨，帧数不动）。重建最干净。
     *
     * ## 为什么不在这里就改视图尺寸（转屏"不自然"的原因）
     *
     * Amlogic 这类硬件解码器从 `configure` 到吐出第一帧要 **~600ms**（实测
     * 42.242 启动 → 42.833 首帧）。如果一收到新 SPS 就改布局，这 600ms 里视图已经是
     * 新长宽比、画面却还是**上一帧** ⇒ 用户看到"被拉扁的定格画面"，比黑屏更难受。
     * ⇒ 尺寸先挂着（[sizePending]），等新解码器**真出帧**的那一刻再改 —— 布局切换与
     *   内容切换同时发生，是一次干脆的"画面翻面"。
     */
    fun restart(csd: ByteArray) {
        log("重建解码器（新 csd ${csd.size}B）")
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        frames = 0
        droppedOnce = false
        start(csd, announceSizeNow = false)
    }

    /**
     * 送一帧（Annex-B）。
     *
     * ★ 本方法跑在 `QtSession` 的**读循环**上（同一条线程还要读 USB、回 SKEW/时钟应答、喂音频），
     *   所以**绝不能等**：输入缓冲拿不到就丢掉这一帧，与 `PcmAudioPlayer.feed` 的
     *   `WRITE_NON_BLOCKING` 是同一个取舍。等 10ms 的话，一旦解码器停吐帧，60fps 下
     *   一半以上的读循环时间会耗在等待上，连时钟应答都被拖慢。
     */
    fun submit(annexB: ByteArray) {
        val c = codec ?: return
        runCatching {
            val inIdx = c.dequeueInputBuffer(0)
            if (inIdx < 0) return@runCatching
            val buf = c.getInputBuffer(inIdx) ?: return@runCatching
            buf.clear()
            if (buf.remaining() < annexB.size) {
                // 输入缓冲不够：这一帧丢掉（正常不会发生，单个 NAL 远小于 1MB）
                c.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                return@runCatching
            }
            buf.put(annexB)
            ptsUs += 16_666
            c.queueInputBuffer(inIdx, 0, annexB.size, ptsUs, 0)
            drain(c)
        }.onFailure {
            // 以前整条路静默吞掉：画面没了却和"没数据"长得一模一样。只打第一次，别刷屏。
            if (droppedOnce) return@onFailure
            droppedOnce = true
            log("✗ 解码喂帧失败（后续同类错误不再重复打印）：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun drain(c: MediaCodec) {
        val info = outInfo
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 尺寸优先级：解码器 crop-* > SPS > 解码器 format 里报的（后者在老 OMX 上是
                // **我们自己请求的值原样回显**，拿它做信箱会把画面拉宽 —— 实测 Amlogic 7.1）。
                runCatching {
                    val f = c.outputFormat
                    val kw = f.getInteger(MediaFormat.KEY_WIDTH)
                    val kh = f.getInteger(MediaFormat.KEY_HEIGHT)
                    val cropL = if (f.containsKey("crop-left")) f.getInteger("crop-left") else 0
                    val cropR = if (f.containsKey("crop-right")) f.getInteger("crop-right") else 0
                    val cropT = if (f.containsKey("crop-top")) f.getInteger("crop-top") else 0
                    val cropB = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") else 0
                    val cropW = if (cropR > cropL) cropR - cropL + 1 else 0
                    val cropH = if (cropB > cropT) cropB - cropT + 1 else 0
                    val sps = spsSize
                    val w: Int
                    val h: Int
                    val from: String
                    when {
                        cropW > 0 && cropH > 0 -> { w = cropW; h = cropH; from = "解码器 crop" }
                        sps != null -> { w = sps.width; h = sps.height; from = "SPS" }
                        else -> { w = kw; h = kh; from = "解码器 format" }
                    }
                    log("视频尺寸 ${w}x$h（来自 $from；format 报 ${kw}x$kh）")
                    onSize?.invoke(w, h)
                    sizePending = false
                }.onFailure { log("读视频尺寸失败：${it.message}") }
                continue
            }
            if (outIdx >= 0) {
                // 兜底：个别解码器不发 FORMAT_CHANGED，那就在"第一帧马上要上屏"时改布局 ——
                // 效果一样（布局切换与内容切换同一拍），不会出现"定格画面被拉扁"。
                if (sizePending && frames == 0) {
                    spsSize?.let { onSize?.invoke(it.width, it.height) }
                    sizePending = false
                }
                c.releaseOutputBuffer(outIdx, true)   // true = 直接渲染到 Surface
                frames++
                if (frames == 1 || frames % 120 == 0) log("渲染 $frames 帧（${info.size}B）")
            } else {
                break
            }
        }
    }

    fun stop() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        if (frames > 0) log("解码结束：共渲染 $frames 帧")
    }

    val renderedFrames: Int get() = frames
}