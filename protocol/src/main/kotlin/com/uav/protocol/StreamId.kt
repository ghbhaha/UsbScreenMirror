package com.uav.protocol

/**
 * 流 id —— **固定常量，不区分 local/remote**（计划 4.2）。
 *
 * 初版 ADB 风格协议里流有 local/remote 两端编号，需要靠 `OPEN` 的 `arg1` 协商；
 * 但本项目的两端都是我们自己的代码，固定 id 可以**消除整整一类错位 bug**，
 * 也让 `OPEN` 的 `arg1` 得以恒为 0。
 *
 * `WRTE.arg0` 是接收端**唯一的流标识**。
 */
object StreamId {

    const val CONTROL = 0
    const val VIDEO = 1
    const val AUDIO = 2

    /** `CLSE.arg0 = 0xFFFFFFFF` 表示整条连接关闭（Int 视角即 -1）。 */
    const val CLOSE_ALL = -1

    /** MVP 实际存在的流：control + video（audio 不启用）。 */
    val MVP_STREAMS = intArrayOf(CONTROL, VIDEO)

    fun name(id: Int): String = when (id) {
        CONTROL -> "control"
        VIDEO -> "video"
        AUDIO -> "audio"
        CLOSE_ALL -> "all"
        else -> "unknown($id)"
    }

    fun isKnown(id: Int): Boolean = id == CONTROL || id == VIDEO || id == AUDIO
}
