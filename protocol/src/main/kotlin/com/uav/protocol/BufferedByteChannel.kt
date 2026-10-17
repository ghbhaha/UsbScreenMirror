package com.uav.protocol

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * [ByteChannel] 装饰器：**把"上层想要多少"和"向底层实际读多少"解耦**（实施计划 10.16）。
 *
 * ## 为什么必须有它（真机教训，别删）
 *
 * MTK 宿主（P40UCH / mt8781）实测把设备搞出内核 panic：
 * ```
 * usercopy: Kernel memory exposure attempt detected from SLUB object 'kmalloc-128' (offset 0, size 391)!
 * kernel BUG at mm/usercopy.c:99!   Comm: uav-reader
 *   usercopy_abort <- __check_object_size <- do_proc_bulk <- usbdev_ioctl
 * ```
 * 根因：协议层读报文头(24B)/小帧末段(65~128B)时，底层 `bulkTransfer` 也只申请了这么几个字节
 * —— **小于端点 maxPacketSize(512B)**。这台 MTK USB 主机栈不把 `actual_length` 截到请求长度，
 * 设备交付更大的包时，内核拿着 391 去拷一块只有 128B 的 bounce buffer → 加固 usercopy 直接
 * `BUG()` 崩内核、设备重启。
 *
 * ## 解法
 *
 * 本类对外仍满足 [ByteChannel] 契约（短读 / 0 / -1），但对底层**永远整块读**：
 * 每次向 [delegate] 申请 [scratchSize] 字节（须 ≥ 端点包长，推荐 = [com.uav.protocol.Transport]
 * 上层用的 readChunkSize，即 16KB），一次到货多于上层要的，**多余字节留在内部残留缓冲**
 * 供下次 `read` 续上 —— 绝不能丢，否则字节流错位（表现会是"流头乱码/解码器配错"）。
 *
 * 线程模型：与宿主读侧一致（单读线程），但内部仍加锁，残留状态不会被并发读写坏。
 */
class BufferedByteChannel(
    private val delegate: ByteChannel,
    /** 每次向 [delegate] 发起的读长度；必须 ≥ 底层端点的 maxPacketSize（这就是本类存在的意义）。 */
    private val scratchSize: Int,
    override val label: String = delegate.label,
) : ByteChannel {

    init {
        require(scratchSize > 0) { "scratchSize must be > 0, got $scratchSize" }
    }

    private val lock = Any()
    private val scratch = ByteArray(scratchSize)

    /** 残留缓冲：底层一次到货多于上层所需时，多的字节暂存在这里。 */
    private val leftover = ByteArray(scratchSize)
    private var leftoverLen = 0
    private var leftoverPos = 0

    /**
     * 是否已关闭。
     *
     * ★ 必须是 `@Volatile`：`close()` 不再持锁（见下），读线程在锁内也要能看到置位。
     */
    @Volatile
    private var closed = false

    /** 底层被真正读了几次（残留能服务多少上层请求，看这个数最直观）。 */
    val underlyingReads = AtomicLong()

    /** 从残留缓冲直接服务的字节数（这些字节没有触碰底层）。 */
    val bufferedServed = AtomicLong()

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        require(off >= 0 && len >= 0 && off + len <= dst.size) {
            "bad range off=$off len=$len dst=${dst.size}"
        }
        if (len == 0) return 0

        synchronized(lock) {
            if (closed) throw IOException("$label closed")

            // 1) 残留优先：能喂多少喂多少，不碰底层
            if (leftoverPos < leftoverLen) {
                val n = minOf(len, leftoverLen - leftoverPos)
                System.arraycopy(leftover, leftoverPos, dst, off, n)
                leftoverPos += n
                bufferedServed.addAndGet(n.toLong())
                return n
            }

            // 2) 底层整块读 —— 永远 scratchSize，绝不因上层只要 24B 就只申请 24B
            val n = delegate.read(scratch, 0, scratchSize)
            if (n <= 0) return n        // 0（超时/ZLP）/ -1（EOF）原样透传，残留语义不变
            underlyingReads.incrementAndGet()

            if (n <= len) {
                System.arraycopy(scratch, 0, dst, off, n)
                return n
            }

            // 3) 到货多于所需：先给 len 字节，剩余压入残留（下次 read 从这里续）
            System.arraycopy(scratch, 0, dst, off, len)
            System.arraycopy(scratch, len, leftover, 0, n - len)
            leftoverLen = n - len
            leftoverPos = 0
            return len
        }
    }

    override fun write(src: ByteArray, off: Int, len: Int) = delegate.write(src, off, len)

    /**
     * 丢弃残留缓冲里的字节（**不是**关通道）。
     *
     * 用于宿主 `drainStaleInput()` 的时机：丢弃"上一次会话的尾巴"时，
     * 残留里若有旧会话字节也一并作废 —— 否则它们会被新会话当成流头。
     */
    fun discardBuffered() {
        synchronized(lock) {
            leftoverLen = 0
            leftoverPos = 0
        }
    }

    /**
     * 关闭通道。
     *
     * ★ **绝不能 `synchronized(lock)`**（10.33 ANR 修复，真机堆栈实证）：
     *   读线程在锁内做的是**阻塞的 USB 读**（`bulkTransfer`，超时 200ms 起，设备半断时更久），
     *   主线程调 `close()` 会被卡在锁上 —— 实测点"强制重握手"时主线程 Blocked 5 秒，
     *   系统直接 ANR：
     *   ```
     *   "main" prio=5 tid=1 Blocked
     *     at BufferedByteChannel.close(BufferedByteChannel.kt:110)
     *     - waiting to lock held by thread 108
     *   "uav-reader" tid=108 Native
     *     UsbDeviceConnection.bulkTransfer ← BufferedByteChannel.read
     *   ```
     *
     * 现在只置标志（`@Volatile`，读线程下一圈就会看到并退出），再关底层通道
     * （[ByteChannel.close] 的契约是"只置标志、不阻塞"，见 `BulkByteChannel.close`）。
     * 残留缓冲不再清理 —— 关闭之后没有读者，丢了也无所谓。
     */
    override fun close() {
        if (closed) return
        closed = true
        delegate.close()
    }

    fun stats(): String =
        "$label underlyingReads=${underlyingReads.get()} bufferedServed=${bufferedServed.get()}B " +
            "pending=${leftoverLen - leftoverPos}B"
}
