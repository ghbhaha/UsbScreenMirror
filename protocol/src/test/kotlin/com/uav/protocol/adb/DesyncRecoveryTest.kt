package com.uav.protocol.adb

import com.uav.protocol.Bytes
import com.uav.protocol.DesyncException
import com.uav.protocol.FakeByteChannel
import com.uav.protocol.MemoryPipe
import com.uav.protocol.aoa.Command
import com.uav.protocol.aoa.Connection
import com.uav.protocol.aoa.ConnectionListener
import com.uav.protocol.aoa.Flags
import com.uav.protocol.aoa.Frame
import com.uav.protocol.aoa.FrameCodec
import com.uav.protocol.aoa.FrameQueue
import com.uav.protocol.aoa.Protocol
import com.uav.protocol.aoa.StreamHeader
import com.uav.protocol.aoa.StreamId
import com.uav.protocol.aoa.Transport
import com.uav.protocol.media.CodecId
import com.uav.protocol.rawMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 失步自愈（[DesyncRecovery]）单测。
 *
 * 背景：AOA 是"点对点字节流、无事务边界"，宿主连上来的那一刻配件端可能正卡在某条报文中间
 * （实测 RK356x 盒子 + HONOR：每次重连都在 `bad magic` 上死掉，宿主每 330ms 重试一次，
 * 永远连不上）。这里钉住"错位之后能自己爬回报文边界"这条能力。
 */
class DesyncRecoveryTest {

    // ------------------------------------------------------------------ 头部判据

    @Test
    fun `合法报文头被接受`() {
        val header = rawMessage(Command.CNXN, Protocol.VERSION, 3, ByteArray(8))
            .copyOfRange(0, AdbMessage.HEADER_SIZE)
        assertTrue(DesyncRecovery.isPlausibleHeader(header))
    }

    @Test
    fun `magic 不符的报文头被拒绝`() {
        val header = rawMessage(Command.CNXN, 0, 0, ByteArray(0)).copyOfRange(0, AdbMessage.HEADER_SIZE)
        Bytes.putIntLE(header, 20, 0x12345678) // 破坏 magic
        assertFalse(DesyncRecovery.isPlausibleHeader(header))
    }

    @Test
    fun `未知命令字被拒绝`() {
        val header = ByteArray(AdbMessage.HEADER_SIZE)
        // magic 自洽，但命令字不在白名单里
        Bytes.putIntLE(header, 0, 0x11223344)
        Bytes.putIntLE(header, 20, 0x11223344 xor -1)
        assertFalse(DesyncRecovery.isPlausibleHeader(header))
    }

    @Test
    fun `WRTE 的流 id 未知时被拒绝`() {
        val header = rawMessage(Command.WRTE, 99, 0, ByteArray(64)).copyOfRange(0, AdbMessage.HEADER_SIZE)
        assertFalse(DesyncRecovery.isPlausibleHeader(header))
    }

    @Test
    fun `WRTE 载荷短于 12B 元数据时被拒绝`() {
        val header = rawMessage(Command.WRTE, StreamId.VIDEO, 0, ByteArray(4))
            .copyOfRange(0, AdbMessage.HEADER_SIZE)
        assertFalse(DesyncRecovery.isPlausibleHeader(header))
    }

    // ------------------------------------------------------------------ 扫描

    @Test
    fun `跳过任意长度垃圾后定位到报文头`() {
        val garbage = ByteArray(5000) { 0xAA.toByte() }
        val real = rawMessage(Command.CNXN, Protocol.VERSION, 3, ByteArray(8))
        val channel = FakeByteChannel(garbage + real)
        val dst = ByteArray(AdbMessage.HEADER_SIZE)

        val skipped = DesyncRecovery.scanForHeader(Transport(channel), dst)

        assertEquals(garbage.size, skipped)
        assertEquals(Command.CNXN.wire, Bytes.getIntLE(dst, 0))
    }

    @Test
    fun `垃圾为 0 字节时偏移量为 0`() {
        val real = rawMessage(Command.OPEN, 0, 0, ByteArray(0))
        val dst = ByteArray(AdbMessage.HEADER_SIZE)

        val skipped = DesyncRecovery.scanForHeader(Transport(FakeByteChannel(real)), dst)

        assertEquals(0, skipped)
        assertEquals(Command.OPEN.wire, Bytes.getIntLE(dst, 0))
    }

    @Test(expected = DesyncException::class)
    fun `超出预算仍找不到报文头则判失败`() {
        val channel = FakeByteChannel(ByteArray(500) { 0xAA.toByte() })
        DesyncRecovery.scanForHeader(Transport(channel), ByteArray(AdbMessage.HEADER_SIZE), maxSkip = 10)
    }

    // ------------------------------------------------------------------ 连接级自愈

    private class Recorder : ConnectionListener {
        val messages = mutableListOf<AdbMessage>()
        var error: Throwable? = null

        override fun onMessage(message: AdbMessage) {
            messages.add(message)
        }

        override fun onError(cause: Throwable) {
            error = cause
        }

        override fun onClosed(reason: String) = Unit
    }

    @Test
    fun `读到半条报文时自动重同步并继续投递后续报文`() {
        val (hostSide, phoneSide) = MemoryPipe.pair()
        val peer = Transport(phoneSide)
        val recorder = Recorder()
        val conn = Connection(Transport(hostSide), listener = recorder)
        val videoQueue = FrameQueue(StreamId.VIDEO)
        conn.registerSink(StreamId.VIDEO, videoQueue)

        // ① 上一次会话的尾巴：我们从一个**非报文边界**开始读（模拟 FIFO 里的半条报文）
        val residue = ByteArray(5000) { 0xAA.toByte() }

        // ② 真正的报文：CNXN（握手）→ 流头 → 一帧
        val header = StreamHeader(CodecId.AVC1, 720, 1600)
        val payload = byteArrayOf(9, 8, 7, 6)
        val wire = ByteArrayOutputStream().apply {
            write(residue)
            write(rawMessage(Command.CNXN, Protocol.VERSION, 3, ByteArray(8)))
            write(rawMessage(Command.WRTE, StreamId.VIDEO, 0, header.encode()))
            write(
                rawMessage(
                    Command.WRTE,
                    StreamId.VIDEO,
                    0,
                    FrameCodec.encodeFrame(Flags.pack(123, keyFrame = true), payload),
                )
            )
        }.toByteArray()
        peer.write(wire)

        conn.start()

        val frame: Frame? = videoQueue.take(3_000)
        assertNotNull("重同步之后帧应该照常投递", frame)
        assertEquals(payload.size, frame!!.size)
        assertEquals(1, conn.stats.resyncs.get())
        // 错位 5000 字节，但**第一次解析已经先吃掉 24 字节**（它被当成一个报文头读走了，
        // 然后 magic 不符），所以重同步只需再跳 5000-24 字节。
        assertEquals(5000L - AdbMessage.HEADER_SIZE, conn.stats.resyncSkippedBytes.get())
        // 握手报文不能被这次重同步吃掉
        assertTrue(recorder.messages.any { it.commandType == Command.CNXN })
        assertEquals(null, recorder.error)
        conn.close()
    }
}
