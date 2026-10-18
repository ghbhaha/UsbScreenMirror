package com.uav.protocol.aoa

import com.uav.protocol.ByteChannel
import com.uav.protocol.adb.AdbMessage
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：**会话结束时必须释放底层通道**（无论哪条路径）。
 *
 * 为什么值得单独盯住：在 Android 上底层通道就是 `/dev/usb_accessory` 的 fd，
 * 而它是**独占打开**的。漏掉这个 fd，手机端就**再也切不进配件模式** ——
 * 宿主侧表现为无限「等待重枚举超时」，只能靠拔插或强杀 App 恢复。
 *
 * 真实故障链：`Connection.fail()` / `notifyClosed()` 把 `closing` 置 true 后
 * **没有** `transport.close()`；而 `closing` 已为 true 又会让后续任何 `close()`
 * 直接 return —— 于是 fd 永远关不掉。真机上表现为"链路断了就再也连不回来"。
 */
class ConnectionResourceTest {

    /** 记录 [close] 是否被调用。 */
    private interface CloseRecording {
        val closed: Boolean
    }

    /** 读必然失败（模拟 EIO：USB 被拔掉/重枚举）。 */
    private class FailingChannel : ByteChannel, CloseRecording {
        @Volatile
        override var closed = false

        override val label: String get() = "failing"

        override fun read(dst: ByteArray, off: Int, len: Int): Int =
            throw IOException("simulated EIO")

        override fun write(src: ByteArray, off: Int, len: Int) = Unit

        override fun close() {
            closed = true
        }
    }

    /** 立刻返回 -1（EOF：对端关闭）。 */
    private class EofChannel : ByteChannel, CloseRecording {
        @Volatile
        override var closed = false

        override val label: String get() = "eof"

        override fun read(dst: ByteArray, off: Int, len: Int): Int = -1

        override fun write(src: ByteArray, off: Int, len: Int) = Unit

        override fun close() {
            closed = true
        }
    }

    private class Recorder : ConnectionListener {
        val errored = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun onMessage(message: AdbMessage) = Unit
        override fun onError(cause: Throwable) {
            errored.countDown()
        }

        override fun onClosed(reason: String) {
            closed.countDown()
        }
    }

    private fun awaitClose(channel: CloseRecording): Boolean {
        val deadline = System.currentTimeMillis() + 2_000
        while (!channel.closed && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        return channel.closed
    }

    @Test
    fun `读链路出错时必须关闭底层通道`() {
        val channel = FailingChannel()
        val recorder = Recorder()
        val conn = Connection(Transport(channel), listener = recorder)
        conn.start()

        assertTrue("onError 未被回调", recorder.errored.await(5, TimeUnit.SECONDS))
        assertTrue(
            "出错后底层通道没被关闭 —— 真机上会漏掉配件 fd，导致手机端永久切不进配件模式",
            awaitClose(channel),
        )
    }

    @Test
    fun `对端正常关闭时也必须关闭底层通道`() {
        val channel = EofChannel()
        val recorder = Recorder()
        val conn = Connection(Transport(channel), listener = recorder)
        conn.start()

        assertTrue("onClosed 未被回调", recorder.closed.await(5, TimeUnit.SECONDS))
        assertTrue(
            "EOF 路径漏关通道 —— 同样会漏 fd（宿主 App 退出时就走的这条路）",
            awaitClose(channel),
        )
    }

    @Test
    fun `出错之后调用 close 也不会漏掉通道`() {
        val channel = FailingChannel()
        val recorder = Recorder()
        val conn = Connection(Transport(channel), listener = recorder)
        conn.start()
        recorder.errored.await(5, TimeUnit.SECONDS)

        // 这一句曾经是"看起来正确、实际空转"的：fail() 已把 closing 置 true，
        // 老的 close() 会直接 return，transport 永远不会被关。
        conn.close()

        assertTrue(channel.closed)
    }
}
