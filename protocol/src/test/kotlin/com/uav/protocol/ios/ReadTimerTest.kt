package com.uav.protocol.ios

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「读满超时窗口」与「快速失败」的分界（2026-10-20 mux 保活误杀事故的回归防护）。
 *
 * ★ 事故经过：mux 保活循环用 `pumpOnce(200)`，每轮设备安静 200ms 就返回 -1。
 *   调用方没有超时判据，把每一轮都当"快速失败" ⇒ `LinkLossGate` 数到 8
 *   （正好 1.6 秒）判 `ENDPOINT_DEAD`，lockdown 会话在起流 1.6s 后被误杀。
 *   实测日志：`10:51:56.326 ✗ lockdown 保活中止：ENDPOINT_DEAD`。
 */
class ReadTimerTest {

    @Test
    fun `读满窗口算超时`() {
        assertTrue(ReadTimer.isTimeout(elapsedMs = 200, timeoutMs = 200))
        assertTrue(ReadTimer.isTimeout(elapsedMs = 497, timeoutMs = 500))
        assertTrue(ReadTimer.isTimeout(elapsedMs = 503, timeoutMs = 500))
    }

    @Test
    fun `留一成抖动余量`() {
        // 450/500 与 180/200 都在容差内：JNI 返程与调度会让实际耗时略短于 timeout
        assertTrue(ReadTimer.isTimeout(elapsedMs = 450, timeoutMs = 500))
        assertTrue(ReadTimer.isTimeout(elapsedMs = 180, timeoutMs = 200))
    }

    @Test
    fun `明显更短的是快速失败`() {
        // 端点失效时是 0~1ms，两个数量级之差
        assertFalse(ReadTimer.isTimeout(elapsedMs = 0, timeoutMs = 500))
        assertFalse(ReadTimer.isTimeout(elapsedMs = 1, timeoutMs = 500))
        assertFalse(ReadTimer.isTimeout(elapsedMs = 213, timeoutMs = 500))
        assertFalse(ReadTimer.isTimeout(elapsedMs = 449, timeoutMs = 500))
        assertFalse(ReadTimer.isTimeout(elapsedMs = 179, timeoutMs = 200))
    }

    @Test
    fun `没有超时语义的调用一律不算超时`() {
        // timeout=0 = 无限等；此时返回 -1 只可能是错误
        assertFalse(ReadTimer.isTimeout(elapsedMs = 0, timeoutMs = 0))
        assertFalse(ReadTimer.isTimeout(elapsedMs = 10_000, timeoutMs = 0))
        assertFalse(ReadTimer.isTimeout(elapsedMs = 10_000, timeoutMs = -1))
    }
}
