package com.uav.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [BufferedByteChannel] 单测 —— 守住"宿主 usercopy 内核 panic"（实施计划 10.16）的修复。
 *
 * 核心断言只有两条，对应真机 panic 的两个必要条件：
 * 1. **对底层的读长恒为 `scratchSize`**（≥ 端点包长）—— 绝不因上层只要 24B 就向内核申请 24B；
 * 2. **底层一次到货多于上层所需时，多余字节必须留在残留** —— 丢了就是字节流错位，
 *    表现会是"流头乱码 / 解码器配错"，比 panic 更隐蔽。
 */
class BufferedByteChannelTest {

    /**
     * 脚本化底层通道：模拟 `bulkTransfer` 的**包语义** ——
     * 无论被要多少，第 i 次读只回 `script[i]` 字节（受剩余量约束）；脚本耗尽后按请求长度回。
     * 记录每次被申请的读长，供"永不小于端点包长"的断言用。
     */
    private class ScriptedChannel(
        private val data: ByteArray,
        private val script: List<Int> = emptyList(),
    ) : ByteChannel {

        private var pos = 0
        private var calls = 0
        val requested = mutableListOf<Int>()
        var closeCalled = false
            private set

        override val label: String get() = "scripted(calls=$calls)"

        override fun read(dst: ByteArray, off: Int, len: Int): Int {
            requested += len
            val idx = calls++
            if (pos >= data.size) return -1
            val want = if (idx < script.size) script[idx] else len
            if (want < 0) return want                  // 脚本负值 = 直接透传（EOF）
            val n = minOf(want, data.size - pos)
            System.arraycopy(data, pos, dst, off, n)
            pos += n
            return n
        }

        override fun write(src: ByteArray, off: Int, len: Int) = Unit

        override fun close() {
            closeCalled = true
        }
    }

    // ---------------------------------------------------------- 复现真机 panic 场景

    @Test
    fun `panic场景 上层要24而底层一次到391 只给24且一个字节都不丢`() {
        // 391B 正是真机 panic 里 actual_length=391 的那个包
        val data = ByteArray(391) { (it * 7).toByte() }
        val under = ScriptedChannel(data, script = listOf(391))
        val ch = BufferedByteChannel(under, scratchSize = 16384)

        val head = ByteArray(24)
        assertEquals(24, ch.read(head, 0, 24))
        assertArrayEquals(data.copyOfRange(0, 24), head)

        // ★ 底层只被申请过一次，且申请的是整块 16384 —— 不是 24
        assertEquals(listOf(16384), under.requested)

        // 残留续读：接下来的读不再碰底层
        val next = ByteArray(100)
        assertEquals(100, ch.read(next, 0, 100))
        assertArrayEquals(data.copyOfRange(24, 124), next)
        assertEquals(1, under.requested.size)
        assertEquals(100L, ch.bufferedServed.get())

        // 把剩余 267 字节也吃完，字节序严格连续
        val rest = ByteArray(391 - 24 - 100)
        assertEquals(rest.size, ch.read(rest, 0, rest.size))
        assertArrayEquals(data.copyOfRange(124, 391), rest)
        assertEquals(1, under.requested.size)          // 391B 一次底层读喂饱了三次上层读

        // 残留耗尽后才真正再读底层（数据已尽 → EOF）
        assertEquals(-1, ch.read(next, 0, next.size))
        assertEquals(2, under.requested.size)
    }

    @Test
    fun `向底层申请的读长恒为 scratchSize 绝不小于端点包长`() {
        val data = ByteArray(64) { it.toByte() }
        val under = ScriptedChannel(data, script = listOf(64))
        val ch = BufferedByteChannel(under, scratchSize = 16384)

        val out = ByteArray(5)
        var got = 0
        while (got < 64) {
            val n = ch.read(out, 0, out.size)
            if (n <= 0) break
            got += n
        }
        assertEquals(64, got)
        assertTrue("底层请求：${under.requested}", under.requested.isNotEmpty())
        assertTrue(
            "每次对底层的读都必须是整块 16384（端点包长 512 的整数倍）：${under.requested}",
            under.requested.all { it == 16384 },
        )
    }

