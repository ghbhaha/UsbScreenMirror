package com.uav.protocol.ios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 停止序列（`HPA0` / `HPD0`）的构造。
 *
 * 依据：`RayrenSX/iPhoneMirror` `docs/PROTOCOL.md` §4 结束段
 * 「停止顺序是 HPA0、HPD0、处理 STOP/RPLY 与 RELS，然后释放接口」。
 *
 * ★ 这里测的每一条都是**"结构对不对"**，因为停止报文不上真机也能验：
 *   长度字段自洽、fourcc 反转正确、子类型名正确。
 */
class QtStopSequenceTest {

    private fun subTypeOf(packet: ByteArray): String =
        String(packet.copyOfRange(16, 20).reversedArray(), Charsets.US_ASCII)

    // ---- HPD0 ----

    @Test
    fun `hpd0 外层是 asyn 且子类型为 hpd0`() {
        val p = QtPacket.hpd0()
        assertEquals("asyn", QtPacket.fccString(p, 4))
        assertEquals("hpd0", subTypeOf(p))
    }

    /**
     * ★ **长度字段必须自洽** —— 这是最容易写错的地方。
     *
     * `[u32 length][magic asyn][u64 clock][4B subtype][body]`，
     * 所以length = 20 + body.size。写错则设备直接丢弃，且**不报错**。
     */
    @Test
    fun `hpd0 长度字段与实际字节数一致`() {
        val p = QtPacket.hpd0()
        assertEquals(p.size, QtPacket.u32At(p, 0))
    }

    /**
     * `hpd0` 与 `hpd1` **结构完全对称**，只差子类型那 4 字节。
     *
     * ⚠️ 反转后的 `hpd1`= `31 64 70 68`、`hpd0` = `30 64 70 68`
     *   —— 只有**第 1 个字节**不同（'1' ↔ '0'）！所以差异是 **2 个**不是 4 个。
     *   （这点当初写断言时想错了：fourcc 的另外三个字母 `h`/`p`/`d` 反转后位置相同。）
     */
    @Test
    fun `hpd0 与 hpd1 结构对称只差子类型`() {
        val hpd1 = QtPacket.hpd1(1640, 2360)
        val hpd0 = QtPacket.hpd0(1640, 2360)
        assertEquals("长度必须相同", hpd1.size, hpd0.size)
        assertEquals("asyn", QtPacket.fccString(hpd0, 4))
        // 反转后只有 '1'→'0' 这一处不同
        assertEquals(listOf(0x30, 0x64, 0x70, 0x68), hpd0.copyOfRange(16, 20).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x31, 0x64, 0x70, 0x68), hpd1.copyOfRange(16, 20).map { it.toInt() and 0xFF })
    }

    // ---- HPA0 ----

    @Test
    fun `hpa0 外层是 asyn 且子类型为 hpa0`() {
        val p = QtPacket.hpa0(0L)
        assertEquals("asyn", QtPacket.fccString(p, 4))
        assertEquals("hpa0", subTypeOf(p))
        assertEquals(p.size, QtPacket.u32At(p, 0))
    }

    @Test
    fun `hpa0 带设备音频时钟`() {
        val p = QtPacket.hpa0(123456789L)
        assertEquals(123456789L, QtPacket.u64At(p, 8))
    }

    /**
     * ★ `hpa0` **不能带 `formats`**。
     *
     * 停止是"关"，不是"重新协商"。若把 `hpa1` 的 body 原样搬过来，
     * 设备可能当成一次新的音频协商而继续推 `EAT!`——
     * 表现为"点了停止，音频还在来"，而且很难查。
     */
    @Test
    fun `hpa0 不带协商字段`() {
        val raw = String(QtPacket.hpa0(0L), Charsets.ISO_8859_1)
        assertTrue("不应出现 formats", !raw.contains("formats"))
        assertTrue("不应出现 ASBD descriptor", !raw.contains("lpcm"))
    }

    // ---- fourcc 反转（老坑，不能退化）----

    @Test
    fun `fourcc 线上是反序的`() {
        val p = QtPacket.hpa0(0L)
        // "hpa0" 反转后应为 30 61 70 68（'0','a','p','h'）
        assertEquals(
            listOf(0x30, 0x61, 0x70, 0x68),
            p.copyOfRange(16, 20).map { it.toInt() and 0xFF },
        )
    }

    // ---- 默认尺寸同源（防漂移）----

    @Test
    fun `hpd1 与 hpd0 的默认尺寸来自同一常量`() {
        assertEquals(
            QtPacket.hpd1().size,
            QtPacket.hpd0(QtPacket.DEFAULT_DISPLAY_WIDTH, QtPacket.DEFAULT_DISPLAY_HEIGHT).size,
        )
        // 默认调用不带参数也必须结构正确（不能因为默认参数而少一段 body）
        assertEquals(QtPacket.hpd0().size, QtPacket.u32At(QtPacket.hpd0(), 0))
    }
}