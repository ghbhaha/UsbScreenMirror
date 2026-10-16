package com.uav.host

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.IntentCompat
import com.uav.host.debug.FileByteChannel
import com.uav.host.input.HidTouchController
import com.uav.host.session.HostSession
import com.uav.host.usb.BulkByteChannel
import com.uav.host.usb.BulkPair
import com.uav.host.usb.UsbAttachRouter
import com.uav.host.video.AspectRatioSurfaceView
import com.uav.protocol.Protocol
import com.uav.protocol.TouchMapper
import com.uav.protocol.Transport
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w
import java.io.File

/**
 * 宿主投屏页（计划 2.12 / 4.7）。
 *
 * 职责很薄：把 UI 与三个部件接起来
 *  - [UsbAttachRouter]：插线 → 权限 → AOA 握手 → 等重枚举 → claim 接口
 *  - [HostSession]：CNXN / OPEN / OKAY 编排 + 收流 + 解码渲染
 *  - 状态与统计文本：联调全靠它看进度（计划 2.12）
 *
 * 另外提供 **`.uav` 回放**入口（计划 4.0）：跳过 USB 与握手直接把抓流喂进同一套会话代码。
 */
class HostActivity : Activity(), UsbAttachRouter.Callback, HostSession.Listener {

    private lateinit var surfaceView: AspectRatioSurfaceView
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView

    private var router: UsbAttachRouter? = null
    private var session: HostSession? = null
    private var bulkChannel: BulkByteChannel? = null
    private var replayChannel: FileByteChannel? = null

    /**
     * 触摸注入控制器（路线图 P1「输入通道」）：把**宿主屏幕上的触摸**转发成手机上的触摸。
     *
     * 坐标换算在 `:protocol/TouchMapper`（纯逻辑 + 单测），本类只负责
     * 「从 MotionEvent 取点 → 换算 → 交给控制器」这段接线。
     */
    private var hidTouch: HidTouchController? = null

    /**
     * 是否已经向手机发出过 `DOWN`。
     *
     * 没有 DOWN 的 MOVE/UP **一律不发** —— 否则手机上会出现幽灵触摸（手指根本没按下去）。
     */
    private var touchActive = false

    @Volatile
    private var surfaceReady = false

    /** 最近一次 attach 的设备，供"自愈"重试用。 */
    @Volatile
    private var lastDevice: UsbDevice? = null

    /** 本次连接是否走了"跳过握手"的快路径（`aoaVersion == 0` 即表示跳过）。 */
    @Volatile
    private var fastPathUnverified = false

    /** 是否已经为本次连接做过一次强制重握手，避免死循环。 */
    @Volatile
    private var forceRetried = false

    /**
     * 连续建链失败次数（成功一次即归零），用于轮询退避。
     *
     * 用原子类而不是普通 int：`onError` 可能从会话线程过来（`HostSession.fail`），
     * 而 `onReady` 与轮询都在主线程。
     */
    private val consecutiveFailures = java.util.concurrent.atomic.AtomicInteger()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickCount = 0L

