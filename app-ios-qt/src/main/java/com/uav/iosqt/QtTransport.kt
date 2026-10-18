package com.uav.iosqt

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * Android 侧的 USB 传输层。
 *
 * 只做四件事：找设备 / 激活 / 找采集接口 / 读写 bulk。
 * 刻意不做（在 Windows 上验证过都是多余的，甚至有害）：
 *  - 不做 `setConfiguration(0) → setConfiguration(5)` 的双配置来回；
 *  - 不 `clearHalt`；
 *  - 不发明文控制 kick。
 */
internal class QtTransport(
    private val context: Context,
    private val usb: UsbManager,
    private val log: (String) -> Unit
) {

    companion object {
        const val APPLE_VID = 0x05AC
        const val IPAD_PID = 0x12AB
        const val ACTION_USB_PERMISSION = "com.uav.iosqt.USB_PERMISSION"
        const val CTRL_TIMEOUT_MS = 3000
    }

    /** 找 Apple 设备（不区分配置态）。 */
    fun findAppleDevice(): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == APPLE_VID }

    fun hasPermission(dev: UsbDevice): Boolean = usb.hasPermission(dev)

    fun requestPermission(dev: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
        )
        log("请求 USB 权限：${dev.deviceName}")
        usb.requestPermission(dev, pi)
    }

    fun open(dev: UsbDevice): UsbDeviceConnection? {
        val conn = usb.openDevice(dev)
        if (conn == null) log("openDevice 返回 null（通常是权限问题）")
        return conn
    }

    /**
     * 激活：`controlTransfer(0x40, 0x52, wValue=0, wIndex=2, no data)`。
     * 返回 true 仅代表设备 ack 了请求，**重枚举可能延迟数十秒**。
     */
    fun activate(conn: UsbDeviceConnection): Boolean {
        val r = conn.controlTransfer(0x40, 0x52, 0, 2, null, 0, CTRL_TIMEOUT_MS)
        log("activate => $r")
        return r >= 0
    }

    /**
     * 找 QuickTime 采集接口：class 0xFF、subclass 0x2A。
     *
     * 用扁平的 `interfaceCount/getInterface()`（公开 API）而不是 `getConfiguration(int)`——
     * Android 的 `UsbDevice` 没有无参 `getConfiguration()`，扁平列表本身就覆盖所有配置的接口。
     * 普通态约 12 个接口（无 0x2A），激活后约 18 个（含 0x2A）。
     */
    fun findCaptureInterface(dev: UsbDevice): UsbInterface? {
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                itf.interfaceSubclass == 0x2A
            ) {
                return itf
            }
        }
        return null
    }

    fun claim(conn: UsbDeviceConnection, itf: UsbInterface): Boolean =
        runCatching { conn.claimInterface(itf, true) }.getOrDefault(false)

    /** 从接口里挑出 bulk IN / OUT。 */
    fun bulkEndpoints(itf: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) {
                if (epIn == null) epIn = ep
            } else if (epOut == null) {
                epOut = ep
            }
        }
        return epIn to epOut
    }

    fun read(conn: UsbDeviceConnection, ep: UsbEndpoint, buf: ByteArray, timeoutMs: Int): Int =
        conn.bulkTransfer(ep, buf, buf.size, timeoutMs)

    fun write(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray, timeoutMs: Int): Int =
        conn.bulkTransfer(ep, data, data.size, timeoutMs)
}
