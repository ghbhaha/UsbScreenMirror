package com.uav.host.aoa.ui

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.View
import com.uav.protocol.media.Scale

/**
 * 按视频宽高比自适应的 `SurfaceView`（计划 4.7）。
 *
 * **明确不调 `holder.setFixedSize(w, h)`** —— 那会重建 surface，
 * 让解码器已持有的 `Surface` 失效，属于隐蔽的时序坑（`configure` 后画面全黑或直接报错）。
 *
 * 1:1 像素映射（10.15）**不是靠这个视图**做到的，而是靠让**编码尺寸 = 这个视图的尺寸**：
 * 宿主把本视图的可用区（根视图尺寸）经 `OPEN` 的 `display_width/height` 告诉手机，
 * 手机按 `Scale.oneToOneMaxSize` 算出编码尺寸。所以这里的几何必须与
 * [com.uav.protocol.media.Scale.fitInside] 完全一致 —— 现在直接调用它。
 *
 * 尺寸权威来源是**流头**的 `width/height`（不是 SPS 解析结果）。
 */
class AspectRatioSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {

    @Volatile
    var videoWidth: Int = 0
        private set

    @Volatile
    var videoHeight: Int = 0
        private set

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (videoWidth == width && videoHeight == height) return
        videoWidth = width
        videoHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val availH = MeasureSpec.getSize(heightMeasureSpec)

        if (videoWidth <= 0 || videoHeight <= 0 || availW <= 0 || availH <= 0) {
            setMeasuredDimension(availW, availH)
            return
        }

        // ★ 信箱适配的几何**只有一份实现**（:protocol 的 Scale.fitInside）——
        //   宿主算 1:1 的编码尺寸时用的是同一个函数，所以"预测的显示尺寸"与"真实视图尺寸"
        //   不可能对不上。以前这里手写一份，是会漂移的风险点。
        val fitted = Scale.fitInside(availW, availH, videoWidth, videoHeight)
        setMeasuredDimension(fitted[0], fitted[1])
    }

    /** 供 UI 显示用。 */
    fun describe(): String =
        if (videoWidth > 0) "${videoWidth}x$videoHeight" else "等待视频尺寸"

    override fun toString(): String =
        "AspectRatioSurfaceView(${describe()}, ${visibilityToString(visibility)})"

    private fun visibilityToString(v: Int): String = when (v) {
        View.VISIBLE -> "visible"
        View.INVISIBLE -> "invisible"
        else -> "gone"
    }
}
