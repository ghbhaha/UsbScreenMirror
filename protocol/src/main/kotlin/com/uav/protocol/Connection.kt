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

    private fun dispatchWrite(message: AdbMessage) {
        val streamId = message.arg0
        val sink = sinks[streamId]
        if (sink == null) {
            // 未知流 id：记日志并忽略（向前兼容）。audio 在 MVP 中不存在，故不刷屏警告。
            logger.w(
                tag,
                "WRTE for unregistered stream ${StreamId.name(streamId)} " +
                    "(${message.payload.size}B) ignored"
            )
            return
        }

        if (headers[streamId] == null) {
            // ★ 该流的首条 WRTE payload 是 12B 流头，只发一次
            val header = StreamHeader.decode(message.payload, 0, message.payload.size)
            headers[streamId] = header
            stats.headerAt(streamId, header)
            sink.onHeader(header)
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
        transport.close()
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
     */
    class Stats {
        val messagesRead = AtomicLong()
        val messagesWritten = AtomicLong()
        val framesPerStream = ConcurrentHashMap<Int, AtomicLong>()
        val bytesPerStream = ConcurrentHashMap<Int, AtomicLong>()
        val headersSeen = ConcurrentHashMap<Int, StreamHeader>()

        internal fun frameAt(streamId: Int, frame: Frame) {
            framesPerStream.computeIfAbsent(streamId) { AtomicLong() }.incrementAndGet()
            bytesPerStream.computeIfAbsent(streamId) { AtomicLong() }.addAndGet(frame.size.toLong())
        }

        internal fun headerAt(streamId: Int, header: StreamHeader) {
            headersSeen[streamId] = header
        }

        fun frames(streamId: Int): Long = framesPerStream[streamId]?.get() ?: 0L

        fun bytes(streamId: Int): Long = bytesPerStream[streamId]?.get() ?: 0L

        fun snapshot(): String = buildString {
            append("msgs(r/w)=").append(messagesRead.get()).append('/').append(messagesWritten.get())
            for ((id, h) in headersSeen) {
                append(" | ").append(StreamId.name(id)).append('=')
                    .append(h.codecId).append(' ').append(h.width).append('x').append(h.height)
                    .append(" frames=").append(frames(id))
                    .append(" bytes=").append(bytes(id))
            }
        }
    }

    private companion object {
        val SHUTDOWN = Outbound(0, 0, 0, AdbMessage.EMPTY, 0, 0)
    }
}
