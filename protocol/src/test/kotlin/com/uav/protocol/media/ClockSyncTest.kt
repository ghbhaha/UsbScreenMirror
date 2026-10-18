package com.uav.protocol.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncTest {

    private val ms = 1_000_000L

    @Test
    fun `对称往返时偏移等于对端起点差`() {
        // 本机 t0=100ms，往返 20ms；对端时钟比本机快 500ms
        // 对端在"本机 110ms"那一刻发应答 -> 对端读数 = 110 + 500 = 610ms
        val t0 = 100 * ms
        val t3 = 120 * ms
        val remote = 610 * ms

        val off = ClockSync.estimate(t0, t3, remote)

        assertEquals(20 * ms, off.rttNanos)
        // offset = 610 - (100 + 10) = 500ms
        assertEquals(500 * ms, off.offsetNanos)
        assertEquals(10 * ms, off.uncertaintyNanos)
    }

    @Test
    fun `对端时钟落后时偏移为负`() {
        val t0 = 1_000 * ms
        val t3 = 1_004 * ms
        // 对端比本机慢 300ms：本机 1002ms 时对端读数为 702ms
        val remote = 702 * ms

        val off = ClockSync.estimate(t0, t3, remote)

        assertEquals(-300 * ms, off.offsetNanos)
        assertEquals(2 * ms, off.uncertaintyNanos)
    }

    @Test
    fun `offset 能把对端读数换算成本机读数`() {
        val off = ClockSync.estimate(
            localSendNanos = 100 * ms,
            localRecvNanos = 120 * ms,
            remoteNanos = 610 * ms,
        )

        // 对端在 610ms 发出的东西，本机时钟是 110ms
        assertEquals(110 * ms, off.toLocalNanos(610 * ms))
        // 对端在 910ms 发出的东西，本机时钟是 410ms（同一条时间轴线性平移）
        assertEquals(410 * ms, off.toLocalNanos(910 * ms))
    }

    @Test
    fun `往返为零时不除以零也不产生负 rtt`() {
        val off = ClockSync.estimate(500 * ms, 500 * ms, 900 * ms)
        assertEquals(0L, off.rttNanos)
        assertEquals(400 * ms, off.offsetNanos)
    }

    @Test
    fun `接收时刻早于发送时刻时 rtt 被钳到 0`() {
        // 时钟回拨、或调用方把参数传反了：不能算出负 RTT
        val off = ClockSync.estimate(
            localSendNanos = 200 * ms,
            localRecvNanos = 100 * ms,
            remoteNanos = 900 * ms,
        )
        assertEquals(0L, off.rttNanos)
        assertEquals(700 * ms, off.offsetNanos)
    }

    @Test
    fun `不确定度始终是往返的一半`() {
        for (rtt in listOf(0L, 1L, 999_999L, 7 * ms, 33 * ms)) {
            val off = ClockSync.estimate(0L, rtt, 12_345L)
            assertEquals(rtt / 2, off.uncertaintyNanos)
        }
    }

    @Test
    fun `toString 用毫秒呈现便于日志阅读`() {
        val off = ClockSync.estimate(0L, 3 * ms, 500 * ms)
        val s = off.toString()
        assertTrue(s, s.contains("offset=498ms"))
        assertTrue(s, s.contains("rtt=3ms"))
    }
}
