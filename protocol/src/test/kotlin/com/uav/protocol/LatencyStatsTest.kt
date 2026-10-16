package com.uav.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyStatsTest {

    private val ms = 1_000_000L

    @Test
    fun `空窗口不崩且返回 0`() {
        val s = LatencyStats()
        assertEquals(0L, s.averageNanos())
        assertEquals(0L, s.percentileNanos(0.95))
        assertEquals(0L, s.maxNanos())
        assertEquals(0L, s.count)
        assertTrue(s.snapshot(), s.snapshot().contains("n=0"))
    }

    @Test
    fun `平均值与分位数语义`() {
        val s = LatencyStats()
        for (v in listOf(10L, 20L, 30L, 40L)) s.add(v * ms)

        assertEquals(4, s.windowSize)
        assertEquals(25 * ms, s.averageNanos())
        // n=4 -> 下标 = round(3 * p)
        assertEquals(30 * ms, s.percentileNanos(0.50))   // round(1.5) = 2
        assertEquals(40 * ms, s.percentileNanos(0.95))   // round(2.85) = 3
        assertEquals(40 * ms, s.percentileNanos(1.0))
        assertEquals(10 * ms, s.percentileNanos(0.0))
        assertEquals(40 * ms, s.maxNanos())
        assertEquals(10 * ms, s.minNanos())
    }

    @Test
    fun `窗口滚动时总和同步扣减`() {
        // 这条最容易写错：忘了随 removeFirst 扣 sumNanos，avg 会越算越大
        val s = LatencyStats(capacity = 2)
        s.add(10 * ms)
        s.add(20 * ms)
        assertEquals(15 * ms, s.averageNanos())

        s.add(30 * ms)   // 挤掉 10

        assertEquals(2, s.windowSize)
        assertEquals(25 * ms, s.averageNanos())   // 若未扣减会是 30ms
        assertEquals(20 * ms, s.minNanos())
        assertEquals(3L, s.count)                 // 累计数仍然记全部
    }

    @Test
    fun `百分位下标不越界`() {
        val s = LatencyStats()
        repeat(100) { s.add((it + 1) * ms) }   // 样本 = 1ms..100ms

        assertEquals(100, s.windowSize)
        // idx = round(99 * p)：sorted[i] = i+1
        assertEquals(51 * ms, s.percentileNanos(0.50))   // round(49.5) = 50
        assertEquals(100 * ms, s.percentileNanos(1.0))
        // p0 必须落在第 0 个，不能越界成 -1
        assertEquals(1 * ms, s.percentileNanos(0.0))
    }

    @Test
    fun `超过上界的离群样本被记为异常且不进分布`() {
        val s = LatencyStats()
        s.add(20 * ms)
        s.add(30 * ms)

        assertFalse(s.add(20_000 * ms))   // 20 秒：首帧/唤醒级别，不是稳态延迟

        assertEquals(3L, s.count)
        assertEquals(1L, s.anomalyCount)
        assertEquals(2, s.windowSize)
        // 若混进分布，p99 会被拉成 20 秒这种误导性结论
        assertEquals(30 * ms, s.percentileNanos(0.99))
        assertEquals(25 * ms, s.averageNanos())
    }

    @Test
    fun `大幅负延迟被记为异常`() {
        val s = LatencyStats()
        s.add(15 * ms)

        assertFalse(s.add(-500 * ms))     // 远超标定误差，说明 pts 或时钟出问题了

        assertEquals(1L, s.anomalyCount)
        assertEquals(1, s.windowSize)
    }

    @Test
    fun `小幅负延迟属于标定误差仍被接受`() {
        // 时钟标定不确定度是 ±rtt/2，真实延迟极小时量到 -1ms 是正常的
        val s = LatencyStats()
        assertTrue(s.add(-1 * ms))
        assertEquals(0L, s.anomalyCount)
        assertEquals(1, s.windowSize)
    }

    @Test
    fun `边界值恰好落在上下界上时被接受`() {
        val s = LatencyStats()
        assertTrue(s.add(LatencyStats.MIN_PLAUSIBLE_NANOS))
        assertTrue(s.add(LatencyStats.MAX_PLAUSIBLE_NANOS))
        assertEquals(0L, s.anomalyCount)
        assertEquals(2, s.windowSize)
    }

    @Test
    fun `snapshot 形如一行可读摘要`() {
        val s = LatencyStats()
        for (v in listOf(10L, 12L, 20L, 44L)) s.add(v * ms)

        val line = s.snapshot()
        assertTrue(line, line.contains("avg=21.5"))
        assertTrue(line, line.contains("p50="))
        assertTrue(line, line.contains("p95="))
        assertTrue(line, line.contains("p99="))
        assertTrue(line, line.contains("max=44.0"))
        assertTrue(line, line.contains("n=4"))
        assertTrue(line, line.contains("bad=0"))
    }

    @Test
    fun `snapshot 能表达负值`() {
        val s = LatencyStats()
        s.add(-2 * ms)
        assertTrue(s.snapshot().contains("avg=-2.0"))
    }

    @Test
    fun `reset 清空一切`() {
        val s = LatencyStats()
        s.add(30 * ms)
        s.add(99_000 * ms)

        s.reset()

        assertEquals(0L, s.count)
        assertEquals(0L, s.anomalyCount)
        assertEquals(0, s.windowSize)
        assertEquals(0L, s.averageNanos())
    }
}
