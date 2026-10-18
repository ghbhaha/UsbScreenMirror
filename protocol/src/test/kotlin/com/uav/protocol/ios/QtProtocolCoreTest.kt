package com.uav.protocol.ios

import com.uav.protocol.Bytes
import com.uav.protocol.media.AnnexB
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QuickTime 协议状态机自检 —— **入站报文 → 出站字节**的映射。
 *
 * 这段逻辑以前和 `UsbDeviceConnection` 缠在一起，只能插着 iPad 抓 USB 才能验证；
 * 而它每一步都对应一次真机事故（HPD1×2、SKEW 回 48000、OG 的 `go! `、FEED 尾部 avcC）。
 * 现在可以在 JVM 里把"设备发什么 → 我们回什么"逐字节钉住。
 *
 * 音频用例用的是**真机抓的 `asyn-eat` 样本**（`resources/ios/asyn-eat.bin`）。
 */
class QtProtocolCoreTest {

    private val sent = ArrayList<ByteArray>()          // 队列写（enqueue）
    private val immediate = ArrayList<ByteArray>()     // 同步写（PING）
    private val csds = ArrayList<ByteArray>()
    private val frames = ArrayList<ByteArray>()
    private val audios = ArrayList<Pair<ByteArray, AudioParser.Format>>()
    private val logs = ArrayList<String>()

    private fun core(
        requestAudio: Boolean = true,
        clock: Long = 1_000_000L,
    ): QtProtocolCore = QtProtocolCore(
        enqueue = { sent.add(it) },
        writeNow = { immediate.add(it); it.size },
        log = { logs.add(it) },
        requestAudio = requestAudio,
        startedAtMillis = 0L,
        now = { clock },
        listener = object : QtProtocolCore.Listener {
            override fun onCsd(csd: ByteArray) { csds.add(csd) }
            override fun onFrame(annexB: ByteArray) { frames.add(annexB) }
            override fun onAudio(pcm: ByteArray, format: AudioParser.Format) { audios.add(pcm to format) }
        },
    )

    // ---------------------------------------------------------------- 报文构造

    /** `SYNC/<fourcc>` 报文：`[sync][4B][fourcc][8B corr][8B clock]`（线上 fourcc 已反转）。 */
    private fun sync(fourcc: String, corr: Long, clock: Long = 0L): ByteArray {
        val b = ByteArray(32)
        QtPacket.fcc("sync").copyInto(b, 0)
        QtPacket.fcc(fourcc).copyInto(b, 12)
        Bytes.putLongLE(b, 16, corr)
        Bytes.putLongLE(b, 24, clock)
        return b
    }

