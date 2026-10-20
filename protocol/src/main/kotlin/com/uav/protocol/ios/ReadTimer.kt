package com.uav.protocol.ios

/**
 * 把「一次没读到数据的 `bulkTransfer`」分类：**正常超时** 还是 **快速失败**。
 *
 * ## 为什么必须单独有这个东西
 *
 * Android 的 `UsbDeviceConnection.bulkTransfer` 对**超时**和**端点错误**都返回 -1，
 * 唯一能区分的是**耗时**：超时的那次一定睡满了 timeout 窗口，而端点坏掉时是
 * 毫秒级返回。
 *
 * 判据原本散在两处、而且只在一处存在：
 * - `QtSession` 的读循环有（`dt >= 450`，本地魔数）；
 * - mux 保活循环**没有**，于是把「设备安静 200ms」（mux 上本来就稀疏）当成
 *   连续快速失败 ⇒ `LinkLossGate` 数到 8 就判 `ENDPOINT_DEAD`，
 *   起流 1.6 秒后 lockdown 会话被误杀（2026-10-20 实测）。
 *
 * ⇒ 抽成一处可单测的判据：**"读满窗口"永远不是错误**，与阈值是否精确无关，
 *   与"谁在调它"无关。
 */
object ReadTimer {

    /**
     * 耗时达到 timeout 的这个比例（十分之九）就算读满了窗口。
     *
     * 留 10% 余量是为了吸收调度抖动：`bulkTransfer` 返回后到 `elapsedRealtime()`
     * 之间还有 JNI 返程与线程调度，实测 500ms 的读回来常常是 497~503ms，
     * 偶尔 460ms。反过来，端点失效时是 0~1ms —— 两者差着两个数量级，
     * 这个阈值不会把真错误放过去。
     */
    private const val SLACK_PERCENT = 10

    /**
     * @param elapsedMs 这次 `bulkTransfer` 实际耗时
     * @param timeoutMs 传进去的超时（`<= 0` = 无超时语义，此时一律不算超时）
     */
    fun isTimeout(elapsedMs: Long, timeoutMs: Int): Boolean =
        timeoutMs > 0 &&
            elapsedMs * 100 >= timeoutMs.toLong() * (100 - SLACK_PERCENT)
}
