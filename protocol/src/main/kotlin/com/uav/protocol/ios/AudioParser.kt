package com.uav.protocol.ios

/**
 * `ASYN/EAT!` 音频样本解析（QuickTime USB 的音频通道）。
 *
 * ## 报文形态
 *
 * 与视频的 `FEED` **同源**：两者都是 `CmSampleBuf`，外层一致
 * ```
 * [4B 总长]['nysa'(asyn)][u64 clock]['eat!'] + sbuf
 * ```
 * 区别只在 `sbuf` 里装什么 —— 视频的 `sdat` 是 H.264 NAL，音频的 `sdat` 是**裸 PCM**。
 *
 * `sbuf` 内部是若干「长度 + fourcc + 载荷」段串联（以下为真机样本实测顺序）：
 * ```
 * 'fubs'(sbuf)
 *   'stpo'(opts)  32B   CMTime 显示时间戳
 *   'aits'(stia)  80B   50 个样本的时序
 *   'tads'(sdat)  4104B → 4096B PCM（48k/16bit/stereo ≈ 21.3ms）
 *   'pmsn'(nsmp)  12B   样本数
 *   'ssiz'        12B
 *   'csdf'(fdsc)  68B   媒体类型 + AudioStreamBasicDescription
 * ```
 * ⚠️ 线上 fourcc 是**字节反转**的，所以源码里出现的是 `tads` / `fubs`（而不是 `sdat` / `sbuf`）。
 * ⚠️ 虽然 `HPA1` 里我们已经声明过格式，**仍以 `fdsc` 里的 ASBD 为准** —— 设备换格式时不用改代码。
 */
object AudioParser {

    private val SDAT = byteArrayOf(0x74, 0x61, 0x64, 0x73)   // 'sdat'
    private val NSMP = byteArrayOf(0x70, 0x6D, 0x73, 0x6E)   // 'nsmp'
    private val OPTS = byteArrayOf(0x73, 0x74, 0x70, 0x6F)   // 'opts'
    private val FDSC = byteArrayOf(0x63, 0x73, 0x64, 0x66)   // 'fdsc'
    private val ASBD = byteArrayOf(0x64, 0x62, 0x73, 0x61)   // 'asbd'

    /** 一帧音频的格式（来自 `fdsc` 内的 AudioStreamBasicDescription）。 */
    data class Format(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerChannel: Int,
        /** 'lpcm' 之类的 4 字符 codec */
        val codec: String,
        val formatFlags: Int
    ) {
        /** 每帧（每采样点）字节数 */
        val bytesPerFrame: Int get() = channels * (bitsPerChannel / 8)

        override fun toString(): String =
            "$codec ${sampleRate}Hz/${bitsPerChannel}bit/${channels}ch"
    }

    /**
     * 取出一个 EAT 帧里的 PCM。
     *
     * @return 裸 PCM（按 [format] 描述的布局交错）；不是音频样本或结构不符时返回 null
     */
    fun pcm(payload: ByteArray): ByteArray? {
        if (payload.size < 32) return null
        val at = indexOf(payload, SDAT, 16)
        if (at < 4) return null
        val len = le32(payload, at - 4)
        if (len < 8) return null
        val dataLen = len - 8
        val start = at + 4
        if (dataLen <= 0 || start + dataLen > payload.size) return null
        return payload.copyOfRange(start, start + dataLen)
    }

    /** 帧内声明的采样点数（没有 `nsmp` 段时返回 -1）。 */
    fun sampleCount(payload: ByteArray): Int {
        val at = indexOf(payload, NSMP, 16)
        if (at < 0 || at + 8 > payload.size) return -1
        return le32(payload, at + 4)
    }

    /**
     * 从 `fdsc` 里读真实音频格式。读不到返回 null（调用方应回退到 HPA1 声明的 48k/16bit/stereo）。
     */
    fun format(payload: ByteArray): Format? {
        val at = indexOf(payload, FDSC, 16)
        if (at < 0) return null
        val asbdAt = indexOf(payload, ASBD, at)
        if (asbdAt < 0) return null
        // ASBD 布局：f64 rate | u32 formatID | u32 flags | u32 bytesPerPacket |
        //            u32 framesPerPacket | u32 bytesPerFrame | u32 channels |
        //            u32 bitsPerChannel | u32 reserved | f64 x2
        val base = asbdAt + 4
        if (base + 40 > payload.size) return null
        val rate = java.lang.Double.longBitsToDouble(le64(payload, base))
        val codec = String(payload.copyOfRange(base + 8, base + 12).reversedArray(), Charsets.US_ASCII)
        val flags = le32(payload, base + 12)
        val channels = le32(payload, base + 28)
        val bits = le32(payload, base + 32)
        if (rate <= 0.0 || channels !in 1..8 || bits !in 8..32) return null
        return Format(rate.toInt(), channels, bits, codec, flags)
    }

    /**
     * `opts` 段里的显示时间戳（秒）；拿不到返回 -1。
     *
     * CMTime：`value(i64) | timescale(i32) | flags(u32) | epoch(i64)`。
     */
    fun ptsSeconds(payload: ByteArray): Double {
        val at = indexOf(payload, OPTS, 12)
        if (at < 0 || at + 16 > payload.size) return -1.0
        val value = le64(payload, at + 4)
        val timescale = le32(payload, at + 12)
        if (timescale <= 0) return -1.0
        return value.toDouble() / timescale
    }

    // ---------------------------------------------------------------- 字节工具

    /** 报文里所有长度/整数都是**小端**。 */
    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun le64(b: ByteArray, off: Int): Long =
        (le32(b, off).toLong() and 0xFFFFFFFFL) or (le32(b, off + 4).toLong() shl 32)

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (hay.size < needle.size) return -1
        val last = hay.size - needle.size
        outer@ for (i in from.coerceAtLeast(0)..last) {
            for (k in needle.indices) if (hay[i + k] != needle[k]) continue@outer
            return i
        }
        return -1
    }
}
