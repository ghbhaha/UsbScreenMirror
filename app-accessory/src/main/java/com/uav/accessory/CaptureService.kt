package com.uav.accessory

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Display
import androidx.core.content.ContextCompat
import com.uav.accessory.audio.AudioPipeline
import com.uav.accessory.session.AccessorySession
import com.uav.accessory.usb.FdByteChannel
import com.uav.accessory.video.VirtualDisplayEncoder
import com.uav.protocol.ByteChannel
import com.uav.protocol.Flags
import com.uav.protocol.ParsedOpen
import com.uav.protocol.StreamHeader
import com.uav.protocol.StreamId
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
class CaptureService : Service(), AccessorySession.Listener, VirtualDisplayEncoder.Sink, AudioPipeline.Sink {

    private var session: AccessorySession? = null
    private var fdChannel: FdByteChannel? = null
    private var dumpChannel: com.uav.accessory.debug.DumpByteChannel? = null
    private var encoder: VirtualDisplayEncoder? = null
    private var projection: MediaProjection? = null

    /**
     * 音频内录管线（10.13）。
     *
     * 与 [encoder] 一样**不随链路断开而销毁** —— 重建要重新申请 `AudioRecord`，
     * 没必要；重连后走 [AudioPipeline.resendStartup] 即可。
     */
    private var audio: AudioPipeline? = null

    /** 宿主是否请求了音频（来自 `OPEN` 的 `audio` 参数）。 */
    @Volatile
    private var audioRequested = false

    /**
     * 本次前台服务是否包含 `microphone` 类型。
     *
     * ★ 必须显式记账：`startForeground` 的类型与"是否真的在录音"要对得上，
     *   否则会出现"录音被系统静默掐掉"（Android 14 的 while-in-use 规则）。
     */
    @Volatile
    private var microphoneTypeActive = false

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

    // ---------------------------------------------------------------- 自动重连（P0）

    /** 连续重连尝试次数；成功建链后归零。 */
    private val reconnectAttempts = java.util.concurrent.atomic.AtomicInteger()

    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val reconnectTask = Runnable { attemptReconnect() }

    /**
     * 服务正在收尾（用户点停止 / 重连放弃 / `onDestroy`）。
     *
     * 没有它会出现这种情况：`stopEverything()` 主动 `close()` 会话后，异步到达的
     * `onClosed` 又把"我们自己的停止"当成断线，于是排一次重连 —— 服务刚停又被拉起。
     */
    @Volatile
    private var stopping = false

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

