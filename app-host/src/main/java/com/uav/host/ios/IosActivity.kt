package com.uav.host.ios

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.uav.host.R
import com.uav.host.common.DebugTools
import com.uav.host.ios.ui.IosPanel
import com.uav.protocol.ios.DeviceVersion
import com.uav.protocol.ios.PairStore
import com.uav.protocol.ios.QtStats
import com.uav.protocol.ios.SampleParser
import com.uav.protocol.ios.QtPacket
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 最小宿主：一个按钮跑完「激活 → 等采集配置 → claim → 握手 → 收流」。
 *
 * 逻辑完全照 Windows 上已验证通过的那份（tools/qt-repro），
 * 不做双配置仪式、不做 clearHalt、不做控制 kick。
 */
class IosActivity : Activity() {

    private lateinit var usb: UsbManager
    private lateinit var transport: QtTransport
    private lateinit var panel: IosPanel

    /** 解码器要等 Surface 和 SPS/PPS 都就绪才能起 */
    private var pendingCsd: ByteArray? = null
    private var decoder: H264Decoder? = null
    private var audio: PcmAudioPlayer? = null

    /** 首帧只处理一次：到首帧就把面板收起来，把画面让出来 */
    private val firstFrameDone = AtomicBoolean(false)

    /**
     * 是否向设备声明音频接收能力（`ASYN/HPA1`）。
     *
     * ⚠️ 打开会让 iPad 把音频路由到 USB；**若本端收不到 EAT 帧，iPad 上点播放会立刻暂停**。
     * 所以只在排查音频时临时置 true，验证完必须改回 false。
     *
     * 本次置 true 是为验证「HPD1 恢复成每个 CWPA 都发」后音频是否出现，验完按结果决定去留。
     * 结果（2026-10-18 17:03 与 17:15，两次都在 iPad 正放音频时测）：HPD1×2+clock+HPA1 与
     * AFMT 应答全齐、写路径也已改成独立线程（读路径不再被写阻塞），**仍然零 EAT**
     * ⇒ 本机这台 iPad8,9 / iOS 27.2 就是不推 QT 音频帧。已改回 false。
     */
    private val requestAudio = true

    /**
     * 是否先建 lockdown 受信任会话再 claim QT。
     *
     * ★ iPhoneMirror（有线模式，能同时出音视频）**完全不做 lockdown**：
     *   它的日志是 `libusb0_quicktime_open claim=success configuration=5 interface=2`
     *   紧接 `async_sprp`，中间没有任何 mux/TLS 步骤。
     *   而我们从 2026-10-18 起一直坚持"先 lockdown 再 claim"（当时的 A/B 说没有它会静默）。
     *   ⇒ 已验证：关掉之后设备**只回一次 PING 就沉默**（零 CWPA/零 FEED）。
     *   原因是它借用了 PC 上常驻的 **Apple Mobile Device Service**（它自己的
     *   DRIVER_DEPENDENCIES.md 把 AMDS 列为必需依赖）—— 那份受信任会话由系统提供，
     *   安卓上没有，所以我们必须自己建。**这个开关必须保持 true。**
     */
    private val useLockdown = true

    /** 用户点了「停止投屏」 */
    @Volatile
    private var stopRequested = false

    /** 当前正在跑的会话 —— 停止信号要能立刻打断循环里的 bulkTransfer */
    @Volatile
    private var activeSession: QtSession? = null

    /**
     * 收流期间 iPad 被拔掉过。
     *
     * 用来把「用户拔线」和「链路出错」区分开：前者回空闲态并提示插回，
     * 后者才是红药丸。混成一种，用户会以为是自己把程序弄坏了。
     */
    private val deviceDetached = AtomicBoolean(false)

    /**
     * 本次会话读到的设备版本（纯诊断，见 [DeviceVersion]）。
     *
     * 存在字段里是因为 [MuxLink] 是 [capture] 内部的局部变量，
     * 而"要不要给用户一句版本相关的提示"要等到**收尾判失败**时才决定。
     */
    @Volatile
    private var lastDeviceVersion: DeviceVersion = DeviceVersion.UNKNOWN

    private val running = AtomicBoolean(false)
    private var permissionLatch: CountDownLatch? = null

    /** 自动开始用的前台轮询（见 [autoPoller]）。只在页面可见时跑。 */
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var polling = false

    /** 上一次见到的 Apple 设备 deviceId；-1 = 当前没有设备（拔掉即复位） */
    @Volatile
    private var lastSeenDeviceId = -1

    /**
     * 是否把 FEED 原始报文落盘。
     *
     * **默认 false**，只能由 adb 打开：`am start … --ez record true`。
     * 理由见 [com.uav.host.common.DebugTools.IOS_EXTRA_RECORD] —— 全量落盘约 2 MB/s，
     * 而且写盘失败会打断读循环。
     */
    private var recordFeed = false

    /** adb 指定"进来就回放 feed.bin"（不插 iPad 也能验证解码链路） */
    private var replayRequested = false