    // ---------------------------------------------------------- 0 / -1 / 关闭 / 丢弃

    @Test
    fun `底层的 0 与 -1 原样透传且不清残留`() {
        // 数据故意比脚本交付量长：否则 pos 尽头会先返回 -1，轮不到脚本注入 0
        val data = ByteArray(200) { it.toByte() }
        val under = ScriptedChannel(data, script = listOf(100, 0, 0, -1))
        val ch = BufferedByteChannel(under, scratchSize = 512)

        val a = ByteArray(30)
        assertEquals(30, ch.read(a, 0, 30))            // 底层回 100，剩 70 进残留
        val b = ByteArray(70)
        assertEquals(70, ch.read(b, 0, 70))            // 吃光残留（不碰底层）
        assertArrayEquals(data.copyOfRange(30, 100), b)
        assertEquals(1, under.requested.size)

        assertEquals(0, ch.read(a, 0, 30))             // 脚本 0：超时/ZLP，透传
        assertEquals(0, ch.read(a, 0, 30))
        assertEquals(-1, ch.read(a, 0, 30))            // EOF 透传
        assertEquals(listOf(512, 512, 512, 512), under.requested)
    }

    @Test
    fun `discardBuffered 丢弃残留 下次读直接走底层`() {
        val data = ByteArray(300) { it.toByte() }
        val under = ScriptedChannel(data, script = listOf(200, 50))
        val ch = BufferedByteChannel(under, scratchSize = 1024)

        val a = ByteArray(30)
        assertEquals(30, ch.read(a, 0, 30))
        assertEquals(170, ch.stats().substringAfter("pending=").substringBefore('B').toInt())

        ch.discardBuffered()                            // 丢弃"上一次会话的尾巴"

        val b = ByteArray(50)
        assertEquals(50, ch.read(b, 0, 50))
        assertArrayEquals(data.copyOfRange(200, 250), b)   // 旧残留没有泄进来
    }

    @Test
    fun `close 后 read 抛 IOException 且透传给底层`() {
        val under = ScriptedChannel(ByteArray(16))
        val ch = BufferedByteChannel(under, scratchSize = 64)
        ch.close()
        assertTrue(under.closeCalled)
        assertThrows(IOException::class.java) { ch.read(ByteArray(4), 0, 4) }
    }

    // ---------------------------------------------------------- 契约与边界

    @Test
    fun `边界 len=0 与越界区间`() {
        val ch = BufferedByteChannel(ScriptedChannel(ByteArray(16)), scratchSize = 64)
        assertEquals(0, ch.read(ByteArray(4), 0, 0))
        assertThrows(IllegalArgumentException::class.java) { ch.read(ByteArray(4), 2, 4) }
        assertThrows(IllegalArgumentException::class.java) { ch.read(ByteArray(4), -1, 4) }
    }

    @Test
    fun `scratchSize 必须 为正`() {
        assertThrows(IllegalArgumentException::class.java) {
            BufferedByteChannel(ScriptedChannel(ByteArray(1)), scratchSize = 0)
        }
    }

    /**
     * 整合测试：包上 [Transport] 后，用各种尺寸的 `readFully` 混合读 ——
     * 字节序必须严格不乱（残留续读在任何交错下都不能丢字/重字）。
     */
    @Test
    fun `经 Transport 混合尺寸 readFully 数据序严格不乱`() {
        val data = ByteArray(50_000) { (it * 31 + 7).toByte() }
        // 底层每次乱给一段，模拟真实包语义（短包整包交错）
        val under = ScriptedChannel(data, script = List(200) { 137 + (it % 5) * 311 })
        val ch = BufferedByteChannel(under, scratchSize = 4096)
        val t = Transport(ch, readTimeoutMs = 10_000)

        val out = ByteArray(data.size)
        val sizes = listOf(24, 391, 1, 5000, 65, 128, 8192, 17, 100, 3, 20000, 16384, 65536)
        var off = 0
        var i = 0
        while (off < out.size) {
            val want = minOf(sizes[i % sizes.size], out.size - off)
            t.readFully(out, off, want)
            off += want
            i++
        }
        assertArrayEquals(data, out)
    }
}
