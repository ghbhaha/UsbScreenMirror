package com.uav.host.aoa.capture.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.uav.host.common.AndroidLogger
import com.uav.protocol.Logger
import com.uav.protocol.aoa.AccessoryIdentity
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AOA（Android Open Accessory）v2 控制请求常量与握手实现（计划 2.3~2.5）。
 *
 * ⚠️ 三条红线（计划「踩坑红线」）：
 *  1. **timeout 严禁传 0** —— 0 在 USB 语义里是**无限等待**，会挂死线程。
 *  2. 控制请求在 `openDevice()` 之后**直接发，不要先 claimInterface**
 *     （配件模式下接口尚未就绪，先 claim 会失败）。
 *  3. **`SEND_STRING` 的字符串必须是 UTF-8 + 单个 `\0`**。
 *     ⚠️ 这条曾写错成"UTF-16LE"，导致手机上身份只剩首字符
 *     （`manufacturer=U model=u version=1`）→ 与 `accessory_filter.xml` 不匹配
 *     → 系统不派发 → `openAccessory` 拿不到权限 → 宿主永远等不到 CNXN。
 *     编码实现在 `:protocol` 的 `AccessoryIdentity.encodeSendString`，并有单测钉住。
 */
object Aoa {

    const val REQUEST_GET_PROTOCOL_VERSION = 51
    const val REQUEST_SEND_STRING = 52
    const val REQUEST_START = 53

    // HID 注入（路线图 P1 输入通道）。常量与语义见 `:protocol` 的 [AoaHid]，
    // 执行器是本包的 [HidInjector]。
    const val REQUEST_REGISTER_HID = 54
    const val REQUEST_UNREGISTER_HID = 55
    const val REQUEST_SET_HID_REPORT_DESC = 56
    const val REQUEST_SEND_HID_EVENT = 57

    /** control transfer 的 bRequestType。 */
    const val REQTYPE_IN_VENDOR_DEVICE = 0xC0
    const val REQTYPE_OUT_VENDOR_DEVICE = 0x40

    /** 配件模式的宿主扮演 Google 的 VID。 */
    const val VID_GOOGLE = 0x18D1

    /** 纯配件模式。 */
    const val PID_ACCESSORY = 0x2D00

    /** 配件 + ADB 模式（adb 已打开时会出现）。 */
    const val PID_ACCESSORY_ADB = 0x2D01

    fun isAccessoryPid(pid: Int): Boolean = pid == PID_ACCESSORY || pid == PID_ACCESSORY_ADB

    fun pidName(pid: Int): String = when (pid) {
        PID_ACCESSORY -> "0x2D00(accessory)"
        PID_ACCESSORY_ADB -> "0x2D01(accessory+adb)"
        else -> String.format("0x%04X", pid)
    }

    /** 单段字符串长度上限（AOA 规范限制 255 字符）。 */
    const val MAX_STRING_LENGTH = 255
}

/**
 * AOA 握手执行器。所有方法都是**阻塞**的，
 * 必须在 worker 线程调用（计划 2.11：UI 线程做 USB 操作会 ANR）。
 */
