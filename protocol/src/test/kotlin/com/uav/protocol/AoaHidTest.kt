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

    /** 造一份"指定槽按下/其余空闲"的快照。 */
    private fun slots(vararg down: Pair<Int, Pair<Int, Int>>): List<TouchSlot> {
        val map = down.toMap()
        return (0 until HidTouchPanel.MAX_FINGERS).map { i ->
            val p = map[i]
            TouchSlot(i, p?.first ?: 0, p?.second ?: 0, p != null)
        }
    }

    @Test
    fun `触摸屏描述符结构固定`() {
        val d = HidTouchPanel.descriptor(1224, 2700)
        assertEquals("描述符长度变了就要同步检查 report 的字段布局", 334, d.size)
        assertEquals(HidTouchPanel.DESCRIPTOR_SIZE, d.size)

        // 宽高只改取值、不改长度：换一组尺寸长度必须一致
        assertEquals(334, HidTouchPanel.descriptor(1080, 1920).size)

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

        // ★ 每个手指是**独立**的 Logical Collection：`09 22 A1 02` 必须出现 N 次
        val fingerCollection = byteArrayOf(0x09, 0x22, 0xA1.toByte(), 0x02)
        assertEquals(HidTouchPanel.MAX_FINGERS, countOf(d, fingerCollection))

        // End Collection 共 N+1 个（N 个手指 + 1 个应用），最后一个字节就是它
        assertEquals(HidTouchPanel.MAX_FINGERS + 1, d.count { it == 0xC0.toByte() })
        assertEquals(0xC0.toByte(), d[d.size - 1])
    }

    @Test
    fun `触点数放在所有手指之后`() {
        val d = HidTouchPanel.descriptor(1224, 2700)
        val cc = byteArrayOf(0x09, 0x54)                       // Usage (Contact Count)
        val idx = indexOf(d, cc)
        assertTrue("没找到 Contact Count", idx >= 0)
        // 它必须在最后一个手指集合之后（即紧跟的 C0 之后就是集合结尾）
        val lastFinger = lastIndexOf(d, byteArrayOf(0x09, 0x22, 0xA1.toByte(), 0x02))
        assertTrue("Contact Count 必须在手指集合之后（放前面实测多点会失效）", idx > lastFinger)
    }

    @Test
    fun `描述符里的逻辑上限就是传入的宽高`() {
        val w = 1224
        val h = 2700
        val d = HidTouchPanel.descriptor(w, h)

        // X 的逻辑上限紧跟 Usage(X) 之后的 0x26（每个手指集合里各有一份）
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

    // ------------------------------------------------------------------ 多点报告

    /** 第 i 个手指在报告里的起始偏移：1 按下位 + 1 标识 + 2 X + 2 Y。 */
    private fun slotOffset(i: Int) = i * 6

    private fun tipByte(r: ByteArray, i: Int) = r[slotOffset(i)].toInt() and 0x01
    private fun idByte(r: ByteArray, i: Int) = r[slotOffset(i) + 1].toInt() and 0xFF
    private fun xs(r: ByteArray, i: Int) = u16At(r, slotOffset(i) + 2)
    private fun ys(r: ByteArray, i: Int) = u16At(r, slotOffset(i) + 4)
    private fun contactCount(r: ByteArray) = r[r.size - 1].toInt() and 0xFF

    @Test
    fun `报告长度与槽位数一致`() {
        // N ×(按下位+补齐 1 + 标识 1 + X 2 + Y 2) + 触点数 1
        assertEquals(31, HidTouchPanel.REPORT_SIZE)
        assertEquals(HidTouchPanel.REPORT_SIZE, HidTouchPanel.report(slots()).size)
    }

    @Test
    fun `单指按下`() {
        val r = HidTouchPanel.report(slots(0 to (100 to 200)))
        assertEquals(1, tipByte(r, 0))
        assertEquals(0, idByte(r, 0))
        assertEquals(100, xs(r, 0))
        assertEquals(200, ys(r, 0))
        // 其余槽都是 Tip=0
        for (i in 1 until HidTouchPanel.MAX_FINGERS) assertEquals(0, tipByte(r, i))
    }

    @Test
    fun `双指同时按下，两个槽各自带坐标`() {
        val r = HidTouchPanel.report(slots(0 to (100 to 200), 1 to (300 to 400)))
        assertEquals(1, tipByte(r, 0))
        assertEquals(1, tipByte(r, 1))
        assertEquals(100, xs(r, 0)); assertEquals(200, ys(r, 0))
        assertEquals(300, xs(r, 1)); assertEquals(400, ys(r, 1))
        assertEquals(0, tipByte(r, 2))
    }

    @Test
    fun `抬起某指只清它自己的位`() {
        val both = HidTouchPanel.report(slots(0 to (10 to 10), 2 to (20 to 20)))
        val onlyTwo = HidTouchPanel.report(slots(2 to (20 to 20)))
        assertEquals(1, tipByte(both, 0))
        assertEquals(0, tipByte(onlyTwo, 0))          // 槽 0 显式发 Tip=0（不能靠"消失"）
        assertEquals(1, tipByte(onlyTwo, 2))
        // 槽 2 的坐标不受影响
        assertEquals(xs(both, 2), xs(onlyTwo, 2))
    }

    @Test
    fun `Contact Identifier 固定等于槽位下标`() {
        val r = HidTouchPanel.report(slots(3 to (1 to 1)))
        for (i in 0 until HidTouchPanel.MAX_FINGERS) {
            assertEquals("槽 $i 的标识必须是 $i", i, idByte(r, i))
        }
    }

    @Test
    fun `触点数填的是帧内记录数而不是按下的触点数`() {
        // ★ 这是最容易搞错的一条：多点协议按"帧内记录数"重组，未按下的槽也算一条记录
        assertEquals(HidTouchPanel.MAX_FINGERS, contactCount(HidTouchPanel.report(slots())))
        assertEquals(HidTouchPanel.MAX_FINGERS, contactCount(HidTouchPanel.report(slots(0 to (1 to 1)))))
        assertEquals(
            HidTouchPanel.MAX_FINGERS,
            contactCount(HidTouchPanel.report(slots(0 to (1 to 1), 1 to (2 to 2)))),
        )
    }

    @Test
    fun `坐标按小端 uint16 编码`() {
        val r = HidTouchPanel.report(slots(0 to (0x1234 to 0x00FF)))
        assertEquals(0x34, r[2].toInt() and 0xFF)
        assertEquals(0x12, r[3].toInt() and 0xFF)
        assertEquals(0xFF, r[4].toInt() and 0xFF)
        assertEquals(0x00, r[5].toInt() and 0xFF)
    }

    @Test
    fun `坐标超范围被夹紧而不是溢出`() {
        val r = HidTouchPanel.report(slots(0 to (-5 to 999_999)))
        assertEquals(0, xs(r, 0))
        assertEquals(0xFFFF, ys(r, 0))
    }

    @Test
    fun `槽位数不对直接拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            HidTouchPanel.report(listOf(TouchSlot(0, 0, 0, true)))
        }
    }

    // ------------------------------------------------------------------ 小工具

    private fun u16At(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    /** needle 在 hay 里出现的次数。 */
    private fun countOf(hay: ByteArray, needle: ByteArray): Int {
        var n = 0
        var from = 0
        while (true) {
            val i = indexOf(hay.copyOfRange(from, hay.size), needle)
            if (i < 0) return n
            n++
            from += i + 1
        }
    }

    /** needle 在 hay 里最后一次出现的位置。 */
    private fun lastIndexOf(hay: ByteArray, needle: ByteArray): Int {
        var last = -1
        var from = 0
        while (true) {
            val i = indexOf(hay.copyOfRange(from, hay.size), needle)
            if (i < 0) return last
            last = from + i
            from = last + 1
        }
    }

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
