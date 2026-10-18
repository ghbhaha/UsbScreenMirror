package com.uav.protocol.aoa

import com.uav.protocol.FakeByteChannel
import com.uav.protocol.MemoryPipe
import com.uav.protocol.ProtocolException
import java.io.EOFException
import java.net.SocketTimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单测 4：**ZLP 容错** —— 这是初版计划无法验证、也是"投屏间歇性断流"的根因所在（计划 5 风险表首条）。
 */
class TransportTest {

    // ------------------------------------------------------------------ 单测 4

    @Test
    fun `注入偶尔返回 0 字节的通道 readFully 仍凑满`() {
        val data = ByteArray(1000) { (it % 251).toByte() }
        // 每 3 次 read 就有 1 次返回 0（模拟 ZLP / 读超时），且每次最多给 7 字节
        val ch = FakeByteChannel(data, chunk = 7, zeroEvery = 3)
        val t = Transport(ch, readTimeoutMs = 5_000)

        val out = ByteArray(1000)
        t.readFully(out, 0, 1000)

        assertArrayEquals(data, out)
        assertTrue("必须确实注入过 0 字节完成", ch.zeroReturns > 0)
        assertEquals(ch.zeroReturns.toLong(), t.zeroByteReads.get())
        assertEquals(1000L, t.bytesRead.get())
    }

    @Test
    fun `0 字节完成绝不会越界写入缓冲区之外`() {
        val data = ByteArray(300) { it.toByte() }
        val ch = FakeByteChannel(data, chunk = 11, zeroEvery = 2)
        val t = Transport(ch, readTimeoutMs = 5_000)

        // 目标缓冲比请求区间大，两侧填充哨兵值
        val dst = ByteArray(500) { 0x7F }
        t.readFully(dst, 100, 300)

        for (i in 0 until 100) assertEquals("左哨兵被改: $i", 0x7F.toByte(), dst[i])
        for (i in 400 until 500) assertEquals("右哨兵被改: $i", 0x7F.toByte(), dst[i])
        for (i in 0 until 300) assertEquals(data[i], dst[100 + i])
    }

    @Test
    fun `单次向底层请求不超过 64 KiB`() {
        val size = 200 * 1024
        val data = ByteArray(size) { (it % 97).toByte() }
        val ch = FakeByteChannel(data, chunk = Int.MAX_VALUE) // 底层愿意一次给完
        val t = Transport(ch, readTimeoutMs = 10_000)

        val out = ByteArray(size)
        t.readFully(out, 0, size)

        assertArrayEquals(data, out)
        assertTrue(
            "单次请求 ${ch.maxRequested}B 超过 64KiB 上限",
            ch.maxRequested <= Protocol.MAX_READ_REQUEST
        )
        assertEquals(64 * 1024, Protocol.MAX_READ_REQUEST)
    }

    // ------------------------------------------------------------------ 其它契约

    @Test
    fun `先无数据后到达也能凑满（阻塞直到够）`() {
        val (a, b) = MemoryPipe.pair()
        val t = Transport(a, readTimeoutMs = 5_000)

        val writer = Thread {
            Thread.sleep(50)
            b.write(byteArrayOf(1, 2, 3, 4), 0, 4)
        }
        writer.start()

        val out = ByteArray(4)
        t.readFully(out, 0, 4)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), out)
        writer.join()
    }

    @Test
    fun `对端关闭时抛 EOFException`() {
        val t = Transport(FakeByteChannel(byteArrayOf(1, 2)), readTimeoutMs = 2_000)
        assertThrows(EOFException::class.java) {
            t.readFully(ByteArray(10), 0, 10)
        }
    }

    @Test
    fun `持续返回 0 且超过上限时抛超时异常而非死等`() {
        // zeroEvery = 1 -> 每次 read 都返回 0
        val ch = FakeByteChannel(ByteArray(10), zeroEvery = 1)
        val t = Transport(ch, readTimeoutMs = 150)

        val started = System.nanoTime()
        assertThrows(SocketTimeoutException::class.java) {
            t.readFully(ByteArray(4), 0, 4)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("应当快速超时而不是长挂，实际 ${elapsedMs}ms", elapsedMs < 3_000)
    }

    @Test
    fun `超过单次请求上限的 readFully 直接判协议错误`() {
        val t = Transport(FakeByteChannel(ByteArray(0)), readTimeoutMs = 1_000)
        assertThrows(ProtocolException::class.java) {
            t.readFully(ByteArray(Protocol.MAX_PAYLOAD_SIZE + 1), 0, Protocol.MAX_PAYLOAD_SIZE + 1)
        }
    }

    @Test
    fun `readAtMost 允许返回 0 但不吞掉 EOF`() {
        val ch = FakeByteChannel(byteArrayOf(9, 8, 7), chunk = 2)
        val t = Transport(ch, readTimeoutMs = 1_000)

        val buf = ByteArray(4)
        assertEquals(2, t.readAtMost(buf, 0, 4))
        assertEquals(1, t.readAtMost(buf, 0, 4))
        assertEquals(-1, t.readAtMost(buf, 0, 4)) // EOF
    }

    @Test
    fun `写路径累计字节数并原样送达`() {
        val ch = FakeByteChannel(ByteArray(0))
        val t = Transport(ch)
        val payload = "hello".toByteArray()

        t.write(payload, 0, payload.size)
        t.write(payload, 0, payload.size)

        assertEquals(10L, t.bytesWritten.get())
        assertEquals("hellohello", ch.written.toString("UTF-8"))
    }
}
