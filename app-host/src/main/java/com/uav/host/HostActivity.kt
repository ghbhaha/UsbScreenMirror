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
import android.view.SurfaceHolder
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.IntentCompat
import com.uav.host.debug.FileByteChannel
import com.uav.host.session.HostSession
import com.uav.host.usb.BulkByteChannel
import com.uav.host.usb.BulkPair
import com.uav.host.usb.HidInjector
import com.uav.host.usb.UsbAttachRouter
import com.uav.host.video.AspectRatioSurfaceView
import com.uav.protocol.HidTouchPanel
import com.uav.protocol.Protocol
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
     * HID 注入器（路线图 P1「输入通道」的探针）。
     *
     * 绑定到**本次连接的** `UsbDeviceConnection`：重枚举后旧连接即失效，
     * 所以每次 [onReady] 都会重建，并清掉已注册的 HID id。
     */
    private var hidInjector: HidInjector? = null

    /** 已注册的 HID 设备 id；0 = 未注册（拔线/重枚举后需要重新注册）。 */
    @Volatile
    private var hidDeviceId = 0

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

        // ---- HID 注入探针（路线图 P1 输入通道；先只做"单点绝对坐标触摸屏"）----
        //
        // am start -n com.uav.host/.HostActivity --ez hid_probe true
        //   注册一个 HID 触摸屏设备。注册成功后手机上 `dumpsys input` 应多出一个触摸屏，
        //   `getevent -l` 也能看到一个新的 /dev/input/eventN。
        if (intent?.getBooleanExtra(EXTRA_HID_PROBE, false) == true) {
            logger.i(TAG, "收到 HID 探针指令")
            startHidProbe()
            return true
        }

        // am start -n com.uav.host/.HostActivity --ez hid_tap true --ei tap_x 612 --ei tap_y 160
        //   发一次点按，验证报告真的进了手机的输入子系统（不传坐标则用安全位置）。
        //   可加 --ei hold_ms 2500 拉长按住时长（配合手机「显示点按操作反馈」可截图留证）。
        if (intent?.getBooleanExtra(EXTRA_HID_TAP, false) == true) {
            val x = intent.getIntExtra(EXTRA_TAP_X, Int.MIN_VALUE)
            val y = intent.getIntExtra(EXTRA_TAP_Y, Int.MIN_VALUE)
            val hold = intent.getIntExtra(EXTRA_HOLD_MS, HID_TAP_HOLD_MS.toInt())
            logger.i(TAG, "收到 HID 点按指令: x=$x y=$y hold=${hold}ms")
            sendHidTap(x, y, hold)
            return true
        }

        return false
    }

    // ------------------------------------------------------------------ HID 注入探针

    /**
     * 注册一个「单点绝对坐标触摸屏」。
     *
     * 坐标空间取手机在 `CNXN` 里上报的**屏幕尺寸** —— 不是编码后的视频尺寸
     * （后者是 `max_size` 缩放过的，用它会让触摸落点整体偏移）。
     */
    private fun startHidProbe() {
        val injector = hidInjector
        if (injector == null) {
            logger.w(TAG, "HID 探针：还没建链（这些请求要等手机进入配件模式之后才有效）")
            return
        }
        val w = session?.peerWidth ?: 0
        val h = session?.peerHeight ?: 0
        if (w <= 0 || h <= 0) {
            logger.w(TAG, "HID 探针：还没收到手机的 CNXN（拿不到屏幕尺寸）")
            return
        }
        // USB 操作必须在 worker 线程（红线）
        Thread({
            val descriptor = HidTouchPanel.descriptor(w, h)
            logger.i(TAG, "HID 探针：描述符 ${descriptor.size}B，坐标空间 ${w}x$h")
            if (injector.registerAndLoad(HID_DEVICE_ID, descriptor)) {
                hidDeviceId = HID_DEVICE_ID
                logger.i(
                    TAG,
                    "HID 探针：注册成功。验证：手机上 dumpsys input | grep -i -A3 hid " +
                        "或 getevent -pl"
                )
            } else {
                logger.e(TAG, "HID 探针：注册失败（见上面哪一条请求返回了负数）")
            }
        }, "uav-hid-probe").apply { isDaemon = true }.start()
    }

    /**
     * 发一次点按（按下 → 停 [holdMs] → 抬起）。
     *
     * 不传坐标时落在一个**安全位置**（屏幕上方 6% 处的正中，通常是状态栏下方的空白区），
     * 避免误触手机上的按钮（实测手机上就有"停止投屏"这种一按就断链的按钮）。
     *
     * @param holdMs 按住时长。探针留证时拉长到 2s 以上，普通点按保持默认的几十毫秒。
     */
    private fun sendHidTap(xArg: Int, yArg: Int, holdMs: Int) {
        val injector = hidInjector
        val id = hidDeviceId
        if (injector == null || id == 0) {
            logger.w(TAG, "HID 点按：尚未注册 HID 设备（先跑一次 --ez hid_probe true）")
            return
        }
        val w = session?.peerWidth ?: 0
        val h = session?.peerHeight ?: 0
        val x = if (xArg == Int.MIN_VALUE) w / 2 else xArg
        val y = if (yArg == Int.MIN_VALUE) (h * 0.06f).toInt() else yArg
        if (w <= 0 || h <= 0) {
            logger.w(TAG, "HID 点按：拿不到手机屏幕尺寸，无法做安全兜底坐标")
            return
        }
        val hold = holdMs.coerceIn(1, HID_TAP_MAX_HOLD_MS)
        Thread({
            val down = HidTouchPanel.report(x, y, tipDown = true)
            val up = HidTouchPanel.report(x, y, tipDown = false)
            val okDown = injector.sendReport(id, down)
            try {
                Thread.sleep(hold.toLong())
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val okUp = injector.sendReport(id, up)
            logger.i(TAG, "HID 点按 (${x},${y}) 按住 ${hold}ms：按下=$okDown 抬起=$okUp")
        }, "uav-hid-tap").apply { isDaemon = true }.start()
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

        // HID 注入器绑定到本次连接。★ 旧的 HID 注册随重枚举一起失效，
        //   所以这里必须清零（真机上要重新 REGISTER_HID + 重发描述符）。
        hidInjector = HidInjector(connection, logger)
        hidDeviceId = 0

        // 建链成功 -> 退避归零，轮询回到最快档
        consecutiveFailures.set(0)

        lastDevice = device
        // aoaVersion == 0 表示这次是按"已是配件、跳过握手"的快路径连的，
        // 配件身份未被验证过（可能还是别的宿主 App 留下的）
        fastPathUnverified = aoaVersion == 0

        stopSession()

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

        /** adb 调试入口用的 extra：`--ez hid_probe true`（注册 HID 触摸屏探针）。 */
        const val EXTRA_HID_PROBE = "hid_probe"

        /** adb 调试入口用的 extra：`--ez hid_tap true [--ei tap_x N --ei tap_y M --ei hold_ms ms]`。 */
        const val EXTRA_HID_TAP = "hid_tap"

        const val EXTRA_TAP_X = "tap_x"
        const val EXTRA_TAP_Y = "tap_y"

        /** 点按的按住时长（毫秒）。探针留证时拉长，普通点按用默认值。 */
        const val EXTRA_HOLD_MS = "hold_ms"

        /** 探针用的 HID 设备 id（AOA 允许一个配件注册多个 HID 设备，我们先只用 1）。 */
        const val HID_DEVICE_ID = 1

        /** 点按时按住的时长：留够时间让手机侧产生明确的按下/抬起两条事件。 */
        const val HID_TAP_HOLD_MS = 80L

        /** 按住时长上限，防止 `--ei hold_ms` 传个离谱的值把线程占住。 */
        const val HID_TAP_MAX_HOLD_MS = 10_000
    }
}
