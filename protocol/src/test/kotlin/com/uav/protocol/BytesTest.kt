package com.uav.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 字节序工具自检。
 *
 * 大端这几个函数是本项目**最容易静默写错**的一类代码：位移方向错了不会报错，
 * 只会让 usbmux 的帧长、FEED 的 NAL 长度读出离谱的值（表现为"设备不说话"或"花屏"）。
 * 所以这里既钉住"读写往返"，也钉住**逐字节的线上形态**。
 */
class BytesTest {

    @Test
    fun `大端 32 位写出的是高位在前`() {
        val b = ByteArray(4)
        Bytes.putIntBE(b, 0, 0x01020304)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), b)
    }

    @Test
    fun `小端 32 位写出的是低位在前`() {
        val b = ByteArray(4)
        Bytes.putIntLE(b, 0, 0x01020304)
        assertArrayEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), b)
    }

    @Test
    fun `大端 16 位写出的是高位在前`() {
        val b = ByteArray(2)
        Bytes.putShortBE(b, 0, 0x1234)
        assertArrayEquals(byteArrayOf(0x12, 0x34), b)
    }

    @Test
    fun `大端读回的值与写入一致（含负值高位）`() {
        val b = ByteArray(4)
        Bytes.putIntBE(b, 0, -1)
        assertEquals(-1, Bytes.getIntBE(b, 0))

        Bytes.putIntBE(b, 0, 0x7FFFFFFF)
        assertEquals(0x7FFFFFFF, Bytes.getIntBE(b, 0))

        Bytes.putIntBE(b, 0, Int.MIN_VALUE)
        assertEquals(Int.MIN_VALUE, Bytes.getIntBE(b, 0))
    }

    @Test
    fun `大端 16 位读回值为无符号语义`() {
        // 0xFFFF 必须读成 65535，不能因为符号位扩成 -1
        val b = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertEquals(65535, Bytes.getShortBE(b, 0))
    }

    @Test
    fun `小端 64 位读回一个真实时间戳`() {
        val v = 0x0000_1234_5678_9ABC_DEFFL
        val b = Bytes.longLE(v)
        assertEquals(8, b.size)
        assertEquals(v, Bytes.getLongLE(b, 0))
        // 小端：最低字节在最前
        assertEquals(0xFF.toByte(), b[0])
    }

    @Test
    fun `大端 32 位能读出 usbmux 帧头里的长度`() {
        // usbmux 头：版本(4) + 类型(4) + 长度(4) + 序号(4)
        val b = byteArrayOf(
            0, 0, 0, 1,             // 版本
            0, 0, 0, 8,             // 类型
            0, 0, 0, 20,            // 长度 = 20
            0, 0, 0, 1,             // 序号
            0, 0, 0, 0,
        )
        assertEquals(1, Bytes.getIntBE(b, 0))
        assertEquals(8, Bytes.getIntBE(b, 4))
        assertEquals(20, Bytes.getIntBE(b, 8))
    }

    @Test
    fun `偏移读写不会动到相邻字节`() {
        val b = ByteArray(8)
        Bytes.putIntBE(b, 4, 0x01020304)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 1, 2, 3, 4), b)
        assertEquals(0x01020304, Bytes.getIntBE(b, 4))
    }

    @Test
    fun `align8 向上取整`() {
        assertEquals(0, Bytes.align8(0))
        assertEquals(8, Bytes.align8(1))
        assertEquals(8, Bytes.align8(8))
        assertEquals(16, Bytes.align8(9))
    }
}