    private val logFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != QtTransport.ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            log("权限广播：granted=$granted")
            permissionLatch?.countDown()
        }
    }

    /**
     * 拔出广播：立刻打断读线程。
     *
     * 读循环自己也能发现（端点连续失败 + 设备从设备表消失），但那要等一次
     * `bulkTransfer` 返回。广播会先到，用户感知更干净；
     * 而且两条判据互相兜底 —— 广播在个别 ROM 上不派发时，读循环仍然能退出。
     */
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val dev = IntentCompat.getParcelableExtra(
                intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java
            )
            // 不认识的厂商设备（U 盘、鼠标）不该打断我们的会话
            if (dev != null && dev.vendorId != QtTransport.APPLE_VID) return
            log("USB 拔出广播：${dev?.deviceName ?: "(未携带设备描述符)"}")
            deviceDetached.set(true)
            activeSession?.stopRequested = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        setContentView(R.layout.activity_ios)
        // ★ 必须在 setContentView 之后构造：IosPanel 在构造期就 findViewById
        panel = IosPanel(this)
        panel.bind(
            onSurface = { ready -> if (ready) resumeRendering() else suspendRendering() },
            onRearm = { if (!running.get()) rearm() }
        )
        // 投屏期间别让屏幕自己熄掉（熄屏会连带把 Surface 收走）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // ★ 遥控器/D-pad：先给药丸一个焦点，否则第一下方向键没有"起点"
        panel.focusFirst()

        transport = QtTransport(this, usb, ::log)
        // ★ 必须走 ContextCompat：三分量 registerReceiver 是 API 26+ 的，
        //   而本模块 minSdk = 23（合并进 app-host 时统一成 23）。ContextCompat 会在
        //   低版本上退化成两分量调用，并自动补 exported 语义。
        ContextCompat.registerReceiver(
            this,
            permissionReceiver,
            IntentFilter(QtTransport.ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 拔出广播：收流时拔线要立刻停下，不能等读循环自己发现
        ContextCompat.registerReceiver(
            this,
            detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        log("QuickTime USB 采集（Android）—— 插上 iPad 即自动配对并投屏，拔线即停")
        refreshDeviceInfo(state = "空闲")
        panel.render(IosPanel.Stage.IDLE, null)
        // 配对记录**按设备**分开存：每台 iPad 一条，互不覆盖（换回来不用再点信任）
        val known = Pairing.knownDeviceKeys(filesDir)
        when {
            known.isNotEmpty() -> log(
                "配对记录：已记住 ${known.size} 台设备（${known.joinToString()}）" +
                    "—— 换设备只在新设备上点一次「信任」"
            )
            Pairing.hasLegacyRecord(filesDir) ->
                log("配对记录：旧版单文件 ⇒ 插上 iPad 后会自动迁移成按设备存放")
            else -> log("配对记录：无 ⇒ 首次会自动发起配对，请留意 iPad 上的「信任」弹窗")
        }

        // ★ 录制 / 回放都只在 adb 上开，界面不留入口：
        //   am start -n com.uav.host/.ios.IosActivity --ez record true
        //   am start -n com.uav.host/.ios.IosActivity --ez replay true
        recordFeed = DebugTools.ENABLED &&
            (intent?.getBooleanExtra(DebugTools.IOS_EXTRA_RECORD, false) ?: false)
        replayRequested = DebugTools.ENABLED &&
            (intent?.getBooleanExtra(DebugTools.IOS_EXTRA_REPLAY, false) ?: false)
        if (recordFeed) log("录制开关：开（adb --ez record true），会写 feed.bin")
        if (replayRequested) {
            log("回放开关：开（adb --ez replay true）")
            // 放到 onCreate 之后：replay 会置 running，随后的轮询看到它就不会抢着建链
            handler.post { replay() }
        }
    }

    /**
     * 插上就自动开跑。
     *
     * 为什么用轮询而不是等广播：**本机（P40UCH）插入时根本不派发 `USB_DEVICE_ATTACHED`**
     * （只发 DETACHED，这是已记录在案的机型缺陷）。合并前 AOA 侧的"插线即达"一直靠
     * `UsbAttachRouter` 的前台轮询，iOS 这边照同样的办法补上。
     *
     * 只认 **deviceId 基线之外的新设备**：拔掉时把基线复位成 -1，
     * 于是"拔了再插"能再次触发，而同一次插入不会因为失败而无限重试
     * （重试手段是拔插一次，或点「重新唤醒」）。
     */
    private val autoPoller = object : Runnable {
        override fun run() {
            if (!polling) return
            val dev = transport.findAppleDevice()
            if (dev == null) {
                // 设备消失 ⇒ 复位基线，这样再插回来一定会重新触发
                lastSeenDeviceId = -1
            } else if (!running.get() && dev.deviceId != lastSeenDeviceId) {
                lastSeenDeviceId = dev.deviceId
                log("检测到 iPad（deviceId=${dev.deviceId}）⇒ 自动开始")
                autoRun()
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /**
     * 全自动流程：**配对（如需要）→ 投屏**。
     *
     * 没有「开始」按钮了，所以进度与失败原因全靠主行/副行 + 日志说清楚。
     * `running` 用 `compareAndSet` 防重入（轮询与「重新唤醒」可能撞在一起）。
     */
    private fun autoRun() {
        if (!running.compareAndSet(false, true)) return
        stopRequested = false
        deviceDetached.set(false)
        firstFrameDone.set(false)
        panel.setMirroring(true)
        panel.render(IosPanel.Stage.CONNECTING, null)
        refreshDeviceInfo(state = "启动中")

        Thread({
            var stats: QtStats? = null
            runCatching {
                // ① 这台设备还没有配对记录 ⇒ 先配对（iPad 上点一次「信任」）
                //    ★ 记录按设备分开：换过一台 iPad 只影响新的那台，旧的记录原样保留
                val pairKey = currentDeviceKey() ?: Pairing.KEY_UNKNOWN
                log("本次设备键=$pairKey（配对记录按它区分设备）")
                if (!Pairing.hasRecord(filesDir, pairKey)) {
                    panel.render(IosPanel.Stage.PAIRING, getString(R.string.ios_detail_pair_hint))
                    if (!pairNow()) {
                        log("配对未完成，本次不投屏 —— 拔插一次或点「重新唤醒」可再试")
                        return@runCatching
                    }
                }
                // ② 投屏：激活 / 切配置 / 建 lockdown / claim QT / 收流，全在 capture 里
                stats = capture()
            }.onFailure { log("自动流程异常：${it.javaClass.simpleName}: ${it.message}") }
            finishRun(stats)
        }, "ios-auto").start()
    }

    /**
     * 刷新「设备」卡片。
     *
     * 只写事实：设备名 / 配置数（4=普通态、5=采集态）/ 配对状态。
     * 这行是排「0 字节」时最有用的一条 —— 比翻日志快。
     */
    private fun refreshDeviceInfo(state: String) {
        val dev = transport.findAppleDevice()
        // 「配对」这一行说的是**当前插着的这台**；下面再补一句本机记住了几台
        val key = dev?.let { Pairing.deviceKey(it) }
        val pair = key?.let { Pairing.loadRecord(filesDir, it) }?.hostId
        val known = Pairing.knownDeviceKeys(filesDir)
        val sb = StringBuilder()
        if (dev == null) {
            sb.append("未连接\n")
        } else {
            sb.append("iPad · pid 0x").append(dev.productId.toString(16)).append('\n')
            sb.append("配置数 ").append(dev.configurationCount)
                .append(if (dev.configurationCount >= 5) "（采集态）" else "（普通态）").append('\n')
        }
        sb.append("配对 ").append(
            when {
                pair != null -> "已信任"
                Pairing.hasLegacyRecord(filesDir) -> "旧版记录（插上后自动迁移）"
                else -> "未配对（首次需在 iPad 上点「信任」）"
            }
        )
        if (pair != null) sb.append('\n').append(pair)
        sb.append('\n').append("已记住 ").append(known.size).append(" 台设备")
        sb.append('\n').append("状态 ").append(state)
        panel.setDeviceInfo(sb.toString())
    }

    /** 当前插着的那台 iPad 的设备键（USB 序列号）；没有设备返回 null。 */
    private fun currentDeviceKey(): String? =
        transport.findAppleDevice()?.let { Pairing.deviceKey(it) }

    override fun onResume() {
        super.onResume()
        // 页面可见时才开始"插上即投"的轮询：后台跑它没有意义，还会误触发
        polling = true
        handler.removeCallbacks(autoPoller)
        handler.post(autoPoller)
    }

    override fun onPause() {
        polling = false
        handler.removeCallbacks(autoPoller)
        super.onPause()
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(permissionReceiver) }
        runCatching { unregisterReceiver(detachReceiver) }
        // ★ Activity 销毁时必须让读线程自己停下来：它正在跑一个无限循环，
        //   而且每轮都可能往已经销毁的 Activity 上 post UI 任务。
        activeSession?.stopRequested = true
        stopRequested = true
        super.onDestroy()
    }

    /** 离线回放 filesDir/feed.bin：验证「解析 → 解码 → 上屏」整条链路。 */
    private fun replay() {
        running.set(true)
        panel.setMirroring(true)
        panel.render(IosPanel.Stage.REPLAY, "回放 filesDir/feed.bin")
        Thread({
            val f = File(getExternalFilesDir(null) ?: filesDir, "feed.bin")
            if (!f.exists() || f.length() < 1024) {
                log("没有可回放的 feed.bin —— 先用 adb 录一次：" +
                    "am start -n com.uav.host/.ios.IosActivity --ez record true")
                running.set(false)
                panel.setMirroring(false)
                panel.render(IosPanel.Stage.IDLE, null)
                return@Thread
            }
            log("回放 ${f.absolutePath}（${f.length()} B）")
            runCatching {
                f.inputStream().buffered().use { ins ->
                    var n = 0
                    var sent = 0
                    while (true) {
                        val hdr = ByteArray(4)
                        if (ins.read(hdr) != 4) break
                        // 落盘时用的是 QtPacket.u32（**小端**），这里必须按小端读回
                        val total = ((hdr[3].toInt() and 0xFF) shl 24) or ((hdr[2].toInt() and 0xFF) shl 16) or
                            ((hdr[1].toInt() and 0xFF) shl 8) or (hdr[0].toInt() and 0xFF)
                        if (total <= 0 || total > (8 shl 20)) break
                        val payload = ByteArray(total)
                        var off = 0
                        while (off < total) {
                            val r = ins.read(payload, off, total - off)
                            if (r <= 0) break
                            off += r
                        }
                        if (off < total) break
                        n++
                        SampleParser.csd(payload)?.let { onCsdArrived(it) }
                        SampleParser.frame(payload)?.let { fr ->
                            decoder?.submit(fr)
                            sent++
                        }
                        if (sent % 30 == 0) Thread.sleep(16)
                        if (sent >= 600) break
                    }
                    log("回放结束：包 $n / 送解码 $sent")
                }
            }.onFailure { log("回放异常：${it.message}") }
            running.set(false)
            panel.setMirroring(false)
            panel.render(IosPanel.Stage.IDLE, null)
        }, "replay").start()
    }

    /**
     * 收到（新的）SPS/PPS。
     *
     * 第一次：等 Surface 就绪后起解码器；
     * 之后内容变了（iPad 转屏 ⇒ 分辨率变）：**必须重建解码器**，否则旧 codec 会静默停产。
     */
    private fun onCsdArrived(csd: ByteArray) {
        pendingCsd = csd
        if (decoder == null) ensureDecoder() else decoder?.restart(csd)
    }

    /** Surface 与 SPS/PPS 都到位时启动解码器。 */
    private fun ensureDecoder() {
        val sf = panel.currentSurface() ?: return
        val csd = pendingCsd ?: return
        if (decoder != null) return
        decoder = H264Decoder(sf, ::log) { w, h -> panel.setVideoSize(w, h) }
            .also { it.start(csd) }
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
     *   `ensureDecoder()` 会永远 `return`，画面再也不会出现。音频却是独立通道，
     *   于是表现为"只有声音没有画面"。
     *
     * 为什么挂在 Surface 生命周期上而不是 `onPause`：`surfaceDestroyed` 才是
     * "真的没有画面可画了"的权威信号，而且它已经在驱动渲染开关；
     * 再挂一份 `onPause` 会出现"有时暂停、有时不暂停"的双状态（这条同样来自 AOA 10.22）。
     */
    private fun suspendRendering() {
        if (decoder == null && audio?.isPaused == true) return
        runCatching { decoder?.stop() }
        decoder = null
        audio?.pauseForBackground()
        if (running.get()) log("画面不可见 ⇒ 已停渲染与音频（USB 链路保持）")
    }

    /** Surface 回来了 → 用缓存的 CSD 立刻重建解码器，并恢复音频。 */
    private fun resumeRendering() {
        log("预览 Surface 就绪")
        audio?.resumeFromBackground()
        ensureDecoder()
    }

    private fun log(msg: String) {
        // 同时打到 logcat：UI 文本要 uiautomator dump 才读得到，logcat 一条命令就能跟
        android.util.Log.i("UAV-IosQt", msg)
        // ★ UI 日志只留"能讲故事"的行。
        //   SKEW 应答每秒 4 条、FEED/渲染计数每秒数条 —— 面板只有 50 条缓冲，
        //   几秒就被它们冲干净，真正要看的握手/错误/中断反而被挤没了。
        //   这些高频计数在 logcat 里一条不少，需要逐包对账时看那边。
        if (msg.startsWith("SKEW") || msg.startsWith("FEED #") || msg.startsWith("渲染 ")) return
        panel.log("${logFmt.format(Date())}  $msg")
    }

    /**
     * 让 iPad 信任本机 —— 走一次完整的 lockdownd `Pair`。
     *
     * 这是「不依赖 PC 导凭证」的那一步：配对记录会存进 `filesDir/ios_pair.properties`，
     * 之后 [Identity.load] 优先用它。配对必须在**普通配置**下做，所以这里不 activate。
     *
     * **同步版本**：自动流程要按「配对 → 投屏」的顺序串起来，所以这里不再自己开线程，
     * 由 [autoRun] 的 `ios-auto` 线程调用。返回 true 才继续投屏。
     */
    private fun pairNow(): Boolean {
        val dev = transport.findAppleDevice()
        if (dev == null) {
            log("未找到 Apple 设备（VID 05ac），请先插线")
            return false
        }
        log("配对目标：${dev.deviceName} pid=0x${dev.productId.toString(16)}")
        if (!ensurePermission(dev)) {
            log("未取得 USB 权限，中止")
            return false
        }
        transport.dumpConfigs(dev)

        val pairing = Pairing(transport, filesDir, ::log)
        // 上一轮若留在采集配置，先还原到普通态再配对
        if (dev.configurationCount >= 5) {
            log("设备停在采集配置 ⇒ 先还原到普通配置")
            restoreToNormal(dev)
            Thread.sleep(400)
            val dev2 = transport.findAppleDevice()
            if (dev2 == null) {
                log("还原后设备消失")
                return false
            }
            if (!ensurePermission(dev2)) {
                log("还原后未取得权限")
                return false
            }
            if (pairing.run(dev2) == null) {
                log("配对未完成")
                return false
            }
        } else if (pairing.run(dev) == null) {
            log("配对未完成")
            return false
        }

        // 配对成功后立刻自证一次：StartSession → TLS → GetValue
        val key = currentDeviceKey() ?: Pairing.KEY_UNKNOWN
        val rec = Pairing.loadRecord(filesDir, key, ::log)
        if (rec != null && IosFeatures.TLS_WAIT_MS >= 8_000L) {
            log("自检：用刚配好的身份走一次 StartSession + TLS…")
            verifyTrust(rec.hostId)
        } else if (rec != null) {
            // TLS 窗口被缩短 ⇒ 握手必然来不及完成，自证只会打一行"失败"误导人
            log("（TLS 窗口 ${IosFeatures.TLS_WAIT_MS}ms ⇒ 跳过信任自检；配对结果以上面的写入为准）")
        }
        return Pairing.hasRecord(filesDir, key)
    }

    /**
     * 重新武装 QT 端点：配置 5 → 3 → `activate` → **等设备自己重枚举**。
     *
     * 什么时候需要它：lockdown 已经信任、QT 接口也 claim 到了，但**一个字节都不来**。
     * 这时说明设备侧的 QT 服务没被"启动"——只有 `activate` 让设备走一次 USB 重枚举，
     * 它才会把带 0x2A 的配置**主动交出来**；单纯的配置来回（3→5）只对"刚武装过"的设备有效。
     *
     * ⚠️ activate 之后**绝不能再碰任何配置**，一碰就打断重枚举（踩过：以为没反应，实际是被自己搅黄的）。
     */
    private fun rearm() {
        running.set(true)
        panel.setBusy(true)
        panel.render(IosPanel.Stage.REARM, getString(R.string.ios_detail_rearm_hint))
        Thread({
            runCatching {
                val dev0 = transport.findAppleDevice()
                if (dev0 == null) { log("未找到 Apple 设备，先插线"); return@Thread }
                if (!ensurePermission(dev0)) { log("未取得 USB 权限，中止"); return@Thread }
                log("起始状态：${dev0.deviceName} 描述配置数=${dev0.configurationCount}")

                if (dev0.configurationCount >= 5) {
                    log("先把设备切回普通配置（3），让 activate 有意义…")
                    restoreToNormal(dev0)
                    Thread.sleep(800)
                }
                val dev = transport.findAppleDevice()
                if (dev == null) { log("切配置后设备消失，请重插"); return@Thread }
                if (!ensurePermission(dev)) { log("切配置后未取得权限"); return@Thread }

                val conn = transport.open(dev)
                if (conn == null) { log("openDevice 失败"); return@Thread }
                try {
                    if (!transport.activate(conn)) { log("activate 被拒"); return@Thread }
                } finally {
                    runCatching { conn.close() }
                }
                log("已发 activate，等设备重枚举（最长 240s，期间不要拔线/不要点其他按钮）…")

                // 判据：配置集必须**先离开 5 再回到 5**。只看">=5"会把"没动"误判成"已就绪"。
                val t0 = System.currentTimeMillis()
                var sawLeave = false
                var lastReport = 0L
                var current: UsbDevice = dev
                while (System.currentTimeMillis() - t0 < 240_000) {
                    val d = transport.findAppleDevice()
                    if (d != null) {
                        current = d
                        if (d.configurationCount < 5) sawLeave = true
                        if (sawLeave && d.configurationCount >= 5) {
                            log("★★ 重枚列完成：${d.deviceName} 配置数=${d.configurationCount} ⇒ 现在可以点「开始采集」")
                            return@Thread
                        }
                    }
                    val elapsed = (System.currentTimeMillis() - t0) / 1000
                    if (elapsed - lastReport >= 10) {
                        lastReport = elapsed
                        log("  等待中 ${elapsed}s… ${current.deviceName} 描述配置数=${current.configurationCount}" +
                            if (sawLeave) "（已离开采集态，等它回来）" else "（还没动静）")
                    }
                    Thread.sleep(700)
                }
                if (sawLeave) log("✗ 设备离开采集态后 240s 内没回来")
                else log("✗ 240s 内设备根本没重枚举 ⇒ 只能拔插线或重启 iPad")
            }.onFailure { log("重新武装异常：${it.message}") }
            running.set(false)
            panel.setBusy(false); refreshDeviceInfo(state = "空闲")
        }, "ios-rearm").start()
    }

    /** 用已保存的配对记录走一遍「StartSession → TLS → GetValue」，输出信任结论。 */
    private fun verifyTrust(hostId: String) {
        val dev = transport.findAppleDevice()
        if (dev == null) { log("自检跳过：设备不可见"); return }
        runCatching {
            val rec = Pairing.loadRecord(filesDir, Pairing.deviceKey(dev)) ?: return
            val identity = Identity.fromRecord(rec)
            val cfg = (0 until dev.configurationCount).mapNotNull {
                runCatching { dev.getConfiguration(it) }.getOrNull()
            }.firstOrNull { transport.muxInterfaceIn(it) != null } ?: run {
                log("自检跳过：没有含 usbmux 的配置"); return
            }
            val itf = transport.muxInterfaceIn(cfg) ?: return
            val conn = transport.open(dev) ?: return
            try {
                transport.setConfiguration(conn, cfg)
                if (!transport.claim(conn, itf)) { log("自检：claim 失败"); return }
                val (epIn, epOut) = transport.bulkEndpoints(itf)
                if (epIn == null || epOut == null) { log("自检：端点不全"); return }
                val link = MuxLink(conn, epIn, epOut, identity, ::log)
                if (link.openRaw()) {
                    link.establishSession(identity)
                    val devKey = Pairing.deviceKey(dev)
                    if (link.trusted) {
                        log("★★ 自检通过：设备已信任 HostID=$hostId，可以点「开始采集」")
                        PairStore.confirm(filesDir, devKey, ::log)
                        // 顺手补记版本（纯诊断；老记录没这个字段，补上不用重新配对）
                        PairStore.recordVersion(filesDir, devKey, link.deviceVersion)
                    } else {
                        log("✗ 自检未通过：TLS=${link.tlsReady} 原因=${link.failed ?: "未取得 Value"}")
                        PairStore.dropProvisional(filesDir, devKey, ::log)
                    }
                } else {
                    log("✗ 自检隧道失败：${link.failed}")
                }
                link.shutdown("自检结束")
                runCatching { transport.release(conn, itf) }
            } finally {
                runCatching { conn.close() }
            }
        }.onFailure { log("自检异常：${it.message}") }
    }

    /**
     * 一次「配对 + 投屏」跑完后的统一收尾（成功、失败、被拔线都走这里）。
     *
     * 之所以收在这里而不是散在 [autoRun] 里：**收尾顺序有依赖** ——
     * 必须先取 `abortReason` 再清 `activeSession`，必须先停解码器再改状态文案，
     * 否则「为什么停了」这条信息就丢了。
     */
    private fun finishRun(stats: QtStats?) {
        // 会话给出的中断原因要在清引用之前取出来
        val lostReason = activeSession?.abortReason
        val detached = deviceDetached.get()

        running.set(false)
        activeSession = null
        // ★ 解码器停在最后一帧会让人以为"界面卡死了"：主动收掉，把画面让回黑底。
        //   下次开始时 QtSession 会重新收到 csd，`onCsdArrived` 会自己重建解码器。
        runCatching { decoder?.stop() }
        decoder = null
        pendingCsd = null

        panel.setMirroring(false)
        panel.setBusy(false)
        refreshDeviceInfo(state = "空闲")

        val devicePresent = transport.findAppleDevice() != null
        when {
            // 拔线是**用户的动作**，不是故障：回空闲态、自动展开面板把话说清楚。
            // 投屏期间面板是收起的，若不展开，用户只会看到画面冻住 + 没有任何入口，
            // 那才是真正的"看起来卡死"。
            detached || lostReason != null -> {
                log("=== 已中断：${lostReason ?: "iPad 被拔掉"} ===")
                panel.render(IosPanel.Stage.IDLE, getString(R.string.ios_detail_link_lost))
                panel.setMenuVisible(true)
            }
            stats == null -> {
                // ★ 设备在、但一个帧都没进来 —— 最需要"说清为什么"的失败态。
                //   版本提示只在这里出现：它是**解释**不是**预测**（协议层没有版本差异），
                //   对 iOS 27+ 补一句"可能还需要开发者模式/媒体流授权"，
                //   免得用户以为配对成功就该一定出画面。
                val hint = lastDeviceVersion.failureHint()
                if (hint != null) log("⚠ $hint")
                panel.render(
                    if (devicePresent) IosPanel.Stage.ERROR else IosPanel.Stage.IDLE,
                    if (devicePresent) hint else getString(R.string.ios_detail_no_device),
                )
            }
            else -> panel.render(IosPanel.Stage.IDLE, null)
        }
        if (stats != null) {
            log("=== 结束：PING ${stats.ping} / CWPA ${stats.cwpa} / CVRP ${stats.cvrp} / " +
                "FEED ${stats.feed}（${stats.feedBytes} B） / EAT ${stats.eat}（${stats.audioBytes} B）" +
                " / 共 ${stats.bytes} B ===")
        }
    }

    private fun capture(): QtStats? {
        // 1) 找设备
        var dev = transport.findAppleDevice()
        if (dev == null) {
            log("未找到 Apple 设备（VID 05ac）。请确认线已连到宿主。")
            return null
        }
        log("设备 ${dev.deviceName} pid=0x${dev.productId.toString(16)}")

        // 2) 权限
        if (!ensurePermission(dev)) {
            log("未取得权限，中止")
            return null
        }

        // 3) ★ 若设备仍停在采集配置（上次会话的残留），**先复原再激活**。
        //    这就是「每次都要重启 iPad」的根因：设备一直留在配置 5，activate 就成了空操作。
        //    iPhoneMirror 每次收流结束都会做 usb_configuration_restore，所以它能反复开。
        if (dev.configurationCount >= 5) {
            // ★ 复用上次会话的关键：把配置从 5 切回 3，再切回 5 —— 设备侧的 QT 会话就重新武装了。
            //   实测（14:30）这一步之后 PING 又来了两次，**完全不用重启 iPad**。
            //   不用等重枚举：设备不会重枚举，配置来回一次就够（约 0.3s）。
            log("设备停在采集配置（${dev.configurationCount} 个配置 = 上次会话残留）⇒ 配置 3→5 重新武装")
            restoreToNormal(dev)
            Thread.sleep(300)
            log("  已切回普通配置，准备重新进入采集配置")
        }

        // 4) 设备**不在采集态**就 activate 并等重枚举。
        //    ⚠️ 判据必须是「配置集里有几个配置」，不能是 `findCaptureInterface()`：
        //    后者扫的是扁平表（所有配置的接口并集），永远能找到 0x2A ⇒ activate 从来没被真正触发过。
        if (dev.configurationCount < 5) {
            val conn0 = transport.open(dev) ?: return null
            try {
                if (!transport.activate(conn0)) {
                    log("activate 失败，中止")
                    return null
                }
            } finally {
                runCatching { conn0.close() }
            }
            log("已请求激活，等待设备重枚举（最长 300s；实测可能延迟 2~3 分钟）…")
            dev = waitForCaptureMode(300)
            if (dev == null) {
                log("✗ 300s 内没有出现采集接口。设备需要物理重启（解锁亮屏）。")
                return null
            }
        } else {
            log("设备已在采集态（${dev.configurationCount} 个配置），跳过激活")
        }

        // 4) 重枚举后要重新拿权限
        if (!ensurePermission(dev)) {
            log("重枚举后未取得权限，中止")
            return null
        }

        // 5) 先把配置切到承载 0x2A 的那个（等价 Windows 的 set_configuration(5)）。
        //    只切这一次 —— 之后建 lockdown 会话、claim QT 都不再动配置。
        transport.dumpConfigs(dev)
        val cfg = transport.captureConfiguration(dev)
        if (cfg == null) {
            log("没有任何配置包含 0xFF/0x2A 采集接口，中止")
            return null
        }
        val itf = transport.captureInterfaceIn(cfg)
        if (itf == null) {
            log("配置 id=${cfg.id} 里找不到 0xFF/0x2A 接口，中止")
            return null
        }
        val conn = transport.open(dev) ?: return null
        if (!transport.setConfiguration(conn, cfg)) {
            log("切换配置失败")
            runCatching { conn.close() }
            return null
        }

        // 6) 先建 lockdown 会话（可选）
        //    ★ 身份按设备取：配对记录是"主机 × 设备"这一对的关系，换设备各用各的
        val identity = Identity.load(this, ::log, Pairing.deviceKey(dev))
        var connMux: UsbDeviceConnection? = null
        var muxLink: MuxLink? = null
        val keepAlive = java.util.concurrent.atomic.AtomicBoolean(true)
        var muxThread: Thread? = null
        val itfMux = if (!useLockdown) {
            log("★ 按 iPhoneMirror 的做法跳过 lockdown，直接 claim QT")
            null
        } else transport.muxInterfaceIn(cfg)
        if (itfMux == null) {
            log("⚠ 配置 id=${cfg.id} 里没有 usbmux 接口（0xFF/0xFE/2），跳过 lockdown 会话")
        } else {
            val cm = transport.open(dev)
            if (cm == null) {
                log("⚠ mux 连接 openDevice 失败")
            } else {
                connMux = cm
                if (!transport.claim(cm, itfMux)) {
                    log("⚠ claim usbmux 接口失败")
                } else {
                    val (mi, mo) = transport.bulkEndpoints(itfMux)
                    if (mi == null || mo == null) {
                        log("⚠ usbmux 接口端点不全")
                    } else {
                        log("建立 lockdown 会话（if#${itfMux.id}）…")
                        val link = MuxLink(cm, mi, mo, identity, ::log)
                        muxLink = link
                        if (link.open(IosFeatures.TLS_WAIT_MS)) {
                            log("★★ lockdown 会话在线（TLS=${link.tlsReady} 信任=${link.trusted}），开始保活")
                            lastDeviceVersion = link.deviceVersion
                            // ★ 归属校验：「暂时接管」的旧版记录要靠**真的信任成功**才能转正。
                            //   TLS 窗口被缩短后 `trusted` 恒为 false —— 这时**不能** dropProvisional，
                            //   否则会把刚迁移过来的记录删掉。宁可让它停在"暂管"。
                            if (link.trusted) {
                                PairStore.confirm(filesDir, Pairing.deviceKey(dev), ::log)
                                // 顺手补记版本（纯诊断；变化时才写盘，见 PairStore.recordVersion）
                                PairStore.recordVersion(
                                    filesDir, Pairing.deviceKey(dev), link.deviceVersion,
                                )
                            }
                            muxThread = Thread({
                                while (keepAlive.get() && link.failed == null) {
                                    runCatching { link.pumpOnce(200) }
                                }
                            }, "mux-keepalive").apply { isDaemon = true; start() }
                        } else {
                            log("✗ lockdown 会话未建立：${link.failed}")
                        }
                    }
                }
            }
        }

        // 7) 现在才 claim QT 并开读
        val connQt = conn
        if (!transport.claim(connQt, itf)) {
            log("claim QT 接口失败")
            keepAlive.set(false)
            runCatching { muxLink?.shutdown("claim QT 失败") }
            runCatching { connMux?.close() }; runCatching { connQt.close() }
            return null
        }
        val (epIn, epOut) = transport.bulkEndpoints(itf)
        if (epIn == null) {
            log("接口里没有 bulk IN 端点")
            keepAlive.set(false)
            runCatching { muxLink?.shutdown("无 bulk IN") }
            runCatching { connMux?.close() }; runCatching { connQt.close() }
            return null
        }
        log("QT 接口 if#${itf.id} 已 claim；IN=0x${epIn.address.toString(16)} " +
            "OUT=${epOut?.let { "0x" + it.address.toString(16) } ?: "无"}")
        // 音频若走**另一个** bulk IN，只取第一个 IN 就会永远收不到 EAT —— 先把端点全列出来
        val eps = (0 until itf.endpointCount).map { i ->
            val e = itf.getEndpoint(i)
            "%s0x%02x/%dB".format(
                if (e.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "IN " else "OUT ",
                e.address, e.maxPacketSize
            )
        }
        log("  接口端点 ${itf.endpointCount} 个：${eps.joinToString(" ")}")

        // 8) 收流：**一直投到拔线**（maxSeconds = 0）
        //
        // ★ **默认不落盘**。以前这里无条件把 FEED 原始报文写进 feed.bin，
        //   代价是 ~2 MB/s（一小时 7 GB）外加"写盘失败就把读循环打断"：
        //   `sink.write` 抛 IOException 会一路冒到读循环外面，表现成"投屏莫名断了"。
        //   现在只在 `--ez record true` 时开，而且流是可空的（QtSession 本来就接受 null）。
        val outFile = if (recordFeed && DebugTools.ENABLED) {
            File(getExternalFilesDir(null) ?: filesDir, "feed.bin").also {
                log("★ 录制已开启（adb）：${it.absolutePath}")
            }
        } else null
        val sinkStream = outFile?.outputStream()
        // 音频：第一包 PCM 到才真正建轨道（没声音就不占音频资源）
        val audioPlayer = PcmAudioPlayer(::log)
        audio = audioPlayer
        return try {
            var session: QtSession? = null
            try {
                panel.render(IosPanel.Stage.MIRRORING, null)
                refreshDeviceInfo(state = "投屏中")
                session = QtSession(
                    connQt, epIn, epOut, sinkStream, ::log,
                    onCsd = { csd -> onCsdArrived(csd) },
                    onFrame = { f ->
                        decoder?.submit(f)
                        if (!firstFrameDone.getAndSet(true)) {
                            // 首帧到了：收起面板把画面让出来（药丸仍显示「投屏中」）
                            log("首帧已渲染 ⇒ 收起面板")
                            panel.render(IosPanel.Stage.MIRRORING, null)
                            panel.setMenuVisible(false)
                        }
                    },
                    onAudio = { pcm, fmt -> audio?.feed(pcm, fmt) },
                    requestAudio = requestAudio,
                    // 读循环靠它判断"设备是不是已经不在设备表里了"（拔线检测的权威判据）
                    linkAlive = { transport.findAppleDevice() != null }
                )

                activeSession = session
                // 用短窗口探一下：一字节都没有 = 设备侧没武装，交回上层重新唤醒
                var st = session.run(maxSeconds = 0, idleAbortSeconds = 12)
                // abortReason 非空 = 设备被拔 / 端点失效，此时再"重新武装"毫无意义
                if (st.bytes > 0 || stopRequested || session.abortReason != null) return st

                log("12s 内没有任何数据 ⇒ 用配置 4→5 重新武装一次")
                transport.release(connQt, itf)
                val cfg4 = transport.configurationById(dev, 4)
                if (cfg4 != null) {
                    transport.setConfiguration(connQt, cfg4)
                    Thread.sleep(300)
                }
                if (!transport.setConfiguration(connQt, cfg)) {
                    log("  重新切回配置 ${cfg.id} 失败")
                    return st
                }
                val itf2 = transport.captureInterfaceIn(cfg) ?: itf
                transport.claim(connQt, itf2)
                val (in2, out2) = transport.bulkEndpoints(itf2)
                if (in2 == null) {
                    log("  重新武装后没有 bulk IN")
                    return st
                }
                session = QtSession(
                    connQt, in2, out2, sinkStream, ::log,
                    onCsd = { csd -> onCsdArrived(csd) },
                    onFrame = { f ->
                        decoder?.submit(f)
                        if (!firstFrameDone.getAndSet(true)) {
                            panel.render(IosPanel.Stage.MIRRORING, null)
                            panel.setMenuVisible(false)
                        }
                    },
                    onAudio = { pcm, fmt -> audio?.feed(pcm, fmt) },
                    requestAudio = requestAudio,
                    linkAlive = { transport.findAppleDevice() != null }
                )
                activeSession = session
                st = session.run(maxSeconds = 0, idleAbortSeconds = 12)
                st
            } finally {
                // ★ 收尾必须「还原配置」，否则设备留在配置 5，下次 activate 变空操作
                //   —— 这正是「必须重启 iPad」的真正原因。
                keepAlive.set(false)
                runCatching { muxThread?.interrupt() }
                // ★ 先 FIN 再放接口：设备侧那条 TCP 不主动关的话会一直 ESTABLISHED，
                //   下次插上连 VERSION 都收不到（只能拔插 iPad）。
                runCatching { muxLink?.shutdown("停止投屏") }
                runCatching { itfMux?.let { transport.release(connMux!!, it) } }
                runCatching { transport.release(connQt, itf) }
                runCatching {
                    transport.normalConfiguration(dev, cfg.id)?.let { transport.setConfiguration(connQt, it) }
                }
                runCatching { audioPlayer.stop() }
                audio = null
                runCatching { connMux?.close() }
                runCatching { connQt.close() }
                log("收尾完成：配置已还回普通态，可直接再次开始（无需重启 iPad）")
            }
        } finally {
            // 录制流收尾。默认没开时 sinkStream 是 null，这里就是空操作。
            // ⚠️ 必须显式 close：不关的话最后几帧可能还压在 FileOutputStream 里。
            runCatching { sinkStream?.close() }
        }
    }

    /** 把设备从采集配置切回普通配置（收尾用）。 */
    private fun restoreToNormal(dev: UsbDevice): Boolean {
        val conn = transport.open(dev)
        if (conn == null) {
            log("  复原失败：openDevice 返回 null")
            return false
        }
        return try {
            val target = transport.normalConfiguration(dev, 5)
            if (target == null) {
                log("  复原失败：找不到普通配置")
                false
            } else {
                transport.setConfiguration(conn, target)
            }
        } finally {
            runCatching { conn.close() }
        }
    }

    /** 轮询直到设备不再带采集配置（bNumConfigurations < 5）。 */
    private fun waitForNormalConfig(maxSeconds: Int): UsbDevice? {
        val deadline = System.currentTimeMillis() + maxSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val d = transport.findAppleDevice()
            if (d != null && d.configurationCount < 5) return d
            Thread.sleep(300)
        }
        return null
    }

    /** 轮询直到设备上出现 0xFF/0x2A 采集接口，返回新的 UsbDevice。 */
    private fun waitForCaptureMode(maxSeconds: Int): UsbDevice? {
        val deadline = System.currentTimeMillis() + maxSeconds * 1000L
        var lastTick = 0L
        while (System.currentTimeMillis() < deadline) {
            val d = transport.findAppleDevice()
            if (d != null) {
                // 同样不能用扁平表：配置集从 4 涨到 5 才代表设备真的切进了采集模式
                if (d.configurationCount >= 5) {
                    log("★ 设备已进入采集态：${d.deviceName}（${d.configurationCount} 个配置）")
                    return d
                }
                val now = System.currentTimeMillis()
                if (now - lastTick > 5000) {
                    lastTick = now
                    log("  等待中… 当前 ${d.deviceName}")
                }
            }
            Thread.sleep(300)
        }
        return null
    }

    /** 确保拿到权限；没有就请求并等广播（最多 25 秒）。 */
    private fun ensurePermission(dev: UsbDevice): Boolean {
        if (transport.hasPermission(dev)) return true
        val latch = CountDownLatch(1)
        permissionLatch = latch
        transport.requestPermission(dev)
        val ok = latch.await(25, TimeUnit.SECONDS)
        permissionLatch = null
        // 广播可能丢，最终以 hasPermission 为准
        val has = transport.hasPermission(dev)
        log("权限结果：broadcast=${ok && true} hasPermission=$has")
        return has
    }

    private companion object {
        /**
         * 自动开始的轮询周期。
         *
         * 1s 是实测过的折中：本机型插入不派发 ATTACHED（只能轮询），
         * 而 1s 足够让"插上线 → 画面出来"的感知是连续的；再密只是白耗电。
         */
        const val POLL_MS = 1000L
    }
}
