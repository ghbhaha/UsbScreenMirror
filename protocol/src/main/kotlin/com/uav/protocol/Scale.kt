package com.uav.protocol

/**
 * `max_size` 的计算与等比缩放（计划 3.7 / 4.3 / 4.5）。
 *
 * 放在 `:protocol` 而非 `:app-accessory`，是因为**两端都要用**：
 * 宿主算 `max_size` 并下发，手机端拿它做缩放。做成纯数学函数，两端不可能算得不一致。
 *
 * 规则（冻结）：
 * - `max_size = align8( min(手机屏幕长边, 宿主屏幕长边) )`
 *   —— 宿主设备就是另一台安卓手机，两边分辨率相近，这样取值的结果是
 *   **编码尺寸 ≈ 宿主可显示尺寸，宿主端不做任何放大**。
 * - 缩放时**长边精确等于 `max_size`**，短边按比例四舍五入，再对齐 8 字节。
 *   两轴都对齐 8 是为了满足编码器对 stride 的要求，避免部分机型拒配。
 */
object Scale {

    /** 设备屏幕的"长边"（宽高中较大者）。 */
    fun longSide(width: Int, height: Int): Int = maxOf(width, height)

    /**
     * 宿主计算下发给手机端的 `max_size`。
     *
     * @param phoneWidth/phoneHeight    手机屏幕尺寸（来自 CNXN payload）
     * @param hostWidth/hostHeight      宿主屏幕尺寸（本地读）
     * @return `align8(min(手机长边, 宿主长边))`
     */
    fun computeMaxSize(
        phoneWidth: Int,
        phoneHeight: Int,
        hostWidth: Int,
        hostHeight: Int,
    ): Int {
        val v = minOf(longSide(phoneWidth, phoneHeight), longSide(hostWidth, hostHeight))
        return Bytes.align8(v)
    }

    /**
     * 把 `srcW x srcH` 等比缩放到长边不超过 [maxSize]，结果**两轴均对齐 8 字节**。
     *
     * 若源尺寸本就 ≤ maxSize 且已对齐，原样返回（不做无谓的二次采样）。
     *
     * @return `intArrayOf(width, height)`
     */
    fun scaledSize(srcW: Int, srcH: Int, maxSize: Int): IntArray {
        require(srcW > 0 && srcH > 0) { "bad source size ${srcW}x$srcH" }
        require(maxSize > 0) { "bad maxSize $maxSize" }

        val srcLong = longSide(srcW, srcH)
        val scale = if (srcLong <= maxSize) 1.0 else maxSize.toDouble() / srcLong

        // 长边取整后精确落在 maxSize（或源长边），短边按比例
        val isLandscape = srcW >= srcH
        val longTarget = if (srcLong <= maxSize) srcLong else Bytes.align8((srcLong * scale).toInt())
        var w: Int
        var h: Int
        if (isLandscape) {
            w = longTarget
            h = Bytes.align8(Math.round(srcH * (longTarget.toDouble() / srcW)).toInt())
        } else {
            h = longTarget
            w = Bytes.align8(Math.round(srcW * (longTarget.toDouble() / srcH)).toInt())
        }
        // 对齐 8 之后可能出现 0（极端窄屏），兜底到 8
        if (w < 8) w = 8
        if (h < 8) h = 8
        return intArrayOf(w, h)
    }

    /** 缩放是否实际发生了变化（用于日志：`virtualDisplay 1080x2400 -> scaled 1080x2400`）。 */
    fun isIdentity(srcW: Int, srcH: Int, outW: Int, outH: Int): Boolean =
        srcW == outW && srcH == outH
}
