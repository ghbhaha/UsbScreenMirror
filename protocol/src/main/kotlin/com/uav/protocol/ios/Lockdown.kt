package com.uav.protocol.ios

import com.uav.protocol.B64
import java.security.MessageDigest
import java.util.Properties

/**
 * lockdownd 的请求构造 + 本地配对记录。
 *
 * 字段与**类型**逐项对照 PC 上成功过的那套实现（`pymobiledevice3/lockdown.py::pair`）：
 *
 * ```text
 * Label / Request            string
 * HostName                   string
 * ProtocolVersion            "2"
 * PairingOptions             dict { ExtendedPairingErrors: true }
 * PairRecord                 dict {
 *     DeviceCertificate      data  ← 内容是 PEM 文本
 *     HostCertificate        data  ← 内容也是 PEM 文本
 *     RootCertificate        data
 *     RootPrivateKey         data  ← PKCS#8 PEM 文本
 *     HostID                 string（UUID，大写带横线）
 *     SystemBUID             string
 *     WiFiMACAddress         string（**设备的** WiFiAddress，不是本机的）
 * }
 * ```
 *
 * ⚠️ `HostPrivateKey` **不出现**在请求里，设备应答（EscrowBag）之后才补进本地记录。
 */
object Lockdown {

    /** 与 libimobiledevice / pymobiledevice3 一致的默认 SystemBUID。 */
    const val SYSTEM_BUID = "30142955-444094379208051516"

    private const val NAMESPACE_DNS = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

    fun queryType(label: String): ByteArray = PlistXml.encode(
        PValue.dict(
            "Label" to PValue.Str(label),
            "Request" to PValue.Str("QueryType")
        )
    )

    fun getValue(label: String, key: String? = null, domain: String? = null): ByteArray =
        PlistXml.encode(
            PValue.dict(
                "Label" to PValue.Str(label),
                "Request" to PValue.Str("GetValue"),
                "Domain" to domain?.let { PValue.Str(it) },
                "Key" to key?.let { PValue.Str(it) }
            )
        )

    fun startSession(label: String, hostId: String, systemBuid: String): ByteArray =
        PlistXml.encode(
            PValue.dict(
                "Label" to PValue.Str(label),
                "Request" to PValue.Str("StartSession"),
                "HostID" to PValue.Str(hostId),
                "SystemBUID" to PValue.Str(systemBuid)
            )
        )

    /**
     * 组装 `Pair` 请求。
     *
     * @param hostName 显示在设备「信任此电脑？」弹窗里的名字
     * @param wifiMac 设备 `WiFiAddress`；null 时该键整体省略（plistlib 也无法编码 None）
     */
    fun pair(
        label: String,
        hostName: String,
        hostId: String,
        systemBuid: String,
        material: CertKit.Material,
        wifiMac: String?
    ): ByteArray {
        val record = PValue.dictOf(
            listOfNotNull(
                "DeviceCertificate" to PValue.Data(material.deviceCertPem),
                "HostCertificate" to PValue.Data(material.hostCertPem),
                "HostID" to PValue.Str(hostId),
                "RootCertificate" to PValue.Data(material.rootCertPem),
                "RootPrivateKey" to PValue.Data(material.rootKeyPem),
                "SystemBUID" to PValue.Str(systemBuid),
                wifiMac?.let { "WiFiMACAddress" to PValue.Str(it) }
            )
        )
        return PlistXml.encode(
            PValue.dict(
                "Label" to PValue.Str(label),
                "Request" to PValue.Str("Pair"),
                "HostName" to PValue.Str(hostName),
                "PairRecord" to record,
                "ProtocolVersion" to PValue.Str("2"),
                "PairingOptions" to PValue.dict("ExtendedPairingErrors" to PValue.Bool(true))
            )
        )
    }

