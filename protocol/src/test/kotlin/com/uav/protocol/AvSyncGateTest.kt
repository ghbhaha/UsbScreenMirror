package com.uav.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AvSyncGate] 单测。
 *
 * 约定：`measured = audioNow - framePts`，正数 = 音频已经播过该帧 ⇒ **视频偏晚**。
 */
class AvSyncGateTest {

    private fun gate(alpha: Double = 0.08, warmup: Int = 60) =
        AvSyncGate(correctionAlpha = alpha, warmupFrames = warmup)

    private fun ms(v: Long) = v / 1_000_000.0

    @Test
    fun `预热期只学习偏移，不丢不等`() {
        val g = gate(warmup = 10)
        // 前 10 帧即便"看起来很晚"也不该被丢 —— 否则开局误丢
        repeat(10) {
            val d = g.decide(frameHostPtsNanos = 0L, audioNowNanos = 500_000_000L) // 晚 500ms
            assertTrue("预热期必须渲染", d.render)
            assertEquals(0L, d.waitNanos)
        }
        assertEquals(0L, g.droppedFrames)
        assertEquals(0L, g.heldFrames)
    }

    @Test
    fun `偏移会被 EMA 收敛到观测值`() {
        // 用**生产参数**：warmup 必须覆盖斜坡期，否则开局就会丢帧（下面那条测试专门盯这个）
        val g = AvSyncGate()
        repeat(300) { g.decide(frameHostPtsNanos = 0L, audioNowNanos = 200_000_000L) }
        assertTrue("offset 应逼近 200ms，实际=${ms(g.offsetNanos)}", ms(g.offsetNanos) > 180.0)
        assertEquals("生产参数下预热期足够，不该丢帧", 0L, g.droppedFrames)
        assertEquals("也不该有等待", 0L, g.heldFrames)
    }

    @Test
    fun `稳态后超出容差加阈值才丢帧`() {
        val g = AvSyncGate()
        repeat(300) { g.decide(0L, 200_000_000L) }        // 先把 offset 学到 ~200ms
        val base = g.droppedFrames
        // 管线延迟突然多 200ms ⇒ 超 容差(30)+阈值(60)=90ms ⇒ 必须丢
        val d = g.decide(frameHostPtsNanos = 0L, audioNowNanos = 400_000_000L)
        assertFalse("明显偏晚必须丢", d.render)
        assertEquals(base + 1, g.droppedFrames)
    }

    @Test
    fun `明显偏早时有界等待`() {
        val g = AvSyncGate()
        repeat(300) { g.decide(0L, 200_000_000L) }        // offset ≈ 200ms
        val d = g.decide(frameHostPtsNanos = 400_000_000L, audioNowNanos = 200_000_000L)
        assertTrue("偏早但仍要渲染", d.render)
        assertTrue("应当等待，实际 ${ms(d.waitNanos)}ms", d.waitNanos > 0)
    }

    @Test
    fun `等待时长有上限（不能卡住解码线程）`() {
        val g = AvSyncGate()
        repeat(300) { g.decide(0L, 200_000_000L) }
        // 极端：帧的 pts 在音频播放点之后 5 秒
        val d = g.decide(frameHostPtsNanos = 5_000_000_000L, audioNowNanos = 200_000_000L)
        assertTrue(d.render)
        assertEquals(AvSyncGate.DEFAULT_MAX_HOLD_NANOS, d.waitNanos)
    }

    @Test
    fun `正好同步的帧不会被误判成偏早`() {
        // ★ 这条是回归：早期版本把"容差"双向相减，导致 lateError=0 的同步帧
        //   被算成"早 30ms"而每次都等 30ms —— 画面反而一顿一顿。
        val g = AvSyncGate()
        repeat(300) { g.decide(0L, 200_000_000L) }
        val heldBefore = g.heldFrames
        val d = g.decide(frameHostPtsNanos = 0L, audioNowNanos = 200_000_000L)
        assertTrue(d.render)
        assertEquals("同步帧不该触发等待", heldBefore, g.heldFrames)
        assertEquals(0L, d.waitNanos)
    }

    @Test
    fun `容差内的轻微抖动不丢不等`() {
        val g = AvSyncGate()
        repeat(300) { g.decide(0L, 200_000_000L) }        // offset ≈ 200ms
        val heldBefore = g.heldFrames
        for (delta in listOf(-20L, -10L, 0L, 10L, 20L)) {
            val d = g.decide(0L, 200_000_000L + delta * 1_000_000L)
            assertTrue("抖动 ${delta}ms 不该丢", d.render)
            assertEquals("抖动 ${delta}ms 不该等", 0L, d.waitNanos)
        }
        assertEquals("整段抖动不应产生等待", heldBefore, g.heldFrames)
    }

    @Test
    fun `校正步进有上限（一次异常不会带偏系统）`() {
        val g = gate(alpha = 1.0, warmup = 1)              // alpha=1 时步进 = delta，必须被 maxStep 截住
        g.decide(0L, 1_000_000_000L)                      // delta = 1000ms → 只能走 4ms
        assertTrue(
            "单次步进应被限幅到 4ms，实际 ${ms(g.offsetNanos)}ms",
            ms(g.offsetNanos) <= 4.1,
        )
    }

    @Test
    fun `慢漂移会被持续吸收（精细同步的核心）`() {
        val g = gate(alpha = 0.2, warmup = 1)
        repeat(200) { g.decide(0L, 200_000_000L) }        // 先稳定
        val before = g.offsetNanos
        // 音频时钟比视频快 100ms（漂移）：观测值整体 +100ms，持续喂
        repeat(200) { g.decide(0L, 300_000_000L) }
        val after = g.offsetNanos
        assertTrue("漂移后 offset 应上升：${ms(before)} -> ${ms(after)}", after > before + 80_000_000L)
    }

    @Test
    fun `reset 后一切归零`() {
        val g = gate(warmup = 1)
        repeat(50) { g.decide(0L, 400_000_000L) }
        assertTrue(g.droppedFrames > 0)
        g.reset()
        assertEquals(0L, g.offsetNanos)
        assertEquals(0L, g.droppedFrames)
        assertEquals(0L, g.heldFrames)
        // 归零后又进入预热
        assertTrue(g.decide(0L, 900_000_000L).render)
    }

    @Test
    fun `stats 一行可读`() {
        val g = gate(warmup = 1)
        g.decide(0L, 400_000_000L)
        val s = g.stats()
        assertTrue(s.contains("offset=") && s.contains("drop=") && s.contains("hold=") && s.contains("frames="))
    }
}
