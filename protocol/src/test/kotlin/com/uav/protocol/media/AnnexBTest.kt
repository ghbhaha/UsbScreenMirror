package com.uav.protocol.media

import com.uav.protocol.Hex
import com.uav.protocol.bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Annex-B 起始码切分 —— 只在"整体作为 csd-0 注入失败"时启用的回退路径（计划 4.5）。
 * 放在 `:protocol` 才能这样纯 JVM 验证。
 */
class AnnexBTest {

    private val start4 = bytes(0, 0, 0, 1)
    private val start3 = bytes(0, 0, 1)

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var p = 0
        for (x in parts) {
            System.arraycopy(x, 0, out, p, x.size)
            p += x.size
        }
        return out
    }

    @Test
    fun `4 字节起始码切出两段且不含起始码`() {
        val sps = bytes(0x67, 0x42, 0x00, 0x1E)
        val pps = bytes(0x68, 0xCE, 0x38, 0x80)
        val stream = concat(start4, sps, start4, pps)

        val nals = AnnexB.split(stream)

        assertEquals(2, nals.size)
        assertArrayEquals(sps, nals[0])
        assertArrayEquals(pps, nals[1])
    }

    @Test
    fun `3 字节起始码同样支持`() {
        val a = bytes(0x11, 0x22)
        val b = bytes(0x33, 0x44, 0x55)
        val nals = AnnexB.split(concat(start3, a, start3, b))

        assertEquals(2, nals.size)
        assertArrayEquals(a, nals[0])
        assertArrayEquals(b, nals[1])
    }

    @Test
    fun `混合长度的起始码也能正确切分`() {
        val a = bytes(0x67)
        val b = bytes(0x68)
        val c = bytes(0x65)
        val nals = AnnexB.split(concat(start4, a, start3, b, start4, c))

        assertEquals(3, nals.size)
        assertArrayEquals(a, nals[0])
        assertArrayEquals(b, nals[1])
        assertArrayEquals(c, nals[2])
    }

    @Test
    fun `切分结果中绝不残留起始码字节`() {
        val sps = bytes(0x67, 0x42, 0x00, 0x1E, 0x95)
        val pps = bytes(0x68, 0xCE, 0x38, 0x80)
        val nals = AnnexB.split(concat(start4, sps, start4, pps))

        for (nal in nals) {
            val hasStart = nal.size >= 3 && (
                (nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) ||
                    (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                        nal[2] == 0.toByte() && nal[3] == 1.toByte())
                )
            assertTrue("残余起始码: ${Hex.toHexString(nal)}", !hasStart)
        }
    }

    @Test
    fun `无起始码时原样返回单段`() {
        val raw = bytes(1, 2, 3, 4, 5)
        val nals = AnnexB.split(raw)
        assertEquals(1, nals.size)
        assertArrayEquals(raw, nals[0])
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(AnnexB.split(ByteArray(0)).isEmpty())
    }

    @Test
    fun `切分与拼接往返一致`() {
        val sps = bytes(0x67, 0x01, 0x02, 0x03)
        val pps = bytes(0x68, 0x04, 0x05)
        val original = concat(start4, sps, start4, pps)

        val roundTrip = AnnexB.join(AnnexB.split(original))

        // join 统一用 4 字节起始码，故原样比较仅当输入本来就是 4 字节起始码
        assertArrayEquals(original, roundTrip)
    }

    @Test
    fun `单段没有尾部起始码也能切出`() {
        val only = bytes(0x67, 0xAA, 0xBB)
        val nals = AnnexB.split(concat(start4, only))
        assertEquals(1, nals.size)
        assertArrayEquals(only, nals[0])
    }

    @Test
    fun `带偏移切分不影响结果`() {
        val sps = bytes(0x67, 0x42)
        val pps = bytes(0x68, 0xCE)
        val body = concat(start4, sps, start4, pps)
        val padded = concat(bytes(0x77, 0x77), body, bytes(0x77))

        val nals = AnnexB.split(padded, off = 2, len = body.size)

        assertEquals(2, nals.size)
        assertArrayEquals(sps, nals[0])
        assertArrayEquals(pps, nals[1])
    }
}
