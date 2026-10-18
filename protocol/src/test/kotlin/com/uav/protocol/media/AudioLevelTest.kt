package com.uav.protocol.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单测：PCM 电平计算（计划 10.13）。
 *
 * 这组测试的意义不只是"公式对"：它是**内录是否真的录到声音**这件事的判据本身。
 * 如果归一化写错（例如按 8 bit 归一化），满幅信号会被算成 -48 dBFS，
 * 于是"静音"和"有声音"就分不开了 —— 真机排错会直接走偏。
 */
class AudioLevelTest {

    /** 把 Int 样本转成小端 16 bit PCM。 */
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `全零是静音下限而不是 NaN`() {
        val db = AudioLevel.rmsDbfs(pcm(0, 0, 0, 0))
        assertEquals(AudioLevel.SILENCE_DBFS, db, 0.001f)
        assertFalse("不能是 NaN", db.isNaN())
        assertTrue("不能是 -Inf", db.isFinite())
    }

    @Test
    fun `空输入返回静音下限`() {
        assertEquals(AudioLevel.SILENCE_DBFS, AudioLevel.rmsDbfs(ByteArray(0)), 0.001f)
        // 长度不足以构成一个样本
        assertEquals(AudioLevel.SILENCE_DBFS, AudioLevel.rmsDbfs(ByteArray(1)), 0.001f)
    }

    @Test
    fun `满幅方波是 0 dBFS 量级`() {
        val db = AudioLevel.rmsDbfs(pcm(32767, -32768, 32767, -32768))
        assertEquals("满幅应贴近 0 dBFS", 0f, db, 0.01f)
    }

    @Test
    fun `半幅信号是 -6 dBFS`() {
        // 幅度减半 -> 20*log10(0.5) ≈ -6.02 dB
        val db = AudioLevel.rmsDbfs(pcm(16384, -16384, 16384, -16384))
        assertEquals(-6.02f, db, 0.05f)
    }

    @Test
    fun `归一化基准写错会把满幅算成 -48dB`() {
        // 反向锁定：满幅 16 bit 信号**绝不能**被算成 -48 dBFS。
        // 这正是"归一化基准写错"的典型症状，写死在这里防止回归。
        val db = AudioLevel.rmsDbfs(pcm(32767, 32767, 32767, 32767))
        assertTrue("满幅被算成 $db dBFS —— 归一化基准错了", db > -1f)
    }

    @Test
    fun `峰值与 RMS 能把瞬态和底噪区分开`() {
        // 16 个样本里只有 1 个满幅脉冲：峰值到顶，RMS 低约 12 dB（= 20*log10(1/4)）
        // ⚠️ 样本数要够多才有区分度 —— 4 个样本里放 1 个满幅只能拉开 6 dB，
        //    那样"峰值高"和"整体响"就分不开了。
        val burst = pcm(
            32767, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
        )
        assertEquals("峰值应到满幅", 0f, AudioLevel.peakDbfs(burst), 0.01f)
        assertEquals("RMS 应低约 12 dB", -12.04f, AudioLevel.rmsDbfs(burst), 0.1f)
    }

    @Test
    fun `安静但非静音的底噪不会被判成静音`() {
        // 约 -40 dBFS：幅度 32768 * 10^(-40/20) ≈ 328
        val noise = pcm(328, -328, 328, -328)
        val db = AudioLevel.rmsDbfs(noise)
        assertEquals(-40f, db, 0.5f)
        assertFalse(AudioLevel.isSilent(noise))
    }

    @Test
    fun `阈值可调`() {
        val quiet = pcm(328, -328)
        assertTrue(AudioLevel.isSilent(quiet, thresholdDbfs = -30f))
        assertFalse(AudioLevel.isSilent(quiet, thresholdDbfs = -50f))
    }

    @Test
    fun `支持带偏移的读取`() {
        val buf = ByteArray(8)
        val body = pcm(32767, 32767)
        System.arraycopy(body, 0, buf, 4, body.size)
        assertEquals("只看第 2 个样本起的 4 字节", 0f, AudioLevel.rmsDbfs(buf, 4, 4), 0.01f)
    }
}
