package com.uav.host

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.Surface
import android.widget.Button
import android.widget.TextView
import androidx.core.content.IntentCompat
import com.uav.host.debug.FileByteChannel
import com.uav.host.session.HostSession
import com.uav.host.usb.BulkByteChannel
import com.uav.host.usb.BulkPair
import com.uav.host.usb.UsbAttachRouter
import com.uav.host.video.AspectRatioSurfaceView
import com.uav.protocol.Protocol
import com.uav.protocol.Transport
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w
import java.io.File

/**
 * 宿主投屏页（计划 2.12 / 4.7）。
 *
 * 职责很薄：把 UI 与三个部件接起来
 *  - [UsbAttachRouter]：插线 → 权限 → AOA 握手 → 等重枚举 → claim 接口
 *  - [HostSession]：CNXN / OPEN / OKAY 编排 + 收流 + 解码渲染
 *  - 状态与统计文本：联调全靠它看进度（计划 2.12）
 *
 * 另外提供 **`.uav` 回放**入口（计划 4.0）：跳过 USB 与握手直接把抓流喂进同一套会话代码。
 */
class HostActivity : Activity(), UsbAttachRouter.Callback, HostSession.Listener {

    private lateinit var surfaceView: AspectRatioSurfaceView
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView

    private var router: UsbAttachRouter? = null
    private var session: HostSession? = null
    private var bulkChannel: BulkByteChannel? = null
    private var replayChannel: FileByteChannel? = null

    @Volatile
    private var surfaceReady = false

    /** 最近一次 attach 的设备，供"自愈"重试用。 */
    @Volatile
    private var lastDevice: UsbDevice? = null

    /** 本次连接是否走了"跳过握手"的快路径（`aoaVersion == 0` 即表示跳过）。 */
    @Volatile
    private var fastPathUnverified = false

    /** 是否已经为本次连接做过一次强制重握手，避免死循环。 */
    @Volatile
    private var forceRetried = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickCount = 0L

