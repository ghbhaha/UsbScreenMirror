package com.uav.iosqt

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
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
        }
        root.addView(btn)
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
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(permissionReceiver) }
        super.onDestroy()
    }

    private fun log(msg: String) {
        val line = "${logFmt.format(Date())}  $msg"
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

        // 3) 已有采集接口就直接用；否则 activate 并等重枚举
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
            log("已请求激活，等待设备重枚举（最长 120s，可能延迟数十秒）…")
            dev = waitForCaptureMode(120)
            if (dev == null) {
                log("✗ 120s 内没有出现采集接口。设备需要物理重启（解锁亮屏）。")
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

        // 5) claim + 找端点
        val itf = transport.findCaptureInterface(dev)
        if (itf == null) {
            log("找不到 0xFF/0x2A 采集接口，中止")
            return null
        }
        val conn = transport.open(dev) ?: return null
        if (!transport.claim(conn, itf)) {
            log("claim 接口失败")
            runCatching { conn.close() }
            return null
        }
        val (epIn, epOut) = transport.bulkEndpoints(itf)
        if (epIn == null) {
            log("接口里没有 bulk IN 端点")
            runCatching { conn.close() }
            return null
        }
        log("接口 if#${itf.id} 已 claim；IN=0x${epIn.address.toString(16)} " +
            "OUT=${epOut?.let { "0x" + it.address.toString(16) } ?: "无"}")

        // 6) 收流落盘
        val outFile = File(getExternalFilesDir(null) ?: filesDir, "feed.bin")
        log("落盘：${outFile.absolutePath}")
        return outFile.outputStream().use { out ->
            try {
                QtSession(conn, epIn, epOut, out, ::log).run(maxSeconds = 30)
            } finally {
                runCatching { conn.close() }
            }
        }
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
