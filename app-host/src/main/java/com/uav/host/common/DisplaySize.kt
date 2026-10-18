package com.uav.host.common

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager

/** 读取宿主屏幕的真实像素尺寸（`CNXN` 的 payload 用它）。 */
object DisplaySize {

    fun of(context: Context): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            Point().also { wm.defaultDisplay.getRealSize(it) }
        }
    }
}
