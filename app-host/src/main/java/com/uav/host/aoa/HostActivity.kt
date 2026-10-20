package com.uav.host.aoa

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.uav.host.R
import com.uav.host.common.AndroidLogger
import com.uav.host.common.DebugTools
import com.uav.host.common.DisplaySize
import com.uav.host.aoa.debug.DebugIntents
import com.uav.host.aoa.debug.FileByteChannel
import com.uav.host.aoa.input.HidTouchController
import com.uav.host.aoa.input.TouchForwarder
import com.uav.host.aoa.link.LinkCoordinator
import com.uav.host.aoa.session.HostSession
import com.uav.host.aoa.ui.HostPanel
import com.uav.host.aoa.usb.BulkPair
import com.uav.host.aoa.usb.UsbAttachRouter
import com.uav.host.aoa.video.QualityController
import com.uav.host.aoa.video.VideoDecoder
import com.uav.protocol.aoa.CloseReason
import com.uav.protocol.aoa.Protocol
import com.uav.protocol.aoa.StreamHealth
import com.uav.protocol.aoa.Transport
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.ui.HostUiState
import com.uav.protocol.w
import java.io.File

/**
 * 宿主投屏页 —— **只做装配与生命周期**（10.24 分层重构）。
 *
 * ## 为什么它现在这么短
 *
 * 拆分前这个类 1262 行，同时干 6 件事：View 渲染、链路编排、画质策略、触摸转发、
 * 调试入口、回放。任何改动都要在脑子里维护全部 6 条线。现在每件事各归其位：
 *
 * | 层 | 类 | 职责 | 不碰 |
 * |---|---|---|---|
 * | View | [HostPanel] | 状态行 / 副行 / 药丸 / 菜单 / 统计 | 链路、画质 |
 * | 链路 | [LinkCoordinator] | 插线 → AOA → 建会话 → 断链自愈 → 轮询退避 | 任何 View |
 * | 画质 | [QualityController] | 1:1↔高清、显示区变化该不该重协商 | View、USB |
 * | 输入 | [TouchForwarder] | `MotionEvent` → 手机触摸 → HID | 链路状态 |
 * | 调试 | [DebugIntents] | adb extra → 动作（release 门控） | 一切实现细节 |
 *
 * 本类剩下的是**它们之间唯一的粘合剂**：生命周期回调、Surface 回调、
 * 状态机（[HostUiState]）的推进、以及"一次重协商"的编排。
 *
 * ## 不变的东西
 *
 * - **实现语义一字未改**：所有阈值、时序、顺序要求（见各层里的注释）都原样搬过来了。
 *   拆分只改"谁住在哪个文件里"。
 * - **调试入口 `--ez scan true` 等**行为不变。
 * - **状态机**（10.23）仍是主状态行的唯一权威。
 */
class HostActivity : Activity() {

    private lateinit var panel: HostPanel
    private lateinit var link: LinkCoordinator
    private lateinit var quality: QualityController
    private lateinit var touch: TouchForwarder
    private lateinit var debugIntents: DebugIntents

    /** 触摸注入控制器。链接失败、Surface 销毁、Activity 结束都会用到它。 */
    private var hidTouch: HidTouchController? = null

    /**
     * 面板状态机（10.23，纯逻辑在 `:protocol/HostUiState`，有 JVM 单测）。
     *
     * ★ **以前状态行是被事件流无条件覆盖的**，而事件里混着"瞬时进度"与"持久状态"两种语义。
     *   现在主状态行只由 [render] 按阶段渲染，进度文本降级为副行信息。
     */
    private val uiState = HostUiState()

    /** 是否向手机请求音频（10.13）。调试入口 `--ez no_audio true` 可关。 */
    private var audioEnabled = true

    /** 音频起播延迟（A/V 粗补偿），可由 `--ei audio_delay_ms N` 调整。 */
    private var audioDelayMs = HostSession.DEFAULT_AUDIO_DELAY_MS

    private val logger = AndroidLogger

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 回放通道（`.uav` 调试模式）；与 USB 会话互斥。 */
    private var replayChannel: FileByteChannel? = null

    private var tickCount = 0L

