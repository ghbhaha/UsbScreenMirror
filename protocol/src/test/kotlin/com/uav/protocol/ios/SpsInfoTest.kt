package com.uav.protocol.ios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPS 尺寸解析。
 *
 * 样本全部是 **ffmpeg(libx264) 现生成的**，尺寸已知 ⇒ 是真值而不是"自己编自己解"：
 * ```
 * ffmpeg -f lavfi -i color=black:s=1200x1718 -frames:v 1 \
 *        -c:v libx264 -profile:v baseline -f h264 out.h264
 * ```
 * 选这几组是因为覆盖了三种情况：宽除得尽 / 高要裁 / 宽要裁 / High profile（带 chroma 与 scaling list）。
 */
class SpsInfoTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Annex-B 包装：`00 00 00 01` + SPS NAL。 */
    private fun annexB(spsHex: String): ByteArray =
        hex("00000001") + hex(spsHex)

    @Test
    fun `baseline 1200x1718 —— 高度要从 1728 裁回 1718`() {
        // 1200/16=75 除尽；1718 不是 16 的倍数 ⇒ 宏块高 108(=1728)，crop_bottom=10
        val s = SpsInfo.fromCsd(
            annexB("6742c028d9004b0367e6c044000003000400000300c83c60c920")
        )
        assertEquals(SpsInfo.Size(1200, 1718), s)
    }

    @Test
    fun `baseline 1920x1080 —— 高度要从 1088 裁回 1080`() {
        val s = SpsInfo.fromCsd(
            annexB("6742c028d900780227e5c044000003000400000300c83c60c920")
        )
        assertEquals(SpsInfo.Size(1920, 1080), s)
    }

    @Test
    fun `baseline 1718x1200 —— 宽度要从 1728 裁回 1718`() {
        val s = SpsInfo.fromCsd(
            annexB("6742c028d9006c025f9bc044000003000400000300c83c60c920")
        )
        assertEquals(SpsInfo.Size(1718, 1200), s)
    }

    @Test
    fun `High profile 1280x720 —— 带 chroma_format_idc 与 scaling list`() {
        val s = SpsInfo.fromCsd(
            annexB("6764001facd9405005bb011000000300100000030320f1831960")
        )
        assertEquals(SpsInfo.Size(1280, 720), s)
    }

    @Test
    fun `High profile 720x1280 竖屏`() {
        val s = SpsInfo.fromCsd(
            annexB("6764001facd940b40a1b011000000300100000030320f1831960")
        )
        assertEquals(SpsInfo.Size(720, 1280), s)
    }

    @Test
    fun `带 PPS 的完整 csd 也能找到 SPS`() {
        // 真机 csd 形如 [SPS][PPS]（28B），SPS 不是第一个 NAL 时也要能找到
        val pps = hex("68ebe3cb22c0")
        val csd = hex("00000001") + hex("6742c028d9004b0367e6c044000003000400000300c83c60c920") +
            hex("00000001") + pps
        assertEquals(SpsInfo.Size(1200, 1718), SpsInfo.fromCsd(csd))
    }

    @Test
    fun `空数据与非 SPS 不抛异常`() {
        assertNull(SpsInfo.fromCsd(ByteArray(0)))
        assertNull(SpsInfo.fromCsd(hex("0000000168ebe3cb22c0")))   // 只有 PPS
        assertNull(SpsInfo.fromSpsNal(hex("68ebe3cb22c0")))
        assertNull(SpsInfo.fromSpsNal(hex("67")))                   // 截断
    }

    @Test
    fun `按真尺寸做信箱：16 比 9 的宿主屏上 1200x1718 只占中间一条`() {
        // 回归：修复前视图拿到 1920x1080 ⇒ 铺满整屏 ⇒ 画面被横向拉宽；
        //       拿到真尺寸后应得 754x1080（左右留黑边）。
        val fitted = com.uav.protocol.media.Scale.fitInside(1920, 1080, 1200, 1718)
        assertEquals(1080, fitted[1])
        assertTrue("宽度应在 750~760 之间，实际 ${fitted[0]}", fitted[0] in 750..760)
    }
}
