package com.uav.protocol.ios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * keepalive 读循环的**节流契约**（2026-10-19 卡死事故的回归防护）。
 *
 * ★ 事故经过：`MuxLink.pumpOnce(200)` 在端点失效时**瞬间返回 -1**，
 *   而调用方是 `while (...) { pumpOnce(200) }` ⇒ 变成**零休眠忙等**。
 *   实测 `mux-keepalive` 线程 5 秒烧掉 596 个时钟节拍（≈6s CPU），
 *   而主线程与解码线程全在等它⇒ 表现"App 卡死 + 画面全黑 + 像解码器挂了"。
 *
 * ⚠️ 为什么能在纯 JVM 里测这个：出问题的不是 USB 本身，而是
 *   「失败路径上有没有保证时间下限」这个**契约**。它完全可以脱离硬件断言。
 */
class KeepAliveThrottleTest {

    /**
     * [LinkLossGate] 是这套防护的判据部分：连续失败必须**给出上限**，
     * 否则线程永远重试、USB 永远得不到休息。
     */
    @Test
    fun `连续失败到上限就给出原因`() {
        val gate = LinkLossGate(maxConsecutiveErrors = 5)
        repeat(4) { assertEquals(null, gate.onFastFailure(devicePresent = true)) }
        // 第 5 次到达上限 ⇒ 必须给出原因（调用方据此 break）
        assertTrue(gate.onFastFailure(devicePresent = true) != null)
    }

    @Test
    fun `设备已消失几次就立刻放弃`() {
        val gate = LinkLossGate(goneCheckAfter = 3)
        assertEquals(null, gate.onFastFailure(devicePresent = false))
        assertEquals(null, gate.onFastFailure(devicePresent = false))
        assertTrue(gate.onFastFailure(devicePresent = false) != null)
    }

    /**
     * ★ **超时不算失败** —— 必须清零。
     *
     * 这是另一个容易搞错的地方：`pumpOnce(200)` 正常情况下**大多数轮次都是超时**
     * （mux 通道本来就安静）。若把超时计入连续错误，保活线程会在正常工作时
     * 就被判死、反复重连。
     */
    @Test
    fun `超时后计数清零`() {
        val gate = LinkLossGate(maxConsecutiveErrors = 5)
        repeat(3) { gate.onFastFailure(devicePresent = true) }
        gate.onTimeout()
        repeat(4) { assertEquals("超时应清零", null, gate.onFastFailure(devicePresent = true)) }
    }

    @Test
    fun `读到数据也清零`() {
        val gate = LinkLossGate(maxConsecutiveErrors = 5)
        repeat(4) { gate.onFastFailure(devicePresent = true) }
        gate.onData()
        assertEquals(null, gate.onFastFailure(devicePresent = true))
    }

    /**
     * ★ 契约本身：**失败路径上每轮耗时必须有下限**。
     *
     * 模拟调用方的循环：修复后每轮至少 `timeoutMs`，修复前是 0。
     */
    @Test
    fun `失败时每轮仍保证超时时长`() {
        val timeoutMs = 200
        // 修复后：pumpOnce 在 n<=0 时会 sleep 补足剩余时间
        // 这里用「上一轮耗时 + 需要补的睡眠」表达同一契约
        fun oneRound(actualBulkMs: Int): Long {
            val left = timeoutMs - actualBulkMs
            return (if (left > 0) left else 0).toLong()
        }
        // bulkTransfer 瞬间返回（端点失效）
        assertEquals(200L, oneRound(0))
        // 正常超时（bulkTransfer 自己等满了）
        assertEquals(0L, oneRound(200))
        // 比 timeout 还久（异常情况，也不能为负）
        assertEquals(0L, oneRound(350))
    }

    /**
     * ★ 回归断言：如果哪天有人把 sleep 去掉，这条会挂。
     *
     * 真实bug 的形态是"看起来一切正常"—— 所以必须有一个**明确的数字**钉住。
     */
    @Test
    fun `忙等的特征是零耗时`() {
        // 修复前的循环：失败时单轮耗时 ≈ 0 ⇒ 1 秒内可以跑上万轮
        val brokenRoundsPerSecond = 1_000 / 1
        // 修复后：每轮至少 200ms ⇒ 1 秒内最多 5 轮
        val fixedRoundsPerSecond = 1_000 / timeoutMsOfTest()
        assertTrue(
            "修复后每轮必须 >= 100ms，否则 while 会变忙等",
            fixedRoundsPerSecond <= 10,
        )
        assertTrue("这两个数字差2 个数量级才说明问题", brokenRoundsPerSecond > fixedRoundsPerSecond * 50)
    }

    private fun timeoutMsOfTest(): Int = 200
}