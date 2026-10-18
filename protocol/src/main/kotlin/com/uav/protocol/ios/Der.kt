package com.uav.protocol.ios

import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 极简 DER 编码器 —— 只覆盖「手工签发 X.509 证书」需要的那几种类型。
 *
 * ## 为什么不用 BouncyCastle
 *
 * 一是本仓库的 `:protocol` 要求零第三方依赖（这样 JVM 单测能真跑）；
 * 二是配对证书的规格极窄（空 DN、serial=1、三个固定扩展），DER 手拼反而比引一套
 * provider 更可控 —— 出问题时能逐字节对照 PC 上那套成功实现。
 *
 * ## 对照实现
 *
 * `pymobiledevice3/ca.py`（libimobiledevice 的 Python 复刻），字段顺序与它逐项一致。
 */
object Der {

    /** DER 长度前缀（短/长两种形式）。 */
    fun length(n: Int): ByteArray {
        require(n >= 0) { "长度不能为负：$n" }
        if (n < 0x80) return byteArrayOf(n.toByte())
        var v = n
        val tmp = ArrayList<Byte>(4)
        while (v > 0) {
            tmp.add((v and 0xFF).toByte())
            v = v ushr 8
        }
        val body = tmp.asReversed().toByteArray()
        return byteArrayOf((0x80 or body.size).toByte()) + body
    }

    fun tlv(tag: Int, body: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + length(body.size) + body

    fun seq(vararg parts: ByteArray): ByteArray = seqOf(parts.toList())

    fun seqOf(parts: List<ByteArray>): ByteArray = tlv(0x30, concat(parts))

    /** INTEGER。`BigInteger.toByteArray()` 本身就是最小补码表示，直接用。 */
    fun integer(v: BigInteger): ByteArray = tlv(0x02, v.toByteArray())

    fun integer(v: Long): ByteArray = integer(BigInteger.valueOf(v))

    /** OBJECT IDENTIFIER，点分字符串 → DER。 */
    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        require(parts.size >= 2) { "OID 至少两段：$dotted" }
        val out = ArrayList<Byte>(8)
        out.add((parts[0] * 40 + parts[1]).toByte())
        for (i in 2 until parts.size) {
            var v = parts[i]
            val chunk = ArrayList<Byte>(4)
            chunk.add((v and 0x7F).toByte())
            v = v shr 7
            while (v > 0) {
                chunk.add(((v and 0x7F) or 0x80).toByte())
                v = v shr 7
            }
            out.addAll(chunk.asReversed())
        }
        return tlv(0x06, out.toByteArray())
    }

    /** UTCTime（`YYMMDDHHMMSSZ`，UTC）。2049 年以前都够用，配对证书效期 10 年。 */
    fun utcTime(t: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return tlv(0x17, f.format(t).toByteArray(Charsets.US_ASCII))
    }

    fun boolean(v: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0x00))

    /** BIT STRING；`unused` 是末字节里未使用的低位个数。 */
    fun bitString(bytes: ByteArray, unused: Int = 0): ByteArray =
        tlv(0x03, byteArrayOf(unused.toByte()) + bytes)

    fun octetString(bytes: ByteArray): ByteArray = tlv(0x04, bytes)

    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    /** `[n] EXPLICIT`：用于 version 与 extensions 这两个包裹容器。 */
    fun explicit(n: Int, body: ByteArray): ByteArray = tlv(0xA0 or n, body)

    fun concat(parts: List<ByteArray>): ByteArray {
        var size = 0
        for (p in parts) size += p.size
        val out = ByteArray(size)
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }

    /** 读出 TLV 的头：返回 (内容起始偏移, 内容长度, tag)。 */
    fun head(b: ByteArray, off: Int = 0): Triple<Int, Int, Int> {
        require(off + 2 <= b.size) { "TLV 头截断" }
        val tag = b[off].toInt() and 0xFF
        var p = off + 1
        var len = b[p].toInt() and 0xFF
        p++
        if (len and 0x80 != 0) {
            val n = len and 0x7F
            require(n in 1..4 && p + n <= b.size) { "非法长度字段" }
            len = 0
            repeat(n) {
                len = (len shl 8) or (b[p].toInt() and 0xFF)
                p++
            }
        }
        require(p + len <= b.size) { "TLV 内容截断" }
        return Triple(p, len, tag)
    }

    /** 拆一个 SEQUENCE 的直接子项（只取偏移与长度，不拷贝）。 */
    fun children(b: ByteArray): List<Pair<Int, Int>> {
        val (start, len, tag) = head(b, 0)
        require(tag == 0x30) { "不是 SEQUENCE（tag=0x%02x）".format(tag) }
        val out = ArrayList<Pair<Int, Int>>(8)
        var p = start
        val end = start + len
        while (p < end) {
            val (cs, cl, _) = head(b, p)
            out.add(cs to cl)
            p = cs + cl
        }
        return out
    }

    /** PEM 包装（64 字符换行）。 */
    fun pem(label: String, der: ByteArray): ByteArray {
        val b64 = java.util.Base64.getEncoder().encodeToString(der)
        val sb = StringBuilder(b64.length + 64)
        sb.append("-----BEGIN ").append(label).append("-----\n")
        var i = 0
        while (i < b64.length) {
            val n = minOf(64, b64.length - i)
            sb.append(b64, i, i + n).append('\n')
            i += n
        }
        sb.append("-----END ").append(label).append("-----\n")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /** 是否 PEM 文本（而不是裸 DER）。 */
    fun isPem(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(16, b.size), Charsets.US_ASCII)
        return head.startsWith("-----BEGIN")
    }

    /** 取 PEM 的 base64 载荷（跳过所有 `-----` 行）。 */
    fun pemBody(b: ByteArray): ByteArray {
        val text = String(b, Charsets.US_ASCII)
        val b64 = text.lineSequence()
            .filter { !it.startsWith("-----") && it.isNotBlank() }
            .joinToString("")
        return java.util.Base64.getDecoder().decode(b64)
    }
}
