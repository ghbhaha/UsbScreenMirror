package com.uav.protocol.media


/**
 * 流头里的 `codecId`（计划 4.4）：**固定 4 个 ASCII 字符**。
 *
 * 不足 4 字符的 id 以**空格右填充**（沿用 scrcpy 的 fourcc 惯例），
 * 例如 `raw` → `"raw "`。这样解码端拿到的永远是 4 字符，无需再判长度。
 *
 * ⚠️ 注意：H.264 在本协议里用 `avc1`（不是 scrcpy 的 `h264`）——
 * 以计划 4.4 的冻结清单为准，两端必须逐字一致。
 */
object CodecId {

    /** H.264（MVP 唯一使用的 id）。 */
    const val AVC1 = "avc1"

    const val RAW = "raw "

    const val AAC = "aac "

    const val OPUS = "opus"

    const val FLAC = "flac"

    /** 固定长度：4 字节 ASCII。 */
    const val SIZE = 4

    val KNOWN: List<String> = listOf(AVC1, RAW, AAC, OPUS, FLAC)

    /** 把 id 规范化为 4 字符（右补空格）；非法则抛异常。 */
    fun normalize(id: String): String {
        require(id.length in 1..SIZE) { "codecId length must be 1..4, got '$id'" }
        require(id.all { it.code in 0x20..0x7E }) { "codecId must be printable ASCII, got '$id'" }
        return id.padEnd(SIZE, ' ')
    }

    fun isValid(id: String): Boolean =
        id.length == SIZE && id.all { it.code in 0x20..0x7E }

    /** 编码为 4 字节（自动右补空格）。 */
    fun encode(id: String): ByteArray = normalize(id).toByteArray(Charsets.US_ASCII)

    /** 从 4 字节解码，去掉尾部空格。 */
    fun decode(buf: ByteArray, off: Int = 0): String {
        require(off + SIZE <= buf.size) { "need $SIZE bytes at $off, have ${buf.size}" }
        val s = String(buf, off, SIZE, Charsets.US_ASCII)
        return s.trimEnd(' ')
    }
}