    /**
     * 流健康度判定（10.30）。纯逻辑在 `:protocol/StreamHealth`，有 JVM 单测。
     *
     * ⚠️ 它要解决的不是"链路断了"（那种有 onClosed/onError），而是**链路好着、对面不动了**：
     *   手机端编码器静默卡死时，以前宿主状态行照旧写「投屏中」，用户只看到画面冻住。
     */
    private val streamHealth = StreamHealth()

    /** 上一拍的健康度与"静默秒数"，用来避免每秒都重绘面板。 */
    private var lastHealthState = StreamHealth.State.HEALTHY
    private var lastSilentSec = -1L

    /** 链路统计的周期刷新。UI 用紧凑形状，日志用完整形状（见 [HostSession.statsCompact]）。 */
    private val statsTicker = object : Runnable {
        override fun run() {
            val s = link.session
            val uiText = when {
                s != null -> s.statsCompact()
                else -> link.bulkStats() ?: "无活动会话"
            }
            panel.setStats(uiText)
            // 每 5 秒把统计打进日志：5 分钟压测（计划 4.10）要靠它读首末对比，
            // 也避免依赖 UI 取值（画面持续刷新时 uiautomator dump 会拿不到稳定帧）。
            // release 下关掉：发布包每 5 秒刷一条 logcat 没有意义（计划 5.2）。
            tickCount++
            if (DebugTools.ENABLED && tickCount % STATS_LOG_EVERY_TICKS == 0L) {
                logger.i(TAG, "STATS ${s?.stats() ?: uiText}")
            }
            checkStreamHealth(s)
            mainHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(
                intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java,
            )
            logger.i(TAG, "detach broadcast: ${intent.action} device=${device?.deviceId}")
            link.onDeviceDetached(device)
            uiState.onDetached("USB 已拔出，请重新插线或点 Scan/Retry")
            render()
            // 这条提示藏在收起的菜单里就等于没提示
            panel.revealMenu()
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            panel.onSurfaceReady()
            logger.i(TAG, "surface created: ${holder.surface}")
            // 优先「原地重建解码器」：链路不动，只把解码器挂到新 Surface 上。
            link.session?.resumeRendering()
            // 会话已经没了（链路错误 / 首次启动）时，才重新建链。
            if (link.session == null) {
                logger.i(TAG, "无活动会话，立即尝试建立链接")
                link.scan()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            logger.i(TAG, "surface changed ${width}x$height")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            panel.onSurfaceGone()
            logger.i(TAG, "surface destroyed")
            // 手指若还按着，必须全部补抬起 —— 否则手机上那几根手指会**一直按住**。
            // （正常路径上 View 会先收到 ACTION_CANCEL，这里只是兜底，`cancelAll()` 幂等。）
            hidTouch?.cancelAll()
            // ★ 只停渲染，**不动链路**。这里踩过一个整进程崩溃和一个重连死锁：
            //
            //   1) 崩溃：`surfaceDestroyed` 先于 `onDestroy`，旧代码只在 `onDestroy`
            //      才停会话 → 这段窗口里解码线程仍在往**刚失效的 Surface** 上渲 →
            //      `CodecException: rendering to non-initialized(obsolete) surface`
            //      → `uav-decode` 线程 FATAL → App 崩。
            //   2) 死锁：如果索性把整个会话停掉，链路一断，手机端会释放配件 fd；
            //      超过它 12s 的空闲看门狗后就不再重开，而手机**本来就在配件模式**时
            //      `START_ACCESSORY` 不会重新枚举、系统也不会重新派发 attach
            //      → 没人再打开那个 fd → 宿主无限「等待手机 CNXN 超时」。
            //
            //   所以正确做法是：**只释放解码器、停止渲染，链路原样保持**。
            //   解码器另有一层兜底（见 `VideoDecoder.drainOutput`）。
            link.session?.suspendRendering()
        }
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)

        // ★ 宿主这块屏就是"显示器"：一息屏画面就没了，也没有人会去点它唤醒。
        //   FLAG_KEEP_SCREEN_ON 只在**本窗口可见时**生效，切后台/锁屏后自动恢复系统的
        //   息屏策略，所以不需要手动 clear（也因此不会拖住整个系统的休眠）。
        //   ⚠️ 它只阻止"息屏"，不改亮度；用户手动按电源键仍然能立即息屏。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        panel = HostPanel(this)
        // ★ 先 bind 再挂 listener / 建TouchForwarder（10.24 崩溃修复）：
        //   bind() 才去找面板里的控件，顺序反了会在 onCreate 里直接崩。
        panel.bind(
            onTogglePixelMode = { quality.setPixelMode(it) },
            onScan = { userScan() },
            onForceRehandshake = { userForceRehandshake() },
            onPickReplay = { pickReplayFile() },
            onGetApp = { openCompanionAppPage() },
            onDisplayAreaChanged = { quality.onDisplayAreaChanged() },
        )
        // ★ 遥控器/D-pad：先给一个初始焦点，否则第一下方向键什么都不发生
        panel.focusFirst()

        hidTouch = HidTouchController(logger)
        touch = TouchForwarder(panel.surfaceView, logger).also { f ->
            f.injector = hidTouch
        }

        // ---- 链路层 ----
        link = LinkCoordinator(
            context = this,
            logger = logger,
            surfaceProvider = { panel.currentSurface() },
            displayArea = { panel.displayArea() },
            oneToOne = { quality.oneToOne },
            audioEnabled = { audioEnabled },
            audioDelayMs = { audioDelayMs },
            listener = linkListener,
        )
        link.isSurfaceReady = { panel.surfaceReady }
        link.onLinkLost = {
            // ⚠️ 触点状态**保留**在控制器里 —— 重连后它会把"手指还按着"补发回去，
            //   避免手机上手指"卡住"。
            // ⚠️ 只调 TouchForwarder 这一处：它内部的 injector 就是 hidTouch，
            //   以前两个都调，日志会重复打两条「作废注入器」。
            touch.onLinkLost()
        }
        link.panelClearVideoSize = { panel.setVideoSize(0, 0) }
        link.onLinkEstablished = { connection -> hidTouch?.onLinkReady(connection) }
        // 注入器自愈的取数口：重连路径上"onLinkLost 清了、却没走到 onLinkReady"时，
        // HidTouchController 会从这里再要一次当前连接（否则触摸静默失效）。
        hidTouch?.connectionProvider = { link.currentConnection }

        // ---- 画质层 ----
        quality = QualityController(
            context = this,
            logger = logger,
            sessionProvider = { link.session },
            displayArea = { panel.displayArea() },
            listener = qualityListener,
        )

        // ---- 调试入口层 ----
        debugIntents = DebugIntents(logger, debugHandler)

        panel.surfaceView.setOnTouchListener { _, event -> touch.onTouch(event) }
        panel.surfaceView.holder.addCallback(surfaceCallback)
        panel.applyPixelModeUi(quality.oneToOne)
        panel.setReplayVisible(DebugTools.ENABLED)
        render()

        logger.i(TAG, "host screen ${DisplaySize.of(this).x}x${DisplaySize.of(this).y}, protocol v${Protocol.VERSION}")
        link.start()
        registerReceiver(detachReceiver, UsbAttachRouter.detachFilter())
        mainHandler.post(statsTicker)

        handleIntent(intent)
    }

