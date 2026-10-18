package com.uav.protocol.aoa

import com.uav.protocol.ByteChannel
import com.uav.protocol.Logger
import com.uav.protocol.ProtocolException
import com.uav.protocol.w
import java.io.Closeable
import java.io.EOFException
import java.util.concurrent.atomic.AtomicLong

/**
 * 协议层传输封装 —— 上层（会话编排 / 编码器输出）**只依赖它，永不感知 USB**（计划 1.1）。
 *
 * 三件核心职责：
 *
 * 1. **凑满读**：`readFully` 阻塞到填满 `len` 字节为止。
 * 2. **丢弃 0 字节完成**（★ ZLP 容错）：底层 `read` 返回 0 是"本次没事发生"，
 *    既不是数据也不是 EOF，必须丢弃并重试。这一步若漏掉，上层会拿到"空缓冲"，
 *    在 host 侧表现为空指针/越界，在链路层表现为**间歇性断流**（计划 5 风险表首条）。
 *    封在这里 ⇒ 只写一次、且能在 JVM 单测里注入验证（单测 4）。
 * 3. **出站串行化**：所有写经同一把锁，杜绝多线程交错把报文写坏（计划 4.8 单写线程）。
 *
 * 契约：**`readFully` 永不向调用方返回 0**（除 EOF 抛 `EOFException`、超时抛 `SocketTimeoutException`）。
 */
class Transport(
    private val channel: ByteChannel,
    private val logger: Logger = Logger.NONE,
    private val tag: String = Protocol.TAG,
    /**
     * 单次 [readFully] 的总等待上限（毫秒）。`<= 0` 表示不限。
     * 默认 15s：单帧上限 8 MiB，USB2 实测约 30 MB/s，正常远小于此值。
     */
    private val readTimeoutMs: Long = 15_000,
) : Closeable {

    /** 累计读到的字节数（不含被丢弃的 0 字节完成）。 */
    val bytesRead = AtomicLong()

    /** 累计写出的字节数。 */
    val bytesWritten = AtomicLong()

    /** 被丢弃的「0 字节完成」次数（ZLP / 读超时）。持续增长说明底层在补零长包。 */
    val zeroByteReads = AtomicLong()

    /** 底层通道的可读名称，用于日志。 */
    val label: String get() = channel.label

    /**
     * 阻塞直到 `[off, off+len)` 全部填满。
     *
     * @throws EOFException               对端关闭且未读满
     * @throws java.net.SocketTimeoutException 超过 [readTimeoutMs] 仍未读满
     * @throws ProtocolException          `len` 超过单次请求上限
     */
    fun readFully(dst: ByteArray, off: Int, len: Int) {
        require(off >= 0 && len >= 0 && off + len <= dst.size) {
            "bad range off=$off len=$len dst=${dst.size}"
        }
        if (len == 0) return
        if (len > Protocol.MAX_PAYLOAD_SIZE) {
            throw ProtocolException("readFully request too large: $len > ${Protocol.MAX_PAYLOAD_SIZE}")
        }

        val deadline = if (readTimeoutMs > 0) System.nanoTime() + readTimeoutMs * 1_000_000 else Long.MAX_VALUE
        var got = 0
        while (got < len) {
            // ★ 单次向底层请求不超过 64 KiB（计划 4.6），大帧自动分多次读
            val want = minOf(len - got, Protocol.MAX_READ_REQUEST)
            val n = channel.read(dst, off + got, want)

            when {
                n < 0 -> throw EOFException(
                    "${channel.label}: EOF after $got/$len bytes"
                )

                n == 0 -> {
                    // ★ ZLP 或读超时 —— 丢弃、重试，绝不让 0 字节冒泡到上层
                    zeroByteReads.incrementAndGet()
                    if (System.nanoTime() > deadline) {
                        throw java.net.SocketTimeoutException(
                            "${channel.label}: readFully timeout after $got/$len bytes " +
                                "(zeroByteReads=${zeroByteReads.get()})"
                        )
                    }
                    // 不 sleep：宿主侧 bulkTransfer 自身带 200ms 超时才返回 0，不会忙转；
                    // 若底层是"空转返回 0"的实现，这里让出一小段时间避免烧 CPU。
                    Thread.yield()
                }

                else -> {
                    got += n
                    bytesRead.addAndGet(n.toLong())
                }
            }
        }
    }

    /**
     * 读「一小块」交给状态机用，避免为 24B 报文头做 16KB 凑满（计划 1.1 注释）。
     *
     * @return 实际读到的字节数（可为 0 = 本次无数据，非致命）；-1 = EOF
     */
    fun readAtMost(dst: ByteArray, off: Int, len: Int): Int {
        require(off >= 0 && len >= 0 && off + len <= dst.size) {
            "bad range off=$off len=$len dst=${dst.size}"
        }
        if (len == 0) return 0
        val want = minOf(len, Protocol.MAX_READ_REQUEST)
        val n = channel.read(dst, off, want)
        when {
            n < 0 -> return -1
            n == 0 -> zeroByteReads.incrementAndGet()
            else -> bytesRead.addAndGet(n.toLong())
        }
        return n
    }

    /** 写出全部 [len] 字节；与其他写调用串行化。 */
    fun write(src: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        synchronized(writeLock) {
            channel.write(src, off, len)
            bytesWritten.addAndGet(len.toLong())
        }
    }

    fun write(src: ByteArray) = write(src, 0, src.size)

    override fun close() {
        try {
            channel.close()
        } catch (t: Throwable) {
            logger.w(tag, "close channel failed: ${t.message}", t)
        }
    }

    private val writeLock = Any()
}
