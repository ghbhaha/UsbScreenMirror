package com.uav.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单测 9：`WRTE.arg0` 按流分流（计划 4.2 要点 —— 初版最大的空白）。
 *
 * 同时覆盖：未知命令忽略而不中断、`CLSE(CLOSE_ALL)` 收敛、单写线程串行化。
 */
class ConnectionTest {

    private class Recorder : ConnectionListener {
        val messages = mutableListOf<AdbMessage>()
        var error: Throwable? = null
        var closedReason: String? = null
        var closedLatch = java.util.concurrent.CountDownLatch(1)

        override fun onMessage(message: AdbMessage) {
            messages.add(message)
        }

        override fun onError(cause: Throwable) {
            error = cause
        }

        override fun onClosed(reason: String) {
            closedReason = reason
            closedLatch.countDown()
        }
    }

    private fun startHost(): Triple<Connection, MemoryPipeChannel, Recorder> {
        val (hostSide, phoneSide) = MemoryPipe.pair()
        val recorder = Recorder()
        val conn = Connection(Transport(hostSide), listener = recorder)
        conn.start()
        return Triple(conn, phoneSide, recorder)
    }

    // ------------------------------------------------------------------ 单测 9

    @Test
    fun `按 WRTE_arg0 把混投的帧分流到各自队列`() {
        val (conn, phoneSide, _) = startHost()
        val peer = Transport(phoneSide)

        val videoQueue = FrameQueue(StreamId.VIDEO)
        val audioQueue = FrameQueue(StreamId.AUDIO)
        conn.registerSink(StreamId.VIDEO, videoQueue)
        conn.registerSink(StreamId.AUDIO, audioQueue)

        val videoHeader = StreamHeader(CodecId.AVC1, 720, 1600)
        val audioHeader = StreamHeader(CodecId.OPUS, 48000, 2)

        // 交错投递两条流的数据，验证分流不受顺序影响
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, videoHeader.encode())
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.AUDIO, 0, audioHeader.encode())
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, FrameCodec.encodeFrame(Flags.pack(111, keyFrame = true), byteArrayOf(1, 1, 1)))
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.AUDIO, 0, FrameCodec.encodeFrame(Flags.pack(222), byteArrayOf(2, 2)))
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, FrameCodec.encodeFrame(Flags.pack(333), byteArrayOf(3)))

        val vf1 = videoQueue.take(3_000)
        val vf2 = videoQueue.take(3_000)
        val af1 = audioQueue.take(3_000)

        assertNotNull(vf1)
        assertNotNull(vf2)
        assertNotNull(af1)

        // 视频队列只拿到了视频帧
        assertEquals(111L, vf1!!.pts)
        assertTrue(vf1.isKeyFrame)
        assertArrayEquals(byteArrayOf(1, 1, 1), vf1.data)
        assertEquals(333L, vf2!!.pts)
        assertArrayEquals(byteArrayOf(3), vf2.data)

        // 音频队列只拿到了音频帧
        assertEquals(222L, af1!!.pts)
        assertArrayEquals(byteArrayOf(2, 2), af1.data)

        // 各流流头分别就位
        assertEquals(videoHeader, videoQueue.header)
        assertEquals(audioHeader, audioQueue.header)
        assertEquals(videoHeader, conn.headerOf(StreamId.VIDEO))
        assertEquals(audioHeader, conn.headerOf(StreamId.AUDIO))

        // 统计分开计数
        assertEquals(2L, conn.stats.frames(StreamId.VIDEO))
        assertEquals(1L, conn.stats.frames(StreamId.AUDIO))
        assertEquals(4L, conn.stats.bytes(StreamId.VIDEO)) // 3 + 1

        conn.close()
    }

    @Test
    fun `未注册的流被忽略且不中断连接`() {
        val (conn, phoneSide, recorder) = startHost()
        val peer = Transport(phoneSide)

        val videoQueue = FrameQueue(StreamId.VIDEO)
        conn.registerSink(StreamId.VIDEO, videoQueue)

        // 先来一条谁都不认领的流（audio 在 MVP 中不存在）
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.AUDIO, 0, FrameCodec.encodeFrame(Flags.pack(1), byteArrayOf(9)))
        // 随后视频流仍必须正常工作
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, StreamHeader(CodecId.AVC1, 1080, 2400).encode())
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, FrameCodec.encodeFrame(Flags.pack(42), byteArrayOf(7, 7)))

        val f = videoQueue.take(3_000)
        assertNotNull("未注册流不应影响后续正常流", f)
        assertEquals(42L, f!!.pts)
        assertNull(recorder.error)
        assertNull(recorder.closedReason)

        conn.close()
    }

    @Test
    fun `未知命令字被忽略且连接继续可用`() {
        val (conn, phoneSide, recorder) = startHost()
        val peer = Transport(phoneSide)

        val videoQueue = FrameQueue(StreamId.VIDEO)
        conn.registerSink(StreamId.VIDEO, videoQueue)

        // 未知命令 "PING"：合法 magic/checksum，但不在命令表里
        AdbMessage.writeTo(peer, Command.of("PING"), 0, 0, byteArrayOf(1, 2, 3))
        // 连接必须活着
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, StreamHeader(CodecId.AVC1, 720, 1600).encode())
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, FrameCodec.encodeFrame(Flags.pack(5), byteArrayOf(5)))

        assertNotNull(videoQueue.take(3_000))
        assertNull(recorder.error)

        conn.close()
    }

    @Test
    fun `杂散 payload 被跳过且不影响真流头与帧的接收`() {
        val (conn, phoneSide, recorder) = startHost()
        val peer = Transport(phoneSide)

        val videoQueue = FrameQueue(StreamId.VIDEO)
        conn.registerSink(StreamId.VIDEO, videoQueue)

        // 模拟"上一次会话残留在 FIFO 里的帧数据"抢先到达：它不是合法流头
        val staleFrame = FrameCodec.encodeFrame(Flags.pack(37_696_994_382L, keyFrame = true), bytes(1, 2, 3, 4))
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, staleFrame)
        // 再来一条更离谱的短 payload
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, byteArrayOf(9, 9))

        // 之后才是真正的流头
        val header = StreamHeader(CodecId.AVC1, 872, 1920)
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, header.encode())
        AdbMessage.writeTo(peer, Command.WRTE, StreamId.VIDEO, 0, FrameCodec.encodeFrame(Flags.pack(777), bytes(7)))

        // 必须能拿到正确流头（而不是被垃圾污染）
        val deadline = System.currentTimeMillis() + 3_000
        while (videoQueue.header == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("流头必须是真流头，不能被残留字节污染", header, videoQueue.header)
        assertEquals(872, conn.headerOf(StreamId.VIDEO)!!.width)

        val frame = videoQueue.take(3_000)
        assertNotNull(frame)
        assertEquals(777L, frame!!.pts)

        // 杂散计数应被记录下来（便于联调判断链路是否有残留）
        assertEquals(2L, conn.stats.strayPayloads.get())
        assertNull(recorder.error)

        conn.close()
    }

    @Test
    fun `会话级报文被交给 listener`() {
        val (conn, phoneSide, recorder) = startHost()
        val peer = Transport(phoneSide)

        AdbMessage.writeTo(peer, Command.CNXN, Protocol.VERSION, 0, Bytes.intLE(1080) + Bytes.intLE(2400))
        AdbMessage.writeTo(peer, Command.OPEN, StreamId.CONTROL, 0, Options.buildPayload(Options.defaultOptions(2400)))

        // 轮询等待（listener 由读线程回调）
        val deadline = System.currentTimeMillis() + 3_000
        while (recorder.messages.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertEquals(2, recorder.messages.size)
        assertEquals(Command.CNXN, recorder.messages[0].commandType)
        assertEquals(Protocol.VERSION, recorder.messages[0].arg0)
        assertEquals(Command.OPEN, recorder.messages[1].commandType)
        assertEquals(2_400, Options.parsePayload(recorder.messages[1].payload).maxSize)

        conn.close()
    }

    @Test
    fun `CLSE 携带 CLOSE_ALL 时收敛并通知关闭`() {
        val (conn, phoneSide, recorder) = startHost()
        val peer = Transport(phoneSide)
        val videoQueue = FrameQueue(StreamId.VIDEO)
        conn.registerSink(StreamId.VIDEO, videoQueue)

        AdbMessage.writeTo(peer, Command.CLSE, StreamId.CLOSE_ALL, 0, "user denied".toByteArray())

        assertTrue("应收到 onClosed", recorder.closedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(recorder.closedReason!!.contains("user denied"))
        assertTrue("流队列必须被关闭以唤醒消费端", videoQueue.isClosed)

        conn.close()
    }

    @Test
    fun `发送路径经单写线程串行化且能被对端完整读出`() {
        val (conn, phoneSide, _) = startHost()
        val peer = Transport(phoneSide)

        // 从多个线程并发发送，写侧必须串行化（不交错）
        val threads = (0 until 4).map { t ->
            Thread {
                repeat(25) { i ->
                    conn.send(Command.WRTE, StreamId.VIDEO, 0, byteArrayOf(t.toByte(), i.toByte()))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }

        // 对端逐条解析 100 条报文，全部必须定义完整
        val seen = mutableSetOf<Pair<Byte, Byte>>()
        repeat(100) {
            val msg = AdbMessage.readFrom(peer)
            assertEquals(Command.WRTE, msg.commandType)
            assertEquals(StreamId.VIDEO, msg.arg0)
            assertEquals(2, msg.payload.size)
            seen.add(msg.payload[0] to msg.payload[1])
        }
        assertEquals(100, seen.size)
        assertEquals(100L, conn.stats.messagesWritten.get())

        conn.close()
    }

    @Test
    fun `sendClose 携带原因串且被截断到 128 字节内`() {
        val (conn, phoneSide, _) = startHost()
        val peer = Transport(phoneSide)

        conn.sendClose(StreamId.VIDEO, "x".repeat(500))

        val msg = AdbMessage.readFrom(peer)
        assertEquals(Command.CLSE, msg.commandType)
        assertEquals(StreamId.VIDEO, msg.arg0)
        assertEquals(128, msg.payload.size)

        conn.close()
    }

    @Test
    fun `注册的 sink 可被查询`() {
        val (conn, _, _) = startHost()
        val q = FrameQueue(StreamId.VIDEO)
        conn.registerSink(StreamId.VIDEO, q)
        assertEquals(q, conn.sinkOf(StreamId.VIDEO))
        assertNull(conn.sinkOf(StreamId.CONTROL))
        assertNull(conn.headerOf(StreamId.VIDEO))
        assertTrue(conn.isRunning)
        conn.close()
    }
}
