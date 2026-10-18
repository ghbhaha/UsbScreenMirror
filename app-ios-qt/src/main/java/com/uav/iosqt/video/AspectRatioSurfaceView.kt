package com.uav.iosqt.video

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import com.uav.protocol.Scale

/**
 * 按视频宽高比自适应的 `SurfaceView`（与 `app-host` 的同名类同一套几何）。
 *
 * iPad 主屏是 4:3，宿主平板是 16:9 —— 直接 `match_parent` 会把画面**横向拉宽**，
 * 所以必须做信箱适配。
 *
 * 尺寸来源：解码器 `INFO_OUTPUT_FORMAT_CHANGED` 给的 `KEY_WIDTH/KEY_HEIGHT`
 * （不用自己解 SPS，那要写一遍 Exp-Golomb）。
 *
 * ⚠️ **不调 `holder.setFixedSize(w, h)`** —— 那会重建 surface，让解码器已持有的
 * `Surface` 失效（configure 之后画面全黑）。
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
        // 几何只有一份实现（:protocol 的 Scale.fitInside），避免与其它端的算法漂移
        val fitted = Scale.fitInside(availW, availH, videoWidth, videoHeight)
        setMeasuredDimension(fitted[0], fitted[1])
    }

    fun describe(): String = if (videoWidth > 0) "${videoWidth}x$videoHeight" else "等待视频尺寸"
}
