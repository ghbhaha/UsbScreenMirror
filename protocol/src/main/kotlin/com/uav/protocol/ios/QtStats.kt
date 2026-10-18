package com.uav.protocol.ios

/**
 * QuickTime 会话的**报文级计数**。
 *
 * 原来定义在宿主的 `QtSession` 里，但它是纯数据、且协议核心要自己维护 ——
 * 放进协议层后，单测可以直接断言"喂了 N 个 CWPA，`cwpa == N`"。
 *
 * `bytes` / `feedBytes` 由读循环累加（不是协议核心的职责），保留在这里是为了
 * 让会话只暴露一个统计对象。
 */
data class QtStats(
    var ping: Int = 0,
    var cwpa: Int = 0,
    var cvrp: Int = 0,
    var afmt: Int = 0,
    var og: Int = 0,
    var skew: Int = 0,
    var eat: Int = 0,
    var audioBytes: Long = 0,
    var clok: Int = 0,
    var time: Int = 0,
    var feed: Int = 0,
    var other: Int = 0,
    /** 收到的原始字节数（读循环填） */
    var bytes: Long = 0,
    /** FEED 报文累计字节数 */
    var feedBytes: Long = 0,
)
