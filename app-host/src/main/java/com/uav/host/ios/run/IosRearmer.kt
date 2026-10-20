package com.uav.host.ios.run

import android.hardware.usb.UsbDevice
import com.uav.host.ios.capture.IosFeatures
import com.uav.host.ios.capture.QtTransport
import kotlinx.coroutines.delay

/**
 * 「重新武装 QT 端点」：配置 5 → 3 → `activate` → **等设备自己重枚举**。
 *
 * 什么时候需要它：lockdown 已经信任、QT 接口也 claim 到了，但**一个字节都不来**。
 * 这时说明设备侧的 QT 服务没被"启动" —— 只有 `activate` 让设备走一次 USB 重枚举，
 * 它才会把带 0x2A 的配置**主动交出来**；单纯的配置来回（3→5）只对"刚武装过"的设备有效。
 *
 * ⚠️ activate 之后**绝不能再碰任何配置**，一碰就打断重枚举（踩过：以为没反应，实际是被自己搅黄的）。
 */
internal class IosRearmer(
    private val transport: QtTransport,
    private val log: (String) -> Unit,
    private val ensurePermission: suspend (UsbDevice) -> Boolean,
    private val restoreToNormal: (UsbDevice) -> Boolean,
    /** 用户已经要求停止（停止按钮 / 返回键）。等待循环每 700ms 问一次。 */
    private val isStopRequested: () -> Boolean,
) {

    /**
     * 跑一次重新武装。
     *
     * ★ 整条链路都是挂起函数：等待重枚举期间**不占任何线程**（`delay(POLL_MS)`），
     *   以前是一条具名线程 `Thread.sleep` 撑满 4 分钟。
     *
     * @return 结论，调用方决定要不要再动界面
     */
    suspend fun run(maxSeconds: Int = WAIT_SECONDS): Result {
        val dev0 = transport.findAppleDevice()
        if (dev0 == null) {
            log("未找到 Apple 设备，先插线")
            return Result.NO_DEVICE
        }
        if (!ensurePermission(dev0)) {
            log("未取得 USB 权限，中止")
            return Result.NO_PERMISSION
        }
        log("起始状态：${dev0.deviceName} 描述配置数=${dev0.configurationCount}")

        if (dev0.configurationCount >= IosFeatures.CAPTURE_CONFIG_COUNT) {
            log("先把设备切回普通配置，让 activate 有意义…")
            // ★ 切不回去就别 activate 了：activate 只对"已离开采集态"的设备有效，
            //   硬发只会白等 maxSeconds。
            if (!restoreToNormal(dev0)) {
                log("✗ 切回普通配置失败 ⇒ 重新武装中止（先检查线/权限）")
                return Result.RESTORE_FAILED
            }
            delay(800)
        }
        val dev = transport.findAppleDevice()
        if (dev == null) {
            log("切配置后设备消失，请重插")
            return Result.NO_DEVICE
        }
        if (!ensurePermission(dev)) {
            log("切配置后未取得权限")
            return Result.NO_PERMISSION
        }

        val conn = transport.open(dev)
        if (conn == null) {
            log("openDevice 失败")
            return Result.OPEN_FAILED
        }
        try {
            if (!transport.activate(conn)) {
                log("activate 被拒")
                return Result.ACTIVATE_REJECTED
            }
        } finally {
            runCatching { conn.close() }
        }
        log("已发 activate，等设备重枚举（最长 ${maxSeconds}s，期间不要拔线/不要点其他按钮）…")
        return waitForReenumeration(dev, maxSeconds)
    }

    /**
     * 等设备重枚举。
     *
     * 判据：配置集必须**先离开 5 再回到 5**。只看「>=5」会把"没动"误判成"已就绪"。
     */
    private suspend fun waitForReenumeration(dev: UsbDevice, maxSeconds: Int): Result {
        val t0 = System.currentTimeMillis()
        var sawLeave = false
        var lastReport = 0L
        var current = dev
        // ★ 循环要认停止信号：否则用户点返回/停止后，它还会占着 USB 最长 4 分钟
        //   （每 700ms 查一次设备表），而 Activity 已经没了。
        while (!isStopRequested() && System.currentTimeMillis() - t0 < maxSeconds * 1000L) {
            val d = transport.findAppleDevice()
            if (d != null) {
                current = d
                if (d.configurationCount < IosFeatures.CAPTURE_CONFIG_COUNT) sawLeave = true
                if (sawLeave && d.configurationCount >= IosFeatures.CAPTURE_CONFIG_COUNT) {
                    log("★★ 重枚举完成：${d.deviceName} 配置数=${d.configurationCount} ⇒ 现在可以点「开始采集」")
                    return Result.READY
                }
            }
            val elapsed = (System.currentTimeMillis() - t0) / 1000
            if (elapsed - lastReport >= 10) {
                lastReport = elapsed
                log(
                    "  等待中 ${elapsed}s… ${current.deviceName} 描述配置数=${current.configurationCount}" +
                        if (sawLeave) "（已离开采集态，等它回来）" else "（还没动静）"
                )
            }
            delay(POLL_MS)
        }
        return when {
            isStopRequested() -> {
                log("重新武装：已收到停止信号，提前退出等待")
                Result.STOPPED
            }
            sawLeave -> {
                log("✗ 设备离开采集态后 ${maxSeconds}s 内没回来")
                Result.TIMEOUT
            }
            else -> {
                log("✗ ${maxSeconds}s 内设备根本没重枚举 ⇒ 只能拔插线或重启 iPad")
                Result.NOT_ENUMERATED
            }
        }
    }

    enum class Result {
        /** 配置重新回到 5 —— 可以开始采集了 */
        READY,
        NO_DEVICE,
        NO_PERMISSION,

        /** 设备还在采集态，但切不回普通配置 ⇒ activate 没有意义 */
        RESTORE_FAILED,
        OPEN_FAILED,
        ACTIVATE_REJECTED,
        /** 用户中途点了停止 / 返回 */
        STOPPED,
        /** 离开过采集态，但没回来 */
        TIMEOUT,
        /** 从头到尾没动静 */
        NOT_ENUMERATED,
    }

    private companion object {
        // 采集态判据统一在 IosFeatures.CAPTURE_CONFIG_COUNT，这里不再自带一份。

        /** 等重枚举的上限。activate 之后实测可能延迟 2~3 分钟。 */
        const val WAIT_SECONDS = 240

        /** 查设备表的周期。700ms 是实测的折中：足够密，又不至于把 USB 栈刷爆。 */
        const val POLL_MS = 700L
    }
}
