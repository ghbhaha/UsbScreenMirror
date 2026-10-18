package com.uav.accessory.usb

import android.os.ParcelFileDescriptor
import com.uav.accessory.common.AndroidLogger
import com.uav.protocol.ByteChannel
import com.uav.protocol.Logger
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 手机侧 [ByteChannel] 实现 —— **整个手机端唯一需要写 USB 代码的地方**（计划 1.1 / 3.2 / 3.3 / 3.6）。
 *
 * 与宿主侧的不对称性：
 *  - 这里是**流**语义（`FileInputStream`/`FileOutputStream` 包着 accessory fd），可短读；
 *  - **写无法设超时** —— `FileOutputStream` 没有超时概念。当宿主停止读取时，
 *    写会**永久阻塞**在管道缓冲区满的那一刻。
 *
 * 因此"写超时 5s"这条规则在手机侧只能靠**看门狗线程**落地（计划 3.6）：
 * 每次写之前起一个 5s 的定时任务，超时就 `close()` 掉 fd，
 * 让阻塞中的 `write` 立刻抛 `IOException`，从而把"永久挂起"变成"可处理的错误"。
 */
class FdByteChannel(
    private val pfd: ParcelFileDescriptor,
    private val logger: Logger = AndroidLogger,
    /** 单次写的最长允许时间，超过则强关 fd 打断阻塞（计划 4.6）。 */
    private val writeTimeoutMs: Long = 5000,
) : ByteChannel {

    override val label: String = "accessory-fd(${pfd.fd})"

    private val input = FileInputStream(pfd.fileDescriptor)
    private val output = FileOutputStream(pfd.fileDescriptor)

    private val closed = AtomicBoolean(false)

    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "uav-fd-watchdog").apply { isDaemon = true }
    }

    val bytesRead = AtomicLong()
    val bytesWritten = AtomicLong()
    val writeTimeouts = AtomicLong()

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (closed.get()) throw IOException("$label closed")
        val n = input.read(dst, off, len)
        if (n > 0) bytesRead.addAndGet(n.toLong())
        return n // -1 = EOF；0 = 本次没数据（非致命）
    }

    override fun write(src: ByteArray, off: Int, len: Int) {
        if (closed.get()) throw IOException("$label closed")
        if (len == 0) return

        // ★ 看门狗：写超时则强关 fd，打断阻塞写
        val timeoutTask: ScheduledFuture<*> = watchdog.schedule({
            writeTimeouts.incrementAndGet()
            logger.e(
                TAG,
                "写超时 ${writeTimeoutMs}ms（宿主可能已停止读取）—— 强制关闭 fd 以打断阻塞写"
            )
            forceClose()
        }, writeTimeoutMs, TimeUnit.MILLISECONDS)

        try {
            output.write(src, off, len)
            output.flush()
            bytesWritten.addAndGet(len.toLong())
        } catch (io: IOException) {
            if (closed.get()) {
                throw IOException("$label 写被强制中断（超时或已关闭）", io)
            }
            throw io
        } finally {
            timeoutTask.cancel(false)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            logger.i(TAG, "$label closing (rx=${bytesRead.get()} tx=${bytesWritten.get()})")
        }
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { pfd.close() }
        watchdog.shutdownNow()
    }

    /** 只关 fd，不做其余清理（看门狗在超时路径上调用）。 */
    private fun forceClose() {
        closed.set(true)
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { pfd.close() }
        // watchdog 自身线程若在此被 shutdownNow 会自杀，故只置标志
    }

    fun stats(): String =
        "$label rx=${bytesRead.get()}B tx=${bytesWritten.get()}B timeouts=${writeTimeouts.get()}"

    companion object {
        const val TAG = "UAV-Accessory"
    }
}
