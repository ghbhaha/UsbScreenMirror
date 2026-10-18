package com.uav.host.aoa.usb

import android.hardware.usb.UsbDeviceConnection
import com.uav.host.common.AndroidLogger
import com.uav.protocol.Logger
import com.uav.protocol.aoa.AoaHid
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w

/**
 * AOA **HID 注入**执行器（宿主侧；纯逻辑见 `:protocol` 的 [AoaHid] 与
 * `com.uav.protocol.input.HidTouchPanel`）。
 *
 * ⚠️ 所有方法都是**阻塞**的，必须在 worker 线程调用（红线：USB 操作不放 UI 线程）。
 *
 * ⚠️ 调用时机：必须等手机**已经进入配件模式**（链路就绪）之后，
 *    否则内核还没接管 `f_accessory`，这些请求会失败。
 *
 * ⚠️ HID 的 id **只在本次连接内有效**：拔线 / 重枚举后旧注册即失效，
 *    必须重新 [registerAndLoad]（这点要挂在自动重连流程里，
 *    见 `docs/实施计划.md` 10.8）。
 */
class HidInjector(
    private val connection: UsbDeviceConnection,
    private val logger: Logger = AndroidLogger,
    private val timeoutMs: Int = AoaHid.DEFAULT_TIMEOUT_MS,
) {

    /**
     * 注册一个 HID 设备并发送它的报告描述符。
     *
     * 对应两条控制请求：`REGISTER_HID` + 一到多条 `SET_HID_REPORT_DESC`。
     * 描述符超过 **EP0 单次可传长度**时必须分块，且**按偏移顺序**发送。
     */
    fun registerAndLoad(id: Int, descriptor: ByteArray): Boolean {
        // 54：value = id，index = 描述符总长度
        val n = connection.controlTransfer(
            AoaHid.REQTYPE_OUT_VENDOR_DEVICE,
            AoaHid.REGISTER_HID,
            id,
            descriptor.size,
            null,
            0,
            timeoutMs,
        )
        if (n < 0) {
            logger.e(TAG, "REGISTER_HID[$id] 失败 n=$n（手机是否真的在配件模式？）")
            return false
        }

        val chunkMax = ep0MaxPacketSize()
        val chunks = AoaHid.chunkDescriptor(descriptor, chunkMax)
        chunks.forEach { chunk ->
            // 56：value = id，index = 该块在描述符里的偏移
            val m = connection.controlTransfer(
                AoaHid.REQTYPE_OUT_VENDOR_DEVICE,
                AoaHid.SET_HID_REPORT_DESC,
                id,
                chunk.offset,
                chunk.bytes,
                chunk.bytes.size,
                timeoutMs,
            )
            if (m < 0) {
                logger.e(
                    TAG,
                    "SET_HID_REPORT_DESC[$id] offset=${chunk.offset} 失败 m=$m"
                )
                return false
            }
        }

        logger.i(
            TAG,
            "HID[$id] 已注册：描述符 ${descriptor.size}B，分 ${chunks.size} 块发送" +
                "（EP0 单次上限 $chunkMax B）"
        )
        return true
    }

    /** 发送一条输入报告（`SEND_HID_EVENT`：value = id，index = 0）。 */
    fun sendReport(id: Int, report: ByteArray): Boolean {
        val n = connection.controlTransfer(
            AoaHid.REQTYPE_OUT_VENDOR_DEVICE,
            AoaHid.SEND_HID_EVENT,
            id,
            0,
            report,
            report.size,
            timeoutMs,
        )
        if (n < 0) {
            logger.w(TAG, "SEND_HID_EVENT[$id] 失败 n=$n")
            return false
        }
        return true
    }

    /**
     * 注销 HID 设备（拔线后 id 也会自动失效，这里用于主动收尾）。
     *
     * @param logFailure 失败是否记警告。**清理残留**时要传 false ——
     *   扫一段 id 空间时，"这个 id 本来就不存在"是**正常结果**，不该刷警告。
     */
    fun unregister(id: Int, logFailure: Boolean = true): Boolean {
        val n = connection.controlTransfer(
            AoaHid.REQTYPE_OUT_VENDOR_DEVICE,
            AoaHid.UNREGISTER_HID,
            id,
            0,
            null,
            0,
            timeoutMs,
        )
        val ok = n >= 0
        if (!ok && logFailure) logger.w(TAG, "UNREGISTER_HID[$id] 失败 n=$n")
        return ok
    }

    /**
     * EP0 单次可传长度 = 设备描述符的 `bMaxPacketSize0`（偏移 7）。
     *
     * 不去猜 64：低速设备是 8，全速 8/16/32/64，高速 64。
     * 读不到就退回 64（最常见的值）。
     */
    fun ep0MaxPacketSize(): Int {
        val raw = try {
            connection.rawDescriptors
        } catch (t: Throwable) {
            logger.w(TAG, "读 rawDescriptors 失败：${t.message}")
            null
        }
        if (raw == null || raw.size < 8) return FALLBACK_EP0
        val v = raw[7].toInt() and 0xFF
        return if (v in 8..64) v else FALLBACK_EP0
    }

    companion object {
        const val TAG = "UAV-Host"

        /** 读不到设备描述符时的兜底 EP0 包长（高速设备固定 64）。 */
        const val FALLBACK_EP0 = 64
    }
}
