package com.uav.iosqt

/**
 * QuickTime USB 报文的构造。
 *
 * 全部是纯函数，方便对抓包逐字节比对。协议依据：
 *  - `danielpaulus/quicktime_video_hack`
 *  - `RayrenSX/iPhoneMirror` 的 `QuickTimeSession.cpp` / `QuickTimePacket.cpp`
 *
 * ★ 关键：fourcc 在线上是**字节反转**的。
 *   `fourcc('p','i','n','g')` 是大端整数、按小端写盘，所以线上是 `67 6e 69 70`。
 *   构造和解析两侧都必须用 [fcc]，否则会"收到 SYNC 却认不出来"。
 */
internal object QtPacket {

    /** fourcc 的线上字节序：`"ping"` → `67 6e 69 70`。 */
    fun fcc(name: String): ByteArray =
        name.toByteArray(Charsets.US_ASCII).reversedArray()

    fun u32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    fun u64(v: Long): ByteArray = u32(v.toInt()) + u32((v ushr 32).toInt())

    fun f64(v: Double): ByteArray = u64(java.lang.Double.doubleToRawLongBits(v))

    /** 统一封装：`[u32 length=8+payload][magic][payload]`。 */
    fun wrap(magic: String, payload: ByteArray): ByteArray =
        u32(8 + payload.size) + fcc(magic) + payload

    // ---- plist 风格的值（QuickTime 自己的 dict/bool/num/str） ----

    fun numU32(v: Int): ByteArray = wrap("nmbv", byteArrayOf(3) + u32(v))

    fun numF64(v: Double): ByteArray = wrap("nmbv", byteArrayOf(6) + f64(v))

    fun boolValue(v: Boolean): ByteArray =
        wrap("bulv", byteArrayOf(if (v) 1 else 0))

    fun strValue(v: String): ByteArray =
        wrap("strv", v.toByteArray(Charsets.UTF_8))

    fun dataValue(v: ByteArray): ByteArray = wrap("datv", v)

    /** `dict` = 若干 `keyv(strk(键) + 值)` 串联。 */
    fun dict(entries: List<Pair<String, ByteArray>>): ByteArray {
        var body = ByteArray(0)
        for ((k, v) in entries) {
            body += wrap("keyv", wrap("strk", k.toByteArray(Charsets.UTF_8)) + v)
        }
        return wrap("dict", body)
    }

    fun asyncDict(subtype: String, clock: Long, body: ByteArray): ByteArray =
        u32(20 + body.size) + fcc("asyn") + u64(clock) + fcc(subtype) + body

    // ---- 具体报文 ----

    /** PING：16 字节固定包，原样回写即可。 */
    fun ping(): ByteArray = u32(16) + fcc("ping") + u32(0) + u32(1)

    /**
     * ASYN/HPD1：显示描述。
     * `Valeria=true` 是 iPad / 新机型的"演示模式"（iPhoneMirror 的 A 模式）。
     */
    fun hpd1(width: Int = 1640, height: Int = 2360): ByteArray {
        val display = dict(listOf(
            "Width" to numF64(width.toDouble()),
            "Height" to numF64(height.toDouble())
        ))
        return asyncDict("hpd1", 1L, dict(listOf(
            "Valeria" to boolValue(true),
            "HEVCDecoderSupports444" to boolValue(false),
            "DisplaySize" to display
        )))
    }

    /** ASYN/HPA1：音频格式（48k / lpcm / 16bit / stereo）。 */
    fun hpa1(audioClock: Long): ByteArray {
        var descriptor = ByteArray(0)
        descriptor += f64(48000.0)
        descriptor += fcc("lpcm")
        descriptor += u32(12) + u32(4) + u32(1) + u32(4) + u32(2) + u32(16) + u32(0)
        descriptor += f64(48000.0) + f64(48000.0)
        return asyncDict("hpa1", audioClock, dict(listOf(
            "BufferAheadInterval" to numF64(0.073),
            "deviceUID" to strValue("Valeria"),
            "ScreenLatency" to numF64(0.04),
            "formats" to dataValue(descriptor),
            "EDIDAC3Support" to numU32(0),
            "deviceName" to strValue("Valeria")
        )))
    }

    /** ASYN/NEED：向设备要下一帧（带设备视频时钟）。 */
    fun need(deviceVideoClock: Long): ByteArray =
        u32(20) + fcc("asyn") + u64(deviceVideoClock) + fcc("need")

    /** RPLY：时钟应答。 */
    fun clockReply(correlation: Long, clock: Long): ByteArray =
        u32(28) + fcc("rply") + u64(correlation) + u32(0) + u64(clock)

    /** RPLY：AFMT 应答（Error=0）。 */
    fun afmtReply(correlation: Long): ByteArray {
        val body = dict(listOf("Error" to numU32(0)))
        return u32(20 + body.size) + fcc("rply") + u64(correlation) + u32(0) + body
    }

    /** RPLY：时间应答。 */
    fun timeReply(correlation: Long, nanos: Long): ByteArray =
        u32(44) + fcc("rply") + u64(correlation) + u32(0) +
            u64(nanos) + u32(1_000_000_000) + u32(1) + u64(0)

    // ---- 解析辅助 ----

    fun u32At(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    fun u64At(b: ByteArray, off: Int): Long =
        (u32At(b, off).toLong() and 0xFFFFFFFFL) or
            (u32At(b, off + 4).toLong() shl 32)

    /** 判断某个偏移处的 4 字节是否等于 fourcc（内部做反转）。 */
    fun matches(b: ByteArray, off: Int, name: String): Boolean {
        val f = fcc(name)
        if (off + 4 > b.size) return false
        for (i in 0 until 4) if (b[off + i] != f[i]) return false
        return true
    }

    /** 把线上 4 字节按 fourcc 还原成可读字符串：`67 6e 69 70` → `ping`。 */
    fun fccString(b: ByteArray, off: Int): String {
        if (off + 4 > b.size) return "?"
        return String(b.copyOfRange(off, off + 4).reversedArray(), Charsets.US_ASCII)
    }
}
