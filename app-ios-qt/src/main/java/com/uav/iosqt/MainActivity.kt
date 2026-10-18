package com.uav.iosqt

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 最小宿主：一个按钮跑完「激活 → 等采集配置 → claim → 握手 → 收流」。
 *
 * 逻辑完全照 Windows 上已验证通过的那份（tools/qt-repro），
 * 不做双配置仪式、不做 clearHalt、不做控制 kick。
 */
class MainActivity : Activity() {

    private lateinit var usb: UsbManager
    private lateinit var transport: QtTransport
    private lateinit var logView: TextView
    private lateinit var btn: Button
    private lateinit var preview: SurfaceView

    /** 预览用的 Surface；解码器要等它和 SPS/PPS 都就绪才能起 */
    private var surface: Surface? = null
    private var pendingCsd: ByteArray? = null
    private var decoder: H264Decoder? = null

    private val running = AtomicBoolean(false)
    private var permissionLatch: CountDownLatch? = null

    private val logFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != QtTransport.ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            log("权限广播：granted=$granted")
            permissionLatch?.countDown()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        logView = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        btn = Button(this).apply {
            text = "开始采集"
            setOnClickListener { if (!running.get()) start() }
            // 长按 = 离线回放已录制的 feed.bin（验证解码/渲染链路，不依赖设备）
            setOnLongClickListener { if (!running.get()) { replay(); true } else false }
        }
        preview = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    surface = h.surface
                    log("预览 Surface 就绪")
                    ensureDecoder()
                }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {
                    surface = null
                    decoder?.stop()
                    decoder = null
                }
            })
        }
        root.addView(btn)
        root.addView(
            preview,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f
            )
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        setContentView(root)

        transport = QtTransport(this, usb, ::log)
        registerReceiver(
            permissionReceiver,
            IntentFilter(QtTransport.ACTION_USB_PERMISSION),
            RECEIVER_NOT_EXPORTED
        )
        log("QuickTime USB 采集（Android）")
        log("用法：iPad 重启 → 解锁亮屏 → 插线 → 点「开始采集」")
        log("（长按按钮 = 回放已录制的 feed.bin，用于离线验证解码）")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(permissionReceiver) }
        super.onDestroy()
    }

    /** 离线回放 filesDir/feed.bin：验证「解析 → 解码 → 上屏」整条链路。 */
    private fun replay() {
        running.set(true)
        Thread({
            val f = File(getExternalFilesDir(null) ?: filesDir, "feed.bin")
            if (!f.exists() || f.length() < 1024) {
                log("没有可回放的 feed.bin（先采一次）")
                running.set(false); return@Thread
            }
            log("回放 ${f.absolutePath}（${f.length()} B）")
            runCatching {
                f.inputStream().buffered().use { ins ->
                    var n = 0
                    var sent = 0
                    while (true) {
                        val hdr = ByteArray(4)
                        if (ins.read(hdr) != 4) break
                        // 落盘时用的是 QtPacket.u32（**小端**），这里必须按小端读回
                        val total = ((hdr[3].toInt() and 0xFF) shl 24) or ((hdr[2].toInt() and 0xFF) shl 16) or
                            ((hdr[1].toInt() and 0xFF) shl 8) or (hdr[0].toInt() and 0xFF)
                        if (total <= 0 || total > (8 shl 20)) break
                        val payload = ByteArray(total)
                        var off = 0
                        while (off < total) {
                            val r = ins.read(payload, off, total - off)
                            if (r <= 0) break
                            off += r
                        }
                        if (off < total) break
                        n++
                        if (pendingCsd == null) SampleParser.csd(payload)?.let {
                            pendingCsd = it
                            log("★ 回放中解出 SPS/PPS ${it.size}B")
                            ensureDecoder()
                        }
                        SampleParser.frame(payload)?.let { fr ->
                            decoder?.submit(fr)
                            sent++
                        }
                        if (sent % 30 == 0) Thread.sleep(16)
                        if (sent >= 600) break
                    }
                    log("回放结束：包 $n / 送解码 $sent")
                }
            }.onFailure { log("回放异常：${it.message}") }
            running.set(false)
        }, "replay").start()
    }

    /** Surface 与 SPS/PPS 都到位时启动解码器。 */
    private fun ensureDecoder() {
        val sf = surface ?: return
        val csd = pendingCsd ?: return
        if (decoder != null) return
        decoder = H264Decoder(sf, ::log).also { it.start(csd) }
    }

    private fun log(msg: String) {
        val line = "${logFmt.format(Date())}  $msg"
        // 同时打到 logcat：UI 文本要 uiautomator dump 才读得到，logcat 一条命令就能跟
        android.util.Log.i("UAV-IosQt", msg)
        runOnUiThread {
            logView.append(line + "\n")
        }
    }

    private fun start() {
        running.set(true)
        btn.isEnabled = false
        Thread({ runCapture() }, "ios-qt").start()
    }

    private fun runCapture() {
        val stats = runCatching { capture() }.getOrElse { t ->
            log("异常：${t.javaClass.simpleName}: ${t.message}")
            null
        }
        running.set(false)
        runOnUiThread { btn.isEnabled = true }
        if (stats != null) {
            log("=== 结束：PING ${stats.ping} / CWPA ${stats.cwpa} / CVRP ${stats.cvrp} / " +
                "FEED ${stats.feed}（${stats.feedBytes} B） / 共 ${stats.bytes} B ===")
            if (stats.feed > 0) log("★ 已拿到视频流")
        }
    }

    private fun capture(): QtSession.Stats? {
        // 1) 找设备
        var dev = transport.findAppleDevice()
        if (dev == null) {
            log("未找到 Apple 设备（VID 05ac）。请确认线已连到宿主。")
            return null
        }
        log("设备 ${dev.deviceName} pid=0x${dev.productId.toString(16)}")

        // 2) 权限
        if (!ensurePermission(dev)) {
            log("未取得权限，中止")
            return null
        }

        // 3) ★ 若设备仍停在采集配置（上次会话的残留），**先复原再激活**。
        //    这就是「每次都要重启 iPad」的根因：设备一直留在配置 5，activate 就成了空操作。
        //    iPhoneMirror 每次收流结束都会做 usb_configuration_restore，所以它能反复开。
        if (dev.configurationCount >= 5) {
            // ★ 复用上次会话的关键：把配置从 5 切回 3，再切回 5 —— 设备侧的 QT 会话就重新武装了。
            //   实测（14:30）这一步之后 PING 又来了两次，**完全不用重启 iPad**。
            //   不用等重枚举：设备不会重枚举，配置来回一次就够（约 0.3s）。
            log("设备停在采集配置（${dev.configurationCount} 个配置 = 上次会话残留）⇒ 配置 3→5 重新武装")
            restoreToNormal(dev)
            Thread.sleep(300)
            log("  已切回普通配置，准备重新进入采集配置")
        }

        // 4) 没有采集接口就 activate 并等重枚举
        if (transport.findCaptureInterface(dev) == null) {
            val conn0 = transport.open(dev) ?: return null
            try {
                if (!transport.activate(conn0)) {
                    log("activate 失败，中止")
                    return null
                }
            } finally {
                runCatching { conn0.close() }
            }
            log("已请求激活，等待设备重枚举（最长 300s；实测可能延迟 2~3 分钟）…")
            dev = waitForCaptureMode(300)
            if (dev == null) {
                log("✗ 300s 内没有出现采集接口。设备需要物理重启（解锁亮屏）。")
                return null
            }
        } else {
            log("设备已在采集配置，跳过激活")
        }

        // 4) 重枚举后要重新拿权限
        if (!ensurePermission(dev)) {
            log("重枚举后未取得权限，中止")
            return null
        }

        // 5) 先把配置切到承载 0x2A 的那个（等价 Windows 的 set_configuration(5)）。
        //    只切这一次 —— 之后建 lockdown 会话、claim QT 都不再动配置。
        transport.dumpConfigs(dev)
        val cfg = transport.captureConfiguration(dev)
        if (cfg == null) {
            log("没有任何配置包含 0xFF/0x2A 采集接口，中止")
            return null
        }
        val itf = transport.captureInterfaceIn(cfg)
        if (itf == null) {
            log("配置 id=${cfg.id} 里找不到 0xFF/0x2A 接口，中止")
            return null
        }
        val conn = transport.open(dev) ?: return null
        if (!transport.setConfiguration(conn, cfg)) {
            log("切换配置失败")
            runCatching { conn.close() }
            return null
        }

        // 6) ★ 先建 lockdown 会话，再碰 QT —— 这是 AMDS 的顺序。
        //    2026-10-18 的 A/B 证明：没有这个会话，设备只回一次 PING 就沉默。
        val identity = Identity.loadFromAssets(this, ::log)
        var connMux: UsbDeviceConnection? = null
        val keepAlive = java.util.concurrent.atomic.AtomicBoolean(true)
        var muxThread: Thread? = null
        val itfMux = transport.muxInterfaceIn(cfg)
        if (itfMux == null) {
            log("⚠ 配置 id=${cfg.id} 里没有 usbmux 接口（0xFF/0xFE/2），跳过 lockdown 会话")
        } else {
            val cm = transport.open(dev)
            if (cm == null) {
                log("⚠ mux 连接 openDevice 失败")
            } else {
                connMux = cm
                if (!transport.claim(cm, itfMux)) {
                    log("⚠ claim usbmux 接口失败")
                } else {
                    val (mi, mo) = transport.bulkEndpoints(itfMux)
                    if (mi == null || mo == null) {
                        log("⚠ usbmux 接口端点不全")
                    } else {
                        log("建立 lockdown 会话（if#${itfMux.id}）…")
                        val link = MuxLink(cm, mi, mo, identity, ::log)
                        if (link.open()) {
                            log("★★ lockdown 会话在线（TLS=${link.tlsReady} 信任=${link.trusted}），开始保活")
                            muxThread = Thread({
                                while (keepAlive.get() && link.failed == null) {
                                    runCatching { link.pumpOnce(200) }
                                }
                            }, "mux-keepalive").apply { isDaemon = true; start() }
                        } else {
                            log("✗ lockdown 会话未建立：${link.failed}")
                        }
                    }
                }
            }
        }

        // 7) 现在才 claim QT 并开读
        val connQt = conn
        if (!transport.claim(connQt, itf)) {
            log("claim QT 接口失败")
            keepAlive.set(false); runCatching { connMux?.close() }; runCatching { connQt.close() }
            return null
        }
        val (epIn, epOut) = transport.bulkEndpoints(itf)
        if (epIn == null) {
            log("接口里没有 bulk IN 端点")
            keepAlive.set(false); runCatching { connMux?.close() }; runCatching { connQt.close() }
            return null
        }
        log("QT 接口 if#${itf.id} 已 claim；IN=0x${epIn.address.toString(16)} " +
            "OUT=${epOut?.let { "0x" + it.address.toString(16) } ?: "无"}")

        // 8) 收流落盘
        val outFile = File(getExternalFilesDir(null) ?: filesDir, "feed.bin")
        log("落盘：${outFile.absolutePath}")
        return outFile.outputStream().use { out ->
            try {
                val st = QtSession(
                    connQt, epIn, epOut, out, ::log,
                    onCsd = { csd ->
                        pendingCsd = csd
                        ensureDecoder()
                    },
                    onFrame = { f -> decoder?.submit(f) }
                ).run(maxSeconds = 60)
                if (st.ping > 0 || st.bytes > 0) return@use st

                log("12s 内没有 PING ⇒ 用配置 4→5 重新武装一次")
                transport.release(connQt, itf)
                val cfg4 = transport.configurationById(dev, 4)
                if (cfg4 != null) {
                    transport.setConfiguration(connQt, cfg4)
                    Thread.sleep(300)
                }
                if (!transport.setConfiguration(connQt, cfg)) {
                    log("  重新切回配置 ${cfg.id} 失败")
                    return@use st
                }
                val itf2 = transport.captureInterfaceIn(cfg) ?: itf
                transport.claim(connQt, itf2)
                val (in2, out2) = transport.bulkEndpoints(itf2)
                if (in2 == null) {
                    log("  重新武装后没有 bulk IN")
                    return@use st
                }
                QtSession(
                    connQt, in2, out2, out, ::log,
                    onCsd = { csd -> pendingCsd = csd; ensureDecoder() },
                    onFrame = { f -> decoder?.submit(f) }
                ).run(maxSeconds = 30)
            } finally {
                // ★ 收尾必须「还原配置」，否则设备留在配置 5，下次 activate 变空操作
                //   —— 这正是「必须重启 iPad」的真正原因。
                keepAlive.set(false)
                runCatching { muxThread?.interrupt() }
                runCatching { itfMux?.let { transport.release(connMux!!, it) } }
                runCatching { transport.release(connQt, itf) }
                runCatching {
                    transport.normalConfiguration(dev, cfg.id)?.let { transport.setConfiguration(connQt, it) }
                }
                runCatching { connMux?.close() }
                runCatching { connQt.close() }
                log("收尾完成：配置已还回普通态，可直接再次开始（无需重启 iPad）")
            }
        }
    }

    /** 把设备从采集配置切回普通配置（收尾用）。 */
    private fun restoreToNormal(dev: UsbDevice): Boolean {
        val conn = transport.open(dev)
        if (conn == null) {
            log("  复原失败：openDevice 返回 null")
            return false
        }
        return try {
            val target = transport.normalConfiguration(dev, 5)
            if (target == null) {
                log("  复原失败：找不到普通配置")
                false
            } else {
                transport.setConfiguration(conn, target)
            }
        } finally {
            runCatching { conn.close() }
        }
    }

    /** 轮询直到设备不再带采集配置（bNumConfigurations < 5）。 */
    private fun waitForNormalConfig(maxSeconds: Int): UsbDevice? {
        val deadline = System.currentTimeMillis() + maxSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val d = transport.findAppleDevice()
            if (d != null && d.configurationCount < 5) return d
            Thread.sleep(300)
        }
        return null
    }

    /** 轮询直到设备上出现 0xFF/0x2A 采集接口，返回新的 UsbDevice。 */
    private fun waitForCaptureMode(maxSeconds: Int): UsbDevice? {
        val deadline = System.currentTimeMillis() + maxSeconds * 1000L
        var lastTick = 0L
        while (System.currentTimeMillis() < deadline) {
            val d = transport.findAppleDevice()
            if (d != null) {
                val itf = transport.findCaptureInterface(d)
                if (itf != null) {
                    log("★ 采集接口已出现：${d.deviceName}")
                    return d
                }
                val now = System.currentTimeMillis()
                if (now - lastTick > 5000) {
                    lastTick = now
                    log("  等待中… 当前 ${d.deviceName}")
                }
            }
            Thread.sleep(300)
        }
        return null
    }

    /** 确保拿到权限；没有就请求并等广播（最多 25 秒）。 */
    private fun ensurePermission(dev: UsbDevice): Boolean {
        if (transport.hasPermission(dev)) return true
        val latch = CountDownLatch(1)
        permissionLatch = latch
        transport.requestPermission(dev)
        val ok = latch.await(25, TimeUnit.SECONDS)
        permissionLatch = null
        // 广播可能丢，最终以 hasPermission 为准
        val has = transport.hasPermission(dev)
        log("权限结果：broadcast=${ok && true} hasPermission=$has")
        return has
    }
}
