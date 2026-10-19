package com.uav.host.ios

import com.uav.protocol.ios.CertKit
import com.uav.protocol.ios.PairRecord
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/**
 * 主机身份（客户端证书链 + 私钥），lockdown 升级 TLS 时要用。
 *
 * 两条来源，优先级如下：
 *
 * 1. **App 自己配出来的**（`filesDir/ios_pair.properties`，见 [Pairing]）—— 目标形态，
 *    用户只需在 iPad 上点一次「信任」；
 * 2. 退化来源：assets 里导入的 PC 配对凭证（`pc_hostid.txt` / `pc_host.der` / `pc_root.der` /
 *    `pc_host_key.pem`）。留着是为了**对照实验**：当自配对出问题时，能一键排除"证书本身"这条嫌疑。
 */
internal class Identity(
    val hostId: String,
    val systemBuid: String,
    /** 原样字节，PEM 或 DER 都行（`CertificateFactory` 两种都吃） */
    val chain: List<ByteArray>,
    val key: java.security.PrivateKey,
    /** true = App 自己配的；false = 从 PC 导入 */
    val selfPaired: Boolean
) {

    companion object {

        /** 旧版单文件名（一台设备一个文件后只剩迁移用途）。 */
        const val PAIR_FILE = Pairing.LEGACY_PAIR_FILE

        fun fromRecord(rec: PairRecord): Identity = Identity(
            hostId = rec.hostId,
            systemBuid = rec.systemBuid,
            chain = listOf(rec.hostCertPem, rec.rootCertPem),
            key = CertKit.privateKey(String(rec.hostKeyPem, Charsets.US_ASCII)),
            selfPaired = true
        )

        /**
         * 读**这台设备**的配对记录；没有再退回 assets。
         *
         * @param deviceKey [Pairing.deviceKey]（USB 序列号）；取不到传 [Pairing.KEY_UNKNOWN]
         */
        fun load(
            ctx: android.content.Context,
            log: (String) -> Unit,
            deviceKey: String = Pairing.KEY_UNKNOWN,
        ): Identity? {
            val rec = Pairing.loadRecord(ctx.filesDir, deviceKey, log)
            if (rec != null) {
                runCatching {
                    val id = fromRecord(rec)
                    log(
                        "已载入 App 自建配对记录：HostID=${id.hostId}" +
                            "（设备 ${rec.deviceSerial ?: "未知"}）"
                    )
                    return id
                }.onFailure { log("（自建配对记录读取失败：${it.message}，回退 assets）") }
            }
            return loadFromAssets(ctx, log)
        }

        /**
         * 从 assets 读 `pc_hostid.txt`（两行：HostID / SystemBUID）、`pc_host.der`、
         * `pc_root.der`、`pc_host_key.pem`（PKCS#8）。缺任何一个就返回 null。
         */
        fun loadFromAssets(ctx: android.content.Context, log: (String) -> Unit): Identity? = runCatching {
            val ids = ctx.assets.open("pc_hostid.txt").bufferedReader().use { it.readLines() }
            val hostDer = ctx.assets.open("pc_host.der").use { it.readBytes() }
            val rootDer = ctx.assets.open("pc_root.der").use { it.readBytes() }
            val keyPem = ctx.assets.open("pc_host_key.pem").bufferedReader().use { it.readText() }
            val key = CertKit.privateKey(keyPem)
            log("已导入 PC 配对凭证：HostID=${ids[0].trim()} 证书 ${hostDer.size}B / 根 ${rootDer.size}B")
            Identity(ids[0].trim(), ids[1].trim(), listOf(hostDer, rootDer), key, selfPaired = false)
        }.getOrElse {
            log("（未导入 PC 凭证：${it.message}）")
            null
        }
    }
}

/**
 * 把 [SSLEngine] 套在 **mux TCP 隧道**上 —— 我们的字节流不在真 socket 上，用不了 SSLSocket。
 *
 * 触发条件：lockdownd 的 `StartSession` 回 `EnableSessionSSL=true`，此后整条流必须加密，
 * 客户端证书就是配对时那条 `HostCertificate`/`HostPrivateKey`。
 *
 * 取舍：只启用 TLS1.2/1.1/1.0（iOS 这套 lockdown TLS 不走 1.3）；不校验服务端
 * （我们是自签主机、对面是设备自签），只要通道能用。
 */
