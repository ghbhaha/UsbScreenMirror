package com.uav.protocol.aoa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GopTailGate] 单测：时钟可注入，所以超时路径是确定性的。 */
class GopTailGateTest {

    private var now = 0L
    private fun gate(timeoutMs: Long = 600L) = GopTailGate(timeoutMs * 1_000_000L) { now }

    @Test
    fun `未 arm 时一律放行`() {
        val g = gate()
        assertFalse(g.isWaiting)
        repeat(5) { assertTrue(g.shouldEmit(isKeyFrame = false)) }
    }

    @Test
    fun `arm 之后非关键帧被丢弃`() {
        val g = gate()
        g.arm()
        assertTrue(g.isWaiting)
        assertFalse(g.shouldEmit(isKeyFrame = false))
        assertFalse(g.shouldEmit(isKeyFrame = false))
    }

    @Test
    fun `arm 之后第一个关键帧放行并解除等待`() {
        val g = gate()
        g.arm()
        assertFalse(g.shouldEmit(false))
        assertTrue("IDR 必须放行（它才是宿主能解码的那一帧）", g.shouldEmit(true))
        assertFalse(g.isWaiting)
        // 之后回到普通状态：P 帧照发
        assertTrue(g.shouldEmit(false))
    }

    @Test
    fun `arm 之后第一帧就是 IDR：立即放行`() {
        val g = gate()
        g.arm()
        assertTrue(g.shouldEmit(true))
        assertFalse(g.isWaiting)
    }

    @Test
    fun `超时兜底：等不到 IDR 也放行（宁可花屏不要黑屏）`() {
        val g = gate(timeoutMs = 600L)
        g.arm()
        now = 100_000_000L                       // 100ms：还在等
        assertFalse(g.shouldEmit(false))
        now = 599_000_000L                       // 刚好不到 600ms
        assertFalse(g.shouldEmit(false))
        now = 600_000_000L                       // 到点
        assertTrue("超时后必须放行", g.shouldEmit(false))
        assertFalse(g.isWaiting)
    }

    @Test
    fun `超时计时从 arm 起算（不是从上一帧起算）`() {
        val g = gate(timeoutMs = 600L)
        now = 5_000_000_000L                     // 挂钟已经很大
        g.arm()                                   // 计时必须从此刻开始
        assertFalse(g.shouldEmit(false))
        now += 600_000_000L
        assertTrue(g.shouldEmit(false))
    }

    @Test
    fun `disarm 后立刻放行`() {
        val g = gate()
        g.arm()
        assertFalse(g.shouldEmit(false))
        g.disarm()
        assertTrue(g.shouldEmit(false))
    }

    @Test
    fun `可以连续 arm（重建 + 重协商连着来）`() {
        val g = gate()
        g.arm()
        assertTrue(g.shouldEmit(true))            // 第一个会话等到 IDR
        g.arm()                                   // 第二个会话又 arm
        assertFalse(g.shouldEmit(false))          // 重新开始等
        assertTrue(g.shouldEmit(true))
    }

    @Test
    fun `默认值可用（不注入时钟也能工作）`() {
        val g = GopTailGate()
        g.arm()
        assertFalse(g.shouldEmit(false))
        assertTrue(g.shouldEmit(true))
        assertEquals(600_000_000L, GopTailGate.DEFAULT_TIMEOUT_NANOS)
    }
}
