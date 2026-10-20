package com.uav.host.ios.capture

import android.hardware.usb.UsbDevice
import kotlinx.coroutines.delay
import android.hardware.usb.UsbInterface
import com.uav.protocol.ios.CertKit
import com.uav.protocol.ios.DeviceVersion
import com.uav.protocol.ios.Lockdown
import com.uav.protocol.ios.PValue
import com.uav.protocol.ios.PairRecord
import com.uav.protocol.ios.PairStore
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

        /** 旧版单文件（不分设备）。只用于**迁移**与"拿不到设备键"的兜底。 */
        const val LEGACY_PAIR_FILE = PairStore.LEGACY_FILE

        /** 拿不到设备键时的占位键（会退回旧版单文件）。 */
        const val KEY_UNKNOWN = PairStore.KEY_UNKNOWN

        /**
         * 设备键 —— 用 **USB 序列号**。
         *
         * ## 为什么必须是"能在配对之前拿到"的东西
         *
         * 流程是"先查有没有记录，没有才配对"，所以键只能在**还没信任**的状态下取得：
         *  - USB 序列号：随设备描述符来，不需要配对 ✅
         *  - lockdown 的 `UniqueDeviceID` / `SerialNumber`：要先起 usbmux 会话，更重；
         *    而且它与 USB 序列号**可能不一致** ⇒ 一旦拿它做键，"写"和"查"用两个来源，
         *    就会永远查不到、每次都重新配对。
         *
         * ⇒ 全程只认 USB 序列号一个来源。取不到（老机型不报 iSerial，或权限还没授予）
         *   返回 [KEY_UNKNOWN]，由 [PairStore.load] 走"只有一台设备记录就直接用它"的兜底。
         */
        fun deviceKey(dev: UsbDevice): String {
            val raw = runCatching { dev.serialNumber }.getOrNull()?.trim().orEmpty()
            val clean = raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            return if (clean.isEmpty()) KEY_UNKNOWN else clean.take(48)
        }

        /** 某台设备的记录文件（布局见 [PairStore]）。 */
        fun recordFile(filesDir: File, key: String = KEY_UNKNOWN): File =
            PairStore.recordFile(filesDir, key)

        fun hasRecord(filesDir: File, key: String = KEY_UNKNOWN): Boolean =
            PairStore.has(filesDir, key)

        /** 读某台设备的配对记录（含旧版单文件迁移）。 */
        fun loadRecord(
            filesDir: File,
            key: String = KEY_UNKNOWN,
            log: ((String) -> Unit)? = null,
        ): PairRecord? = PairStore.load(filesDir, key, log)

        /** 已记住的设备键（界面显示"换回来不用再点信任"用）。 */
        fun knownDeviceKeys(filesDir: File): List<String> = PairStore.knownKeys(filesDir)

        /** 旧版单文件是否还在（还没被迁移）。 */
        fun hasLegacyRecord(filesDir: File): Boolean = PairStore.hasLegacy(filesDir)
    }

    /**
     * 走完一次配对。
     *
     * @return 成功时返回记录（**已写盘**）；失败返回 null 并把原因打到日志
     */
    suspend fun run(dev: UsbDevice): PairRecord? {
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
        var link: MuxLink? = null
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
            link = MuxLink(conn, epIn, epOut, null, log)
            val l = link
            if (!l.openRaw()) {
                log("✗ lockdown 隧道建立失败：${l.failed}")
                return null
            }

            val devicePub = fetchDevicePublicKey(l) ?: return null
            val info = fetchDeviceInfo(l)
            val wifiMac = info.wifiMac
            val deviceVersion = info.version
            // 记录按设备分开存：键只在"配对之前"可用（见 deviceKey 的说明）
            val key = deviceKey(dev)
            log("设备 WiFiAddress=${wifiMac ?: "（未报）"}；设备键=$key；${deviceVersion.summary()}")

            val hostId = Lockdown.hostIdFor(HOST_NAME)
            val material = CertKit.generate(devicePub, hostId)
            log("已生成主机证书链（HostID=$hostId），开始 Pair…")
            log("★ 请在 iPad 上点「信任」")

            val record = doPair(
                l, material, wifiMac,
                deviceSerial = if (key == KEY_UNKNOWN) null else key,
                deviceVersion = deviceVersion,
            ) ?: return null
            val file = PairStore.save(filesDir, key, record)
            log(
                "★★ 配对成功并已保存：${file.name}（${file.length()} B，设备 ${record.deviceSerial ?: "未知"}）" +
                    "—— 每台设备一条记录，换回来不用再点信任"
            )
            return record
        } finally {
            // ★ 先发 FIN 再放接口：否则设备侧那条 TCP 一直 ESTABLISHED，
            //   下次插上连 VERSION 都收不到（只能拔插 iPad）。
            link?.let { runCatching { it.shutdown("配对流程结束") } }
            if (claimed) runCatching { transport.release(conn, itf) }
            runCatching { conn.close() }
        }
    }

    /** `GetValue(DevicePublicKey)`。设备既可能给 `<data>` 也可能给 `<string>`（PEM 文本）。 */
    private fun fetchDevicePublicKey(link: MuxLink): ByteArray? {
        val reply = link.request(Lockdown.getValue("UsbScreenMirror", key = "DevicePublicKey"))
        if (reply == null) {
            // ★ `request()` 返回的是"解析出来的 plist"，所以 null 有两种含义：
            //   ① 真的没回包；② **回包来了但解析失败**（MuxLink 那边会单独打原文）。
            //   以前一律说"超时"，会把②误导成链路/时序问题（实测踩过）。
            val why = link.failed
                ?: if (link.lastRequestReplied) "回包无法解析（见上一行原文）" else "超时"
            log("✗ GetValue(DevicePublicKey) 无回复：$why")
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

    /**
     * 配对前从全量 `GetValue` 拿到的一次性设备信息。
     *
     * ⚠️ 特意**不用** `kotlin.Pair` —— 本包已有一个同名的 `Pair`（历史遗留），
     *   用 `Pair<String?, DeviceVersion>` 会命中"component1() is ambiguous"的编译错误，
     *   而且编译器报错完全指不到真正的原因（不说是哪个 `Pair`）。具名类是更省事的选择。
     */
    private class DeviceInfo(val wifiMac: String?, val version: DeviceVersion)

    /**
     * 全量 `GetValue`：取 `WiFiAddress`（设备 MAC）与 `ProductVersion` / `ProductType`。
     *
     * ⚠️ 一次请求拿两样 —— 这条回包本来就包含全部键，分两次问只是多一个 RTT。
     *
     * 两者都可能取不到（老设备/字段缺失）：`wifiMac` 为 null 时 `Pair` 请求里省略该字段，
     * 版本为 [DeviceVersion.UNKNOWN] ⇒ **不影响配对**，也不做任何版本相关降级。
     */
    private fun fetchDeviceInfo(link: MuxLink): DeviceInfo {
        val dict = link.request(Lockdown.getValue("UsbScreenMirror"))?.dict("Value")
        return DeviceInfo(
            wifiMac = dict?.str("WiFiAddress"),
            version = DeviceVersion.fromReply(dict),
        )
    }

    /**
     * 发 `Pair` 并等用户点信任。
     *
     * 设备在用户还没点时会回 `PairingDialogResponsePending`，这不是失败，
     * 按 PC 实现的做法每秒重发一次。
     */
    private suspend fun doPair(
        link: MuxLink,
        material: CertKit.Material,
        wifiMac: String?,
        deviceSerial: String?,
        deviceVersion: DeviceVersion,
    ): PairRecord? {
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
                        escrowBag = bag,
                        deviceSerial = deviceSerial,
                        deviceVersion = deviceVersion
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
            // ★ 挂起而不是 sleep：等用户点「信任」最长 60s，阻塞线程白占一条
            delay(1000)
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