    private val statsTicker = object : Runnable {
        override fun run() {
            val s = session
            val text = when {
                s != null -> s.stats()
                bulkChannel != null -> bulkChannel!!.stats()
                else -> "no active session"
            }
            statsText.text = text
            // 每 5 秒把统计打进日志：5 分钟压测（计划 4.10）要靠它读首末对比，
            // 也避免依赖 UI 取值（画面持续刷新时 uiautomator dump 会拿不到稳定帧）。
            // release 下关掉：发布包每 5 秒刷一条 logcat 没有意义（计划 5.2）。
            tickCount++
            if (DebugTools.ENABLED && tickCount % STATS_LOG_EVERY_TICKS == 0L) {
                logger.i(TAG, "STATS $text")
            }
            mainHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    /**
     * 前台轮询兜底：实测宿主设备（BOE P40UCH）在手机重枚举后**只发 DETACHED、不发 ATTACHED**，
     * 只靠 `USB_DEVICE_ATTACHED` 广播会让"插线即投"彻底失效。
     * 因此在 Activity 可见期间定时扫一遍设备列表，发现没试过的就自动握手。
     *
     * ⚠️ 必须同时要求 **Surface 就绪**：没有 Surface 时握手也白搭
     *   （`handleConfigFrame` 会等不到 Surface 而失败），而且每次握手都会让手机**重新枚举**
     *   USB —— 切后台时反复重枚举纯属浪费，还会把自动重试配额耗掉。
     */
    private val usbPoller = object : Runnable {
        override fun run() {
            if (session == null && surfaceReady) {
                runCatching { router?.pollOnce() }
                    .onFailure { logger.w(TAG, "pollOnce 异常: ${it.message}") }
            }
            mainHandler.postDelayed(this, nextPollDelayMs())
        }
    }

    /**
     * 轮询间隔：连续失败时**指数退避**（2s → 4s → 8s → 16s → 30s 封顶）。
     *
     * 为什么要退避：设备一直在、但握手一直失败时（例如它已被别的宿主 App 占成孤儿链路），
     * 固定 2 秒轮询会无休止重试；退避能在**不放弃**的前提下把空转降到最低。
     * 建链成功 后 [consecutiveFailures] 归零，立刻回到最快档。
     */
    private fun nextPollDelayMs(): Long =
        (POLL_INTERVAL_MS shl consecutiveFailures.get().coerceIn(0, MAX_POLL_BACKOFF_SHIFT))
            .coerceAtMost(MAX_POLL_INTERVAL_MS)

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            logger.i(TAG, "detach broadcast: ${intent.action} device=${device?.deviceId}")
            onDetached(device)
        }
    }

    private val logger = AndroidLogger

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceReady = true
            logger.i(TAG, "surface created: ${holder.surface}")
            // 优先「原地重建解码器」：链路不动，只把解码器挂到新 Surface 上。
            session?.resumeRendering()
            // 会话已经没了（链路错误 / 首次启动）时，才重新建链。
            if (session == null) {
                logger.i(TAG, "无活动会话，立即尝试建立链接")
                router?.scanAndAttach()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            logger.i(TAG, "surface changed ${width}x$height")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            logger.i(TAG, "surface destroyed")
            // 手指若还按着，必须补一个抬起 —— 否则手机上那根手指会**一直按住**。
            // （正常路径上 View 会先收到 ACTION_CANCEL，这里只是兜底，`up()` 自身幂等。）
            touchActive = false
            hidTouch?.up()
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
            session?.suspendRendering()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)

        surfaceView = findViewById(R.id.surface)
        statusText = findViewById(R.id.status)
        statsText = findViewById(R.id.stats)

        surfaceView.holder.addCallback(surfaceCallback)

        // 触摸注入：把宿主屏幕上的触摸转发成手机上的触摸（路线图 P1）。
        hidTouch = HidTouchController(logger)
        surfaceView.setOnTouchListener { _, event -> handleSurfaceTouch(event) }

        val screen = DisplaySize.of(this)
        logger.i(TAG, "host screen ${screen.x}x${screen.y}, protocol v${Protocol.VERSION}")

        // Scan / Retry 与 Force handshake 是**功能**而非调试入口
        // （手机被别的宿主 App 切成配件模式时，用户需要它们自救），release 保留。
        findViewById<Button>(R.id.btn_scan).setOnClickListener { router?.scanAndAttach() }
        findViewById<Button>(R.id.btn_force).setOnClickListener {
            stopSession()
            forceRetried = true
            fastPathUnverified = false
            router?.scanAndAttach(forceHandshake = true)
        }

        // `.uav` 回放是**纯调试入口**（计划 5.2）：release 包连按钮都不显示。
        findViewById<Button>(R.id.btn_replay).apply {
            if (DebugTools.ENABLED) {
                setOnClickListener { pickReplayFile() }
            } else {
                visibility = View.GONE
            }
        }

        router = UsbAttachRouter(this, logger, this).also { it.start() }

        registerReceiver(detachReceiver, UsbAttachRouter.detachFilter())

