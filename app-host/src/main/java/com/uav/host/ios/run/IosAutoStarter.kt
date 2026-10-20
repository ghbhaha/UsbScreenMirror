package com.uav.host.ios.run

import android.hardware.usb.UsbDevice

/**
 * 「插上就自动开跑」的前台轮询。
 *
 * 为什么用轮询而不是等广播：**本机（P40UCH）插入时根本不派发 `USB_DEVICE_ATTACHED`**
 * （只发 DETACHED，这是已记录在案的机型缺陷）。合并前 AOA 侧的"插线即达"一直靠
 * `UsbAttachRouter` 的前台轮询，iOS 这边照同样的办法补上 —— 两边同一套判定，不各记一份。
 *
 * 只认 **deviceId 基线之外的新设备**：拔掉时把基线复位成 -1，于是"拔了再插"能再次触发；
 * 而同一次插入不会因为失败而无限重试（重试手段是拔插一次，或点「重新唤醒」）。
 */
internal class IosAutoStarter(
    private val findDevice: () -> UsbDevice?,
    /** 真的可以开始了吗（= 当前没有别的流程占着 USB）。 */
    private val isBusy: () -> Boolean,
    private val log: (String) -> Unit,
    private val onDetected: () -> Unit,
) {

    /**
     * 上一次见到的 Apple 设备 deviceId；-1 = 当前没有设备（拔掉即复位）。
     *
     * ★ `@Volatile`：`tick()` 在主线程，而采集收尾会从 IO 协程里调 [resetBaseline]
     *   让"拔了再插"能立刻重来。没有它，主线程可能一直读到旧值。
     */
    @Volatile
    private var lastSeenDeviceId = -1

    /**
     * 查一次设备表。
     *
     * 主线程调用（Handler 每 [POLL_MS] 一次），所以这里只做一次设备表遍历 + 一次判定。
     */
    fun tick() {
        val dev = findDevice()
        if (dev == null) {
            // 设备消失 ⇒ 复位基线，这样再插回来一定会重新触发
            lastSeenDeviceId = -1
            return
        }
        if (isBusy() || dev.deviceId == lastSeenDeviceId) return
        lastSeenDeviceId = dev.deviceId
        log("检测到 iPad（deviceId=${dev.deviceId}）⇒ 自动开始")
        onDetected()
    }

    /**
     * 复位基线，让"同一台设备"能被当成新插入再触发一次。
     *
     * 只在一处调用：`IosCaptureFlow.capture()` 的 finally —— 采集因为**设备侧原因**
     * 失败（一个字节都没收到 / 端点坏了）时，用户的心智是"拔插一下重来"。
     * 若不复位，`lastSeenDeviceId` 还是老值，**拔了再插也不会自动开始**，
     * 用户只能去点「重新唤醒」或者杀进程。
     */
    fun resetBaseline() {
        lastSeenDeviceId = -1
    }

    companion object {
        /**
         * 轮询周期。
         *
         * 1s 是实测过的折中：本机型插入不派发 ATTACHED（只能轮询），
         * 而 1s 足够让"插上线 → 画面出来"的感知是连续的；再密只是白耗电。
         */
        const val POLL_MS = 1000L
    }
}