internal class TlsTunnel(
    private val identity: Identity?,
    private val logf: (String) -> Unit
) {

    private val engine: SSLEngine = buildEngine()
    private var netIn: java.nio.ByteBuffer = java.nio.ByteBuffer.allocate(64 * 1024)
    private var appIn: java.nio.ByteBuffer = java.nio.ByteBuffer.allocate(256 * 1024)
    private val pendingOut = java.io.ByteArrayOutputStream()

    var handshaken = false
        private set
    var failed: String? = null
        private set

    private fun buildEngine(): SSLEngine {
        val ctx = SSLContext.getInstance("TLS")
        var kms: Array<javax.net.ssl.KeyManager>? = null
        val id = identity
        if (id != null) {
            runCatching {
                val cf = CertificateFactory.getInstance("X.509")
                val stream = java.io.ByteArrayInputStream(id.chain.fold(ByteArray(0)) { a, b -> a + b })
                val chain = cf.generateCertificates(stream).toTypedArray()
                val ks = KeyStore.getInstance("PKCS12")
                ks.load(null, null)
                val pw = charArrayOf('u', 'a', 'v')
                ks.setKeyEntry("host", id.key, pw, chain)
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(ks, pw)
                kms = kmf.keyManagers
                logf("  TLS 客户端证书装入 KeyStore（链长 ${chain.size}）")
            }.onFailure { logf("  ⚠ KeyStore 装配失败: ${it.message}") }
        }
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        ctx.init(kms, arrayOf(tm), SecureRandom())
        return ctx.createSSLEngine().apply {
            useClientMode = true
            val want = arrayOf("TLSv1.2", "TLSv1.1", "TLSv1")
            val sup = supportedProtocols?.toSet() ?: emptySet()
            val use = want.filter { it in sup }
            if (use.isNotEmpty()) enabledProtocols = use.toTypedArray()
            logf("  TLS 协议=${enabledProtocols.joinToString()}")
        }
    }

    /** 启动握手，返回要写进隧道的首包（ClientHello）。 */
    fun begin(): ByteArray {
        engine.beginHandshake()
        pumpWrap()
        return drainOut()
    }

    /** 喂一段隧道密文；返回需要回写的密文。 */
    fun feed(cipher: ByteArray): ByteArray {
        if (cipher.isEmpty()) return ByteArray(0)
        if (netIn.remaining() < cipher.size) {
            val grown = java.nio.ByteBuffer.allocate(netIn.position() + cipher.size + 65536)
            netIn.flip(); grown.put(netIn); netIn = grown
        }
        netIn.put(cipher)
        netIn.flip()
        var guard = 0
        while (netIn.hasRemaining() && guard++ < 64) {
            val r = try {
                engine.unwrap(netIn, appIn)
            } catch (e: SSLException) {
                failed = e.message
                logf("  ✗ TLS unwrap: ${e.message}")
                break
            }
            if (r.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks()
            when (r.status) {
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> break
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    appIn.flip()
                    val grown = java.nio.ByteBuffer.allocate(appIn.capacity() * 2)
                    grown.put(appIn); appIn = grown
                }
                SSLEngineResult.Status.CLOSED -> { failed = "对端关闭 TLS"; break }
                else -> {}
            }
            if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) pumpWrap()
            if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) markHandshaken()
        }
        netIn.compact()
        return drainOut()
    }

    fun readApp(): ByteArray {
        if (appIn.position() == 0) return ByteArray(0)
        appIn.flip()
        val out = ByteArray(appIn.remaining())
        appIn.get(out)
        appIn.clear()
        return out
    }

    fun writeApp(plain: ByteArray): ByteArray {
        val src = java.nio.ByteBuffer.wrap(plain)
        var guard = 0
        while (src.hasRemaining() && guard++ < 32) {
            val buf = java.nio.ByteBuffer.allocate(engine.session.packetBufferSize + plain.size + 64)
            val r = engine.wrap(src, buf)
            buf.flip()
            val chunk = ByteArray(buf.remaining())
            buf.get(chunk)
            pendingOut.write(chunk)
            if (r.status != SSLEngineResult.Status.OK) break
        }
        return drainOut()
    }

    private fun runTasks() {
        var t = engine.delegatedTask
        while (t != null) { t.run(); t = engine.delegatedTask }
    }

    private fun pumpWrap() {
        var guard = 0
        while (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP && guard++ < 16) {
            val buf = java.nio.ByteBuffer.allocate(engine.session.packetBufferSize + 128)
            val r = try {
                engine.wrap(java.nio.ByteBuffer.allocate(0), buf)
            } catch (e: SSLException) {
                failed = e.message
                logf("  ✗ TLS wrap: ${e.message}")
                return
            }
            buf.flip()
            val chunk = ByteArray(buf.remaining())
            buf.get(chunk)
            if (chunk.isNotEmpty()) pendingOut.write(chunk)
            if (r.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) markHandshaken()
            if (r.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks()
        }
    }

    private fun markHandshaken() {
        if (!handshaken) {
            handshaken = true
            logf("  ★★ TLS 握手完成（${engine.session.protocol} / ${engine.session.cipherSuite}）")
        }
    }

    private fun drainOut(): ByteArray {
        val bytes = pendingOut.toByteArray()
        pendingOut.reset()
        return bytes
    }

    fun describe(): String =
        if (handshaken) "${engine.session.protocol}/${engine.session.cipherSuite}" else "未握手"
}
