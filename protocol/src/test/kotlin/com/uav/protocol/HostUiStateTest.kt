package com.uav.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [HostUiState] 单测（10.23）。
 *
 * 重点覆盖那个真机 bug 的**精确场景**：原地重协商期间视频没断，
 * 进度事件却来报"等待视频流" —— 状态行不许被它带走。
 */
class HostUiStateTest {

    @Test
    fun `初始为空闲`() {
        val s = HostUiState()
        assertEquals(HostUiState.Phase.IDLE, s.phase)
    }

    @Test
    fun `进度事件把空闲推进到连接中`() {
        val s = HostUiState()
        s.onProgress("CNXN 已发送…")
        assertEquals(HostUiState.Phase.CONNECTING, s.phase)
        assertEquals("CNXN 已发送…", s.detail)
    }

    @Test
    fun `首帧宣告投屏中`() {
        val s = HostUiState()
        s.onProgress("会话已建立，等待视频流…")
        s.onFirstFrame()
        assertEquals(HostUiState.Phase.MIRRORING, s.phase)
    }

    // ---- 10.23 的正题：进度事件不得降级 ----

    @Test
    fun `投屏中收到重协商的进度事件时状态不被降级`() {
        val s = HostUiState()
        s.onFirstFrame()
        // 原地重协商：同一条连接上重发 OPEN，收到 OKAY 后照旧发这条进度
        s.onProgress("OPEN 已发送：手机 1080x2400 / max_size=1920，等待 OKAY…")
        s.onProgress("会话已建立，等待视频流…")

        assertEquals(
            "画面没断过，绝不能因为这两条进度就回退成 CONNECTING",
            HostUiState.Phase.MIRRORING,
            s.phase,
        )
        // 但进度文本要留着（副行仍能看到新参数）
        assertEquals("会话已建立，等待视频流…", s.detail)
    }

    @Test
    fun `重协商期间进度事件不把阶段拽回连接中`() {
        val s = HostUiState()
        s.onFirstFrame()
        s.onRenegotiate()
        assertEquals(HostUiState.Phase.RENEGOTIATING, s.phase)

        s.onProgress("会话已建立，等待视频流…")
        assertEquals(HostUiState.Phase.RENEGOTIATING, s.phase)
    }

    @Test
    fun `重协商后收到新尺寸即收尾成投屏中`() {
        val s = HostUiState()
        s.onFirstFrame()
        s.onRenegotiate()
        s.onVideoSize()
        assertEquals(HostUiState.Phase.MIRRORING, s.phase)
    }

    @Test
    fun `重协商在还没出首帧时不该被 onVideoSize 提前宣告投屏`() {
        val s = HostUiState()
        s.onProgress("CNXN 已发送…")
        s.onRenegotiate()
        s.onVideoSize()
        assertEquals(
            "尺寸就绪但还没出画面，不能报投屏中",
            HostUiState.Phase.CONNECTING,
            s.phase,
        )
        s.onFirstFrame()
        assertEquals(HostUiState.Phase.MIRRORING, s.phase)
    }

    // ---- 允许的降级方向 ----

    @Test
    fun `用户主动重协商可以把投屏中降级`() {
        val s = HostUiState()
        s.onFirstFrame()
        s.onRenegotiate()
        assertEquals(HostUiState.Phase.RENEGOTIATING, s.phase)
    }

    @Test
    fun `首次连接期间的重协商保持连接中`() {
        val s = HostUiState()
        s.onProgress("会话已建立，等待视频流…")
        s.onRenegotiate()
        assertEquals(HostUiState.Phase.CONNECTING, s.phase)
    }

    @Test
    fun `断开回到连接中而不是空闲`() {
        val s = HostUiState()
        s.onFirstFrame()
        s.onClosed("链路读失败")
        assertEquals(HostUiState.Phase.CONNECTING, s.phase)
        assertEquals("已断开：链路读失败", s.detail)
    }

    @Test
    fun `拔出回到空闲`() {
        val s = HostUiState()
        s.onFirstFrame()
        s.onDetached("USB 已拔出，请重新插线或点 Scan/Retry")
        assertEquals(HostUiState.Phase.IDLE, s.phase)
    }

    @Test
    fun `出错是终态且带原因`() {
        val s = HostUiState()
        s.onFirstFrame()
        s.onError("等待手机 CNXN 超时（5000ms）")
        assertEquals(HostUiState.Phase.ERROR, s.phase)
        assertEquals("ERROR: 等待手机 CNXN 超时（5000ms）", s.detail)
    }

    @Test
    fun `回放中不被重协商或扫描劫持`() {
        val s = HostUiState()
        s.onReplay("回放：x.uav")
        s.onRenegotiate()
        assertEquals(HostUiState.Phase.REPLAY, s.phase)
        s.onScanRequested()
        assertEquals(HostUiState.Phase.REPLAY, s.phase)
    }

    @Test
    fun `出错后用户点重试回到连接中`() {
        val s = HostUiState()
        s.onError("boom")
        s.onScanRequested()
        assertEquals(HostUiState.Phase.CONNECTING, s.phase)
    }

    // ---------------------------------------------------------------- 流健康（10.30）

    @Test
    fun `可疑停帧只改副行不动主行`() {
        // 等 IDR / 重建编码器都会短暂停帧，主行改成"无输出"再改回来会闪（10.26 同款陷阱）。
        val s = HostUiState()
        s.onFirstFrame()
        s.onStreamHealth(StreamHealth.State.STALLED, silentMs = 4_200)
        assertEquals(HostUiState.Phase.MIRRORING, s.phase)
        assertEquals("画面已 4 秒无新帧…", s.detail)
    }

    @Test
    fun `投屏中长时间无帧降级为无输出`() {
        val s = HostUiState()
        s.onFirstFrame()
        s.onStreamHealth(StreamHealth.State.STOPPED, silentMs = 12_000)
        assertEquals(HostUiState.Phase.NO_SIGNAL, s.phase)
        assert(s.detail.contains("12 秒")) { s.detail }
    }

    @Test
    fun `重协商期间长时间无帧也降级为无输出`() {
        val s = HostUiState()
        s.onFirstFrame()
        s.onRenegotiate()
        s.onStreamHealth(StreamHealth.State.STOPPED, silentMs = 11_000)
        assertEquals(HostUiState.Phase.NO_SIGNAL, s.phase)
    }

    @Test
    fun `无输出状态下恢复出帧会自动回到投屏中`() {
        val s = HostUiState()
        s.onFirstFrame()
        s.onStreamHealth(StreamHealth.State.STOPPED, silentMs = 15_000)
        s.onStreamHealth(StreamHealth.State.HEALTHY, silentMs = 0)
        assertEquals(HostUiState.Phase.MIRRORING, s.phase)
        assertEquals("画面已恢复", s.detail)
    }

    @Test
    fun `连接中（还没首帧）的停帧不该报无输出`() {
        // 刚 OPEN 完、首帧还没来，这属于正常等待，不能报"手机端没输出"。
        val s = HostUiState()
        s.onProgress("会话已建立，等待视频流…")
        s.onStreamHealth(StreamHealth.State.STOPPED, silentMs = 20_000)
        assertEquals(HostUiState.Phase.CONNECTING, s.phase)
    }

    @Test
    fun `健康时不动任何状态`() {
        val s = HostUiState()
        s.onFirstFrame()
        val before = s.detail
        s.onStreamHealth(StreamHealth.State.HEALTHY, silentMs = 0)
        assertEquals(HostUiState.Phase.MIRRORING, s.phase)
        assertEquals(before, s.detail)
    }
}
