package com.uav.accessory.session

import com.uav.accessory.AndroidLogger
import com.uav.protocol.Bytes
import com.uav.protocol.Logger
import com.uav.protocol.adb.AdbMessage
import com.uav.protocol.aoa.Command
import com.uav.protocol.aoa.Connection
import com.uav.protocol.aoa.ConnectionListener
import com.uav.protocol.aoa.FrameCodec
import com.uav.protocol.aoa.Protocol
import com.uav.protocol.aoa.StreamHeader
import com.uav.protocol.aoa.StreamId
import com.uav.protocol.aoa.Transport
import com.uav.protocol.d
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.media.Options
import com.uav.protocol.media.ParsedOpen
import com.uav.protocol.w
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机端会话（计划 3.2 / 4.2）。
 *
 * 报文流：
 * ```
 * 手机 <--CNXN(宿主 w/h)------------- 宿主
 * 手机  --CNXN(本机 w/h)------------> 宿主
 * 手机 <--OPEN(newScreen1:2 ...)----- 宿主
 * 手机  --OKAY---------------------> 宿主      ← 收到即回，不等采集就绪
 * 手机  --WRTE[1](12B 流头)--------> 宿主
 * 手机  --WRTE[2](CONFIG 帧)-------> 宿主
 * 手机  --WRTE[3..](普通帧)--------> 宿主
 * ```
 *
 * **OKAY 立即回**：采集授权是异步的（用户要点一次系统弹窗），
 * 若等采集就绪再回 OKAY，宿主的 5s 应答超时会先到。授权被拒时改发
 * `CLSE(含原因串)` 让宿主走正常断连流程（计划 4.4：禁止静默干等）。
 */
