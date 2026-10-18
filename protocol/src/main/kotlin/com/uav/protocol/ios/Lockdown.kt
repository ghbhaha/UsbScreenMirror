package com.uav.protocol.ios

import java.security.MessageDigest
import java.util.Base64
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
 * 本地保存的配对记录（`filesDir/ios_pair.properties`）。
 *
 * 只存**这台主机自己配出来的**那一套；PC 导入的凭证仍走 assets 那条老路。
 */
class PairRecord(
    val hostId: String,
    val systemBuid: String,
    val wifiMac: String?,
    val hostCertPem: ByteArray,
    val hostKeyPem: ByteArray,
    val rootCertPem: ByteArray,
    val deviceCertPem: ByteArray,
    val escrowBag: ByteArray? = null
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
        val out = java.io.StringWriter()
        p.store(out, "UsbScreenMirror · iOS pairing record (self-paired)")
        return out.toString()
    }

    companion object {
        private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

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
                escrowBag = p.getProperty("escrowBag")?.takeIf { it.isNotBlank() }?.let { unb64(it) }
            )
        }.getOrNull()
    }
}
