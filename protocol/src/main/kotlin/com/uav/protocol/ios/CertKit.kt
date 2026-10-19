package com.uav.protocol.ios

import com.uav.protocol.B64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * 自建配对身份 —— 生成 lockdown `Pair` 请求要的那一整套证书/密钥。
 *
 * ## 规格来源
 *
 * 逐项对照 `pymobiledevice3/ca.py::generate_pairing_cert_chain`（= libimobiledevice 的 C 行为），
 * 那张表在 PC 上是被真机接受过的：
 *
 * | 项 | 值 |
 * |---|---|
 * | 三张证书的 subject / issuer | **空 DN**（`30 00`，不是 CN=xxx） |
 * | serial | 恒 `1` |
 * | 效期 | now-1min ~ now+10y |
 * | 签名算法 | sha256WithRSAEncryption（iOS ≥ 4） |
 * | root | 自签，BasicConstraints CA:TRUE（critical） |
 * | host | 由 root 签发，BC CA:FALSE(critical) + KeyUsage digitalSignature,keyEncipherment(critical) |
 * | device | 同上，**主体是设备下发的 DevicePublicKey**，另加 SubjectKeyIdentifier(非 critical) |
 *
 * ## 三个容易踩的点
 *
 * 1. `DeviceCertificate` **不是**设备给的，是拿 `DevicePublicKey` 现签的一张叶子证书；
 * 2. 证书内容必须是 **PEM 文本**（不是裸 DER）—— 设备侧按文本解析；
 * 3. `HostPrivateKey` **不在** Pair 请求里，等设备应答之后才补进本地记录。
 */
object CertKit {

    private const val OID_SHA256_RSA = "1.2.840.113549.1.1.11"
    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_SUBJECT_KEY_ID = "2.5.29.14"

    const val CN_ROOT = "UsbScreenMirror Root"
    const val CN_HOST = "UsbScreenMirror Host"
    const val CN_DEVICE = "UsbScreenMirror Device"

    /** KeyUsage = digitalSignature(bit0) + keyEncipherment(bit2) ⇒ 1010_0000，尾部 5 位未用。 */
    private val KEY_USAGE_BITS = byteArrayOf(0xA0.toByte())
    private const val KEY_USAGE_UNUSED = 5

    /** 一次配对产出的全部材料（PEM 文本 + 可直接用的私钥对象）。 */
    class Material(
        val hostId: String,
        /** `Pair` 请求里的 `HostCertificate` */
        val hostCertPem: ByteArray,
        /** 应答后用；**不进请求** */
        val hostKeyPem: ByteArray,
        val hostKey: PrivateKey,
        /** `Pair` 请求里的 `DeviceCertificate`（用设备公钥签出来的那张） */
        val deviceCertPem: ByteArray,
        /** `Pair` 请求里的 `RootCertificate` */
        val rootCertPem: ByteArray,
        /** `Pair` 请求里的 `RootPrivateKey` */
        val rootKeyPem: ByteArray,
        /** root 私钥本体（TLS / 验签自检用） */
        val rootKey: PrivateKey
    )

    /**
     * 生成完整材料。
     *
     * @param devicePublicKeyRaw `GetValue(DevicePublicKey)` 的原样返回，PEM 文本或裸 DER 都行
     * @param hostId 本机标识（UUID 大写带横线；参与 `Pair` 与后续 `StartSession`）
     */
    fun generate(
        devicePublicKeyRaw: ByteArray,
        hostId: String,
        now: Long = System.currentTimeMillis()
    ): Material {
        val devicePub = rsaPublicKey(devicePublicKeyRaw)
        val rootPair = newRsa()
        val hostPair = newRsa()
        val nb = java.util.Date(now - 60_000L)
        val na = java.util.Date(now + 365L * 10 * 24 * 3600 * 1000)

        val rootDer = buildCert(
            subjectCn = CN_ROOT,
            issuerCn = CN_ROOT,
            subjectKey = rootPair.public,
            signingKey = rootPair.private,
            notBefore = nb,
            notAfter = na,
            ca = true,
            keyUsage = false,
            addSki = false
        )
        val hostDer = buildCert(
            subjectCn = CN_HOST,
            issuerCn = CN_ROOT,
            subjectKey = hostPair.public,
            signingKey = rootPair.private,
            notBefore = nb,
            notAfter = na,
            ca = false,
            keyUsage = true,
            addSki = false
        )
        val deviceDer = buildCert(
            subjectCn = CN_DEVICE,
            issuerCn = CN_ROOT,
            subjectKey = devicePub,
            signingKey = rootPair.private,
            notBefore = nb,
            notAfter = na,
            ca = false,
            keyUsage = true,
            addSki = true
        )

        return Material(
            hostId = hostId,
            hostCertPem = Der.pem("CERTIFICATE", hostDer),
            hostKeyPem = Der.pem("PRIVATE KEY", hostPair.private.encoded),
            hostKey = hostPair.private,
            deviceCertPem = Der.pem("CERTIFICATE", deviceDer),
            rootCertPem = Der.pem("CERTIFICATE", rootDer),
            rootKeyPem = Der.pem("PRIVATE KEY", rootPair.private.encoded),
            rootKey = rootPair.private
        )
    }