                override fun requestKeyFrame() {
                    encoder?.requestKeyFrameForHost()
                }
            },
            logger = AndroidLogger,
            listener = this,
        )
        session = s
        activeSession = s
        // 建链成功 -> 取消排队中的重连、计数归零（下次断开又是全新的 N 次机会）。
        // 这条也顺手解决了"主动重启后被 onClosed 误排重连"的重复建链问题。
        cancelReconnect()
        reconnectAttempts.set(0)
        stopping = false
        s.start()
        AccessoryStatus.set("配件已打开（${accessory.model}/${accessory.manufacturer}），等待宿主报文")
    }

    // ------------------------------------------------------------------ 采集

    private fun startCaptureInternal(options: ParsedOpen, session: AccessorySession) {
        audioRequested = options.audio

        // ★ 配件重新接入时复用既有编码器：投影令牌只允许 createVirtualDisplay 一次，
        //   重建会失败并要求用户重新授权。这里只重发流头 + 缓存的 CONFIG。
        val existing = encoder
        if (existing != null && existing.isRunning) {
            // ★ 10.17：宿主可以切换「1:1 / 高清」像素模式并重开会话 —— 复用前先比一下
            //   "按新参数该编多大"。不一样就重建（复用虚拟屏），否则 max_size 改了却照旧编码，
            //   开关等于没生效（真机实测踩到）。
            if (existing.applyOptions(options)) {
                // 已按新尺寸重建：新输出线程**已重发视频流头 + CONFIG**，这里只补音频。
                //   ⚠️ 此时绝不能再 resendStartup —— 会多发一份流头，把宿主解码器搞乱。
                val audioResumed = resumeAudio()
                AccessoryStatus.set(
                    when {
                        audioResumed == null -> "像素模式变更：已按新尺寸重建编码器（宿主未要音频）"
                        audioResumed -> "像素模式变更：已按新尺寸重建编码器，视频/音频流头均已重发"
                        else -> "像素模式变更：已重建编码器（音频重发失败：还没有音频 CONFIG）"
                    }
                )
                return
            }
            val ok = existing.resendStartup()
            // 音频同样只重发流头 + CONFIG —— 宿主的音频解码器也是按连接新建的
            val audioResumed = resumeAudio()
            AccessoryStatus.set(
                when {
                    !ok -> "复用既有编码器（无缓存 CONFIG，宿主可能无法解码）"
                    audioResumed == null -> "复用既有编码器，已重发流头与 CONFIG（宿主未要音频）"
                    audioResumed -> "复用既有编码器与内录，已重发两条流的流头与 CONFIG"
                    else -> "复用既有编码器（音频重发失败：还没有音频 CONFIG）"
                }
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
        startAudioIfWanted(p, options)
        AccessoryStatus.set(
            "编码中：${screen.x}x${screen.y} -> ${scaled[0]}x${scaled[1]} ${audioNote()}"
        )
    }

    // ------------------------------------------------------------------ 音频内录（10.13）

    /** 状态文案后缀：让手机界面上直接看得到"这次到底有没有声音"。 */
    private fun audioNote(): String = when {
        !audioRequested -> "（宿主未要音频）"
        audio?.isRunning != true -> "（⚠️ 无音频）"
        else -> "（含内录音频）"
    }

    /** 重连路径：复用既有内录。返回 null 表示宿主这次没要音频。 */
    private fun resumeAudio(): Boolean? {
        if (!audioRequested) return null
        val a = audio ?: return false
        if (!a.isRunning) return false
        return a.resendStartup()
    }

    /**
     * 首次启动路径：按需拉起内录。
     *
     * ★ **任何失败都只降级、不掐会话**：没给录音权限、ROM 阉割内录、被系统活动限制挡住
     *   —— 这些都不该让用户连画面都看不到。
     */
    private fun startAudioIfWanted(p: MediaProjection, options: ParsedOpen) {
        if (!audioRequested) {
            AndroidLogger.i(TAG, "宿主未请求音频，本次只投屏")
            return
        }

        val existing = audio
        if (existing != null && existing.isRunning) {
            existing.resendStartup()
            return
        }

        if (!hasRecordAudioPermission()) {
            AndroidLogger.w(
                TAG,
                "宿主请求了音频，但 RECORD_AUDIO 未授权 —— 本次只投屏。" +
                    "在手机上授予『录音』权限后重新投屏即可带声音"
            )
            AccessoryStatus.set("无录音权限，仅投屏")
            return
        }

        val pipe = AudioPipeline(
            projection = p,
            sink = this,
            logger = AndroidLogger,
            bitRate = options.options.audioBitRate,
        )
        // 先把 microphone 前台服务类型补上 —— 否则在后台录音会被系统静默掐成静音
        ensureMicrophoneType()
        if (pipe.start()) {
            audio = pipe
        } else {
            AndroidLogger.w(TAG, "内录未能启动（原因见上），本次只投屏")
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ 音频编码器回调
    //
    // 注意方法名带 `Audio` 前缀：与视频的 Sink 方法签名相同，若同名会被合并成一个实现，
    // 结果是把音频帧发进视频流（静默错流），所以刻意区分。

    override fun onAudioStreamHeader(header: StreamHeader) {
        session?.sendStreamHeader(header, StreamId.AUDIO)
    }

    override fun onAudioConfig(payload: ByteArray) {
        // CONFIG 帧：宿主据此 configure 音频解码器（永不吞掉）
        session?.sendFrame(Flags.pack(0, config = true), payload, StreamId.AUDIO)
    }

    override fun onAudioFrame(ptsAndFlags: Long, data: ByteArray) {
        session?.sendFrame(ptsAndFlags, data, StreamId.AUDIO)
    }

    override fun onAudioError(t: Throwable) {
        // ★ 音频出问题**不掐会话**：把管线停掉即可，视频继续
        AndroidLogger.e(TAG, "内录出错，本次将不再有声音: ${t.message}", t)
        AccessoryStatus.set("内录出错（已停用音频）: ${t.message}")
        runCatching { audio?.stop() }
        audio = null
    }

    private fun stopCapture() {
        unregisterRotationWatcher()
        encoder?.stop()
        encoder = null
        audio?.stop()
        audio = null
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

        // ★ 链路级错误（`readFully timeout` 之类）走的是这里，**不一定**会再走 onClosed，
        //   所以必须在这里也安排重连，否则断了照样回不来。
        //   用 `session != null` 判断，避免与 onClosed 重复排队、白白吃掉重连配额。
        if (!stopping && (session != null || fdChannel != null)) {
            // ★ 必须真的把 fd 关掉（见 releaseTransport）：只把引用置 null 会漏 fd，
            //   而漏掉的 fd 会让手机端永久切不进配件模式。
            releaseTransport("链路错误: $message")
            scheduleReconnect(message)
        }
    }

    /**
     * 传输会话结束。
     *
     * ★ **不再直接 `stopSelf()`** —— 那正是"断了就回不来"的根源。
     *   改为自动重连，并且**保留投影与编码器**：`MediaProjection` 令牌只允许
     *   `createVirtualDisplay` 一次，销毁就等于要重新弹一次录屏授权 ——
     *   而"免去这次重新授权"恰恰是自动重连的意义。
     */
    override fun onClosed(reason: String) {
        AccessoryStatus.set("会话结束: $reason")
        session = null
        activeSession = null
        if (stopping) {
            // 我们自己主动关的（用户点停止 / 重连放弃），别再排一次重连
            AndroidLogger.i(TAG, "服务正在收尾，不安排重连（$reason）")
            return
        }
        scheduleReconnect(reason)
    }

    /**
     * "假配件"：会话起来了但宿主一声不响。
     *
     * ★ **刻意只释放、不重连。** 原因见 [AccessorySession.Listener.onIdleTimeout]：
     *   fd 被我们握着时手机端切不进配件模式，硬重连只会再次拿到同一个假配件、
     *   把互锁继续维持下去。释放之后等宿主重新握手，系统会重新派发 attach，
     *   那时 [ProjectionActivity.onNewIntent] 会把会话重新拉起来。
     */
    override fun onIdleTimeout(reason: String) {
        AndroidLogger.w(TAG, "释放假配件（$reason），等待宿主重新握手")
        cancelReconnect()
        releaseTransport("假配件: $reason")
        AccessoryStatus.set("配件无应答（$reason），已释放。等待宿主重新握手…")
    }

    // ------------------------------------------------------------------ 前台通知

    private fun goForeground(typed: Boolean) {
        val notification = buildNotification()
        if (typed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 阶段二：拿到投影后必须带上 mediaProjection 类型，之后才允许 getMediaProjection()
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (canDeclareMicrophoneType()) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                microphoneTypeActive = true
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            // 阶段一：此时还没有投影，用不带类型的 startForeground（等价于 type NONE），
            // 避免过早声明 mediaProjection 类型导致校验失败
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 内录能否声明 `microphone` 前台服务类型。
     *
     * ★ 这是本次音频改造里最"反直觉"的一条：**只用 `mediaProjection` 类型是不够的**。
     *   查 AOSP 的 `ForegroundServiceTypePolicy` 清单可以看到：
     *   - `mediaProjection` 类型的运行时前提只有 `CAPTURE_VIDEO_OUTPUT` + `OP_PROJECT_MEDIA`
     *     —— **里面没有 `RECORD_AUDIO`**；
     *   - `RECORD_AUDIO` 只出现在 `microphone` 类型的前提下。
     *
     *   而 `RECORD_AUDIO` 属于"使用时权限"（Android 11 起）：应用退到后台后，
     *   只有当前台服务带了**覆盖该权限的类型**才允许使用。所以后台内录必须并上
     *   `microphone` 类型，否则会出现最恶心的形态 —— **不报错、但录到全 0**。
     *
     * ⚠️ 反向风险同样要防：`microphone` 类型要求**先拿到 RECORD_AUDIO**，
     *   没授权就传这个类型会直接抛 SecurityException，把整个前台服务（也就是投屏）打掉。
     *   所以这里层层设限：宿主没要音频 → 不加；没授权 → 不加。
     */
    private fun canDeclareMicrophoneType(): Boolean {
        if (!audioRequested) return false
        // FOREGROUND_SERVICE_TYPE_MICROPHONE 常量 API 30 才有
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return hasRecordAudioPermission()
    }

    /**
     * 确保前台服务已带上 `microphone` 类型。
     *
     * 时序上"要不要音频"是收到宿主 `OPEN` 才知道的，而前台服务早就以
     * `mediaProjection` 类型起来了 —— 所以这里要**再调一次 `startForeground`
     * 把类型并上去**（官方文档明确支持"服务后续需要新权限时再调一次并追加类型"）。
     */
    private fun ensureMicrophoneType() {
        if (microphoneTypeActive || !canDeclareMicrophoneType()) return
        goForeground(typed = true)
        AndroidLogger.i(TAG, "前台服务已追加 microphone 类型（内录在后台录音需要它）")
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

    // ------------------------------------------------------------------ 自动重连（P0）

    /**
     * 链路断开后安排一次自动重连。
     *
     * 为什么不销毁编码器 / 虚拟屏：`MediaProjection` 令牌只允许 `createVirtualDisplay`
     * 一次，销毁就意味着要**重新弹一次录屏授权**。重连成功后走 `resendStartup()`
     * 复用既有编码器（该路径已在"配件重新接入"场景验证过）。
     *
     * 放弃时也**不是**简单退出：会清干净并 `stopSelf()`，把状态交还给系统 ——
     * 用户重新插线时系统仍会派发配件 intent 把我们拉起。
     */
    private fun scheduleReconnect(reason: String) {
        val n = reconnectAttempts.incrementAndGet()
        if (n > MAX_RECONNECT_ATTEMPTS) {
            AndroidLogger.w(TAG, "自动重连放弃（已试 $MAX_RECONNECT_ATTEMPTS 次）：$reason")
            AccessoryStatus.set("自动重连失败，已停止。请重新插线")
            stopEverything()
            stopSelf()
            return
        }
        AndroidLogger.i(TAG, "安排自动重连第 $n/$MAX_RECONNECT_ATTEMPTS 次（原因：$reason）")
        AccessoryStatus.set(
            "链路断开（$reason），${RECONNECT_DELAY_MS / 1000}s 后自动重连（第 $n 次）…"
        )
        reconnectHandler.removeCallbacks(reconnectTask)
        reconnectHandler.postDelayed(reconnectTask, RECONNECT_DELAY_MS)
    }

    private fun attemptReconnect() {
        val accessory = resolveAccessory(null)
        if (accessory == null) {
            // 多半是物理拔出：系统会重新派发 intent 把我们拉起，这里继续排队等（次数上限兜底）
            AccessoryStatus.set("未找到 USB 配件，继续等待…")
            scheduleReconnect("配件不存在")
            return
        }
        // ★ USB 操作不能放主线程（红线）：openAccessory 可能阻塞
        Thread({ openAccessoryAndStartSession(accessory) }, "uav-reconnect").apply {
            isDaemon = true
            start()
        }
    }

    private fun cancelReconnect() {
        reconnectHandler.removeCallbacks(reconnectTask)
    }

    /**
     * 只拆掉**传输层**（会话 + fd + 抓流），保留投影与编码器。
     *
     * 用于配件重新接入与链路出错：旧 fd 已失效，但投影令牌是一次性的，
     * 停掉投影就意味着要重新弹一次录屏授权。
     *
     * ★ 一定要真的 `close()` 而不是只把引用置 null。
     *   `/dev/usb_accessory` 是**独占打开**的，漏掉一个 fd 就会把手机端
     *   "切进配件模式"的路径**永久堵死**：宿主侧表现为无限『等待重枚举超时』，
     *   只能靠拔插或强杀 App 恢复（实测：释放 fd 后下一次握手立刻成功）。
     */
    private fun releaseTransport(reason: String) {
        session?.close()
        session = null
        activeSession = null
        dumpChannel?.close()
        dumpChannel = null
        fdChannel?.close()
        fdChannel = null
        AndroidLogger.i(TAG, "传输层已释放（$reason）")
    }

    private fun teardownSessionOnly() {
        if (session == null && fdChannel == null) return
        AccessoryStatus.set("配件重新接入，重建传输会话（保留编码器）…")
        releaseTransport("配件重新接入")
    }

    private fun stopEverything() {
        stopping = true
        cancelReconnect()
        pendingOptions = null
        stopCapture()
        releaseTransport("停止采集")
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

        /** 自动重连：最多试几次（每次间隔 [RECONNECT_DELAY_MS]）。 */
        const val MAX_RECONNECT_ATTEMPTS = 10

        /**
         * 自动重连间隔。
         *
         * 取 3 秒：太短会在 USB 尚未稳定时反复失败（每次失败还会占掉一次配额），
         * 太长则用户要干等。10 次 × 3s ≈ 30 秒的耐心窗口，实测够用。
         */
        const val RECONNECT_DELAY_MS = 3_000L

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