    /** FEED 报文：`[asyn][8B][feed]` + 样本体（`tads` 起）。 */
    private fun feed(nals: List<ByteArray>, sps: ByteArray?, pps: ByteArray?): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        body.write(byteArrayOf(0x74, 0x61, 0x64, 0x73))          // 'tads'
        for (nal in nals) {
            body.write(Bytes.intBE(nal.size))
            body.write(nal)
        }
        if (sps != null && pps != null) {
            body.write(byteArrayOf(0x76, 0x74, 0x61, 0x64))      // 'datv'
            body.write(byteArrayOf(0x01, 0x64, 0x00, 0x2A, 0xFF.toByte(), 0xE1.toByte()))
            body.write(Bytes.shortBE(sps.size))
            body.write(sps)
            body.write(byteArrayOf(0x01))
            body.write(Bytes.shortBE(pps.size))
            body.write(pps)
        }
        val head = ByteArray(16)
        QtPacket.fcc("asyn").copyInto(head, 0)
        QtPacket.fcc("feed").copyInto(head, 12)
        return head + body.toByteArray()
    }

    // ---------------------------------------------------------------- 用例

    @Test
    fun `PING 走同步写而不是队列`() {
        val c = core()
        // ⚠️ PING 不是 SYNC 报文：读循环已剥掉 4B 长度前缀，核心收到的是 [ping][u32][u32]
        c.handle(QtPacket.ping().copyOfRange(4, 16))
        assertEquals("PING 必须立即写", 1, immediate.size)
        assertEquals("PING 不能进队列", 0, sent.size)
        assertArrayEquals(QtPacket.ping(), immediate[0])
        assertEquals(1, c.stats.ping)
    }

    @Test
    fun `CWPA 回 HPD1 两次 + clock + HPA1`() {
        val c = core(requestAudio = true)
        c.handle(sync("cwpa", corr = 0x1122, clock = 0xAABB))
        assertEquals("HPD1×2 + clock + HPA1", 4, sent.size)
        assertArrayEquals(QtPacket.hpd1(), sent[0])
        assertArrayEquals(QtPacket.hpd1(), sent[1])
        assertArrayEquals(QtPacket.clockReply(0x1122, 0xAABB + 0x1000), sent[2])
        assertArrayEquals(QtPacket.hpa1(0xAABB), sent[3])
    }

    @Test
    fun `未声明音频时 CWPA 只回三个包`() {
        val c = core(requestAudio = false)
        c.handle(sync("cwpa", corr = 1, clock = 2))
        assertEquals("HPD1×2 + clock", 3, sent.size)
        assertTrue("绝不能发 HPA1", sent.none { it.contentEquals(QtPacket.hpa1(2)) })
    }

    @Test
    fun `CVRP 回 NEED 与 clock`() {
        val c = core()
        c.handle(sync("cvrp", corr = 0x55, clock = 0x99))
        assertEquals(2, sent.size)
        assertArrayEquals(QtPacket.need(0x99), sent[0])
        assertArrayEquals(QtPacket.clockReply(0x55, 0x99 + 0x1000af), sent[1])
    }

    @Test
    fun `AFMT 必须应答`() {
        val c = core()
        c.handle(sync("afmt", corr = 7))
        assertEquals(1, sent.size)
        assertArrayEquals(QtPacket.afmtReply(7), sent[0])
        assertEquals(1, c.stats.afmt)
    }

    @Test
    fun `SKEW 回的是采样率 48000 而不是 0`() {
        val c = core()
        c.handle(sync("skew", corr = 3))
        assertArrayEquals(QtPacket.skewReply(3, 48_000.0), sent[0])
    }

    @Test
    fun `OG 的 fourcc 是 go 感叹号空格`() {
        val c = core()
        // 线上字节 20 21 6F 67 = fcc("go! ")
        c.handle(sync("go! ", corr = 9))
        assertEquals("OG 必须被识别（写成 \"og! \" 会匹配不上）", 1, c.stats.og)
        assertArrayEquals(QtPacket.ogReply(9), sent[0])
    }

    @Test
    fun `time 应答用会话相对时间`() {
        val c = core(clock = 1_500_000L)     // 会话 1.5 秒
        c.handle(sync("time", corr = 4))
        assertArrayEquals(QtPacket.timeReply(4, 1_500_000L * 1_000_000), sent[0])
    }

    @Test
    fun `未知 SYNC 只计数不回应`() {
        val c = core()
        c.handle(sync("zzzz", corr = 1))
        assertEquals(0, sent.size)
        assertEquals(1, c.stats.other)
    }

    @Test
    fun `FEED 同时给出帧与 CSD`() {
        val c = core()
        val nal1 = byteArrayOf(0x65, 0x01, 0x02)
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x2A)
        val pps = byteArrayOf(0x68, 0xEE.toByte())
        // videoClock 还是 0（没收到 CVRP）⇒ FEED 之后不应发 NEED
        c.handle(feed(listOf(nal1), sps, pps))

        assertEquals(1, c.stats.feed)
        assertEquals(1, frames.size)
        assertEquals(0, sent.size)

        assertNotNull(csds.firstOrNull())
        assertArrayEquals(
            AnnexB.join(listOf(sps, pps)),
            csds[0],
        )
    }

    @Test
    fun `收到 CVRP 之后每个 FEED 都追一个 NEED`() {
        val c = core()
        c.handle(sync("cvrp", corr = 1, clock = 0x777))
        sent.clear()
        c.handle(feed(listOf(byteArrayOf(0x41, 0x01)), null, null))
        assertEquals(1, sent.size)
        assertArrayEquals(QtPacket.need(0x777), sent[0])
    }

    @Test
    fun `CSD 内容不变时不重复回调，变了才回调`() {
        val c = core()
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x2A)
        val pps = byteArrayOf(0x68, 0xEE.toByte())
        c.handle(feed(listOf(byteArrayOf(0x65, 0x01)), sps, pps))
        c.handle(feed(listOf(byteArrayOf(0x65, 0x02)), sps, pps))
        assertEquals("同样的 csd 只交一次", 1, csds.size)

        // 转屏：SPS 变了（分辨率不同）
        val sps2 = byteArrayOf(0x67, 0x64, 0x00, 0x1F)
        c.handle(feed(listOf(byteArrayOf(0x65, 0x03)), sps2, pps))
        assertEquals("csd 变了必须重建解码器", 2, csds.size)
    }

    @Test
    fun `没有帧回调的调用方不解析 NAL`() {
        val c = QtProtocolCore(
            enqueue = { sent.add(it) },
            writeNow = { 0 },
            log = {},
            parseFrames = false,
            startedAtMillis = 0L,
            listener = object : QtProtocolCore.Listener {
                override fun onCsd(csd: ByteArray) { csds.add(csd) }
                override fun onFrame(annexB: ByteArray) { frames.add(annexB) }
                override fun onAudio(pcm: ByteArray, format: AudioParser.Format) {}
            },
        )
        c.handle(feed(listOf(byteArrayOf(0x41, 0x01)), null, null))
        assertEquals(1, c.stats.feed)
        assertEquals("parseFrames=false 时不该产出帧", 0, frames.size)
    }

    @Test
    fun `真机 EAT 样本产出 PCM 与格式`() {
        val sample = javaClass.classLoader!!.getResourceAsStream("ios/asyn-eat.bin")!!
            .use { it.readBytes() }
        val c = core()
        c.handle(sample)

        assertEquals(1, c.stats.eat)
        assertEquals(1, audios.size)
        val (pcm, fmt) = audios[0]
        assertEquals("4096B PCM", 4096, pcm.size)
        assertEquals("lpcm", fmt.codec)
        assertEquals(48_000, fmt.sampleRate)
        assertEquals(2, fmt.channels)
        assertEquals(16, fmt.bitsPerChannel)
        assertEquals(4096L, c.stats.audioBytes)
    }

    @Test
    fun `rply 只计数`() {
        val c = core()
        val rply = ByteArray(16).also { QtPacket.fcc("rply").copyInto(it, 0) }
        c.handle(rply)
        assertEquals(0, sent.size)
        assertEquals(1, c.stats.other)
    }

    @Test
    fun `日志里保留 CWPA 的时钟值便于对照真机`() {
        val c = core(requestAudio = true)
        c.handle(sync("cwpa", corr = 1, clock = 499_638_652_832L))
        assertTrue(logs.any { it.contains("CWPA#1") && it.contains("499638652832") })
    }
}
