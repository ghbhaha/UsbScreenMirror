package com.uav.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamHealthTest {

    private val health = StreamHealth(stallMs = 3_000, stopMs = 10_000)

    @Test
    fun `还没收到过首帧时一律判健康`() {
        // 0 表示"还没有任何视频帧"。刚 OPEN 完就报"无输出"是误报，交给连接流程判断。
        assertEquals(StreamHealth.State.HEALTHY, health.evaluate(nowMs = 999_999, lastFrameAtMs = 0))
        assertEquals(StreamHealth.State.HEALTHY, health.evaluate(nowMs = 999_999, lastFrameAtMs = -1))
    }

    @Test
    fun `刚出过帧是健康的`() {
        val now = 100_000L
        assertEquals(StreamHealth.State.HEALTHY, health.evaluate(now, now - 100))
        assertEquals(StreamHealth.State.HEALTHY, health.evaluate(now, now))          // 同时刻
    }

    @Test
    fun `静态画面的十帧每秒不该被误判`() {
        // 手机端设了 KEY_REPEAT_PREVIOUS_FRAME_AFTER=100ms，静态画面也有 ~10fps。
        // 逐帧推进到 2.9 秒，全程必须 HEALTHY。
        val base = 500_000L
        for (offset in 0..2_900 step 100) {
            assertEquals(
                "offset=${offset}ms 时不该报警",
                StreamHealth.State.HEALTHY,
                health.evaluate(base + offset, base),
            )
        }
    }

    @Test
    fun `超过 stallMs 判为可疑但仍未停`() {
        val last = 200_000L
        assertEquals(StreamHealth.State.STALLED, health.evaluate(last + 3_000, last))
        assertEquals(StreamHealth.State.STALLED, health.evaluate(last + 9_999, last))
    }

    @Test
    fun `超过 stopMs 判为已停`() {
        val last = 200_000L
        assertEquals(StreamHealth.State.STOPPED, health.evaluate(last + 10_000, last))
        assertEquals(StreamHealth.State.STOPPED, health.evaluate(last + 60_000, last))
    }

    @Test
    fun `停帧后来一帧就立刻回健康`() {
        val last = 300_000L
        assertEquals(StreamHealth.State.STOPPED, health.evaluate(last + 20_000, last))
        // 新的最后一帧时间戳（上层每次收到帧都会更新它）
        assertEquals(StreamHealth.State.HEALTHY, health.evaluate(last + 20_000, last + 20_000))
    }

    @Test
    fun `时钟回绕或乱序时不报假警`() {
        // now < last 只可能是时间源异常；宁可当健康，也不要弹一个假的"无输出"。
        assertEquals(StreamHealth.State.HEALTHY, health.evaluate(nowMs = 1_000, lastFrameAtMs = 5_000))
    }

    @Test
    fun `静默时长换算给文案用`() {
        assertEquals(0L, health.silentMs(nowMs = 1_000, lastFrameAtMs = 0))
        assertEquals(4_200L, health.silentMs(nowMs = 10_000, lastFrameAtMs = 5_800))
        assertEquals(0L, health.silentMs(nowMs = 1_000, lastFrameAtMs = 5_000))   // 负值夹到 0
    }

    @Test
    fun `阈值必须递增`() {
        val sameThreshold = runCatching { StreamHealth(stallMs = 5_000, stopMs = 5_000) }.exceptionOrNull()
        assertTrue("相等阈值应被拒绝，实际=$sameThreshold", sameThreshold is IllegalArgumentException)
        val zeroThreshold = runCatching { StreamHealth(stallMs = 0, stopMs = 1_000) }.exceptionOrNull()
        assertTrue("零阈值应被拒绝，实际=$zeroThreshold", zeroThreshold is IllegalArgumentException)
    }
}
