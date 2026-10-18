package com.uav.protocol.ios

/**
 * 读循环的「链路是不是已经断了」判定。
 *
 * ## 为什么需要它
 *
 * Android 的 `bulkTransfer` 对**超时**和**端点错误**都返回 -1，只能靠耗时区分。
 * 而设备被拔掉之后，它会以接近 0ms 的速度**持续**返回 -1 —— 读循环若只是 `continue`，
 * 就变成每秒上千次的空转，而且每一轮都可能往 UI 写一行日志（这一条曾把界面彻底刷死）。
 *
 * 这个类只回答一个问题：**这次失败之后，还该不该继续读？**
 *
 * ## 两条判据（缺一不可）
 *
 * 1. **设备从系统设备表消失** —— 权威判据，拔线必然命中，且快（3 次失败内）；
 * 2. **连续失败计数兜底** —— 设备还在、但端点彻底失效（STALL / 主机控制器异常）
 *    时第 1 条永远不成立，没有它就会卡在原地。
 *
 * 纯状态机、不碰 Android API：阈值边界正是"多一次就误判中断、少一次就卡死"的地方，
 * 必须在 JVM 单测里钉住。
 */
class LinkLossGate(
    /**
     * 连续快速失败达到这个次数后，`devicePresent == false` 才判定为"设备已消失"。
     *
     * 取 3：拔线后一次失败约 0~1ms，3 次不到 5ms，用户感知是"秒退"；
     * 而正常链路里瞬时错误极少连着 3 次，不会误判。
     */
    private val goneCheckAfter: Int = 3,
    /**
     * 无论设备在不在，连续失败到这里就放弃。取 8 —— 给"端点坏但设备还在"留判定余量，
     * 同时保证仍是毫秒级退出。
     */
    private val maxConsecutiveErrors: Int = 8
) {

    /** 链路中断的原因。**不带 UI 文案** —— 怎么措辞由宿主决定。 */
    enum class Cause {
        /** 设备从 USB 设备表消失：拔线 / 设备断电 */
        DEVICE_GONE,

        /** 设备还在，但端点连续读失败（STALL / 主机控制器异常） */
        ENDPOINT_DEAD,
    }

    /** 当前连续失败次数（收到数据或超时即清零）。 */
    var consecutiveErrors: Int = 0
        private set

    /**
     * 记录一次**快速失败**（耗时明显小于超时 ⇒ 不是"没数据"，而是"出错"）。
     *
     * @param devicePresent 设备是否还在 `UsbManager.deviceList` 里。
     *                      `null` = 本轮没有这个判据可用（只按计数判）。
     * @return 该放弃时返回原因；null = 可以继续读
     */
    fun onFastFailure(devicePresent: Boolean? = null): Cause? {
        consecutiveErrors++
        if (devicePresent == false && consecutiveErrors >= goneCheckAfter) return Cause.DEVICE_GONE
        if (consecutiveErrors >= maxConsecutiveErrors) return Cause.ENDPOINT_DEAD
        return null
    }

    /** 超时 = 链路还活着，只是暂时没数据。 */
    fun onTimeout() {
        consecutiveErrors = 0
    }

    /** 读到数据。 */
    fun onData() {
        consecutiveErrors = 0
    }
}
