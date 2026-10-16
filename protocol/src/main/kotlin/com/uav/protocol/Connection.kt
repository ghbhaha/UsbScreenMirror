package com.uav.protocol

import java.io.Closeable
import java.io.EOFException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 某条流的数据接收端。 */
interface StreamSink {

    /** 该流的流头（首条 `WRTE`）。 */
    fun onHeader(header: StreamHeader)

    /** 一帧数据。**实现必须非阻塞**（由读线程同步调用）。 */
    fun onFrame(frame: Frame)
}

/** 会话级事件回调（`CNXN` / `OPEN` / `OKAY` / `CLSE`）。 */
interface ConnectionListener {

    /** 收到非 `WRTE` 的会话级报文。 */
    fun onMessage(message: AdbMessage)

    /** 读/写线程异常退出。 */
    fun onError(cause: Throwable)

    /** 对端正常关闭（EOF 或收到 `CLSE(CLOSE_ALL)`）。 */
    fun onClosed(reason: String)
}

/**
 * 连接层：**单读线程 + 单写线程**（计划 1.4）。
 *
 * **读侧**：`AdbMessage.readFrom` → 按命令分发。`WRTE` 按 `arg0`（★ 唯一的流标识）
 * 投递到对应流的 [StreamSink]；该流的首条 `WRTE` payload 是 [StreamHeader]，
 * 之后每条是一帧。**读线程绝不能被消费端阻塞**，所以下沉到有界的 [FrameQueue]（计划 4.8）。
 *
 * **写侧**：所有出站报文（含流头、帧、`OKAY`、`CLSE`）经同一队列串行化，
 * 杜绝多线程交错把报文写坏（计划 4.8 单写线程）。
 *
 * **未知命令字 / 未知流 id → 记日志并忽略该条报文，不中断连接**（向前兼容，计划 4.2）。
 * 但 magic / checksum 不符、长度超限、收到已裁剪的 `AUTH`/`STLS` **必须抛错**（计划 1.2）。
 */
