package com.uav.protocol.aoa

import com.uav.protocol.MemoryPipe
import com.uav.protocol.adb.AdbMessage
import com.uav.protocol.media.CodecId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 屏幕旋转（计划 P0「旋转三件套」）：**尺寸变更靠重发 12B 流头表达**。
 *
 * 判据是**载荷长度**而不是"内容像不像流头"：帧的 payload 是 `12 + size`（size ≥ 1），
 * 所以恰好 12 字节只可能是流头 —— 这个性质让识别变得确定，不需要启发式。
 */
class ConnectionSizeChangeTest {

    /** 只记录回调，不消费队列（避免测试里还要驱动消费者线程）。 */
    private class HeaderSink : StreamSink {
        val headers = mutableListOf<StreamHeader>()
        val frames = mutableListOf<Frame>()

        override fun onHeader(header: StreamHeader) {
            synchronized(headers) { headers.add(header) }
        }

        override fun onFrame(frame: Frame) {
            synchronized(frames) { frames.add(frame) }
        }

        fun headerCount(): Int = synchronized(headers) { headers.size }
        fun frameCount(): Int = synchronized(frames) { frames.size }
        fun headerAt(i: Int): StreamHeader = synchronized(headers) { headers[i] }
    }

    private class SilentListener : ConnectionListener {
        @Volatile
        var error: Throwable? = null

        override fun onMessage(message: AdbMessage) = Unit
        override fun onError(cause: Throwable) {
            error = cause
        }

        override fun onClosed(reason: String) = Unit
    }

    private fun start(): Triple<Connection, Transport, HeaderSink> {
        val (hostSide, phoneSide) = MemoryPipe.pair()
        val sink = HeaderSink()
        val conn = Connection(Transport(hostSide), listener = SilentListener())
        conn.registerSink(StreamId.VIDEO, sink)
        conn.start()
        return Triple(conn, Transport(phoneSide), sink)
    }

    /** 读线程是异步的，用轮询等待而不是 sleep 固定时长（后者会让测试变慢且易抖）。 */
    private fun awaitUntil(what: String, timeoutMs: Long = 2_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(5)
        }
        throw AssertionError("等待超时: $what")
    }

    private fun header(w: Int, h: Int) = StreamHeader(CodecId.AVC1, w, h).encode()

    private fun frame(pts: Long, payload: Byte) =
        FrameCodec.encodeFrame(Flags.pack(pts), byteArrayOf(payload))

    @Test
    fun `首次流头回调一次`() {
        val (conn, peer, sink) = start()
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, header(720, 1600))

        awaitUntil("首次流头") { sink.headerCount() == 1 }
        assertEquals(720, sink.headerAt(0).width)
        assertEquals(1600, sink.headerAt(0).height)
        conn.close()
    }

    @Test
    fun `重发不同尺寸的流头会更新并再次回调`() {
        val (conn, peer, sink) = start()
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, header(720, 1600))
        awaitUntil("首次流头") { sink.headerCount() == 1 }

        // 手机旋转：长边短边互换
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, header(1600, 720))

        awaitUntil("尺寸变更后的流头") { sink.headerCount() == 2 }
        assertEquals(1600, sink.headerAt(1).width)
        assertEquals(720, sink.headerAt(1).height)
        assertEquals(1L, conn.stats.sizeChanges.get())
        conn.close()
    }

    @Test
    fun `重发相同尺寸的流头不触发重复回调`() {
        // 否则宿主的解码器会被无谓地重建一次（画面闪一下）
        val (conn, peer, sink) = start()
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, header(720, 1600))
        awaitUntil("首次流头") { sink.headerCount() == 1 }

        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, header(720, 1600))
        Thread.sleep(150)

        assertEquals(1, sink.headerCount())
        assertEquals(0L, conn.stats.sizeChanges.get())
        conn.close()
    }

    @Test
    fun `12 字节但不是合法流头时被忽略且不影响后续`() {
        val (conn, peer, sink) = start()
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, header(720, 1600))
        awaitUntil("首次流头") { sink.headerCount() == 1 }

        // 全 0：codecId 不是可打印 ASCII，也不满足尺寸范围 -> 严格校验会拒
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, ByteArray(12))
        Thread.sleep(150)
        assertEquals(1, sink.headerCount())

        // 关键：不能因为这条垃圾就把连接搞坏
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, frame(101, 7))
        awaitUntil("后续帧仍能入队") { sink.frameCount() == 1 }
        conn.close()
    }

    @Test
    fun `尺寸变更前后的帧都能正常投递`() {
        val (conn, peer, sink) = start()
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, header(720, 1600))
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, frame(1, 11))
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, header(1600, 720))
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, frame(2, 22))

        awaitUntil("两帧都到") { sink.frameCount() == 2 }
        assertEquals(2, sink.headerCount())
        conn.close()
    }

    @Test
    fun `连续多次旋转逐次计数`() {
        val (conn, peer, sink) = start()
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, header(720, 1600))
        awaitUntil("首次流头") { sink.headerCount() == 1 }

        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, header(1600, 720))
        awaitUntil("第一次旋转") { sink.headerCount() == 2 }
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, header(720, 1600))
        awaitUntil("转回竖屏") { sink.headerCount() == 3 }

        assertEquals(2L, conn.stats.sizeChanges.get())
        assertEquals(720, sink.headerAt(2).width)
        conn.close()
    }

    @Test
    fun `尺寸变更被计入 stats 的 headersSeen`() {
        val (conn, peer, sink) = start()
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, header(720, 1600))
        awaitUntil("首次流头") { sink.headerCount() == 1 }
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, header(1600, 720))
        awaitUntil("旋转") { sink.headerCount() == 2 }

        // 统计里看到的必须是**最新**尺寸，否则故障排查时读到的方向是旧的
        assertEquals(1600, conn.stats.headersSeen[StreamId.VIDEO]?.width)
        conn.close()
    }
}
