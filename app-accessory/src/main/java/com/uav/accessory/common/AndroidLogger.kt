package com.uav.accessory.common

import android.util.Log
import com.uav.protocol.Logger

/**
 * `:protocol` 的日志接口在 Android 侧的适配器（与宿主侧同构）。
 *
 * `:protocol` 是纯 JVM 库不能引用 `android.util.Log`，故两端各提供一个薄适配器注入。
 */
object AndroidLogger : Logger {

    override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            Logger.Level.V -> Log.v(tag, message, throwable)
            Logger.Level.D -> Log.d(tag, message, throwable)
            Logger.Level.I -> Log.i(tag, message, throwable)
            Logger.Level.W -> Log.w(tag, message, throwable)
            Logger.Level.E -> Log.e(tag, message, throwable)
        }
    }
}