    private val statsTicker = object : Runnable {
        override fun run() {
            val s = session
            val text = when {
                s != null -> s.stats()
                bulkChannel != null -> bulkChannel!!.stats()
                else -> "no active session"
            }
            statsText.text = text
            // 每 5 秒把统计打进日志：5 分钟压测（计划 4.10）要靠它读首末对比，
            // 也避免依赖 UI 取值（画面持续刷新时 uiautomator dump 会拿不到稳定帧）
            tickCount++
            if (tickCount % STATS_LOG_EVERY_TICKS == 0L) {
                logger.i(TAG, "STATS $text")
            }
            mainHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    /**
     * 前台轮询兜底：实测宿主设备（BOE P40UCH）在手机重枚举后**只发 DETACHED、不发 ATTACHED**，
     * 只靠 `USB_DEVICE_ATTACHED` 广播会让"插线即投"彻底失效。
     * 因此在 Activity 可见期间定时扫一遍设备列表，发现没试过的就自动握手。
     */
    private val usbPoller = object : Runnable {
        override fun run() {
            if (session == null) {
                runCatching { router?.pollOnce() }
                    .onFailure { logger.w(TAG, "pollOnce 异常: ${it.message}") }
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            logger.i(TAG, "detach broadcast: ${intent.action} device=${device?.deviceId}")
            onDetached(device)
        }
    }

    private val logger = AndroidLogger

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceReady = true
            logger.i(TAG, "surface created: ${holder.surface}")
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            logger.i(TAG, "surface changed ${width}x$height")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            logger.i(TAG, "surface destroyed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)

        surfaceView = findViewById(R.id.surface)
        statusText = findViewById(R.id.status)
        statsText = findViewById(R.id.stats)

        surfaceView.holder.addCallback(surfaceCallback)

        val screen = DisplaySize.of(this)
        logger.i(TAG, "host screen ${screen.x}x${screen.y}, protocol v${Protocol.VERSION}")

        findViewById<Button>(R.id.btn_scan).setOnClickListener { router?.scanAndAttach() }
        findViewById<Button>(R.id.btn_replay).setOnClickListener { pickReplayFile() }
        findViewById<Button>(R.id.btn_force).setOnClickListener {
            stopSession()
            forceRetried = true
            fastPathUnverified = false
            router?.scanAndAttach(forceHandshake = true)
        }

        router = UsbAttachRouter(this, logger, this).also { it.start() }

        registerReceiver(detachReceiver, UsbAttachRouter.detachFilter())

        mainHandler.post(statsTicker)
        mainHandler.postDelayed(usbPoller, POLL_INTERVAL_MS)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // ★ 供 adb 驱动的调试入口：am start -n com.uav.host/.HostActivity --es replay_path /data/data/com.uav.host/files/replay.uav
        //   从指定文件回放抓流（计划 4.0），跳过整个 USB 与握手。
        intent?.getStringExtra(EXTRA_REPLAY_PATH)?.takeIf { it.isNotEmpty() }?.let { path ->
            logger.i(TAG, "收到回放指令: $path")
            val file = File(path)
            if (!file.exists()) {
                onError("回放文件不存在: $path（可先用 run-as 放进 filesDir）", null)
                return
            }
            startReplay(file)
            return
        }

        // ★ 供 adb 驱动的调试入口：am start -n com.uav.host/.HostActivity --ez scan true
        //   走普通扫描（已是配件则复用直连），等价于点界面上的 Scan / Retry 按钮。
        if (intent?.getBooleanExtra(EXTRA_SCAN, false) == true) {
            logger.i(TAG, "收到扫描指令")
            stopSession()
            forceRetried = false
            router?.scanAndAttach(forceHandshake = false)
            return
        }

        // ★ 供 adb 驱动的调试入口：am start -n com.uav.host/.HostActivity --ez force_handshake true
        //   强制走完整 AOA 握手，用于"手机已被别的宿主 App 切成配件模式"的场景。
        if (intent?.getBooleanExtra(EXTRA_FORCE_HANDSHAKE, false) == true) {
            logger.i(TAG, "收到强制重握手指令")
            stopSession()
            forceRetried = true
            fastPathUnverified = false
            router?.scanAndAttach(forceHandshake = true)
            return
        }

        val action = intent?.action ?: return
        logger.i(TAG, "intent action=$action")
        if (action != UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED
        ) {
            return
        }
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        if (device == null) {
            logger.w(TAG, "attach intent 但没有 EXTRA_DEVICE")
            return
        }
        stopSession()
        forceRetried = false
        router?.onDeviceAttached(device)
    }

    // ------------------------------------------------------------------ router 回调

    override fun onStatus(text: String) {
        // 会话真正建立起来 -> 快路径已验证通过，撤销自愈待办
        if (text.contains("会话已建立")) fastPathUnverified = false
        runOnUiThread { statusText.text = text }
    }

    override fun onReady(
        connection: android.hardware.usb.UsbDeviceConnection,
        pair: BulkPair,
        aoaVersion: Int,
        device: UsbDevice,
    ) {
        logger.i(TAG, "link ready: aoaVersion=$aoaVersion device=${device.deviceId}")

        lastDevice = device
        // aoaVersion == 0 表示这次是按"已是配件、跳过握手"的快路径连的，
        // 配件身份未被验证过（可能还是别的宿主 App 留下的）
        fastPathUnverified = aoaVersion == 0

        stopSession()

        val channel = BulkByteChannel(connection, pair, logger)
        // ★ 先把上一次会话残留在 FIFO 里的字节丢掉，再开新会话。
        //   否则第一个 payload 会被当成流头解析，静默配错解码器（实测踩到过）。
        channel.drainStaleInput()
        bulkChannel = channel
        val transport = Transport(channel, logger, TAG)

        val screen = DisplaySize.of(this)
        val s = HostSession(
            transport = transport,
            localWidth = screen.x,
            localHeight = screen.y,
            surfaceProvider = ::currentSurface,
            logger = logger,
            listener = this,
        )
        session = s
        s.start()
    }

    override fun onError(message: String, cause: Throwable?) {
        runOnUiThread { statusText.text = "ERROR: $message" }
        logger.e(TAG, message, cause)

        // ★ 自愈：快路径（跳过握手）连上的链路如果没人应答，说明配件身份不是我们的
        //   —— 手机很可能被"别的宿主 App"切成了配件模式，系统因此没有拉起我们的手机端 App。
        //   这时强制走一次完整 AOA 握手，把配件身份改写成 UAV/usbscreen。
        val dev = lastDevice
        if (fastPathUnverified && !forceRetried && dev != null) {
            forceRetried = true
            fastPathUnverified = false
            logger.w(TAG, "快路径失败（$message）→ 改为强制完整 AOA 握手重试一次")
            runOnUiThread { statusText.text = "重试：强制 AOA 重握手（改写配件身份）…" }
            session?.close()
            session = null
            router?.onDeviceAttached(dev, forceHandshake = true)
        }
    }

    /** 设备拔出：与读写异常统一走这里（计划 2.10）。 */
    private fun onDetached(device: UsbDevice?) {
        stopSession()
        statusText.text = "USB 已拔出，请重新插线或点 Scan/Retry"
    }

    // ------------------------------------------------------------------ session 回调

    override fun onVideoSize(width: Int, height: Int) {
        runOnUiThread { surfaceView.setVideoSize(width, height) }
    }

    override fun onFirstFrame() {
        runOnUiThread { statusText.text = "投屏中（首帧已输出）" }
    }

    override fun onClosed(reason: String) {
        runOnUiThread { statusText.text = "已断开：$reason" }
    }

    // ------------------------------------------------------------------ 回放

    private fun pickReplayFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_PICK_UAV)
    }

    @Deprecated("plain Activity onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_UAV || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val dest = File(cacheDir, "replay.uav")
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "openInputStream returned null" }
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            startReplay(dest)
        } catch (t: Throwable) {
            onError("读取抓流文件失败: ${t.message}", t)
        }
    }

    private fun startReplay(file: File) {
        stopSession()
        // 不回环：抓流里的"流头"只在该流首条 WRTE 有效，循环第二遍时这 12B 会被当成
        // 一帧解析而报错。要看第二遍就再触发一次回放。
        // 配速默认开启（按帧 PTS 还原真实速度），否则会把深度 4 的队列冲爆。
        val channel = FileByteChannel(file, logger, paceByPts = true)
        replayChannel = channel
        val transport = Transport(channel, logger, TAG)
        val screen = DisplaySize.of(this)

        val s = HostSession(
            transport = transport,
            localWidth = screen.x,
            localHeight = screen.y,
            surfaceProvider = ::currentSurface,
            logger = logger,
            listener = this,
            replay = true,
        )
        session = s
        s.start()
        statusText.text = "回放：${file.name}（${channel.totalBytes} 字节）"
    }

    // ------------------------------------------------------------------ 生命周期

    private fun currentSurface(): Surface? =
        if (surfaceReady) surfaceView.holder.surface else null

    private fun stopSession() {
        session?.close()
        session = null
        replayChannel?.close()
        replayChannel = null
        bulkChannel = null
        surfaceView.setVideoSize(0, 0)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(statsTicker)
        mainHandler.removeCallbacks(usbPoller)
        runCatching { unregisterReceiver(detachReceiver) }
        router?.close()
        router = null
        stopSession()
        super.onDestroy()
    }

    companion object {
        const val TAG = "UAV-Host"
        const val REQ_PICK_UAV = 1001
        const val STATS_INTERVAL_MS = 1000L
        const val STATS_LOG_EVERY_TICKS = 5L
        const val POLL_INTERVAL_MS = 2_000L

        /** adb 调试入口用的 extra：`--es replay_path <绝对路径>`。 */
        const val EXTRA_REPLAY_PATH = "replay_path"

        /** adb 调试入口用的 extra：`--ez force_handshake true`。 */
        const val EXTRA_FORCE_HANDSHAKE = "force_handshake"

        /** adb 调试入口用的 extra：`--ez scan true`。 */
        const val EXTRA_SCAN = "scan"
    }
}