    /**
     * 全屏：隐藏状态栏与导航栏。
     *
     * ★ 宿主这块屏是"显示器"，两根系统栏纯属干扰 —— 尤其导航栏会永久吃掉底部一条画面。
     * ★ `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`：从边缘划一下就能临时调出系统栏，
     *   松手自动隐藏 —— 既干净，又不至于"退不出去"。
     * ⚠️ 必须**每次拿到焦点时重新隐藏**：从后台回来、或用户划出系统栏之后，它们会赖着不走。
     *   只在 `onCreate` 设一次是不够的，这是沉浸式全屏最经典的一个坑。
     */
    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    /**
     * ★ 10.24 重构时这一段被漏掉了（真机踩到：`am start --ez hid_tap true` 毫无反应）。
     *
     * 它的作用不只是调试入口 —— App 已在前台时，系统派发的
     * `USB_DEVICE_ATTACHED` / `USB_ACCESSORY_ATTACHED` 也走这里（`launchMode` 单实例）。
     * 少了它，配件插上来只能靠广播接收器与前台轮询兜底。
     *
     * ⚠️ 与手机端相反的约定：这里**不要**无条件重建会话 —— 任何一次 `am start`
     *   都会走到这里（包括调试入口），把那当成"配件重新插上"会白白断链。
     *   真正的重建判据在 [handleIntent] 里（必须带 `EXTRA_DEVICE`，且只对 attach 动作生效）。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(statsTicker)
        runCatching { unregisterReceiver(detachReceiver) }
        link.close()
        closeReplay()
        hidTouch?.close()
        hidTouch = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 链路回调（只汇报，不决策）

    private val linkListener = object : LinkCoordinator.Listener {
        override fun onProgress(text: String) {
            // ★ 10.23：进度文本**不再**直接写主状态行，只进副行 + 由状态机决定主行。
            //   原地重协商时画面根本没断，但这里照样会发「会话已建立，等待视频流…」——
            //   旧代码直接覆盖主行，而 onFirstFrame 不会再来，于是状态被永久钉死。
            uiState.onProgress(text)
            render()
        }

        override fun onVideoSize(width: Int, height: Int) {
            // ★ 10.23：尺寸就绪只用来给"重协商中"收尾，**不能**提前宣告投屏中
            //   （还没出画面就报"投屏中"是同一类错误，只是方向相反）。
            //   同时把副行换成"新参数已生效"：否则重协商结束后副行会一直挂着
            //   「会话已建立，等待视频流…」—— 那句话在画面正在播的时候读起来很别扭。
            val wasRenegotiating = uiState.phase == HostUiState.Phase.RENEGOTIATING
            uiState.onVideoSize()
            if (wasRenegotiating) {
                uiState.onProgress(
                    "已生效：${width}x$height" + if (quality.oneToOne) "（1:1 像素）" else "（高清）"
                )
            }
            render()
            panel.setVideoSize(width, height)
            // ★ 视频尺寸是**触摸坐标空间**的权威线索：手机屏幕的物理尺寸只在 CNXN 报一次，
            //   旋转后不会重报，只能靠视频方向推出它当前的长宽（见 TouchMapper）。
            //   空间变了控制器会自动重新注册 HID（描述符里的 Logical Max 就是空间尺寸）。
            link.session?.let {
                touch.onGeometry(it.peerWidth, it.peerHeight, width, height)
                hidTouch?.updateGeometry(it.peerWidth, it.peerHeight, width, height)
            }
        }

        override fun onFirstFrame() {
            // ★ 10.23：这是**唯一**能宣告"投屏中"的事件（首帧真的输出了）。
            uiState.onFirstFrame()
            render()
            // 画面出来了就把菜单收起来，让视频干净地占满整屏（只自动收一次）。
            panel.autoCollapseOnFirstFrame()
        }

        override fun onClosed(reason: String) {
            // 链路断开不是终态：前台轮询会自动重试，所以回"连接中"而非"空闲"。
            //
            // ★ 10.32：用户主动停止要和"意外断线"分开。前者要在界面上把原因**钉住**
            //   （否则紧接着的自动重连会把"用户停止投屏"刷成"CNXN 已发送…"），
            //   后者照旧随进度走。判据用协议层的同一个常量，两端不会失配。
            if (reason.contains(CloseReason.USER_STOP)) {
                uiState.onPeerStopped()
            } else {
                uiState.onClosed(reason)
            }
            render()
            panel.revealMenu()
        }

        override fun onError(message: String, cause: Throwable?) {
            // ★ 出错必须把菜单叫回来：面板收起时错误信息是看不见的，
            //   用户只会看到"画面不动"，无从判断是拔线还是授权被拒。
            uiState.onError(message)
            render()
            panel.revealMenu()
            logger.e(TAG, message, cause)
            // 链路层已负责 stopSession / 退避计数 / 自愈判定，这里只管"让用户可见"。
        }

        /**
         * 对端在配件模式下没人应答（典型：手机端没装配套 App）。
         *
         * 走独立回调而不是 [onError]：这不是链路故障，等自动重连永远不会成功，
         * 界面要给的是**可操作的去处**（下载按钮），而不是"错误"两个字。
         */
        override fun onPeerAppMissing() {
            uiState.onPeerAppMissing(getString(R.string.host_detail_peer_app_missing))
            render()
            // 面板收起时这条引导是看不见的，必须主动叫回来
            panel.revealMenu()
        }

