package com.uav.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单测 7：`OPEN` 服务串的往返幂等与向前兼容（计划 1.7 / 4.3 / 4.5）。 */
class OptionsTest {

    @Test
    fun `默认参数集与冻结默认值一致`() {
        val o = Options.defaultOptions(maxSize = 2400)
        assertEquals(2_400, o.maxSize)
        assertEquals(8_000_000, o.videoBitRate)
        assertEquals(30, o.maxFps)
        assertTrue("10.13 起音频默认开启", o.audio)
        assertEquals(AudioSpec.DEFAULT_BIT_RATE, o.audioBitRate)
        assertEquals(3, Options.I_FRAME_INTERVAL_SECONDS)
    }

    @Test
    fun `服务名与流 id 常量一致`() {
        assertEquals("newScreen1:2", Options.serviceName())
        assertEquals("newScreen1:2", Options.serviceName(StreamId.VIDEO, StreamId.AUDIO))
    }

    @Test
    fun `payload 必须以 NUL 结尾`() {
        val payload = Options.buildPayload(Options.defaultOptions(2400))
        assertEquals(0.toByte(), payload[payload.size - 1])
    }

    @Test
    fun `payload 文本形态符合冻结格式`() {
        val payload = Options.buildPayload(Options.defaultOptions(2400))
        val text = String(payload, 0, payload.size - 1, Charsets.UTF_8)
        assertTrue(text.startsWith("newScreen1:2 "))
        assertTrue(text.contains("max_size=2400"))
        assertTrue(text.contains("video_bit_rate=8000000"))
        assertTrue(text.contains("max_fps=30"))
        assertTrue(text.contains("audio=true"))
        assertTrue(text.contains("audio_bit_rate=128000"))
    }

    @Test
    fun `往返幂等`() {
        val original = Options.defaultOptions(maxSize = 2400).apply {
            videoBitRate = 12_000_000
            maxFps = 60
        }
        val payload = Options.buildPayload(original)
        val parsed = Options.parsePayload(payload)

        assertEquals(StreamId.VIDEO, parsed.videoId)
        assertEquals(StreamId.AUDIO, parsed.audioId)
        assertEquals(original.maxSize, parsed.maxSize)
        assertEquals(original.videoBitRate, parsed.videoBitRate)
        assertEquals(original.maxFps, parsed.maxFps)
        assertEquals(original.audio, parsed.audio)

        // 再序列化一次应逐字节相同
        val again = Options.buildPayload(parsed.options, parsed.videoId, parsed.audioId)
        org.junit.Assert.assertArrayEquals(payload, again)
    }

    @Test
    fun `未知参数被忽略而不报错`() {
        val text = "newScreen1:2 max_size=1600&future_flag=1&unknown=xyz"
        val payload = (text + "\u0000").toByteArray(Charsets.UTF_8)

        val parsed = Options.parsePayload(payload)

        assertEquals(1_600, parsed.maxSize)
        assertEquals("1", parsed.options.get("future_flag"))
        assertEquals("xyz", parsed.options.get("unknown"))
        // 未提供的参数回落到默认值
        assertEquals(Options.DEFAULT_VIDEO_BIT_RATE, parsed.videoBitRate)
        assertEquals(Options.DEFAULT_MAX_FPS, parsed.maxFps)
    }

    @Test
    fun `畸形参数片段被跳过且不影响其余参数`() {
        val text = "newScreen1:2 max_size=2400&&bogus&=empty&audio=false"
        val payload = (text + "\u0000").toByteArray(Charsets.UTF_8)

        val parsed = Options.parsePayload(payload)
        assertEquals(2_400, parsed.maxSize)
        assertFalse(parsed.audio)
        assertNull(parsed.options.get("bogus"))
    }

    @Test
    fun `缺少 NUL 结尾也能解析（容忍）`() {
        val payload = "newScreen1:2 max_size=1080".toByteArray(Charsets.UTF_8)
        assertEquals(1_080, Options.parsePayload(payload).maxSize)
    }

    @Test
    fun `服务名格式非法时抛协议错误`() {
        val bad = "notAScreen 1:2".toByteArray(Charsets.UTF_8)
        assertThrows(ProtocolException::class.java) { Options.parsePayload(bad) }
    }

    @Test
    fun `无参数时服务串不带尾随空格`() {
        val payload = Options.buildPayload(Options())
        val text = String(payload, 0, payload.size - 1, Charsets.UTF_8)
        assertEquals("newScreen1:2", text)
        // 无参数 -> 解析出空 options
        assertEquals(0, Options.parsePayload(payload).options.size)
    }

    @Test
    fun `默认构造的 Options 为空`() {
        val o = Options()
        assertTrue(o.isEmpty())
        assertEquals(0, o.size)
        assertNull(o.maxSize)
    }

    // ------------------------------------------------------------------ 1:1 显示区（10.15）

    @Test
    fun `显示区随 OPEN 往返且老宿主不带这两项`() {
        val withArea = Options.defaultOptions(1024, 1920, 1023)
        val text = withArea.toParamString()
        assertTrue("串里要看得见，才好真机核对：$text", text.contains("display_width=1920"))
        assertTrue(text.contains("display_height=1023"))

        val back = Options.parsePayload(Options.buildPayload(withArea))
        assertEquals(1920, back.displayWidth)
        assertEquals(1023, back.displayHeight)
        assertEquals(1024, back.maxSize)

        // 老宿主（不给显示区）—— 解析回来必须是 null，而不是 0
        val old = Options.parsePayload(Options.buildPayload(Options.defaultOptions(1920)))
        assertEquals(null, old.displayWidth)
        assertEquals(null, old.displayHeight)
        assertFalse(old.options.toParamString().contains("display_"))
    }

    @Test
    fun `非法显示区被当作未提供`() {
        val o = Options.defaultOptions(1024, 0, -5)
        assertEquals(null, o.displayWidth)
        assertEquals(null, o.displayHeight)
        assertFalse(o.toParamString().contains("display_"))

        // 解析侧同理：0/负数/非数字一律当没给
        val parsed = Options.parsePayload(
            "newScreen1:2 max_size=1024&display_width=0&display_height=abc".toByteArray() + byteArrayOf(0)
        )
        assertEquals(null, parsed.displayWidth)
        assertEquals(null, parsed.displayHeight)
    }

    @Test
    fun `编码尺寸优先用显示区算 1比1，缺省退回 max_size`() {
        val withArea = Options.parsePayload(
            Options.buildPayload(Options.defaultOptions(1920, 1920, 1023))
        )
        // 显示区 1920x1023 + 手机 1224x2700 -> 1024（不是 max_size 的 1920）
        assertEquals(1024, withArea.encodeMaxSize(1224, 2700))
        // 同一显示区，手机旋转成横屏 -> 现算出更大的值
        assertEquals(1920, withArea.encodeMaxSize(2700, 1224))

        val old = Options.parsePayload(Options.buildPayload(Options.defaultOptions(1920)))
        assertEquals(1920, old.encodeMaxSize(1224, 2700))
        assertEquals(null, Options.parsePayload(Options.buildPayload(Options())).encodeMaxSize(1224, 2700))
    }
}
