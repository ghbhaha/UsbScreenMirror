package com.uav.host.aoa.capture.session

import android.os.SystemClock
import android.view.Surface
import com.uav.host.aoa.HostActivity
import com.uav.host.aoa.capture.audio.AudioPlayer
import com.uav.host.common.AndroidLogger
import com.uav.host.aoa.capture.debug.FileByteChannel
import com.uav.host.aoa.capture.usb.UsbAttachRouter
import com.uav.host.aoa.capture.video.VideoDecoder
import com.uav.protocol.Bytes
import com.uav.protocol.Logger
import com.uav.protocol.adb.AdbMessage
import com.uav.protocol.aoa.AppVersions
import com.uav.protocol.aoa.Command
import com.uav.protocol.aoa.Connection
import com.uav.protocol.aoa.ConnectionListener
import com.uav.protocol.aoa.Frame
import com.uav.protocol.aoa.FrameQueue
import com.uav.protocol.aoa.GopTailGate
import com.uav.protocol.aoa.Protocol
import com.uav.protocol.aoa.StreamId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.uav.protocol.aoa.Transport
import com.uav.protocol.d
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.media.AvSyncGate
import com.uav.protocol.media.ClockOffset
import com.uav.protocol.media.ClockSync
import com.uav.protocol.media.LatencyStats
import com.uav.protocol.media.Options
import com.uav.protocol.media.Scale
import com.uav.protocol.w
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 宿主侧会话编排（计划 4.1~4.9）。
 *
 * 时序（本端是发起方）：
 * ```
 * 宿主 --CNXN(本机 w/h, version=1)--> 手机
 * 宿主 <--CNXN(手机 w/h)------------- 手机
 * 宿主   max_size = align8(min(两边长边))   ← 两边尺寸都已知，故由宿主算（计划 4.3）
 * 宿主 --OPEN(newScreen1:2 ...)-----> 手机
 * 宿主 <--OKAY---------------------- 手机
 * 宿主 <--WRTE[1](12B 流头)--------- 手机
 * 宿主 <--WRTE[2](CONFIG 帧)-------- 手机   → 驱动解码器 configure（永不吞掉）
 * 宿主 <--WRTE[3..](普通帧)--------- 手机   → 送入解码器直渲
 * ```
 *
 * **所有阻塞等待都带超时**（计划 4.3 / 4.6），超时一律转 [Listener.onError]。
 *
 * 本类刻意只依赖 [Transport]，不感知 USB —— 因此 `replay = true` 时同一套代码
 * 可以直接从 `.uav` 文件回放，跳过全部握手（计划 4.0）。
 */
