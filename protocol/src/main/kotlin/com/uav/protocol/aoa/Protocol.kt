package com.uav.protocol.aoa

import com.uav.protocol.ByteChannel

/**
 * 协议版本与全局常量。
 *
 * 说明：本模块为**纯 JVM 库**，不得引用任何 `android.*` 类型（计划 A.2-7）。
 * 所有平台相关的字节搬运由两端各自实现 `ByteChannel` 接口后注入。
 */
object Protocol {
    /** CNXN 携带的协议版本；MVP = 1，两端必须相等或取较小值。 */
    const val VERSION: Int = 1

    /** 报文头长度（24 字节，见协议冻结清单 4.1）。 */
    const val MESSAGE_HEADER_SIZE: Int = 24

    /** 报文 payload 长度上限 8 MiB（防伪造头 OOM，见 4.6）。 */
    const val MAX_PAYLOAD_SIZE: Int = 8 * 1024 * 1024

    /** 单帧码流长度上限 8 MiB（见 4.6）。 */
    const val MAX_FRAME_SIZE: Int = 8 * 1024 * 1024

    /** 单次 readFully 请求上限 64 KiB（见 4.6）。 */
    const val MAX_READ_REQUEST: Int = 64 * 1024

    /** 每流队列深度：4 帧（背压上界，见 4.7）。 */
    const val QUEUE_DEPTH: Int = 4

    /** 日志 TAG 前缀，两端一致（见项目约定）。 */
    const val TAG: String = "UAV-Protocol"
}
