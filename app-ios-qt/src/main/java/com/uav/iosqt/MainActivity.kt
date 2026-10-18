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
import android.view.WindowManager
import com.uav.iosqt.ui.IosPanel
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
    private lateinit var panel: IosPanel

    /** 解码器要等 Surface 和 SPS/PPS 都就绪才能起 */
    private var pendingCsd: ByteArray? = null
    private var decoder: H264Decoder? = null

    /** 首帧只处理一次：到首帧就把面板收起来，把画面让出来 */
    private val firstFrameDone = AtomicBoolean(false)

    /** 用户点了「停止投屏」 */
    @Volatile
    private var stopRequested = false

    /** 当前正在跑的会话 —— 停止信号要能立刻打断循环里的 bulkTransfer */
    @Volatile
    private var activeSession: QtSession? = null

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

        setContentView(R.layout.activity_ios)
        // ★ 必须在 setContentView 之后构造：IosPanel 在构造期就 findViewById
        panel = IosPanel(this)
        panel.bind(
            onSurface = { ready ->
                if (ready) {
                    log("预览 Surface 就绪")
                    ensureDecoder()
                } else {
                    decoder?.stop()
                    decoder = null
                    pendingCsd = null
                }
            },
            onStart = { toggleStart() },
            onStartLongClick = { if (!running.get()) { replay(); true } else false },
            onPair = { if (!running.get()) pairDevice() },
            onRearm = { if (!running.get()) rearm() }
        )
        // 投屏期间别让屏幕自己熄掉（熄屏会连带把 Surface 收走）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        transport = QtTransport(this, usb, ::log)
        registerReceiver(
            permissionReceiver,
            IntentFilter(QtTransport.ACTION_USB_PERMISSION),
            RECEIVER_NOT_EXPORTED
        )

        log("QuickTime USB 采集（Android）")
        refreshDeviceInfo(state = "空闲")
        if (!Pairing.hasRecord(filesDir)) {
            panel.render(IosPanel.Stage.UNPAIRED, getString(R.string.ios_detail_pair_hint))
            log("配对记录：无 ⇒ 首次请先点「配对」，在 iPad 上点一次「信任」")
        } else {
            panel.render(IosPanel.Stage.IDLE, null)
            log("配对记录：已有（${Pairing.loadRecord(filesDir)?.hostId ?: "读取失败"}）")
        }
    }

    /**
     * 刷新「设备」卡片。
     *
     * 只写事实：设备名 / 配置数（4=普通态、5=采集态）/ 配对状态。
     * 这行是排「0 字节」时最有用的一条 —— 比翻日志快。
     */
    private fun refreshDeviceInfo(state: String) {
        val dev = transport.findAppleDevice()
        val pair = Pairing.loadRecord(filesDir)?.hostId
        val sb = StringBuilder()
        if (dev == null) {
            sb.append("未连接\n")
        } else {
            sb.append("iPad · pid 0x").append(dev.productId.toString(16)).append('\n')
            sb.append("配置数 ").append(dev.configurationCount)
                .append(if (dev.configurationCount >= 5) "（采集态）" else "（普通态）").append('\n')
        }
        sb.append("配对 ").append(if (pair != null) "已信任" else "未配对")
        if (pair != null) sb.append('\n').append(pair)
        sb.append('\n').append("状态 ").append(state)
        panel.setDeviceInfo(sb.toString())
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(permissionReceiver) }
        super.onDestroy()
    }

    /** 离线回放 filesDir/feed.bin：验证「解析 → 解码 → 上屏」整条链路。 */
    private fun replay() {
        running.set(true)
        panel.setMirroring(true)
        panel.render(IosPanel.Stage.REPLAY, "回放 filesDir/feed.bin")
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
                        SampleParser.csd(payload)?.let { onCsdArrived(it) }
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
            panel.setMirroring(false)
            panel.render(IosPanel.Stage.IDLE, null)
        }, "replay").start()
    }

    /**
     * 收到（新的）SPS/PPS。
     *
     * 第一次：等 Surface 就绪后起解码器；
     * 之后内容变了（iPad 转屏 ⇒ 分辨率变）：**必须重建解码器**，否则旧 codec 会静默停产。
     */
    private fun onCsdArrived(csd: ByteArray) {
        pendingCsd = csd
        if (decoder == null) ensureDecoder() else decoder?.restart(csd)
    }

    /** Surface 与 SPS/PPS 都到位时启动解码器。 */
    private fun ensureDecoder() {
        val sf = panel.currentSurface() ?: return
        val csd = pendingCsd ?: return
        if (decoder != null) return
        decoder = H264Decoder(sf, ::log) { w, h -> panel.setVideoSize(w, h) }
            .also { it.start(csd) }
    }

    private fun log(msg: String) {
        // 同时打到 logcat：UI 文本要 uiautomator dump 才读得到，logcat 一条命令就能跟
        android.util.Log.i("UAV-IosQt", msg)
        panel.log("${logFmt.format(Date())}  $msg")
    }

    /**
     * 开始 / 停止投屏（同一个按钮）。
     *
     * 投屏是**持续**的：`QtSession.run(maxSeconds = 0)` 一直读到用户点停止。
     * 停止走的是置位 [QtSession.stopRequested]，读循环在 500ms 的 bulkTransfer 超时后就会退出，
     * 所以点下去是秒停，不需要强杀线程。
     */
    private fun toggleStart() {
        if (running.get()) {
            stopRequested = true
            activeSession?.stopRequested = true
            log("正在停止投屏…")
            panel.render(IosPanel.Stage.MIRRORING, "正在停止…")
            return
        }
        stopRequested = false
        running.set(true)
        firstFrameDone.set(false)
        panel.setMirroring(true)
        panel.render(IosPanel.Stage.CONNECTING, null)
        refreshDeviceInfo(state = "启动中")
        Thread({ runCapture() }, "ios-qt").start()
    }

    /**
     * 让 iPad 信任本机 —— 走一次完整的 lockdownd `Pair`。
     *
     * 这是「不依赖 PC 导凭证」的那一步：配对记录会存进 `filesDir/ios_pair.properties`，
     * 之后 [Identity.load] 优先用它。配对必须在**普通配置**下做，所以这里不 activate。
     */
    private fun pairDevice() {
        running.set(true)
        panel.setBusy(true)
        panel.render(IosPanel.Stage.PAIRING, getString(R.string.ios_detail_pair_hint))
        Thread({
            runCatching {
                val dev = transport.findAppleDevice()
                if (dev == null) {
                    log("未找到 Apple 设备（VID 05ac）。请先插线。")
                    return@Thread
                }
                log("配对目标：${dev.deviceName} pid=0x${dev.productId.toString(16)}")
                if (!ensurePermission(dev)) {
                    log("未取得 USB 权限，中止")
                    return@Thread
                }
                transport.dumpConfigs(dev)

                val pairing = Pairing(transport, filesDir, ::log)
                // 上一轮若留在采集配置，先还原到普通态再配对
                if (dev.configurationCount >= 5) {
                    log("设备停在采集配置 ⇒ 先还原到普通配置")
                    restoreToNormal(dev)
                    Thread.sleep(400)
                    transport.findAppleDevice()?.let { dev2 ->
                        if (!ensurePermission(dev2)) { log("还原后未取得权限"); return@Thread }
                        if (pairing.run(dev2) == null) { log("配对未完成"); return@Thread }
                    } ?: run { log("还原后设备消失"); return@Thread }
                } else if (pairing.run(dev) == null) {
                    log("配对未完成")
                    return@Thread
                }

                // 配对成功后立刻自证一次：StartSession → TLS → GetValue
                Pairing.loadRecord(filesDir)?.let { rec ->
                    log("自检：用刚配好的身份走一次 StartSession + TLS…")
                    verifyTrust(rec.hostId)
                }
            }.onFailure { log("配对异常：${it.javaClass.simpleName}: ${it.message}") }
            running.set(false)
            panel.setBusy(false); refreshDeviceInfo(state = "空闲")
        }, "ios-pair").start()
    }

    /**
     * 重新武装 QT 端点：配置 5 → 3 → `activate` → **等设备自己重枚举**。
     *
     * 什么时候需要它：lockdown 已经信任、QT 接口也 claim 到了，但**一个字节都不来**。
     * 这时说明设备侧的 QT 服务没被"启动"——只有 `activate` 让设备走一次 USB 重枚举，
     * 它才会把带 0x2A 的配置**主动交出来**；单纯的配置来回（3→5）只对"刚武装过"的设备有效。
     *
     * ⚠️ activate 之后**绝不能再碰任何配置**，一碰就打断重枚举（踩过：以为没反应，实际是被自己搅黄的）。
     */
    private fun rearm() {
        running.set(true)
        panel.setBusy(true)
        panel.render(IosPanel.Stage.REARM, getString(R.string.ios_detail_rearm_hint))
        Thread({
            runCatching {
                val dev0 = transport.findAppleDevice()
                if (dev0 == null) { log("未找到 Apple 设备，先插线"); return@Thread }
                if (!ensurePermission(dev0)) { log("未取得 USB 权限，中止"); return@Thread }
                log("起始状态：${dev0.deviceName} 描述配置数=${dev0.configurationCount}")

                if (dev0.configurationCount >= 5) {
                    log("先把设备切回普通配置（3），让 activate 有意义…")
                    restoreToNormal(dev0)
                    Thread.sleep(800)
                }
                val dev = transport.findAppleDevice()
                if (dev == null) { log("切配置后设备消失，请重插"); return@Thread }
                if (!ensurePermission(dev)) { log("切配置后未取得权限"); return@Thread }

                val conn = transport.open(dev)
                if (conn == null) { log("openDevice 失败"); return@Thread }
                try {
                    if (!transport.activate(conn)) { log("activate 被拒"); return@Thread }
                } finally {
                    runCatching { conn.close() }
                }
                log("已发 activate，等设备重枚举（最长 240s，期间不要拔线/不要点其他按钮）…")

                // 判据：配置集必须**先离开 5 再回到 5**。只看">=5"会把"没动"误判成"已就绪"。
                val t0 = System.currentTimeMillis()
                var sawLeave = false
                var lastReport = 0L
                var current: UsbDevice = dev
                while (System.currentTimeMillis() - t0 < 240_000) {
                    val d = transport.findAppleDevice()
                    if (d != null) {
                        current = d
                        if (d.configurationCount < 5) sawLeave = true
                        if (sawLeave && d.configurationCount >= 5) {
                            log("★★ 重枚列完成：${d.deviceName} 配置数=${d.configurationCount} ⇒ 现在可以点「开始采集」")
                            return@Thread
                        }
                    }
                    val elapsed = (System.currentTimeMillis() - t0) / 1000
                    if (elapsed - lastReport >= 10) {
                        lastReport = elapsed
                        log("  等待中 ${elapsed}s… ${current.deviceName} 描述配置数=${current.configurationCount}" +
                            if (sawLeave) "（已离开采集态，等它回来）" else "（还没动静）")
                    }
                    Thread.sleep(700)
                }
                if (sawLeave) log("✗ 设备离开采集态后 240s 内没回来")
                else log("✗ 240s 内设备根本没重枚举 ⇒ 只能拔插线或重启 iPad")
            }.onFailure { log("重新武装异常：${it.message}") }
            running.set(false)
            panel.setBusy(false); refreshDeviceInfo(state = "空闲")
        }, "ios-rearm").start()
    }

    /** 用已保存的配对记录走一遍「StartSession → TLS → GetValue」，输出信任结论。 */
    private fun verifyTrust(hostId: String) {
        val dev = transport.findAppleDevice()
        if (dev == null) { log("自检跳过：设备不可见"); return }
        runCatching {
            val rec = Pairing.loadRecord(filesDir) ?: return
            val identity = Identity.fromRecord(rec)
            val cfg = (0 until dev.configurationCount).mapNotNull {
                runCatching { dev.getConfiguration(it) }.getOrNull()
            }.firstOrNull { transport.muxInterfaceIn(it) != null } ?: run {
                log("自检跳过：没有含 usbmux 的配置"); return
            }
            val itf = transport.muxInterfaceIn(cfg) ?: return
            val conn = transport.open(dev) ?: return
            try {
                transport.setConfiguration(conn, cfg)
                if (!transport.claim(conn, itf)) { log("自检：claim 失败"); return }
                val (epIn, epOut) = transport.bulkEndpoints(itf)
                if (epIn == null || epOut == null) { log("自检：端点不全"); return }
                val link = MuxLink(conn, epIn, epOut, identity, ::log)
                if (link.openRaw()) {
                    link.establishSession(identity)
                    log(
                        if (link.trusted) "★★ 自检通过：设备已信任 HostID=$hostId，可以点「开始采集」"
                        else "✗ 自检未通过：TLS=${link.tlsReady} 原因=${link.failed ?: "未取得 Value"}"
                    )
                } else {
                    log("✗ 自检隧道失败：${link.failed}")
                }
                runCatching { transport.release(conn, itf) }
            } finally {
                runCatching { conn.close() }
            }
        }.onFailure { log("自检异常：${it.message}") }
    }

    private fun runCapture() {
        val stats = runCatching { capture() }.getOrElse { t ->
            log("异常：${t.javaClass.simpleName}: ${t.message}")
            null
        }
        running.set(false)
        activeSession = null
        panel.setMirroring(false)
        refreshDeviceInfo(state = "空闲")
        panel.render(
            IosPanel.Stage.IDLE,
            if (stats == null) getString(R.string.ios_status_error) else null
        )
        if (stats != null) {
            log("=== 结束：PING ${stats.ping} / CWPA ${stats.cwpa} / CVRP ${stats.cvrp} / " +
                "FEED ${stats.feed}（${stats.feedBytes} B） / 共 ${stats.bytes} B ===")
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

        // 4) 设备**不在采集态**就 activate 并等重枚举。
        //    ⚠️ 判据必须是「配置集里有几个配置」，不能是 `findCaptureInterface()`：
        //    后者扫的是扁平表（所有配置的接口并集），永远能找到 0x2A ⇒ activate 从来没被真正触发过。
        if (dev.configurationCount < 5) {
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
            log("设备已在采集态（${dev.configurationCount} 个配置），跳过激活")
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
        val identity = Identity.load(this, ::log)
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

        // 8) 收流：**一直投到用户点停止**（原先是跑 60s 就结束，那是采集测试、不是投屏）
        val outFile = File(getExternalFilesDir(null) ?: filesDir, "feed.bin")
        log("落盘：${outFile.absolutePath}")
        return outFile.outputStream().use { out ->
            var session: QtSession? = null
            try {
                panel.render(IosPanel.Stage.MIRRORING, null)
                refreshDeviceInfo(state = "投屏中")
                session = QtSession(
                    connQt, epIn, epOut, out, ::log,
                    onCsd = { csd -> onCsdArrived(csd) },
                    onFrame = { f ->
                        decoder?.submit(f)
                        if (!firstFrameDone.getAndSet(true)) {
                            // 首帧到了：收起面板把画面让出来（药丸仍显示「投屏中」）
                            log("首帧已渲染 ⇒ 收起面板")
                            panel.render(IosPanel.Stage.MIRRORING, null)
                            panel.setMenuVisible(false)
                        }
                    }
                )

                activeSession = session
                // 用短窗口探一下：一字节都没有 = 设备侧没武装，交回上层重新唤醒
                var st = session.run(maxSeconds = 0, idleAbortSeconds = 12)
                if (st.bytes > 0 || stopRequested) return@use st

                log("12s 内没有任何数据 ⇒ 用配置 4→5 重新武装一次")
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
                session = QtSession(
                    connQt, in2, out2, out, ::log,
                    onCsd = { csd -> onCsdArrived(csd) },
                    onFrame = { f ->
                        decoder?.submit(f)
                        if (!firstFrameDone.getAndSet(true)) {
                            panel.render(IosPanel.Stage.MIRRORING, null)
                            panel.setMenuVisible(false)
                        }
                    }
                )
                activeSession = session
                st = session.run(maxSeconds = 0, idleAbortSeconds = 12)
                st
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
                // 同样不能用扁平表：配置集从 4 涨到 5 才代表设备真的切进了采集模式
                if (d.configurationCount >= 5) {
                    log("★ 设备已进入采集态：${d.deviceName}（${d.configurationCount} 个配置）")
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
