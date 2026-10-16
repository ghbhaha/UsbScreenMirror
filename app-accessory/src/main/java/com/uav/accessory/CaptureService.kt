package com.uav.accessory

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.uav.accessory.session.AccessorySession
import com.uav.accessory.usb.FdByteChannel
import com.uav.accessory.video.VirtualDisplayEncoder
import com.uav.protocol.ParsedOpen
import com.uav.protocol.StreamHeader
import com.uav.protocol.Transport
import com.uav.protocol.e
import com.uav.protocol.i

/** 手机端状态发布（Activity 轮询显示；无第三方依赖，够用即可）。 */
object AccessoryStatus {
    @Volatile
    var text: String = "idle"
        private set

    @Volatile
    var stats: String = ""
        private set

    fun set(text: String) {
        this.text = text
        AndroidLogger.i(TAG, text)
    }

    fun setStats(stats: String) {
        this.stats = stats
    }

    private const val TAG = "UAV-Accessory"
}

/**
 * 采集前台服务（计划 3.5 / 3.4）。
 *
 * 为什么必须是**前台服务**：Android 14+（API 34）要求
 * **先以 `mediaProjection` 类型 `startForeground`，之后才允许 `getMediaProjection()`**。
 * 顺序写反会直接抛异常。因此本服务的启动顺序是严格固定的：
 *
 * ```
 * startForeground(type = mediaProjection)  ->  getMediaProjection()  ->  registerCallback()  ->  createVirtualDisplay()
 * ```
 *
 * 另外：投影 token **一次性**，`createVirtualDisplay` 只能调一次；
 * 旋转场景（P0 路线图）复用的是"resize + setSurface + 编码器重配置"三件套，不重建虚拟屏。
 */
class CaptureService : Service(), AccessorySession.Listener, VirtualDisplayEncoder.Sink {