class AoaHandshake(
    private val usbManager: UsbManager,
    private val logger: Logger = com.uav.host.common.AndroidLogger,
    private val controlTimeoutMs: Int = 1000,
) {

    /**
     * 51 号请求：读配件协议版本。
     *
     * @return 版本号；返回 0 表示设备不支持 AOA
     */
    fun getProtocolVersion(device: UsbDevice, connection: UsbDeviceConnection): Int {
        val buf = ByteArray(2)
        val n = connection.controlTransfer(
            Aoa.REQTYPE_IN_VENDOR_DEVICE,
            Aoa.REQUEST_GET_PROTOCOL_VERSION,
            0,
            0,
            buf,
            buf.size,
            controlTimeoutMs,
        )
        if (n < 0) {
            logger.w(TAG, "GET_PROTOCOL_VERSION failed n=$n")
            return 0
        }
        // 小端 u16
        val version = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
        logger.i(TAG, "AOAv2 version=$version (raw=$n bytes)")
        return version
    }

    /**
     * 52 号请求 ×6：上报配件身份。
     *
     * ⚠️ manufacturer / model / version 三者必须与手机端 `accessory_filter.xml` 完全一致，
     * 因此这里**只从 [AccessoryIdentity] 取值**，绝不手写字符串。
     */
    fun sendStrings(device: UsbDevice, connection: UsbDeviceConnection): Boolean {
        AccessoryIdentity.SEND_STRING_ORDER.forEachIndexed { index, value ->
            if (!sendString(connection, index, value)) {
                logger.e(TAG, "SEND_STRING[$index]='$value' failed")
                return false
            }
        }
        logger.i(
            TAG,
            "SEND_STRING x6 ok: manufacturer=${AccessoryIdentity.MANUFACTURER} " +
                "model=${AccessoryIdentity.MODEL} version=${AccessoryIdentity.VERSION}",
        )
        return true
    }

    /** 52 号请求单段：**UTF-16LE 且含结尾 `\0`**（编码实现见 [AccessoryIdentity.encodeSendString]）。 */
    fun sendString(connection: UsbDeviceConnection, index: Int, value: String): Boolean {
        val bytes = AccessoryIdentity.encodeSendString(value)
        val n = connection.controlTransfer(
            Aoa.REQTYPE_OUT_VENDOR_DEVICE,
            Aoa.REQUEST_SEND_STRING,
            0,
            index,
            bytes,
            bytes.size,
            controlTimeoutMs,
        )
        val ok = n >= 0
        if (!ok) logger.w(TAG, "SEND_STRING[$index] returned $n")
        return ok
    }

    /**
     * 53 号请求：切换到配件模式。
     *
     * ⚠️ 调用后手机将**重枚举**，旧 [UsbDeviceConnection] 立即失效，
     * 必须马上 `close()`（计划 2.5）。
     */
    fun startAccessory(connection: UsbDeviceConnection): Boolean {
        val n = connection.controlTransfer(
            Aoa.REQTYPE_OUT_VENDOR_DEVICE,
            Aoa.REQUEST_START,
            0,
            0,
            null,
            0,
            controlTimeoutMs,
        )
        logger.i(TAG, "START_ACCESSORY sent (raw=$n)")
        // 注意：部分机型返回 -1 但实际已切换（连接被断开导致传输失败），
        // 因此这里不做硬判失败，交由后续"等待重枚举"来确认。
        return true
    }

    /**
     * 完整握手：51 → 52×6 → 53。
     *
     * @return 协商到的协议版本（<2 视为失败，调用方应报错）
     */
    fun handshake(device: UsbDevice, connection: UsbDeviceConnection): Int {
        val version = getProtocolVersion(device, connection)
        if (version < 2) {
            logger.e(TAG, "设备不支持 AOAv2 (version=$version)")
            return version
        }
        if (!sendStrings(device, connection)) return 0
        startAccessory(connection)
        return version
    }

    /**
     * 等待手机重枚举为配件设备（计划 2.6：**200ms × 25 次 = 5s**）。
     *
     * ★ 按 **PID 0x2D00/0x2D01** 识别，**绝不能按 interface class**
     *   —— 配件接口与 adb 接口的 class/subclass/protocol 同为 255/66/1，无法区分。
     *   （实测本机配件接口是 `class=255 sub=255 proto=0`，更说明按 class 判断不可靠。）
     *
     * ★ **必须要求 `deviceId != previousDeviceId`**：设备本来就已经是配件模式时
     *   （强制重握手场景），不过滤会立刻返回**同一个旧设备**，导致拿到的还是旧连接。
     *
     * @param previousDeviceId 切换前那台设备的 deviceId
     * @param allowSameDevice  若 5s 内只看到旧设备，是否允许退而求其次返回它
     */
    fun awaitAccessoryDevice(
        previousDeviceId: Int,
        timeoutMs: Long = 5000,
        intervalMs: Long = 200,
        allowSameDevice: Boolean = false,
    ): UsbDevice? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempts = 0
        var staleCandidate: UsbDevice? = null

        while (System.currentTimeMillis() < deadline) {
            attempts++
            val accessories = usbManager.deviceList.values.filter { Aoa.isAccessoryPid(it.productId) }

            // 优先取"新出现的那台"
            val fresh = accessories.firstOrNull { it.deviceId != previousDeviceId }
            if (fresh != null) {
                logger.i(
                    TAG,
                    "re-enumerated as accessory after $attempts polls: " +
                        "VID=0x${Integer.toHexString(fresh.vendorId).uppercase()} " +
                        "PID=${Aoa.pidName(fresh.productId)} deviceId=${fresh.deviceId} " +
                        "(prev=$previousDeviceId)"
                )
                return fresh
            }
            if (accessories.isNotEmpty()) staleCandidate = accessories.first()

            try {
                Thread.sleep(intervalMs)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }

        if (staleCandidate != null && allowSameDevice) {
            logger.w(
                TAG,
                "5s 内没等到新的配件设备 id（旧 id=$previousDeviceId 仍在），" +
                    "按 allowSameDevice 复用它 deviceId=${staleCandidate.deviceId}"
            )
            return staleCandidate
        }

        logger.e(
            TAG,
            "等待重枚举超时 ${timeoutMs}ms（$attempts 次轮询）；" +
                "当前设备: ${describeDevices()}"
        )
        return null
    }

    fun describeDevices(): String = usbManager.deviceList.values.joinToString("; ") { d ->
        "id=${d.deviceId} VID=0x${Integer.toHexString(d.vendorId)} " +
            "PID=0x${Integer.toHexString(d.productId)} ${d.productName}"
    }

    companion object {
        const val TAG = "UAV-Host"

        /** 把字符串按 AOA 要求编码为 UTF-16LE + `\0`（实现在 :protocol，便于单测）。 */
        fun encodeString(value: String): ByteArray = AccessoryIdentity.encodeSendString(value)

        /** 小端 u16 读取（协议版本是小端）。 */
        fun u16LE(b: ByteArray): Int = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }
}
