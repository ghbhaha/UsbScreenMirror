package com.uav.host.aoa.link

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import com.uav.host.common.AndroidLogger
import com.uav.host.common.DisplaySize
import com.uav.host.aoa.flow.AoaStreamFlow
import com.uav.host.aoa.run.AoaLinkRun
import com.uav.host.aoa.input.HidTouchController
import com.uav.host.aoa.session.HostSession
import com.uav.host.aoa.usb.BulkByteChannel
import com.uav.host.aoa.usb.BulkPair
import com.uav.host.aoa.usb.UsbAttachRouter
import com.uav.protocol.ByteChannel
import com.uav.protocol.Logger
import com.uav.protocol.aoa.LinkRetry
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w

/**
 * **链路编排层**（10.24 分层重构，从 [com.uav.host.aoa.HostActivity] 抽出）。
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

        /**
         * 链路**已建立**（AOA 握手完成、`HostSession` 已在跑，还没等 CNXN / 首帧）。
         *
         * ★ 与 [onProgress] 分开：进度事件按规矩不许改动主状态行，而这条是**硬事实**
         *   —— 它必须能把上一次的「链路出错」拉回「连接中」，否则自动重连成功了、
         *   界面还停在错误上（2026-10-20 真机踩到）。
         */
        fun onLinkEstablished(text: String) {}

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

        /**
         * 对端在配件模式下**完全没人应答**（典型：手机端没装配套 App）。
         *
         * 与 [onError] 分开：这不是链路故障，等自动重连永远不会成功，
         * 上层要给的是可操作的去处（下载入口），不是一个"出错"标签。
         */
        fun onPeerAppMissing() {}
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
     * 会话侧协程作用域。
     *
     * 只跑**可挂起**的部分（握手流程、原地重协商）；USB 读写与解码仍在各自的专用线程上
     * —— `bulkTransfer` 不可中断，协程取消打不断卡在 URB 上的调用。
     * 生命周期跟着 [close]：`scope.cancel()` 之后所有挂起中的握手立刻退出。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("uav-aoa"))

    /** 给回放会话用：它不在本类里构造，但同样要把握手协程挂在这个作用域上。 */
    val sessionScope: CoroutineScope get() = scope

    private val retry = LinkRetry(
        baseDelayMs = POLL_INTERVAL_MS,
        maxShift = MAX_POLL_BACKOFF_SHIFT,
        capDelayMs = MAX_POLL_INTERVAL_MS,
    )

    /**
     * 链路动作的**串行执行器 + 运行权**（关会话 / 重新握手都排在这一条队列上）。
     *
     * 为什么必须串行、为什么队列关了要另起线程而不是退回调用线程，
     * 见 [AoaLinkRun] 的类注释 —— 那两条都是 10.33 用 ANR 换来的。
     */
    private val linkRun = AoaLinkRun(retry = retry, logger = logger, tag = TAG)


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

    /** 切换重开的兜底清理 Job：即使一直没出画面，也不能让标志永久挂着掩盖真错误。 */
    private var clearExpectingJob: Job? = null

    /**
     * 点亮"预期内重开"标志，并在 [timeoutMs] 后自动熄灭。
     *
     * ★ 用协程 `delay` 而不是 `Handler.postDelayed` + `removeCallbacks`：
     *   后者要求每个置位点都记得配一次移除，漏一处就是"标志永久亮着、真错误被吞"。
     *   这里"新的顶掉旧的"由 [Job.cancel] 保证，语义只有一处。
     */
    private fun armExpectingRestart(timeoutMs: Long = RESTART_FLAG_TIMEOUT_MS) {
        expectingRestart.set(true)
        clearExpectingJob?.cancel()
        clearExpectingJob = scope.launch(CoroutineName("uav-expect-restart")) {
            delay(timeoutMs)
            expectingRestart.set(false)
        }
    }

    /** 首帧到了 / 会话重开结束：立刻熄灯。 */
    private fun clearExpectingRestart() {
        expectingRestart.set(false)
        clearExpectingJob?.cancel()
        clearExpectingJob = null
    }

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
     * 是否停在"对端没人应答（多半没装配套 App）"上。
     *
     * ★ 置位期间**停止自动重试**。这是必须的：缺 App 是个**不会自己变好**的状态，
     *   自动重试只会以 5s 一轮无限空转 —— 每轮都要走一次 AOA 交互、打一条错误日志，
     *   而用户看到的还是同一句话。退避在这里也救不了：链路层（`onReady`）每轮都成功，
     *   计数会被清零（实测就是恒定 5.09s 一轮）。
     *
     * 解除条件只有三个，全是**外部的新情况**：用户点扫描/强制重握手、拔插、或会话真的跑通。
     */
    @Volatile
    private var waitingForPeerApp = false

    /** 上一次判定"对端没人应答"的时刻（`elapsedRealtime`），用于给 attach 触发的重试设闸。 */
    @Volatile
    private var peerAppMissingAt = 0L

    /**
     * 前台轮询兜底：实测宿主设备（BOE P40UCH）在手机重枚举后**只发 DETACHED、不发 ATTACHED**，
     * 只靠 `USB_DEVICE_ATTACHED` 广播会让"插线即投"彻底失效。
     *
     * ⚠️ 必须同时要求 **Surface 就绪**：没有 Surface 时握手也白搭
     *   （会话会等不到 Surface 而失败），而且每次握手都会让手机**重新枚举**
     *   USB —— 切后台时反复重枚举纯属浪费，还会把自动重试配额耗掉。
     */
    /**
     * `HostSession.Listener` 的翻译层：**只把事实交回本类**，怎么解释留在这里。
     *
     * 以前这段内联在 `onReady()` 里，与"建通道/建 Transport"那几十行混在一起；
     * 改动时很难判断某一行属于"装配"还是"状态机"。搬出去后
     * [AoaStreamFlow] 只管装配，这里只管状态与界面。
     */
    private val streamBridge = object : AoaStreamFlow.Bridge {
        override fun onStatus(text: String) = listener.onProgress(text)

        override fun onVideoSize(width: Int, height: Int) {
            // 尺寸（重）配置成功 = 重协商已见效 → 立刻清掉"切换中"标志，
            // 免得它一直挂到 45s 兜底、把这段窗口里的真错误也吞掉。
            clearExpectingRestart()
            listener.onVideoSize(width, height)
        }

        override fun onFirstFrame() {
            clearExpectingRestart()
            waitingForPeerApp = false
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
            retry.onAttemptFailed()
        }

        override fun onError(message: String, cause: Throwable?) =
            handleSessionError(message, cause)

        override fun onPeerAppMissing() {
            // ★ 必须清掉会话：轮询的接管条件是 `session == null`，
            //   不清就会一直挂着一个已经死掉的会话，用户装好 App 也接不回来。
            mainHandler.post { stopSession() }
            // ★ 并且**停掉自动重试**（见 waitingForPeerApp 的说明）：
            //   这里不计失败次数也没用 —— 链路层每轮都成功、
            //   计数会被 onReady 清零，退避永远停在最小档。
            waitingForPeerApp = true
            peerAppMissingAt = SystemClock.elapsedRealtime()
            listener.onPeerAppMissing()

            // 自愈仍值得试一次：配件身份可能被"别的宿主 App"改写过，
            // 那种情况系统不会拉起我们的配件 App，表现和"没装"一模一样。
            val dev = lastDevice
            if (fastPathUnverified && !forceRetried && dev != null) {
                forceRetried = true
                fastPathUnverified = false
                logger.w(TAG, "快路径连上但没人应答 → 强制完整 AOA 握手重试一次")
                listener.onSelfHealRequest(dev)
            }
        }
    }

    /** 装配层：连接 → 通道 → Transport → HostSession（见 [AoaStreamFlow] 的类注释）。 */
    private val streamFlow = AoaStreamFlow(
        logger = logger,
        scope = scope,
        appVersion = com.uav.host.BuildConfig.VERSION_CODE,
        surfaceProvider = surfaceProvider,
        displayArea = displayArea,
        oneToOne = oneToOne,
        audioEnabled = audioEnabled,
        audioDelayMs = audioDelayMs,
        panelSize = {
            val p = DisplaySize.of(context)
            intArrayOf(p.x, p.y)
        },
        bridge = streamBridge,
    )

    /** 轮询循环的 Job（关页面时随 [close] 取消）。 */
    private var pollJob: Job? = null

    /** Surface 是否就绪（由上层在建链前判断；这里只读，不持有 View）。 */
    var isSurfaceReady: () -> Boolean = { true }

    fun start() {
        router = UsbAttachRouter(context, logger, this).also { it.start() }
        // ★ 协程轮询而不是 Handler.postDelayed：销毁时随 scope 一起取消。
        //   用 Handler 的写法要求"每个 postDelayed 都配一次 removeCallbacks"，
        //   漏一处就是"页面已经走完了，回调还在跑"（本仓库踩过同类）。
        pollJob = scope.launch(Dispatchers.Main + CoroutineName("uav-aoa-poll")) {
            delay(POLL_INTERVAL_MS)          // 首次照旧等一个间隔，避免启动瞬间和 onCreate 抢
            while (isActive) {
                if (session == null && !waitingForPeerApp && isSurfaceReady()) {
                    runCatching { router?.pollOnce() }
                        .onFailure { logger.w(TAG, "pollOnce 异常: ${it.message}") }
                }
                delay(nextPollDelayMs())
            }
        }
    }

    fun close() {
        pollJob?.cancel()
        clearExpectingJob?.cancel()
        router?.close()
        router = null
        stopSession()
        // ★ 会话先停、作用域后取消：反过来的话握手协程可能在收尾中间被掐断
        scope.cancel()
        // 不中断正在跑的关闭任务：`/dev/usb_accessory` 独占，漏一个 fd 就永久连不回来
        linkRun.shutdown()
    }

    /**
     * 轮询间隔：连续失败时**指数退避**（2s → 4s → 8s → 16s → 30s 封顶）。
     *
     * 为什么要退避：设备一直在、但握手一直失败时（例如它已被别的宿主 App 占成孤儿链路），
     * 固定 2 秒轮询会无休止重试；退避能在**不放弃**的前提下把空转降到最低。
     * 建链成功后计数归零，立刻回到最快档。判据本身在 [LinkRetry]（有单测）。
     */
    private fun nextPollDelayMs(): Long = retry.pollDelayMs()

    // ------------------------------------------------------------------ 用户动作

    /** Scan / Retry 按钮：普通扫描（已是配件则复用直连）。 */
    fun scan(forceHandshake: Boolean = false) {
        // 用户主动点扫描/强制重握手 = 他大概刚把 App 装上，解除"等 App"状态重试
        waitingForPeerApp = false
        if (forceHandshake) forceRetried = true
        else forceRetried = false
        if (forceHandshake) fastPathUnverified = false
        restartSerial { router?.scanAndAttach(forceHandshake) }
    }

    /** USB 设备刚插上。 */
    fun onDeviceAttached(device: UsbDevice) {
        // ⚠️ 我们自己的 AOA 握手会让手机**重新枚举** USB，于是又派发一次 attach ——
        //   如果把它当成"用户重插"，重试就会自我续命：每次超时 → 重新握手 → 又 attach
        //   → 又重试，形成 5s 一轮的空转（真机日志里数到 5 次）。
        //   所以这里加一道时间闸：判定缺 App 后的 15s 内忽略 attach 触发的重试。
        //   用户真拔插一次通常远超 15s，不受影响；急着重试也有「扫描 / 重试」按钮。
        val sinceMissing = SystemClock.elapsedRealtime() - peerAppMissingAt
        if (waitingForPeerApp && sinceMissing < PEER_APP_RETRY_GATE_MS) {
            logger.i(TAG, "忽略本次 attach：刚判定对端没人应答（${sinceMissing}ms < ${PEER_APP_RETRY_GATE_MS}ms）")
            return
        }
        waitingForPeerApp = false
        forceRetried = false
        restartSerial { router?.onDeviceAttached(device) }
    }

    /** 设备拔出。 */
    fun onDeviceDetached(device: UsbDevice?) {
        // ⚠️ 这里**不解除** waitingForPeerApp：我们自己的握手同样会让手机重新枚举、
        //   派发 DETACHED —— 那不是"用户拔线"。解除只发生在 attach（带 15s 闸）与用户动作。
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
            // ★ 必须走串行队列：旧会话的 close 可能还排在里面（10.33），
            //   并发碰 USB 会抢端点。
            restartSerial { router?.scanAndAttach() }
            return
        }
        // 同一时刻只允许一次重协商，避免布局回调连发把手机端反复重建
        if (!renegotiating.compareAndSet(false, true)) {
            logger.i(TAG, "已有重协商在进行，忽略这次请求")
            return
        }
        // 标记"预期内重开"：期间瞬时失败不弹 ERROR（见 [onError]），45s 兜底清除
        armExpectingRestart()

        // ★ 协程而不是裸线程：`renegotiate()` 内部是可取消的挂起等待（`withTimeoutOrNull`），
        //   页面销毁时随 [scope] 一起取消，不必等满那 5 秒超时。
        scope.launch(CoroutineName("uav-renegotiate")) {
            var ok = false
            try {
                ok = s.renegotiate()
            } catch (c: kotlinx.coroutines.CancellationException) {
                // ★ 页面销毁 / 链路关闭导致的取消：不是"重协商失败"，更不能回退去重连
                throw c
            } catch (t: Throwable) {
                logger.w(TAG, "原地重协商异常: ${t.message}", t)
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
        }
    }

    /**
     * 把"正在重开会话"的标志打开（切画质时由 UI 层调用）。
     *
     * ⚠️ 必须**在**发起 [requestRenegotiate] **之前**调：一旦 `renegotiate()` 已经返回 false
     * 并开始回退重连，那期间的失败就不再是"预期内"的了。
     */
    fun beginExpectedRestart() {
        armExpectingRestart()
    }

    /**
     * 断开当前会话。
     *
     * 分两段：**同步**地"解除引用 + 通知上层"（这样轮询能立刻接管、界面立刻清空），
     * **异步**地关连接 —— 后者是阻塞的（`MediaCodec.release()` +
     * `Connection.close()` 要拿 `BufferedByteChannel` 的锁，而读线程正卡在 `bulkTransfer`）。
     * 这段阻塞以前直接压在调用者线程上，主线程点"强制重握手"就会被堵 5 秒 ⇒ ANR（10.33）。
     */
    fun stopSession() {
        detachSession()?.let { submitClose(it) }
    }

    /**
     * 只解除引用 + 通知上层，**不关连接**。
     *
     * @return 被摘下来的旧会话（调用方负责关闭）
     */
    private fun detachSession(): HostSession? {
        val old = session
        if (old == null && bulkChannel == null) return null
        // 链路没了：旧 HID 注册随之失效。触点状态**保留**在控制器里 ——
        // 重连后它会把"手指还按着"补发回去，避免手机上手指"卡住"。
        onLinkLost()
        session = null
        bulkChannel = null
        panelClearVideoSize()
        return old
    }

    /** 把关闭动作丢进串行队列（队列已关时另起线程关，绝不退回调用线程）。 */
    private fun submitClose(s: HostSession) {
        linkRun.closeLater { s.close() }
    }

    /**
     * 串行执行"关干净旧会话 → 再做 [then]"。
     *
     * ★ 必须串行：关会话和重新握手都要碰 USB，旧连接还没 close 就 claim 新接口会抢端点。
     */
    private fun restartSerial(then: () -> Unit) {
        linkRun.restart {
            detachSession()?.let { old ->
                runCatching { old.close() }
                    .onFailure { logger.w(TAG, "关闭旧会话失败: ${it.message}") }
            }
            then()
        }
    }

    /** 同步关会话。只用于"马上就要 claim 新接口、必须先放掉旧连接"的后台路径。 */
    private fun stopSessionNow() {
        detachSession()?.let { old ->
            runCatching { old.close() }
                .onFailure { logger.w(TAG, "关闭旧会话失败: ${it.message}") }
        }
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
        retry.onAttemptSucceeded()

        lastDevice = device
        // aoaVersion == 0 表示这次是按"已是配件、跳过握手"的快路径连的，
        // 配件身份未被验证过（可能还是别的宿主 App 留下的）
        fastPathUnverified = aoaVersion == 0

        // ⚠️ 顺序要求：stopSession() 里会作废旧连接的 HID 注册，所以它必须排在下面
        //    "绑定新连接"**之前** —— 反过来的话刚绑上就被清掉了，表现为触摸屏永远注册不上（踩过）。
        //
        // ★ 这里用**同步**版本：马上要 claim 新接口，旧连接必须先真的关掉，
        //   否则两者抢同一组端点。本方法本来就跑在 router 的后台线程上，同步关不会卡 UI。
        stopSessionNow()
        currentConnection = connection
        onLinkEstablished(connection)

        // ★ 装配与回调翻译都在 [AoaStreamFlow]：这里只留"顺序"与"状态"。
        //   ⚠️ 顺序要求：stopSessionNow() 里会作废旧连接的 HID 注册，所以它必须排在
        //   onLinkEstablished（绑定新连接）**之前** —— 反过来的话刚绑上就被清掉，
        //   表现为触摸屏永远注册不上（踩过）。
        val opened = streamFlow.open(connection, pair)
        bulkChannel = opened.raw
        session = opened.session
        opened.session.start()
        // 建链成功但还没画面 ⇒ "连接中"。⚠️ 不能在这里报"投屏中"（画面还没出来）。
        // ★ 走 onLinkEstablished 而不是 onProgress：后者不动主行，会把上一次的
        //   「链路出错」永远留在屏幕上（重连成功也是白搭）。
        listener.onLinkEstablished("链路已建立，等待 CNXN / 首帧…")
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
            // ★ 串行：先关干净这条失败的会话，再重连（10.33）
            restartSerial { router?.scanAndAttach() }
            retry.onAttemptFailed()
            return
        }

        listener.onError(message, cause)

        // ★ 自动重连：链路错误后**必须**把会话清掉 —— 轮询的接管条件是 `session == null`，
        //   不清就永远不会自动重试。注意 stopSession() 有 UI 调用，只能放主线程。
        mainHandler.post { stopSession() }
        retry.onAttemptFailed()
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

        /** 判定缺 App 后，这段窗口内忽略 attach 触发的重试（见 onDeviceAttached）。 */
        const val PEER_APP_RETRY_GATE_MS = 15_000L

        /** 轮询退避的上限（连续失败会 2s→4s→8s… 最长等这么久再试）。 */
        const val MAX_POLL_INTERVAL_MS = 30_000L

        /** 退避最多翻几番（2s 左移 4 位 = 32s，会被上面那条封到 30s）。 */
        const val MAX_POLL_BACKOFF_SHIFT = 4

        /** 「切换画质重开中」标志的兜底存活时长（ms）；出画面会提前清除。 */
        const val RESTART_FLAG_TIMEOUT_MS = 45_000L
    }
}