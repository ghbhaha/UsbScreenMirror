package com.uav.iosqt

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

/**
 * usbmux + lockdownd 会话（跑在接口 `0xFF/0xFE/02` 上），可选升级 TLS。
 *
 * ## 为什么需要它
 *
 * 2026-10-18 的决定性对照（见 docs/QT协议Windows复现.md）：
 * 同一台 iPad、同一份 QT 脚本，
 *  - Windows 上 Apple 的常驻服务（AMDS）在跑 → **PING 两次 → CWPA → 1400+ 帧**；
 *  - 把 AMDS 停掉 → **PING 一次就沉默**，与安卓侧此前症状逐项相同。
 *
 * ⇒ 「有一台被设备信任的主机在后台维持 lockdown 会话」是 QT 推流的**硬前置**。
 * 安卓上没有 usbmuxd，所以只能自己在这条 vendor 接口上说 mux 协议。
 *
 * ## 协议事实（usbmuxd/src/device.c 字节级核实 + 本项目真机实证）
 *
 * - mux 帧头 16B **大端**：`protocol | length(含头) | magic=0xfeedface | tx_seq(u16) | rx_seq(u16)`；
 *   只有 VERSION 阶段用 8B 头（`protocol | length`）。
 * - protocol：`0=VERSION 1=CONTROL 2=SETUP 6=TCP`。
 * - VERSION 请求 20B：8B 头 + `major=2 | minor=0 | padding=0`；
 *   设备 v2 的回复用 16B 头（靠 offset 8 是否等于 magic 判别）。
 * - SETUP 载荷固定 `0x07`，`tx_seq=0 / rx_seq=0xFFFF`。
 * - TCP 子头必须是 **20B 的系统 `struct tcphdr`**（16B 会被设备拒 "short tcp header"）；
 *   **Data Offset 写在 byte 12 的高 4 位**（`0x50`，不是 `0x05`）；数据段只带 `TH_ACK(0x10)`，
 *   从不设 PSH；窗口 512（按 256 缩放）。
 * - lockdownd 隧道流 = `[u32 **大端**长度][plist]`，长度不含自身 4 字节；设备回复恒为 XML plist。
 */
