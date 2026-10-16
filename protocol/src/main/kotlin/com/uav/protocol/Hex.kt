package com.uav.protocol

/** hexdump 工具，联调时用于打印报文/帧的头部字节（计划 1.8）。 */
object Hex {

    private const val HEX = "0123456789abcdef"

    fun toHexString(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): String {
        val sb = StringBuilder(length * 2)
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 经典 hexdump：每行 16 字节，左侧偏移、中间 hex、右侧 ASCII。
     *
     * @param maxBytes 最多打印多少字节（默认 64，联调只关心头部，避免刷屏）
     */
    fun dump(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
        maxBytes: Int = 64,
    ): String {
        val end = offset + length
        val limit = minOf(end, offset + maxBytes)
        val sb = StringBuilder()
        var i = offset
        while (i < limit) {
            val lineEnd = minOf(i + 16, limit)
            sb.append(String.format("%08x  ", i - offset))
            for (j in i until i + 16) {
                if (j < lineEnd) {
                    val b = data[j].toInt() and 0xFF
                    sb.append(HEX[b ushr 4]).append(HEX[b and 0x0F]).append(' ')
                } else {
                    sb.append("   ")
                }
            }
            sb.append(' ')
            for (j in i until lineEnd) {
                val b = data[j].toInt() and 0xFF
                sb.append(if (b in 0x20..0x7E) b.toChar() else '.')
            }
            sb.append('\n')
            i += 16
        }
        if (end - offset > maxBytes) {
            sb.append("... (").append(end - offset).append(" bytes total, ")
                .append("showing first ").append(maxBytes).append(")\n")
        }
        return sb.toString()
    }
}
