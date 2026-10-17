package com.uav.host.link

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.os.Handler
import android.os.Looper
import com.uav.host.AndroidLogger
import com.uav.host.DisplaySize
import com.uav.host.session.HostSession
import com.uav.host.usb.BulkByteChannel
import com.uav.host.usb.BulkPair
import com.uav.host.usb.UsbAttachRouter
import com.uav.protocol.BufferedByteChannel
import com.uav.protocol.ByteChannel
import com.uav.protocol.Logger
import com.uav.protocol.Transport
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w

/**
 * **链路编排层**（10.24 分层重构，从 [com.uav.host.HostActivity] 抽出）。
 *
 * ## 职责边界
 *
 * 只管「插线 → 权限 → AOA 握手 → 等重枚举 → 建 [HostSession] → 断了怎么恢复」，
 * 以及那条**前台轮询兜底**。**不碰任何 View**、不认识像素模式、不做触摸转发。
 *
 * 拆分前这些逻辑（`onReady` / `onError` / `onClosed` / `usbPoller` / `nextPollDelayMs` /
 * 自愈快路径 / `detachReceiver` / `stopSession`，合计 300+ 行）与 UI 混在同一个类里，
 * 结果是"想看链路策略"必须先读完整个 Activity。
 *
 * ## 它向上汇报什么
 *
 * 全部通过 [Listener] —— **只报事实，不下指令**。UI 怎么显示、要不要收菜单，是上层的决定。
 * 唯一例外是 [Listener.onSelfHealRequest]：那是"我识别出需要强制重握手了"，
 * 上层负责把它变成用户可见的状态与实际的重连动作。
 */
