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
 * 虚拟屏 + H.264 编码（计划 3.7~3.10）。
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

    @Volatile
    var outputWidth = 0
        private set

    @Volatile
    var outputHeight = 0
        private set

    /**
     * 启动采集与编码。
     *
     * @param srcWidth/srcHeight 手机真实屏幕尺寸
     * @param options 宿主下发的参数（`max_size` / `video_bit_rate` / `max_fps`）
     * @return 实际编码输出尺寸 `intArrayOf(w, h)`
     */
    fun start(srcWidth: Int, srcHeight: Int, options: ParsedOpen): IntArray {
        val maxSize = options.maxSize ?: Scale.longSide(srcWidth, srcHeight)
        val scaled = Scale.scaledSize(srcWidth, srcHeight, maxSize)
        outputWidth = scaled[0]
        outputHeight = scaled[1]

        logger.i(
            TAG,
            "virtualDisplay ${srcWidth}x$srcHeight -> scaled ${outputWidth}x$outputHeight " +
                "(max_size=$maxSize, bitrate=${options.videoBitRate}, max_fps=${options.maxFps})"
        )

        val format = MediaFormat.createVideoFormat(MIME_TYPE, outputWidth, outputHeight).apply {
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

        // dpi 传错会导致画面比例异常 -> 用 densityDpi 并在日志里校验缩放比（计划 3 风险）
        val densityDpi = context.resources.displayMetrics.densityDpi
        // 注意：API 34 起 createVirtualDisplay 标注为 @Nullable，必须显式判空
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

        running.set(true)
        outputThread = Thread({ outputLoop(encoder) }, "uav-encoder-out").apply {
            isDaemon = true
            start()
        }
        return scaled
    }

    private fun outputLoop(encoder: MediaCodec) {
        // ★ 流头必须最先发出（该流首条 WRTE 的 payload），写队列 FIFO 保证顺序
        sink.onStreamHeader(
            StreamHeader(CodecId.AVC1, outputWidth, outputHeight)
        )

        while (running.get() && !Thread.currentThread().isInterrupted) {
            val index = try {
                encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            } catch (t: Throwable) {
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
        framesEncoded.incrementAndGet()
        sink.onFrame(flags, data)
    }

    /**
     * 从 `INFO_OUTPUT_FORMAT_CHANGED` 的 `csd-0`/`csd-1` 拼出 CONFIG 帧 payload（计划 3.10）。
     *
     * 宿主侧会把这个 payload **整体作为 `csd-0`** 注入解码器。
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
        logger.i(TAG, "CONFIG 帧产出：${parts.size} 段拼接，共 $total 字节")
        sink.onConfig(payload)
    }

    fun stop() {
        running.set(false)
        outputThread?.interrupt()
        outputThread = null

        runCatching { virtualDisplay?.release() }
            .onFailure { logger.w(TAG, "virtualDisplay.release 失败: ${it.message}") }
        virtualDisplay = null

        runCatching { inputSurface?.release() }
        inputSurface = null

        codec?.let { c ->
            runCatching { c.stop() }.onFailure { logger.w(TAG, "codec.stop 失败: ${it.message}") }
            runCatching { c.release() }.onFailure { logger.w(TAG, "codec.release 失败: ${it.message}") }
        }
        codec = null
        logger.i(TAG, "encoder stopped (${stats()})")
    }

    fun stats(): String =
        "out=${outputWidth}x$outputHeight frames=${framesEncoded.get()} " +
            "key=${keyFrames.get()} config=${configFrames.get()}"

    companion object {
        const val TAG = "UAV-Video"

        const val MIME_TYPE = "video/avc"

        const val DISPLAY_NAME = "uav-virtual-display"

        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /** `repeat-previous-frame-after`（微秒）。 */
        private const val KEY_REPEAT_PREVIOUS_FRAME_AFTER = "repeat-previous-frame-after"

        /** 已核实存在的限帧键（surface 输入模式下由编码器丢源帧）。 */
        private const val KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder"

        private val CSD_KEYS = arrayOf("csd-0", "csd-1")
    }
}
