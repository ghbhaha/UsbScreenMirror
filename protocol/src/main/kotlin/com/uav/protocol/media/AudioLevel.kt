package com.uav.protocol.media

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * PCM 电平计算（dBFS）—— 内录链路"到底有没有声音"的**唯一客观证据**。
 *
 * 为什么值得单独放一层并写单测：内录失败最典型的形态是**静默成功** ——
 * `AudioRecord` 正常打开、编码器正常出帧、宿主也在播放，但录到的是全 0。
 * 从"帧数在涨"完全看不出来，只能靠电平判断。
 * 而电平公式写错（比如把 16 bit 当成 8 bit 归一化）会得出 -48 dBFS 这种
 * "看起来有声音"的假读数，恰好掩盖问题。
 *
 * 纯逻辑、无 `android.*` 依赖，两端共用（宿主端也可用它判断解码结果是否静音）。
 */
object AudioLevel {

    /** 静音下限。用有限值而不是 `-Infinity`：日志与统计里出现 `-Inf` 会很难看清。 */
    const val SILENCE_DBFS: Float = -120f

    /** 16 bit PCM 的满幅值。 */
    private const val FULL_SCALE = 32768.0

    /**
     * 均方根电平（dBFS）。
     *
     * @param pcm 小端 16 bit 交错 PCM
     * @param bytesPerSample 每样本字节数（本项目固定 2）
     * @return 0 dBFS 为满幅；静音返回 [SILENCE_DBFS]。
     *         结果**永远不会是 NaN 或 -Infinity**（日志友好）。
     */
    fun rmsDbfs(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset, bytesPerSample: Int = 2): Float {
        require(bytesPerSample in 1..4) { "bytesPerSample 只支持 1..4，实际 $bytesPerSample" }
        val samples = length / bytesPerSample
        if (samples <= 0) return SILENCE_DBFS

        var sum = 0.0
        var i = offset
        val end = offset + samples * bytesPerSample
        while (i < end) {
            val v = readSample(pcm, i, bytesPerSample)
            sum += v.toDouble() * v
            i += bytesPerSample
        }
        val rms = sqrt(sum / samples)
        return toDbfs(rms)
    }

    /** 峰值电平（dBFS）。用于区分"整体安静但有瞬态"与"彻底静音"。 */
    fun peakDbfs(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset, bytesPerSample: Int = 2): Float {
        val samples = length / bytesPerSample
        if (samples <= 0) return SILENCE_DBFS
        var peak = 0
        var i = offset
        val end = offset + samples * bytesPerSample
        while (i < end) {
            val v = kotlin.math.abs(readSample(pcm, i, bytesPerSample))
            if (v > peak) peak = v
            i += bytesPerSample
        }
        return toDbfs(peak.toDouble())
    }

    /** 是否静音（低于 [thresholdDbfs]）。 */
    fun isSilent(
        pcm: ByteArray,
        offset: Int = 0,
        length: Int = pcm.size - offset,
        bytesPerSample: Int = 2,
        thresholdDbfs: Float = -60f,
    ): Boolean = rmsDbfs(pcm, offset, length, bytesPerSample) <= thresholdDbfs

    /**
     * 读一个小端样本。
     *
     * 16 bit 按**有符号**解读（PCM 就是有符号的）；8 bit 在 Android 上按无符号，
     * 这里只用于本项目（16 bit），8 bit 分支仅作公式完整性补全。
     */
    private fun readSample(pcm: ByteArray, at: Int, bytesPerSample: Int): Int = when (bytesPerSample) {
        1 -> (pcm[at].toInt() and 0xFF) - 128
        2 -> ((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()
        3 -> {
            val raw = (pcm[at].toInt() and 0xFF) or
                ((pcm[at + 1].toInt() and 0xFF) shl 8) or
                (pcm[at + 2].toInt() shl 16)
            raw shl 8 shr 8 // 24 bit -> 符号扩展
        }
        else -> {
            val raw = (pcm[at].toInt() and 0xFF) or
                ((pcm[at + 1].toInt() and 0xFF) shl 8) or
                ((pcm[at + 2].toInt() and 0xFF) shl 16) or
                (pcm[at + 3].toInt() shl 24)
            raw
        }
    }

    /** 幅度 → dBFS。刻意把非正值钳到下限，避免 NaN / -Inf 污染日志。 */
    private fun toDbfs(amplitude: Double): Float {
        if (amplitude <= 0.0 || amplitude.isNaN()) return SILENCE_DBFS
        val db = 20.0 * log10(amplitude / FULL_SCALE)
        if (db.isNaN()) return SILENCE_DBFS
        return db.toFloat().coerceAtLeast(SILENCE_DBFS)
    }
}