class LinkCoordinator(
    private val context: Context,
    private val logger: Logger = AndroidLogger,

    /**
     * 建会话需要的三样"当前值"。都做成**回调**而非构造时捕获的值 ——
     * 原地重协商会在同一条会话里取"此刻"的口径（10.18 真机踩过：捕获值会一直按旧口径算）。
     */
    private val surfaceProvider: () -> android.view.Surface?,
    private val displayArea: () -> IntArray?,
    private val oneToOne: () -> Boolean,
    private val audioEnabled: () -> Boolean,
    private val audioDelayMs: () -> Int,

    private val listener: Listener,
) : UsbAttachRouter.Callback {

    interface Listener {
        /** 会话进度（对应 `HostSession.Listener.onStatus`）。 */
        fun onProgress(text: String) {}

        /** 视频尺寸就绪（解码器 configure 成功）。 */
        fun onVideoSize(width: Int, height: Int) {}

        /** 首帧已输出 —— 唯一能宣告"投屏中"的事件。 */
        fun onFirstFrame() {}

        /** 链路断开。 */
        fun onClosed(reason: String) {}

        /** 链路出错（终态，除非上层自行发起重试）。 */
        fun onError(message: String, cause: Throwable?) {}

        /** 需要强制走一次完整 AOA 握手（自愈快路径）。 */
        fun onSelfHealRequest(device: UsbDevice) {}
    }

    /** 当前活动会话；`null` = 没有链路。 */
    @Volatile
    var session: HostSession? = null
        private set

    private var router: UsbAttachRouter? = null

    /** USB 层的 bulk 通道，仅用于统计展示；协议层实际读的是它的 [BufferedByteChannel] 包装。 */
    private var bulkChannel: BulkByteChannel? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 「用户主动切换画质 / 正在重开会话」标志（10.17）。
     *
     * 为 `true` 时，瞬时握手失败不当 ERROR 展示、也不触发强制 AOA 重握手 ——
     * 那类失败是"手机端还在推流 + 宿主刚重连"的字节交错，自动重试即恢复。
     * 首个视频帧到达（或超时兜底）即清除。
     *
     * ★ 这里**跨线程**：置位在布局回调（主线程），读在会话线程，故用原子变量。
     */
    private val expectingRestart = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 切换重开的兜底清理：即使一直没出画面，也不能让标志永久挂着掩盖真错误。 */
    private val clearExpectingRestart = Runnable { expectingRestart.set(false) }

    /** 同一时刻只允许一次"尺寸重协商"在飞（布局回调可能连发）。 */
    private val renegotiating = java.util.concurrent.atomic.AtomicBoolean(false)

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
     * 用原子类而不是普通 int：`onError` 可能从会话线程过来，而 `onReady` 与轮询都在主线程。
     */
    private val consecutiveFailures = java.util.concurrent.atomic.AtomicInteger()

    /**
     * 前台轮询兜底：实测宿主设备（BOE P40UCH）在手机重枚举后**只发 DETACHED、不发 ATTACHED**，
     * 只靠 `USB_DEVICE_ATTACHED` 广播会让"插线即投"彻底失效。
     *
     * ⚠️ 必须同时要求 **Surface 就绪**：没有 Surface 时握手也白搭
     *   （会话会等不到 Surface 而失败），而且每次握手都会让手机**重新枚举**
     *   USB —— 切后台时反复重枚举纯属浪费，还会把自动重试配额耗掉。
     */
    private val usbPoller = object : Runnable {
        override fun run() {
            if (session == null && isSurfaceReady()) {
                runCatching { router?.pollOnce() }
                    .onFailure { logger.w(TAG, "pollOnce 异常: ${it.message}") }
            }
            mainHandler.postDelayed(this, nextPollDelayMs())
        }
    }

    /** Surface 是否就绪（由上层在建链前判断；这里只读，不持有 View）。 */
    var isSurfaceReady: () -> Boolean = { true }

    fun start() {
        router = UsbAttachRouter(context, logger, this).also { it.start() }
        mainHandler.postDelayed(usbPoller, POLL_INTERVAL_MS)
    }

    fun close() {
        mainHandler.removeCallbacks(usbPoller)
        mainHandler.removeCallbacks(clearExpectingRestart)
        router?.close()
        router = null
        stopSession()
    }

    /**
     * 轮询间隔：连续失败时**指数退避**（2s → 4s → 8s → 16s → 30s 封顶）。
     *
     * 为什么要退避：设备一直在、但握手一直失败时（例如它已被别的宿主 App 占成孤儿链路），
     * 固定 2 秒轮询会无休止重试；退避能在**不放弃**的前提下把空转降到最低。
     * 建链成功后 [consecutiveFailures] 归零，立刻回到最快档。
     */
    private fun nextPollDelayMs(): Long =
        (POLL_INTERVAL_MS shl consecutiveFailures.get().coerceIn(0, MAX_POLL_BACKOFF_SHIFT))
            .coerceAtMost(MAX_POLL_INTERVAL_MS)

    // ------------------------------------------------------------------ 用户动作

    /** Scan / Retry 按钮：普通扫描（已是配件则复用直连）。 */
    fun scan(forceHandshake: Boolean = false) {
        if (forceHandshake) forceRetried = true
        else forceRetried = false
        if (forceHandshake) fastPathUnverified = false
        stopSession()
        router?.scanAndAttach(forceHandshake)
    }

    /** USB 设备刚插上。 */
    fun onDeviceAttached(device: UsbDevice) {
        stopSession()
        forceRetried = false
        router?.onDeviceAttached(device)
    }

    /** 设备拔出。 */
    fun onDeviceDetached(device: UsbDevice?) {
        stopSession()
        listener.onProgress("USB 已拔出，请重新插线或点 Scan/Retry")
        listener.onClosed("USB 已拔出")
    }

    /**
     * 请求一次"尺寸重协商"：优先**原地重发 OPEN**（10.18），失败才回退"断开 + 重连"。
     *
     * ⚠️ 不能在主线程做 `renegotiate()` —— 它会等手机回 `OKAY`（最长 `TIMEOUT_MS`）。
     * 统一丢到后台线程；回退路径需要主线程。
     */
    fun requestRenegotiate() {
        val s = session
        if (s == null) {
            // 没有活动会话（还没连上）→ 直接走扫描/重连
            stopSession()
            router?.scanAndAttach()
            return
        }
        // 同一时刻只允许一次重协商，避免布局回调连发把手机端反复重建
        if (!renegotiating.compareAndSet(false, true)) {
            logger.i(TAG, "已有重协商在进行，忽略这次请求")
            return
        }
        // 标记"预期内重开"：期间瞬时失败不弹 ERROR（见 [onError]），45s 兜底清除
        expectingRestart.set(true)
        mainHandler.removeCallbacks(clearExpectingRestart)
        mainHandler.postDelayed(clearExpectingRestart, RESTART_FLAG_TIMEOUT_MS)

        Thread({
            val ok = try {
                s.renegotiate()
            } catch (t: Throwable) {
                logger.w(TAG, "原地重协商异常: ${t.message}", t)
                false
            } finally {
                renegotiating.set(false)
            }
            if (!ok) {
                logger.w(TAG, "原地重协商未成功 → 回退到断开重连")
                mainHandler.post {
                    stopSession()
                    router?.scanAndAttach()
                }
            }
        }, "uav-renegotiate").apply { isDaemon = true }.start()
    }

    /**
     * 把"正在重开会话"的标志打开（切画质时由 UI 层调用）。
     *
     * ⚠️ 必须**在**发起 [requestRenegotiate] **之前**调：一旦 `renegotiate()` 已经返回 false
     * 并开始回退重连，那期间的失败就不再是"预期内"的了。
     */
    fun beginExpectedRestart() {
        expectingRestart.set(true)
        mainHandler.removeCallbacks(clearExpectingRestart)
        mainHandler.postDelayed(clearExpectingRestart, RESTART_FLAG_TIMEOUT_MS)
    }

    fun stopSession() {
        // 链路没了：旧 HID 注册随之失效。触点状态**保留**在控制器里 ——
        // 重连后它会把"手指还按着"补发回去，避免手机上手指"卡住"。
        onLinkLost()
        session?.close()
        session = null
        bulkChannel = null
        panelClearVideoSize()
    }

    /**
     * 回放模式：把一个**不走 USB** 的会话挂进来，让它参与"有没有活动会话"的判断。
     *
     * ⚠️ 为什么要挂进来而不是各存各的：轮询的接管条件是 `session == null`，
     *   回放期间若被判成"无会话"，前台轮询会去握手真实设备并把回放顶掉。
     */
    fun adoptReplaySession(s: HostSession) {
        stopSession()
        session = s
    }

    /** 两条回调：链路失效时让上层作废 HID 注册 / 清视频尺寸。 */
    var onLinkLost: () -> Unit = {}
    var panelClearVideoSize: () -> Unit = {}

    /**
     * USB 层的统计文本（只在"有 bulk 通道但还没有会话"的那一小段窗口里有值）。
     *
     * ⚠️ 正常情况下 [session] 非空就已经够看了，这个是兜底。
     */
    fun bulkStats(): String? = bulkChannel?.stats()

    /** 链路就绪（拿到新的 USB 连接）→ 让上层把 HID 绑定上去。 */
    var onLinkEstablished: (android.hardware.usb.UsbDeviceConnection) -> Unit = {}

    /**
     * 当前那条 USB 连接（没有链路时为 null）。
     *
     * 只给 HID 层做"注入器自愈"用：`onLinkLost` 清了注入器、而重建路径没走到
     * `onReady` 时，靠它把触摸救回来（见 `HidTouchController.rebindInjectorLocked`）。
     */
    @Volatile
    var currentConnection: UsbDeviceConnection? = null
        private set

    // ------------------------------------------------------------------ router 回调

    override fun onStatus(text: String) {
        listener.onProgress(text)
    }

    override fun onReady(
        connection: UsbDeviceConnection,
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

        // ⚠️ 顺序要求：stopSession() 里会作废旧连接的 HID 注册，所以它必须排在下面
        //    "绑定新连接"**之前** —— 反过来的话刚绑上就被清掉了，表现为触摸屏永远注册不上（踩过）。
        stopSession()
        currentConnection = connection
        onLinkEstablished(connection)

        val raw = BulkByteChannel(connection, pair, logger)
        // ★ 先把上一次会话残留在 FIFO 里的字节丢掉，再开新会话。
        //   否则第一个 payload 会被当成流头解析，静默配错解码器（实测踩到过）。
        raw.drainStaleInput()
        bulkChannel = raw
        // ★ 10.16 内核 panic 防线：协议层无论要多少（哪怕 24B 报文头），对内核的 bulkTransfer
        //   永远整块（readChunkSize=16KB ≥ 端点包长 512B）发起，多到货的进残留缓冲续读。
        //   直接把协议层的小请求透传给内核，MTK 主机栈不截断 actual_length → usercopy BUG → 重启
        //   （实测：kmalloc-128 被要求拷 391B，Comm: uav-reader）。
        val channel: ByteChannel = BufferedByteChannel(raw, pair.readChunkSize)
        val transport = Transport(channel, logger, TAG)

        val screen = DisplaySize.of(context)
        val s = HostSession(
            transport = transport,
            localWidth = screen.x,
            localHeight = screen.y,
            surfaceProvider = surfaceProvider,
            displayArea = displayArea,
            oneToOne = oneToOne,
            audioEnabled = audioEnabled(),
            audioDelayMs = audioDelayMs(),
            logger = logger,
            listener = object : HostSession.Listener {
                override fun onStatus(text: String) = listener.onProgress(text)

                override fun onVideoSize(width: Int, height: Int) {
                    // 尺寸（重）配置成功 = 重协商已见效 → 立刻清掉"切换中"标志，
                    // 免得它一直挂到 45s 兜底、把这段窗口里的真错误也吞掉。
                    expectingRestart.set(false)
                    mainHandler.removeCallbacks(clearExpectingRestart)
                    listener.onVideoSize(width, height)
                }

                override fun onFirstFrame() {
                    expectingRestart.set(false)
                    mainHandler.removeCallbacks(clearExpectingRestart)
                    listener.onFirstFrame()
                }

                override fun onClosed(reason: String) {
                    listener.onClosed(reason)
                    // ★ 10.32：这里**必须**把会话清掉，理由与 [handleSessionError] 完全一样 ——
                    //   轮询的接管条件是 `session == null`，不清就永远不会自动重连
                    //   （界面会一直停在"正在建立连接…"，实际一次都没去连）。
                    //
                    //   以前漏了没暴露：手机端 `close()` 从不发 CLSE，宿主这边总是因为
                    //   读失败走 `onError`，那条路径会清。后来手机端改成"停止时发 CLSE"，
                    //   才第一次走进这条分支 —— 表现就是"点了停止，宿主界面提示对了，
                    //   但画面再也回不来，还得拔插"。
                    mainHandler.post { stopSession() }
                    consecutiveFailures.incrementAndGet()
                }

                override fun onError(message: String, cause: Throwable?) =
                    handleSessionError(message, cause)
            },
        )
        session = s
        s.start()
        // 建链成功但还没画面 ⇒ "连接中"。⚠️ 不能在这里报"投屏中"（画面还没出来）。
        listener.onProgress("链路已建立，等待 CNXN / 首帧…")
    }

    /**
     * 会话错误（`HostSession.fail`）的**统一收口**。
     *
     * 分两路：用户主动重协商期间的瞬时失败 → 静默重试；其余 → 上报 ERROR。
     */
    private fun handleSessionError(message: String, cause: Throwable?) {
        if (expectingRestart.get()) {
            logger.w(TAG, "切换画质重开会话中的瞬时失败（将自动重试）: $message", cause)
            listener.onProgress("重协商未成功（$message），正在自动重试…")
            mainHandler.post {
                stopSession()
                router?.scanAndAttach()
            }
            consecutiveFailures.incrementAndGet()
            return
        }

        listener.onError(message, cause)

        // ★ 自动重连：链路错误后**必须**把会话清掉 —— 轮询的接管条件是 `session == null`，
        //   不清就永远不会自动重试。注意 stopSession() 有 UI 调用，只能放主线程。
        mainHandler.post { stopSession() }
        consecutiveFailures.incrementAndGet()
        // 断线是"新情况"（设备可能马上回来），重置自动重试计数；
        // 否则几次偶发断线就会耗光配额，自动重连永久失效
        router?.resetAttempts()

        // ★ 自愈：快路径（跳过握手）连上的链路如果没人应答，说明配件身份不是我们的
        //   —— 手机很可能被"别的宿主 App"切成了配件模式，系统因此没有拉起我们的手机端 App。
        //   这时强制走一次完整 AOA 握手，把配件身份改写成 UAV/usbscreen。
        val dev = lastDevice
        if (fastPathUnverified && !forceRetried && dev != null) {
            forceRetried = true
            fastPathUnverified = false
            logger.w(TAG, "快路径失败（$message）→ 改为强制完整 AOA 握手重试一次")
            listener.onSelfHealRequest(dev)
        }
    }

    companion object {
        const val TAG = "UAV-Host"

        /** 前台轮询的基准间隔。 */
        const val POLL_INTERVAL_MS = 2_000L

        /** 轮询退避的上限（连续失败会 2s→4s→8s… 最长等这么久再试）。 */
        const val MAX_POLL_INTERVAL_MS = 30_000L

        /** 退避最多翻几番（2s 左移 4 位 = 32s，会被上面那条封到 30s）。 */
        const val MAX_POLL_BACKOFF_SHIFT = 4

        /** 「切换画质重开中」标志的兜底存活时长（ms）；出画面会提前清除。 */
        const val RESTART_FLAG_TIMEOUT_MS = 45_000L
    }
}