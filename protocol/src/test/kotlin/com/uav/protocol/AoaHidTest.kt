package com.uav.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AOA HID 注入的纯逻辑单测：请求常量、描述符分块、触摸屏描述符与报告。
 *
 * 这里刻意把**描述符长度**和**报告长度**都钉死成具体数字：
 * 报告的字段位宽完全由描述符决定，改动描述符必然要同步改 [HidTouchPanel.report]，
 * 钉死数字能让"改了描述符却忘了改报告"立刻变成编译期/测试期错误，
 * 而不是等真机上出现"坐标错位"这种难查的现象。
 */
class AoaHidTest {

    // ------------------------------------------------------------------ 请求常量

    @Test
    fun `AOA HID 请求号与规范一致`() {
        assertEquals(54, AoaHid.REGISTER_HID)
        assertEquals(55, AoaHid.UNREGISTER_HID)
        assertEquals(56, AoaHid.SET_HID_REPORT_DESC)
        assertEquals(57, AoaHid.SEND_HID_EVENT)
        // OUT | VENDOR | DEVICE，与 51/52/53 同族
        assertEquals(0x40, AoaHid.REQTYPE_OUT_VENDOR_DEVICE)
    }

    @Test
    fun `超时不允许为 0`() {
        // 0 在 USB 语义里是"无限等待"，会挂死线程（本项目红线）
        assertTrue(AoaHid.DEFAULT_TIMEOUT_MS > 0)
    }

    // ------------------------------------------------------------------ 描述符分块

    @Test
    fun `描述符超过 EP0 包长时按偏移顺序分块`() {
        val desc = ByteArray(70) { it.toByte() }
        val chunks = AoaHid.chunkDescriptor(desc, maxChunk = 64)

        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].offset)
        assertEquals(64, chunks[0].bytes.size)
        assertEquals(64, chunks[1].offset)
        assertEquals(6, chunks[1].bytes.size)

        // 顺序拼接必须还原成原描述符（规范要求"必须顺序发送"）
        val joined = chunks.flatMap { it.bytes.toList() }.toByteArray()
        assertArrayEquals(desc, joined)
    }

    @Test
    fun `描述符刚好等于包长时只有一块`() {
        val chunks = AoaHid.chunkDescriptor(ByteArray(64), maxChunk = 64)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].offset)
    }

    @Test
    fun `包长大于描述符时仍只有一块且偏移为 0`() {
        val chunks = AoaHid.chunkDescriptor(ByteArray(10), maxChunk = 4096)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].offset)
        assertEquals(10, chunks[0].bytes.size)
    }

    @Test
    fun `空描述符不产生分块`() {
        assertTrue(AoaHid.chunkDescriptor(ByteArray(0), maxChunk = 64).isEmpty())
    }

    @Test
    fun `非法包长直接拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            AoaHid.chunkDescriptor(ByteArray(10), maxChunk = 0)
        }
    }

    // ------------------------------------------------------------------ 触摸屏描述符

    @Test
    fun `触摸屏描述符长度与首尾结构固定`() {
        val d = HidTouchPanel.descriptor(1224, 2700)
        assertEquals("描述符长度变了就要同步检查 report 的字段位宽", 76, d.size)
        assertEquals(HidTouchPanel.DESCRIPTOR_SIZE, d.size)

        // 宽高只改取值、不改长度：换一组尺寸长度必须一致
        assertEquals(76, HidTouchPanel.descriptor(1080, 1920).size)

        // Usage Page (Digitizers) / Usage (Touch Screen) / Collection (Application)
        assertEquals(0x05.toByte(), d[0])
        assertEquals(0x0D.toByte(), d[1])
        assertEquals(0x09.toByte(), d[2])
        assertEquals(
            "Usage 必须是 Touch Screen(0x04)；写成 Pen 会被识别成鼠标指针",
            0x04.toByte(), d[3],
        )
        assertEquals(0xA1.toByte(), d[4])
        assertEquals(0x01.toByte(), d[5])

        // 两个 End Collection 收尾
        assertEquals(0xC0.toByte(), d[d.size - 2])
        assertEquals(0xC0.toByte(), d[d.size - 1])
    }

    @Test
    fun `描述符里的逻辑上限就是传入的宽高`() {
        val w = 1224
        val h = 2700
        val d = HidTouchPanel.descriptor(w, h)

        // X 的逻辑上限紧跟 Usage(X) 之后的 0x26
        val xIdx = indexOf(d, byteArrayOf(0x09, 0x30, 0x15, 0x00, 0x26.toByte()))
        assertTrue("没找到 Usage(X) 段", xIdx >= 0)
        assertEquals(w, u16At(d, xIdx + 5))

        // Y 的逻辑上限紧跟 Usage(Y) 之后的 0x26
        val yIdx = indexOf(d, byteArrayOf(0x09, 0x31, 0x26.toByte()))
        assertTrue("没找到 Usage(Y) 段", yIdx >= 0)
        assertEquals(h, u16At(d, yIdx + 3))
    }

    @Test
    fun `宽高超出 uint16 直接拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { HidTouchPanel.descriptor(0, 2700) }
        assertThrows(IllegalArgumentException::class.java) { HidTouchPanel.descriptor(1224, 70000) }
    }

    // ------------------------------------------------------------------ 触摸报告

    @Test
    fun `报告长度固定且与描述符同步`() {
        assertEquals(7, HidTouchPanel.REPORT_SIZE)
        assertEquals(HidTouchPanel.REPORT_SIZE, HidTouchPanel.report(0, 0, false).size)
    }

    @Test
    fun `按下与抬起只有触点数与 TipSwitch 不同`() {
        val down = HidTouchPanel.report(100, 200, tipDown = true)
        val up = HidTouchPanel.report(100, 200, tipDown = false)

        assertEquals(1, down[0].toInt())   // Contact Count
        assertEquals(1, down[2].toInt() and 0x01) // Tip Switch
        assertEquals(0, up[0].toInt())
        assertEquals(0, up[2].toInt() and 0x01)

        // 触点编号固定 0；抬起也要带坐标（稳定 Contact ID 的释放记录）
        assertEquals(0, down[1].toInt())
        assertEquals(0, up[1].toInt())
        assertArrayEquals(down.copyOfRange(3, 5), up.copyOfRange(3, 5))
    }

    @Test
    fun `坐标按小端 uint16 编码`() {
        val r = HidTouchPanel.report(0x1234, 0x00FF, tipDown = true)
        assertEquals(0x34, r[3].toInt() and 0xFF)
        assertEquals(0x12, r[4].toInt() and 0xFF)
        assertEquals(0xFF, r[5].toInt() and 0xFF)
        assertEquals(0x00, r[6].toInt() and 0xFF)
    }

    @Test
    fun `坐标超范围被夹紧而不是溢出`() {
        val r = HidTouchPanel.report(-5, 999_999, tipDown = true)
        assertEquals(0, u16At(r, 3))
        assertEquals(0xFFFF, u16At(r, 5))
    }

    // ------------------------------------------------------------------ 小工具

    private fun u16At(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