    private var session: AccessorySession? = null
    private var fdChannel: FdByteChannel? = null
    private var dumpChannel: com.uav.accessory.debug.DumpByteChannel? = null
    private var encoder: VirtualDisplayEncoder? = null
    private var projection: MediaProjection? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            AccessoryStatus.set("系统已停止屏幕共享（onStop）")
            stopCapture()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AccessoryStatus.set("收到停止指令")
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }

            null -> Unit

            else -> {
                // ★ 第一步：必须先以 mediaProjection 类型进入前台，才能 getMediaProjection
                goForeground()

                val accessory = resolveAccessory(intent)
                if (accessory == null) {
                    AccessoryStatus.set("没有可用的 USB 配件 —— 请重新插线")
                    return START_NOT_STICKY
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultData == null) {
                    AccessoryStatus.set("缺少录屏授权结果(EXTRA_RESULT_DATA)")
                    return START_NOT_STICKY
                }

                acquireProjection(resultCode, resultData)
                openAccessoryAndStartSession(accessory)
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ 投影

    private fun acquireProjection(resultCode: Int, resultData: Intent) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mgr.getMediaProjection(resultCode, resultData)
        if (p == null) {
            AccessoryStatus.set("getMediaProjection 返回 null（授权结果无效）")
            return
        }
        // ★ API 34+ 必须先 registerCallback 再 createVirtualDisplay，否则抛 IllegalStateException
        p.registerCallback(projectionCallback, null)
        projection = p
        AccessoryStatus.set("录屏授权已获取")
    }

    // ------------------------------------------------------------------ accessory

    private fun resolveAccessory(intent: Intent?): UsbAccessory? {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        intent?.let {
            val acc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
            if (acc != null) return acc
        }
        return usbManager.accessoryList?.firstOrNull()
    }

    private fun openAccessoryAndStartSession(accessory: UsbAccessory) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val pfd = try {
            usbManager.openAccessory(accessory)
        } catch (t: Throwable) {
            AccessoryStatus.set("openAccessory 抛异常: ${t.message}")
            null
        }
        if (pfd == null) {
            // 计划 3.2：openAccessory 返回 null 必须显式报错，不能装死
            AccessoryStatus.set("openAccessory 返回 null（无权限或配件已被占用）")
            return
        }

        val raw = FdByteChannel(pfd, AndroidLogger)
        fdChannel = raw

        // 抓流落盘（计划 3.12）：给宿主离线回放用；失败不影响实时链路
        val dumpFile = java.io.File(getExternalFilesDir(null) ?: filesDir, DUMP_FILE_NAME)
        val dump = com.uav.accessory.debug.DumpByteChannel(raw, dumpFile, AndroidLogger)
        dumpChannel = dump

        val transport = Transport(dump, AndroidLogger, AccessorySession.TAG)
        val screen = DisplaySize.of(this)

        val s = AccessorySession(
            transport = transport,
            localWidth = screen.x,
            localHeight = screen.y,
            capture = object : AccessorySession.CaptureController {
                override fun startCapture(options: ParsedOpen, session: AccessorySession) {
                    this@CaptureService.startCaptureInternal(options, session)
                }

                override fun stopCapture() {
                    stopCapture()
                }
            },
            logger = AndroidLogger,
            listener = this,
        )
        session = s
        s.start()
        AccessoryStatus.set("配件已打开（${accessory.model}/${accessory.manufacturer}），等待宿主报文")
    }

    // ------------------------------------------------------------------ 采集

    private fun startCaptureInternal(options: ParsedOpen, session: AccessorySession) {
        val p = projection
        if (p == null) {
            // 计划 4.4：如实告知宿主，别互相干等
            session.failAndClose("录屏授权尚未就绪，请重新插线并允许录屏")
            return
        }
        val screen = DisplaySize.of(this)
        val enc = VirtualDisplayEncoder(this, p, this, AndroidLogger)
        val scaled = try {
            enc.start(screen.x, screen.y, options)
        } catch (t: Throwable) {
            AndroidLogger.e(TAG, "启动编码器失败: ${t.message}", t)
            session.failAndClose("启动编码器失败: ${t.message}")
            return
        }
        encoder = enc
        AccessoryStatus.set("编码中：${screen.x}x${screen.y} -> ${scaled[0]}x${scaled[1]}")
    }

    private fun stopCapture() {
        encoder?.stop()
        encoder = null
    }

    // ------------------------------------------------------------------ 编码器回调

    override fun onStreamHeader(header: StreamHeader) {
        session?.sendStreamHeader(header)
    }

    override fun onConfig(payload: ByteArray) {
        // CONFIG 帧：宿主据此 configure 解码器（永不吞掉）
        session?.sendFrame(com.uav.protocol.Flags.pack(0, config = true), payload)
    }

    override fun onFrame(ptsAndFlags: Long, data: ByteArray) {
        session?.sendFrame(ptsAndFlags, data)
    }

    override fun onError(t: Throwable) {
        AccessoryStatus.set("编码器错误: ${t.message}")
        session?.failAndClose("编码器错误: ${t.message}")
    }

    // ------------------------------------------------------------------ 会话回调

    override fun onStatus(text: String) {
        AccessoryStatus.set(text)
    }

    override fun onError(message: String, cause: Throwable?) {
        AndroidLogger.e(TAG, message, cause)
        AccessoryStatus.set("错误: $message")
    }

    override fun onClosed(reason: String) {
        AccessoryStatus.set("会话结束: $reason")
        stopEverything()
        stopSelf()
    }

    // ------------------------------------------------------------------ 前台通知

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ProjectionActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    // ------------------------------------------------------------------ 生命周期

    private fun stopEverything() {
        stopCapture()
        session?.close()
        session = null
        dumpChannel?.close()
        dumpChannel = null
        fdChannel = null
        projection?.let { p ->
            runCatching { p.unregisterCallback(projectionCallback) }
            runCatching { p.stop() }
        }
        projection = null
    }

    override fun onDestroy() {
        AccessoryStatus.set("Service 结束")
        stopEverything()
        super.onDestroy()
    }

    companion object {
        const val TAG = "UAV-Accessory"

        const val ACTION_START = "com.uav.accessory.START"
        const val ACTION_STOP = "com.uav.accessory.STOP"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        const val DUMP_FILE_NAME = "uav-dump.uav"

        private const val CHANNEL_ID = "uav-capture"
        private const val NOTIFICATION_ID = 4101

        fun startIntent(
            context: Context,
            accessory: UsbAccessory?,
            resultCode: Int,
            resultData: Intent,
        ): Intent = Intent(context, CaptureService::class.java).apply {
            action = ACTION_START
            putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).apply { action = ACTION_STOP }
    }
}
