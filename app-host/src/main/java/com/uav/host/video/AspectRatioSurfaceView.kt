package com.uav.host.video

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.View

/**
 * 按视频宽高比自适应的 `SurfaceView`（计划 4.7）。
 *
 * **MVP 明确不调 `holder.setFixedSize(w, h)`** —— 那会重建 surface，
 * 让解码器已持有的 `Surface` 失效，属于隐蔽的时序坑（`configure` 后画面全黑或直接报错）。
 * 代价只是轻微重采样；1:1 像素映射列入路线图 P1。
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

        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val containerRatio = availW.toFloat() / availH.toFloat()

        if (containerRatio > videoRatio) {
            // 容器更宽 -> 受高度约束
            setMeasuredDimension((availH * videoRatio).toInt().coerceAtLeast(1), availH)
        } else {
            // 容器更高 -> 受宽度约束
            setMeasuredDimension(availW, (availW / videoRatio).toInt().coerceAtLeast(1))
        }
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
