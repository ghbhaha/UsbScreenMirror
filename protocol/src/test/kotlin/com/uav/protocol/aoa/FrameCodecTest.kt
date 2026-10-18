package com.uav.protocol.aoa

import com.uav.protocol.Bytes
import com.uav.protocol.ProtocolException
import com.uav.protocol.bytes
import com.uav.protocol.media.CodecId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 流头与帧元数据的编解码（计划 1.6 / 4.4）。 */
class FrameCodecTest {

    // ------------------------------------------------------------------ 流头

    @Test
    fun `流头是 12 字节且宽高为小端`() {
        val h = StreamHeader(CodecId.AVC1, 720, 1600)
        val bytes = h.encode()

        assertEquals(12, bytes.size)
        assertEquals(StreamHeader.SIZE, bytes.size)
        assertEquals("avc1", String(bytes, 0, 4, Charsets.US_ASCII))
        // 小端：低字节在前
        assertEquals(0xD0.toByte(), bytes[4]) // 720 = 0x2D0
        assertEquals(0x02.toByte(), bytes[5])
        assertEquals(0x40.toByte(), bytes[8]) // 1600 = 0x640
        assertEquals(0x06.toByte(), bytes[9])
    }

    @Test
    fun `流头往返一致`() {
        val h = StreamHeader(CodecId.AVC1, 1080, 2400)
        assertEquals(h, StreamHeader.decode(h.encode()))
    }

    @Test
    fun `流头宽高是唯一的尺寸权威来源`() {
        val h = StreamHeader(CodecId.AVC1, 720, 1600)
        assertEquals(720, h.width)
        assertEquals(1_600, h.height)
        assertTrue(h.isPortrait)
        assertEquals(720f / 1600f, h.aspectRatio, 1e-6f)
    }

    @Test
    fun `流头长度不足时抛协议错误`() {
        assertThrows(ProtocolException::class.java) { StreamHeader.decode(ByteArray(11)) }
    }

    @Test
    fun `流头宽高非法时抛协议错误`() {
        val b = StreamHeader(CodecId.AVC1, 720, 1600).encode()
        Bytes.putIntLE(b, 4, 0)
        assertThrows(ProtocolException::class.java) { StreamHeader.decode(b) }
    }

    // ---------------------------------------------------------------- 流头严格校验
    // 背景（真机实测）：上一次会话残留在 USB FIFO 里的帧数据会被"首个 WRTE 即流头"
    // 这条约定误解析，得到 StreamHeader(乱码 8x466) 并静默配错解码器。以下用例把这类误解析钉死。

    @Test
    fun `真实帧数据被误当流头时会被拒绝`() {
        // 用真机抓流里的真实 PTS，其低 32 位含非可打印字节
        val ptsAndFlags = Flags.pack(37_696_994_382L, keyFrame = true)
        val framePayload = FrameCodec.encodeFrame(ptsAndFlags, bytes(0x67, 0x42, 0x00, 0x1E, 0x95))

        val e = assertThrows(ProtocolException::class.java) { StreamHeader.decode(framePayload) }
        assertTrue("应在 codecId 上失败: ${e.message}", e.message!!.contains("codecId"))
    }

    @Test
    fun `codecId 全可打印但尺寸荒谬时也会被拒绝`() {
        // 构造 12B：codecId 恰好是 "avc1"，但宽高是被误读出来的垃圾值
        val bad = ByteArray(12)
        System.arraycopy(CodecId.encode(CodecId.AVC1), 0, bad, 0, 4)
        Bytes.putIntLE(bad, 4, 8)
        Bytes.putIntLE(bad, 8, 466)

        val e = assertThrows(ProtocolException::class.java) { StreamHeader.decode(bad) }
        assertTrue(e.message!!.contains("尺寸不合理"))
    }

    @Test
    fun `尺寸下限与上限被强制执行`() {
        val below = StreamHeader(CodecId.AVC1, 720, 1600).encode().also { Bytes.putIntLE(it, 4, 15) }
        assertThrows(ProtocolException::class.java) { StreamHeader.decode(below) }

        val above = StreamHeader(CodecId.AVC1, 720, 1600).encode().also { Bytes.putIntLE(it, 8, 8193) }
        assertThrows(ProtocolException::class.java) { StreamHeader.decode(above) }

        // 边界内应通过
        assertEquals(16, StreamHeader.decode(StreamHeader(CodecId.AVC1, 16, 16).encode()).width)
        assertEquals(8192, StreamHeader.decode(StreamHeader(CodecId.AVC1, 8192, 8192).encode()).width)
    }

    @Test
    fun `非视频流只校验 codecId 不校验数值范围`() {
        // 音频流的宽高字段语义未定义（MVP 不启用音频），不能因为 48000x2 这种值被拒
        val audio = StreamHeader(CodecId.OPUS, 48000, 2).encode()
        val decoded = StreamHeader.decode(audio, expectVideoDims = false)
        assertEquals(48_000, decoded.width)
        assertEquals(2, decoded.height)
        // 但 codecId 依然要合法
        val garbage = ByteArray(12) { 0xFF.toByte() }
        assertThrows(ProtocolException::class.java) {
            StreamHeader.decode(garbage, expectVideoDims = false)
        }
    }

