package com.uav.host.ios.run

import android.hardware.usb.UsbDevice
import com.uav.protocol.ios.InsertionBaseline

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
     * 「上一次见到的是哪台设备」。
     *
     * 判定本身在 [InsertionBaseline]（`:protocol`，可单测）—— 这里的 bug 类型
     * （忙的时候要不要对齐基线）用真机发现代价太高，见那个类的注释。
     */
    private val baseline = InsertionBaseline()

    /**
     * 查一次设备表。
     *
     * 主线程调用（Handler 每 [POLL_MS] 一次），所以这里只做一次设备表遍历 + 一次判定。
     */
    fun tick() {
        val dev = findDevice()
        if (dev == null) {
            // 设备消失 ⇒ 复位基线，这样再插回来一定会重新触发
            baseline.onAbsent()
            return
        }
        // 忙：只对齐基线，绝不触发（同一时刻只能有一个流程碰 USB）。
        // ★ 对齐这一步不能省：采集启动时 `activate` 会把设备重枚举成新 deviceId，
        //   不跟的话收尾后那个 id 会被当成"刚插入"⇒ 停止后 1 秒内自动重开。
        if (!baseline.onObserved(dev.deviceId, busy = isBusy())) return
        log("检测到 iPad（deviceId=${dev.deviceId}）⇒ 自动开始")
        onDetected()
    }

    /**
     * 复位基线，让"同一台设备"能被当成新插入再触发一次。
     *
     * ⚠️ **只该在设备真的不在了的时候调用**（拔线 / 重枚举中）。设备还在却复位，
     * 下一轮 tick（1 秒后）就会立刻重开一轮 —— 失败 → 复位 → 重开，变成自动重试死循环。
     * 调用点的判据见 `IosActivity.finishRun`。
     */
    fun resetBaseline() {
        baseline.onAbsent()
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