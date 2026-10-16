package com.uav.host.session

import android.view.Surface
import com.uav.host.AndroidLogger
import com.uav.host.video.VideoDecoder
import com.uav.protocol.AdbMessage
import com.uav.protocol.Bytes
import com.uav.protocol.ClockOffset
import com.uav.protocol.ClockSync
import com.uav.protocol.Command
import com.uav.protocol.Connection
import com.uav.protocol.ConnectionListener
import com.uav.protocol.Frame
import com.uav.protocol.FrameQueue
import com.uav.protocol.LatencyStats
import com.uav.protocol.Logger
import com.uav.protocol.Options
import com.uav.protocol.Protocol
import com.uav.protocol.Scale
import com.uav.protocol.StreamId
import com.uav.protocol.Transport
import com.uav.protocol.d
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    }

    private val videoQueue = FrameQueue(StreamId.VIDEO, logger = logger)

    private val peerCnxnLatch = CountDownLatch(1)
    private val openAckLatch = CountDownLatch(1)

    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    private var connection: Connection? = null
    private var consumerThread: Thread? = null
    private var sessionThread: Thread? = null

    @Volatile
    private var decoder: VideoDecoder? = null

    @Volatile
    private var phoneWidth = 0

    @Volatile
    private var phoneHeight = 0

    /**
     * 手机在 `CNXN` 里上报的**屏幕尺寸**。
     *
     * ★ 这是**触摸坐标**该用的空间：HID 触摸屏的逻辑上限（[com.uav.protocol.HidTouchPanel]）
     *   要按它来声明，而不是按编码后的视频尺寸（`max_size` 那个是缩放过的）。
     */
    val peerWidth: Int get() = phoneWidth

    val peerHeight: Int get() = phoneHeight

    private val framesSkippedBeforeConfig = AtomicLong()
    private var warnedMissingConfig = false
    private var reportedFirstFrame = false

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
        private set

    val isReplay: Boolean get() = replay

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val conn = Connection(transport, logger, TAG, connectionListener)
        conn.registerSink(StreamId.VIDEO, videoQueue)
        connection = conn
        conn.start()

        consumerThread = Thread({ consumerLoop() }, "uav-decode").apply {
            isDaemon = true
            start()
        }
        sessionThread = Thread({ sessionFlow() }, "uav-session").apply {
            isDaemon = true
            start()
        }
    }

    // ------------------------------------------------------------------ 握手

    private fun sessionFlow() {
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
            connection?.send(Command.CNXN, Protocol.VERSION, 0, payload)
            listener.onStatus("CNXN 已发送（本机 ${localWidth}x$localHeight），等待手机尺寸…")

            if (!peerCnxnLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                fail("等待手机 CNXN 超时（${TIMEOUT_MS}ms）")
                return
            }

            // ★ max_size 由宿主计算后下发（计划 4.3）：CNXN 交换后两边尺寸都已知
            val maxSize = Scale.computeMaxSize(phoneWidth, phoneHeight, localWidth, localHeight)
            negotiatedMaxSize = maxSize
            val options = Options.defaultOptions(maxSize)

            connection?.send(Command.OPEN, StreamId.CONTROL, 0, Options.buildPayload(options))
            listener.onStatus(
                "OPEN 已发送：手机 ${phoneWidth}x$phoneHeight / 本机 ${localWidth}x$localHeight " +
                    "→ max_size=$maxSize，等待 OKAY…"
            )

            if (!openAckLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                fail("等待 OPEN 应答超时（${TIMEOUT_MS}ms）")
                return
            }
            listener.onStatus("会话已建立，等待视频流…")
        } catch (t: Throwable) {
            fail("会话握手异常: ${t.message}", t)
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
                        logger.i(TAG, "peer CNXN: version=${message.arg0} ${phoneWidth}x$phoneHeight")
                    } else {
                        logger.w(TAG, "CNXN payload 只有 ${message.payload.size} 字节，期望 8")
                    }
                    estimateClockOffset(recvNanos, message.payload)
                    peerCnxnLatch.countDown()
                }

                Command.OKAY -> {
                    logger.i(TAG, "OKAY received for stream ${StreamId.name(message.arg0)}")
                    openAckLatch.countDown()
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
            if (closed.get()) return
            listener.onClosed(reason)
            stopInternal()
        }
    }

    // ------------------------------------------------------------------ 解码消费

    private fun consumerLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val frame = videoQueue.take(POLL_MS)
            if (frame == null) {
                if (videoQueue.isClosed) break
                decoder?.drainOutput()
                continue
            }

            if (frame.isConfig) {
                // ★ CONFIG 帧永不吞掉：即使已有配置也重新 configure（计划 4.4）
                handleConfigFrame(frame)
                continue
            }

            val d = decoder
            if (d == null) {
                val n = framesSkippedBeforeConfig.incrementAndGet()
                if (!warnedMissingConfig) {
                    warnedMissingConfig = true
                    logger.w(TAG, "尚未收到 CONFIG 帧，先丢弃普通帧（已丢 $n）—— 不能空参数 configure")
                }
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
        // ★ 等 Surface 就绪：回放是磁盘速度，CONFIG 可能比 surfaceCreated 先到。
        //   SurfaceView 的回调通常几十毫秒内就到，这里给一个短的等待窗口，
        //   超时才算真失败（避免白白报错打断一次回放）。
        val surface = awaitSurface(SURFACE_WAIT_MS)
        if (surface == null) {
            fail("Surface 在 ${SURFACE_WAIT_MS}ms 内仍未就绪，无法配置解码器")
            return
        }

        decoder?.release()
        val d = VideoDecoder(
            surface = surface,
            logger = logger,
            onConfigured = { w, h -> listener.onVideoSize(w, h) },
            onError = { t -> logger.e(TAG, "decoder error: ${t.message}", t) },
        )
        // 端到端延迟采样点：每解出一帧就记一次（计划 4.10）
        d.onFrameDecoded = ::onFrameDecoded
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

    private fun fail(message: String, cause: Throwable? = null) {
        if (closed.get()) return
        logger.e(TAG, message, cause)
        listener.onError(message, cause)
        stopInternal()
    }

    fun close() {
        if (closed.getAndSet(true)) return
        stopInternal()
    }

    private fun stopInternal() {
        running.set(false)
        videoQueue.close()
        decoder?.release()
        decoder = null
        connection?.close()
        consumerThread?.interrupt()
        sessionThread?.interrupt()
        consumerThread = null
        sessionThread = null
        logger.i(TAG, "host session stopped")
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
            "maxSize=$negotiatedMaxSize | ${connection?.stats?.snapshot() ?: "-"} | " +
            "queue=$videoQueue | ${decoder?.stats() ?: "no decoder"} | ${latencyLine()}"

    companion object {
        const val TAG = "UAV-Host"

        /** 握手各阶段等待上限（计划 4.6：OPEN 应答 5s）。 */
        const val TIMEOUT_MS = 5_000L

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
    }
}