        mainHandler.post(statsTicker)
        mainHandler.postDelayed(usbPoller, POLL_INTERVAL_MS)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // 先消化调试入口 extra（release 构建下整块被跳过，计划 5.2）
        if (handleDebugIntent(intent)) return

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
        stopSession()
        forceRetried = false
        router?.onDeviceAttached(device)
    }

    /**
     * 处理 adb 驱动的调试 extra（计划 5.2）。
     *
     * 这三条都是为「不碰屏幕就能驱动联调」而加的，**release 包必须全部失效** ——
     * 否则发布版仍可被 `--es replay_path` 读任意本地文件、被 `--ez force_handshake`
     * 强制打断正常链路。
     *
     * @return `true` 表示 extra 命中并已接管本次 intent，调用方不应再按 action 分发。
     */
    private fun handleDebugIntent(intent: Intent?): Boolean {
        if (!DebugTools.ENABLED) {
            // release 下若仍传了调试 extra，明确说一句"被门控忽略"。
            // 否则调用方只会看到"extra 毫无反应"，无从判断是漏实现还是被门控（计划 5.2）。
            if (intent != null && (
                    intent.hasExtra(EXTRA_REPLAY_PATH) ||
                        intent.hasExtra(EXTRA_SCAN) ||
                        intent.hasExtra(EXTRA_FORCE_HANDSHAKE) ||
                        intent.hasExtra(EXTRA_HID_PROBE) ||
                        intent.hasExtra(EXTRA_HID_TAP) ||
                        intent.hasExtra(EXTRA_HOLD_MS)
                    )
            ) {
                logger.w(TAG, "release 构建：调试入口已关闭，忽略 intent extra")
            }
            return false
        }

        // am start -n com.uav.host/.HostActivity --es replay_path /data/data/com.uav.host/files/replay.uav
        //   从指定文件回放抓流（计划 4.0），跳过整个 USB 与握手。
        intent?.getStringExtra(EXTRA_REPLAY_PATH)?.takeIf { it.isNotEmpty() }?.let { path ->
            logger.i(TAG, "收到回放指令: $path")
            val file = File(path)
            if (!file.exists()) {
                onError("回放文件不存在: $path（可先用 run-as 放进 filesDir）", null)
                return true
            }
            startReplay(file)
            return true
        }

        // am start -n com.uav.host/.HostActivity --ez scan true
        //   走普通扫描（已是配件则复用直连），等价于点界面上的 Scan / Retry 按钮。
        if (intent?.getBooleanExtra(EXTRA_SCAN, false) == true) {
            logger.i(TAG, "收到扫描指令")
            stopSession()
            forceRetried = false
            router?.scanAndAttach(forceHandshake = false)
            return true
        }

        // am start -n com.uav.host/.HostActivity --ez force_handshake true
        //   强制走完整 AOA 握手，用于"手机已被别的宿主 App 切成配件模式"的场景。
        if (intent?.getBooleanExtra(EXTRA_FORCE_HANDSHAKE, false) == true) {
            logger.i(TAG, "收到强制重握手指令")
            stopSession()
            forceRetried = true
            fastPathUnverified = false
            router?.scanAndAttach(forceHandshake = true)
            return true
        }

        // ---- HID 触摸注入的排障入口（正常情况下**自动注册**，不需要这些）----
        //
        // am start -n com.uav.host/.HostActivity --ez hid_probe true
        //   强制重新注册触摸屏（排查"手机上没出现触摸设备"用）。
        if (intent?.getBooleanExtra(EXTRA_HID_PROBE, false) == true) {
            logger.i(TAG, "收到指令：强制重新注册 HID 触摸屏")
            hidTouch?.forceReRegister()
            return true
        }

        // am start -n com.uav.host/.HostActivity --ez hid_tap true [--ei tap_x X --ei tap_y Y --ei hold_ms Ms]
        //   在**手机坐标**上发一次点按，不用真去点宿主屏幕就能验证注入链路（不传坐标用安全位置）。
        if (intent?.getBooleanExtra(EXTRA_HID_TAP, false) == true) {
            val xArg = intent.getIntExtra(EXTRA_TAP_X, Int.MIN_VALUE)
            val yArg = intent.getIntExtra(EXTRA_TAP_Y, Int.MIN_VALUE)
            val hold = intent.getIntExtra(EXTRA_HOLD_MS, DEFAULT_TAP_HOLD_MS)
            debugTap(xArg, yArg, hold)
            return true
        }

        return false
    }

    // ------------------------------------------------------------------ 触摸注入（宿主 → 手机）

    /**
     * 宿主屏幕上的触摸 → 手机。
     *
     * 规则：
     *  - 只有落在**视频显示区**内的触摸才转发（信箱边忽略，见 [TouchMapper]）；
     *  - 维护"已经发出 DOWN"的状态：没有 DOWN 的 MOVE/UP 一律不发，否则手机上会出现
     *    幽灵触摸（明明没按下去却响应了）；
     *  - 手指拖出显示区 → 立刻等价于抬起，避免手机上的手指"卡"在边缘。
     *
     * 多点触摸暂不支持（只跟 pointer 0），见路线图 P2。
     */
    private fun handleSurfaceTouch(event: MotionEvent): Boolean {
        val s = session ?: return false
        val viewW = surfaceView.width
        val viewH = surfaceView.height
        val screenW = s.peerWidth
        val screenH = s.peerHeight
        if (viewW <= 0 || viewH <= 0 || screenW <= 0 || screenH <= 0) return false

        val point = TouchMapper.mapTouch(
            viewX = event.getX(0).toInt(),
            viewY = event.getY(0).toInt(),
            viewW = viewW,
            viewH = viewH,
            videoW = surfaceView.videoWidth,
            videoH = surfaceView.videoHeight,
            screenW = screenW,
            screenH = screenH,
        )

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (point == null) {
                    // 落在信箱边/显示区外：不转发。日志有助于判断"为什么点了没反应"
                    if (DebugTools.ENABLED) {
                        logger.i(
                            TAG,
                            "触摸忽略：视图内 (${event.getX(0).toInt()},${event.getY(0).toInt()}) " +
                                "不在显示区内（view=${viewW}x$viewH video=" +
                                "${surfaceView.videoWidth}x${surfaceView.videoHeight}）",
                        )
                    }
                    touchActive = false
                    false
                } else {
                    if (DebugTools.ENABLED) {
                        logger.i(
                            TAG,
                            "触摸转发：视图内 (${event.getX(0).toInt()},${event.getY(0).toInt()})" +
                                " / view=${viewW}x$viewH video=" +
                                "${surfaceView.videoWidth}x${surfaceView.videoHeight}" +
                                " 手机屏=${screenW}x$screenH → 手机 (${point.x},${point.y})",
                        )
                    }
                    touchActive = true
                    hidTouch?.down(point.x, point.y)
                    true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touchActive) return false
                if (point == null) {
                    // 拖到信箱边/显示区外：等价于抬指，否则手机上会一直按着
                    touchActive = false
                    hidTouch?.up()
                } else {
                    hidTouch?.move(point.x, point.y)
                }
                true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                if (!touchActive) return false
                touchActive = false
                hidTouch?.up()
                true
            }

            else -> false
        }
    }

    /**
     * 在**手机坐标**上发一次点按（排障用）。
     *
     * 不传坐标时落在一个**安全位置**（屏幕上方 6% 处的正中，通常是状态栏下方的空白区），
     * 避免误触手机上的按钮（手机上就有"停止投屏"这种一按就断链的按钮）。
     */
    private fun debugTap(xArg: Int, yArg: Int, holdMs: Int) {
        val w = session?.peerWidth ?: 0
        val h = session?.peerHeight ?: 0
        if (w <= 0 || h <= 0) {
            logger.w(TAG, "HID 点按：还没收到手机的 CNXN（拿不到屏幕尺寸）")
            return
        }
        val size = TouchMapper.logicalPhoneSize(
            w, h, surfaceView.videoWidth, surfaceView.videoHeight,
        ) ?: return
        val x = if (xArg == Int.MIN_VALUE) size.width / 2 else xArg
        val y = if (yArg == Int.MIN_VALUE) (size.height * 0.06f).toInt() else yArg
        logger.i(TAG, "HID 点按指令：手机坐标 ($x,$y) 按住 ${holdMs}ms（逻辑空间 ${size.width}x${size.height}）")
        hidTouch?.debugTap(x, y, holdMs)
    }

    // ------------------------------------------------------------------ router 回调

    override fun onStatus(text: String) {
        // 会话真正建立起来 -> 快路径已验证通过，撤销自愈待办
        if (text.contains("会话已建立")) fastPathUnverified = false
        runOnUiThread { statusText.text = text }
    }

    override fun onReady(
        connection: android.hardware.usb.UsbDeviceConnection,
        pair: BulkPair,
        aoaVersion: Int,
        device: UsbDevice,
    ) {
        logger.i(TAG, "link ready: aoaVersion=$aoaVersion device=${device.deviceId}")

        // 建链成功 -> 退避归零，轮询回到最快档
        consecutiveFailures.set(0)

        lastDevice = device
        // aoaVersion == 0 表示这次是按"已是配件、跳过握手"的快路径连的，
        // 配件身份未被验证过（可能还是别的宿主 App 留下的）
        fastPathUnverified = aoaVersion == 0

        // ⚠️ 顺序要求：`stopSession()` 里会 `hidTouch.onLinkLost()`（作废旧连接），
        //    所以它必须排在下面"绑定新连接"**之前** —— 反过来的话刚绑上就被清掉了，
        //    表现为触摸屏永远注册不上（踩过）。
        stopSession()

        // 触摸注入绑定到**本次连接**。★ 旧的 HID 注册随重枚举一起失效（id 只在连接内有效），
        //   这里清零 id，等拿到视频尺寸（= 手机屏幕尺寸 + 视频方向都已知）时重新注册。
        touchActive = false
        hidTouch?.onLinkReady(connection)

        val channel = BulkByteChannel(connection, pair, logger)
        // ★ 先把上一次会话残留在 FIFO 里的字节丢掉，再开新会话。
        //   否则第一个 payload 会被当成流头解析，静默配错解码器（实测踩到过）。
        channel.drainStaleInput()
        bulkChannel = channel
        val transport = Transport(channel, logger, TAG)

        val screen = DisplaySize.of(this)
        val s = HostSession(
            transport = transport,
            localWidth = screen.x,
            localHeight = screen.y,
            surfaceProvider = ::currentSurface,
            logger = logger,
            listener = this,
        )
        session = s
        s.start()
    }

    override fun onError(message: String, cause: Throwable?) {
        runOnUiThread { statusText.text = "ERROR: $message" }
        logger.e(TAG, message, cause)

        // ★ 自动重连（P0）：链路错误后**必须**把会话清掉 ——
        //   前台轮询的接管条件是 `session == null`，不清就永远不会自动重试
        //   （加自动重连之前，`readFully timeout` 之类断链后只能靠用户点按钮）。
        //   注意 stopSession 里有 UI 调用（setVideoSize），只能放主线程。
        runOnUiThread { stopSession() }
        consecutiveFailures.incrementAndGet()
        // 断线是"新情况"（设备可能马上回来），重置自动重试计数；
        // 否则几次偶发断线就会耗光 MAX_ATTEMPTS_PER_DEVICE 配额，自动重连永久失效
        router?.resetAttempts()

        // ★ 自愈：快路径（跳过握手）连上的链路如果没人应答，说明配件身份不是我们的
        //   —— 手机很可能被"别的宿主 App"切成了配件模式，系统因此没有拉起我们的手机端 App。
        //   这时强制走一次完整 AOA 握手，把配件身份改写成 UAV/usbscreen。
        val dev = lastDevice
        if (fastPathUnverified && !forceRetried && dev != null) {
            forceRetried = true
            fastPathUnverified = false
            logger.w(TAG, "快路径失败（$message）→ 改为强制完整 AOA 握手重试一次")
            runOnUiThread { statusText.text = "重试：强制 AOA 重握手（改写配件身份）…" }
            session?.close()
            session = null
            router?.onDeviceAttached(dev, forceHandshake = true)
        }
    }

    /** 设备拔出：与读写异常统一走这里（计划 2.10）。 */
    private fun onDetached(device: UsbDevice?) {
        stopSession()
        statusText.text = "USB 已拔出，请重新插线或点 Scan/Retry"
    }

    // ------------------------------------------------------------------ session 回调

    override fun onVideoSize(width: Int, height: Int) {
        runOnUiThread { surfaceView.setVideoSize(width, height) }
        // ★ 视频尺寸是**触摸坐标空间**的权威线索：手机屏幕的物理尺寸只在 CNXN 报一次，
        //   旋转后不会重报，只能靠视频方向推出它当前的长宽（见 TouchMapper）。
        //   空间变了控制器会自动重新注册 HID（描述符里的 Logical Max 就是空间尺寸）。
        val s = session ?: return
        hidTouch?.updateGeometry(s.peerWidth, s.peerHeight, width, height)
    }

    override fun onFirstFrame() {
        runOnUiThread { statusText.text = "投屏中（首帧已输出）" }
    }

    override fun onClosed(reason: String) {
        runOnUiThread { statusText.text = "已断开：$reason" }
    }

    // ------------------------------------------------------------------ 回放

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
            onError("读取抓流文件失败: ${t.message}", t)
        }
    }

    private fun startReplay(file: File) {
        // 最后一道闸：即使调用方漏了门控，release 下也不会走进回放通道（计划 5.2）
        if (!DebugTools.ENABLED) {
            logger.w(TAG, "release 构建不支持 .uav 回放（请用 debug 包）: ${file.name}")
            return
        }
        stopSession()
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
            surfaceProvider = ::currentSurface,
            logger = logger,
            listener = this,
            replay = true,
        )
        session = s
        s.start()
        statusText.text = "回放：${file.name}（${channel.totalBytes} 字节）"
    }

    // ------------------------------------------------------------------ 生命周期

    private fun currentSurface(): Surface? =
        if (surfaceReady) surfaceView.holder.surface else null

    private fun stopSession() {
        // 链路没了：旧 HID 注册随之失效。逻辑触点状态交给控制器保留 ——
        // 重连后它会把"手指还按着"补发回去，避免手机上手指"卡住"。
        touchActive = false
        hidTouch?.onLinkLost()
        session?.close()
        session = null
        replayChannel?.close()
        replayChannel = null
        bulkChannel = null
        surfaceView.setVideoSize(0, 0)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(statsTicker)
        mainHandler.removeCallbacks(usbPoller)
        runCatching { unregisterReceiver(detachReceiver) }
        router?.close()
        router = null
        stopSession()
        hidTouch?.close()
        hidTouch = null
        super.onDestroy()
    }

    companion object {
        const val TAG = "UAV-Host"
        const val REQ_PICK_UAV = 1001
        const val STATS_INTERVAL_MS = 1000L
        const val STATS_LOG_EVERY_TICKS = 5L
        const val POLL_INTERVAL_MS = 2_000L

        /** 轮询退避的上限（连续失败会 2s→4s→8s… 最长等这么久再试）。 */
        const val MAX_POLL_INTERVAL_MS = 30_000L

        /** 退避最多翻几番（2s 左移 4 位 = 32s，会被上面那条封到 30s）。 */
        const val MAX_POLL_BACKOFF_SHIFT = 4

        /** adb 调试入口用的 extra：`--es replay_path <绝对路径>`。 */
        const val EXTRA_REPLAY_PATH = "replay_path"

        /** adb 调试入口用的 extra：`--ez force_handshake true`。 */
        const val EXTRA_FORCE_HANDSHAKE = "force_handshake"

        /** adb 调试入口用的 extra：`--ez scan true`。 */
        const val EXTRA_SCAN = "scan"

        /** adb 调试入口用的 extra：`--ez hid_probe true`（强制重新注册触摸屏）。 */
        const val EXTRA_HID_PROBE = "hid_probe"

        /** adb 调试入口用的 extra：`--ez hid_tap true [--ei tap_x N --ei tap_y M --ei hold_ms ms]`。 */
        const val EXTRA_HID_TAP = "hid_tap"

        const val EXTRA_TAP_X = "tap_x"
        const val EXTRA_TAP_Y = "tap_y"

        /** 点按的按住时长（毫秒）。留证时拉长，普通点按用默认值。 */
        const val EXTRA_HOLD_MS = "hold_ms"

        /** 排障点按的默认按住时长：够手机侧产生明确的按下/抬起两条事件。 */
        const val DEFAULT_TAP_HOLD_MS = 80
    }
}
