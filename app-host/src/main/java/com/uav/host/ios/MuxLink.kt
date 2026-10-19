package com.uav.host.ios

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.uav.protocol.Bytes
import com.uav.protocol.ios.DeviceVersion
import com.uav.protocol.ios.Lockdown
import com.uav.protocol.ios.PValue
import com.uav.protocol.ios.PlistXml

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

    /**
     * 上一次 [request] 是否**收到了**回包（哪怕解析失败）。
     *
     * 用来区分"真超时"和"回包到了但 plist 不认"——后者以前会被报成超时。
     */
    var lastRequestReplied: Boolean = false
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

    /**
     * 会话建立时读到的设备版本（`ProductVersion` / `ProductType`）。
     *
     * ⚠️ **纯诊断**：协议层没有版本差异（见 `DeviceVersion` 类注释），
     *   这里只用来在"跑不起来"时能一眼区分「版本问题」与「会话问题」，
     *   以及给界面一句有依据的提示。**读不到就是 UNKNOWN，不做任何降级。**
     */
    @Volatile
    var deviceVersion: DeviceVersion = DeviceVersion.UNKNOWN
        private set

    @Volatile
    var failed: String? = null
        private set

    // ---------- 字节工具（大端） ----------

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
        Bytes.putIntBE(th, 4, connTx)
        Bytes.putIntBE(th, 8, connAck)
        th[12] = (5 shl 4).toByte()                                  // data offset=5 ⇒ 0x50
        th[13] = flags.toByte()
        Bytes.putShortBE(th, 14, 512)

        val total = 16 + th.size + payload.size
        val pkt = ByteArray(total)
        Bytes.putIntBE(pkt, 0, PROTO_TCP)
        Bytes.putIntBE(pkt, 4, total)
        Bytes.putIntBE(pkt, 8, MAGIC)
        Bytes.putShortBE(pkt, 12, devTx)
        Bytes.putShortBE(pkt, 14, devRx)
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
        Bytes.putIntBE(framed, 0, plist.size)
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
        Bytes.putIntBE(ver, 0, PROTO_VERSION)
        Bytes.putIntBE(ver, 4, 20)
        Bytes.putIntBE(ver, 8, 2)
        val wv = conn.bulkTransfer(epOut, ver, ver.size, 1000)
        if (wv < 0) { failed = "VERSION 写入失败（$wv）"; return false }

        var pre = ByteArray(0)
        val end = System.currentTimeMillis() + 2000
        // ⚠️ 必须攒够 20B 再判：设备的 VERSION 应答是 8B 头 + 12B 体，而一次 `bulkTransfer`
        //   完全可能只回来头那 8B。原来这里写的是 `pre.size < 8` —— 拿到头就退出循环，
        //   紧接着 `pre.size < 20` 判失败，把"只到了一半"错报成"设备没有应答"。
        while (System.currentTimeMillis() < end && pre.size < 20) {
            val n = conn.bulkTransfer(epIn, readBuf, readBuf.size, 300)
            if (n > 0) pre += readBuf.copyOfRange(0, n)
        }
        if (pre.size < 20) { failed = "2s 内无有效 VERSION 回复（${pre.size}B）"; return false }
        val hdr16 = pre.size >= 12 && Bytes.getIntBE(pre, 8) == MAGIC
        val off = if (hdr16) 16 else 8
        val major = Bytes.getIntBE(pre, off)
        log("← mux VERSION major=$major（${hex(pre, 32)}）")
        if (major !in 1..2) { failed = "设备 mux 版本 $major 不在 1..2"; return false }
        val flen = Bytes.getIntBE(pre, 4)
        if (pre.size > flen) acc = pre.copyOfRange(flen, pre.size)

        // --- B) SETUP ---
        val setup = ByteArray(17)
        Bytes.putIntBE(setup, 0, PROTO_SETUP)
        Bytes.putIntBE(setup, 4, 17)
        Bytes.putIntBE(setup, 8, MAGIC)
        Bytes.putShortBE(setup, 12, 0)
        Bytes.putShortBE(setup, 14, 0xFFFF)
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
     *
     * @param tlsWaitMs 等 TLS 握手的时长，到点就带着"半开"的连接继续投屏。
     *   见 [IosFeatures.TLS_WAIT_MS] —— 这个窗口不能省成 0，设备要靠它放行 QT 推流。
     */
    fun open(tlsWaitMs: Long = 8_000L): Boolean {
        if (!openRaw()) return false
        val id = identity ?: return true
        return establishSession(id, tlsWaitMs)
    }

    /**
     * 用给定身份走 StartSession → TLS → GetValue。
     *
     * 单独抽出来是为了让**配对刚做完**的那条连接能立刻自证一次（不必让用户再点一次开始采集）。
     */
    fun establishSession(id: Identity, tlsWaitMs: Long = 8_000L): Boolean {
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
        while (!tlsReady && failed == null && System.currentTimeMillis() - t3 < tlsWaitMs) pumpOnce(300)
        if (!tlsReady) {
            // ★ 这条**不是投屏故障**：TLS 只承载控制面（设备信息/唤醒/事件），
            //   视频音频走 QT 明文通道。iOS 27 上设备本来就不回 ServerHello。
            log("（TLS 未完成：${failed ?: "超时"}（${t.describe()}）—— 只影响控制面，投屏不受影响）")
            return true
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
            // ★ 把版本记下来（纯诊断）。放在这里是因为全量 GetValue 的时机最自然：
            //   紧接着 GetValue 成功、连接还活着，拿到的值最可信。
            deviceVersion = DeviceVersion.fromReply(all)
            log(
                "   设备状态：屏幕${if (all.bool("ScreenLocked") == true) "已锁定" else "已解锁"}" +
                    " / 是否设密码=${all.bool("PasswordProtected") ?: "?"}" +
                    " / ${deviceVersion.summary()}"
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
        // ★ 回报"这次到底有没有回包"：request 返回 null 有两种含义 ——
        //   真超时 / **回包到了但 plist 解析失败**，调用方要能分清（否则日志一律写"超时"）。
        lastRequestReplied = replyCount != base
        if (!lastRequestReplied) return null
        return lastReply
    }

    /** 读一轮；超时返回 0。供握手与后台保活共用。 */
    /**
     * 读一次 mux 通道（保活用）。
     *
     * ★★★ **失败时必须睡满 [timeoutMs]，不能立刻返回**。
     *
     * 实测事故（2026-10-19 16:17）：`bulkTransfer` 在端点失效/USB 半释放时
     * **瞬间返回 -1**（既不超时也不阻塞）。而调用方是这么写的：
     *
     * ```
     * while (keepAlive.get() && link.failed == null) { link.pumpOnce(200) }
     * ```
     *
     * 于是它变成**没有任何休眠的死循环**—— 实测 `mux-keepalive` 线程 5 秒内
     * 烧掉 596 个时钟节拍（约 6 秒 CPU 时间），而主线程/解码线程全都在等它，
     * 表现就是「App 卡死、画面全黑、解码器像是挂了」。
     *
     * ⚠️ 讽刺的是：这个空转把 USB 端点反复 submit/cancel URB，
     *   **正好制造了它自己无法退出的那种状态**（另一轮里 `mux-keepalive`
     *   卡进 D 状态 `usb_start_wait_urb`）⇒ 自锁。
     *
     * 所以：只要这一轮没拿到数据，就保证 [timeoutMs] 到点再返回，
     * 让调用方每次迭代都有 200ms 的天然节流。
     *
     * @return 读到的字节数；`<= 0` 表示本轮没数据（超时或失败）
     */
    fun pumpOnce(timeoutMs: Int): Int {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val n = conn.bulkTransfer(epIn, readBuf, readBuf.size, timeoutMs)
        if (n <= 0) {
            // ★ 端点失效时 bulkTransfer 会瞬间返回，绝不能直接 return ——
            //   必须把剩下的时间睡掉，否则调用方的 while 变成忙等死循环。
            val spent = android.os.SystemClock.elapsedRealtime() - t0
            val left = timeoutMs - spent
            if (left > 0) {
                runCatching { android.os.SystemClock.sleep(left) }
            }
            return n
        }
        acc += readBuf.copyOfRange(0, n)
        var handled = 0
        while (true) {
            if (acc.size < 16) break
            val flen = Bytes.getIntBE(acc, 4)
            if (flen < 16 || flen > 65536) {
                log("  非法 mux 帧长 $flen，清空重同步")
                acc = ByteArray(0)
                break
            }
            if (acc.size < flen) break
            val frame = acc.copyOfRange(0, flen)
            acc = acc.copyOfRange(flen, acc.size)
            devRx = Bytes.getShortBE(frame, 12)
            when (Bytes.getIntBE(frame, 0)) {
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
                    val sport = Bytes.getShortBE(frame, 16)
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
            val mlen = Bytes.getIntBE(tunnel, 0)
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
        val result = PlistXml.parseDetailed(text)
        val parsed = result.getOrNull()
        lastReplyText = text
        lastReply = parsed
        replyCount++

        val flat = text.replace("\n", " ").replace("\r", "").replace(Regex("\\s+"), " ")
        val err = parsed?.str("Error")
        if (err != null) {
            log("← lockdownd 报错 Error=$err")
        } else if (parsed == null) {
            // ★ 解析失败要说清楚**为什么**：`request()` 返回的是 lastReply，解析不出来就等于
            //   "没有应答"，上层只会看到"无回复/超时"，排查时会被带偏。
            //   实测两回事都踩过：一是 `<data/>` 自闭合标签读不出来；二是 `java.util.Base64`
            //   在 Android 7.1 上根本不存在（API 26+ 才有），异常被吞掉后同样只表现为"没回复"。
            log("✗ lockdownd ${msg.size}B 回包**解析失败**：${result.exceptionOrNull()?.message ?: "未知原因"}")
            log("  原文：${flat.take(900)}")
        } else {
            log("← lockdownd ${msg.size}B: ${flat.take(160)}")
        }
    }

    /** 后台保活用：只推进管道，不解析语义。 */
    fun keepAliveTick() {
        runCatching { pumpOnce(200) }
    }

    /** 收尾是否已经做过（FIN 只能发一次）。 */
    @Volatile
    private var shutdownDone = false

    /**
     * 收尾：给设备发一个 **FIN**，让 usbmuxd 释放这条 TCP。
     *
     * ★ 不做这一步，设备侧那条连接会一直停在 ESTABLISHED（USB 没有"连接关闭"的概念，
     *   我们只是把 fd 关了）⇒ 下一次插上时 VERSION 一个字节都等不到
     *   （`2s 内无有效 VERSION 回复（0B）`），只能靠**拔插 iPad / 重启 iPad** 才恢复。
     *   实测这台 V31103 上：成功过一次 VERSION 之后放弃，下一次必然 0B。
     *
     * 幂等：重复调用只发一次。
     */
    fun shutdown(reason: String = "结束会话") {
        if (shutdownDone) return
        shutdownDone = true
        if (!connected) return
        runCatching {
            sendTcp(0x11)            // FIN
            pumpOnce(150)            // 顺手把 FIN/ACK 读掉，别留半包
        }
        log("已向设备发 FIN（$reason），避免下次 VERSION 收不到")
    }
}
