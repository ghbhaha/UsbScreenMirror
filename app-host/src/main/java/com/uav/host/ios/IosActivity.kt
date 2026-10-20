package com.uav.host.ios

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.atomic.AtomicBoolean
import com.uav.host.common.DebugTools
import com.uav.host.ios.capture.IosDecoderHost
import com.uav.host.ios.capture.IosFeatures
import com.uav.host.ios.capture.IosUsbPermission
import com.uav.host.ios.capture.Pairing
import com.uav.host.ios.capture.QtSession
import com.uav.host.ios.capture.QtTransport
import com.uav.host.ios.flow.IosAutoFlow
import com.uav.host.ios.flow.IosCaptureFlow
import com.uav.host.ios.flow.IosCaptureHost
import com.uav.host.ios.flow.IosCaptureListener
import com.uav.host.ios.flow.IosPairingFlow
import com.uav.host.ios.run.IosAutoStarter
import com.uav.host.ios.run.IosCaptureRun
import com.uav.host.ios.run.IosRearmer
import com.uav.host.ios.run.IosReplay
import com.uav.host.ios.ui.IosPanel
import com.uav.host.R
import com.uav.protocol.ios.QtStats

/**
 * 最小宿主：插上 iPad 即自动配对并投屏，拔线即停。
 *
 * ## 边界（各职责都搬出去了，这里只剩"生命周期 + 界面事实 + 失败归因"）
 *
 * | 谁 | 管什么 |
 * |---|---|
 * | 本类 | 生命周期、广播、自动开始轮询、重新武装、回放、失败归因、界面复位 |
 * | [IosAutoFlow] | 「配对（如需要）→ 投屏」这条串行流程 |
 * | [IosPairingFlow] | 配对 + 自证信任（挂起、无界面） |
 * | [IosCaptureFlow] | 激活 → claim → lockdown → 收流 → USB 收尾 |
 * | [IosDecoderHost] | CSD/解码器/Surface、音频、日志 |
 * | [IosUsbPermission] | USB 授权（挂起等广播） |
 * | [IosPanel] | 纯 View 层（写方法内部已切主线程） |
 *
 * 每个 flow 只通过**窄接口**（[IosCaptureHost] / [IosCaptureListener]）回调界面事实，
 * 不反向引用本类的方法 —— 这样加一个流程不需要回来改这里。
 *
 * ## 为什么停止仍然是"标志位"而不是 `Job.cancel()`
 *
 * `bulkTransfer` 不可中断：协程取消打不断卡在 `usb_start_wait_urb` 的读调用。
 * 所以停止信号走 [stopRequested] → `QtSession.stopSignal` → 读循环下一轮自己退出，
 * 收尾（发 `HPA0`/`HPD0`、还配置）全在 [IosCaptureFlow.capture] 的 finally 里。
 * 协程只负责"有个明确的归属"，不负责打断 USB。
 */
class IosActivity : ComponentActivity(), IosCaptureHost {

    private lateinit var usb: UsbManager
    private lateinit var transport: QtTransport
    private lateinit var panel: IosPanel

    // ── 子流程（onCreate 里建，因为它们需要 transport / panel 先就位）──────────
    private lateinit var permission: IosUsbPermission
    private lateinit var decoderHost: IosDecoderHost
    private lateinit var pairingFlow: IosPairingFlow
    private lateinit var captureFlow: IosCaptureFlow
    private lateinit var autoFlow: IosAutoFlow
    private lateinit var rearmer: IosRearmer
    private lateinit var replayFlow: IosReplay
    private lateinit var autoStarter: IosAutoStarter

    /**
     * 「一次运行」的登记与收尾（运行标志的复位、面板复位全在这里）。
     *
     * 不是 lateinit：它不依赖 transport，而且 [stopProjection] / [onDestroy] 可能在
     * 流程起过之前就被调到。
     */
    private val run = IosCaptureRun(lifecycleScope, ::decodeLog) {
        panel.setMirroring(false)
        panel.setBusy(false)
        refreshDeviceInfo(state = "空闲")
    }

