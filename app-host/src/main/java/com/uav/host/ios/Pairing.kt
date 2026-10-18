package com.uav.host.ios

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import com.uav.protocol.ios.CertKit
import com.uav.protocol.ios.Lockdown
import com.uav.protocol.ios.PValue
import com.uav.protocol.ios.PairRecord
import java.io.File

/**
 * 让 App **自己**成为一台被 iPad 信任的主机（不再从 PC 导凭证）。
 *
 * ## 为什么走这一步
 *
 * QT 推流的硬前置是「有一台受信任主机维持 lockdown 会话」。此前用的是从 PC 复制过来的
 * 配对记录，能用但不可交付 —— 用户不可能为了投屏去电脑上导证书。
 *
 * ## 与 PC 成功实现的对照
 *
 * 字段/类型逐项照 `pymobiledevice3/lockdown.py::pair`（2026-10-18 在真机上跑通的那版）：
 *
 * 1. 先 `GetValue(DevicePublicKey)` —— 拿设备公钥；
 * 2. 再 `GetValue()` 全量 —— 取 `WiFiAddress`（**设备的** MAC，必须放进请求）；
 * 3. `CertKit.generate()` 出 root / host / device 三张 PEM + 两把私钥；
 * 4. `Pair` 请求；设备弹「信任此电脑？」，用户点信任后回 `EscrowBag`；
 * 5. 存盘，下次直接当身份用。
 *
 * ## 三个只在真机上才会暴露的点
 *
 * - **`PairingDialogResponsePending` 要重试**：请求发出时人还没点，设备先回这个错误，
 *   必须每秒重发一次（PC 实现也是这样），当成失败就永远配不上；
 * - **必须在普通配置下做**，不要为了配对先 activate 到采集配置 —— 多一步重枚举纯属自找；
 * - **配对成功后不需要立刻 StartSession**：`EscrowBag` 就是成功凭据，后续会话照常走。
 */
