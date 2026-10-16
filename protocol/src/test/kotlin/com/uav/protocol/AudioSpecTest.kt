package com.uav.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单测：音频流（内录）的线格式与参数自洽（计划 10.13）。
 *
 * 这一组测试钉的都是"错了不会报错、只会听起来不对"的东西：
 * 采样率/声道数在四个组件之间不一致、音频流头被当成视频流头校验、
 * 或者帧 payload 短到能被误认成 12B 流头。
 */
class AudioSpecTest {

    // ------------------------------------------------------------------ 参数

    @Test
    fun `音频参数自洽`() {
        assertEquals(48_000, AudioSpec.SAMPLE_RATE)
        assertEquals(2, AudioSpec.CHANNELS)
        assertEquals(1024, AudioSpec.AAC_FRAME_SAMPLES)

        // 一次读取的字节数 = 样本 × 声道 × 2B
        assertEquals(4096, AudioSpec.PCM_FRAME_BYTES)
        assertEquals(AudioSpec.AAC_FRAME_SAMPLES * AudioSpec.CHANNELS * 2, AudioSpec.PCM_FRAME_BYTES)

        // 每秒 PCM 流量：48k × 2ch × 2B = 192 KB/s（未压缩）
        assertEquals(192_000, AudioSpec.PCM_BYTES_PER_SECOND)

        // 1024 / 48000 s ≈ 21.333ms
        assertEquals(21_333L, AudioSpec.FRAME_DURATION_US)

        // 默认码率必须远小于未压缩 PCM，否则编码没有意义
        assertTrue(AudioSpec.DEFAULT_BIT_RATE / 8 < AudioSpec.PCM_BYTES_PER_SECOND)
    }

    // ------------------------------------------------------------------ 流头

    @Test
    fun `音频流头往返幂等且仍是 12 字节`() {
        val h = StreamHeader.audio(48_000, 2)
        assertEquals(StreamHeader.SIZE, h.encode().size)
        assertEquals(AudioSpec.CODEC_ID, h.codecId)

        val back = StreamHeader.decodeAudio(h.encode())
        assertEquals(h, back)
        assertEquals(48_000, back.audioSampleRate)
        assertEquals(2, back.audioChannels)
    }

    @Test
    fun `音频与视频流头线格式长度相同`() {
        // ★ 这条长度相等是"payload 恰 12B 只可能是流头"这条判据成立的前提，
        //   视频旋转与音频重连都靠它识别流头，改长度会让判据失效。
        assertEquals(
            StreamHeader(CodecId.AVC1, 872, 1920).encode().size,
            StreamHeader.audio().encode().size,
        )
    }

    @Test
    fun `视频流头不会被当成音频流头`() {
        // 872/1920 落在音频的合法区间里，唯一挡住它的是 codecId
        val video = StreamHeader(CodecId.AVC1, 872, 1920).encode()
        assertThrows(ProtocolException::class.java) { StreamHeader.decodeAudio(video) }
    }

    @Test
    fun `音频流头拒绝越界的采样率与声道数`() {
        assertThrows(ProtocolException::class.java) {
            StreamHeader.decodeAudio(StreamHeader(AudioSpec.CODEC_ID, 4_000, 2).encode())
        }
        assertThrows(ProtocolException::class.java) {
            StreamHeader.decodeAudio(StreamHeader(AudioSpec.CODEC_ID, 48_000, 0).encode())
        }
        assertThrows(ProtocolException::class.java) {
            StreamHeader.decodeAudio(StreamHeader(AudioSpec.CODEC_ID, 48_000, 9).encode())
        }
    }

    @Test
    fun `音频流头拒绝非可打印 codecId`() {
        val junk = ByteArray(StreamHeader.SIZE) { 0x01 }
        assertThrows(ProtocolException::class.java) { StreamHeader.decodeAudio(junk) }
    }

    @Test
    fun `audio 工厂拒绝越界参数`() {
        assertThrows(IllegalArgumentException::class.java) { StreamHeader.audio(4_000, 2) }
        assertThrows(IllegalArgumentException::class.java) { StreamHeader.audio(48_000, 0) }
    }

    @Test
    fun `音频流头日志渲染成 Hz 与声道`() {
        val s = StreamHeader.audio(48_000, 2).toString()
        assertTrue("音频不该渲染成 48000x2：$s", s.contains("48000Hz"))
        assertTrue(s.contains("2ch"))
        // 视频仍是宽高
        assertTrue(StreamHeader(CodecId.AVC1, 872, 1920).toString().contains("872x1920"))
    }

    @Test
    fun `解码回来的音频流头也渲染成 Hz 与声道`() {
        // 解码会去掉 codecId 的右填充空格，若比较没规范化，日志就是 "aac 48000x2"
        val decoded = StreamHeader.decodeAudio(StreamHeader.audio(48_000, 2).encode())
        assertTrue("解码后渲染错误：$decoded", decoded.toString().contains("48000Hz"))
        assertTrue(decoded.toString().contains("2ch"))
    }

    @Test
    fun `全空格 codecId 必须抛协议异常`() {
        // ★ 空格是"可打印 ASCII"，能过前面那道可打印性检查；若不额外拦，
        //   会解出空串并让 CodecId.normalize 抛 IllegalArgumentException ——
        //   调用方只 catch ProtocolException，异常会穿透并把读线程打挂。
        val junk = ByteArray(StreamHeader.SIZE) { 0x20 }
        assertThrows(ProtocolException::class.java) { StreamHeader.decode(junk) }
    }

    // ------------------------------------------------------------------ 帧

    @Test
    fun `帧元数据对音频小载荷同样可用`() {
        val payload = ByteArray(320) { (it % 251).toByte() }
        val pts = 1_234_567L
        val out = FrameCodec.encodeFrame(pts, payload)
        assertEquals(FrameCodec.META_SIZE + payload.size, out.size)

        val frame = FrameCodec.decodeFrame(out)
        assertEquals(pts, frame.ptsAndFlags)
        assertArrayEquals(payload, frame.data)
    }

    @Test
    fun `非空帧的 payload 一定长于流头`() {
        // ★ 接收端靠"payload 恰为 12B ⇒ 这是流头"来识别流头/重发的流头。
        //   帧 payload = 12 + size，只要 size ≥ 1 就 ≥ 13，不存在歧义 ——
        //   但 **size == 0 的帧会造出恰 12B 的 payload**，被误判成流头。
        //   所以发送端必须保证不产出空帧（手机端在 emit 前过滤 size > 0）。
        val empty = FrameCodec.encodeFrame(0L, ByteArray(0))
        assertEquals(
            "空帧的 payload 恰好等于流头长度 —— 这正是必须过滤空帧的原因",
            StreamHeader.SIZE, empty.size,
        )

        val oneByte = FrameCodec.encodeFrame(0L, ByteArray(1))
        assertNotEquals(StreamHeader.SIZE, oneByte.size)
        assertEquals(StreamHeader.SIZE + 1, oneByte.size)
    }

    // ------------------------------------------------------------------ 协商

    @Test
    fun `音频码率可在服务串里往返`() {
        val o = Options.defaultOptions(2400).apply { audioBitRate = 96_000 }
        val parsed = Options.parsePayload(Options.buildPayload(o))
        assertEquals(96_000, parsed.options.audioBitRate)
        assertTrue(parsed.audio)
    }

    @Test
    fun `关掉音频后服务串里看得到`() {
        val o = Options.defaultOptions(2400).apply { audio = false }
        val parsed = Options.parsePayload(Options.buildPayload(o))
        assertEquals(false, parsed.audio)
    }
}
