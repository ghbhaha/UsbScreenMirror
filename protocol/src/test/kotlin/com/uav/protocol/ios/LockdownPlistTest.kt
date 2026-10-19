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

    // ---------------------------------------------------------------- 解析健壮性

    /**
     * 自闭合标签（`<data/>`、`<string/>`）以前会把整条 plist 判成"解析失败"：
     * [PlistXml] 遇到 `<data/>` 仍去正文找 `</data>`，找不到就抛异常 ——
     * 上层只能看到 `request()` 返回 null，日志写成"无回复/超时"（实测一台 iPad
     * 的 `GetValue` 回包就栽在这里）。
     */
    @Test
    fun `自闭合标签不再让整条 plist 解析失败`() {
        val text = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Request</key><string>GetValue</string>
                <key>Value</key><data/>
                <key>Empty</key><string/>
                <key>Nested</key><dict/>
                <key>OK</key><true/>
            </dict>
            </plist>
        """.trimIndent()

        val v = PlistXml.parse(text)
        assertNotNull("自闭合标签不应该让整条回包解析失败", v)
        assertEquals("GetValue", v!!.str("Request"))
        assertEquals(0, v.blob("Value")?.size)          // 空 data ⇒ 空数组，不是 null
        assertEquals("", v.str("Empty"))
        assertEquals(true, v.bool("OK"))
    }

    /** 真机形态：`GetValue(DevicePublicKey)` 回包，`Value` 是多行 base64 的 `<data>`。 */
    @Test
    fun `GetValue 回包里的多行 base64 data 能读出来`() {
        val key = ByteArray(294) { (it * 7).toByte() }
        val b64 = java.util.Base64.getEncoder().encodeToString(key)
        val wrapped = b64.chunked(64).joinToString("\n\t")
        val text = """
            <plist version="1.0">
            <dict>
                <key>DevicePublicKey</key>
                <data>
                    $wrapped
                </data>
                <key>Type</key><string>com.apple.mobile.lockdown</string>
            </dict>
            </plist>
        """.trimIndent()

        val v = PlistXml.parse(text)
        assertNotNull(v)
        val got = v!!.blob("DevicePublicKey")
        assertNotNull(got)
        assertTrue("多行 base64 必须能还原", key.contentEquals(got!!))
    }

    /**
     * 逐字复刻真机回包（iPad8,9 / iOS 27.2，`GetValue(DevicePublicKey)` 的 914B 应答）。
     *
     * 原文抓自 logcat，只把行尾统一成 `\n`。这条用例的意义是**锁住"设备怎么发我们就能读"**：
     * 之前正是这条回包解析失败、被上层当成"设备没回复"，白排查了一轮链路。
     */
    @Test
    fun `真机 GetValue(DevicePublicKey) 回包能被解析`() {
        val reply = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
            	<key>Key</key>
            	<string>DevicePublicKey</string>
            	<key>Request</key>
            	<string>GetValue</string>
            	<key>Value</key>
            	<data>
            LS0tLS1CRUdJTiBSU0EgUFVCTElDIEtFWS0tLS0tCk1JSUJDZ0tDQVFFQXMxWFhFZEMz
            cVB2T2t0TmVEMWlSVzNxeGJManZJckwxQ1IwdUhHVFgwRTRmMlFiVVo2Q3MNCnV5emRI
            Z0pLeURmZlh3T3duK3VJTEdwNUlkN3EzakgrTlZpWGd2THBKQ1Q3UTR6UGl1c01YYVlI
            Z1VTVDZjaGcNCmpaSmJFbVFGdHBRMHdOeFc1ZG9LZkNDUWRkRTVrSlNoYzNuM2VXTHU5
            aUZXanZrSHZ3U3ZoYU5QSE4wSUM0d0sNClFNcHF5MVE1ejhiYzdMSXkyK2FWNm44ZmpN
            UXdCUkpqRy9EQUc4alRqOUdaejZZV1RDN1hVZE04UG1JNE81bkcNCkEyWWdpSWJVM09G
            WFRnRWdDK0I2K2g2TEgrSzU1OHdTVElEVXJTbERxc0s1b0pWREsvdWN6Ymovc2VKaTl2
            VTANCk9FeEwwU1Zvdk9VWTZYZndPRis4WFJkYkVJcGttQlN3SFFJREFRQUIKLS0tLS1F
            TkQgUlNBIFBVQkxJQyBLRVktLS0tLQo=
            	</data>
            </dict>
            </plist>
        """.trimIndent()

        val r = PlistXml.parseDetailed(reply)
        assertTrue("解析失败：${r.exceptionOrNull()}", r.isSuccess)
        val v = r.getOrThrow()
        assertEquals("DevicePublicKey", v.str("Key"))
        val pem = v.blob("Value")
        assertNotNull(pem)
        val text = String(pem!!, Charsets.UTF_8)
        assertTrue("解出来的必须是 PEM 公钥文本，实际开头：${text.take(40)}",
            text.startsWith("-----BEGIN RSA PUBLIC KEY-----"))
    }
}
