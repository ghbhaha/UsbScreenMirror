package com.uav.protocol

/** 手机屏幕上的一个点（像素）。 */
data class TouchPoint(val x: Int, val y: Int)

/** 一个尺寸（像素）。 */
data class IntSize(val width: Int, val height: Int)

/** 像素矩形（左上 + 右下）。 */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 宿主视图上的触点 → **手机屏幕像素**的映射（纯逻辑，可 JVM 单测）。
 *
 * ### 为什么需要它
 *
 * 宿主用 `SurfaceView` 显示解码后的画面，而画面的宽高比被 `max_size` 缩放过：
 * 屏幕 1224x2700 的手机 → `max_size=1920` → 编码 **872x1920**。
 * 而 AOA HID 触摸屏的逻辑范围声明的是**手机屏幕像素**（1224x2700）。
 * 所以"点在宿主的哪里"到"手机上的哪个像素"必须经过一次等比换算，
 * 且**信箱边（letterbox）里的触摸要丢掉** —— 那里没有画面，映射过去会点到手机上看不见的地方。
 *
 * ### 旋转
 *
 * 手机屏幕的**物理**尺寸只在 `CNXN` 里报一次，旋转后不会重报；
 * 但视频尺寸会随旋转变（10.7 重发 12B 流头）。两者方向必然一致
 * （视频是手机屏幕等比缩放来的），所以这里用**视频方向**推出手机屏幕在当前方向下的长宽：
 * 视频是横的 → 手机屏幕也是横的。见 [logicalPhoneSize]。
 *
 * 放在 `:protocol` 是因为它是纯数学、且**两端将来都可能用到**（手机端若要反向映射同理）。
 */
object TouchMapper {

    /**
     * 画面在视图里的实际显示矩形（居中信箱式适配）。
     *
     * 与 `AspectRatioSurfaceView.onMeasure` **同一套分支与公式** ——
     * 那个 View 会把自己量成视频宽高比，所以正常情况下返回的就是整个视图
     * （`left=top=0`）；这里仍然完整实现，是为了在"视图尺寸与视频比例不一致"时
     * （例如将来做 1:1 像素映射）依然算得对。
     */
    fun videoRect(viewW: Int, viewH: Int, videoW: Int, videoH: Int): IntRect? {
        if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) return null
        val rectW: Int
        val rectH: Int
        // 用乘法比较避免浮点误差，且与 onMeasure 的分支判断一致
        if (viewW.toLong() * videoH > viewH.toLong() * videoW) {
            // 容器更宽 → 受高度约束
            rectH = viewH
            rectW = (viewH.toLong() * videoW / videoH).toInt()
        } else {
            // 容器更高 → 受宽度约束
            rectW = viewW
            rectH = (viewW.toLong() * videoH / videoW).toInt()
        }
        if (rectW <= 0 || rectH <= 0) return null
        val left = (viewW - rectW) / 2
        val top = (viewH - rectH) / 2
        return IntRect(left, top, left + rectW, top + rectH)
    }

    /**
     * 手机屏幕**在当前方向下**的像素尺寸。
     *
     * @param screenW/screenH 手机屏幕物理尺寸（`CNXN` 报的，旋转不变）
     * @param videoW/videoH   当前画面的流头尺寸（旋转后会变）
     */
    fun logicalPhoneSize(screenW: Int, screenH: Int, videoW: Int, videoH: Int): IntSize? {
        if (screenW <= 0 || screenH <= 0) return null
        val longSide = maxOf(screenW, screenH)
        val shortSide = minOf(screenW, screenH)
        if (longSide == shortSide) return IntSize(screenW, screenH) // 正方形屏：无所谓方向
        // 视频方向 == 手机屏幕方向（视频由手机屏幕等比缩放而来）。
        // 没有任何视频尺寸信息时，按物理朝向原样返回。
        if (videoW <= 0 || videoH <= 0) return IntSize(screenW, screenH)
        return if (videoW > videoH) IntSize(longSide, shortSide) else IntSize(shortSide, longSide)
    }

    /**
     * 把视图坐标映射成手机屏幕像素。
     *
     * @return `null` 表示落在**信箱边**或尺寸信息不全 —— 调用方应当忽略这次触摸、不要发 HID 报告。
     */
    fun mapTouch(
        viewX: Int,
        viewY: Int,
        viewW: Int,
        viewH: Int,
        videoW: Int,
        videoH: Int,
        screenW: Int,
        screenH: Int,
    ): TouchPoint? {
        val rect = videoRect(viewW, viewH, videoW, videoH) ?: return null
        val size = logicalPhoneSize(screenW, screenH, videoW, videoH) ?: return null
        if (viewX < rect.left || viewX >= rect.right) return null
        if (viewY < rect.top || viewY >= rect.bottom) return null

        // 归一化到 [0,1] 再乘目标尺寸；用 width 作为逻辑上限（与 HID 描述符的 Logical Max 一致）
        val nx = (viewX - rect.left).toDouble() / rect.width
        val ny = (viewY - rect.top).toDouble() / rect.height
        return TouchPoint(
            x = Math.round(nx * size.width).toInt().coerceIn(0, size.width),
            y = Math.round(ny * size.height).toInt().coerceIn(0, size.height),
        )
    }
}