    /** 日志出口只有 [decoderHost] 一处（它同时负责 logcat 与页内日志）—— 但它 lateinit。 */
    private fun decodeLog(msg: String) {
        if (::decoderHost.isInitialized) decoderHost.log(msg) else Log.w(IosDecoderHost.TAG, msg)
    }

    /** 用户点了「停止投屏」/ 返回键 / 拔线 —— 读循环每轮问一次。 */
    @Volatile
    private var stopRequested = false

    /** 当前正在跑的会话 —— 停止信号要能立刻打断循环里的 bulkTransfer。 */
    @Volatile
    private var activeSession: QtSession? = null

    /**
     * 收流期间 iPad 被拔掉过。
     *
     * 用来把「用户拔线」和「链路出错」区分开：前者回空闲态并提示插回，
     * 后者才是红药丸。混成一种，用户会以为是自己把程序弄坏了。
     */
    private val deviceDetached = AtomicBoolean(false)

    /** 自动开始用的前台轮询（见 [autoPoller]）。只在页面可见时跑。 */
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var polling = false

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

    /** Surface 消失时用来判断"要不要打那句日志"（没有解码器也没暂停 = 本来就没画面）。 */
    @Volatile
    private var renderingSuspended = false

    // ─────────────────────────────────────────────────────── IosCaptureHost

    override fun isStopRequested(): Boolean = stopRequested

    override fun recordFeed(): Boolean = recordFeed

    override fun onFirstFrame(): Boolean {
        // 首帧到了：收起面板把画面让出来（药丸仍显示「投屏中」）。
        // 「只处理一次」由 IosCaptureFlow 计数，这里纯粹是动作。
        panel.setMenuVisible(false)
        return true
    }

    // ───────────────────────────────────────────────────── 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        setContentView(R.layout.activity_ios)
        // ★ 必须在 setContentView 之后构造：IosPanel 在构造期就 findViewById
        panel = IosPanel(this)
        panel.bind(
            onSurface = { ready -> onSurfaceReady(ready) },
            onRearm = { if (!run.isRunning()) rearm() },
            // ★ 停止：只置停止信号，真正的收尾在 capture() 的 finally 里做。
            //   这里**不要**直接 close/杀线程 —— 那会跳过发 HPA0/HPD0 与还配置，
            //   下次 activate 就成了空操作（= 必须重启 iPad）。
            onStop = { stopProjection() }
        )
        // 投屏期间别让屏幕自己熄掉（熄屏会连带把 Surface 收走）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // ★ 遥控器/D-pad：先给药丸一个焦点，否则第一下方向键没有"起点"
        panel.focusFirst()

        // ★ 只传 applicationContext：传 `this`(Activity) 会让这个持有者活过 Activity，
        //   字段名叫 appContext 就更像个谎话（审查报告点名的那条）。
        transport = QtTransport(application as Application, usb) { msg -> decoderHost.log(msg) }
        permission = IosUsbPermission(usb)
        decoderHost = IosDecoderHost(panel)
        autoStarter = IosAutoStarter(
            findDevice = { transport.findAppleDevice() },
            isBusy = { run.isRunning() },
            log = { decoderHost.log(it) },
            onDetected = { autoRun() },
        )
        replayFlow = IosReplay(
            dir = getExternalFilesDir(null) ?: filesDir,
            log = { decoderHost.log(it) },
            onCsd = { decoderHost.onCsdArrived(it) },
            onFrame = { decoderHost.onFrame(it) },
        )
        pairingFlow = IosPairingFlow(
            transport = transport,
            filesDir = filesDir,
            log = { decoderHost.log(it) },
            ensurePermission = ::ensurePermission,
            restoreToNormal = ::restoreToNormal,
        )
        captureFlow = IosCaptureFlow(
            transport = transport,
            host = this,
            log = { decoderHost.log(it) },
            ensurePermission = ::ensurePermission,
            onCsd = { decoderHost.onCsdArrived(it) },
            onFrame = { f -> decoderHost.onFrame(f) },
            onAudio = { pcm, fmt -> decoderHost.onAudio(pcm, fmt) },
            onAudioPlayer = { decoderHost.attachAudio(it) },
            onSession = { activeSession = it },
            listener = IosCaptureListener { phase ->
                when (phase) {
                    IosCaptureListener.Phase.LOCKDOWN_UP -> {
                        // 阶段可见化：lockdown 建好后可能还要等设备武装，界面得说清"到哪一步了"
                        refreshDeviceInfo(state = "lockdown 在线")
                    }
                    IosCaptureListener.Phase.MIRRORING -> {
                        panel.render(IosPanel.Stage.MIRRORING, null)
                        panel.setMirroring(true)
                        refreshDeviceInfo(state = "投屏中")
                    }
                }
            },
        )
        // 自动流程：配对 → 投屏。它只碰三个子流程，界面事实全部经 listener 回到这边。
        autoFlow = IosAutoFlow(
            filesDir = filesDir,
            log = { decoderHost.log(it) },
            pairingFlow = pairingFlow,
            captureFlow = captureFlow,
            deviceKey = ::currentDeviceKey,
            onStage = { stage -> onAutoStage(stage) },
        )
        rearmer = IosRearmer(
            transport = transport,
            log = { decoderHost.log(it) },
            ensurePermission = ::ensurePermission,
            restoreToNormal = ::restoreToNormal,
            isStopRequested = { stopRequested },
        )
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

