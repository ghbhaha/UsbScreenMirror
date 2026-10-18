package com.uav.host.ios

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConfiguration
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
        const val ACTION_USB_PERMISSION = "com.uav.host.ios.USB_PERMISSION"
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

    private fun isCapture(itf: UsbInterface): Boolean =
        itf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && itf.interfaceSubclass == 0x2A

    /**
     * 找承载 QuickTime 采集接口（0xFF/0x2A）的那个**配置**。
     *
     * ⚠️ 只扫 `interfaceCount/getInterface()` 是不够的：那张扁平表是所有配置的接口并集，
     * 找到 0x2A 并不代表它在**当前活动配置**里 —— 直接 claim 会失败（实测 `claim 接口失败`）。
     * Windows 侧的做法是 `set_configuration(5)` 后再 claim，这里等价地先定位到那个配置。
     */
    fun captureConfiguration(dev: UsbDevice): UsbConfiguration? {
        for (i in 0 until dev.configurationCount) {
            val cfg = runCatching { dev.getConfiguration(i) }.getOrNull() ?: continue
            for (j in 0 until cfg.interfaceCount) {
                if (isCapture(cfg.getInterface(j))) return cfg
            }
        }
        return null
    }

    /** 找 QuickTime 采集接口：class 0xFF、subclass 0x2A。 */
    fun findCaptureInterface(dev: UsbDevice): UsbInterface? {
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            if (isCapture(itf)) return itf
        }
        return null
    }

    /** 从某个配置里取出 0x2A 接口（一定存在，调用前已确认）。 */
    fun captureInterfaceIn(cfg: UsbConfiguration): UsbInterface? {
        for (j in 0 until cfg.interfaceCount) {
            val itf = cfg.getInterface(j)
            if (isCapture(itf)) return itf
        }
        return null
    }

    /** 找 usbmux 接口：class 0xFF、subclass 0xFE、protocol 2（Apple 的 mux 通道）。 */
    fun muxInterfaceIn(cfg: UsbConfiguration): UsbInterface? {
        for (j in 0 until cfg.interfaceCount) {
            val itf = cfg.getInterface(j)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                itf.interfaceSubclass == 0xFE && itf.interfaceProtocol == 2
            ) {
                return itf
            }
        }
        return null
    }

    /**
     * 挑一个「普通」配置用于复原（不是承载 0x2A 的那个）。
     *
     * 设备开机默认活动配置是 **id=3**（iPhoneMirror 日志里 `active_config=3`），
     * 所以优先 3，其次 1、2，最后退而求其次选任意非采集配置。
     */
    fun normalConfiguration(dev: UsbDevice, captureId: Int): UsbConfiguration? {
        val all = (0 until dev.configurationCount).mapNotNull { i ->
            runCatching { dev.getConfiguration(i) }.getOrNull()
        }
        return all.firstOrNull { it.id == 3 && it.id != captureId }
            ?: all.firstOrNull { it.id == 1 && it.id != captureId }
            ?: all.firstOrNull { it.id != captureId }
    }

    /** 按配置 id 取配置（找不到返回 null）。只返回描述符里真实存在的，绝不凭空构造。 */
    fun configurationById(dev: UsbDevice, id: Int): UsbConfiguration? {
        for (i in 0 until dev.configurationCount) {
            val cfg = runCatching { dev.getConfiguration(i) }.getOrNull() ?: continue
            if (cfg.id == id) return cfg
        }
        return null
    }

    /** 打印配置/接口分布，用于确认「采集接口到底在哪个配置里」。 */
    fun dumpConfigs(dev: UsbDevice) {
        log("配置数 ${dev.configurationCount}，扁平接口数 ${dev.interfaceCount}")
        for (i in 0 until dev.configurationCount) {
            val cfg = runCatching { dev.getConfiguration(i) }.getOrNull() ?: continue
            val marks = (0 until cfg.interfaceCount).map { j ->
                val t = cfg.getInterface(j)
                "#${t.id}:%02x/%02x/%02x".format(t.interfaceClass, t.interfaceSubclass, t.interfaceProtocol)
            }
            log("  cfg[$i] id=${cfg.id} ifs=${cfg.interfaceCount} ${marks.joinToString(" ")}")
        }
    }

    /**
     * 把活动配置切到目标配置（公开 API，API 21+）。
     *
     * 只切**设备描述符里确实存在**的配置 —— Windows 上切到不存在的配置会让驱动栈崩掉，
     * 这里也绝不凭空构造配置对象。
     */
    fun setConfiguration(conn: UsbDeviceConnection, cfg: UsbConfiguration): Boolean {
        val r = runCatching { conn.setConfiguration(cfg) }
        val ok = r.getOrDefault(false)
        log("setConfiguration(id=${cfg.id}) => $ok" + (r.exceptionOrNull()?.let { " 异常:$it" } ?: ""))
        return ok
    }

    fun claim(conn: UsbDeviceConnection, itf: UsbInterface): Boolean {
        val r = runCatching { conn.claimInterface(itf, true) }
        val ok = r.getOrDefault(false)
        if (!ok) log("claimInterface(if#${itf.id}) => false" + (r.exceptionOrNull()?.let { " 异常:$it" } ?: ""))
        return ok
    }

    /**
     * 释放接口。
     *
     * ⚠️ 有接口被 claim 住时 `setConfiguration` 会直接返回 false（实测）——
     * 所以任何「切配置」之前都必须先 release，否则改不动配置。
     */
    fun release(conn: UsbDeviceConnection, itf: UsbInterface): Boolean {
        val ok = runCatching { conn.releaseInterface(itf) }.getOrDefault(false)
        if (!ok) log("releaseInterface(if#${itf.id}) => false")
        return ok
    }

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