class Connection(
    val transport: Transport,
    private val logger: Logger = Logger.NONE,
    private val tag: String = Protocol.TAG,
    private var listener: ConnectionListener? = null,
) : Closeable {

    private val sinks = ConcurrentHashMap<Int, StreamSink>()
    private val headers = ConcurrentHashMap<Int, StreamHeader>()

    private val writeQueue = LinkedBlockingQueue<Outbound>()

    private val closing = AtomicBoolean(false)

    @Volatile
    private var running = false

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null

    val stats = Stats()

    /** 底层通道名称，用于日志。 */
    val channelLabel: String get() = transport.label

    fun setListener(listener: ConnectionListener?) {
        this.listener = listener
    }

    /** 注册某条流的数据接收端。必须在 [start] 之前或读线程首次收到该流数据之前调用。 */
    fun registerSink(streamId: Int, sink: StreamSink) {
        sinks[streamId] = sink
    }

    fun sinkOf(streamId: Int): StreamSink? = sinks[streamId]

    /** 某条流已解析出的流头（宿主 `MediaCodec.configure` 的宽高来源）。 */
    fun headerOf(streamId: Int): StreamHeader? = headers[streamId]

    val isRunning: Boolean get() = running

    /** 启动读/写线程。 */
    fun start() {
        if (running) return
        running = true

        writerThread = Thread({ writerLoop() }, "uav-writer").apply {
            isDaemon = true
            start()
        }
        readerThread = Thread({ readerLoop() }, "uav-reader").apply {
            isDaemon = true
            start()
        }
        logger.i(tag, "connection started on ${transport.label}")
    }

    // ------------------------------------------------------------------ 发送

    /**
     * 排队发送一条报文。
     *
     * ⚠️ [payload] 的**所有权转移给本连接**，调用方在调用后不得复用该缓冲区
     * （发送端每帧新分配缓冲，正常路径无需拷贝；这也避免了为大帧做整帧复制）。
     *
     * @return false 表示连接已关闭，报文被丢弃
     */
    fun send(
        command: Command,
        arg0: Int = 0,
        arg1: Int = 0,
        payload: ByteArray = AdbMessage.EMPTY,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size,
    ): Boolean = sendRaw(command.wire, arg0, arg1, payload, payloadOffset, payloadLength)

    fun send(message: AdbMessage): Boolean =
        sendRaw(message.command, message.arg0, message.arg1, message.payload, 0, message.payload.size)

    /** 关闭某条流；[streamId] 传 [StreamId.CLOSE_ALL] 表示整条连接关闭。 */
    fun sendClose(streamId: Int = StreamId.CLOSE_ALL, reason: String = ""): Boolean {
        val payload = if (reason.isEmpty()) AdbMessage.EMPTY else {
            reason.toByteArray(Charsets.UTF_8).let { if (it.size <= 128) it else it.copyOf(128) }
        }
        return send(Command.CLSE, streamId, 0, payload)
    }

    fun sendOkay(streamId: Int = StreamId.CONTROL): Boolean = send(Command.OKAY, streamId, 0)

    private fun sendRaw(
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        if (closing.get()) {
            logger.w(tag, "send dropped, connection closing: ${Command.fromWire(command)?.name ?: command}")
            return false
        }
        if (length > Protocol.MAX_PAYLOAD_SIZE) {
            throw ProtocolException("outbound payload too large: $length")
        }
        writeQueue.put(Outbound(command, arg0, arg1, payload, offset, length))
        return true
    }

    // ------------------------------------------------------------------ 写线程

    private fun writerLoop() {
        try {
            while (true) {
                val out = writeQueue.take()
                if (out === SHUTDOWN) break
                AdbMessage.writeTo(
                    transport, out.command, out.arg0, out.arg1, out.payload, out.offset, out.length,
                )
                stats.messagesWritten.incrementAndGet()
            }
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            logger.e(tag, "writer loop failed: ${t.message}", t)
            fail(t)
        }
    }

    // ------------------------------------------------------------------ 读线程

    private fun readerLoop() {
        try {
            while (running && !closing.get()) {
                val message = AdbMessage.readFrom(transport)
                stats.messagesRead.incrementAndGet()
                dispatch(message)
            }
        } catch (eof: EOFException) {
            if (!closing.get()) {
                logger.i(tag, "peer closed: ${eof.message}")
                notifyClosed("eof")
            }
        } catch (t: Throwable) {
            if (!closing.get()) {
                logger.e(tag, "reader loop failed: ${t.message}", t)
                fail(t)
            }
        }
    }

    private fun dispatch(message: AdbMessage) {
        when (message.commandType) {
            Command.WRTE -> dispatchWrite(message)
            Command.CLSE -> {
                logger.i(
                    tag,
                    "CLSE stream=${StreamId.name(message.arg0)} reason='${message.payload.toString(Charsets.UTF_8)}'",
                )
                if (message.arg0 == StreamId.CLOSE_ALL) {
                    notifyClosed("peer CLSE: ${message.payload.toString(Charsets.UTF_8)}")
                } else {
                    sinks[message.arg0]?.let { if (it is Closeable) it.close() }
                    notifyMessage(message)
                }
            }

            Command.CNXN, Command.OPEN, Command.OKAY -> notifyMessage(message)

            null -> {
                // 未知命令字：向前兼容，记日志并忽略，不中断连接（计划 4.2）
                logger.w(
                    tag,
                    "unknown command 0x${Integer.toHexString(message.command)} ignored\n" +
                        Hex.dump(message.encode(), 0, AdbMessage.HEADER_SIZE)
                )
            }
        }
    }

    /**
     * 按流的种类解析 12B 流头。
     *
     * 视频流用 `width`/`height` 作尺寸权威（范围校验能挡掉绝大多数"帧数据被误当流头"）；
     * 音频流上这两个字段是采样率/声道数，必须换成音频的范围校验
     * —— 48000 Hz 会被视频的 `MIN_DIM..MAX_DIM` 判成非法（见 [FrameCodec] 的说明）。
     */
    private fun decodeStreamHeader(payload: ByteArray, streamId: Int): StreamHeader = when (streamId) {
        StreamId.VIDEO -> StreamHeader.decode(payload, 0, payload.size, expectVideoDims = true)
        StreamId.AUDIO -> StreamHeader.decodeAudio(payload, 0, payload.size)
        // 其它流（未来扩展）：只校验 codecId 可打印，数值语义未知
        else -> StreamHeader.decode(payload, 0, payload.size, expectVideoDims = false)
    }

    private fun dispatchWrite(message: AdbMessage) {
        val streamId = message.arg0
        val sink = sinks[streamId]
        if (sink == null) {
            // 未注册接收端的流：记日志并忽略（向前兼容）。
            // 正常不应出现 —— 视频/音频流在 start() 时都已 registerSink。
            logger.w(
                tag,
                "WRTE for unregistered stream ${StreamId.name(streamId)} " +
                    "(${message.payload.size}B) ignored"
            )
            return
        }

        if (headers[streamId] == null) {
            // ★ 该流的首条 WRTE payload 是 12B 流头，只发一次。
            //   但实测会遇到"上一次会话残留在 USB FIFO 里的帧数据"被读到 —— 严格校验会把它
            //   判为非法流头。此时**不能**拿垃圾去 configure 解码器（会静默配错尺寸），
            //   而应当把它当杂散 payload 丢掉，继续等真正的流头。
            val header = try {
                decodeStreamHeader(message.payload, streamId)
            } catch (pe: ProtocolException) {
                val n = stats.strayPayloads.incrementAndGet()
                if (n <= STRAY_WARN_LIMIT || n % 100 == 0L) {
                    logger.w(
                        tag,
                        "stream[${StreamId.name(streamId)}] 首个 payload 不是合法流头" +
                            "(${message.payload.size}B)，按残留字节丢弃（第 $n 次）: ${pe.message}"
                    )
                }
                return
            }
            headers[streamId] = header
            stats.headerAt(streamId, header)
            sink.onHeader(header)
            return
        }

        // ★ 尺寸变更（手机旋转）：发送端**重发 12B 流头**来表达新尺寸。
        //
        //   为什么这样识别是可靠的：帧的 payload 长度是 `12 + size`（size ≥ 1），
        //   所以**载荷恰为 12 字节只可能是流头**，不存在歧义 —— 不需要去猜"内容像不像流头"。
        //
        //   为什么不让 CONFIG 帧顺便带尺寸：CONFIG 的 payload 要**整体作为 csd-0**
        //   注入解码器（见 HostSession），在它前面垫字节会破坏这个前提。
        if (message.payload.size == StreamHeader.SIZE) {
            val updated = try {
                decodeStreamHeader(message.payload, streamId)
            } catch (pe: ProtocolException) {
                logger.w(tag, "12B payload 但不是合法流头，忽略: ${pe.message}")
                null
            }
            if (updated != null && updated != headers[streamId]) {
                val old = headers[streamId]
                headers[streamId] = updated
                stats.headerAt(streamId, updated)
                stats.sizeChanges.incrementAndGet()
                logger.i(
                    tag,
                    "stream[${StreamId.name(streamId)}] 流头更新: $old -> $updated"
                )
                sink.onHeader(updated)
            }
            // 无论是否真的变了，12B 都不可能是帧，直接结束
            return
        }

        val frame = FrameCodec.decodeFrame(message.payload, 0, message.payload.size)
        stats.frameAt(streamId, frame)
        sink.onFrame(frame)
    }

    private fun notifyMessage(message: AdbMessage) {
        val l = listener ?: return
        try {
            l.onMessage(message)
        } catch (t: Throwable) {
            logger.w(tag, "listener.onMessage threw: ${t.message}", t)
        }
    }

    private fun notifyClosed(reason: String) {
        if (closing.getAndSet(true)) return
        running = false
        val l = listener
        closeSinks()
        try {
            l?.onClosed(reason)
        } catch (t: Throwable) {
            logger.w(tag, "listener.onClosed threw: ${t.message}", t)
        }
        // ★ 与 [fail] 同理：会话已经结束，必须把底层通道关掉。
        //   漏掉它就等于漏掉 Android 上的 `/dev/usb_accessory` fd，
        //   会让手机端再也切不进配件模式（宿主侧无限「等待重枚举超时」）。
        closeTransportQuietly()
    }

    /** 关闭底层通道；`transport.close()` 自身幂等，重复调用无害。 */
    private fun closeTransportQuietly() {
        try {
            transport.close()
        } catch (t: Throwable) {
            logger.w(tag, "closing transport failed: ${t.message}", t)
        }
    }

    private fun fail(cause: Throwable) {
        if (closing.getAndSet(true)) return
        running = false
        val l = listener
        closeSinks()
        try {
            l?.onError(cause)
        } catch (t: Throwable) {
            logger.w(tag, "listener.onError threw: ${t.message}", t)
        }
        // ★ 出错也必须把底层通道关掉，否则 **fd 泄漏**。
        //
        //   这里踩过一个很贵的坑：`closing` 在上面已经被置 true，于是之后即便有人
        //   规规矩矩调用 `close()`，也会被 `closing.getAndSet(true)` 挡掉，
        //   `transport.close()` 永远不会执行 —— fd 就这么一直开着。
        //
        //   在 Android 上后果极重：`/dev/usb_accessory` 是**独占打开**的，漏掉的 fd 会
        //   让手机端**再也切不进配件模式**，宿主侧表现为无限「等待重枚举超时」，
        //   只能靠拔插或强杀 App 恢复（实测：停掉 App 释放 fd 后，下一次握手立刻成功）。
        //   而且此刻 transport 已不可用（读到 EIO 之类），继续持有也没有任何意义。
        closeTransportQuietly()
    }

    private fun closeSinks() {
        headers.clear()
        sinks.values.forEach { sink ->
            if (sink is Closeable) {
                try {
                    sink.close()
                } catch (t: Throwable) {
                    logger.w(tag, "closing sink failed: ${t.message}", t)
                }
            }
        }
    }

    override fun close() {
        if (closing.getAndSet(true)) return
        running = false
        writeQueue.clear()
        writeQueue.offer(SHUTDOWN)
        closeSinks()
        closeTransportQuietly()
        readerThread?.interrupt()
        writerThread?.interrupt()
        readerThread = null
        writerThread = null
        logger.i(tag, "connection closed on ${transport.label}")
    }

    private class Outbound(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
        val offset: Int,
        val length: Int,
    )

    /**
     * 累计统计（计划 1.9），两端定期打印以判断链路健康。
     *
     * 口径刻意分开，理由：
     *  - [frames] 只统计**媒体帧**，CONFIG 帧不计入 —— 否则阶段 4.10 的帧率压测会系统性偏大
     *    （旋转重发 CONFIG 时偏差更明显）。
     *  - [bytes] 统计该流 payload 的**全部字节**，含 CONFIG —— 它是链路带宽口径。
     */
    class Stats {
        val messagesRead = AtomicLong()
        val messagesWritten = AtomicLong()

        /** 被当作"非法流头"丢弃的杂散 payload 数（正常应为 0；非 0 说明链路残留了旧会话数据）。 */
        val strayPayloads = AtomicLong()

        /**
         * 流头更新次数（= 尺寸变更 / 旋转次数）。
         *
         * 建链后正常恒为 0；手机旋转时发送端会重发流头，这里每次 +1。
         * 排查"画面方向不对"时先看这个数：不动它就说明手机端根本没上报旋转。
         */
        val sizeChanges = AtomicLong()

        val framesPerStream = ConcurrentHashMap<Int, AtomicLong>()
        val configFramesPerStream = ConcurrentHashMap<Int, AtomicLong>()
        val bytesPerStream = ConcurrentHashMap<Int, AtomicLong>()
        val headersSeen = ConcurrentHashMap<Int, StreamHeader>()

        internal fun frameAt(streamId: Int, frame: Frame) {
            bytesPerStream.computeIfAbsent(streamId) { AtomicLong() }.addAndGet(frame.size.toLong())
            val map = if (frame.isConfig) configFramesPerStream else framesPerStream
            map.computeIfAbsent(streamId) { AtomicLong() }.incrementAndGet()
        }

        internal fun headerAt(streamId: Int, header: StreamHeader) {
            headersSeen[streamId] = header
        }

        /** 媒体帧数（**不含** CONFIG 帧）。 */
        fun frames(streamId: Int): Long = framesPerStream[streamId]?.get() ?: 0L

        /** CONFIG 帧数（每次 configure/重配置 +1）。 */
        fun configFrames(streamId: Int): Long = configFramesPerStream[streamId]?.get() ?: 0L

        /** 该流 payload 的总字节数（含 CONFIG）。 */
        fun bytes(streamId: Int): Long = bytesPerStream[streamId]?.get() ?: 0L

        fun snapshot(): String = buildString {
            append("msgs(r/w)=").append(messagesRead.get()).append('/').append(messagesWritten.get())
            // 这两个只在非 0 时显示，避免正常运行时刷屏；但排查旋转 / 残留字节时是首要线索
            if (sizeChanges.get() > 0L) append(" sizeChanges=").append(sizeChanges.get())
            if (strayPayloads.get() > 0L) append(" stray=").append(strayPayloads.get())
            if (strayPayloads.get() > 0) append(" stray=").append(strayPayloads.get())
            for ((id, h) in headersSeen) {
                append(" | ").append(StreamId.name(id)).append('=')
                    .append(h.codecId).append(' ').append(h.width).append('x').append(h.height)
                    .append(" frames=").append(frames(id))
                    .append(" config=").append(configFrames(id))
                    .append(" bytes=").append(bytes(id))
            }
        }
    }

    private companion object {
        val SHUTDOWN = Outbound(0, 0, 0, AdbMessage.EMPTY, 0, 0)

        /** 杂散 payload 的前若干次逐条告警，之后降频，避免刷屏。 */
        const val STRAY_WARN_LIMIT = 10L
    }
}
