package com.uav.protocol.ios

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用手机关抓的**真机样本**钉死我们发出去的字节。
 *
 * ## 这个测试为什么存在
 *
 * 2026-10-18 为了"为什么收不到音频"折腾了好几个小时：对着四份开源实现逐项对照、
 * 抓包、反复调参数。最后真正的原因只有两处 fourcc 写错：
 *
 * 1. HPA1 里的 codec 写成裸 `b"lpcm"`（线上应该是反转后的 `6d 63 70 6c`）
 *    ⇒ 在设备看来我们的音频格式叫 `mcpl` ⇒ 它**连 AFMT 都不发**。
 * 2. `OG` 的 subtype 名写成 `"og! "`（线上是 `20 21 6f 67`，读回来是 `"go! "`）
 *    ⇒ `matches()` 永远为假 ⇒ 应答分支**一次都没触发过**。
 *
 * 这两个错误都**不会报错、不会崩**，只表现为"设备静默"。而它们本来只要
 * 「把要发的包和真机样本比一遍」就能当场发现 —— 所以把比对固化成测试。
 *
 * ★ 结论：**凡手写 fourcc 一律走 [QtPacket.fcc]，禁止字面量**；
 *   新增/修改任何出站报文，先在这里加一条与样本的逐字节断言。
 */
class QtPacketFixtureTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("ios/$name")!!.use { it.readBytes() }

    private fun u64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    /** 报文里的长度前缀 = 整个包长，构造侧必须一致。 */
    private fun assertLengthPrefix(packet: ByteArray) {
        val n = (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8) or
            ((packet[2].toInt() and 0xFF) shl 16) or ((packet[3].toInt() and 0xFF) shl 24)
        assertEquals("长度前缀应等于包长", packet.size, n)
    }

    @Test
    fun `HPA1 与真机样本逐字节一致`() {
        val fx = fixture("hpa1.bin")
        assertLengthPrefix(fx)
        // 包头是 [4B 长度][4B 'asyn'][8B clock][4B 子类型]，clock 从第 8 字节开始
        val clock = u64(fx, 8)
        assertArrayEquals("HPA1 必须与真机样本完全相同", fx, QtPacket.hpa1(clock))
    }

    @Test
    fun `afmt_reply 与真机样本逐字节一致`() {
        val fx = fixture("afmt-reply.bin")
        assertLengthPrefix(fx)
        val correlation = u64(fx, 8)   // [4B 长度][4B 'rply'][8B correlation]
        assertArrayEquals("afmt 应答必须与真机样本完全相同", fx, QtPacket.afmtReply(correlation))
    }

    /**
     * HPA1 里的 codec：线上是 `6d 63 70 6c`（'lpcm' 的反转）。
     * 写裸 ASCII 会变成 `6c 70 63 6d` —— 设备不认这个格式，**连 AFMT 都不发**。
     */
    @Test
    fun `HPA1 的 codec 必须是反转后的 lpcm`() {
        val packet = QtPacket.hpa1(0)
        val coded = byteArrayOf(0x6d, 0x63, 0x70, 0x6c)   // 'mcpl' -> 读回 'lpcm'
        val raw = byteArrayOf(0x6c, 0x70, 0x63, 0x6d)     // 裸 ASCII，错的
        assertTrue("HPA1 里必须出现 'lpcm' 的线上字节", contains(packet, coded))
        assertFalse("HPA1 里不能出现裸 ASCII 'lpcm'", contains(packet, raw))
    }

    /**
     * `OG` 报文：线上字节 `20 21 6f 67`。
     * 按 fourcc 的规则反转后读回来是 **`go! `**（不是 `og! `）。
     * 名字写错的后果是 `QtPacket.matches()` 永远为假，应答分支等于不存在。
     */
    @Test
    fun `OG 的 fourcc 名是 go 感叹号 空格`() {
        val wire = byteArrayOf(0x20, 0x21, 0x6f, 0x67)
        assertArrayEquals("fcc(\"go! \") 必须等于线上字节", wire, QtPacket.fcc("go! "))
        assertArrayEquals(
            "应答匹配用的就是这个名字，写反了就永远不匹配",
            wire,
            QtPacket.fcc("go! ")
        )
        assertFalse("`og! ` 是错的写法", QtPacket.fcc("og! ").contentEquals(wire))
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || hay.size < needle.size) return false
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
