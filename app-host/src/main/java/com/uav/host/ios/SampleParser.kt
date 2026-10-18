package com.uav.host.ios

/**
 * FEED 样本解析（纯函数，便于离线回放自测）。
 *
 * 实测结构（2026-10-18，552 个包全部命中）：
 * ```
 * FEED 报文体 = [4B 总长]['sbuf'][…80B 头部…]['tads'][4B 大端 NAL 长度][NAL]…
 *              … [4B 长度]['datv'] [AVCDecoderConfigurationRecord]
 * ```
 * - NAL 类型只有 1/5/6（P/IDR/SEI），**没有 SPS/PPS**；
 * - SPS/PPS 藏在尾部字典里的 avcC 记录：`01 64 00 2a ff e1 00 10 <SPS> 01 00 04 <PPS>`。
 */
internal object SampleParser {

    private val TADS = byteArrayOf(0x74, 0x61, 0x64, 0x73)   // 'tads'
    private val DATV = byteArrayOf(0x76, 0x74, 0x61, 0x64)   // 'vtad'

    /** 抽出一帧（Annex-B：每个 NAL 前加 00 00 00 01）。找不到返回 null。 */
    fun frame(payload: ByteArray): ByteArray? {
        if (payload.size < 24) return null
        val tads = indexOf(payload, TADS, 16)
        if (tads < 0) return null
        var i = tads + 4
        val out = java.io.ByteArrayOutputStream()
        var nals = 0
        while (i + 4 <= payload.size) {
            val n = be32(payload, i)
            if (n <= 0 || i + 4 + n > payload.size) break
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(payload, i + 4, n)
            i += 4 + n
            nals++
        }
        return if (nals == 0) null else out.toByteArray()
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

            var j = i + 5
            val numSps = b[j].toInt() and 0x1F
            if (numSps !in 1..2) { i++; continue }
            j++
            val out = java.io.ByteArrayOutputStream()
            var ok = true
            for (k in 0 until numSps) {
                if (j + 2 > b.size) { ok = false; break }
                val len = ((b[j].toInt() and 0xFF) shl 8) or (b[j + 1].toInt() and 0xFF)
                j += 2
                if (len == 0 || j + len > b.size) { ok = false; break }
                out.write(byteArrayOf(0, 0, 0, 1)); out.write(b, j, len); j += len
            }
            if (!ok || j >= b.size) { i++; continue }
            val numPps = b[j].toInt() and 0xFF
            if (numPps !in 1..2) { i++; continue }
            j++
            for (k in 0 until numPps) {
                if (j + 2 > b.size) { ok = false; break }
                val len = ((b[j].toInt() and 0xFF) shl 8) or (b[j + 1].toInt() and 0xFF)
                j += 2
                if (len == 0 || j + len > b.size) { ok = false; break }
                out.write(byteArrayOf(0, 0, 0, 1)); out.write(b, j, len); j += len
            }
            if (ok) return out.toByteArray()
            i++
        }
        return null
    }

    internal fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (hay.size < needle.size) return -1
        outer@ for (i in from..(hay.size - needle.size)) {
            for (k in needle.indices) if (hay[i + k] != needle[k]) continue@outer
            return i
        }
        return -1
    }
}