class HostSession(
    private val transport: Transport,
    private val localWidth: Int,
    private val localHeight: Int,
    private val surfaceProvider: () -> Surface?,
    private val logger: Logger = AndroidLogger,
    private val listener: Listener,
    /** 回放模式：跳过 CNXN/OPEN 握手，直接消费文件里的 WRTE。 */
    private val replay: Boolean = false,

    /**
     * 本端 `versionCode`，握手时通过 `CNXN.arg1` 报给配件端（10.39）。
     *
     * 由调用方传入而不是直接读 `BuildConfig`：本类要能在 JVM 单测里跑，
     * 掺进构建系统常量会让"喂固定字节 → 断言出站字节"这类测试没法做。
     */
    private val appVersion: Int = 0,

    /** 是否向手机请求音频（10.13）。关掉用于 A/B 对比排障。 */
    private val audioEnabled: Boolean = true,

    /**
     * 音频起播延迟（A/V 粗补偿）。
     *
     * 音频管线比视频少一段（无合成 / 无 vsync），所以默认让音频**晚一点起播**去凑齐差值。
     * 这是个**起播时刻**的一次性补偿，不是长期漂移校正；精确同步留待后续项。
     */
    private val audioDelayMs: Int = DEFAULT_AUDIO_DELAY_MS,

    /**
     * 宿主**视频显示区**的可用尺寸 `[w, h]`（= 根视图尺寸，px）；尚未布局时返回 `null`。
     *
     * 1:1 像素映射（10.15）的输入：编码尺寸按"画面在这个区域里适配后有多大"来定，
     * 而不是按宿主面板长边。做成回调是因为视图尺寸随旋转/分屏动态变化，
     * 必须在发 `OPEN` 的那一刻取**当前值**。
     */
    private val displayArea: () -> IntArray? = { null },

    /**
     * **当前**是否按 1:1 像素映射协商编码尺寸（10.17 起可切；10.18 起可原地重协商）。
     *
     * 做成**回调**而不是构造时捕获的布尔值：原地重协商会在**同一条会话**里重新下发 OPEN，
     * 必须取"此刻"的模式 —— 用捕获值会一直按会话创建时的口径算（真机踩到：
     * 用户切到 1:1 后重协商仍下发 `max_size=1920 display_area=-`）。
     */
    private val oneToOne: () -> Boolean = { true },

    /**
     * 会话所在的作用域（由 `LinkCoordinator` 持有）。
     *
     * 为什么需要它：握手里的两处"等对端"（CNXN / OKAY）以前是 `CountDownLatch`，
     * 一等就是 5 秒且**不可取消** —— 页面关掉、用户重连，线程还得把 5 秒等完。
     * 换成协程后超时是 `withTimeoutOrNull`，收尾直接 `cancel()`。
     *
     * ⚠️ 读循环（`uav-decode` / `uav-audio-in`）**仍然是线程**：`bulkTransfer` 不可中断，
     * 协程的取消打不断卡在 URB 上的调用（iOS 侧实测卡死数分钟，只能关连接）。
     */
    private val scope: CoroutineScope,
) {

    interface Listener {
        fun onStatus(text: String) {}
        fun onVideoSize(width: Int, height: Int) {}
        fun onFirstFrame() {}

        /**
         * 注意：这里**故意不给 `cause` 默认值**。
         * [HostActivity] 同时实现了本接口与 `UsbAttachRouter.Callback`，
         * 若两个 `onError` 都带默认值，Kotlin 会报
         * "More than one function overridden by 'onError' declares a default value"。
         */
        fun onError(message: String, cause: Throwable?)

        fun onClosed(reason: String) {}

        /**
         * 对端在配件模式下**完全没有应答**（CNXN 发出后超时，或 OPEN 没有 OKAY）。
         *
         * 单独开一个回调而不是走 [onError]：这不是链路故障（USB 枚举、切换配件模式都成功了），
         * 而是"对端没有软件接管这条管道"—— 用户要做的是**去装/打开配套 App**，
         * 而不是等宿主自动重连。上层据此换成可操作的引导文案（含下载入口）。
         */
        fun onPeerAppMissing() {}
    }

    private val videoQueue = FrameQueue(StreamId.VIDEO, logger = logger)

    /** 对端在 `CNXN.arg1` 报的 `versionCode`（0 = 旧包），仅用于日志与提示。 */
    @Volatile
    private var peerAppVersion = AppVersions.UNKNOWN

    /**
     * 宿主版本**低于**配件端 ⇒ 拒绝继续（见 `AppVersions.hostCanServe`）。
     *
     * 为什么必须在 latch 之后才处理：这个标志在**读线程**的 CNXN 回调里置位，
     * 而 `fail()` 会关闭连接 —— 从连接自己的读回调里关自己容易自锁。
     */
    @Volatile
    private var versionBlocked = false

    /**
     * 最后一次收到**媒体帧**（非 CONFIG）的单调时刻；`0` = 本次会话还没收到过。
     *
     * 供流健康监测（10.30）用：上层（`HostActivity` 的每秒 tick）拿它喂
     * `:protocol/StreamHealth`，据此判断"手机端是不是不再产帧了"。
     * 以前没有这个判据时，手机端编码器静默卡死会让宿主一直显示「投屏中」，
     * 用户只看到画面冻住却无从判断原因。
     */
    @Volatile
    var lastVideoFrameAtMs: Long = 0L
        private set

    /**
     * A/V 同步门（10.20③）：以**音频播放头**为基准，决定每一帧是"渲染 / 等一下再渲染 / 丢掉"。
     *
     * 决策逻辑在 `:protocol/AvSyncGate`（纯逻辑、有单测），本类只负责把两个时钟喂进去。
     */
    private val avSync = AvSyncGate()

    /** 上一次读到的音频播放位置；用来发现"音频卡住了"（此时必须放弃同步，否则会把视频全丢掉）。 */
    @Volatile
    private var lastAudioPlayedNanos = 0L

    /** 音频播放头连续不前进的次数（超过阈值就临时关闭 A/V 同步）。 */
    private val audioStallFrames = java.util.concurrent.atomic.AtomicInteger()

    @Volatile
    private var avSyncDisabled = false

    /**
     * 音频队列（10.13）。
     *
     * 深度比视频大：AAC 帧约 21ms 一个（约 47 个/秒），深度 8 才对应 ~170ms 的抖动吸收，
     * 而视频一方 4 帧就已经是 ~130ms。音频帧都不是关键帧，队满时丢弃代价只是一声咔哒。
     */
    private val audioQueue = FrameQueue(StreamId.AUDIO, capacity = AUDIO_QUEUE_DEPTH, logger = logger)

    private var audio: AudioPlayer? = null

    private var audioThread: Thread? = null

    /** 音频 CONFIG 只该生效一次；重复到达时说明手机端重发了（重连），需要重配。 */
    private var audioConfigured = AtomicBoolean(false)

    private val peerCnxnSignal = CompletableDeferred<Unit>()

    /**
     * 等待手机 `OKAY`（OPEN 应答）。
     *
     * ⚠️ 是 `var` 而不是 `val`：**原地重协商**（10.18）要在同一条连接上重发 OPEN，
     * 每次都得换一只新的 `CompletableDeferred`（一次性、完成即定型）。`sendOpen()` 里
     * 先换再发，并用**局部引用**等待，避免与下一次重协商互相干扰。
     */
    @Volatile
    private var openAckSignal = CompletableDeferred<Boolean>()

    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    /**
     * 收尾（[stopInternal]）是否已经跑过。
     *
     * ★ 为什么不是复用 [closed]：两者的语义不同 —— [closed] 挡的是**回调**
     *   （`fail` / `peerSilent` 开头就 `if (closed.get()) return`，避免对已经死掉的会话
     *   再报一次错），而一次失败正常收尾时**不会**把它置上。于是 `fail()` 先
     *   `stopInternal()`、上层紧接着又 `session.close()`（`close` 的 CAS 只挡自己），
     *   收尾跑两遍、`host session stopped` 打两遍（2026-10-20 真机日志）。
     *
     * ⚠️ 守卫必须放在**释放方法内部**，不能写回 `close()`：内存里那条教训是
     *   "幂等标志把入口锁死" —— 谁先置位、谁没做完清理，之后所有 `close()` 全部
     *   直接 return，fd 永远释放不掉。放在这里则天然满足"任何路径都走同一处释放"。
     *
     * 之所以敢在这里 CAS：下面每一步都已经 `quiet()` 包住，本方法不会中途抛异常，
     * 不存在"置了位却没释放完"的窗口。
     */
    private val stopped = AtomicBoolean(false)

    private var connection: Connection? = null
    private var consumerThread: Thread? = null
    /** 握手协程（收尾时 cancel；不再是一条裸线程）。 */
    private var sessionJob: Job? = null

    @Volatile
    private var decoder: VideoDecoder? = null

    /**
     * 最近一次 CONFIG 的**原始 payload** 与流头尺寸。
     *
     * 为什么必须留一份：MediaCodec 的 output surface **在 `configure` 时固定、无法改绑**，
     * 所以"换 Surface"只能新建解码器，而它的 csd 只能来自同一份 CONFIG。
     * Surface 被销毁（切后台）时把解码器放掉、Surface 回来时用它重建。
     */
    @Volatile
    private var lastConfig: ByteArray? = null

    @Volatile
    private var lastConfigSize: Pair<Int, Int>? = null

    /**
     * 是否处于"要渲染"状态。
     *
     * Surface 被销毁后置 false：链路照常消费，但**不喂解码器、不渲染**。
     * 见 `HostActivity.surfaceCallback` 里记的那两个坑（崩溃 + 重连死锁）。
     */
    @Volatile
    private var rendering = true

    /**
     * 宿主是否在后台（声音暂停中，10.22）。
     *
     * 与 [rendering] 分开记：两者都由 Surface 生命周期驱动，但语义不同 ——
     * `rendering=false` 是"不喂解码器"，`audioPaused=true` 是"不喂音频"。
     * 分开的好处是万一将来只想静音不黑屏（或反之），改一处即可。
     */
    private val audioPaused = AtomicBoolean(false)

    /** 需要在（新的）Surface 上重建解码器；由消费线程执行，保证与帧处理同序。 */
    @Volatile
    private var pendingRebuild = false

    /**
     * 宿主侧的"等 IDR"门控（10.21）。
     *
     * 与手机端的 [com.uav.protocol.aoa.GopTailGate] 同理，但作用点不同：手机端 arm 是为了
     * **不推**缺 IDR 的 P 帧；这里 arm 是为了**即使手机端没配合**（老版本 App / RQID 丢失），
     * 也不把这些无主的 P 帧喂进刚建好的解码器 —— 喂进去轻则花屏，重则 `MediaCodec` 报错。
     *
     * 同样有 600ms 超时兜底：宁可短暂花屏，也不要一直黑屏。
     */
    private val resumeGate = GopTailGate()

    @Volatile
    private var phoneWidth = 0

    @Volatile
    private var phoneHeight = 0

    /**
     * 手机在 `CNXN` 里上报的**屏幕尺寸**。
     *
     * ★ 这是**触摸坐标**该用的空间：HID 触摸屏的逻辑上限（[com.uav.protocol.input.HidTouchPanel]）
     *   要按它来声明，而不是按编码后的视频尺寸（`max_size` 那个是缩放过的）。
     */
    val peerWidth: Int get() = phoneWidth

    val peerHeight: Int get() = phoneHeight

    private val framesSkippedBeforeConfig = AtomicLong()
    private var warnedMissingConfig = false
    private var reportedFirstFrame = false

    /** 因"重建解码器后等 IDR"被丢弃的帧数（诊断用；非 0 说明门控在起作用）。 */
    private val resumeTailDropped = AtomicLong()

    // ---- 端到端延迟测量（计划 4.10）----

    /** 延迟样本统计（滚动窗口 + 分位数）。 */
    private val latency = LatencyStats()

    /**
     * **只统计"新内容帧"**的延迟。
     *
     * 为什么要分两份：静止画面下编码器会按 `repeat-previous-frame-after = 100ms`
     * 反复送出**上一帧**（这是刻意的设计，让链路不空转），而这些重复帧的 pts
     * 停在旧值上 —— 实测静止时 p50 ≈ 143ms，而画面连续变化时 p50 ≈ 61ms，
     * 差了约一个 repeat 周期。**观感只由"内容真的变了"的那些帧决定**，
     * 所以这一份才是有意义的口径，全量那份用于观察与对照。
     */
    private val latencyFresh = LatencyStats()

    /** 上一帧被解码输出时的 pts（微秒，手机侧时基）；-1 = 尚无。 */
    private var lastDecodedPtsUs = -1L

    /** CNXN 发出时刻（本机单调时钟，纳秒）；-1 = 未发出（回放模式）。 */
    @Volatile
    private var cnxnSentNanos = -1L

    /** 两端时钟标定结果；null = 未标定（回放模式，或对端不带时钟读数）。 */
    @Volatile
    private var clockOffset: ClockOffset? = null

    /** 会话实际协商出来的参数，供 UI 显示。 */
    @Volatile
    var negotiatedMaxSize: Int = 0

    /** 本次协商用的宿主显示区（`""` = 未知，走了回退口径）。仅用于日志/STATS 核对。 */
    @Volatile
    var negotiatedDisplayArea: String = ""
        private set

    /**
     * 本次协商实际下发的**视频码率**（bps，10.20 起按编码面积缩放）。
     *
     * 只用于日志/统计显示 —— 真实码率要看 `connection.stats` 的字节计数。
     */
    @Volatile
    var negotiatedBitRate: Int = 0
        private set

    val isReplay: Boolean get() = replay

    fun start() {
        if (!running.compareAndSet(false, true)) return

        avSync.reset()
        lastAudioPlayedNanos = 0L
        audioStallFrames.set(0)
        avSyncDisabled = false
        audioPaused.set(false)

        val conn = Connection(transport, logger, TAG, connectionListener)
        conn.registerSink(StreamId.VIDEO, videoQueue)
        // 音频也注册：手机没发音频时这条 sink 只是闲着，不产生任何开销
        conn.registerSink(StreamId.AUDIO, audioQueue)
        connection = conn
        conn.start()

        consumerThread = Thread({ consumerLoop() }, "uav-decode").apply {
            isDaemon = true
            start()
        }
        // ★ 音频单独一条线程：它会被 AudioTrack 的阻塞写压住节拍，
        //   与视频共用会让视频的丢帧/重建把它带停（听感上就是咔哒声）。
        audioThread = Thread({ audioLoop() }, "uav-audio-in").apply {
            isDaemon = true
            start()
        }
        sessionJob = scope.launch(Dispatchers.IO + CoroutineName("uav-session")) { sessionFlow() }
    }

    // ------------------------------------------------------------------ 握手

    private suspend fun sessionFlow() {
        if (replay) {
            logger.i(TAG, "回放模式：跳过 CNXN/OPEN 握手，直接消费抓流数据")
            listener.onStatus("回放模式：跳过握手")
            return
        }
        try {
            // ★ payload 追加本机单调时钟读数（8B -> 16B）。手机在应答里回它自己的读数，
            //   本机据此标定两端时钟 —— 这是测端到端延迟的前提（计划 4.10）：
            //   两台设备的单调时钟起点不同，不标定就只能量到"延迟 + 未知偏移"。
            //   两端都只读前 8 字节，所以这个扩展是**向后兼容**的。
            val sentNanos = System.nanoTime()
            val payload = Bytes.intLE(localWidth) + Bytes.intLE(localHeight) +
                Bytes.longLE(sentNanos)
            cnxnSentNanos = sentNanos
            // ★ arg1 = 本端 versionCode：老包不读它（会忽略），新包据此核对两端版本。
            connection?.send(Command.CNXN, Protocol.VERSION, appVersion, payload)
            listener.onStatus("CNXN 已发送（本机 ${localWidth}x$localHeight），等待手机尺寸…")

            if (withTimeoutOrNull(TIMEOUT_MS) { peerCnxnSignal.await() } == null) {
                // ★ 对端在配件模式下"人不在"：CNXN 发出去 5 秒一个字节都没回。
                //   正常情况配件端 App 收到会立刻应答，所以这不是链路问题。
                peerSilent("等待手机 CNXN 超时（${TIMEOUT_MS}ms）：配件模式下对端没有任何应答")
                return
            }

            if (versionBlocked) {
                // ★ 单向硬约束：宿主可以更新，但不能比配件端旧（见 AppVersions.hostCanServe）
                fail("宿主版本过低：宿主 v$appVersion / 配件 v$peerAppVersion —— 请先升级宿主")
                return
            }

            if (!sendOpen()) {
                peerSilent("等待 OPEN 应答超时（${TIMEOUT_MS}ms）：对端收了 OPEN 却没有 OKAY")
                return
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            // ★ 收尾取消不是错误：页面上看见的应该是"已断开"，不是"握手异常"。
            //   （协程化之前这里永远走不到 —— 线程被 interrupt 时 await 抛的是 InterruptedException）
            throw c
        } catch (t: Throwable) {
            fail("会话握手异常: ${t.message}", t)
        }
    }

    /**
     * 计算并下发 OPEN，等待手机 `OKAY`。
     *
     * 握手（[sessionFlow]）与**原地重协商**（[renegotiate]）共用 —— 两者唯一的区别只是
     * 前者已先发过 CNXN，后者直接复用现有连接。
     *
     * @return true = 收到 OKAY；false = 超时（由调用方决定报错还是回退）
     */
    private suspend fun sendOpen(): Boolean {
        // ★ max_size 由宿主计算后下发（计划 4.3）：CNXN 交换后两边尺寸都已知。
        //   10.15 起按**视频显示区**算（1:1 像素映射），并把显示区一并下发 ——
        //   这样手机端旋转时能自己重算（见 ParsedOpen.encodeMaxSize），不必重新协商。
        //   10.17 起可用开关退回旧口径（按面板长边编高分辨率、宿主端缩放更锐）——
        //   关掉时**不下发显示区**，手机端 encodeMaxSize 自动回退到 max_size。
        // ★ 取**此刻**的像素模式（原地重协商时用户可能刚切过；见 [oneToOne] 的说明）
        val enabled = runCatching { oneToOne() }.getOrDefault(true)
        val area = if (enabled) runCatching { displayArea() }.getOrNull() else null
        val availW = area?.getOrNull(0) ?: 0
        val availH = area?.getOrNull(1) ?: 0
        val maxSize = if (enabled) {
            Scale.oneToOneMaxSize(
                phoneW = phoneWidth,
                phoneH = phoneHeight,
                availW = availW,
                availH = availH,
                hostPanelW = localWidth,
                hostPanelH = localHeight,
            )
        } else {
            Scale.computeMaxSize(phoneWidth, phoneHeight, localWidth, localHeight)
        }
        negotiatedMaxSize = maxSize
        negotiatedDisplayArea = if (availW > 0 && availH > 0) "${availW}x$availH" else ""

        // ★ 码率按**编码面积**缩放（10.20）：1:1 把面积降到旧口径的 ~27%，却仍按 8 Mbps 发
        //   ⇒ 每秒字节数一点没降，只是把同样码率摊到更少像素上。要真省流就得按面积缩。
        //   参考面积 = 旧口径（面板长边）下的面积，所以高清模式**保持 8 Mbps 不变**，
        //   且与手机当前方向无关（旋转不会让码率跳变）。
        val referenceMaxSize = Scale.computeMaxSize(phoneWidth, phoneHeight, localWidth, localHeight)
        val bitrate = Scale.videoBitRate(
            baseBitRate = Options.DEFAULT_VIDEO_BIT_RATE,
            maxSize = maxSize,
            srcW = phoneWidth,
            srcH = phoneHeight,
            referenceMaxSize = referenceMaxSize,
        )
        negotiatedBitRate = bitrate

        val options = Options.defaultOptions(maxSize, availW, availH).apply {
            audio = audioEnabled
            videoBitRate = bitrate
        }

        // ★ 换一只新闩再发：重协商时上一只可能已经被用掉/等待中
        val signal = CompletableDeferred<Boolean>()
        openAckSignal = signal
        connection?.send(Command.OPEN, StreamId.CONTROL, 0, Options.buildPayload(options))
        val sizeSrc = when {
            availW > 0 && availH > 0 ->
                "显示区 ${availW}x$availH（1:1 → max_size=$maxSize）"
            enabled ->
                "按面板长边 ${localWidth}x$localHeight 回退（显示区未知 → max_size=$maxSize）"
            else ->
                "高清模式（1:1 关）按面板长边 ${localWidth}x$localHeight → max_size=$maxSize"
        }
        listener.onStatus(
            "OPEN 已发送：手机 ${phoneWidth}x$phoneHeight / $sizeSrc，等待 OKAY…"
        )
        logger.i(
            TAG,
            "OPEN options: max_size=$maxSize display_area=${if (availW > 0) "${availW}x$availH" else "-"} " +
                "phone=${phoneWidth}x$phoneHeight panel=${localWidth}x$localHeight " +
                "bitrate=$bitrate(${bitrate / 1000}kbps, 基准 ${Options.DEFAULT_VIDEO_BIT_RATE / 1000}kbps 按面积缩)"
        )

        if (withTimeoutOrNull(TIMEOUT_MS) { signal.await() } != true) return false
        listener.onStatus("会话已建立，等待视频流…")
        return true
    }

    /**
     * **原地重协商**：不重连，在同一条连接上重发 `OPEN`（10.18）。
     *
     * 为什么不需要"先停推流"、也不需要断开重连：
     * - **手机端**：`AccessorySession` 的命令循环**无状态门槛**，`OPEN` 任何时候都处理 ——
     *   `handleOpen` → `capture.startCapture` → `VirtualDisplayEncoder.applyOptions`（10.17），
     *   其内部顺序正是"先停旧输出线程 → 复用虚拟屏按新尺寸重建 → 新输出线程重发流头+CONFIG"。
     * - **宿主端**：`Connection` 已支持**会话中途的新流头**（`updated != headers[id]` 会更新并回调），
     *   `FrameQueue.header` 更新后 `handleConfigFrame` 会释放旧解码器、按新尺寸新建 ——
     *   这正是"手机旋转重建"在线上跑的机制。
     *
     * 因为全程用的是**同一个已同步的解析器**，不会出现"重连时撞上推流中字节"的
     * `bad magic` / 握手超时 / 自动重试，切换耗时从 15~25s 降到约 <1s。
     *
     * @return true = 新参数已生效（收到 OKAY）；false = 不可用/超时，**调用方应回退到断开重连**
     */
    suspend fun renegotiate(): Boolean {
        if (replay) return false
        if (closed.get() || !running.get()) return false
        if (connection == null) return false
        if (phoneWidth <= 0 || phoneHeight <= 0) return false
        return try {
            logger.i(TAG, "原地重协商：在同一条连接上重发 OPEN")
            val ok = sendOpen()
            if (!ok) logger.w(TAG, "原地重协商未收到 OKAY，调用方应回退到断开重连")
            ok
        } catch (t: Throwable) {
            logger.w(TAG, "原地重协商异常（回退断开重连）: ${t.message}", t)
            false
        }
    }

    private val connectionListener = object : ConnectionListener {

        override fun onMessage(message: AdbMessage) {
            when (message.commandType) {
                Command.CNXN -> {
                    val recvNanos = System.nanoTime()
                    if (message.payload.size >= 8) {
                        phoneWidth = Bytes.getIntLE(message.payload, 0)
                        phoneHeight = Bytes.getIntLE(message.payload, 4)
                        logger.i(
                            TAG,
                            "peer CNXN: proto=${message.arg0} app=${message.arg1} " +
                                "${phoneWidth}x$phoneHeight",
                        )
                        checkPeerVersions(message.arg0, message.arg1)
                    } else {
                        logger.w(TAG, "CNXN payload 只有 ${message.payload.size} 字节，期望 8")
                    }
                    estimateClockOffset(recvNanos, message.payload)
                    peerCnxnSignal.complete(Unit)
                }

                Command.OKAY -> {
                    logger.i(TAG, "OKAY received for stream ${StreamId.name(message.arg0)}")
                    openAckSignal.complete(true)
                }

                Command.OPEN -> {
                    // 手机端不应主动发 OPEN；记录但忽略
                    logger.w(TAG, "unexpected OPEN from accessory")
                }

                Command.CLSE -> {
                    val reason = message.payload.toString(Charsets.UTF_8)
                    logger.i(TAG, "CLSE from accessory: stream=${StreamId.name(message.arg0)} reason='$reason'")
                    listener.onClosed(reason)
                }

                else -> logger.d(TAG, "message ${message.commandName} ignored")
            }
        }

        override fun onError(cause: Throwable) {
            if (closed.get()) return
            fail("链路错误: ${cause.message}", cause)
        }

        override fun onClosed(reason: String) {
            // 抢占终态：`CLSE` 之后紧接着的读写失败不该再变成一条"链路错误"
            if (closed.getAndSet(true)) return
            listener.onClosed(reason)
            stopInternal()
        }
    }

    // ------------------------------------------------------------------ 音频消费（10.13）

    /**
     * 音频消费循环。
     *
     * 与视频循环的三处刻意差异：
     *  1. **不因 Surface 消失而停会话，但会停喂帧**（10.22）—— 链路照常跑（否则手机进
     *     空闲看门狗会释放 fd，见 [suspendRendering] 的说明），只是把声音暂停；
     *     ⚠️ 停喂是**必须的**：`AudioPlayer.feed` 里是 `write(WRITE_BLOCKING)`，
     *     `AudioTrack` 暂停后不再消费缓冲，继续喂会把这条线程永久阻塞。
     *  2. **不做关键帧等待** —— 音频帧没有依赖关系，随时可开始；
     *  3. **不重建解码器** —— AAC 没有"尺寸随旋转变化"的问题。
     */
    private fun audioLoop() {
        logger.i(TAG, "音频消费线程启动（A/V 补偿 ${audioDelayMs}ms，请求音频=$audioEnabled）")
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val frame = audioQueue.take(POLL_MS)
            if (frame == null) {
                if (audioQueue.isClosed) break
                continue
            }

            if (frame.isConfig) {
                handleAudioConfig(frame)
                continue
            }

            // 后台暂停中：只消费、不喂（AudioTrack 暂停时不能写，否则线程会被钉死）
            if (audioPaused.get()) continue

            // 没启用 / 还没 configure 时静默丢弃（手机端正常情况下根本不会发）
            audio?.feed(frame)
        }
    }

    /**
     * 处理音频 CONFIG 帧：配置（或重配）解码器与 `AudioTrack`。
     *
     * ★ CONFIG **永不吞掉**（与视频同一约定）：重连后手机端会重发流头 + CONFIG，
     *   旧解码器已经作废，必须按新到达的 CONFIG 重建 —— 否则重连后就没声音了。
     */
    private fun handleAudioConfig(frame: Frame) {
        if (!audioEnabled) {
            if (audioConfigured.compareAndSet(false, true)) {
                logger.i(TAG, "本次未启用音频（宿主关闭），忽略手机端发来的音频流")
            }
            return
        }
        val header = audioQueue.header ?: connection?.headerOf(StreamId.AUDIO)
        if (header == null) {
            logger.w(TAG, "音频 CONFIG 到达但还没有流头，忽略（正常不应发生）")
            return
        }
        val player = audio ?: AudioPlayer(logger = logger).also { audio = it }
        if (player.start(header, frame.data, audioDelayMs)) {
            audioConfigured.set(true)
            logger.i(TAG, "音频解码器已配置：$header，CONFIG=${frame.data.size}B")
        } else {
            // start() 内部已经把资源收敛掉了，这里只需记账（不要掐会话）
            audioConfigured.set(false)
        }
    }

    // ------------------------------------------------------------------ 解码消费

    private fun consumerLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val frame = videoQueue.take(POLL_MS)
            if (frame == null) {
                if (videoQueue.isClosed) break
                if (rendering) decoder?.drainOutput()
                continue
            }

            // 流健康监测（10.30）：只认**媒体帧**，不认 CONFIG。
            // CONFIG 在重建路径里可能单独出现，但那不代表画面在动；静态画面则有
            // KEY_REPEAT_PREVIOUS_FRAME_AFTER=100ms 兜底送帧，所以"有媒体帧"等价于"流在跑"。
            if (!frame.isConfig) lastVideoFrameAtMs = SystemClock.elapsedRealtime()

            // ★ 换了 Surface 就必须重建解码器（MediaCodec 的 output surface 不能改绑）。
            //   放在消费线程里做而不是在 `surfaceCreated` 回调里做：既保证
            //   "重建 → 喂帧"的顺序，也避免与 handleConfigFrame 并发 configure。
            if (pendingRebuild && rendering && rebuildDecoder()) {
                pendingRebuild = false
            }

            if (frame.isConfig) {
                // ★ CONFIG 帧永不吞掉：即使已有配置也重新 configure（计划 4.4）
                handleConfigFrame(frame)
                continue
            }

            // Surface 不在（切后台）：只丢帧，**不动链路** —— 保持消费，手机侧就不会
            // 因为"宿主零报文"而进空闲看门狗（那样会释放配件 fd，链路就回不来了）。
            if (!rendering) continue

            val d = decoder
            if (d == null) {
                val n = framesSkippedBeforeConfig.incrementAndGet()
                if (!warnedMissingConfig) {
                    warnedMissingConfig = true
                    logger.w(TAG, "尚未收到 CONFIG 帧，先丢弃普通帧（已丢 $n）—— 不能空参数 configure")
                }
                continue
            }

            // ★ 重建解码器后的"等 IDR"门控（10.21）：新解码器没有任何参考帧，
            //   缺 IDR 的 P 帧喂进去只会花屏/报错，所以先挡在门外。
            //   手机端已被 RQID 要求 arm 了同样的门控，这里是**兜底**
            //   （手机端是旧版本、或 RQID 在 USB FIFO 里丢了）。
            if (!resumeGate.shouldEmit(frame.isKeyFrame)) {
                resumeTailDropped.incrementAndGet()
                continue
            }

            d.feed(frame)

            if (!reportedFirstFrame && d.framesDecoded.get() > 0) {
                reportedFirstFrame = true
                logger.i(TAG, "首帧已输出 ${d.size?.let { "${it.first}x${it.second}" }}")
                listener.onFirstFrame()
            }
        }
    }

    /**
     * 解码器出错（与链路错误分开处理）。
     *
     * ★ 确认是 **Surface 失效**时**不结束会话**，改成"丢帧 + 申请重建"：
     *   会话一旦结束，手机端会释放配件 fd，而手机此刻**本来就在配件模式**
     *   （`START_ACCESSORY` 不会重新枚举、系统也不会重新派发 attach）→ 没人再打开
     *   那个 fd → 宿主永远等不到 CNXN。切后台 40s 就能稳定复现这个死锁。
     *   所以这里只把解码器放掉，等 `surfaceCreated` 时用缓存的 CONFIG 重建。
     */
    private fun onDecoderError(t: Throwable) {
        logger.e(TAG, "decoder error: ${t.message}", t)
        if (decoder?.isSurfaceLost == true) {
            suspendRendering()
        }
    }

    // ------------------------------------------------------------------ 渲染的挂起与恢复

    /**
     * Surface 被销毁（切后台 / 屏幕关闭 / Activity 销毁中）→ **停渲染，但保持链路**。
     *
     * 解码器绑定的是 `configure` 那一刻的 Surface，失效后既不能继续渲染也不能改绑，
     * 所以直接放掉；[lastConfig] 留着，Surface 回来时用它重建。
     */
    fun suspendRendering() {
        rendering = false
        decoder?.let {
            it.release()
            logger.i(TAG, "Surface 已销毁：已释放解码器、停止渲染（链路保持）")
        }
        decoder = null
        suspendAudio()
    }

    /** Surface 回来了 → 让消费线程用缓存的 CONFIG 在新 Surface 上重建解码器。 */
    fun resumeRendering() {
        if (rendering) return
        rendering = true
        pendingRebuild = lastConfig != null
        logger.i(
            TAG,
            if (pendingRebuild) "Surface 已恢复：准备用缓存的 CONFIG 重建解码器"
            else "Surface 已恢复（尚未收到 CONFIG，等它到达）",
        )
        resumeAudio()
    }

    // ------------------------------------------------------------------ 音频的后台暂停（10.22）

    /**
     * 切后台 → **暂停声音**（10.22）。
     *
     * ## 为什么跟着 Surface 生命周期走，而不是 `onPause`
     * `surfaceDestroyed` 是"真的没有画面可画了"这个事实的**权威信号**，而且它已经在
     * 驱动 [rendering]；再挂一份 `onPause` 就会出现"有时暂停、有时不暂停"的双状态。
     * 代价是**屏幕熄灭也会静音** —— 这与系统行为一致（ Music 播放器锁屏后通常也停），
     * 且比"锁屏后手机声音继续外放"更符合预期。
     *
     * ⚠️ **顺序很重要**：必须**先置 [audioPaused] 再 `pause()`**。
     *   反过来的话，音频消费线程可能正在 `write()` 的中途看到 track 变暂停，
     *   而那一写会一直阻塞到缓冲排空 —— 而缓冲永远不会排空（已暂停，没人消费）。
     */
    private fun suspendAudio() {
        if (!audioPaused.compareAndSet(false, true)) return
        audio?.pauseForBackground()
    }

    /**
     * 回前台 → **恢复声音**（10.22）。
     *
     * ⚠️ 恢复后**必须重置 A/V 学习状态**：后台那几十秒里手机一直在采集编码，
     *   回前台后新帧的 pts 已经跳到"当前时刻"，而 [AudioPlayer] 的播放头刚重新锚定 ——
     *   若让 [AvSyncGate] 带着旧 offset 继续判，每一帧都会被算成"偏早几十秒"而触发等待。
     *   这里与 [AvSyncGate.reset] 的既有语义一致（播放头恢复前进时也是这么处理的）。
     */
    private fun resumeAudio() {
        if (!audioPaused.compareAndSet(true, false)) return
        val player = audio
        if (player == null || !player.isRunning) {
            // 没有音频可恢复（宿主关了音频，或还没 configure）：只把 A/V 状态清干净
            avSync.reset()
            return
        }
        player.resumeFromBackground()
        avSync.reset()
        lastAudioPlayedNanos = 0L
        audioStallFrames.set(0)
        avSyncDisabled = false
    }

    /**
     * 用缓存的 CONFIG 在当前 Surface 上新建解码器。
     *
     * ⚠️ 只能在消费线程调用（见 [consumerLoop]）。
     * @return 是否真的建好了（Surface 还没回来或没有 CONFIG 时返回 false，调用方继续丢帧）
     */
    private fun rebuildDecoder(): Boolean {
        val config = lastConfig ?: return false
        val size = lastConfigSize ?: return false
        val surface = surfaceProvider() ?: return false

        decoder?.release()
        val d = VideoDecoder(
            surface = surface,
            logger = logger,
            onConfigured = { w, h -> listener.onVideoSize(w, h) },
            onError = ::onDecoderError,
        )
        d.onFrameDecoded = ::onFrameDecoded
        // ★ A/V 同步：渲染门（10.20③）
        d.frameGate = ::avSyncFrame
        if (!d.configure(size.first, size.second, config)) {
            fail("Surface 恢复后 decoder 重新 configure 失败（${size.first}x${size.second}）")
            return false
        }
        decoder = d

        // ★ 10.21：新解码器没有历史参考帧 ⇒ 必须先见 IDR。
        //   本地 arm 门控（挡住缺 IDR 的 P 帧），同时向手机要一个关键帧 ——
        //   手机端会 arm 它自己的门控并 `setParameters(REQUEST_SYNC_FRAME)`，
        //   于是这段黑屏从"最长 3s（等 I 帧周期）"缩到"~1 帧（几十毫秒）"。
        //   ⚠️ 绝不能走 resendStartup 那套（重发流头 + CONFIG）：链路与流头都还有效，
        //   多发一份流头会把宿主解码器搞乱。
        resumeGate.arm()
        resumeTailDropped.set(0)
        val asked = connection?.send(Command.RQID, StreamId.CONTROL, 0) ?: false
        logger.i(
            TAG,
            "解码器已在新 Surface 上重建（${size.first}x${size.second}，csd=${config.size}B）" +
                " —— 已 arm 等 IDR 门控并向手机请求关键帧（RQID 已发出=$asked），" +
                "通常 ~1 帧内恢复画面",
        )
        return true
    }

    /**
     * 回放配速**不在这里做**。
     *
     * 真机实测教训：把配速放在消费线程上完全无效 —— 读线程是全速把文件灌进深度为 4 的
     * 有界队列，4316 帧里丢掉 4310 帧（连关键帧一起丢），解码器随即报错。
     * 配速只能做在**字节进入管线的地方**，即 `FileByteChannel` 按帧 PTS 节流。
     */

    private fun handleConfigFrame(frame: Frame) {
        val header = videoQueue.header ?: connection?.headerOf(StreamId.VIDEO)
        if (header == null) {
            logger.w(TAG, "CONFIG 帧到达但尚无流头，忽略（正常不应发生）")
            return
        }
        // ★ 先留底：Surface 重建时只能靠它重新 configure（output surface 不能改绑）
        lastConfig = frame.data
        lastConfigSize = header.width to header.height

        // Surface 不在（切后台）：只缓存，不算失败 —— 回来时用它重建解码器
        if (!rendering) {
            logger.i(TAG, "Surface 不在，先缓存 CONFIG ${frame.data.size}B，等 Surface 回来再建解码器")
            pendingRebuild = true
            return
        }

        // ★ 等 Surface 就绪：回放是磁盘速度，CONFIG 可能比 surfaceCreated 先到。
        //   SurfaceView 的回调通常几十毫秒内就到，这里给一个短的等待窗口，
        //   超时才算真失败（避免白白报错打断一次回放）。
        val surface = awaitSurface(SURFACE_WAIT_MS)
        if (surface == null) {
            // 等待期间被挂起（切后台）不算失败 —— 缓存的 CONFIG 已经留好了
            if (!rendering) {
                logger.i(TAG, "等待 Surface 期间被挂起，改为等 Surface 回来再建解码器")
                pendingRebuild = true
                return
            }
            fail("Surface 在 ${SURFACE_WAIT_MS}ms 内仍未就绪，无法配置解码器")
            return
        }

        decoder?.release()
        val d = VideoDecoder(
            surface = surface,
            logger = logger,
            onConfigured = { w, h -> listener.onVideoSize(w, h) },
            onError = ::onDecoderError,
        )
        // 端到端延迟采样点：每解出一帧就记一次（计划 4.10）
        d.onFrameDecoded = ::onFrameDecoded
        // ★ A/V 同步：渲染门（10.20③）
        d.frameGate = ::avSyncFrame
        val ok = d.configure(header.width, header.height, frame.data)
        if (!ok) {
            fail(
                "解码器 configure 失败（尺寸 ${header.width}x${header.height}，" +
                    "CONFIG ${frame.data.size}B）"
            )
            return
        }
        decoder = d
        logger.i(
            TAG,
            "video config: ${header.codecId} ${header.width}x${header.height} " +
                "csd=${frame.data.size}B"
        )
    }

    // ------------------------------------------------------------------ 生命周期

    /** 在 [timeoutMs] 内轮询等待 Surface 就绪。 */
    private fun awaitSurface(timeoutMs: Long): Surface? {
        surfaceProvider()?.let { return it }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline && running.get() && !closed.get()) {
            try {
                Thread.sleep(50)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            surfaceProvider()?.let { return it }
        }
        return null
    }

    /**
     * 握手期的版本核对（10.39）：`CNXN.arg0` = 协议版本，`arg1` = 对方 `versionCode`。
     *
     * **只提示、不中断**：版本不同不等于不能用，绝大多数差异只是少个新功能；
     * 硬拒绝会让"用旧包复现老问题"这种调试手段直接失效。判定边界在
     * `:protocol/aoa/AppVersions`（有单测），这里只负责说人话。
     *
     * ⚠️ 提示走 [Listener.onStatus]（副行），而副行很快会被"OPEN 已发送…"覆盖 ——
     * 所以**日志才是这条信息的持久载体**，排查时 grep `两端版本` / `旧包`。
     */
    private fun checkPeerVersions(peerProto: Int, peerApp: Int) {
        if (peerProto != Protocol.VERSION) {
            // 配件端回的是 minOf(对方, 自己)，正常情况下不会不等；真不等说明有人改过常量
            logger.w(TAG, "协议版本不同：host v${Protocol.VERSION} / peer v$peerProto（按较低版本继续）")
        }
        peerAppVersion = peerApp
        when (AppVersions.compat(appVersion, peerApp)) {
            AppVersions.Compat.SAME ->
                logger.i(TAG, "两端 App 版本一致（$appVersion）")

            // ---- 允许：宿主不旧于配件端，只是可能少用几个新功能 ----
            AppVersions.Compat.PEER_LEGACY -> {
                logger.w(
                    TAG,
                    "配件端未上报版本（旧包）⇒ 依赖新协议的功能可能静默不生效（如像素模式）",
                )
                listener.onStatus("配件端是旧包（未上报版本），部分新功能可能不生效")
            }

            AppVersions.Compat.PEER_OLDER -> {
                logger.i(TAG, "配件端较旧（$peerApp < 宿主 $appVersion）⇒ 向下兼容，继续")
                listener.onStatus("配件端 v$peerApp 较旧（宿主 v$appVersion），个别新功能可能不生效")
            }

            // ---- 拒绝：宿主比配件端旧 ----
            AppVersions.Compat.PEER_NEWER -> {
                logger.e(
                    TAG,
                    "宿主版本过低：host $appVersion < peer $peerApp ⇒ 拒绝继续" +
                        "（新配件端可能发本端解析不了的报文）",
                )
                // ★ 不在读线程里直接 fail()：fail 会关连接，从读回调里关自己可能自锁。
                //   只置标志，由 sessionFlow 在 latch 之后统一收口。
                versionBlocked = true
            }
        }
    }

    private fun fail(message: String, cause: Throwable? = null) {
        // ★ 抢占终态，而不是"检查后再收尾"。
        //
        //   一条连接上**多个线程**都可能撞到同一个故障：读线程的 `readFully` 超时、
        //   消费线程在等同一条流头、写线程的下一次 `bulkTransfer`。它们几乎同时调用
        //   `fail()`，如果只写 `if (closed.get()) return`，两边都能通过检查，
        //   于是同一个原因被上报两遍 —— 真机 6ms 内两条「链路错误」+ 两次自愈
        //   （2026-10-20 13:48:27.456 / .462）。`getAndSet` 让只有第一个到这里的人继续。
        if (closed.getAndSet(true)) return
        logger.e(TAG, message, cause)
        listener.onError(message, cause)
        stopInternal()
    }

    /**
     * 对端"人不在"：配件模式切成功了，但 CNXN/OPEN 发出去后**一个字节都没回**。
     *
     * 与 [fail] 的区别是本质的：这不是链路故障，USB 枚举和 AOA 切换都成功了，
     * 只是**没有软件在对端接管这条管道**。常见三种原因都指向同一个动作
     * （装 / 重新打开配套 App）：
     *
     * 1. 没装 `com.uav.accessory`；
     * 2. 装了但被 `adb force-stop` 过 —— 之后系统不再派发配件 intent；
     * 3. 首次插线时把"是否打开该 App 处理 USB 配件"点了否。
     *
     * 所以走专用回调而不是 [fail]：让上层显示可操作的引导（含下载入口），
     * 而不是"错误 + 等自动重连"——后者在缺 App 时永远不会成功。
     */
    private fun peerSilent(message: String) {
        // 与 [fail] 同样的抢占：这条也是终态，后来的第二个"没人应答"不该再报一遍
        if (closed.getAndSet(true)) return
        logger.e(TAG, message)
        listener.onPeerAppMissing()
        stopInternal()
    }

    fun close() {
        if (closed.getAndSet(true)) return
        stopInternal()
    }

    /**
     * 收尾。**每一步都必须独立兜住异常**（10.33）。
     *
     * 原来是一串裸调用：`MediaCodec.release()` 抛一次（例如解码器已进入 error 状态、
     * 或已被停止渲染那条路径释放过），后面的 `connection.close()` 就**永远不执行** ——
     * USB 连接的 fd 就这么漏掉。而 `/dev/usb_accessory` 是独占的，漏一个就再也
     * 连不回配件模式（10.8 踩过同样的坑，代价是"只能拔插"）。
     *
     * 现在每一步各自 `runCatching`，最后一步 `connection.close()` 无论如何都会跑到。
     */
    private fun stopInternal() {
        // ★ 只跑一次：`fail()` / `peerSilent()` 已经收过尾之后，上层还会再 `close()` 一次，
        //   重复释放不仅日志骗人（两行 host session stopped），`connection.close()` 也会被调两遍。
        if (!stopped.compareAndSet(false, true)) {
            logger.d(TAG, "收尾已执行过，跳过重复释放")
            return
        }
        // ★ 收尾 = 会话终结 ⇒ 此后一律不再派发回调。
        //
        //   以前 [closed] 只在 `close()` 里置位，而 `fail()` / `Connection.onClosed` 收尾时不动它：
        //   一条连接同时有读线程和写线程，读失败先 `fail()`，紧接着写线程也失败 ⇒
        //   `onError` 再进来一次，同一个原因被上报两遍、上层打两条「链路错误」、
        //   连自愈也跟着触发两次「快路径失败 → 强制完整握手」（2026-10-20 真机：.241 / .244）。
        //
        //   放在这里而不是 `fail()` 里：`fail` / `peerSilent` / `Connection.onClosed` / `close`
        //   四条路径都会走到收尾，只在收尾处统一关门才不会漏。
        //   ⚠️ 顺序仍然正确 —— 三个回调都是**先** `listener.onXxx()`、**后** `stopInternal()`。
        closed.set(true)
        running.set(false)
        quiet("videoQueue.close") { videoQueue.close() }
        quiet("audioQueue.close") { audioQueue.close() }
        quiet("decoder.release") { decoder?.release() }
        decoder = null
        quiet("audio(解码).release") { audio?.release() }
        audio = null
        // ★ 排在最关键的释放之前，保证它一定被执行
        consumerThread?.interrupt()
        audioThread?.interrupt()
        // ★ 握手协程靠 cancel 退出：它在 withTimeoutOrNull 里挂起，不占线程
        sessionJob?.cancel()
        sessionJob = null
        quiet("connection.close") { connection?.close() }
        consumerThread = null
        audioThread = null
        logger.i(TAG, "host session stopped")
    }

    /** 收尾专用：单步失败只记一条 W，绝不中断后续释放。 */
    private inline fun quiet(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.w(TAG, "收尾时 $what 失败（忽略）: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ 端到端延迟（计划 4.10）

    /**
     * 用 CNXN 的这一次往返标定两端时钟。
     *
     * 手机在 CNXN payload 的第 8~16 字节带回它**发出该报文那一刻**的单调时钟读数，
     * 配合本机的发送 / 接收时刻即可算出偏移（原理见 [ClockSync]）。
     *
     * 拿不到读数时**宁可不标定** —— 没有数据好过报一个偏移未知的假数字。
     */
    private fun estimateClockOffset(recvNanos: Long, payload: ByteArray) {
        val sentNanos = cnxnSentNanos
        if (sentNanos < 0L) {
            logger.w(TAG, "未记录 CNXN 发出时刻，跳过时钟标定")
            return
        }
        if (payload.size < CNXN_PAYLOAD_WITH_CLOCK) {
            logger.w(
                TAG,
                "CNXN payload 无时钟读数（${payload.size}B < ${CNXN_PAYLOAD_WITH_CLOCK}B）" +
                    "—— 对端可能是旧版本，本轮不测端到端延迟"
            )
            return
        }
        val remoteNanos = Bytes.getLongLE(payload, 8)
        if (remoteNanos <= 0L) {
            logger.w(TAG, "对端时钟读数为 $remoteNanos，跳过标定")
            return
        }
        val off = ClockSync.estimate(sentNanos, recvNanos, remoteNanos)
        clockOffset = off
        logger.i(
            TAG,
            "时钟已标定: $off —— 延迟测量不确定度 ±${off.uncertaintyNanos / 1_000_000}ms"
        )
    }

    /**
     * 每解出一帧记一次端到端延迟。
     *
     * `延迟 = 本机提交合成的时刻 − 手机采集该帧的时刻`
     *
     * 手机侧的采集时刻就是帧的 PTS（MediaCodec 在 surface 输入模式下把帧进入编码器的
     * 时间写进 pts，时基同 `System.nanoTime()`），经 [ClockOffset] 换算到本机时钟后
     * 才能与本地时刻相减。
     *
     * 未标定时直接跳过 —— 不产生任何样本，[latencyLine] 会如实说明原因。
     */
    /**
     * 渲染门（跑在解码线程里，每帧一次）：把"帧的采集时刻"与"音频已播到的时刻"交给
     * [avSync] 决策，按结果丢帧 / 等待 / 照常渲染。
     *
     * ## 什么时候**放弃**同步（返回 true = 一切照旧）
     * - 没有音频（`audioEnabled=false` 或还没起播）→ 没有音频时钟可依据；
     * - 时钟没标定（拿不到 `clockOffset`，无法把帧 pts 换算到宿主时钟）；
     * - **音频播放头连续不前进**（上游没数据 / AudioTrack 异常）—— 这种情况下每一帧都会
     *   被判成"晚几十毫秒以上"，不关掉就会把视频**全丢光**（画面直接黑）。这是最危险的边界，
     *   所以单独计数兜底，并只告警一次。
     */
    private fun avSyncFrame(ptsUs: Long, atNanos: Long): Boolean {
        val player = audio ?: return true
        if (!audioEnabled) return true
        val off = clockOffset ?: return true
        if (ptsUs <= 0L) return true
        // ⚠️ 标定不可靠时**不能拿它做同步决策**：帧 pts 换算到宿主时钟本身就带着
        //   ±1.4s 的不确定度（CNXN 撞上手机端启动会话时实测），此时"偏移"是噪声，
        //   拿噪声去纠正偏移只会疯狂丢帧/等待。宁可不同步。
        if (off.uncertaintyNanos > CLOCK_TRUSTWORTHY_NANOS) return true

        val played = player.playedUntilNanos() ?: return true
        val last = lastAudioPlayedNanos
        if (played <= last) {
            if (audioStallFrames.incrementAndGet() > AUDIO_STALL_LIMIT_FRAMES) {
                if (!avSyncDisabled) {
                    avSyncDisabled = true
                    logger.w(
                        TAG,
                        "音频播放头连续 ${AUDIO_STALL_LIMIT_FRAMES} 帧不前进 —— 暂时关闭 A/V 同步" +
                            "（否则每一帧都会被判为偏晚而丢弃，画面会黑）",
                    )
                }
                return true
            }
        } else {
            if (avSyncDisabled) {
                logger.i(TAG, "音频播放头恢复前进，重新启用 A/V 同步")
                avSyncDisabled = false
                avSync.reset()
            }
            audioStallFrames.set(0)
            lastAudioPlayedNanos = played
        }
        if (avSyncDisabled) return true

        // 帧 pts（手机时钟）→ 宿主时钟
        val frameLocalNanos = off.toLocalNanos(ptsUs * 1_000L)
        val decision = avSync.decide(frameLocalNanos, played)
        if (!decision.render) return false
        if (decision.waitNanos > 0) {
            // 有界等待：跑在解码线程里，必须睡得短（门控内部已封顶 40ms）
            runCatching { Thread.sleep(decision.waitNanos / 1_000_000L) }
                .onFailure { /* 被中断就算了 */ }
        }
        return true
    }

    private fun onFrameDecoded(ptsUs: Long, atNanos: Long) {
        val off = clockOffset ?: return
        if (ptsUs <= 0L) return
        val capturedLocalNanos = off.toLocalNanos(ptsUs * 1_000L)
        val d = atNanos - capturedLocalNanos
        latency.add(d)

        // 分离"新内容帧"：pts 与上一帧的间隔若明显小于 repeat 周期，说明中间没有停顿，
        // 这一帧承载的是新内容 —— 它的延迟才代表用户真正的观感。
        val gapUs = ptsUs - lastDecodedPtsUs
        if (lastDecodedPtsUs > 0L && gapUs in 1 until FRESH_GAP_US) {
            latencyFresh.add(d)
        }
        lastDecodedPtsUs = ptsUs
    }

    /** 延迟那一行的文本；把"没有数据"和"测得 0"区分开，避免误读。 */
    private fun latencyLine(): String {
        if (replay) return "delay=(回放，非实时链路)"
        val off = clockOffset ?: return "delay=(未标定：对端未带时钟读数)"
        return "${latency.snapshot()} | ${latencyFresh.snapshot("fresh")} " +
            "skew=±${off.uncertaintyNanos / 1_000_000}ms"
    }

    fun stats(): String =
        "phone=${if (phoneWidth > 0) "${phoneWidth}x$phoneHeight" else "-"} " +
            "maxSize=$negotiatedMaxSize bitrate=${negotiatedBitRate / 1000}k" +
            (if (negotiatedDisplayArea.isEmpty()) "" else "(显示区 $negotiatedDisplayArea)") +
            " | ${connection?.stats?.snapshot() ?: "-"} | " +
            "queue=$videoQueue | ${decoder?.stats() ?: "no decoder"} | avsync=${avSync.stats()} | " +
            "resumeTailDropped=${resumeTailDropped.get()} | audioPaused=$audioPaused | " +
            "audioQueue=$audioQueue | ${audio?.stats() ?: "no audio"} | ${latencyLine()}"

    /**
     * **界面用**的紧凑统计：几行短句，而不是日志那一整行。
     *
     * 日志（[stats]）要的是"什么都别漏"，界面要的是"一眼看懂"—— 两者分开，
     * 面板上的统计卡片用这个，logcat 与压测仍读 [stats]。
     *
     * 形状（最多 4 行，**刻意控制在 300dp 侧栏不折行**）：
     * ```
     * 编码 456x992 · 1:1 @1920x987
     * 帧 544 · 丢帧 0 · 队列 0/4
     * 延迟 p50 64ms · skew ±2ms
     * 音频 48k/2ch · 电平 -12.3dBFS
     * ```
     */
    fun statsCompact(): String {
        if (replay) return "回放模式（非实时链路）"
        val sb = StringBuilder()

        // 1) 编码尺寸与画质口径（手机屏尺寸不在这儿重复 —— CNXN 那行已经有）
        sb.append("编码 ").append(decoder?.size?.let { "${it.first}x${it.second}" } ?: "-")
            .append(" · ")
            .append(if (negotiatedDisplayArea.isEmpty()) "高清(面板长边)" else "1:1 @$negotiatedDisplayArea")
            .append(" · ").append(negotiatedBitRate / 1000).append("kbps")

        // 2) 帧 / 丢帧 / 队列
        sb.append('\n').append("帧 ").append(decoder?.framesDecoded?.get() ?: 0L)
            .append(" · 丢帧 ").append(videoQueue.droppedTotal)
            .append(" · 队列 ").append(videoQueue.size).append('/').append(videoQueue.capacity)

        // 3) 延迟。
        //    ⚠️ 标定本身可能很糟：CNXN 交换若撞上"手机端正在启动会话"（实测等 2.8s），
        //    时钟偏移的不确定度就有 ±1.4s —— 这时 p50 是个**假数**，宁可如实说"不可靠"，
        //    也不要让人以为 1.4s 是真延迟（真值只能用 skew 小的会话量，见 10.6 口径）。
        sb.append('\n').append("延迟 ")
        val off = clockOffset
        when {
            off == null -> sb.append("未标定")
            off.uncertaintyNanos > CLOCK_TRUSTWORTHY_NANOS ->
                sb.append("标定不可靠（skew ±").append(off.uncertaintyNanos / 1_000_000).append("ms）")
            else -> sb.append("p50 ").append(ms(latencyFresh.percentileNanos(0.50)))
                .append(" · skew ±").append(off.uncertaintyNanos / 1_000_000).append("ms")
        }

        // 4) 音频（电平是判断"内录真在录"的唯一可靠读数）
        sb.append('\n').append("音频 ")
        val a = audio
        when {
            a == null -> sb.append(if (audioEnabled) "未起（等待中）" else "未启用")
            // 后台暂停时电平是"暂停前最后一帧"的残留值，照报会让人以为还在放声
            audioPaused.get() -> sb.append("已暂停（宿主在后台）")
            else -> sb.append(a.configuredSampleRate / 1000).append("k/").append(a.configuredChannels)
                .append("ch · 电平 ").append(String.format(Locale.US, "%.1f", a.lastRms)).append("dBFS")
        }
        return sb.toString()
    }

    private fun ms(nanos: Long): String = "${nanos / 1_000_000}ms"

    companion object {
        const val TAG = "UAV-Host"

        /** 握手各阶段等待上限（计划 4.6：OPEN 应答 5s）。 */
        const val TIMEOUT_MS = 5_000L

        /**
         * 时钟标定的"可信"阈值（不确定度）。
         *
         * CNXN 交换的往返时间就是偏移估计的不确定度来源 —— 实测撞上"手机端正在启动会话"时
         * 往返可达 2.8s（skew ±1.4s），此时延迟读数是假数。超过这个阈值就**不显示 p50**，
         * 只显示"标定不可靠"，免得把 1.4s 当成真延迟（真值见 docs 10.6 口径：fresh p50 64ms）。
         */
        const val CLOCK_TRUSTWORTHY_NANOS = 200_000_000L

        /**
         * 音频播放头连续多少帧不前进就临时关闭 A/V 同步（30fps 下约 2 秒）。
         *
         * 音频一停，视频每一帧都会被判成"偏晚"⇒ 全丢 ⇒ 画面黑。这是必须有的兜底。
         */
        const val AUDIO_STALL_LIMIT_FRAMES = 60

        /** 消费线程轮询间隔。 */
        const val POLL_MS = 500L

        /** 等待 Surface 就绪的上限（回放场景下 CONFIG 可能比 surfaceCreated 先到）。 */
        const val SURFACE_WAIT_MS = 3_000L

        /** 带时钟读数的 CNXN payload 长度：4B width + 4B height + 8B 单调时钟（计划 4.10）。 */
        const val CNXN_PAYLOAD_WITH_CLOCK = 16

        /**
         * 判定"新内容帧"的 pts 间隔上界（微秒）。
         *
         * `repeat-previous-frame-after = 100ms` 是重复帧的周期，所以间隔明显小于它的
         * 才算连续新内容。取 50ms 以同时容纳 30fps(33ms) 与 60fps(16ms) 两种刷新率，
         * 又不至于把 100ms 的重复帧算进来。
         */
        const val FRESH_GAP_US = 50_000L

        /** 音频队列深度。AAC 帧约 21ms 一个，深度 8 ≈ 170ms 的抖动吸收。 */
        const val AUDIO_QUEUE_DEPTH = 8

        /**
         * 默认 A/V 粗补偿（毫秒）。
         *
         * 取值依据：音频管线比视频**少一段**（不经 SurfaceFlinger 合成、无 vsync 等待），
         * 所以音频天然领先。实测视频 fresh 口径 p95 ≈ 150ms（10.6），扣掉音频自身
         * 管线（采集 ~21ms + 编码 + USB + 解码 + 播放缓冲）后，差值落在几十毫秒量级 ——
         * 取 60ms 作为起点。
         *
         * ⚠️ **这是估算的起点，不是测出来的值**：真正的同步需要对照测量
         * （做法见 docs/实施计划.md 10.13 末尾）。用 `--ei audio_delay_ms N` 现场调。
         */
        const val DEFAULT_AUDIO_DELAY_MS = 60
    }
}
