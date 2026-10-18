package com.uav.protocol.ios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「拔线后读循环该不该退出」的判据。
 *
 * 这几条用例守的是整个"拔掉 iPad 界面卡死"的修复：
 * 阈值放宽一次 → 设备没了还在空转（界面继续被日志刷死）；
 * 阈值收紧一次 → 一次瞬时 STALL 就把正常投屏掐掉。
 */
class LinkLossGateTest {

    @Test
    fun `前两次失败不判断开`() {
        val g = LinkLossGate()
        assertNull(g.onFastFailure(devicePresent = false))
        assertNull(g.onFastFailure(devicePresent = false))
        assertEquals(2, g.consecutiveErrors)
    }

    @Test
    fun `第三次失败且设备消失 判定 DEVICE_GONE`() {
        val g = LinkLossGate()
        g.onFastFailure(devicePresent = false)
        g.onFastFailure(devicePresent = false)
        assertEquals(LinkLossGate.Cause.DEVICE_GONE, g.onFastFailure(devicePresent = false))
    }

    @Test
    fun `设备还在时不会因为前三次判成 DEVICE_GONE`() {
        val g = LinkLossGate()
        repeat(3) { assertNull(g.onFastFailure(devicePresent = true)) }
    }

    @Test
    fun `没有在位判据时只按计数兜底`() {
        val g = LinkLossGate()
        // devicePresent = null：前 7 次都要能继续
        repeat(7) { assertNull(g.onFastFailure(devicePresent = null)) }
        assertEquals(LinkLossGate.Cause.ENDPOINT_DEAD, g.onFastFailure(devicePresent = null))
    }

    @Test
    fun `端点彻底失效时 即使设备还在也要退出`() {
        val g = LinkLossGate()
        var cause: LinkLossGate.Cause? = null
        repeat(8) { cause = g.onFastFailure(devicePresent = true) }
        assertEquals(LinkLossGate.Cause.ENDPOINT_DEAD, cause)
    }

    @Test
    fun `读到数据后计数清零`() {
        val g = LinkLossGate()
        repeat(5) { g.onFastFailure(devicePresent = true) }
        g.onData()
        assertEquals(0, g.consecutiveErrors)
        // 清零后重新从 1 开始，不会因为"累计很多"而立刻误判
        assertNull(g.onFastFailure(devicePresent = true))
    }

    @Test
    fun `超时不算错误 会清零`() {
        val g = LinkLossGate()
        repeat(6) { g.onFastFailure(devicePresent = true) }
        g.onTimeout()
        assertEquals(0, g.consecutiveErrors)
    }

    @Test
    fun `失败与超时交替时不误判`() {
        val g = LinkLossGate()
        // 真实链路：偶发错误 → 一轮超时 → 又一个错误，永远到不了阈值
        repeat(20) {
            assertNull(g.onFastFailure(devicePresent = false))
            g.onTimeout()
        }
        assertEquals(0, g.consecutiveErrors)
    }

    @Test
    fun `第8次是端点失效的边界`() {
        val g = LinkLossGate()
        repeat(7) { assertNull(g.onFastFailure()) }
        assertEquals(LinkLossGate.Cause.ENDPOINT_DEAD, g.onFastFailure())
    }
}
