package com.uav.protocol.ios

import com.uav.protocol.Bytes
import com.uav.protocol.media.AnnexB

/**
 * QuickTime `FEED` 样本解析（**纯函数**）。
 *
 * 实测结构（2026-10-18，552 个包全部命中）：
 * ```
 * FEED 报文体 = [4B 总长]['sbuf'][…80B 头部…]['tads'][4B 大端 NAL 长度][NAL]…
 *              … [4B 长度]['datv'] [AVCDecoderConfigurationRecord]
 * ```
 * - NAL 类型只有 1/5/6（P/IDR/SEI），**没有 SPS/PPS**；
 * - SPS/PPS 藏在尾部字典里的 avcC 记录：`01 64 00 2a ff e1 00 10 <SPS> 01 00 04 <PPS>`。
 *
 * ## 为什么在 `:protocol` 而不是 `app-host/ios`
 *
 * 它一个 `android.*` 都不碰，却原来留在 Android 模块里 —— 而 `app-host` 没有 test 源集，
 * 等于这段"设备换固件就静默花屏"的解析逻辑**从没被任何单测覆盖过**。
 * 下沉后由 `SampleParserTest` 用构造样本钉住。
 *
 * ⚠️ 两处字节序/位域陷阱：
 *  - NAL 长度与 avcC 里的长度字段是**大端**（用 [Bytes.getIntBE] / [Bytes.getShortBE]）；
 *  - `numSps` 在**一个字节的低 5 位**（高 3 位保留），别整字节当计数。
 */
object SampleParser {

    private val TADS = byteArrayOf(0x74, 0x61, 0x64, 0x73)   // 'tads' 的线上字节
    private val DATV = byteArrayOf(0x76, 0x74, 0x61, 0x64)   // 'datv' 的线上字节 = 'vtad'

    /** 抽出一帧（Annex-B：每个 NAL 前加 `00 00 00 01`）。找不到 NAL 返回 null。 */
    fun frame(payload: ByteArray): ByteArray? {
        if (payload.size < 24) return null
        val tads = indexOf(payload, TADS, 16)
        if (tads < 0) return null

        var i = tads + 4
        val nals = ArrayList<ByteArray>(4)
        while (i + 4 <= payload.size) {
            val n = Bytes.getIntBE(payload, i)
            // ⚠️ 必须写成 `n > size - i - 4` 而不是 `i + 4 + n > size`：
            //   后者在 n 接近 Int.MAX（畸形报文）时**溢出成负数**，越界检查会被绕过，
            //   紧接着的 copyOfRange 抛 IllegalArgumentException 把宿主打崩。
            //   这是下沉重写时由单测发现的既有漏洞。
            if (n <= 0 || n > payload.size - i - 4) break
            nals.add(payload.copyOfRange(i + 4, i + 4 + n))
            i += 4 + n
        }
        return if (nals.isEmpty()) null else AnnexB.join(nals)
    }

    /**
     * 从报文里找 avcC 并转成 Annex-B 的 SPS+PPS。
     *
     * 优先在 `datv` 之后找（样本尾部的格式描述），找不到就全报文扫
     * （avcC 的特征够独特：`01 <profile 42/4D/64/F4> xx xx 0xFF`）。
     */
    fun csd(payload: ByteArray): ByteArray? {
        val at = indexOf(payload, DATV, 0)
        if (at >= 0) parseAvcC(payload, at + 4)?.let { return it }
        return parseAvcC(payload, 0)
    }

    private fun parseAvcC(b: ByteArray, from: Int): ByteArray? {
        var i = from
        while (i <= b.size - 8) {
            if ((b[i].toInt() and 0xFF) != 0x01) { i++; continue }
            val profile = b[i + 1].toInt() and 0xFF
            if (profile != 0x42 && profile != 0x4D && profile != 0x64 && profile != 0xF4) { i++; continue }
            if ((b[i + 4].toInt() and 0xFF) and 0xFC != 0xFC) { i++; continue }

            // 6 字节头（version..lengthSizeMinusOne）之后是 SPS/PPS 序列
            readSpsPps(b, i + 5)?.let { return it }
            i++
        }
        return null
    }

    /**
     * 从 `numSps` 那一字节开始读完整的 SPS+PPS，返回 Annex-B；结构不合法返回 null。
     *
     * 布局：`[numSps(低5位)][numSps×[2B 长度][NAL]][numPps(整字节)][numPps×[2B 长度][NAL]]`
     */
    private fun readSpsPps(b: ByteArray, start: Int): ByteArray? {
        if (start >= b.size) return null
        val numSps = b[start].toInt() and 0x1F
        if (numSps !in 1..2) return null

        val nals = ArrayList<ByteArray>(4)
        var j = readNals(b, start + 1, numSps, nals)
        if (j < 0 || j >= b.size) return null

        val numPps = b[j].toInt() and 0xFF
        if (numPps !in 1..2) return null
        j = readNals(b, j + 1, numPps, nals)
        if (j < 0) return null

        return AnnexB.join(nals)
    }

    /** 读 `count` 段 `[2B 大端长度][NAL]` 追加到 [out]，返回读完后游标；越界返回 -1。 */
    private fun readNals(b: ByteArray, start: Int, count: Int, out: MutableList<ByteArray>): Int {
        var j = start
        for (k in 0 until count) {
            if (j + 2 > b.size) return -1
            val len = Bytes.getShortBE(b, j)
            j += 2
            if (len == 0 || j + len > b.size) return -1
            out.add(b.copyOfRange(j, j + len))
            j += len
        }
        return j
    }

    fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (hay.size < needle.size) return -1
        outer@ for (i in from..(hay.size - needle.size)) {
            for (k in needle.indices) if (hay[i + k] != needle[k]) continue@outer
            return i
        }
        return -1
    }
}