        logPairingRecords()
        applyDebugExtras()
    }

    override fun onResume() {
        super.onResume()
        // 页面可见时才开始"插上即投"的轮询：后台跑它没有意义，还会误触发
        polling = true
        handler.removeCallbacks(autoPoller)
        handler.post(autoPoller)
    }

    /**
     * 退到后台不是"用户要停止"。
     *
     * 链路必须保持：点位资源有限，切一次后台就重连会让画面闪、也会让设备反复
     * 重枚举。画面这里本来就由 `surfaceDestroyed` 收掉了（见 [onSurfaceReady]）。
     * 停止只走三条路：返回键（[onDestroy]）、拔线、停止按钮。
     */
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
        //   ★ 这两行是**唯一**的停止通道：此刻 activeSession 可能已经被
        //   finishRun 清空，所以 stopRequested 这一行不能省（2026-10-19 实测踩过）。
        activeSession?.stopRequested = true
        stopRequested = true
        super.onDestroy()
    }

    // ───────────────────────────────────────────────────── 自动开始

    /**
     * 插上就自动开跑；页面可见时才转。
     *
     * 判定规则全在 [IosAutoStarter]（含"本机型不派发 ATTACHED 只能轮询"这条结论），
     * 这里只负责按周期叫它。
     */
    private val autoPoller = object : Runnable {
        override fun run() {
            if (!polling) return
            autoStarter.tick()
            handler.postDelayed(this, IosAutoStarter.POLL_MS)
        }
    }

    /**
     * 全自动流程：**配对（如需要）→ 投屏**。
     *
     * 没有「开始」按钮了，所以进度与失败原因全靠主行/副行 + 日志说清楚。
     */
    private fun autoRun() {
        if (!run.tryStart()) return
        stopRequested = false
        deviceDetached.set(false)
        panel.setMirroring(true)
        panel.render(IosPanel.Stage.CONNECTING, null)
        refreshDeviceInfo(state = "启动中")

        run.launch("ios-auto") {
            var stats: QtStats? = null
            runCatching {
                stats = autoFlow.run()
            }.onFailure {
                decoderHost.log("自动流程异常：${it.javaClass.simpleName}: ${it.message}")
            }
            finishRun(stats)
        }
    }

    /**
     * [IosAutoFlow] 把"现在该显示什么"报上来，翻译成面板状态。
     *
     * 流程类不认识 [IosPanel.Stage]，界面文案的取舍留在这边 —— 这样改文案不用碰 USB 代码。
     */
    private fun onAutoStage(stage: IosAutoFlow.Stage) {
        when (stage) {
            IosAutoFlow.Stage.PAIRING ->
                panel.render(IosPanel.Stage.PAIRING, getString(R.string.ios_detail_pair_hint))
            IosAutoFlow.Stage.PAIRING_SKIPPED ->
                decoderHost.log("配对未完成，本次不投屏 —— 拔插一次或点「重新唤醒」可再试")
        }
    }

    // ───────────────────────────────────────────────────── 启动参数 / 记录

    /**
     * 把「本机已经记得几台 iPad」说清楚。
     *
     * 配对记录**按设备**分开存：每台一条，互不覆盖 —— 换回旧机器不用再点一次信任。
     * 这条日志只在启动时打一次，是排查「为什么又让我点信任」的第一现场。
     */
    private fun logPairingRecords() {
        decoderHost.log("QuickTime USB 采集（Android）—— 插上 iPad 即自动配对并投屏，拔线即停")
        refreshDeviceInfo(state = "空闲")
        panel.render(IosPanel.Stage.IDLE, null)
        val known = Pairing.knownDeviceKeys(filesDir)
        when {
            known.isNotEmpty() -> decoderHost.log(
                "配对记录：已记住 ${known.size} 台设备（${known.joinToString()}）" +
                    "—— 换设备只在新设备上点一次「信任」"
            )
            Pairing.hasLegacyRecord(filesDir) ->
                decoderHost.log("配对记录：旧版单文件 ⇒ 插上 iPad 后会自动迁移成按设备存放")
            else -> decoderHost.log("配对记录：无 ⇒ 首次会自动发起配对，请留意 iPad 上的「信任」弹窗")
        }
    }

    /**
     * 读取 adb 调试开关：录制 / 回放。
     *
     * ★ 两样都只在 adb 上开，界面**不留入口**（[DebugTools.ENABLED] 为 false 的版本里
     *   这两个 extra 完全不生效）：
     * ```
     * am start -n com.uav.host/.ios.IosActivity --ez record true
     * am start -n com.uav.host/.ios.IosActivity --ez replay true
     * ```
     */
    private fun applyDebugExtras() {
        recordFeed = DebugTools.ENABLED &&
            (intent?.getBooleanExtra(DebugTools.IOS_EXTRA_RECORD, false) ?: false)
        replayRequested = DebugTools.ENABLED &&
            (intent?.getBooleanExtra(DebugTools.IOS_EXTRA_REPLAY, false) ?: false)
        if (recordFeed) decoderHost.log("录制开关：开（adb --ez record true），会写 feed.bin")
        if (replayRequested) {
            decoderHost.log("回放开关：开（adb --ez replay true）")
            // 放到 onCreate 之后：replay 会置运行标志，随后的轮询看到它就不会抢着建链
            handler.post { replay() }
        }
    }

    // ───────────────────────────────────────────────────── 界面事实

    /**
     * 刷新「设备」卡片。
     *
     * 只写事实：设备名 / 配置数（4=普通态、5=采集态）/ 配对状态。
     * 这行是排「0 字节」时最有用的一条 —— 比翻日志快。
     *
     * ★ 用的是**设备表**里的配置数（`configurationCount`），不是"当前活动配置"：
     *   采集态的标志是设备多长出一个配置，而不是切换了活动配置。
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
                .append(
                    if (dev.configurationCount >= IosFeatures.CAPTURE_CONFIG_COUNT) {
                        "（采集态）"
                    } else {
                        "（普通态）"
                    }
                ).append('\n')
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

    // ───────────────────────────────────────────────────── Surface

    /**
     * Surface 来了/走了 → 解码与音频跟着走，**链路不动**。
     *
     * 判据只有 Surface 一个（权威信号），不像从前那样再挂一份 `onPause` ——
     * 两份来源会打架，出现"有时暂停、有时不暂停"的双状态。
     */
    private fun onSurfaceReady(ready: Boolean) {
        if (ready) {
            renderingSuspended = false
            decoderHost.resumeRendering()
        } else {
            decoderHost.suspendRendering()
            if (!renderingSuspended && run.isRunning()) {
                renderingSuspended = true
                decoderHost.log("画面不可见 ⇒ 已停渲染与音频（USB 链路保持）")
            }
        }
    }

    // ───────────────────────────────────────────────────── 停止 / 重新武装

    /**
     * 用户点了「停止投屏」。
     *
     * ★ **只置停止信号**，其余一概不做。理由：收尾必须走 capture() 的 finally ——
     *   那里才会发`HPA0`/`HPD0`、放接口、把配置还回普通态、发 TCP FIN。
     *   在这里直接关线程会跳过全部这些，下次 activate 变空操作（必须重启 iPad）。
     *
     * ★ 运行标志**不在这里清**：清掉之后轮询会立刻认为空闲并重新拉起一次投屏，
     *   而旧会话还在收尾（两个会话抢同一台设备）。清标志统一在
     *   [IosCaptureRun] 的 finally 里做，那里才是"这一趟真的结束了"。
     */
    private fun stopProjection() {
        if (!run.isRunning()) {
            decoderHost.log("停止投屏：当前没有在投屏")
            return
        }
        stopRequested = true
        // 读循环的外部判据（见 QtSession.stopSignal）—— 这一行才是真正让会话停下来的
        activeSession?.stopRequested = true
        panel.setBusy(false)
        panel.render(IosPanel.Stage.IDLE, getString(R.string.ios_status_idle))
        decoderHost.log("用户请求停止投屏（已置停止信号，等待读循环退出并收尾）")
    }

    /**
     * 重新武装 QT 端点。流程本体在 [IosRearmer]，这里只做状态与界面。
     */
    private fun rearm() {
        // ★ 必须复位 stopRequested ——
        //   「停止投屏」把它置成 true 后，若不复位，本次 rearm 的等待循环
        //   会在第一轮就退出（"收到停止信号，提前退出等待"），按钮等于白按。
        //   autoRun() 也有同样一行，两条入口都要复位。
        stopRequested = false
        run.markRunning()
        panel.setBusy(true)
        panel.render(IosPanel.Stage.REARM, getString(R.string.ios_detail_rearm_hint))
        run.launch("ios-rearm") {
            runCatching { rearmer.run() }
                .onFailure { decoderHost.log("重新武装异常：${it.message}") }
        }
    }

    // ───────────────────────────────────────────────────── 离线回放

    /** 离线回放 feed.bin：验证「解析 → 解码 → 上屏」整条链路。流程本体在 [IosReplay]。 */
    private fun replay() {
        run.markRunning()
        panel.setMirroring(true)
        panel.render(IosPanel.Stage.REPLAY, "回放 filesDir/feed.bin")
        run.launch("replay") {
            replayFlow.play()
            panel.render(IosPanel.Stage.IDLE, null)
        }
    }

    // ───────────────────────────────────────────────────── 收尾与判定

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
        val aborted = captureFlow.aborted

        // 运行标志已由 IosCaptureRun 在 finally 里无条件复位（见 IosCaptureRun.launch）。

        // ★ 这里**不能**立刻 activeSession = null —— 它是 onDestroy 唯一的停止通道：
        //   `activeSession?.stopRequested = true` 走到这里已经是空操作，读循环收不到信号。
        //   capture() 的 finally 已经跑完（收尾已完成），所以此刻置位不会有副作用。
        //   真正的置 null 放到最后（return 之前），保持"字段非空 = 会话还在"的语义。
        // ★ 解码器停在最后一帧会让人以为"界面卡死了"：主动收掉，把画面让回黑底。
        //   下次开始时 QtSession 会重新收到 csd，`onCsdArrived` 会自己重建解码器。
        decoderHost.releaseDecoder()

        // ★ 因为**设备侧原因**失败（设备还在但没数据 / 端点坏了）⇒ 复位自动开始基线，
        //   用户的心智是"拔插一下重来"，而基线不复位的话拔了再插也不会自动开始。
        //   ⚠️ 但**设备还插着**时绝不能复位：下一轮 tick（1 秒）会立刻重开，
        //   失败 → 复位 → 重开 = 自动重试死循环（旧代码如此：端点坏掉后会一直重连）。
        //   设备真不在了的话，`IosAutoStarter.tick` 的 `onAbsent()` 本来就会复位，
        //   这里只是在"刚拔掉、设备表已空"的窗口里补一刀，避免漏掉。
        val devicePresent = transport.findAppleDevice() != null
        if (aborted && !devicePresent) autoStarter.resetBaseline()

        panel.setMirroring(false)
        panel.setBusy(false)
        refreshDeviceInfo(state = "空闲")

        when {
            // 拔线是**用户的动作**，不是故障：回空闲态、自动展开面板把话说清楚。
            // 投屏期间面板是收起的，若不展开，用户只会看到画面冻住 + 没有任何入口，
            // 那才是真正的"看起来卡死"。
            detached || lostReason != null -> {
                decoderHost.log("=== 已中断：${lostReason ?: "iPad 被拔掉"} ===")
                panel.render(IosPanel.Stage.IDLE, getString(R.string.ios_detail_link_lost))
                panel.setMenuVisible(true)
            }
            stats == null -> {
                // ★ 设备在、但一个帧都没进来 —— 最需要"说清为什么"的失败态。
                //   版本提示只在这里出现：它是**解释**不是**预测**（协议层没有版本差异），
                //   对 iOS 27+ 补一句"可能还需要开发者模式/媒体流授权"，
                //   免得用户以为配对成功就该一定出画面。
                val hint = captureFlow.failureHint()
                if (hint != null) decoderHost.log("⚠ $hint")
                panel.render(
                    if (devicePresent) IosPanel.Stage.ERROR else IosPanel.Stage.IDLE,
                    if (devicePresent) hint else getString(R.string.ios_detail_no_device),
                )
            }
            else -> panel.render(IosPanel.Stage.IDLE, null)
        }
        if (stats != null) {
            decoderHost.log(
                "=== 结束：PING ${stats.ping} / CWPA ${stats.cwpa} / CVRP ${stats.cvrp} / " +
                    "FEED ${stats.feed}（${stats.feedBytes} B） / EAT ${stats.eat}（${stats.audioBytes} B）" +
                    " / 共 ${stats.bytes} B ==="
            )
        }
        // 收尾全部做完才清空会话引用 —— 见上面那条注释：早清会让 onDestroy 的停止信号落空
        activeSession = null
    }

    // ───────────────────────────────────────────────────── USB 设备操作

    /**
     * 把设备从采集配置切回普通配置（配对 / 重新武装 / 采集开始前都要用）。
     *
     * @return 切成功了没有 —— 调用方（尤其 [IosRearmer]）要靠它决定还要不要 activate。
     */
    private fun restoreToNormal(dev: UsbDevice): Boolean {
        val conn = transport.open(dev)
        if (conn == null) {
            decoderHost.log("  复原失败：openDevice 返回 null")
            return false
        }
        return try {
            val target = transport.normalConfiguration(dev, IosFeatures.CAPTURE_CONFIG_COUNT)
            if (target == null) {
                decoderHost.log("  复原失败：找不到普通配置")
                false
            } else {
                transport.setConfiguration(conn, target)
            }
        } finally {
            runCatching { conn.close() }
        }
    }

    /**
     * 确保拿到权限（**挂起**）。
     *
     * ★ 以前这里是 `runBlocking { permission.await(...) }`：为了保住三层调用方的同步形状，
     *   硬占住一条 IO 线程最多 25 秒。现在 [IosPairingFlow] / [IosCaptureFlow] / [IosRearmer]
     *   整条链路都已经是挂起函数，`runBlocking` 这一层就多余了 —— 它本来只是为了
     *   "不想改调用方"，而调用方已经改完了。
     */
    private suspend fun ensurePermission(dev: UsbDevice): Boolean {
        val ok = permission.await(dev, PERMISSION_TIMEOUT_MS) {
            transport.requestPermission(dev)
        }
        decoderHost.log("权限结果：hasPermission=$ok")
        return ok
    }

    // ───────────────────────────────────────────────────── 广播

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != QtTransport.ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            decoderHost.log("权限广播：granted=$granted")
            permission.onBroadcastReceived(granted)
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
            decoderHost.log("USB 拔出广播：${dev?.deviceName ?: "(未携带设备描述符)"}")
            deviceDetached.set(true)
            activeSession?.stopRequested = true
        }
    }

    private companion object {
        /**
         * 等 USB 权限广播的上限。
         *
         * 25s 是给用户的反应时间：授权弹窗要人在 iPad/手机上点一下，
         * 太短会在慢手用户身上误判成"没权限"，太长会让自动流程卡死四分之一分钟。
         */
        const val PERMISSION_TIMEOUT_MS = 25_000L

        // 采集态判据（配置数 4 → 5）统一在 IosFeatures.CAPTURE_CONFIG_COUNT，
        // 轮询周期统一在 IosAutoStarter.POLL_MS —— 这里不再各留一份。
    }
}
