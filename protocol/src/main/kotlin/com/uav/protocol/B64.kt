package com.uav.protocol

/**
 * Base64（RFC 4648 §4，标准字母表 + `=` 填充）。
 *
 * ## 为什么不用 `java.util.Base64`
 *
 * `java.util.Base64` 是 **API 26（Android 8.0）** 才有的类。宿主 minSdk 是 23，
 * 在 Android 7.1（API 25）及更老的定制宿主上它 **不存在** ⇒ 抛 `NoClassDefFoundError`。
 *
 * ★ 这个异常极其阴险：它被 `runCatching` 吞掉之后只表现为"解析不出内容"，
 *   看起来像"设备没回复"而不是"代码在这台机器上不可用"。实测 CVTE V31103（Android 7.1.2）
 *   上 `GetValue(DevicePublicKey)` 的回包（914B，base64 完全合法）就是栽在这里。
 *
 * 所以 `:protocol` 自带一份纯 Kotlin 实现：零 Android 依赖（JVM 单测直接可用），
 * 也不挑系统版本。**`:protocol` 里禁止再出现 `java.util.Base64`**。
 */
object B64 {

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** ASCII → 6 bit 值；-1 = 非法字符，-2 = 填充。 */
    private val REVERSE = IntArray(128) { -1 }.also { t ->
        ALPHABET.forEachIndexed { i, c -> t[c.code] = i }
        t['='.code] = -2
    }

    fun encode(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): String {
        if (length <= 0) return ""
        val sb = StringBuilder((length + 2) / 3 * 4)
        var i = offset
        val end = offset + length
        while (i + 2 < end) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n ushr 18) and 0x3F])
                .append(ALPHABET[(n ushr 12) and 0x3F])
                .append(ALPHABET[(n ushr 6) and 0x3F])
                .append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (end - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[(n ushr 18) and 0x3F])
                    .append(ALPHABET[(n ushr 12) and 0x3F])
                    .append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[(n ushr 18) and 0x3F])
                    .append(ALPHABET[(n ushr 12) and 0x3F])
                    .append(ALPHABET[(n ushr 6) and 0x3F])
                    .append('=')
            }
        }
        return sb.toString()
    }

    /**
     * 解码。**忽略所有空白**（plist 的 `<data>` 会按 64 列换行），
     * 也容忍省略填充（部分设备如此）。
     *
     * @throws IllegalArgumentException 出现非字母表字符时（宁可报错也不要解出错数据）
     */
    fun decode(text: String): ByteArray {
        val out = ByteArray(text.length / 4 * 3 + 3)
        var o = 0
        var acc = 0
        var bits = 0
        for (c in text) {
            if (c.isWhitespace()) continue
            val code = c.code
            val v = if (code < 128) REVERSE[code] else -1
            when {
                v == -2 -> break                  // `=` 之后不再有有效数据
                v < 0 -> throw IllegalArgumentException("base64 含非法字符 '\\u${code.toString(16)}'")
                else -> {
                    acc = ((acc shl 6) or v) and 0xFFFF   // 只需要低 14 位，别让高位一直长
                    bits += 6
                    if (bits >= 8) {
                        bits -= 8
                        out[o++] = ((acc ushr bits) and 0xFF).toByte()
                    }
                }
            }
        }
        return if (o == out.size) out else out.copyOf(o)
    }
}
