package com.uav.host

import android.content.Context

/**
 * 宿主侧持久化偏好。
 *
 * 目前只有一项 —— **像素模式**（1:1 像素映射 ↔ 高清旧口径，10.17）。
 * 放持久化是因为它决定 `OPEN` 时协商的 `max_size`，用户选一次后应当记住，
 * 不该每次重连都回默认值。
 */
internal object HostPrefs {

    private const val FILE = "uav_host"
    private const val KEY_ONE_TO_ONE = "one_to_one_pixels"

    /**
     * 是否启用 **1:1 像素映射**（默认开）。
     *
     * - `true`：编码尺寸 = 画面在宿主显示区里适配后的大小（省编解码算力，但无超采样）。
     * - `false`：退回旧口径（按面板长边编高分辨率，宿主端缩下去 = 效果上超采样，更锐）。
     */
    fun oneToOne(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONE_TO_ONE, true)

    fun setOneToOne(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONE_TO_ONE, enabled).apply()
    }
}