        override fun onSelfHealRequest(device: UsbDevice) {
            uiState.onProgress("快路径未验证通过，强制重握手（改写配件身份）…")
            render()
            link.stopSession()
            link.onDeviceAttached(device)
        }
    }

    // ------------------------------------------------------------------ 配套 App

    /**
     * 打开配套 App 的下载页（GitHub Releases 最新版）。
     *
     * 为什么要兜底剪贴板：宿主可能是**机顶盒 / 车机**，没有浏览器或没有默认 handler，
     * `startActivity` 会抛 `ActivityNotFoundException`。那种情况下把链接塞进剪贴板，
     * 至少用户还能拷到别处——比什么都不发生强。
     *
     * ⚠️ 这里打开的地址与 AOA `SEND_STRING[4]`（`AccessoryIdentity.URI`）是同一个，
     *   一处改、两处要同步（一个是给手机系统看，一个是给用户看）。
     */
    private fun openCompanionAppPage() {
        val url = getString(R.string.host_app_url)
        val ok = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.isSuccess
        if (ok) {
            logger.i(TAG, "已打开配套 App 下载页：$url")
            return
        }
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("UsbScreenMirror", url))
            android.widget.Toast.makeText(this, R.string.host_link_copied, android.widget.Toast.LENGTH_LONG)
                .show()
        }.onFailure { logger.w(TAG, "复制下载链接失败：${it.message}") }
        logger.w(TAG, "没有可用的浏览器，已把下载链接放进剪贴板：$url")
    }

    // ------------------------------------------------------------------ 画质回调

    private val qualityListener = object : QualityController.Listener {
        override fun onQualityChange(oneToOne: Boolean, reason: String) {
            uiState.onRenegotiate()
            uiState.onProgress(reason)
            render()
            panel.applyPixelModeUi(oneToOne)
            // ⚠️ 必须**先**打"预期内重开"标志，**再**发起重协商：
            //   一旦 renegotiate() 已返回 false 并开始回退重连，那期间的失败就不再是"预期内"的。
            link.beginExpectedRestart()
            link.requestRenegotiate()
        }
    }

    // ------------------------------------------------------------------ 用户动作

    private fun userScan() {
        uiState.onScanRequested()
        render()
        link.scan()
    }

    private fun userForceRehandshake() {
        uiState.onProgress("强制 AOA 重握手中…")
        render()
        link.scan(forceHandshake = true)
    }

    // ------------------------------------------------------------------ 调试入口

    private val debugHandler = object : DebugIntents.Handler {
        override fun onReplay(path: String, file: File) {
            if (!file.exists()) {
                linkListener.onError("回放文件不存在: $path（可先用 run-as 放进 filesDir）", null)
                return
            }
            startReplay(file)
        }

        override fun onScan() {
            uiState.onScanRequested()
            render()
            link.scan()
        }

        override fun onForceHandshake() {
            uiState.onProgress("强制 AOA 重握手中…")
            render()
            link.scan(forceHandshake = true)
        }

        override fun onHidProbe() {
            hidTouch?.forceReRegister()
        }

        override fun onHidTap(x: Int, y: Int, x2: Int, y2: Int, holdMs: Int): Boolean =
            touch.debugTap(x, y, x2, y2, holdMs)

        override fun onAudioTuning(noAudio: Boolean?, audioDelayMs: Int?) {
            if (noAudio != null) {
                audioEnabled = !noAudio
                logger.i(TAG, "音频已${if (audioEnabled) "启用" else "关闭"}（调试参数）")
            }
            if (audioDelayMs != null) {
                this@HostActivity.audioDelayMs = audioDelayMs.coerceIn(0, 2_000)
                logger.i(TAG, "音频起播延迟 = ${this@HostActivity.audioDelayMs}ms（调试参数）")
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        // 先消化调试入口 extra（release 构建下整块被跳过，计划 5.2）。
        // ⚠️ 音频参数**不接管 intent**，所以无论是否命中别的动作都要应用。
        debugIntents.applyAudioTuning(intent)
        debugIntents.parse(intent)?.let { action ->
            debugIntents.dispatch(action)
            return
        }

        val action = intent?.action ?: return
        logger.i(TAG, "intent action=$action")
        if (action != UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED
        ) {
            return
        }
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        if (device == null) {
            logger.w(TAG, "attach intent 但没有 EXTRA_DEVICE")
            return
        }
        link.onDeviceAttached(device)
    }

    // ------------------------------------------------------------------ 回放（纯调试入口，计划 4.0）

    private fun pickReplayFile() {
        if (!DebugTools.ENABLED) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_PICK_UAV)
    }

    @Deprecated("plain Activity onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_UAV || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val dest = File(cacheDir, "replay.uav")
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "openInputStream returned null" }
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            startReplay(dest)
        } catch (t: Throwable) {
            linkListener.onError("读取抓流文件失败: ${t.message}", t)
        }
    }

    private fun startReplay(file: File) {
        // 最后一道闸：即使调用方漏了门控，release 下也不会走进回放通道（计划 5.2）
        if (!DebugTools.ENABLED) {
            logger.w(TAG, "release 构建不支持 .uav 回放（请用 debug 包）: ${file.name}")
            return
        }
        link.stopSession()
        // 不回环：抓流里的"流头"只在该流首条 WRTE 有效，循环第二遍时这 12B 会被当成
        // 一帧解析而报错。要看第二遍就再触发一次回放。
        // 配速默认开启（按帧 PTS 还原真实速度），否则会把深度 4 的队列冲爆。
        val channel = FileByteChannel(file, logger, paceByPts = true)
        replayChannel = channel
        val transport = Transport(channel, logger, TAG)
        val screen = DisplaySize.of(this)

        val s = HostSession(
            transport = transport,
            localWidth = screen.x,
            localHeight = screen.y,
            surfaceProvider = { panel.currentSurface() },
            logger = logger,
            replay = true,
            appVersion = com.uav.host.BuildConfig.VERSION_CODE,
            listener = replayListener,
            // 回放也要有个作用域：握手流程在 replay 下会直接返回，但构造参数是必填的
            scope = link.sessionScope,
        )
        link.adoptReplaySession(s)
        s.start()
        uiState.onReplay("回放：${file.name}（${channel.totalBytes} 字节）")
        render()
    }

    /**
     * 回放会话的回调：**只接进度**，其余事件一律忽略。
     *
     * ⚠️ 回放模式下没有链路可言，报"已断开 / 出错"会让状态机说谎；
     *   画面与统计照常由 `HostSession` 自己驱动。
     */
    private val replayListener = object : HostSession.Listener {
        override fun onStatus(text: String) {
            uiState.onProgress(text)
            render()
        }

        override fun onError(message: String, cause: Throwable?) {
            linkListener.onError(message, cause)
        }
    }

    private fun closeReplay() {
        replayChannel?.close()
        replayChannel = null
    }

    // ------------------------------------------------------------------ 渲染

    /** 把状态机画到面板上（唯一的主状态行渲染入口）。 */
    private fun render() {
        panel.render(uiState)
    }

    /**
     * 每秒评估一次流健康度（10.30）。
     *
     * 只在**状态或秒数真的变了**才推进状态机并重绘 —— 否则每秒 render 一次，
     * 面板会持续重排（10.26 那个"反复显示隐藏"的教训：UI 动作要由真实变化驱动）。
     */
    private fun checkStreamHealth(session: HostSession?) {
        val now = SystemClock.elapsedRealtime()
        val lastAt = session?.lastVideoFrameAtMs ?: 0L
        val state = streamHealth.evaluate(now, lastAt)
        val silentMs = streamHealth.silentMs(now, lastAt)
        val silentSec = silentMs / 1000

        val changed = state != lastHealthState ||
            (state == StreamHealth.State.STALLED && silentSec != lastSilentSec) ||
            (state == StreamHealth.State.STOPPED && silentSec != lastSilentSec)
        if (!changed) return

        lastHealthState = state
        lastSilentSec = silentSec
        uiState.onStreamHealth(state, silentMs)
        render()
        if (DebugTools.ENABLED) {
            logger.w(TAG, "流健康：$state，已 ${silentSec}s 无新帧（判定阈值 stall=${StreamHealth.DEFAULT_STALL_MS}ms stop=${StreamHealth.DEFAULT_STOP_MS}ms）")
        }
    }

    companion object {
        const val TAG = "UAV-Host"
        const val REQ_PICK_UAV = 1001
        const val STATS_INTERVAL_MS = 1_000L
        const val STATS_LOG_EVERY_TICKS = 5L
    }
}