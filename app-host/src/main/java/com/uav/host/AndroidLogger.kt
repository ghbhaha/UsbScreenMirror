package com.uav.host

import android.util.Log
import com.uav.protocol.Logger

/**
 * `:protocol` 的日志接口在 Android 侧的适配器。
 *
 * `:protocol` 是纯 JVM 库不能引用 `android.util.Log`（计划 A.2-7），
 * 因此由两端各自提供这样一个约 15 行的薄适配器注入进去。
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
