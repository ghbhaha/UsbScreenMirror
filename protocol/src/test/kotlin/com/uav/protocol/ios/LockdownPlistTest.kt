package com.uav.protocol.ios

import com.uav.protocol.bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** plist 编解码与 Pair 请求字段（逐项对照 PC 上成功的那份）。 */
class LockdownPlistTest {

    @Test
    fun `dict 编解码往返`() {
        val v = PValue.dict(
            "Label" to PValue.Str("A&B<C>"),
            "Request" to PValue.Str("Pair"),
            "PairingOptions" to PValue.dict("ExtendedPairingErrors" to PValue.Bool(true)),
            "Blob" to PValue.Data(byteArrayOf(1, 2, 3)),
            "N" to PValue.Int(42)
        )
        val text = String(PlistXml.encode(v), Charsets.UTF_8)
        assertTrue(text.contains("<plist version=\"1.0\">"))
        assertTrue("布尔必须是自闭合标签", text.contains("<true/>"))

        val back = PlistXml.parse(text)
        assertNotNull(back)
        assertEquals("A&B<C>", back!!.str("Label"))
        assertEquals(true, back.bool("PairingOptions") == null)   // dict 不是 Bool
        assertEquals(true, back.dict("PairingOptions")!!.bool("ExtendedPairingErrors"))
        assertEquals(3, back.data("Blob")!!.size)
        assertNull(back.str("Nope"))
    }

    @Test
    fun `设备真回包片段能被解析出 EscrowBag`() {
        val reply = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>EscrowBag</key>
              <data>
              AQIDBA==
              </data>
              <key>Request</key>
              <string>Pair</string>
            </dict>
            </plist>
        """.trimIndent()
        val v = PlistXml.parse(reply)
        assertNotNull(v)
        assertEquals("Pair", v!!.str("Request"))
        assertEquals(4, v.data("EscrowBag")!!.size)
    }

    @Test
    fun `GetValue 全量回包里的嵌套 dict 能取到 WiFiAddress`() {
        val reply = """
            <plist version="1.0"><dict>
              <key>Value</key>
              <dict>
                <key>WiFiAddress</key><string>aa:bb:cc:dd:ee:ff</string>
                <key>UniqueDeviceID</key><string>00008030-0011223344556677</string>
              </dict>
            </dict></plist>
        """.trimIndent()
        val v = PlistXml.parse(reply)!!
        val value = v.dict("Value")!!
        assertEquals("aa:bb:cc:dd:ee:ff", value.str("WiFiAddress"))
    }

    @Test
    fun `Pair 请求字段与顺序逐项对齐`() {
        val m = CertKit.generate(CertKit.newRsa().public.encoded, "TEST-HOST-ID")
        val bytes = Lockdown.pair(
            label = "UsbScreenMirror",
            hostName = "UsbScreenMirror",
            hostId = "TEST-HOST-ID",
            systemBuid = Lockdown.SYSTEM_BUID,
            material = m,
            wifiMac = "aa:bb:cc:dd:ee:ff"
        )
        val text = String(bytes, Charsets.UTF_8)

        // 顶层
        assertTrue(text.contains("<key>Request</key><string>Pair</string>"))
        assertTrue(text.contains("<key>ProtocolVersion</key><string>2</string>"))
        assertTrue(text.contains("<key>ExtendedPairingErrors</key><true/>"))

        // 三张证书 + root 私钥都以 <data> 承载（载荷是 PEM 文本）
        for (k in listOf("DeviceCertificate", "HostCertificate", "RootCertificate", "RootPrivateKey")) {
            assertTrue("$k 必须是 data", Regex("<key>$k</key><data>").containsMatchIn(text))
        }
        // ★ HostPrivateKey 绝不能出现在请求里（只在应答后写进本地记录）
        assertTrue("HostPrivateKey 不应入请求", !text.contains("HostPrivateKey"))
        // 设备 MAC 必须带
        assertTrue(text.contains("<key>WiFiMACAddress</key><string>aa:bb:cc:dd:ee:ff</string>"))

        // data 载荷解出来应当以 PEM 头开始
        val parsed = PlistXml.parse(text)!!
        val record = parsed.dict("PairRecord")!!
        val hostCert = String(record.data("HostCertificate")!!, Charsets.US_ASCII)
        assertTrue("HostCertificate 内容是 PEM 文本", hostCert.startsWith("-----BEGIN CERTIFICATE-----"))
        val rootKey = String(record.data("RootPrivateKey")!!, Charsets.US_ASCII)
        assertTrue("RootPrivateKey 内容是 PKCS#8 PEM", rootKey.startsWith("-----BEGIN PRIVATE KEY-----"))
    }

    @Test
    fun `没有 WiFiMAC 时整键省略而不是写空串`() {
        val m = CertKit.generate(CertKit.newRsa().public.encoded, "X")
        val text = String(
            Lockdown.pair("L", "H", "X", Lockdown.SYSTEM_BUID, m, null), Charsets.UTF_8
        )
        assertTrue(!text.contains("WiFiMACAddress"))
    }

    @Test
    fun `配对记录能存盘再读回`() {
        val m = CertKit.generate(CertKit.newRsa().public.encoded, "TEST-HOST-ID")
        val rec = PairRecord(
            hostId = m.hostId,
            systemBuid = Lockdown.SYSTEM_BUID,
            wifiMac = "aa:bb:cc:dd:ee:ff",
            hostCertPem = m.hostCertPem,
            hostKeyPem = m.hostKeyPem,
            rootCertPem = m.rootCertPem,
            deviceCertPem = m.deviceCertPem,
            escrowBag = byteArrayOf(9, 8, 7)
        )
        val back = PairRecord.decode(rec.encode())
        assertNotNull(back)
        assertEquals(rec.hostId, back!!.hostId)
        assertEquals(rec.wifiMac, back.wifiMac)
        assertTrue(rec.hostCertPem.contentEquals(back.hostCertPem))
        assertTrue(rec.escrowBag!!.contentEquals(back.escrowBag!!))
    }
}
