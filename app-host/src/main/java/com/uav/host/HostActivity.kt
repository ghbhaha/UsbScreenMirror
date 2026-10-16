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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val statsTicker = object : Runnable {
        override fun run() {
            val s = session
            statsText.text = when {
                s != null -> s.stats()
                bulkChannel != null -> bulkChannel!!.stats()
                else -> "no active session"
            }
            mainHandler.postDelayed(this, STATS_INTERVAL_MS)
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

        router = UsbAttachRouter(this, logger, this).also { it.start() }

        registerReceiver(detachReceiver, UsbAttachRouter.detachFilter())

        mainHandler.post(statsTicker)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
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
        router?.onDeviceAttached(device)
    }

    // ------------------------------------------------------------------ router 回调

    override fun onStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    override fun onReady(
        connection: android.hardware.usb.UsbDeviceConnection,
        pair: BulkPair,
        aoaVersion: Int,
        device: UsbDevice,
    ) {
        logger.i(TAG, "link ready: aoaVersion=$aoaVersion device=${device.deviceId}")

        stopSession()

        val channel = BulkByteChannel(connection, pair, logger)
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
        val channel = FileByteChannel(file, logger, loop = true)
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
    }
}
