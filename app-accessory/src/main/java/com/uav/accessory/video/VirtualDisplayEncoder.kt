package com.uav.accessory.video

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Surface
import com.uav.accessory.AndroidLogger
import com.uav.accessory.DisplaySize
import com.uav.protocol.CodecId
import com.uav.protocol.Flags
import com.uav.protocol.Logger
import com.uav.protocol.Options
import com.uav.protocol.ParsedOpen
import com.uav.protocol.Scale
import com.uav.protocol.StreamHeader
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 虚拟屏 + H.264 编码（计划 3.7~3.10，含 P0 的「旋转三件套」）。
 *
 * 采用**surface 输入模式**：`MediaProjection.createVirtualDisplay(...)` 直接写到
 * `MediaCodec.createInputSurface()` 拿到的 Surface，编码器自己去 GPU 取帧，**零拷贝**。
 *
 * 冻结的编码参数（计划 4.5 / 3.8）：
 * | 参数 | 值 | 理由 |
 * | --- | --- | --- |
 * | `KEY_I_FRAME_INTERVAL` | **3 s** | 与丢帧策略联动：丢 P 帧的花屏时长 = I 帧间隔 |
 * | `KEY_REPEAT_PREVIOUS_FRAME_AFTER` | 100 ms | 静止画面也持续出帧，链路不空转 |
 * | `KEY_MAX_FPS_TO_ENCODER` | `max_fps` | surface 输入下由编码器丢源帧限帧 |
 *
 * **CONFIG 帧的来源**（计划 3.10，冻结）：只在 `INFO_OUTPUT_FORMAT_CHANGED` 时，
 * 从新的 format 取 `csd-0`/`csd-1` **拼接**后产出。旋转后重发 CONFIG 复用同一条路径。
 * 带 `BUFFER_FLAG_CODEC_CONFIG` 的输出缓冲会被跳过，避免重复下发。
 *
 * ## 屏幕旋转（P0「旋转三件套」）
 *
 * 分辨率变了就必须重建编码器（`MediaCodec` 的尺寸在 `configure` 时固定、不可改），
 * 但**虚拟屏不能重建** —— `MediaProjection` 令牌只允许 `createVirtualDisplay` 一次，
 * 重建意味着重新弹一次录屏授权，对用户是明显倒退。
 *
 * 所以本类把两者的生命周期**拆开**：
 *
 * | 对象 | 生命周期 |
 * | --- | --- |
 * | [virtualDisplay] | 全程只创建一次，旋转时用 `resize()` + `setSurface()` **复用** |
 * | [codec] / [inputSurface] | 可随旋转反复重建 |
 *
 * [rebuildForSizeChange] 执行的就是这三件套：**停编码器 → 建新编码器 → `resize` + 换面**。
 * 新编码器的输出线程会在开头重发 12B 流头，宿主机据此得知新尺寸并重配解码器
 * （见 `:protocol` 的 `Connection.dispatchWrite`）。
 */