    /**
     * 按 hostname 生成 HostID —— `UUIDv3(NAMESPACE_DNS, name)` 的大写形式。
     *
     * 用确定性生成（而不是随机 uuid4）是为了**重配对时 HostID 不变**：设备侧按 HostID
     * 认记录，变了就等于换一台新主机，得重新点一次信任。
     */
    fun hostIdFor(name: String): String {
        val ns = hexToBytes(NAMESPACE_DNS.replace("-", ""))
        val md = MessageDigest.getInstance("MD5")
        md.update(ns)
        md.update(name.toByteArray(Charsets.UTF_8))
        val h = md.digest()
        h[6] = ((h[6].toInt() and 0x0F) or 0x30).toByte()   // version 3
        h[8] = ((h[8].toInt() and 0x3F) or 0x80).toByte()   // variant RFC4122
        val hex = h.joinToString("") { "%02x".format(it) }
        return (hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) +
            "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32)).uppercase()
    }

    private fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) or
            Character.digit(s[it * 2 + 1], 16)).toByte() }
}

/**
 * 本地保存的配对记录（`filesDir/ios_pair_<设备序列号>.properties`）。
 *
 * 只存**这台主机对某台设备**自己配出来的那一套；PC 导入的凭证仍走 assets 那条老路。
 *
 * ★ **一台设备一条记录**（按 [deviceSerial] 分文件）：配对记录里既有主机身份，也有
 *   **设备专属**的 `DeviceCertificate` / `EscrowBag`，所以它本质是"主机 × 设备"这一对的关系，
 *   不能多台设备共用一条 —— 共用就等于"换一台 iPad 就把上一台的信任覆盖掉"（换回来还要再点信任）。
 */
