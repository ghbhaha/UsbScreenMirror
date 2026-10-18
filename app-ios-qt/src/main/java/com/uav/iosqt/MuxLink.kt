package com.uav.iosqt

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.uav.protocol.ios.Lockdown
import com.uav.protocol.ios.PlistXml
import com.uav.protocol.ios.PValue

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
 *
 * ## 两种用法
 *
 * - **会话模式** `open()`：QueryType → StartSession →（TLS）→ GetValue，用于投屏时维持
 *   「受信任主机在线」这个前提；
 * - **配对模式** `openRaw()` + `request()`：由 [Pairing] 组合出 GetValue(DevicePublicKey)
 *   → GetValue 全量 → Pair 这套序列。
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

    /** 已收到的 lockdownd 应答条数（配对流程靠它做「等下一条」的同步）。 */
    @Volatile
    var replyCount = 0
        private set

    /** 最近一条应答（已解析）；解析失败时对应该条原文见 [lastReplyText]。 */
    @Volatile
    var lastReply: PValue? = null
        private set

    @Volatile
    var lastReplyText: String? = null
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

    /**
     * 只把隧道与 lockdownd 连起来并完成 QueryType —— 不做 StartSession、不升 TLS。
     *
     * 配对必须走这条路：设备**尚未**信任我们，配对的 `GetValue(DevicePublicKey)` / `Pair`
     * 都在明文 lockdown 上进行。
     */
    fun openRaw(): Boolean {
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
        val hdr16 = pre.size >= 12 && u32be(pre, 8) == MAGIC
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

        // --- E) QueryType ---
        val qt = request(Lockdown.queryType("UsbScreenMirror"))
        if (qt == null) { failed = failed ?: "QueryType 无回复"; return false }
        log("← QueryType: Type=${qt.str("Type")} ProductVersion=${qt.str("ProductVersion")}")
        return true
    }

    /**
     * 会话模式：QueryType（已在 [openRaw] 做完）→ StartSession → TLS → GetValue。
     *
     * 返回 true 只代表"流程跑完且没炸"，是否真受信任看 [trusted]。
     */
    fun open(): Boolean {
        if (!openRaw()) return false
        val id = identity ?: return true
        return establishSession(id)
    }

    /**
     * 用给定身份走 StartSession → TLS → GetValue。
     *
     * 单独抽出来是为了让**配对刚做完**的那条连接能立刻自证一次（不必让用户再点一次开始采集）。
     */
    fun establishSession(id: Identity): Boolean {
        val started = request(Lockdown.startSession("UsbScreenMirror", id.hostId, id.systemBuid))
        if (started == null) {
            log("✗ StartSession 无回复：${failed ?: "超时"}")
            return false
        }
        if (started.str("Error") != null) {
            log("✗ StartSession 被拒：${started.str("Error")}")
            if (started.str("Error") == "InvalidHostID") failed = "设备不认这个 HostID（配对记录无效）"
            return false
        }
        if (started.bool("EnableSessionSSL") != true) {
            log("（未要求 SSL，停在明文）")
            return true
        }

        sslRequired = true
        log("→ 升级 TLS（客户端证书 = ${if (id.selfPaired) "App 自签" else "导入的 PC 配对证书"}）")
        val t = TlsTunnel(id, log)
        tls = t
        val hello = t.begin()
        log("  → ClientHello ${hello.size}B hex=${hex(hello, minOf(hello.size, 24))}")
        if (hello.isNotEmpty()) sendTcp(0x10, hello)

        val t3 = System.currentTimeMillis()
        while (!tlsReady && failed == null && System.currentTimeMillis() - t3 < 8000) pumpOnce(300)
        if (!tlsReady) {
            log("✗ TLS 未完成：${failed ?: "超时"}（${t.describe()}）")
            return false
        }

        log("→ GetValue（判决信任是否成立）")
        val v = request(Lockdown.getValue("UsbScreenMirror", key = "UniqueDeviceID"))
        if (v != null && v.str("Value") != null) {
            trusted = true
            log("★★★ GetValue 成功 —— 设备确认本主机已受信任（UDID=${v.str("Value")}）")
        } else if (v != null) {
            log("✗ GetValue 返回异常：${v.str("Error") ?: "无 Value"}")
        }

        // ★ 设备侧「亮屏解锁」是 QT 推流的隐含前提：息屏/锁屏时端点照样能连，但一个字节都不吐。
        //   排「已信任但 0 B」时先看这两行，比反复重启设备省事。
        val all = request(Lockdown.getValue("UsbScreenMirror"))?.dict("Value")
        if (all != null) {
            log(
                "   设备状态：屏幕${if (all.bool("ScreenLocked") == true) "已锁定" else "已解锁"}" +
                    " / 是否设密码=${all.bool("PasswordProtected") ?: "?"}" +
                    " / iOS=${all.str("ProductVersion") ?: "?"}" +
                    " / 机型=${all.str("ProductType") ?: "?"}"
            )
        }
        return true
    }

    /**
     * 发一条 lockdown 请求并等**下一条**应答。
     *
     * 用 [replyCount] 做序号配对，而不是"内容里含某个关键字" —— 后者在配对流程里会误判
     * （GetValue 全量回包里也带 `Pair` 字段）。
     */
    fun request(plist: ByteArray, timeoutMs: Int = 8000): PValue? {
        val base = replyCount
        sendLockdown(plist)
        val t0 = System.currentTimeMillis()
        while (replyCount == base && failed == null && System.currentTimeMillis() - t0 < timeoutMs) {
            pumpOnce(300)
        }
        if (replyCount == base) return null
        return lastReply
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
            if (flen < 16 || flen > 65536) {
                log("  非法 mux 帧长 $flen，清空重同步")
                acc = ByteArray(0)
                break
            }
            if (acc.size < flen) break
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

    /** 从 tunnel 里切出完整的 lockdownd 消息并记录。 */
    private fun drainTunnel(): Int {
        var handled = 0
        while (true) {
            if (tunnel.size < 4) break
            val mlen = u32be(tunnel, 0)
            if (mlen <= 0 || mlen > 0x200000 || tunnel.size < 4 + mlen) break
            val msg = tunnel.copyOfRange(4, 4 + mlen)
            tunnel = tunnel.copyOfRange(4 + mlen, tunnel.size)
            handled++
            onLockdown(msg)
        }
        return handled
    }

    private fun onLockdown(msg: ByteArray) {
        val text = String(msg, Charsets.UTF_8)
        val parsed = PlistXml.parse(text)
        lastReplyText = text
        lastReply = parsed
        replyCount++

        val flat = text.replace("\n", " ").replace("\r", "").replace(Regex("\\s+"), " ")
        val err = parsed?.str("Error")
        if (err != null) log("← lockdownd 报错 Error=$err")
        else log("← lockdownd ${msg.size}B: ${flat.take(160)}")
    }

    /** 后台保活用：只推进管道，不解析语义。 */
    fun keepAliveTick() {
        runCatching { pumpOnce(200) }
    }
}
