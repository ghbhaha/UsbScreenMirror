package com.uav.protocol.ios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * 配对材料自检。
 *
 * 验收口径：**生成的证书必须能被独立实现（JDK 的 CertificateFactory）解析并验签通过** ——
 * 只"能编码"不算过，设备那边认的是签名本身。
 */
class CertKitTest {

    private fun sampleDevicePublicKey(): ByteArray =
        CertKit.newRsa().public.encoded          // SPKI DER，等价于设备下发的那段

    @Test
    fun `hostId 与 UUIDv3 规范一致`() {
        // 参考值由 Python `uuid.uuid3(uuid.NAMESPACE_DNS, name)` 生成
        assertEquals("CBDF0036-810B-35CB-9611-13361F31DEEA", Lockdown.hostIdFor("UsbScreenMirror"))
        assertEquals("64C5B954-CD28-3A58-ABCD-B5434F2FA934", Lockdown.hostIdFor("DESKTOP-TEST"))
        assertEquals("D74E4E7E-B8AB-396D-84E0-6E5006E0A488", Lockdown.hostIdFor("test-workstation"))
    }

    @Test
    fun `三张证书都是可解析的 X509 v3`() {
        val m = CertKit.generate(sampleDevicePublicKey(), Lockdown.hostIdFor("UsbScreenMirror"))
        val cf = CertificateFactory.getInstance("X.509")
        val expect = mapOf(
            "root" to (m.rootCertPem to CertKit.CN_ROOT),
            "host" to (m.hostCertPem to CertKit.CN_HOST),
            "device" to (m.deviceCertPem to CertKit.CN_DEVICE)
        )
        for ((name, pair) in expect) {
            val (pem, cn) = pair
            val cert = cf.generateCertificate(pem.inputStream()) as X509Certificate
            assertEquals("$name 版本", 3, cert.version)
            assertEquals("$name serial", 1L, cert.serialNumber.toLong())
            assertTrue("$name subject 应含 CN=$cn", cert.subjectX500Principal.name.contains("CN=$cn"))
            assertEquals("$name issuer", CertKit.CN_ROOT, issuerCn(cert))
        }
    }

    private fun issuerCn(cert: X509Certificate): String =
        cert.issuerX500Principal.name.substringAfter("CN=")

    @Test
    fun `三张证书都被 DER 级独立验签通过`() {
        // 不经过 JDK 的证书解析器：自己拆 tbs/signature，用 root 公钥验 —— 与设备端的校验等价
        val m = CertKit.generate(sampleDevicePublicKey(), "TEST-HOST-ID")
        val rootPub = CertKit.keyPairOf(m.rootKey).public
        for ((name, pem) in listOf("host" to m.hostCertPem, "device" to m.deviceCertPem)) {
            val der = CertKit.derOf(pem)
            val kids = Der.children(der)                       // tbs, sigAlg, signature
            // children 给的是「内容」起止，验签要用完整 TLV
            val tbs = Der.tlv(0x30, der.copyOfRange(kids[0].first, kids[0].first + kids[0].second))
            val (sigStart, sigLen) = kids[2]
            val sig = der.copyOfRange(sigStart + 1, sigStart + sigLen)   // 去掉 unused-bits 字节
            val v = Signature.getInstance("SHA256withRSA")
            v.initVerify(rootPub)
            v.update(tbs)
            assertTrue("$name 验签", v.verify(sig))
        }
    }


    @Test
    fun `root 是 CA 而叶子不是`() {
        val m = CertKit.generate(sampleDevicePublicKey(), "TEST-HOST-ID")
        val cf = CertificateFactory.getInstance("X.509")
        val root = cf.generateCertificate(m.rootCertPem.inputStream()) as X509Certificate
        val host = cf.generateCertificate(m.hostCertPem.inputStream()) as X509Certificate
        assertEquals("root basicConstraints", Int.MAX_VALUE, root.basicConstraints)  // CA 且不限 pathLen
        assertEquals("host basicConstraints", -1, host.basicConstraints)     // -1 = 非 CA
        val ku = host.keyUsage
        assertNotNull("host 必须带 keyUsage", ku)
        assertTrue("digitalSignature", ku[0])
        assertTrue("keyEncipherment", ku[2])
        // device 独有 SubjectKeyIdentifier（2.5.29.14）—— 用 DER 判，因为 JDK 不暴露该扩展
        assertTrue("device 证书必须带 SKI", hasExtension(derOfSelf(m.deviceCertPem), "2.5.29.14"))
        assertTrue("host 证书不带 SKI", !hasExtension(derOfSelf(m.hostCertPem), "2.5.29.14"))
    }

    private fun derOfSelf(pem: ByteArray): ByteArray = CertKit.derOf(pem)

    /** 在证书 DER 里搜某个扩展 OID 的编码字节（足够判"有没有"）。 */
    private fun hasExtension(certDer: ByteArray, oid: String): Boolean {
        val needle = Der.oid(oid)
        var i = 0
        while (i <= certDer.size - needle.size) {
            var ok = true
            for (j in needle.indices) if (certDer[i + j] != needle[j]) { ok = false; break }
            if (ok) return true
            i++
        }
        return false
    }

    @Test
    fun `host 私钥能解回来且与证书公钥配对`() {
        val m = CertKit.generate(sampleDevicePublicKey(), "TEST-HOST-ID")
        val key = CertKit.privateKey(String(m.hostKeyPem, Charsets.US_ASCII))
        val cf = CertificateFactory.getInstance("X.509")
        val host = cf.generateCertificate(m.hostCertPem.inputStream()) as X509Certificate
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(key)
        sig.update("probe".toByteArray())
        val s = sig.sign()
        sig.initVerify(host.publicKey)
        sig.update("probe".toByteArray())
        assertTrue("私钥与证书公钥是同一对", sig.verify(s))
    }

    @Test
    fun `PKCS1 形态的设备公钥也能识别`() {
        val spki = sampleDevicePublicKey()
        // SPKI 的 BIT STRING 内容本身就是 PKCS#1 的 RSAPublicKey SEQUENCE —— 模拟旧固件直接下发它
        val (bs, bl) = Der.children(spki)[1]
        val pkcs1 = spki.copyOfRange(bs + 1, bs + bl)          // 跳过 unused-bits 字节
        assertEquals(0x30, pkcs1[0].toInt() and 0xFF)
        val a = CertKit.rsaPublicKey(pkcs1)
        val b = CertKit.rsaPublicKey(spki)
        assertEquals(a.modulus, b.modulus)
        assertEquals(a.publicExponent, b.publicExponent)
    }

    @Test
    fun `PEM 文本形态的设备公钥也能识别`() {
        val spki = sampleDevicePublicKey()
        val a = CertKit.rsaPublicKey(Der.pem("PUBLIC KEY", spki))
        val b = CertKit.rsaPublicKey(spki)
        assertEquals(b.modulus, a.modulus)
        assertEquals(b.publicExponent, a.publicExponent)
    }
}