internal class MuxLink(
    private val conn: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    /** 有身份就继续做 StartSession + TLS；没有就只停在 QueryType */
    private val identity: Identity?,
    private val log: (String) -> Unit
) {

    companion object {
        const val LOCKDOWN_PORT = 62078
        private const val MAGIC = 0xfeedface.toInt()
        private const val PROTO_VERSION = 0
        private const val PROTO_CONTROL = 1
        private const val PROTO_SETUP = 2
        private const val PROTO_TCP = 6
    }

    private var devTx = 0
    private var devRx = 0
    private var connTx = 0
    private var connAck = 0
    private var connected = false

    private var acc = ByteArray(0)
    private var tunnel = ByteArray(0)
    private val readBuf = ByteArray(4096)

    private var tls: TlsTunnel? = null

    @Volatile
    var lockdownReply: String? = null
        private set

    /** 设备是否要求 SSL（StartSession 的回复里）。 */
    @Volatile
    var sslRequired = false
        private set

    /** TLS 握手是否完成。 */
    @Volatile
    var tlsReady = false
        private set

    /** GetValue 是否成功（= 设备真的把我们当受信任主机）。 */
    @Volatile
    var trusted = false
        private set

    @Volatile
    var failed: String? = null
        private set

    // ---------- 字节工具（大端） ----------

    private fun be32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
    }

    private fun be16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte(); b[off + 1] = v.toByte()
    }

    private fun u32be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun u16be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun hex(b: ByteArray, n: Int): String =
        b.copyOfRange(0, minOf(n, b.size)).joinToString(" ") { "%02x".format(it) }

    // ---------- 发送 ----------

    /** 发一个 mux TCP 段（20B tcphdr + 载荷）。 */
    private fun sendTcp(flags: Int, data: ByteArray? = null) {
        val payload = data ?: ByteArray(0)
        val th = ByteArray(20)
        th[1] = 1                                                    // sport=1
        th[2] = (LOCKDOWN_PORT shr 8).toByte()
        th[3] = (LOCKDOWN_PORT and 0xFF).toByte()                    // dport=62078
        be32(th, 4, connTx)
        be32(th, 8, connAck)
        th[12] = (5 shl 4).toByte()                                  // data offset=5 ⇒ 0x50
        th[13] = flags.toByte()
        be16(th, 14, 512)

        val total = 16 + th.size + payload.size
        val pkt = ByteArray(total)
        be32(pkt, 0, PROTO_TCP)
        be32(pkt, 4, total)
        be32(pkt, 8, MAGIC)
        be16(pkt, 12, devTx)
        be16(pkt, 14, devRx)
        devTx = (devTx + 1) and 0xFFFF
        th.copyInto(pkt, 16)
        payload.copyInto(pkt, 36)

        val w = conn.bulkTransfer(epOut, pkt, pkt.size, 1000)
        if (payload.isNotEmpty()) connTx += payload.size
        if (w < 0) failed = "mux 写失败（$w）"
    }

    /** 发一条 lockdownd 请求：加密后走 TLS，否则明文 `[u32 BE 长度][plist]`。 */
    private fun sendLockdown(plist: ByteArray) {
        val framed = ByteArray(4 + plist.size)
        be32(framed, 0, plist.size)
        plist.copyInto(framed, 4)
        val t = tls
        if (t == null) {
            sendTcp(0x10, framed)
        } else {
            val cipher = t.writeApp(framed)
            if (cipher.isNotEmpty()) sendTcp(0x10, cipher)
        }
    }

    // ---------- 建立 ----------

    fun open(): Boolean {
        // --- A) VERSION（8B 头 + major/minor/padding） ---
        val ver = ByteArray(20)
        be32(ver, 0, PROTO_VERSION)
        be32(ver, 4, 20)
        be32(ver, 8, 2)
        val wv = conn.bulkTransfer(epOut, ver, ver.size, 1000)
        if (wv < 0) { failed = "VERSION 写入失败（$wv）"; return false }

        var pre = ByteArray(0)
        val end = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < end && pre.size < 8) {
            val n = conn.bulkTransfer(epIn, readBuf, readBuf.size, 300)
            if (n > 0) pre += readBuf.copyOfRange(0, n)
        }
        if (pre.size < 20) { failed = "2s 内无有效 VERSION 回复（${pre.size}B）"; return false }
        val hdr16 = u32be(pre, 8) == MAGIC
        val off = if (hdr16) 16 else 8
        val major = u32be(pre, off)
        log("← mux VERSION major=$major（${hex(pre, 32)}）")
        if (major !in 1..2) { failed = "设备 mux 版本 $major 不在 1..2"; return false }
        val flen = u32be(pre, 4)
        if (pre.size > flen) acc = pre.copyOfRange(flen, pre.size)

        // --- B) SETUP ---
        val setup = ByteArray(17)
        be32(setup, 0, PROTO_SETUP)
        be32(setup, 4, 17)
        be32(setup, 8, MAGIC)
        be16(setup, 12, 0)
        be16(setup, 14, 0xFFFF)
        setup[16] = 0x07
        devTx = 1
        conn.bulkTransfer(epOut, setup, setup.size, 1000)

        // --- C) SYN → 设备 62078 ---
        connTx = 0; connAck = 0
        sendTcp(0x02)
        log("→ TCP SYN → :$LOCKDOWN_PORT（lockdownd）")

        // --- D) 等 SYN/ACK ---
        val t0 = System.currentTimeMillis()
        while (!connected && failed == null && System.currentTimeMillis() - t0 < 5000) pumpOnce(300)
        if (!connected) { failed = failed ?: "5s 内没等到 SYN/ACK"; return false }

        // --- E) 等 QueryType 回复 ---
        val t1 = System.currentTimeMillis()
        while (lockdownReply == null && failed == null && System.currentTimeMillis() - t1 < 5000) pumpOnce(300)
        if (lockdownReply == null) { failed = failed ?: "QueryType 无回复"; return false }

        // --- F) StartSession → TLS → GetValue ---
        val id = identity ?: return true
        log("→ StartSession（HostID=${id.hostId}）")
        sendLockdown(Plist.startSession(id.hostId, id.systemBuid))

        val t2 = System.currentTimeMillis()
        while (tls == null && failed == null && System.currentTimeMillis() - t2 < 8000) pumpOnce(300)
        if (tls == null) { log("（未收到 EnableSessionSSL，停在明文）"); return true }

        val t3 = System.currentTimeMillis()
        while (!tlsReady && failed == null && System.currentTimeMillis() - t3 < 8000) pumpOnce(300)
        if (!tlsReady) {
            log("✗ TLS 未完成：${failed ?: "超时"}（${tls?.describe()}）")
            return true
        }
        log("→ GetValue（判决信任是否成立）")
        sendLockdown(Plist.getValue())
        val t4 = System.currentTimeMillis()
        while (!trusted && failed == null && System.currentTimeMillis() - t4 < 5000) pumpOnce(300)
        return true
    }

    /** 读一轮；超时返回 0。供握手与后台保活共用。 */
    fun pumpOnce(timeoutMs: Int): Int {
        val n = conn.bulkTransfer(epIn, readBuf, readBuf.size, timeoutMs)
        if (n <= 0) return n
        acc += readBuf.copyOfRange(0, n)
        var handled = 0
        while (true) {
            if (acc.size < 16) break
            val flen = u32be(acc, 4)
            if (flen < 16 || flen > 65536 || acc.size < flen) {
                if (acc.size < flen) break
                log("  非法 mux 帧长 $flen，清空重同步")
                acc = ByteArray(0)
                break
            }
            val frame = acc.copyOfRange(0, flen)
            acc = acc.copyOfRange(flen, acc.size)
            devRx = u16be(frame, 12)
            when (u32be(frame, 0)) {
                PROTO_CONTROL -> {
                    val p = if (frame.size > 16) frame.copyOfRange(16, frame.size) else ByteArray(0)
                    val code = if (p.isNotEmpty()) p[0].toInt() and 0xFF else -1
                    val kind = when (code) { 3 -> "ERROR"; 5 -> "WARN"; 7 -> "INFO"; else -> "?" }
                    val text = if (p.size > 1) String(p, 1, p.size - 1, Charsets.UTF_8) else ""
                    log("← mux CONTROL[$kind] $text")
                    if (code == 3) failed = "mux CONTROL ERROR: $text"
                }
                PROTO_TCP -> {
                    if (frame.size < 36) continue
                    val sport = u16be(frame, 16)
                    val flags = frame[29].toInt() and 0xFF
                    val payload = if (frame.size > 36) frame.copyOfRange(36, frame.size) else ByteArray(0)
                    if (sport != LOCKDOWN_PORT) continue
                    when {
                        flags == 0x12 && !connected -> {
                            connected = true
                            connTx = 1; connAck = 1
                            log("★★ SYN/ACK：lockdownd 隧道建立")
                            sendTcp(0x10)
                            sendLockdown(Plist.queryType())
                        }
                        flags and 0x04 != 0 -> failed = "设备 RST（${String(payload, Charsets.UTF_8)}）"
                        payload.isNotEmpty() -> {
                            connAck += payload.size
                            val t = tls
                            if (t == null) {
                                tunnel += payload
                                sendTcp(0x10)
                                handled += drainTunnel()
                            } else {
                                val back = t.feed(payload)
                                if (back.isNotEmpty()) sendTcp(0x10, back)
                                val app = t.readApp()
                                if (app.isNotEmpty()) {
                                    tunnel += app
                                    handled += drainTunnel()
                                }
                                if (t.handshaken && !tlsReady) {
                                    tlsReady = true
                                    log("★★ TLS 就绪：${t.describe()}")
                                }
                                if (t.failed != null) failed = "TLS: ${t.failed}"
                            }
                        }
                    }
                }
            }
        }
        return handled
    }

    /** 从 tunnel 里切出完整的 lockdownd 消息并判读。 */
    private fun drainTunnel(): Int {
        var handled = 0
        while (true) {
            if (tunnel.size < 4) break
            val mlen = u32be(tunnel, 0)
            if (mlen <= 0 || mlen > 0x100000 || tunnel.size < 4 + mlen) break
            val msg = tunnel.copyOfRange(4, 4 + mlen)
            tunnel = tunnel.copyOfRange(4 + mlen, tunnel.size)
            handled++
            onLockdown(msg)
        }
        return handled
    }

    private fun onLockdown(msg: ByteArray) {
        val text = String(msg, Charsets.UTF_8)
        val flat = text.replace("\n", " ").replace("\r", "").replace(Regex("\\s+"), " ")

        if (lockdownReply == null) {
            lockdownReply = text
            log("← lockdownd ${msg.size}B: ${flat.take(140)}")
            return
        }
        if (flat.contains("EnableSessionSSL")) {
            val ssl = flat.contains("<key>EnableSessionSSL</key> <true/>") ||
                flat.contains("<key>EnableSessionSSL</key><true/>")
            log("← StartSession 回复：EnableSessionSSL=$ssl ${flat.take(120)}")
            if (ssl) {
                sslRequired = true
                val id = identity
                if (id != null) {
                    log("→ 升级 TLS（客户端证书 = 导入的配对证书）")
                    val t = TlsTunnel(id, log)
                    tls = t
                    val hello = t.begin()
                    log("  → ClientHello ${hello.size}B hex=${hex(hello, minOf(hello.size, 24))}")
                    if (hello.isNotEmpty()) sendTcp(0x10, hello)
                }
            }
            return
        }
        if (flat.contains("TrustedHostAttached")) {
            trusted = true
            log("★★★ GetValue 成功 —— 设备确认本主机已受信任")
            log("    ${flat.take(400)}")
            return
        }
        val err = Regex("<key>Error</key>\\s*<string>([^<]+)</string>").find(flat)?.groupValues?.get(1)
        if (err != null) {
            log("✗ lockdownd 报错：Error=$err")
            if (err == "InvalidHostID") failed = "设备不认这个 HostID（配对记录无效）"
            return
        }
        log("← lockdownd ${msg.size}B: ${flat.take(160)}")
    }
}
