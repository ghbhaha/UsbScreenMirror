package com.uav.protocol.aoa

import com.uav.protocol.Bytes
import com.uav.protocol.FakeByteChannel
import com.uav.protocol.ProtocolException
import com.uav.protocol.adb.AdbMessage
import com.uav.protocol.bytes
import com.uav.protocol.media.CodecId
import com.uav.protocol.rawMessage
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧感知读（10.34）的回归测试。
 *
 * 背景：`WRTE` 帧的 payload 是「12B 元数据 + 码流」。老路径是**先整包读进一个 ByteArray**，
 * 再由 `FrameCodec.decodeFrame` 把码流 `arraycopy` 到另一个数组 —— 每帧两次分配、两次拷贝。
 * 新路径把两段分开读，码流直接落进 `Frame.data`。
 *
 * 这几条用例锁住三件事：
 *  1. 快路径解析结果与老路径**逐字节一致**（含 pts / flags / 尺寸）；
 *  2. 12B 流头**不能**被当成帧（`data_length == 12` 必须留在通用路径）；
 *  3. 校验与错误语义不变（尺寸不符 / checksum 不符仍然抛 `ProtocolException`）。
 */
class FrameFastPathTest {

    private val meta = ByteArray(FrameCodec.META_SIZE)

    /** 造一条 WRTE 帧报文；`declaredSize` 允许故意写错，用于测尺寸校验。 */
    private fun frameMessage(
        ptsAndFlags: Long,
        data: ByteArray,
        declaredSize: Int = data.size,
        declaredChecksum: Int? = null,
    ): ByteArray {
        val payload = ByteArray(FrameCodec.META_SIZE + data.size)
        Bytes.putLongLE(payload, 0, ptsAndFlags)
        Bytes.putIntLE(payload, 8, declaredSize)
        System.arraycopy(data, 0, payload, FrameCodec.META_SIZE, data.size)
        return rawMessage(
            Command.WRTE,
            arg0 = StreamId.VIDEO,
            payload = payload,
            declaredChecksum = declaredChecksum ?: AdbMessage.checksumOf(payload),
        )
    }

    @Test
    fun `帧感知读把码流直接读进 Frame，payload 为空`() {
        val codec = ByteArray(1000) { (it * 7).toByte() }
        val ptsAndFlags = Flags.pack(123_456_789L, keyFrame = true)
        val t = Transport(FakeByteChannel(frameMessage(ptsAndFlags, codec)), readTimeoutMs = 5_000)

        val msg = AdbMessage.readFrom(t, meta)

        val frame = assertNotNull(msg.frame).let { msg.frame!! }
        assertArrayEquals(codec, frame.data)
        assertEquals(ptsAndFlags, frame.ptsAndFlags)
        assertEquals(codec.size, frame.size)
        assertTrue(frame.isKeyFrame)
        // 快路径下 payload 必须为空 —— 否则等于又分配了一整包没用的缓冲
        assertEquals(0, msg.payload.size)
        assertEquals(Command.WRTE, msg.commandType)
        assertEquals(StreamId.VIDEO, msg.arg0)
    }

    @Test
    fun `不传 frameMeta 时行为与老路径完全一致`() {
        val codec = ByteArray(300) { (it + 1).toByte() }
        val payload = FrameCodec.encodeFrame(42L, codec)
        val bytes = rawMessage(Command.WRTE, arg0 = StreamId.VIDEO, payload = payload)

        val msg = AdbMessage.readFrom(Transport(FakeByteChannel(bytes), readTimeoutMs = 5_000))

        assertNull(msg.frame)
        assertArrayEquals(payload, msg.payload)
    }

    @Test
    fun `12B 流头不会被当成帧`() {
        val header = StreamHeader(CodecId.AVC1, 872, 1920)
        val t = Transport(
            FakeByteChannel(rawMessage(Command.WRTE, arg0 = StreamId.VIDEO, payload = header.encode())),
            readTimeoutMs = 5_000,
        )

        val msg = AdbMessage.readFrom(t, meta)

        assertNull("12B 是流头，必须留在通用路径", msg.frame)
        assertEquals(StreamHeader.SIZE, msg.payload.size)
    }

    @Test
    fun `帧尺寸与 data_length 不符时报错`() {
        val data = ByteArray(64) { 1 }
        val bytes = frameMessage(0L, data, declaredSize = data.size + 1)
        val t = Transport(FakeByteChannel(bytes), readTimeoutMs = 2_000)

        val e = org.junit.Assert.assertThrows(ProtocolException::class.java) {
            AdbMessage.readFrom(t, meta)
        }
        assertTrue("要明确指出尺寸不符: ${e.message}", e.message!!.contains("size mismatch"))
    }

    @Test
    fun `checksum 不符时报错（分段累加与整包等价）`() {
        val data = ByteArray(50) { (it * 3).toByte() }
        val payload = FrameCodec.encodeFrame(7L, data)
        val bytes = rawMessage(
            Command.WRTE,
            arg0 = StreamId.VIDEO,
            payload = payload,
            declaredChecksum = AdbMessage.checksumOf(payload) + 1,
        )
        val t = Transport(FakeByteChannel(bytes), readTimeoutMs = 2_000)

        val e = org.junit.Assert.assertThrows(ProtocolException::class.java) {
            AdbMessage.readFrom(t, meta)
        }
        assertTrue(e.message!!.contains("checksum"))
    }

    @Test
    fun `帧数据被切成每字节一次也能正确拼出`() {
        val codec = ByteArray(40) { (it * 5).toByte() }
        val bytes = frameMessage(99L, codec)
        val t = Transport(FakeByteChannel(bytes, chunk = 1), readTimeoutMs = 10_000)

        val msg = AdbMessage.readFrom(t, meta)

        assertArrayEquals(codec, msg.frame!!.data)
    }

    @Test
    fun `帧被截断时报 EOF 而不是返回半个帧`() {
        val data = ByteArray(200) { 2 }
        val bytes = frameMessage(0L, data)
        val t = Transport(FakeByteChannel(bytes.copyOf(bytes.size - 10)), readTimeoutMs = 2_000)

        org.junit.Assert.assertThrows(EOFException::class.java) { AdbMessage.readFrom(t, meta) }
    }

    @Test
    fun `非 WRTE 命令不受帧感知影响`() {
        val payload = ByteArray(16) { it.toByte() }
        val bytes = rawMessage(Command.CNXN, arg0 = 1, payload = payload)
        val t = Transport(FakeByteChannel(bytes), readTimeoutMs = 2_000)

        val msg = AdbMessage.readFrom(t, meta)

        assertNull(msg.frame)
        assertArrayEquals(payload, msg.payload)
    }

    @Test
    fun `复用同一个 meta 缓冲连读多帧互不干扰`() {
        val frames = (1..5).map { i -> ByteArray(10 * i) { (it + i).toByte() } }
        val stream = frames.mapIndexed { i, d ->
            frameMessage(i.toLong(), d)
        }.reduce { a, b -> a + b }

        val t = Transport(FakeByteChannel(stream), readTimeoutMs = 5_000)
        frames.forEachIndexed { i, expected ->
            val msg = AdbMessage.readFrom(t, meta)
            assertArrayEquals("第 $i 帧", expected, msg.frame!!.data)
            assertEquals(i.toLong(), msg.frame!!.pts)
        }
    }
}
