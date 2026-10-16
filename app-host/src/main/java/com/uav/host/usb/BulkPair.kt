package com.uav.host.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.uav.protocol.Logger
import com.uav.protocol.i
import com.uav.protocol.w

/**
 * 配件模式下的接口与端点选择（计划 2.8）。
 *
 * ⚠️ 端点地址**不要写死** —— 不同机型可能不同（常见的是 `in=0x81 / out=0x02`，
 * 但也见过 `0x81/0x01`）。这里按 `direction + type==BULK` 遍历选定，**并把结果打进日志**，
 * 联调时先看日志确认，再决定是否需要特例。
 */
class BulkPair(
    val usbInterface: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
) {

    val maxPacketSize: Int get() = maxOf(inEndpoint.maxPacketSize, outEndpoint.maxPacketSize)

    /** 一次 bulkTransfer 的读长度：16 KB 且必须是 maxPacketSize 的整数倍（计划 4.6）。 */
    val readChunkSize: Int =
        (READ_CHUNK_TARGET / maxPacketSize.coerceAtLeast(1)) * maxPacketSize.coerceAtLeast(1)

    override fun toString(): String =
        "iface=${usbInterface.id} in=0x${Integer.toHexString(inEndpoint.address)}" +
            "(pkt=${inEndpoint.maxPacketSize}) " +
            "out=0x${Integer.toHexString(outEndpoint.address)}" +
            "(pkt=${outEndpoint.maxPacketSize})"

    companion object {
        const val TAG = "UAV-Host"

        /** 读 chunk 目标值 16 KB（计划 4.6）。 */
        const val READ_CHUNK_TARGET = 16384

        /**
         * 选择接口与端点。
         *
         * AOA 的配件接口是设备上的**第一个接口**（index 0），且只有一个 bulk in + 一个 bulk out。
         */
        fun find(device: UsbDevice, logger: Logger): BulkPair? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null

                val described = StringBuilder()
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    described.append(
                        " [${e}] addr=0x${Integer.toHexString(ep.address)} " +
                            "type=${ep.type} dir=${ep.direction} pkt=${ep.maxPacketSize}"
                    )
                    if (ep.type != android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                        if (inEp == null) inEp = ep
                    } else {
                        if (outEp == null) outEp = ep
                    }
                }
                logger.i(
                    TAG,
                    "interface[$i] id=${iface.id} class=${iface.interfaceClass} " +
                        "sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol}" +
                        " endpoints:$described"
                )
                if (inEp != null && outEp != null) {
                    val pair = BulkPair(iface, inEp, outEp)
                    logger.i(TAG, "selected bulk pair: $pair")
                    return pair
                }
            }
            logger.w(TAG, "未找到同时含 BULK IN/OUT 的接口")
            return null
        }
    }
}

/**
 * 在已打开的设备连接上 claim 接口并包成可用的端点对（计划 2.8 / 2.10）。
 *
 * @return 成功返回 [BulkPair]；失败返回 null
 */
fun claimBulkPair(
    connection: UsbDeviceConnection,
    device: UsbDevice,
    logger: Logger,
): BulkPair? {
    val pair = BulkPair.find(device, logger) ?: return null
    pair.usbInterface.let { iface ->
        // 幂等：某些机型需要先 release 再 claim
        connection.releaseInterface(iface)
        val ok = connection.claimInterface(iface, true)
        if (!ok) {
            logger.w(
                TAG,
                "claimInterface(${iface.id}) 失败 —— " +
                    "常见原因：接口已被内核驱动占用 / 权限未授予"
            )
            return null
        }
    }
    logger.i(TAG, "interface claimed, in=0x${Integer.toHexString(pair.inEndpoint.address)} " +
        "out=0x${Integer.toHexString(pair.outEndpoint.address)}")
    return pair
}

private const val TAG = BulkPair.TAG
