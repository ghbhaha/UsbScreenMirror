package com.uav.protocol.aoa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行权与退避的边界（2026-10-20 抽层时的回归防护）。
 *
 * 这段逻辑原本是 `LinkCoordinator` 里三个独立的原子量 + 一句移位算术，
 * 出过的两类事故都很隐蔽：忘了复位 ⇒ 入口永久锁死；忘了清零 ⇒ 自动重连退到 30s。
 */
class LinkRetryTest {

    @Test
    fun `运行权只发给一个调用方`() {
        val r = LinkRetry()
        assertTrue(r.tryBeginAttempt())
        // 第二次（用户连点、或 attach 与用户动作撞车）必须被拒
        assertFalse(r.tryBeginAttempt())
        assertTrue(r.isAttempting)
    }

    @Test
    fun `交还后可以再次抢占`() {
        val r = LinkRetry()
        assertTrue(r.tryBeginAttempt())
        r.endAttempt()
        assertFalse(r.isAttempting)
        assertTrue(r.tryBeginAttempt())
    }

    @Test
    fun `失败逐级退避并在上限封顶`() {
        val r = LinkRetry(baseDelayMs = 2_000, maxShift = 4, capDelayMs = 30_000)
        assertEquals(2_000, r.pollDelayMs())   // 0 次
        r.onAttemptFailed(); assertEquals(4_000, r.pollDelayMs())
        r.onAttemptFailed(); assertEquals(8_000, r.pollDelayMs())
        r.onAttemptFailed(); assertEquals(16_000, r.pollDelayMs())
        r.onAttemptFailed(); assertEquals(30_000, r.pollDelayMs())  // 32s 被封顶
        repeat(10) { r.onAttemptFailed() }
        assertEquals(30_000, r.pollDelayMs())  // 再多失败也不会超过上限
    }

    @Test
    fun `失败计数到顶后不再增长`() {
        val r = LinkRetry(maxShift = 4)
        repeat(20) { r.onAttemptFailed() }
        // 计数本身有意义的上限就是"能翻几番"，再涨只会让清零看起来像丢信息
        assertEquals(4, r.consecutiveFailures)
    }

    @Test
    fun `建链成功立刻回到最快档`() {
        val r = LinkRetry()
        repeat(4) { r.onAttemptFailed() }
        assertEquals(30_000, r.pollDelayMs())
        r.onAttemptSucceeded()
        assertEquals(0, r.consecutiveFailures)
        assertEquals(2_000, r.pollDelayMs())
    }

    @Test
    fun `自定义档位`() {
        // 允许上层调参（例如诊断时把退避压平），但语义不变
        val r = LinkRetry(baseDelayMs = 500, maxShift = 2, capDelayMs = 1_500)
        assertEquals(500, r.pollDelayMs())
        r.onAttemptFailed(); assertEquals(1_000, r.pollDelayMs())
        r.onAttemptFailed(); assertEquals(1_500, r.pollDelayMs())  // 2000 被封顶
    }
}
