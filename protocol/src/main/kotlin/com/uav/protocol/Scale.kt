package com.uav.protocol

/**
 * `max_size` 的计算与等比缩放（计划 3.7 / 4.3 / 4.5；1:1 口径见 10.15）。
 *
 * 放在 `:protocol` 而非 `:app-accessory`，是因为**两端都要用**：
 * 宿主算 `max_size` 并下发，手机端拿它做缩放。做成纯数学函数，两端不可能算得不一致。
 *
 * 规则（冻结）：
 * - **1:1 口径（当前）**：`max_size = align8( min(手机长边, 长边(画面在宿主上的显示区)) )`
 *   —— 让**编码尺寸 ≈ 宿主实际显示尺寸**，宿主端不再二次缩放（见 [oneToOneMaxSize]）。
 * - 旧口径（显示区未知时回退）：`align8(min(手机长边, 宿主面板长边))`（见 [computeMaxSize]）。
 *   按"长边"拍，不看宿主到底把画面铺多大，横屏时会**多编 3 倍多的像素**（10.15 实测）。
 * - 缩放时**长边精确等于 `max_size`**，短边按比例四舍五入，再对齐 8 字节。
 *   两轴都对齐 8 是为了满足编码器对 stride 的要求，避免部分机型拒配。
 */
object Scale {

    /**
     * 1:1 目标的**下界**（长边像素）。
     *
     * 纯安全网：宿主窗口被压得极小时（分屏/floating），照 1:1 会算出 100 这种离谱尺寸，
     * 画面糊到不可用。取一个"小但可用"的值，仅在低于它时才生效
     * （此时宿主是缩小显示，不会产生"放大导致的模糊"）。
     */
    const val MIN_ONE_TO_ONE_LONG_SIDE = 240

    /** 设备屏幕的"长边"（宽高中较大者）。 */
    fun longSide(width: Int, height: Int): Int = maxOf(width, height)

    /**
     * 把 `srcW x srcH` 按**同一宽高比**塞进 `availW x availH` 容器（居中留黑边）后的尺寸。
     *
     * ★ 这是"画面在屏幕上被显示成多大"的**唯一权威定义**，三处共用：
     *  - 宿主 `AspectRatioSurfaceView.onMeasure`（真正决定视图尺寸）；
     *  - [oneToOneMaxSize]（据此预测显示尺寸，反推编码尺寸）；
     *  - 触摸映射（`TouchMapper` 的显示区判定按已量好的视图算，所以不受影响）。
     *  各处若各写一份，"哪里算画面内"就会出现多个可能不一致的定义 —— 这正是 10.11 踩过的坑。
     *
     * @return `intArrayOf(w, h)`，两轴至少 1
     */
    fun fitInside(availW: Int, availH: Int, srcW: Int, srcH: Int): IntArray {
        require(srcW > 0 && srcH > 0) { "bad source size ${srcW}x$srcH" }
        if (availW <= 0 || availH <= 0) return intArrayOf(1, 1)

        val srcRatio = srcW.toDouble() / srcH.toDouble()
        val containerRatio = availW.toDouble() / availH.toDouble()

        return if (containerRatio > srcRatio) {
            // 容器更宽 -> 受高度约束
            intArrayOf((availH * srcRatio).toInt().coerceAtLeast(1), availH)
        } else {
            // 容器更高 -> 受宽度约束
            intArrayOf(availW, (availW / srcRatio).toInt().coerceAtLeast(1))
        }
    }

    /**
     * **1:1 像素映射**的目标：编码长边取多少，才能让宿主按原大小显示、不再缩放。
     *
     * 取 `align8(min(手机长边, 长边(显示区)))`：
     * - 显示区**比手机小** → 取显示区（只编真正会显示的那点像素，省码流；清晰度不降）；
     * - 显示区**比手机大** → 取手机（再编大只是放大空像素，白花码流）。
     *
     * 关键：显示区由**画面比例**决定（信箱式适配），所以**随手机方向变化** ——
     * 手机旋转后重新调用本函数即可得到新方向下的正确值，不需要重新协商。
     *
     * @param availW/availH 宿主**视频显示区**的可用尺寸（宿主根视图尺寸，px）
     * @param hostPanelW/hostPanelH 宿主面板尺寸，**仅在显示区未知时**用于回退到旧口径
     */
    fun oneToOneMaxSize(
        phoneW: Int,
        phoneH: Int,
        availW: Int,
        availH: Int,
        hostPanelW: Int = 0,
        hostPanelH: Int = 0,
    ): Int {
        require(phoneW > 0 && phoneH > 0) { "bad phone size ${phoneW}x$phoneH" }
        val phoneLong = longSide(phoneW, phoneH)
        if (availW <= 0 || availH <= 0) {
            // 显示区还没测量出来（视图尚未布局）：退回旧口径，宁可多编也不能算错。
            // 同样夹住源长边 —— 旧口径会对源长边做 align8 上取整（2700 -> 2704），
            // 那等于让手机端放大自己的画面，只有糊没有信息。
            val fallback = if (hostPanelW > 0 && hostPanelH > 0) {
                computeMaxSize(phoneW, phoneH, hostPanelW, hostPanelH)
            } else {
                Bytes.align8(phoneLong)
            }
            return minOf(fallback, phoneLong)
        }
        val fitted = fitInside(availW, availH, phoneW, phoneH)
        val shownLong = longSide(fitted[0], fitted[1])

        val want = maxOf(Bytes.align8(minOf(phoneLong, shownLong)), MIN_ONE_TO_ONE_LONG_SIDE)
        // 永不超出源长边：超出就等于让手机端放大自己的画面，只有糊没有信息。
        // ⚠️ 这一步会牺牲 8 对齐（源长边本身可能不是 8 的倍数，例如 2700）——
        //   此时 max_size >= 源长边，scaledSize 走"原样返回"分支，编码尺寸就是源尺寸本身，
        //   这正是"不做任何缩放"的最优结果。
        return minOf(want, phoneLong)
    }

    /**
     * 旧口径（按"面板长边"算 `max_size`）—— **仅在视频显示区未知时回退使用**。
     *
     * ⚠️ 别在新代码里直接用它：它不看宿主把画面铺多大，横屏时会多编 3 倍多的像素
     * （10.15 实测 1.67 Mpx vs 显示 0.47 Mpx）。保留是为了兼容与回退。
     *
     * @param phoneWidth/phoneHeight    手机屏幕尺寸（来自 CNXN payload）
     * @param hostWidth/hostHeight      宿主**面板**尺寸（本地读）
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
