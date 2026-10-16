package com.uav.host.usb

import android.hardware.usb.UsbDeviceConnection
import com.uav.host.AndroidLogger
import com.uav.protocol.ByteChannel
import com.uav.protocol.Logger
import com.uav.protocol.v
import com.uav.protocol.w
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 宿主侧 [ByteChannel] 实现 —— **整个宿主端唯一需要写 USB 代码的地方**（计划 1.1 / 2.9）。
 *
 * 承担两件"把 USB 的包语义伪装成流语义"的事：
 *
 * 1. **读侧拼流**：`bulkTransfer` 是**包**语义，可能短读、可能返回 0/负值。
 *    本类在 [readPollWindowMs] 窗口内反复重试，凑到至少 1 字节就返回；
 *    窗口内无数据则返回 `0`（**非致命**），由 [com.uav.protocol.Transport] 决定后续重试或超时。
 *    ⚠️ 这里绝不能把"0 字节完成"当成 EOF —— 那正是"投屏间歇性断流"的经典成因。
 *
 * 2. **写侧防 ZLP**：当一次写出的总长度恰为 `maxPacketSize` 整数倍时，
 *    补发一个零长包，避免对端把"整包传输"误当作还有后续数据而空等。
 *    该行为是 best-effort（部分机型/内核会直接返回错误），故失败只记日志不抛。
 */
class BulkByteChannel(
    private val connection: UsbDeviceConnection,
    private val pair: BulkPair,
    private val logger: Logger = AndroidLogger,
    /** 单次 bulkTransfer 读超时，短超时便于循环检查退出标志（计划 4.6）。 */
    private val readTimeoutMs: Int = 200,
    /** 单次 bulkTransfer 写超时，大帧不拆包断流（计划 4.6）。 */
    private val writeTimeoutMs: Int = 5000,
    /** 单次 read() 内部的重试窗口；窗口内无数据则返回 0。 */
    private val readPollWindowMs: Long = 1000,
    /** 是否在写满整数包时补零长包。 */
    private val sendZlpWhenAligned: Boolean = true,
) : ByteChannel {

    override val label: String =
        "usb:in=0x${Integer.toHexString(pair.inEndpoint.address)}" +
            "/out=0x${Integer.toHexString(pair.outEndpoint.address)}"

    private val closed = AtomicBoolean(false)

    val bytesReceived = AtomicLong()
    val bytesSent = AtomicLong()
    val readTimeouts = AtomicLong()
    val writeFailures = AtomicLong()
    val zlpSent = AtomicLong()

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (closed.get()) throw IOException("$label closed")
        if (len == 0) return 0

        val max = minOf(len, pair.readChunkSize)
        val deadline = System.nanoTime() + readPollWindowMs * 1_000_000L

        while (true) {
            if (closed.get()) throw IOException("$label closed during read")
            val n = connection.bulkTransfer(pair.inEndpoint, dst, off, max, readTimeoutMs)
            if (n > 0) {
                bytesReceived.addAndGet(n.toLong())
                return n
            }
            // n == 0（零长包）或 n < 0（本次超时/失败）都**不是** EOF：
            // 在窗口内继续重试，超窗口后如实返回 0，交由 Transport 判定。
            readTimeouts.incrementAndGet()
            if (System.nanoTime() > deadline) return 0
        }
    }

    override fun write(src: ByteArray, off: Int, len: Int) {
        if (closed.get()) throw IOException("$label closed")
        if (len == 0) return

        var written = 0
        while (written < len) {
            val chunk = minOf(len - written, WRITE_CHUNK)
            val n = connection.bulkTransfer(pair.outEndpoint, src, off + written, chunk, writeTimeoutMs)
            if (n <= 0) {
                writeFailures.incrementAndGet()
                throw IOException("$label bulk write failed: n=$n (written=$written/$len)")
            }
            written += n
        }
        bytesSent.addAndGet(written.toLong())
        maybeSendZlp(written)
    }

    /**
     * 长度恰为整数包时补零长包（best-effort）。
     *
     * 失败只记日志：某些内核/机型对 0 长度 bulk 直接返回错误，
     * 而这并不代表数据没发出去。
     */
    private fun maybeSendZlp(written: Int) {
        if (!sendZlpWhenAligned) return
        val pkt = pair.outEndpoint.maxPacketSize
        if (pkt <= 0 || written % pkt != 0) return
        try {
            connection.bulkTransfer(pair.outEndpoint, EMPTY, 0, 0, writeTimeoutMs)
            zlpSent.incrementAndGet()
            logger.v(TAG, "$label ZLP sent (written=$written, maxPacketSize=$pkt)")
        } catch (t: Throwable) {
            logger.w(TAG, "$label ZLP send failed (ignored): ${t.message}")
        }
    }

    override fun close() {
        closed.set(true)
    }

    /**
     * 丢掉 claim 之后残留在入站端点里的字节 —— **上一次会话的尾巴**。
     *
     * 实测踩到的坑：上一次会话结束时手机端可能又写了几帧，这些字节躺在 USB FIFO 里；
     * 新会话开始时，宿主按"该流首个 `WRTE` 即流头"去解析，就会把一帧数据当成 12B 流头，
     * 得到 `StreamHeader(乱码 8x466)` 这种结果并**静默配错解码器**。
     *
     * 调用时机：claimInterface 成功之后、发第一个 `CNXN` 之前（此刻配件端不应当有正经数据在飞）。
     *
     * @return 丢弃的字节数
     */
    fun drainStaleInput(maxWaitMs: Long = 300, perReadTimeoutMs: Int = 50): Int {
        var total = 0
        val deadline = System.nanoTime() + maxWaitMs * 1_000_000L
        val buf = ByteArray(pair.readChunkSize)
        while (System.nanoTime() < deadline) {
            if (closed.get()) break
            val n = try {
                connection.bulkTransfer(pair.inEndpoint, buf, 0, buf.size, perReadTimeoutMs)
            } catch (t: Throwable) {
                logger.w(TAG, "$label drain 读取异常（忽略）: ${t.message}")
                break
            }
            if (n > 0) {
                total += n
                continue // 还有残留，继续
            }
            break // 0 / 负值 = 当前已无更多残留
        }
        if (total > 0) {
            logger.w(
                TAG,
                "$label 丢弃 $total 字节入站残留（上一次会话的尾巴）—— " +
                    "否则会被误当成流头去 configure 解码器"
            )
        }
        return total
    }

    fun stats(): String =
        "$label rx=${bytesReceived.get()}B tx=${bytesSent.get()}B " +
            "readTimeouts=${readTimeouts.get()} writeFailures=${writeFailures.get()} " +
            "zlp=${zlpSent.get()}"

    companion object {
        const val TAG = "UAV-Host"

        /** 单次 bulkTransfer 写的最大长度。 */
        private const val WRITE_CHUNK = 16384

        private val EMPTY = ByteArray(0)
    }
}
