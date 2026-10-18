package com.uav.protocol.ios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频样本解析自检。
 *
 * 用的不是编造数据，而是 **quicktime_video_hack 项目里真机抓的 `asyn-eat` 样本**
 * （`protocol/src/test/resources/ios/asyn-eat.bin`，4332B）——
 * 这样"能解析"才有意义。
 */
class AudioParserTest {

    private fun sample(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("ios/asyn-eat.bin")!!.use { it.readBytes() }

    @Test
    fun `真机样本的结构能被识别为 EAT 音频帧`() {
        val p = sample()
        assertEquals("总长", 4332, p.size)
        // [asyn][clock u64][eat!][sbuf...]
        assertEquals("asyn", String(p.copyOfRange(0, 4).reversedArray(), Charsets.US_ASCII))
        assertEquals("eat!", String(p.copyOfRange(12, 16).reversedArray(), Charsets.US_ASCII))
    }

    @Test
    fun `解析出 4096 字节 PCM`() {
        val pcm = AudioParser.pcm(sample())
        assertNotNull("应能解出 PCM", pcm)
        assertEquals("sdat 段 4104B，去掉 8B 头", 4096, pcm!!.size)
    }

    @Test
    fun `格式取自 fdsc 里真实的 ASBD`() {
        val f = AudioParser.format(sample())
        assertNotNull("应能解出格式", f)
        assertEquals("lpcm", f!!.codec)
        assertEquals(48_000, f.sampleRate)
        assertEquals(2, f.channels)
        assertEquals(16, f.bitsPerChannel)
        assertEquals("每采样帧 4 字节", 4, f.bytesPerFrame)
        assertEquals("lpcm 48000Hz/16bit/2ch", f.toString())
    }

    @Test
    fun `4096 字节约等于 21 毫秒`() {
        val f = AudioParser.format(sample())!!
        val pcm = AudioParser.pcm(sample())!!
        val frames = pcm.size / f.bytesPerFrame
        val ms = frames * 1000.0 / f.sampleRate
        assertTrue("时长应在 20~23ms，实际 $ms", ms in 20.0..23.0)
    }

    @Test
    fun `样本数与时间戳可读`() {
        val p = sample()
        assertEquals("nsmp", 1024, AudioParser.sampleCount(p))
        val pts = AudioParser.ptsSeconds(p)
        assertTrue("PTS 应非负，实际 $pts", pts >= 0.0)
    }

    @Test
    fun `非音频报文安全返回 null`() {
        assertNull(AudioParser.pcm(ByteArray(0)))
        assertNull(AudioParser.pcm(ByteArray(16)))
        assertNull("缺少 sdat 段", AudioParser.pcm(ByteArray(200) { 0x41 }))
        assertNull(AudioParser.format(ByteArray(64)))
    }

    /**
     * AFMT 里的 ASBD 与 EAT 帧**不在一个位置**：前者裸放在报文 off 24，
     * 后者藏在 sbuf 的 `fdsc` 段里。这条用例就是防止把两者搞混。
     */
    @Test
    fun `AFMT 报文里的格式能单独读出来`() {
        val raw = javaClass.classLoader!!.getResourceAsStream("ios/afmt-request.bin")!!
            .use { it.readBytes() }
        // fixture 带 4B 长度前缀，而运行时拿到的是去掉前缀的 payload
        val payload = raw.copyOfRange(4, raw.size)
        val f = AudioParser.afmtFormat(payload)
        assertNotNull("AFMT 的 ASBD 应能解析", f)
        assertEquals("lpcm", f!!.codec)
        assertEquals(48_000, f.sampleRate)
        assertEquals(2, f.channels)
        assertEquals(16, f.bitsPerChannel)
        // 顺便证明：用 EAT 那套（找 fdsc）读 AFMT 是读不出来的
        assertNull("AFMT 没有 fdsc 段，format() 应返回 null", AudioParser.format(payload))
    }

    @Test
    fun `截断的 sdat 不会被越界读取`() {
        val p = sample()
        // 只保留到 sdat 头 + 100 字节，声明长度仍是 4104 ⇒ 必须拒绝而不是抛异常
        val cut = p.copyOfRange(0, 136 + 8 + 100)
        assertNull(AudioParser.pcm(cut))
    }
}
