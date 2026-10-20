package com.uav.protocol.ios

/**
 * 「这次看到设备」算不算一次**新的插入**。
 *
 * ## 为什么值得单独一个类
 *
 * 这段判定原本写在 `IosAutoStarter.tick()` 里，看着只有三行，却踩过一个真 bug：
 *
 * ```
 * 忙的时候直接 return（不动基线）
 * ⇒ 采集启动时设备被 activate 重枚举（deviceId 2011 → 2012）
 * ⇒ 用户点「停止投屏」，isBusy() 变 false
 * ⇒ 下一轮 tick 把 2012 当成"新插入" ⇒ 1 秒内自动重开
 * ```
 *
 * 实测日志（2026-10-20）：
 * `10:44:18.665 用户请求停止投屏` → `10:44:19.169 检测到 iPad（deviceId=2012）⇒ 自动开始`。
 *
 * 结论：**基线必须跟着设备走，包括"忙"的时候**。忙只代表"不许触发"，
 * 不代表"没看见它"。纯粹的状态判定，放这里就能用单元测试钉住。
 */
class InsertionBaseline {

    /** 上一次见到的设备 id；[ABSENT] = 当前没有设备。 */
    @Volatile
    var lastSeenDeviceId: Int = ABSENT
        private set

    /**
     * 设备表里**没有**设备了。
     *
     * 复位是必须的：拔掉再插回来时 deviceId 可能完全相同（同口同设备），
     * 不复位就再也不会自动开始。
     */
    fun onAbsent() {
        lastSeenDeviceId = ABSENT
    }

    /**
     * 设备表里有设备。
     *
     * @param deviceId 当前设备 id（Android 的 `UsbDevice.deviceId`，重枚举会变）
     * @param busy 是否有流程正占着 USB（有 ⇒ 只对齐基线，不触发）
     * @return `true` = 这是一次新插入，调用方可以开始
     */
    fun onObserved(deviceId: Int, busy: Boolean): Boolean {
        val previous = lastSeenDeviceId
        // ★ 先对齐再看结果：忙的时候也要记住"我已经见过这台了"
        lastSeenDeviceId = deviceId
        if (busy) return false
        return deviceId != previous
    }

    companion object {
        /** 「当前没有设备」的哨兵值（`UsbDevice.deviceId` 从 1000 起，-1 不会撞）。 */
        const val ABSENT = -1
    }
}
