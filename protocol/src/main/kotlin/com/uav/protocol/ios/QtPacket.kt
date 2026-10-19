package com.uav.protocol.ios

import com.uav.protocol.Bytes

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
object QtPacket {

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

    /**
     * `HPD1` / `HPD0` 共用的显示尺寸（"未识别机型的面板参数"）。
     *
     * ⚠️ 这**不是**编码分辨率（上游 PROTOCOL.md §7：
     * 「HPD1 DisplaySize 是主机能力/显示参数，不是可靠的 USB 编码分辨率控制」）。
     * 真实尺寸由设备 `vdim`/timing 决定，我们从 SPS 取。
     * iPhoneMirror 按完整 ProductType 查表给不同机型不同值；我们固定用这一个
     * **已实测可流** 的值——换机型要重新验证，不要以为它安全。
     */
    const val DEFAULT_DISPLAY_WIDTH = 1640
    const val DEFAULT_DISPLAY_HEIGHT = 2360

    /** PING：16 字节固定包，原样回写即可。 */
    fun ping(): ByteArray = u32(16) + fcc("ping") + u32(0) + u32(1)

    /**
     * ASYN/HPD1：显示描述。
     * `Valeria=true` 是 iPad / 新机型的"演示模式"（iPhoneMirror 的 A 模式）。
     */
    fun hpd1(width: Int = DEFAULT_DISPLAY_WIDTH, height: Int = DEFAULT_DISPLAY_HEIGHT): ByteArray {
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
            // ★ 用精确位模式，不要写 0.073 字面量：它与设备给的 double 差最后 1 bit
            //   （0x3FB2B020C49BA5E4），逐字节比对样本时会露出来。
            "BufferAheadInterval" to numF64(java.lang.Double.longBitsToDouble(0x3FB2B020C49BA5E4L)),
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

    // ---- 停止序列（与 hpd1 / hpa1 对称） ----

    /**
     * ASYN/HPD0：声明显示能力撤销（[hpd1] 的反向）。
     *
     * 结构与 [hpd1] 完全对称，只是 fourcc 从 `hpd1` 变成 `hpd0` ——
     * ★ 记得 fourcc 也要反转（`fcc()` 已处理）。
     *
     * 上游 `iPhoneMirror/docs/PROTOCOL.md` §4 结束段：
     * 「停止顺序是 HPA0、HPD0、处理 STOP/RPLY 与 RELS，然后释放接口」。
     */
    fun hpd0(width: Int = DEFAULT_DISPLAY_WIDTH, height: Int = DEFAULT_DISPLAY_HEIGHT): ByteArray {
        val display = dict(listOf(
            "Width" to numF64(width.toDouble()),
            "Height" to numF64(height.toDouble())
        ))
        return asyncDict("hpd0", 0L, dict(listOf(
            "Valeria" to boolValue(true),
            "HEVCDecoderSupports444" to boolValue(false),
            "DisplaySize" to display
        )))
    }

    /**
     * ASYN/HPA0：撤销音频请求（[hpa1] 的反向）。
     *
     * ★ 注意它**不带** `formats` 等协商字段 —— 停止是"关"，不是"重新协商"。
     * 若把 `hpa1` 的 body 原样搬过来，设备可能当成一次新的音频协商而继续推 `EAT!`。
     */
    fun hpa0(audioClock: Long): ByteArray =
        asyncDict("hpa0", audioClock, dict(listOf(
            "deviceUID" to strValue("Valeria"),
            "deviceName" to strValue("Valeria")
        )))

    /** RPLY：时钟应答。 */
    fun clockReply(correlation: Long, clock: Long): ByteArray =
        u32(28) + fcc("rply") + u64(correlation) + u32(0) + u64(clock)

    /** RPLY：AFMT 应答（Error=0）。 */
    fun afmtReply(correlation: Long): ByteArray {
        val body = dict(listOf("Error" to numU32(0)))
        return u32(20 + body.size) + fcc("rply") + u64(correlation) + u32(0) + body
    }

    /**
     * RPLY：`OG` 应答（24B，值恒 0）。
     *
     * `OG` 的线上 fourcc 是 `20 21 6F 67`（读出来像 "go! "），含义未知 ——
     * 但 quicktime_video_hack 对每个 OG 都回一包，且**这是音频真正开始推的前置**：
     * 不回它，设备只推视频、一个 EAT 帧都不发。
     */
    fun ogReply(correlation: Long): ByteArray =
        u32(24) + fcc("rply") + u64(correlation) + u64(0)

    /** RPLY：`SKEW` 应答（音频时钟偏差，秒）。没有可靠标定时就回 0。 */
    fun skewReply(correlation: Long, skew: Double): ByteArray =
        u32(28) + fcc("rply") + u64(correlation) + u32(0) + f64(skew)

    /** RPLY：时间应答。 */
    fun timeReply(correlation: Long, nanos: Long): ByteArray =
        u32(44) + fcc("rply") + u64(correlation) + u32(0) +
            u64(nanos) + u32(1_000_000_000) + u32(1) + u64(0)

    // ---- 解析辅助 ----

    /** ⚠️ QuickTime 报文的多字节字段是**小端**，别被 "u32" 这个名字骗了。 */
    fun u32At(b: ByteArray, off: Int): Int = Bytes.getIntLE(b, off)

    fun u64At(b: ByteArray, off: Int): Long = Bytes.getLongLE(b, off)

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