class PairRecord(
    val hostId: String,
    val systemBuid: String,
    val wifiMac: String?,
    val hostCertPem: ByteArray,
    val hostKeyPem: ByteArray,
    val rootCertPem: ByteArray,
    val deviceCertPem: ByteArray,
    val escrowBag: ByteArray? = null,
    /**
     * 这条记录属于哪台设备（iPad 的序列号）。
     *
     * 两个用途：① 迁移旧版单文件记录时判定归属；② 界面上说清"这台是哪台"。
     * 老记录没有这个字段 ⇒ 解码为 null（= 归属未知，只允许被"当前唯一在插的那台"接管）。
     */
    val deviceSerial: String? = null,
    /**
     * **暂时接管**（还没经过实机校验）。
     *
     * 只有一种来源：旧版单文件记录被某台设备认领（见 [PairStore.load]）。旧记录不带归属，
     * 无法确定它属于哪台设备，所以先"试着接管"；**真正跑通一次 TLS** 之后才转正
     * （[PairStore.confirm]）。若 TLS 没通过 ⇒ 说明认错人了，把这条临时记录丢掉
     * （[PairStore.dropProvisional]），旧单文件仍在，可以还给对的那台。
     */
    val provisional: Boolean = false,
    /**
     * 这台设备的 iOS 版本（`ProductVersion` / `ProductType`）。
     *
     * ⚠️ **纯诊断信息，不参与任何行为决策**（见 [DeviceVersion] 类注释）：
     *   协议层没有版本差异，这里只为「跑不起来时能一眼区分'版本问题'和'会话问题'」。
     *
     * 老记录没有这个字段 ⇒ null（= 未知）⇒ 行为与从前完全一致。
     * 一旦会话走通（[PairStore.confirm]）就顺手刷新，不必重新配对。
     */
    val deviceVersion: DeviceVersion = DeviceVersion.UNKNOWN,
) {

    fun encode(): String {
        val p = Properties()
        p["version"] = "1"
        p["hostId"] = hostId
        p["systemBuid"] = systemBuid
        p["wifiMac"] = wifiMac ?: ""
        p["hostCert"] = b64(hostCertPem)
        p["hostKey"] = b64(hostKeyPem)
        p["rootCert"] = b64(rootCertPem)
        p["deviceCert"] = b64(deviceCertPem)
        p["escrowBag"] = escrowBag?.let { b64(it) } ?: ""
        p["deviceSerial"] = deviceSerial ?: ""
        p["provisional"] = if (provisional) "1" else ""
        p["productVersion"] = deviceVersion.productVersion ?: ""
        p["productType"] = deviceVersion.productType ?: ""
        val out = java.io.StringWriter()
        p.store(out, "UsbScreenMirror · iOS pairing record (self-paired)")
        return out.toString()
    }

    /**
     * 复制一份，只把"归属设备"改成 [serial]。
     *
     * 用途：旧版单文件记录没有归属字段，被某台设备接管时要**盖章** ——
     * 否则下次再出现旧文件（备份恢复之类）会被另一台设备重复认领。
     */
    fun withDeviceSerial(serial: String, asProvisional: Boolean = false): PairRecord = PairRecord(
        hostId = hostId,
        systemBuid = systemBuid,
        wifiMac = wifiMac,
        hostCertPem = hostCertPem,
        hostKeyPem = hostKeyPem,
        rootCertPem = rootCertPem,
        deviceCertPem = deviceCertPem,
        escrowBag = escrowBag,
        deviceSerial = serial,
        provisional = asProvisional,
    )

    /** 复制一份，只改 [provisional] 标记（实机校验通过 ⇒ 转正）。 */
    fun withProvisional(value: Boolean): PairRecord = PairRecord(
        hostId = hostId,
        systemBuid = systemBuid,
        wifiMac = wifiMac,
        hostCertPem = hostCertPem,
        hostKeyPem = hostKeyPem,
        rootCertPem = rootCertPem,
        deviceCertPem = deviceCertPem,
        escrowBag = escrowBag,
        deviceSerial = deviceSerial,
        provisional = value,
    )

    /**
     * 复制一份，只填[DeviceVersion]（纯诊断信息）。
     *
     * 用法：会话走通后调一次，把版本补记进去——**不必重新配对**。
     */
    fun withDeviceVersion(v: DeviceVersion): PairRecord = PairRecord(
        hostId = hostId,
        systemBuid = systemBuid,
        wifiMac = wifiMac,
        hostCertPem = hostCertPem,
        hostKeyPem = hostKeyPem,
        rootCertPem = rootCertPem,
        deviceCertPem = deviceCertPem,
        escrowBag = escrowBag,
        deviceSerial = deviceSerial,
        provisional = provisional,
        deviceVersion = v,
    )

    companion object {
        private fun b64(b: ByteArray): String = B64.encode(b)
        private fun unb64(s: String): ByteArray = B64.decode(s)

        fun decode(text: String): PairRecord? = runCatching {
            val p = Properties()
            p.load(java.io.StringReader(text))
            PairRecord(
                hostId = p.getProperty("hostId") ?: error("缺 hostId"),
                systemBuid = p.getProperty("systemBuid") ?: Lockdown.SYSTEM_BUID,
                wifiMac = p.getProperty("wifiMac")?.takeIf { it.isNotBlank() },
                hostCertPem = unb64(p.getProperty("hostCert")!!),
                hostKeyPem = unb64(p.getProperty("hostKey")!!),
                rootCertPem = unb64(p.getProperty("rootCert")!!),
                deviceCertPem = unb64(p.getProperty("deviceCert")!!),
                escrowBag = p.getProperty("escrowBag")?.takeIf { it.isNotBlank() }?.let { unb64(it) },
                // 老记录没有这个字段 ⇒ null（归属未知）
                deviceSerial = p.getProperty("deviceSerial")?.takeIf { it.isNotBlank() },
                provisional = p.getProperty("provisional")?.isNotBlank() == true,
                // 老记录没这两个字段 ⇒ UNKNOWN（行为与从前完全一致）
                deviceVersion = DeviceVersion(
                    productVersion = p.getProperty("productVersion")?.takeIf { it.isNotBlank() },
                    productType = p.getProperty("productType")?.takeIf { it.isNotBlank() },
                )
            )
        }.getOrNull()
    }
}
