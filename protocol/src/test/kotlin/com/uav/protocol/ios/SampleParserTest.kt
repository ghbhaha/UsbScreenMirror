package com.uav.protocol.ios

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEED 样本解析自检。
 *
 * 这段逻辑此前留在 `app-host`（没有 test 源集），等于**从没被验证过** ——
 * 而它是"设备换固件 / SPS 变了"时唯一能看到差异的地方，症状又是静默花屏。
 *
 * 这里用**按实测结构构造**的样本（真机 FEED 报文无法入库：体积大且含屏幕内容），
 * 但把三处关键约定都钉住：
 *  1. `tads` 之后的 NAL 长度是**大端**；
 *  2. NAL 循环遇到 `datv` 要停（`vtad` 被当成超长长度自然 break）；
 *  3. avcC 里 `numSps` 在**低 5 位**，SPS/PPS 的长度也是大端。
 */
class SampleParserTest {

    /** 一段 16B 头部 + `tads` + 两个 NAL + `datv` + avcC 的最小 FEED 样本。 */
    private fun feed(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(ByteArray(16))                                   // 头部
        out.write(byteArrayOf(0x74, 0x61, 0x64, 0x73))             // 'tads'
        out.write(byteArrayOf(0, 0, 0, 4))                         // NAL#1 长度 = 4
        out.write(byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte(), 0x00))             // NAL#1（IDR）
        out.write(byteArrayOf(0, 0, 0, 3))                         // NAL#2 长度 = 3
        out.write(byteArrayOf(0x41, 0x9A.toByte(), 0x20))                   // NAL#2（P）
        out.write(byteArrayOf(0x76, 0x74, 0x61, 0x64))             // 'datv' 的线上字节
        // avcC：01 64 00 2a ff e1 [SPS] 01 [PPS]
        out.write(byteArrayOf(0x01, 0x64, 0x00, 0x2A, 0xFF.toByte(), 0xE1.toByte()))
        out.write(byteArrayOf(0x00, 0x04))                         // SPS 长度
        out.write(byteArrayOf(0x67, 0x64, 0x00, 0x2A))             // SPS
        out.write(byteArrayOf(0x01))                               // numPps
        out.write(byteArrayOf(0x00, 0x02))                         // PPS 长度
        out.write(byteArrayOf(0x68, 0xEE.toByte()))                // PPS
        return out.toByteArray()
    }

    @Test
    fun `两个 NAL 被拆出来并各加 4 字节起始码`() {
        val f = SampleParser.frame(feed())!!
        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 1, 0x65, 0x88.toByte(), 0x80.toByte(), 0x00,
                0, 0, 0, 1, 0x41, 0x9A.toByte(), 0x20,
            ),
            f,
        )
    }

    @Test
    fun `收尾的 avcC 被当成 SPS 与 PPS`() {
        val csd = SampleParser.csd(feed())!!
        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 1, 0x67, 0x64, 0x00, 0x2A,
                0, 0, 0, 1, 0x68, 0xEE.toByte(),
            ),
            csd,
        )
    }

    @Test
    fun `帧里绝不混进 SPS 或 PPS`() {
        val p = feed()
        val f = SampleParser.frame(p)!!
        // 帧必须是 4+4 与 4+3 两段，绝不能把尾部的参数集一起交出去
        org.junit.Assert.assertEquals(15, f.size)
        org.junit.Assert.assertEquals(
            -1,
            SampleParser.indexOf(f, byteArrayOf(0x67, 0x64, 0x00, 0x2A), 0),
        )
        org.junit.Assert.assertEquals(
            -1,
            SampleParser.indexOf(f, byteArrayOf(0x68, 0xEE.toByte()), 0),
        )
    }

    @Test
    fun `没有 tads 的报文返回 null`() {
        assertNull(SampleParser.frame(ByteArray(64)))
    }

    @Test
    fun `太短的报文返回 null`() {
        assertNull(SampleParser.frame(ByteArray(8)))
    }

    @Test
    fun `只有 tads 没有 NAL 时返回 null`() {
        val out = java.io.ByteArrayOutputStream()
        out.write(ByteArray(16))
        out.write(byteArrayOf(0x74, 0x61, 0x64, 0x73))
        out.write(ByteArray(8))
        assertNull(SampleParser.frame(out.toByteArray()))
    }

    @Test
    fun `NAL 长度越界时安全停止而不是抛异常`() {
        val out = java.io.ByteArrayOutputStream()
        out.write(ByteArray(16))
        out.write(byteArrayOf(0x74, 0x61, 0x64, 0x73))
        out.write(byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))  // 声称 2GB
        out.write(byteArrayOf(0x65, 0x01))
        assertNull(SampleParser.frame(out.toByteArray()))
    }

    @Test
    fun `没有 avcC 的报文没有 CSD`() {
        assertNull(SampleParser.csd(ByteArray(128)))
    }

    @Test
    fun `numSps 只取低 5 位`() {
        // 高 3 位是保留位（实测常为 111），整字节读会得到 225 而非法
        val b = feed()
        val at = SampleParser.indexOf(b, byteArrayOf(0x76, 0x74, 0x61, 0x64), 0) + 4
        val numSpsByte = b[at + 5].toInt() and 0xFF
        assertTrue("构造样本里 numSps 字节应带保留位", numSpsByte > 0x1F)
        assertTrue("低 5 位应为 1", (numSpsByte and 0x1F) == 1)
    }
}