    @Test
    fun `合法流头仍能通过严格校验`() {
        for (size in listOf(720 to 1600, 872 to 1920, 1080 to 2400, 1224 to 2700)) {
            val h = StreamHeader(CodecId.AVC1, size.first, size.second)
            assertEquals(h, StreamHeader.decode(h.encode()))
        }
    }

    // ------------------------------------------------------------------ 帧

    @Test
    fun `帧元数据是 8B 标志加 4B 长度`() {
        val data = byteArrayOf(9, 8, 7, 6)
        val payload = FrameCodec.encodeFrame(Flags.pack(500, keyFrame = true), data)

        assertEquals(12 + 4, payload.size)
        assertEquals(FrameCodec.META_SIZE, 12)
        assertEquals(Flags.pack(500, keyFrame = true), Bytes.getLongLE(payload, 0))
        assertEquals(4, Bytes.getIntLE(payload, 8))
        assertArrayEquals(data, payload.copyOfRange(12, 16))
    }

    @Test
    fun `帧往返一致且标志不丢`() {
        val data = ByteArray(64) { it.toByte() }
        val ptsAndFlags = Flags.pack(123_456, config = false, keyFrame = true)
        val frame = FrameCodec.decodeFrame(FrameCodec.encodeFrame(ptsAndFlags, data))

        assertEquals(ptsAndFlags, frame.ptsAndFlags)
        assertEquals(123_456L, frame.pts)
        assertTrue(frame.isKeyFrame)
        assertEquals(64, frame.size)
        assertArrayEquals(data, frame.data)
    }

    @Test
    fun `CONFIG 帧能被识别`() {
        val frame = FrameCodec.decodeFrame(
            FrameCodec.encodeFrame(Flags.pack(0, config = true), byteArrayOf(0, 0, 0, 1, 0x67))
        )
        assertTrue(frame.isConfig)
        assertEquals(5, frame.size)
    }

    @Test
    fun `声明的 size 与实际不符时抛协议错误`() {
        val payload = FrameCodec.encodeFrame(Flags.pack(1), byteArrayOf(1, 2, 3, 4))
        Bytes.putIntLE(payload, 8, 99) // 谎报长度
        val e = assertThrows(ProtocolException::class.java) { FrameCodec.decodeFrame(payload) }
        assertTrue(e.message!!.contains("mismatch"))
    }

    @Test
    fun `帧 size 超过 8 MiB 上限时抛协议错误`() {
        val payload = ByteArray(FrameCodec.META_SIZE)
        Bytes.putLongLE(payload, 0, Flags.pack(1))
        Bytes.putIntLE(payload, 8, Protocol.MAX_FRAME_SIZE + 1)
        assertThrows(ProtocolException::class.java) { FrameCodec.decodeFrame(payload) }
    }

    @Test
    fun `元数据不足 12 字节时抛协议错误`() {
        assertThrows(ProtocolException::class.java) { FrameCodec.decodeFrame(ByteArray(11)) }
    }

    @Test
    fun `空帧（size=0）被允许`() {
        val payload = FrameCodec.encodeFrame(Flags.pack(7), ByteArray(0))
        val frame = FrameCodec.decodeFrame(payload)
        assertEquals(0, frame.size)
        assertEquals(7L, frame.pts)
    }

    // ------------------------------------------------------------------ codecId

    @Test
    fun `codecId 恒为 4 个 ASCII 字符`() {
        assertEquals("avc1", CodecId.normalize("avc1"))
        assertEquals("raw ", CodecId.normalize("raw"))
        assertEquals("aac ", CodecId.normalize("aac"))
        assertEquals("opus", CodecId.normalize("opus"))
        assertEquals("flac", CodecId.normalize("flac"))
    }

    @Test
    fun `codecId 编解码往返一致`() {
        for (id in CodecId.KNOWN) {
            val bytes = CodecId.encode(id)
            assertEquals(4, bytes.size)
            // decode 会去掉右侧补位空格（预期行为），再规范化后必须与原始值一致
            assertEquals(CodecId.normalize(id), CodecId.normalize(CodecId.decode(bytes)))
            // 更强的性质：encode(decode(bytes)) 与 bytes 逐字节相同
            assertArrayEquals(bytes, CodecId.encode(CodecId.decode(bytes)))
        }
    }

    @Test
    fun `短 codecId 右补空格且可无损还原`() {
        assertArrayEquals("raw ".toByteArray(Charsets.US_ASCII), CodecId.encode("raw"))
        assertEquals("raw", CodecId.decode(CodecId.encode("raw")))
    }

    @Test
    fun `H264 的 codecId 是 avc1 而不是 h264`() {
        // 以计划 4.4 的冻结清单为准，两端必须逐字一致
        assertEquals("avc1", CodecId.AVC1)
        assertTrue(CodecId.KNOWN.contains("avc1"))
    }

    @Test
    fun `非法 codecId 被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { CodecId.normalize("toolong") }
        assertThrows(IllegalArgumentException::class.java) { CodecId.normalize("") }
    }
}
