package com.uav.protocol.ios

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「新插入」判定（2026-10-20 停止后自动重开事故的回归防护）。
 *
 * ★ 事故经过：采集启动时 `activate` 把设备重枚举成新 deviceId，而基线只在
 *   「触发了自动开始」时前进 ⇒ 用户点停止后，当前 id 看起来像"刚插入"
 *   ⇒ 1 秒内又自动开一轮。
 */
class InsertionBaselineTest {

    @Test
    fun `第一次看到设备算新插入`() {
        val b = InsertionBaseline()
        assertTrue(b.onObserved(deviceId = 2011, busy = false))
    }

    @Test
    fun `同一台设备不会重复触发`() {
        val b = InsertionBaseline()
        assertTrue(b.onObserved(deviceId = 2011, busy = false))
        assertFalse(b.onObserved(deviceId = 2011, busy = false))
    }

    @Test
    fun `忙的时候重枚举过 收尾后不能再触发`() {
        val b = InsertionBaseline()
        // 插上：触发一次自动开始
        assertTrue(b.onObserved(deviceId = 2011, busy = false))
        // 采集启动后 activate 重枚举：忙，只对齐基线
        assertFalse(b.onObserved(deviceId = 2012, busy = true))
        // 用户点停止 ⇒ 不忙了：同一个 id 不得当成新插入
        assertFalse(b.onObserved(deviceId = 2012, busy = false))
    }

    @Test
    fun `忙的时候同 id 也不会触发`() {
        val b = InsertionBaseline()
        b.onObserved(deviceId = 2011, busy = false)
        assertFalse(b.onObserved(deviceId = 2012, busy = true))
        assertFalse(b.onObserved(deviceId = 2012, busy = true))
    }

    @Test
    fun `真换设备仍然触发`() {
        val b = InsertionBaseline()
        assertTrue(b.onObserved(deviceId = 2011, busy = false))
        // 换一台 iPad（id 不同，且没经过"拔出"）也要能开始
        assertTrue(b.onObserved(deviceId = 3050, busy = false))
    }

    @Test
    fun `拔掉再插回来还能触发`() {
        val b = InsertionBaseline()
        assertTrue(b.onObserved(deviceId = 2011, busy = false))
        b.onAbsent()
        // 同口重新插入，deviceId 可能一模一样
        assertTrue(b.onObserved(deviceId = 2011, busy = false))
    }
}
