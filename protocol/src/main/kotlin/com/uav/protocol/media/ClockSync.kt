package com.uav.protocol.media

/**
 * 两端时钟的一次标定结果（计划 4.10：端到端延迟测量）。
 *
 * **为什么必须标定**：宿主想知道「这一帧从手机采集到宿主显示花了多久」，
 * 但两台设备的单调时钟**起点不同**，直接相减得到的是「真实延迟 + 未知的时钟偏移」，
 * 无法分离。
 *
 * 做法与 NTP 同理 —— 用一次双向往返估出偏移。设
 *
 *  - `t0` = 本机发出请求的时刻（本机时钟）
 *  - `t3` = 本机收到应答的时刻（本机时钟）
 *  - `tr` = 对端**发出应答**的时刻（对端时钟）
 *
 * 则 `rtt = t3 - t0`。若假设往返路径对称，对端发出应答的瞬间，
 * 本机时钟约为 `t0 + rtt/2`，于是
 *
 * ```
 * offset = tr - (t0 + rtt/2)        含义：本机 = 对端 - offset
 * ```
 *
 * 本项目用 `CNXN` 完成这一次往返（USB bulk 明文，实测单程 1~3 ms），
 * 因此标定不确定度是 **毫秒级**（[uncertaintyNanos]），足以判定 4.10 的「≤150ms」。
 *
 * ⚠️ **这是一次性标定，不是长期同步**：
 * 两端晶振的日漂移是 ppm 级（一天差几十毫秒），对本场景无影响；
 * 但**手机重启会让单调时钟归零**，所以每次建链都必须重新标定
 * （[com.uav.host.session.HostSession] 就是这么做的）。
 */
class ClockOffset(
    /** 对端读数与本机读数之差：`本机 = 对端 - offset`。 */
    val offsetNanos: Long,
    /** 标定时的往返时延。 */
    val rttNanos: Long,
) {

    /** 标定不确定度的上界（往返不对称时最坏情况）。 */
    val uncertaintyNanos: Long get() = rttNanos / 2

    /** 把**对端**时钟读数换算成**本机**时钟读数。 */
    fun toLocalNanos(remoteNanos: Long): Long = remoteNanos - offsetNanos

    override fun toString(): String =
        "ClockOffset(offset=${offsetNanos / 1_000_000}ms, rtt=${rttNanos / 1_000_000}ms, " +
            "±${uncertaintyNanos / 1_000_000}ms)"
}

object ClockSync {

    /**
     * 用一次请求 / 应答估算时钟偏移。
     *
     * @param localSendNanos 本机发出请求的时刻（本机时钟）
     * @param localRecvNanos 本机收到应答的时刻（本机时钟）
     * @param remoteNanos    对端**发出应答**那一刻的对端时钟读数
     */
    fun estimate(localSendNanos: Long, localRecvNanos: Long, remoteNanos: Long): ClockOffset {
        // 时钟回拨或调用方传反时不要算出负 RTT，否则 offset 会离谱
        val rtt = (localRecvNanos - localSendNanos).coerceAtLeast(0L)
        val localAtRemoteSend = localSendNanos + rtt / 2
        return ClockOffset(
            offsetNanos = remoteNanos - localAtRemoteSend,
            rttNanos = rtt,
        )
    }
}
