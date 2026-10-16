package com.uav.protocol

/**
 * 注入式日志接口。
 *
 * `:protocol` 是**纯 JVM 库**，不能引用 `android.util.Log`（计划 1.8 / A.2-7），
 * 因此两端各自提供一个薄适配器注入进来：
 *  - `:app-host` / `:app-accessory` -> AndroidLogger（转发到 `android.util.Log`）
 *  - 单测 / 命令行回放 -> PrintStreamLogger
 */
interface Logger {

    fun log(level: Level, tag: String, message: String, throwable: Throwable?)

    enum class Level { V, D, I, W, E }

    companion object {
        /** 什么都不做的日志器（默认值，便于单测静默）。 */
        val NONE: Logger = object : Logger {
            override fun log(level: Level, tag: String, message: String, throwable: Throwable?) = Unit
        }
    }
}

fun Logger.v(tag: String, message: String) = log(Logger.Level.V, tag, message, null)

fun Logger.d(tag: String, message: String) = log(Logger.Level.D, tag, message, null)

fun Logger.i(tag: String, message: String) = log(Logger.Level.I, tag, message, null)

fun Logger.w(tag: String, message: String, throwable: Throwable? = null) =
    log(Logger.Level.W, tag, message, throwable)

fun Logger.e(tag: String, message: String, throwable: Throwable? = null) =
    log(Logger.Level.E, tag, message, throwable)

/** 写到标准输出/标准错误的日志器，供单测与命令行回放使用。 */
class PrintStreamLogger(
    private val minLevel: Logger.Level = Logger.Level.D,
    private val out: java.io.PrintStream = System.out,
) : Logger {

    override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        val line = "${level.name}/$tag: $message"
        if (level == Logger.Level.E) {
            System.err.println(line)
            throwable?.printStackTrace(System.err)
        } else {
            out.println(line)
            throwable?.printStackTrace(out)
        }
    }
}
