package com.uav.protocol

/**
 * 协议错误。
 *
 * 凡是「报文头不合法 / magic 或 checksum 不符 / 长度超限 / 命令被裁剪」一律抛本异常。
 * 计划 1.2 明确要求：**不静默跳过**，否则错位会被掩盖成"间歇性断流"这类难查现象。
 */
open class ProtocolException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

/**
 * **链路失步**（字节流错位）—— [ProtocolException] 里唯一"可恢复"的一类。
 *
 * ## 为什么需要和普通协议错误分开
 *
 * 协议本身是"点对点、字节流、没有事务边界"的：一旦读指针偏了 1 个字节，
 * 之后每一条报文头都会被读成垃圾（magic 不符 ⇒ 普通 [ProtocolException] ⇒ 直接判链路死）。
 * 但错位**不是对端违规**，而是"我们开始读的位置不在报文边界上"——典型来源：
 *
 *  - 上一次会话结束时配件端还在推流，FIFO 里留着**半条报文**；
 *  - 配件端的写线程被压住（bulk write 阻塞数秒），期间它的读线程也读不到我们的新 `CNXN`；
 *  - 宿主的 `drainStaleInput()` 只能在"读超时"处停下，天然可能停在报文中间。
 *
 * 实测（RK356x 电视盒子 + HONOR MTA-AN10）：上面三条同时成立 ⇒ 每次重连都在
 * `bad magic` 上立刻失败，形成"永远连不上"的死循环（宿主侧每 330ms 重试一次）。
 *
 * 因此这一类的正确处理不是"判死"，而是**前移到下一个合法报文头继续**
 * （见 [com.uav.protocol.adb.DesyncRecovery]）。
 *
 * ⚠️ 与它相对：`AUTH`/`STLS` 被裁剪之类是**对端真的说了不该说的话**，
 * 仍然用普通 [ProtocolException]，必须判死，不能靠扫描掩盖。
 */
class DesyncException(message: String) : ProtocolException(message)
