package com.uav.accessory

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Display
import com.uav.accessory.session.AccessorySession
import com.uav.accessory.usb.FdByteChannel
import com.uav.accessory.video.VirtualDisplayEncoder
import com.uav.protocol.ByteChannel
import com.uav.protocol.ParsedOpen
import com.uav.protocol.StreamHeader
import com.uav.protocol.Transport
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w

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

    /**
     * 屏幕旋转监听（P0「旋转三件套」）。
     *
     * ★ 回调里**只置标志**，重建由编码器自己的线程在安全点执行 ——
     *   这里是主线程，不能停线程、换 Surface。
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            encoder?.onDisplayChanged()
        }
    }

    private var rotationWatcherRegistered = false

    /** 收到 OPEN 但录屏授权还没到时的挂起参数。 */
    @Volatile
    private var pendingOptions: ParsedOpen? = null

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

            // ---- 第二阶段：拿到录屏授权结果 ----
            ACTION_SET_PROJECTION -> {
                // ★ Android 14+ 必须先以 mediaProjection 类型进入前台，才允许 getMediaProjection()
                goForeground(typed = true)

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

                // 如果 OPEN 已经先到（宿主比用户点授权更快），这里把挂起的采集补启动
                val pending = pendingOptions
                val s = session
                if (pending != null && s != null) {
                    pendingOptions = null
                    startCaptureInternal(pending, s)
                }
            }

            // ---- 第一阶段：只管打开配件、把会话跑起来（不依赖录屏授权）----
            ACTION_START -> {
                // ★ 无论后面走哪个分支，都必须先 startForeground —— 因为它是被
                //   startForegroundService 拉起来的，5s 内不进入前台会被系统判 ANR。
                goForeground(typed = projection != null)

                // ★ 配件重新接入（系统重新派发 USB_ACCESSORY_ATTACHED）时，
                //   旧的 accessory fd 已经失效，必须重建传输会话 —— 但**保留**投影与编码器，
                //   因为 MediaProjection 令牌只允许 createVirtualDisplay 一次。
                if (intent.getBooleanExtra(EXTRA_FORCE_RESTART, false)) {
                    teardownSessionOnly()
                }

                if (session != null) {
                    AccessoryStatus.set("会话已存在，忽略重复启动")
                    return START_NOT_STICKY
                }

                val accessory = resolveAccessory(intent)
                if (accessory == null) {
                    AccessoryStatus.set("没有可用的 USB 配件 —— 请重新插线")
                    return START_NOT_STICKY
                }
                // ★ 立刻打开配件并启动会话：这样即使录屏授权弹窗还没点，
                //   宿主的 CNXN/OPEN 也能被正常应答（否则链路会"没人接电话"）
                openAccessoryAndStartSession(accessory)
            }

            null -> Unit
            else -> AccessoryStatus.set("未知 action: ${intent.action}")
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ 投影

    private fun acquireProjection(resultCode: Int, resultData: Intent) {
        // ★ 幂等保护：Android 14+ 的投影令牌**只能兑换一次**，
        //   重复用同一份 resultData 调 getMediaProjection 会抛
        //   "Don't re-use the resultData to retrieve the same projection instance..."
        //   （实测：服务被重复拉起时就会踩到，且报错点离根因很远）
        if (projection != null) {
            AccessoryStatus.set("已有录屏授权，忽略重复的授权结果")
            AndroidLogger.w(TAG, "getMediaProjection 被重复调用，已忽略")
            return
        }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try {
            mgr.getMediaProjection(resultCode, resultData)
        } catch (t: Throwable) {
            AndroidLogger.e(TAG, "getMediaProjection 失败: ${t.message}", t)
            AccessoryStatus.set("换取录屏投影失败: ${t.message}")
            // 如实告知宿主，免得它一直等（计划 4.4）
            session?.failAndClose("换取录屏投影失败: ${t.message}")
            return
        }
        if (p == null) {
            AccessoryStatus.set("getMediaProjection 返回 null（授权结果无效）")
            session?.failAndClose("getMediaProjection 返回 null（授权结果无效）")
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

        // ★ openAccessory 需要"本 App 就是收到配件派发的那个 App"。
        //   实测偶发：服务启动比系统派发 intent 更早（或 App 刚被拉起），
        //   此时 openAccessory 会抛 "User has not given <uid> permission to accessory ..."。
        //   这类瞬时失败重试几次即可，直接报错会白白浪费一次插拔。
        var pfd: android.os.ParcelFileDescriptor? = null
        var lastError: String? = null
        for (attempt in 1..OPEN_ACCESSORY_ATTEMPTS) {
            pfd = try {
                usbManager.openAccessory(accessory)
            } catch (t: Throwable) {
                lastError = t.message
                null
            }
            if (pfd != null) {
                if (attempt > 1) {
                    AndroidLogger.i(TAG, "openAccessory 第 $attempt 次尝试成功")
                }
                break
            }
            AndroidLogger.w(TAG, "openAccessory 第 $attempt 次失败: $lastError")
            if (attempt < OPEN_ACCESSORY_ATTEMPTS) {
                try {
                    Thread.sleep(OPEN_ACCESSORY_RETRY_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        if (pfd == null) {
            // 计划 3.2：openAccessory 返回 null 必须显式报错，不能装死
            AccessoryStatus.set("openAccessory 失败（无权限或配件已被占用）: $lastError")
            return
        }

        val raw = FdByteChannel(pfd, AndroidLogger)
        fdChannel = raw

        // 抓流落盘（计划 3.12）：给宿主离线回放用；失败不影响实时链路。
        // ★ release 下必须关掉（计划 5.2）—— 抓流是**全量**写出的（实测 110 秒 ≈ 28 MB），
        //   发布包里持续写盘既耗电又占空间，而且用户完全不知情。
        val transportChannel: ByteChannel = if (DebugTools.DUMP_ENABLED) {
            val dumpFile = java.io.File(getExternalFilesDir(null) ?: filesDir, DUMP_FILE_NAME)
            com.uav.accessory.debug.DumpByteChannel(raw, dumpFile, AndroidLogger).also {
                dumpChannel = it
            }
        } else {
            // 留一条日志让"门控生效"在运行时**可自证**（否则只能靠文件没出现来反推）
            AndroidLogger.i(TAG, "release 构建：抓流落盘已关闭，只转发不落盘")
            raw
        }

        val transport = Transport(transportChannel, AndroidLogger, AccessorySession.TAG)
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
        activeSession = s
        s.start()
        AccessoryStatus.set("配件已打开（${accessory.model}/${accessory.manufacturer}），等待宿主报文")
    }

    // ------------------------------------------------------------------ 采集

    private fun startCaptureInternal(options: ParsedOpen, session: AccessorySession) {
        // ★ 配件重新接入时复用既有编码器：投影令牌只允许 createVirtualDisplay 一次，
        //   重建会失败并要求用户重新授权。这里只重发流头 + 缓存的 CONFIG。
        val existing = encoder
        if (existing != null && existing.isRunning) {
            val ok = existing.resendStartup()
            AccessoryStatus.set(
                if (ok) "复用既有编码器，已重发流头与 CONFIG"
                else "复用既有编码器（无缓存 CONFIG，宿主可能无法解码）"
            )
            return
        }

        val p = projection
        if (p == null) {
            // 录屏授权还没到（用户还没在系统弹窗上点确认）—— 先记住参数，
            // 等 ACTION_SET_PROJECTION 到了再补启动。绝不在这里发 CLSE：
            // 宿主已经收到 OKAY，用户点一下授权就能开始，没必要把会话掐掉。
            pendingOptions = options
            AccessoryStatus.set("已收到宿主参数，等待录屏授权（请在手机上点『开始录制』）…")
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
        registerRotationWatcher()
        AccessoryStatus.set("编码中：${screen.x}x${screen.y} -> ${scaled[0]}x${scaled[1]}")
    }

    private fun stopCapture() {
        unregisterRotationWatcher()
        encoder?.stop()
        encoder = null
    }

    // ------------------------------------------------------------------ 旋转监听（P0）

    /**
     * 注册屏幕旋转监听。
     *
     * ★ 回调里**只置标志**（[VirtualDisplayEncoder.onDisplayChanged]），
     *   真正的重建由编码器自己的线程在安全点执行 ——
     *   这里跑在主线程，不能停线程、换 Surface。
     */
    private fun registerRotationWatcher() {
        if (rotationWatcherRegistered) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        dm.registerDisplayListener(displayListener, null)
        rotationWatcherRegistered = true
        AndroidLogger.i(TAG, "已注册屏幕旋转监听")
    }

    private fun unregisterRotationWatcher() {
        if (!rotationWatcherRegistered) return
        runCatching {
            (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.unregisterDisplayListener(displayListener)
        }
        rotationWatcherRegistered = false
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

    private fun goForeground(typed: Boolean) {
        val notification = buildNotification()
        if (typed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 阶段二：拿到投影后必须带上 mediaProjection 类型，之后才允许 getMediaProjection()
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            // 阶段一：此时还没有投影，用不带类型的 startForeground（等价于 type NONE），
            // 避免过早声明 mediaProjection 类型导致校验失败
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

    /**
     * 只拆掉**传输会话与 fd**，保留投影与编码器。
     *
     * 用于配件重新接入：旧 fd 已随重枚举失效，但投影令牌是一次性的，
     * 停掉投影就意味着要重新弹一次录屏授权。
     */
    private fun teardownSessionOnly() {
        if (session == null && fdChannel == null) return
        AccessoryStatus.set("配件重新接入，重建传输会话（保留编码器）…")
        session?.close()
        session = null
        activeSession = null
        dumpChannel?.close()
        dumpChannel = null
        fdChannel = null
    }

    private fun stopEverything() {
        pendingOptions = null
        stopCapture()
        session?.close()
        session = null
        activeSession = null
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

        /** 阶段一：打开配件、启动会话（不需要录屏授权）。 */
        const val ACTION_START = "com.uav.accessory.START"

        /** 阶段二：把录屏授权结果补进来。 */
        const val ACTION_SET_PROJECTION = "com.uav.accessory.SET_PROJECTION"

        const val ACTION_STOP = "com.uav.accessory.STOP"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /** 配件重新接入时置 true：重建传输会话（保留投影与编码器）。 */
        const val EXTRA_FORCE_RESTART = "forceRestart"

        const val DUMP_FILE_NAME = "uav-dump.uav"

        /** openAccessory 的重试次数与间隔（应对"系统派发晚于服务启动"的瞬时无权限）。 */
        const val OPEN_ACCESSORY_ATTEMPTS = 6
        const val OPEN_ACCESSORY_RETRY_MS = 400L

        private const val CHANNEL_ID = "uav-capture"
        private const val NOTIFICATION_ID = 4101

        /** 阶段一意图：只带配件，不带投影。[forceRestart] 用于配件重新接入时重建会话。 */
        fun startIntent(context: Context, accessory: UsbAccessory?, forceRestart: Boolean = false): Intent =
            Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
                putExtra(EXTRA_FORCE_RESTART, forceRestart)
            }

        /** 阶段二意图：补入录屏授权结果。 */
        fun projectionIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, CaptureService::class.java).apply {
                action = ACTION_SET_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).apply { action = ACTION_STOP }

        /** 当前活跃会话的只读引用（供 Activity 在授权被拒时回 CLSE）。 */
        @Volatile
        private var activeSession: AccessorySession? = null

        fun currentSession(): AccessorySession? = activeSession
    }
}