class AccessorySession(
    private val transport: Transport,
    private val localWidth: Int,
    private val localHeight: Int,
    private val capture: CaptureController,
    private val logger: Logger = AndroidLogger,
    private val listener: Listener,
) {

    interface Listener {
        fun onStatus(text: String) {}
        fun onError(message: String, cause: Throwable?)
        fun onClosed(reason: String) {}

        /**
         * 会话起来了、但宿主长时间**没有任何报文**。
         *
         * ★ 这**不是普通断线**，而是实测确认过的一种"假配件"：
         *   配件字符串已经写进驱动、框架因此把 attach 派发给了我们，
         *   但 gadget 实际还停在普通模式 —— 而 `/dev/usb_accessory` 是**独占打开**的，
         *   我们一旦握着一个这样的 fd，手机端就**再也切不进配件模式**：
         *   宿主侧表现为无限"等待重枚举超时"，只能靠拔插或强杀 App 恢复。
         *   （实测：停掉 App 释放 fd 后，**下一次握手立刻成功**。）
         *
         * 因此实现方应当**只释放、不重连** —— 把 gadget 让出来等宿主重新握手，
         * 那时系统会重新派发 attach，链路自然建起来。
         */
        fun onIdleTimeout(reason: String) {}
    }

    /** 由 App 侧实现（`CaptureService`），负责虚拟屏 + 编码器。 */
    interface CaptureController {
        /**
         * 按协商参数启动采集与编码。
         *
         * 实现必须是**非阻塞**的；真正的授权/编码在后台完成，出帧后调用
         * [AccessorySession.sendStreamHeader] / [AccessorySession.sendFrame]。
         * 若确定无法启动（例如录屏权限被拒），应调用 [AccessorySession.failAndClose]。
         */
        fun startCapture(options: ParsedOpen, session: AccessorySession)

        /**
         * 停止采集。
         *
         * ⚠️ **会话层现在不调它**（10.33）：编码器与虚拟屏的生死由 `CaptureService` 独占，
         * 会话断了要留着给重连复用（`MediaProjection` 令牌只能 `createVirtualDisplay` 一次）。
         * 从会话层停采集会让下一条 `OPEN` 建不出虚拟屏、逼用户重新授权。
         * 只留作契约声明 —— 真要停，走 `CaptureService.stopEverything()`。
         */
        fun stopCapture()

        /**
         * 宿主重建了解码器（切后台→回前台），请**立刻产出一个 IDR**（10.21）。
         *
         * ★ 不能靠"新会话接入"那套：`resendStartup` 会重发流头 + CONFIG，
         *   而这里链路与流头都还有效，重发只会把宿主的解码器搞乱。
         *
         * 实现必须是**非阻塞**的（跑在协议读线程上）。
         */
        fun requestKeyFrame() {}
    }

    private val closed = AtomicBoolean(false)
    private var connection: Connection? = null

    /**
     * 本会话内哪些流已经发过流头。
     *
     * ★ 必须用它把帧"关"在流头后面：复用编码器时（配件重新接入），
     *   编码器在 OPEN 到达、`resendStartup()` 执行之前就已经在推帧了；
     *   这些帧会抢在流头之前发出去，宿主把第一帧当 12B 流头解析 → 得到荒谬尺寸。
     *   （宿主侧虽有"杂散 payload 丢弃"兜底，但那是补救；源头必须堵住。）
     */
    private val headerSent = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    /** 因"本会话还没发流头"而被丢弃的帧数（正常只会在重连瞬间出现少量）。 */
    val framesDroppedBeforeHeader = java.util.concurrent.atomic.AtomicLong()

    /**
     * 是否已收到宿主的**第一条**报文。
     *
     * 用来给"假配件"设期限：拿到 fd 不等于宿主真的在那头
     * （详见 [Listener.onIdleTimeout] 的说明）。
     */
    private val hostSeen = AtomicBoolean(false)

    /** 空闲看门狗线程；收到宿主报文或会话关闭时打断。 */
    @Volatile
    private var idleWatchdog: Thread? = null

    @Volatile
    var negotiatedOptions: ParsedOpen? = null
        private set

    @Volatile
    var hostWidth = 0
        private set

    @Volatile
    var hostHeight = 0
        private set

    fun start() {
        if (connection != null) return
        val conn = Connection(transport, logger, TAG, connectionListener)
        connection = conn
        conn.start()
        logger.i(TAG, "accessory session started on ${transport.label} (本机 ${localWidth}x$localHeight)")
        startIdleWatchdog()
    }

    /**
     * 看门狗：会话起来后若 [IDLE_RELEASE_MS] 内宿主一条报文都没发，判定为"假配件"并主动释放。
     *
     * 这是解开**互锁**的关键一步 —— 我们不放 fd，手机端就切不进配件模式，
     * 宿主就永远等不到重枚举，死锁一直持续到拔插为止。
     */
    private fun startIdleWatchdog() {
        val t = Thread({
            try {
                Thread.sleep(IDLE_RELEASE_MS)
            } catch (ie: InterruptedException) {
                return@Thread // 已收到宿主报文、或会话被主动关闭
            }
            if (closed.get() || hostSeen.get()) return@Thread

            val reason = "宿主 ${IDLE_RELEASE_MS / 1000}s 内无任何报文"
            logger.w(
                TAG,
                "$reason —— 判定为假配件，释放 fd（否则会堵死配件模式切换；宿主侧会一直报" +
                    "『等待重枚举超时』）"
            )
            // 先释放再通知：fd 必须真的关掉，gadget 才能让出来
            close()
            listener.onIdleTimeout(reason)
        }, "uav-accessory-idle")
        t.isDaemon = true
        idleWatchdog = t
        t.start()
    }

    // ------------------------------------------------------------------ 发送

    /** 发送某条流的流头（该流**首条** `WRTE` 的 payload）。 */
    fun sendStreamHeader(header: StreamHeader, streamId: Int = StreamId.VIDEO) {
        val conn = connection ?: return
        conn.send(Command.WRTE, streamId, 0, header.encode())
        headerSent[streamId] = true
    }

    /** 发送一帧（合并成一条 `WRTE`，接收端一报一帧、无需重组）。 */
    fun sendFrame(ptsAndFlags: Long, data: ByteArray, streamId: Int = StreamId.VIDEO) {
        val conn = connection ?: return
        // ★ 流头必须先行：否则宿主会把这一帧的头部字节当 12B 流头解析
        if (headerSent[streamId] != true) {
            val n = framesDroppedBeforeHeader.incrementAndGet()
            if (n <= 3 || n % 100 == 0L) {
                logger.w(
                    TAG,
                    "流[${StreamId.name(streamId)}] 尚未发送流头，丢弃该帧（第 $n 帧）" +
                        "—— 防止宿主把帧误当流头"
                )
            }
            return
        }
        conn.send(
            Command.WRTE, streamId, 0,
            FrameCodec.encodeFrame(ptsAndFlags, data, 0, data.size),
        )
    }

    /** 出错时如实告知宿主并收敛，而不是静默干等（计划 4.4）。 */
    fun failAndClose(reason: String) {
        logger.e(TAG, "failing session: $reason")
        listener.onError(reason, null)
        sendCloseAndFlush(reason)
        close()
    }

    /**
     * 正常主动下线：先告诉对端"我为什么走"，再关连接。
     *
     * 与 [failAndClose] 的区别是**不触发 `listener.onError`** —— 用户点"停止投屏"
     * 不是故障，走错误路径会把本端界面的状态标成"错误"，也会让宿主的
     * `expectingRestart` 之类分流判断走错分支。
     *
     * ★ 为什么不直接 `close()`：`close()` 只关本地 socket，**一个报文都不发**。
     *   对端（宿主）收不到任何信号，只能靠读超时或空闲看门狗自己发现 ——
     *   表现为"点了停止，宿主画面冻住好几秒，然后才开始无意义的重连重试"，
     *   而且用户完全不知道自己那一按到底有没有生效。
     */
    fun closeGracefully(reason: String) {
        logger.i(TAG, "closing gracefully: $reason")
        sendCloseAndFlush(reason)
        close()
    }

    /** 发 `CLSE(CLOSE_ALL, reason)` 并给写线程一点时间把它真正送出去。 */
    private fun sendCloseAndFlush(reason: String) {
        connection?.sendClose(StreamId.CLOSE_ALL, reason)
        try {
            Thread.sleep(CLOSE_FLUSH_MS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ------------------------------------------------------------------ 收报文

    private val connectionListener = object : ConnectionListener {

        override fun onMessage(message: AdbMessage) {
            // 宿主确实在（哪怕只是发来一条我们不认识的报文）—— 期限解除
            if (hostSeen.compareAndSet(false, true)) {
                idleWatchdog?.interrupt()
                idleWatchdog = null
            }
            when (message.commandType) {
                Command.CNXN -> handleCnxn(message)
                Command.OPEN -> handleOpen(message)
                Command.OKAY -> logger.i(TAG, "OKAY received (host acks stream ${StreamId.name(message.arg0)})")
                // ★ 宿主换 Surface 重建了解码器（切后台→回前台），要一个 IDR（10.21）。
                //   这是**唯一**的"没换会话却要关键帧"路径，所以必须显式支持。
                Command.RQID -> {
                    logger.i(TAG, "RQID received：宿主请求关键帧（切后台恢复渲染）")
                    runCatching { capture.requestKeyFrame() }
                        .onFailure { logger.w(TAG, "requestKeyFrame 失败: ${it.message}") }
                }
                Command.CLSE -> {
                    logger.i(TAG, "CLSE from host: ${message.payload.toString(Charsets.UTF_8)}")
                    listener.onClosed("host CLSE: ${message.payload.toString(Charsets.UTF_8)}")
                }
                else -> logger.d(TAG, "message ${message.commandName} ignored")
            }
        }

        override fun onError(cause: Throwable) {
            if (closed.get()) return
            logger.e(TAG, "链路错误: ${cause.message}", cause)
            listener.onError("链路错误: ${cause.message}", cause)
        }

        override fun onClosed(reason: String) {
            if (closed.get()) return
            listener.onClosed(reason)
        }
    }

    private fun handleCnxn(message: AdbMessage) {
        if (message.payload.size >= 8) {
            hostWidth = Bytes.getIntLE(message.payload, 0)
            hostHeight = Bytes.getIntLE(message.payload, 4)
        }
        logger.i(
            TAG,
            "host CNXN: version=${message.arg0} 宿主屏幕 ${hostWidth}x$hostHeight"
        )
        // 回复本机尺寸；版本取两者较小值
        val version = minOf(message.arg0, Protocol.VERSION)
        // ★ payload 追加本机单调时钟读数（8B -> 16B）：宿主用它标定两端时钟，
        //   进而把每帧的 pts 换算到宿主时钟，算出端到端延迟（计划 4.10）。
        //   ⚠️ 这个读数的时基必须与帧 pts 一致 —— MediaCodec 在 surface 输入模式下
        //   用 System.nanoTime() 作为帧时间戳，所以这里也用 System.nanoTime()。
        val payload = Bytes.intLE(localWidth) + Bytes.intLE(localHeight) +
            Bytes.longLE(System.nanoTime())
        connection?.send(Command.CNXN, version, 0, payload)
        listener.onStatus("已回 CNXN（本机 ${localWidth}x$localHeight）")
    }

    private fun handleOpen(message: AdbMessage) {
        val parsed = try {
            Options.parsePayload(message.payload)
        } catch (t: Throwable) {
            failAndClose("OPEN 服务串解析失败: ${t.message}")
            return
        }
        negotiatedOptions = parsed
        logger.i(
            TAG,
            "OPEN: service='${parsed.service}' max_size=${parsed.maxSize} " +
                "bitrate=${parsed.videoBitRate} max_fps=${parsed.maxFps} audio=${parsed.audio}"
        )

        if (parsed.audio) {
            // MVP 不建音频管线（计划 3.11）；明确告知而不是假装在发
            logger.w(TAG, "宿主请求了音频，但 MVP 不启用音频流，本次忽略")
        }

        // ★ 先回 OKAY 再启动采集：采集授权是异步的，等它就绪会超过宿主的 5s 超时
        connection?.sendOkay(StreamId.CONTROL)
        listener.onStatus("已回 OKAY，启动屏幕采集…")

        try {
            capture.startCapture(parsed, this)
        } catch (t: Throwable) {
            failAndClose("启动采集失败: ${t.message}")
        }
    }

    /**
     * 关会话。
     *
     * ★ **不碰采集**（10.33）：编码器与虚拟屏的生命周期由 `CaptureService` 独占，
     *   会话断了要**留着**给重连复用 —— 因为 `MediaProjection` 令牌只允许
     *   `createVirtualDisplay` 一次，编码器一旦销毁，下一条 `OPEN` 再建虚拟屏必然失败，
     *   用户就得重新点一次录屏授权（对用户是明显倒退）。
     *
     *   以前这里调了 `stopCaptureQuietly()`，但它其实**从来没生效过**：
     *   那个 override 写成了 `stopCapture()`（调自己）⇒ 无限递归 ⇒ StackOverflowError
     *   被 `runCatching` 吞掉。也就是说"重连能复用编码器"一直是靠这个 bug 侥幸成立的，
     *   修掉递归后反而暴露成"重连后永远没有画面"。现在把这条约定写进文档与代码。
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        idleWatchdog?.interrupt()
        idleWatchdog = null
        connection?.close()
        connection = null
        logger.i(TAG, "accessory session closed")
    }

    val isClosed: Boolean get() = closed.get()

    fun stats(): String =
        "host=${if (hostWidth > 0) "${hostWidth}x$hostHeight" else "-"} " +
            "options=${negotiatedOptions?.options ?: "-"} | " +
            (connection?.stats?.snapshot() ?: "-")

    companion object {
        const val TAG = "UAV-Accessory"

        /**
         * 会话启动后多久没收到宿主报文就释放 fd。
         *
         * 取值权衡：正常链路里宿主 claim 完接口就发 CNXN（1~3s 量级），
         * 12s 有充足余量；又不能太长，否则"假配件"会把手机端堵住十几秒以上。
         */
        const val IDLE_RELEASE_MS = 12_000L

        /**
         * 发完 `CLSE` 后等多久再真正关连接（毫秒）。
         *
         * 报文是异步写出去的：立刻 `close()` 会把刚排进队列的 `CLSE` 一起掐掉，
         * 对端就永远收不到"我下线了"。150ms 是实测能稳定送达、又不至于让
         * "停止"按钮明显卡顿的量级（与 `failAndClose` 一直沿用的值一致）。
         */
        private const val CLOSE_FLUSH_MS = 150L
    }
}