class VirtualDisplayEncoder(
    private val context: Context,
    private val projection: MediaProjection,
    private val sink: Sink,
    private val logger: Logger = AndroidLogger,
) {

    interface Sink {
        /** 流头（**必须**先于任何一帧发出，因为它是该流首条 `WRTE` 的 payload）。 */
        fun onStreamHeader(header: StreamHeader)

        /** CONFIG 帧 payload（SPS+PPS 拼接）。 */
        fun onConfig(payload: ByteArray)

        /** 一帧码流。[ptsAndFlags] 见 [Flags]。 */
        fun onFrame(ptsAndFlags: Long, data: ByteArray)

        fun onError(t: Throwable)
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputThread: Thread? = null

    private val running = AtomicBoolean(false)

    private val bufferInfo = MediaCodec.BufferInfo()

    val framesEncoded = AtomicLong()
    val configFrames = AtomicLong()
    val keyFrames = AtomicLong()

    /** 最近一次产出的 CONFIG payload —— 新会话接入时要重发（宿主的解码器靠它 configure）。 */
    @Volatile
    private var lastConfig: ByteArray? = null

    val isRunning: Boolean get() = running.get()

    @Volatile
    var outputWidth = 0
        private set

    @Volatile
    var outputHeight = 0
        private set

    // ---------------------------------------------------------------- 旋转支持

    /** 宿主下发的参数（重建编码器时复用，不必再问一次）。 */
    @Volatile
    private var negotiatedOptions: ParsedOpen? = null

    @Volatile
    private var maxSize = 0

    @Volatile
    private var srcWidth = 0

    @Volatile
    private var srcHeight = 0

    private var densityDpi = 0

    /**
     * 旋转待处理标志。
     *
     * ★ 计划要求「`RotationWatcher` 回调只置标志，在安全点跳出内层处理」——
     * 因为 `DisplayListener` 回调跑在主线程，而重建编码器要停线程、换 Surface，
     * 绝不能在那里做。真正的重建在 [rotatorLoop] 里做。
     */
    private val rotationPending = AtomicBoolean(false)

    private var rotatorThread: Thread? = null

    @Volatile
    private var stopped = false

    /** 生命周期互斥：`stop()` 可能来自主线程，而重建来自 [rotatorThread]。 */
    private val lifecycleLock = Any()

    /** 尺寸变更次数（= 实际重建编码器的次数）。 */
    val rebuilds = AtomicLong()

    /**
     * 启动采集与编码。
     *
     * @param srcWidth/srcHeight 手机真实屏幕尺寸
     * @param options 宿主下发的参数（`max_size` / `video_bit_rate` / `max_fps`）
     * @return 实际编码输出尺寸 `intArrayOf(w, h)`
     */
    fun start(srcWidth: Int, srcHeight: Int, options: ParsedOpen): IntArray {
        this.stopped = false
        this.negotiatedOptions = options
        this.srcWidth = srcWidth
        this.srcHeight = srcHeight
        // ★ 1:1 像素映射（10.15）：优先按宿主**视频显示区**算编码尺寸，
        //   而不是按宿主长边拍一个值 —— 后者在宿主横屏时会多编 3 倍多的像素。
        //   用 encodeMaxSize 而不是直接用 options.maxSize，是为了旋转后能重算（见 rebuildForSizeChange）。
        this.maxSize = options.encodeMaxSize(srcWidth, srcHeight)
            ?: Scale.longSide(srcWidth, srcHeight)
        this.densityDpi = context.resources.displayMetrics.densityDpi

        val scaled = Scale.scaledSize(srcWidth, srcHeight, maxSize)
        outputWidth = scaled[0]
        outputHeight = scaled[1]

        logger.i(
            TAG,
            "virtualDisplay ${srcWidth}x$srcHeight -> scaled ${outputWidth}x$outputHeight " +
                "(max_size=$maxSize, ${describeSizeSource(options)}, " +
                "bitrate=${options.videoBitRate}, max_fps=${options.maxFps})"
        )

        val surface = buildEncoder(outputWidth, outputHeight, options)

        startOutputLoop()
        return scaled
    }

    /**
     * 创建虚拟屏并接管编码器的输入面。**只在 [start] 里调用一次。**
     *
     * 注意：API 34 起 `createVirtualDisplay` 标注为 `@Nullable`，必须显式判空。
     */
    private fun ensureVirtualDisplay(surface: Surface) {
        if (virtualDisplay != null) return
        val vd = projection.createVirtualDisplay(
            DISPLAY_NAME,
            outputWidth,
            outputHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        ) ?: throw IllegalStateException("createVirtualDisplay 返回 null（投影 token 可能已失效）")
        virtualDisplay = vd
        logger.i(TAG, "encoder started, densityDpi=$densityDpi displayId=${vd.display?.displayId}")
    }

    /**
     * 建编码器并启动，返回它的输入 Surface。
     *
     * 抽出来是为了让 [start] 与旋转重建 [rebuildForSizeChange] 共用同一套参数 ——
     * 两处各写一遍迟早会漏掉某个 `KEY_*`。
     */
    private fun buildEncoder(width: Int, height: Int, options: ParsedOpen): Surface {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            // surface 输入模式必须显式指定这个 color format
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, options.videoBitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, options.maxFps)
            // ★ 3s：与 4.7 的丢帧策略联动（花屏最长时间 = I 帧间隔）
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Options.I_FRAME_INTERVAL_SECONDS)
            // 静止画面也持续出帧，避免链路空转
            setLong(KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            // 限帧：surface 输入模式下由编码器丢源帧实现（API 29+ 才有 setFloat）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { setFloat(KEY_MAX_FPS_TO_ENCODER, options.maxFps.toFloat()) }
                    .onFailure { logger.w(TAG, "设置 $KEY_MAX_FPS_TO_ENCODER 失败: ${it.message}") }
            }
        }

        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()

        codec = encoder
        inputSurface = surface
        ensureVirtualDisplay(surface)
        return surface
    }

    private fun startOutputLoop() {
        val encoder = codec ?: return
        running.set(true)
        outputThread = Thread({ outputLoop(encoder) }, "uav-encoder-out").apply {
            isDaemon = true
            start()
        }
        if (rotatorThread == null) {
            rotatorThread = Thread({ rotatorLoop() }, "uav-rotator").apply {
                isDaemon = true
                start()
            }
        }
    }

    // ---------------------------------------------------------------- 旋转三件套

    /** `DisplayListener` 调用入口。**只置标志**，不做任何重活。 */
    fun onDisplayChanged() {
        rotationPending.set(true)
    }

    /**
     * 旋转处理循环：轮询标志，在安全点执行重建。
     *
     * 为什么用独立线程而不是直接在回调里做：重建要停编码器线程、`join` 等它退出、
     * 再换 Surface —— 这些都不能在 `DisplayListener` 的主线程回调里做。
     */
    private fun rotatorLoop() {
        while (!stopped && !Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(ROTATION_POLL_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (!rotationPending.compareAndSet(true, false)) continue
            if (stopped || !running.get()) continue

            // 拿到的是"当前方向下的真实尺寸"；旋转后它会变成新方向的值
            val now = runCatching { DisplaySize.of(context) }.getOrNull() ?: continue
            if (now.x == srcWidth && now.y == srcHeight) continue // 与显示无关的事件，忽略

            rebuildForSizeChange(now.x, now.y)
        }
    }

    /**
     * 尺寸变更时重建编码器，并复用虚拟屏。
     *
     * 顺序是有讲究的：
     * 1. **先停旧编码器**（并 join 它的输出线程）—— 否则旧线程会继续在新会话上发旧尺寸的帧；
     * 2. **再建新编码器**并启动，让它的输入面先就绪；
     * 3. **最后**才动虚拟屏（`resize` + `setSurface`），避免出现"面还没好就被切换"的空窗。
     *
     * 新输出线程进入 [outputLoop] 的第一件事就是重发流头，宿主据此得知新尺寸。
     *
     * @return true 表示确实重建了（尺寸有变化且成功）
     */
    private fun rebuildForSizeChange(newSrcW: Int, newSrcH: Int): Boolean {
        val opts = negotiatedOptions ?: return false

        synchronized(lifecycleLock) {
            if (stopped) return false
            val vd = virtualDisplay ?: return false

            // ★ 旋转后必须**重算** max_size（10.15）：1:1 的目标尺寸由"画面比例在宿主显示区里
            //   适配后有多大"决定，横竖屏切换时它变化很大（实测 1024 -> 1920）。
            //   沿用旋转前的值会让宿主不得不放大画面（糊），或者继续多编几倍像素（浪费）。
            val newMaxSize = opts.encodeMaxSize(newSrcW, newSrcH)
                ?: Scale.longSide(newSrcW, newSrcH)
            val oldMaxSize = maxSize
            maxSize = newMaxSize

            val scaled = Scale.scaledSize(newSrcW, newSrcH, newMaxSize)
            if (scaled[0] == outputWidth && scaled[1] == outputHeight) {
                // 源尺寸变了但缩放后编码尺寸相同（例如变化在 max_size 阈值以内）：
                // 不必重建，但要把源尺寸记下来，否则会每次轮询都重新算一遍
                logger.i(TAG, "源尺寸 ${newSrcW}x$newSrcH 变化但编码尺寸不变，跳过重建")
                srcWidth = newSrcW
                srcHeight = newSrcH
                return false
            }

            logger.i(
                TAG,
                "旋转：源 ${srcWidth}x$srcHeight -> ${newSrcW}x$newSrcH，" +
                    "max_size $oldMaxSize -> $newMaxSize（${describeSizeSource(opts)}），" +
                    "编码 ${outputWidth}x$outputHeight -> ${scaled[0]}x${scaled[1]}"
            )

            // 1) 停旧编码器（保留虚拟屏，旧 Surface 也先留着 —— 见 stopEncoderOnly 的说明）
            val oldSurface = stopEncoderOnly()

            // 2) 建新编码器
            val newSurface = try {
                buildEncoder(scaled[0], scaled[1], opts)
            } catch (t: Throwable) {
                logger.e(TAG, "旋转后重建编码器失败: ${t.message}", t)
                running.set(false)
                runCatching { oldSurface?.release() }
                sink.onError(t)
                return false
            }
            outputWidth = scaled[0]
            outputHeight = scaled[1]
            srcWidth = newSrcW
            srcHeight = newSrcH

            // 3) 换虚拟屏：**先断开、再改尺寸、最后接上新面**。
            //    顺序不能省：直接 resize/setSurface 会让"虚拟屏尺寸"与"面尺寸"在中间态
            //    不匹配；而断开后 resize 再接入，全程没有不匹配的窗口。
            try {
                vd.surface = null
                vd.resize(scaled[0], scaled[1], densityDpi)
                vd.surface = newSurface
            } catch (t: Throwable) {
                logger.e(TAG, "虚拟屏 resize/setSurface 失败: ${t.message}", t)
                runCatching { oldSurface?.release() }
                sink.onError(t)
                return false
            }

            // 4) 虚拟屏已经切到新面，旧面这时才能安全释放
            runCatching { oldSurface?.release() }

            // 5) 新输出线程会在开头重发流头（携带新尺寸）
            startOutputLoop()
            rebuilds.incrementAndGet()
            logger.i(
                TAG,
                "旋转完成：编码 ${outputWidth}x$outputHeight，已重发流头等待宿主重配解码器"
            )
            return true
        }
    }

    /**
     * 停掉编码器与它的输出线程，**保留虚拟屏**。
     *
     * 先 `codec.stop()` 再 `join`：`outputLoop` 此时多半阻塞在 `dequeueOutputBuffer`，
     * `stop()` 会让它抛错返回，从而尽快退出 —— 只靠 `interrupt()` 是打断不了这个原生调用的。
     *
     * @return 旧编码器的输入 Surface —— **由调用方负责释放，且必须等到虚拟屏切走之后**。
     *
     * ⚠️ 实测踩过的坑：在这里直接 `release()` 旧面会让**虚拟屏的输出管线失效**，
     * 表现为"重建后 CONFIG 帧出来了、但一个视频帧都出不来"（CONFIG 是编码器启动时
     * 自己产生的，不需要输入；而视频帧必须由虚拟屏投递）。
     */
    private fun stopEncoderOnly(): Surface? {
        running.set(false)
        val t = outputThread
        outputThread = null

        codec?.let { c ->
            runCatching { c.stop() }.onFailure { logger.w(TAG, "codec.stop 失败: ${it.message}") }
        }
        t?.interrupt()
        try {
            t?.join(ENCODER_JOIN_MS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        codec?.let { c ->
            runCatching { c.release() }.onFailure { logger.w(TAG, "codec.release 失败: ${it.message}") }
        }
        codec = null
        val old = inputSurface
        inputSurface = null
        return old
    }

    private fun outputLoop(encoder: MediaCodec) {
        // ★ 流头必须最先发出（该流首条 WRTE 的 payload），写队列 FIFO 保证顺序。
        //   旋转重建后这里会再执行一次 —— 这正是"把新尺寸告诉宿主"的手段。
        sink.onStreamHeader(
            StreamHeader(CodecId.AVC1, outputWidth, outputHeight)
        )

        while (running.get() && !Thread.currentThread().isInterrupted) {
            val index = try {
                encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            } catch (t: Throwable) {
                // 正常停机遇 stopEncoderOnly 里的 codec.stop()，此时 running 已是 false，静默退出
                if (running.get()) {
                    logger.e(TAG, "dequeueOutputBuffer 失败: ${t.message}", t)
                    sink.onError(t)
                }
                return
            }

            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> emitConfig(encoder.outputFormat)

                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                // 其余负值（含已废弃的 INFO_OUTPUT_BUFFERS_CHANGED = -3）统一忽略
                index < 0 -> Unit

                else -> {
                    try {
                        val isCodecConfig =
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val size = bufferInfo.size
                        if (size > 0 && !isCodecConfig) {
                            val buf = encoder.getOutputBuffer(index)
                            if (buf != null) {
                                val data = ByteArray(size)
                                buf.position(bufferInfo.offset)
                                buf.limit(bufferInfo.offset + size)
                                buf.get(data)
                                emitFrame(data)
                            }
                        }
                    } catch (t: Throwable) {
                        logger.w(TAG, "读取输出缓冲失败: ${t.message}")
                    } finally {
                        runCatching { encoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
        }
    }

    private fun emitFrame(data: ByteArray) {
        val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        if (isKey) keyFrames.incrementAndGet()
        val pts = bufferInfo.presentationTimeUs.coerceIn(0L, Flags.PTS_MASK)
        val flags = Flags.pack(pts, keyFrame = isKey)
        val n = framesEncoded.incrementAndGet()
        // 每 300 帧打一条：排查"旋转后还有没有帧"时，这条日志是唯一直接证据
        // （CONFIG 帧是编码器启动时自己产的，不需要输入，所以它出现**不代表**采到画面）
        if (n == 1L || n % 300 == 0L) {
            logger.i(TAG, "编码帧 #$n（${outputWidth}x$outputHeight, ${data.size}B, key=$isKey）")
        }
        sink.onFrame(flags, data)
    }

    /**
     * 从 `INFO_OUTPUT_FORMAT_CHANGED` 的 `csd-0`/`csd-1` 拼出 CONFIG 帧 payload（计划 3.10）。
     *
     * 宿主侧会把这个 payload **整体作为 `csd-0`** 注入解码器。
     * 旋转重建后编码器格式会变（分辨率在 SPS 里），这里产出的就是新 CONFIG。
     */
    private fun emitConfig(format: MediaFormat) {
        val parts = ArrayList<ByteArray>(2)
        for (key in CSD_KEYS) {
            val buf = format.getByteBuffer(key) ?: continue
            val dup = buf.duplicate()
            dup.rewind()
            val n = dup.remaining()
            if (n <= 0) continue
            val arr = ByteArray(n)
            dup.get(arr)
            parts.add(arr)
        }

        if (parts.isEmpty()) {
            logger.w(TAG, "INFO_OUTPUT_FORMAT_CHANGED 但没有 csd-0/csd-1 —— 无法产出 CONFIG 帧")
            return
        }

        val total = parts.sumOf { it.size }
        val payload = ByteArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, payload, pos, p.size)
            pos += p.size
        }
        configFrames.incrementAndGet()
        lastConfig = payload
        logger.i(TAG, "CONFIG 帧产出：${parts.size} 段拼接，共 $total 字节（${outputWidth}x$outputHeight）")
        sink.onConfig(payload)
    }

    /**
     * 新会话接入时**复用既有编码器**，只把「流头 + 最近一份 CONFIG」重发一遍。
     *
     * ★ 为什么必须这样做，而不能"停掉再重建"：
     *   `MediaProjection` 的令牌**只允许 `createVirtualDisplay` 一次**，
     *   重建会直接失败（Android 会抛 "Don't re-use the resultData..." 一类错误），
     *   而那意味着要重新弹一次录屏授权 —— 对用户是明显的倒退。
     *
     * @return true 表示成功重发；false 表示还没有可用的 CONFIG（宿主将无法 configure 解码器）
     */
    fun resendStartup(): Boolean {
        val cfg = lastConfig
        sink.onStreamHeader(StreamHeader(CodecId.AVC1, outputWidth, outputHeight))
        if (cfg == null) {
            logger.w(TAG, "新会话接入但没有缓存的 CONFIG，宿主解码器将无法 configure")
            return false
        }
        sink.onConfig(cfg)
        logger.i(TAG, "新会话接入：已重发流头 ${outputWidth}x$outputHeight 与 CONFIG(${cfg.size}B)")
        return true
    }

    fun stop() {
        synchronized(lifecycleLock) {
            stopped = true
            rotationPending.set(false)
            val old = stopEncoderOnly()
            runCatching { old?.release() }

            rotatorThread?.interrupt()
            rotatorThread = null

            runCatching { virtualDisplay?.release() }
                .onFailure { logger.w(TAG, "virtualDisplay.release 失败: ${it.message}") }
            virtualDisplay = null
        }
        logger.i(TAG, "encoder stopped (${stats()})")
    }

    fun stats(): String =
        "out=${outputWidth}x$outputHeight frames=${framesEncoded.get()} " +
            "key=${keyFrames.get()} config=${configFrames.get()} rebuilds=${rebuilds.get()}"

    companion object {
        const val TAG = "UAV-Video"

        /**
         * 日志用：这一版尺寸是"按宿主显示区算的 1:1"还是"按宿主长边的旧口径"。
         *
         * 排错时这两种情况的症状完全不同（前者画面本该锐利，后者在宿主横屏时白编几倍像素），
         * 所以每次打尺寸都要带上是哪一种。
         */
        private fun describeSizeSource(options: ParsedOpen): String {
            val w = options.displayWidth
            val h = options.displayHeight
            return if (w != null && h != null) "1:1(宿主显示区 ${w}x$h)" else "旧口径(宿主长边)"
        }

        const val MIME_TYPE = "video/avc"

        const val DISPLAY_NAME = "uav-virtual-display"

        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /** 旋转检查间隔。旋转动画本身约 300ms，200ms 足够及时又不至于空转。 */
        private const val ROTATION_POLL_MS = 200L

        /** 等旧输出线程退出的上限。退出不了也不阻塞太久 —— 后续 release 会强制打断它。 */
        private const val ENCODER_JOIN_MS = 500L

        /** `repeat-previous-frame-after`（微秒）。 */
        private const val KEY_REPEAT_PREVIOUS_FRAME_AFTER = "repeat-previous-frame-after"

        /** 已核实存在的限帧键（surface 输入模式下由编码器丢源帧）。 */
        private const val KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder"

        private val CSD_KEYS = arrayOf("csd-0", "csd-1")
    }
}
