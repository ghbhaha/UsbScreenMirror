package com.uav.host.aoa.usb

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

    /**
     * 一次 bulkTransfer 的读长度：64 KB 且必须是 maxPacketSize 的整数倍（计划 4.6）。
     *
     * ★ 10.34 从 16 KB 提到 64 KB：真机实测一帧 30~50 KB（1080p 静止画面也有 ~1.3 KB），
     *   16 KB 意味着**一帧要 3 次 `bulkTransfer`**，每次都是一次 syscall + 一次从 URB 缓冲
     *   到用户态的拷贝 + 一次调度。64 KB 通常一帧一次拿完。
     *
     * ⚠️ 与 10.16 的内核 panic **不冲突**：那条红线是"向内核申请的读长度**不得小于端点包长
     *   512B**"（MTK 主机栈不截 `actual_length`），而这里是取 `maxPacketSize` 的整数倍、
     *   只会更大。改大不会重新踩坑，但仍需复验 ramoops 无新增。
     *
     * ⚠️ 不能用「刚好一帧」这种精确尺寸：bulkTransfer 在端点有数据时就返回（允许短读），
     *   大缓冲不会增加延迟，只会让"多到的字节"进 `BufferedByteChannel` 的残留缓冲续读。
     */
    val readChunkSize: Int =
        (READ_CHUNK_TARGET / maxPacketSize.coerceAtLeast(1)) * maxPacketSize.coerceAtLeast(1)

    override fun toString(): String =
        "iface=${usbInterface.id} in=0x${Integer.toHexString(inEndpoint.address)}" +
            "(pkt=${inEndpoint.maxPacketSize}) " +
            "out=0x${Integer.toHexString(outEndpoint.address)}" +
            "(pkt=${outEndpoint.maxPacketSize})"

    companion object {
        const val TAG = "UAV-Host"

        /** 读 chunk 目标值 64 KB（计划 4.6；10.34 由 16 KB 调大，见 [readChunkSize]）。 */
        const val READ_CHUNK_TARGET = 65536

        /**
         * 该设备是否含「BULK IN + BULK OUT」端点对。
         *
         * 用途：**在"扫描/轮询"时排除掉不是手机的设备**。
         * 实测宿主内建面板（KASA-TECH CoolTouch）只有 HID 接口（class=3），
         * 而手机无论普通模式（MTP/mass_storage + vendor 接口）还是配件模式都带 BULK 端点对 ——
         * 这个判据足够把二者分开，且恰好也是 AOA 通信的硬性前提。
         */
        fun hasBulkPair(device: UsbDevice): Boolean {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var hasIn = false
                var hasOut = false
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type != android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) hasIn = true
                    else hasOut = true
                }
                if (hasIn && hasOut) return true
            }
            return false
        }

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