    /** 新 RSA 密钥对（与 Apple 实现一致：2048 bit / e=65537）。 */
    fun newRsa(bits: Int = 2048): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    /**
     * 设备公钥。`lockdownd` 在不同版本上给过 PEM 文本、PKCS#1 DER、SPKI DER 三种形态，
     * 这里按「PEM → SPKI → PKCS#1」顺序试。
     */
    fun rsaPublicKey(raw: ByteArray): RSAPublicKey {
        require(raw.isNotEmpty()) { "DevicePublicKey 为空" }
        val der = if (Der.isPem(raw)) Der.pemBody(raw) else raw
        runCatching {
            return KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
        }
        // PKCS#1: SEQUENCE { modulus INTEGER, publicExponent INTEGER }
        val kids = Der.children(der)
        require(kids.size >= 2) { "设备公钥既不是 SPKI 也不是 PKCS#1（首字节 0x%02x）".format(der[0]) }
        fun int(i: Int): BigInteger {
            val (s, l) = kids[i]
            return BigInteger(der.copyOfRange(s, s + l))
        }
        return KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(int(0), int(1))) as RSAPublicKey
    }

    /** 解析 PKCS#8 / PKCS#1 的 PEM 私钥。 */
    fun privateKey(pem: String): PrivateKey {
        val der = Der.pemBody(pem.toByteArray(Charsets.US_ASCII))
        runCatching {
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        }
        // PKCS#1：SEQUENCE { version, n, e, d, p, q, dp, dq, qinv }
        val kids = Der.children(der)
        require(kids.size >= 9) { "私钥字段不足（${kids.size}）" }
        val ints = kids.map { (s, l) -> BigInteger(der.copyOfRange(s, s + l)) }
        val spec = java.security.spec.RSAPrivateCrtKeySpec(
            ints[1], ints[2], ints[3], ints[4], ints[5], ints[6], ints[7], ints[8]
        )
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    /** 用私钥的 n/e 反推公钥（装进 KeyStore 时要成对）。 */
    fun keyPairOf(key: PrivateKey): KeyPair {
        val crt = key as RSAPrivateCrtKey
        val pub = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(crt.modulus, crt.publicExponent))
        return KeyPair(pub, key)
    }

    /**
     * 签发一张证书。
     *
     * ## DN 为什么不是空的（与 PC 那套实现的唯一偏差）
     *
     * libimobiledevice / pymobiledevice3 用的是**空 DN**（`30 00`）。设备侧（libcrypto）能吃，
     * 但 **JDK 的 `X509CertImpl` 会直接拒**：`Empty issuer DN not allowed in X509Certificates`——
     * 而 App 里把证书装进 `KeyStore` 走的正是 Java 这条路。
     *
     * 设备端只校验「签名链是否由它存下的 RootCertificate 认证」，DN 内容不参与任何判断，
     * 所以这里填 `CN=` 既不改协议语义，又让 JDK/Conscrypt 两侧都能解析（已有单测覆盖）。
     */
    private fun buildCert(
        subjectCn: String,
        issuerCn: String,
        subjectKey: PublicKey,
        signingKey: PrivateKey,
        notBefore: java.util.Date,
        notAfter: java.util.Date,
        ca: Boolean,
        keyUsage: Boolean,
        addSki: Boolean
    ): ByteArray {
        val spki = subjectKey.encoded                       // X.509 SubjectPublicKeyInfo 现成的
        val sigAlg = Der.seq(Der.oid(OID_SHA256_RSA), Der.nullValue())

        val exts = ArrayList<ByteArray>(3)
        exts.add(extension(OID_BASIC_CONSTRAINTS, true, Der.seq(Der.boolean(ca))))
        if (keyUsage) {
            exts.add(extension(OID_KEY_USAGE, true, Der.bitString(KEY_USAGE_BITS, KEY_USAGE_UNUSED)))
        }
        if (addSki) {
            val keyId = MessageDigest.getInstance("SHA-1").digest(spkiKeyBits(spki))
            exts.add(extension(OID_SUBJECT_KEY_ID, false, Der.octetString(keyId)))
        }

        val tbs = Der.seq(
            Der.explicit(0, Der.integer(2)),                // version v3
            Der.integer(1),                                 // serial
            sigAlg,
            derName(issuerCn),
            Der.seq(Der.utcTime(notBefore), Der.utcTime(notAfter)),
            derName(subjectCn),
            spki,
            Der.explicit(3, Der.seqOf(exts))
        )

        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(signingKey)
            update(tbs)
            sign()
        }
        return Der.seq(tbs, sigAlg, Der.bitString(sig))
    }

    /** `Name ::= SEQUENCE { RDNSequence }`，这里只放一个 `CN`（UTF8String）。 */
    private fun derName(cn: String): ByteArray = Der.seq(
        Der.tlv(0x31, Der.seq(Der.oid(OID_COMMON_NAME), Der.tlv(0x0C, cn.toByteArray(Charsets.UTF_8))))
    )

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) Der.seq(Der.oid(oid), Der.boolean(true), Der.octetString(value))
        else Der.seq(Der.oid(oid), Der.octetString(value))

    /** 取 SPKI 里 BIT STRING 的那段密钥位串（SKI 的算法输入）。 */
    private fun spkiKeyBits(spki: ByteArray): ByteArray {
        val kids = Der.children(spki)
        require(kids.size >= 2) { "SPKI 结构异常" }
        val (s, l) = kids[1]
        return spki.copyOfRange(s + 1, s + l)               // 跳过 unused-bits 字节
    }

    /** 证书链 PEM 拼成一个字节流，供 `CertificateFactory` 逐张解析。 */
    fun chainStream(vararg pems: ByteArray): ByteArray = Der.concat(pems.toList())

    /** 从 PEM 证书取 DER（给 KeyStore 之外的路径用）。 */
    fun derOf(pem: ByteArray): ByteArray = Der.pemBody(pem)

    /** 仅供自检：PEM 的 base64 是否可解。 */
    fun selfCheck(pem: ByteArray): Boolean = runCatching {
        B64.decode(
            String(pem, Charsets.US_ASCII).lineSequence()
                .filter { !it.startsWith("-----") && it.isNotBlank() }.joinToString("")
        )
    }.isSuccess
}