internal class Pairing(
    private val transport: QtTransport,
    private val filesDir: File,
    private val log: (String) -> Unit
) {

    companion object {
        /** 显示在设备「信任此电脑？」弹窗里的名字。 */
        const val HOST_NAME = "UsbScreenMirror"

        /** 等用户点「信任」的上限。 */
        private const val USER_ACCEPT_TIMEOUT_MS = 90_000L

        fun recordFile(filesDir: File): File = File(filesDir, Identity.PAIR_FILE)

        fun hasRecord(filesDir: File): Boolean = recordFile(filesDir).let { it.exists() && it.length() > 0 }

        fun loadRecord(filesDir: File): PairRecord? =
            runCatching { PairRecord.decode(recordFile(filesDir).readText()) }.getOrNull()
    }

    /**
     * 走完一次配对。
     *
     * @return 成功时返回记录（**已写盘**）；失败返回 null 并把原因打到日志
     */
    fun run(dev: UsbDevice): PairRecord? {
        val cfg = muxConfiguration(dev)
        if (cfg == null) {
            log("✗ 没有任何配置含 usbmux 接口（0xFF/0xFE/02）")
            return null
        }
        val itf = transport.muxInterfaceIn(cfg)
        if (itf == null) {
            log("✗ 配置 id=${cfg.id} 里找不到 usbmux 接口")
            return null
        }

        val conn = transport.open(dev)
        if (conn == null) {
            log("✗ openDevice 失败")
            return null
        }
        var claimed = false
        try {
            if (!transport.setConfiguration(conn, cfg)) {
                log("✗ 切到配置 id=${cfg.id} 失败")
                return null
            }
            if (!transport.claim(conn, itf)) {
                log("✗ claim usbmux 接口失败")
                return null
            }
            claimed = true
            val (epIn, epOut) = transport.bulkEndpoints(itf)
            if (epIn == null || epOut == null) {
                log("✗ usbmux 接口端点不全（IN=${epIn != null} OUT=${epOut != null}）")
                return null
            }

            // 配对阶段还没有身份 —— MuxLink 只建隧道 + QueryType
            val link = MuxLink(conn, epIn, epOut, null, log)
            if (!link.openRaw()) {
                log("✗ lockdown 隧道建立失败：${link.failed}")
                return null
            }

            val devicePub = fetchDevicePublicKey(link) ?: return null
            val wifiMac = fetchWifiMac(link)
            log("设备 WiFiAddress=${wifiMac ?: "（未报）"}")

            val hostId = Lockdown.hostIdFor(HOST_NAME)
            val material = CertKit.generate(devicePub, hostId)
            log("已生成主机证书链（HostID=$hostId），开始 Pair…")
            log("★ 请在 iPad 上点「信任」")

            val record = doPair(link, material, wifiMac) ?: return null
            recordFile(filesDir).writeText(record.encode())
            log("★★ 配对成功并已保存：${recordFile(filesDir).absolutePath}（${recordFile(filesDir).length()} B）")
            return record
        } finally {
            if (claimed) runCatching { transport.release(conn, itf) }
            runCatching { conn.close() }
        }
    }

    /** `GetValue(DevicePublicKey)`。设备既可能给 `<data>` 也可能给 `<string>`（PEM 文本）。 */
    private fun fetchDevicePublicKey(link: MuxLink): ByteArray? {
        val reply = link.request(Lockdown.getValue("UsbScreenMirror", key = "DevicePublicKey"))
        if (reply == null) {
            log("✗ GetValue(DevicePublicKey) 无回复：${link.failed ?: "超时"}")
            return null
        }
        reply.str("Error")?.let { log("✗ 取设备公钥被拒：$it"); return null }
        val raw = reply.blob("Value")
        if (raw == null || raw.isEmpty()) {
            log("✗ DevicePublicKey 为空（回包：${link.lastReplyText?.take(160)}）")
            return null
        }
        log("已取设备公钥 ${raw.size}B（${if (raw[0].toInt() == '-'.code) "PEM" else "DER"}）")
        return raw
    }

    /** 全量 `GetValue` 里取 `WiFiAddress`（设备 MAC）。取不到就省略该字段。 */
    private fun fetchWifiMac(link: MuxLink): String? {
        val reply = link.request(Lockdown.getValue("UsbScreenMirror"))
        if (reply == null) return null
        return reply.dict("Value")?.str("WiFiAddress")
    }

    /**
     * 发 `Pair` 并等用户点信任。
     *
     * 设备在用户还没点时会回 `PairingDialogResponsePending`，这不是失败，
     * 按 PC 实现的做法每秒重发一次。
     */
    private fun doPair(link: MuxLink, material: CertKit.Material, wifiMac: String?): PairRecord? {
        val request = Lockdown.pair(
            label = "UsbScreenMirror",
            hostName = HOST_NAME,
            hostId = material.hostId,
            systemBuid = Lockdown.SYSTEM_BUID,
            material = material,
            wifiMac = wifiMac
        )
        val deadline = System.currentTimeMillis() + USER_ACCEPT_TIMEOUT_MS
        var round = 0
        while (System.currentTimeMillis() < deadline) {
            round++
            val reply: PValue? = link.request(request, timeoutMs = 15_000)
            if (reply == null) {
                if (link.failed != null) {
                    log("✗ Pair 中断：${link.failed}")
                    return null
                }
                log("  Pair 第 $round 次无回复，重试")
                continue
            }
            when (val err = reply.str("Error")) {
                null -> {
                    val bag = reply.blob("EscrowBag")
                    log("★★ Pair 成功${if (bag != null) "（EscrowBag ${bag.size}B）" else ""}")
                    return PairRecord(
                        hostId = material.hostId,
                        systemBuid = Lockdown.SYSTEM_BUID,
                        wifiMac = wifiMac,
                        hostCertPem = material.hostCertPem,
                        hostKeyPem = material.hostKeyPem,
                        rootCertPem = material.rootCertPem,
                        deviceCertPem = material.deviceCertPem,
                        escrowBag = bag
                    )
                }
                "PairingDialogResponsePending" ->
                    log("  等你在 iPad 上确认（第 $round 次）…")
                "UserDeniedPairing" -> {
                    log("✗ 你在 iPad 上点了「不信任」")
                    return null
                }
                else -> {
                    log("✗ Pair 被拒：$err")
                    log("   回包：${link.lastReplyText?.take(400)}")
                    return null
                }
            }
            Thread.sleep(1000)
        }
        log("✗ ${USER_ACCEPT_TIMEOUT_MS / 1000}s 内没有点信任，放弃")
        return null
    }

    /**
     * 挑一个用于配对的配置。
     *
     * 优先**普通**配置（id=3 是设备默认活动配置，1 也常见），因为配对跟采集无关；
     * 只有普通配置里都没有 mux 接口时才退到任意含 mux 的配置。
     */
    private fun muxConfiguration(dev: UsbDevice): android.hardware.usb.UsbConfiguration? {
        val all = (0 until dev.configurationCount).mapNotNull { i ->
            runCatching { dev.getConfiguration(i) }.getOrNull()
        }
        val withMux = all.filter { transport.muxInterfaceIn(it) != null }
        if (withMux.isEmpty()) return null
        return withMux.firstOrNull { it.id == 3 }
            ?: withMux.firstOrNull { it.id == 1 }
            ?: withMux.first()
    }
}
