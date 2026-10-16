package com.uav.accessory.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import com.uav.accessory.AndroidLogger
import com.uav.protocol.AudioLevel
import com.uav.protocol.AudioSpec
import com.uav.protocol.Flags
import com.uav.protocol.Logger
import com.uav.protocol.StreamHeader
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 音频内录 → AAC 编码（计划 10.13）。
 *
 * ## 为什么用「内录」而不是麦克风
 *
 * Android 10（API 29）起提供 `AudioPlaybackCaptureConfiguration`：把**系统正在播放的
 * 声音**（而不是环境声）复制一份给本应用。投屏要的是"手机里响什么，宿主就响什么"，
 * 所以内录才是对的语义 —— 麦克风会把环境噪音也录进去，投屏时等于开了个窃听器。
 *
 * 内录的约束（官方文档 + 实测，全部无法绕过）：
 *  - 必须有 `RECORD_AUDIO` 权限；
 *  - 必须有**用户批准的 `MediaProjection` 令牌** —— 好在录屏本来就要了；
 *  - 只能捕获 `USAGE_MEDIA` / `USAGE_GAME` / `USAGE_UNKNOWN`；
 *  - 被捕获的应用必须没有声明 `allowAudioPlaybackCapture="false"`，
 *    且其 `targetSdk` ≥ 29（否则要先自行 opt-in）。
 *    → **银行、DRM 类应用会静默退出捕获**，表现为"录到的是静音"，不是 bug。
 *
 * ## 结构
 *
 * 单线程闭环：`AudioRecord.read` → 直接入队给 AAC 编码器 → 排空输出 → 交给 [Sink]。
 * 刻意**不加中间队列** —— 音频对延迟敏感，多一级队列就多一级抖动。
 *
 * 复用与重连：编码器与 `AudioRecord` 一旦起来就**不随链路断开而销毁**
 * （与视频侧的虚拟屏同样的道理），新会话接入时只调用 [resendStartup] 重发
 * 流头 + CONFIG。
 *
 * ⚠️ **启动失败绝不能掐掉整个会话**：手机可能没给录音权限、ROM 可能阉割了内录。
 * 那时应当只有视频、没有声音，而不是黑屏。[start] 因此返回布尔值而不抛异常。
 */
class AudioPipeline(
    private val projection: MediaProjection,
    private val sink: Sink,
    private val logger: Logger = AndroidLogger,
    private val bitRate: Int = AudioSpec.DEFAULT_BIT_RATE,
    private val sampleRate: Int = AudioSpec.SAMPLE_RATE,
    private val channels: Int = AudioSpec.CHANNELS,
) {

    /**
     * 音频专用回调名（`onAudio*`）。
     *
     * ★ 刻意**不**和 `VirtualDisplayEncoder.Sink` 用同名方法：两者签名一致
     * （都要发流头/CONFIG/帧），若同名，同一个实现类无法区分"这一帧该发到 VIDEO 还是 AUDIO"，
     * Kotlin 也会把两个接口的方法合并成一个实现，等于静默把音频发到视频流。
     */
    interface Sink {
        fun onAudioStreamHeader(header: StreamHeader)
        fun onAudioConfig(payload: ByteArray)
        fun onAudioFrame(ptsAndFlags: Long, data: ByteArray)
        fun onAudioError(t: Throwable)
    }

    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    private val running = AtomicBoolean(false)

    private val bufferInfo = MediaCodec.BufferInfo()

    /** 实际申请的录音缓冲字节数（排错时要看它是否被欠载）。 */
    @Volatile
    var bufferBytes = 0
        private set

    // ---- 统计（排错与验证用；也是"内录是否真的录到声音"的证据）----

    val framesEncoded = AtomicLong()
    val configFrames = AtomicLong()
    val pcmBytes = AtomicLong()

    @Volatile
    var framesSilent = 0L
        private set

    @Volatile
    var framesAudible = 0L
        private set

    /** 整段会话里见过的最大 RMS 电平 —— 判定"是不是一直静音"的关键读数。 */
    @Volatile
    var maxRmsDbfs = AudioLevel.SILENCE_DBFS
        private set

    @Volatile
    var maxPeakDbfs = AudioLevel.SILENCE_DBFS
        private set

    /** 最近一帧的电平，用于周期性日志。 */
    @Volatile
    private var lastRmsDbfs = AudioLevel.SILENCE_DBFS

    /** 最近一次产出的 CONFIG（AAC 的 `AudioSpecificConfig`），新会话接入时重发。 */
    @Volatile
    private var lastConfig: ByteArray? = null

    val isRunning: Boolean get() = running.get()

    // ------------------------------------------------------------------ 生命周期

    /**
     * 启动内录与编码。
     *
     * @return false 表示**本机拿不到内录**（Android 9 及以下 / 权限被拒 / ROM 不支持），
     *         调用方应当只把音频这一路降级掉，**不要动视频链路**。
     */
    fun start(): Boolean {
        if (running.get()) return true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            logger.w(
                TAG,
                "本机 Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）" +
                    "不支持内录 —— 需要 Android 10 / API 29 起。本次只投屏、不出声音"
            )
            return false
        }

        return try {
            val rec = buildRecord()
            rec.startRecording()
            val enc = buildEncoder()

            record = rec
            codec = enc
            running.set(true)
            thread = Thread({ encodeLoop(enc, rec) }, "uav-audio").apply {
                isDaemon = true
                start()
            }
            logger.i(
                TAG,
                "内录已启动：${sampleRate}Hz/${channels}ch，AAC-LC ${bitRate / 1000}kbps，" +
                    "录音缓冲 ${bufferBytes}B（约 ${bufferBytes * 1000 / AudioSpec.PCM_BYTES_PER_SECOND}ms）"
            )
            true
        } catch (t: Throwable) {
            // ★ 到这里不抛：音频起不来只是"没声音"，不该把视频也带走
            logger.e(TAG, "内录启动失败，本次只投屏不出声音: ${t.javaClass.simpleName}: ${t.message}", t)
            releaseQuietly()
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false) && thread == null) return
        // 先让两个阻塞点返回，再 join —— 否则 join 会白等到超时
        runCatching { codec?.stop() }
        runCatching { record?.stop() }
        thread?.interrupt()
        try {
            thread?.join(STOP_JOIN_MS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
        releaseQuietly()
        logger.i(TAG, "内录已停止（${stats()}）")
    }

    /**
     * 新会话（重连）接入：重发流头 + CONFIG。
     *
     * ⚠️ 音频**不做**旋转那套；但重连后必须重发 —— 宿主的解码器是按连接新建的，
     * 不给它流头与 `AudioSpecificConfig`，它就永远 configure 不出来。
     */
    fun resendStartup(): Boolean {
        sink.onAudioStreamHeader(StreamHeader.audio(sampleRate, channels))
        val cfg = lastConfig
        if (cfg == null) {
            logger.w(TAG, "新会话接入但还没有音频 CONFIG，宿主暂时无法解码音频")
            return false
        }
        sink.onAudioConfig(cfg)
        logger.i(TAG, "新会话接入：已重发音频流头与 CONFIG(${cfg.size}B)")
        return true
    }

    private fun releaseQuietly() {
        codec?.let { c ->
            runCatching { c.stop() }.onFailure { logger.w(TAG, "audio codec.stop 失败: ${it.message}") }
            runCatching { c.release() }.onFailure { logger.w(TAG, "audio codec.release 失败: ${it.message}") }
        }
        codec = null
        record?.let { r ->
            runCatching {
                if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
            }
            runCatching { r.release() }
        }
        record = null
    }

    // ------------------------------------------------------------------ 构建

    /**
     * 建 `AudioRecord`：**内录**配置 + 16 bit PCM。
     *
     * 注意与麦克风录音的两处差别：
     *  - 用 `setAudioPlaybackCaptureConfig`，**不能**再 `setAudioSource`（互斥）；
     *  - usage 只列 `MEDIA`/`GAME`/`UNKNOWN` —— 这三个是框架允许捕获的全部。
     */
    private fun buildRecord(): AudioRecord {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val channelMask =
            if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            throw IllegalStateException("AudioRecord.getMinBufferSize 返回 $minBuf（参数不被本机支持）")
        }
        // 至少留两帧的余量：刚好一帧的话，读走一刻就开始欠载，会周期性丢样本
        bufferBytes = maxOf(minBuf, AudioSpec.PCM_FRAME_BYTES * 2)

        val rec = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .build()

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            throw IllegalStateException(
                "AudioRecord 未初始化（state=${rec.state}）—— 常见原因：" +
                    "缺少 RECORD_AUDIO 权限，或系统/厂商禁用了内录"
            )
        }
        return rec
    }

    private fun buildEncoder(): MediaCodec {
        val fmt = MediaFormat.createAudioFormat(AudioSpec.MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, AudioSpec.AAC_PROFILE_LC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            // 一次输入就是 1024 样本，声明出来免得编码器内部再切
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AudioSpec.PCM_FRAME_BYTES)
        }
        val c = MediaCodec.createEncoderByType(AudioSpec.MIME)
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        return c
    }

    // ------------------------------------------------------------------ 采集/编码闭环

    private fun encodeLoop(enc: MediaCodec, rec: AudioRecord) {
        // ★ 流头必须最先发出（该流首条 WRTE 的 payload）
        sink.onAudioStreamHeader(StreamHeader.audio(sampleRate, channels))

        // 音频线程给到"紧急音频"优先级：被视频编码抢占会导致周期性欠载（听起来是咔哒声）
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }

        val pcm = ByteArray(AudioSpec.PCM_FRAME_BYTES)

        while (running.get() && !Thread.currentThread().isInterrupted) {
            val n = try {
                rec.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
            } catch (t: Throwable) {
                if (running.get()) {
                    logger.e(TAG, "AudioRecord.read 失败: ${t.message}", t)
                    sink.onAudioError(t)
                }
                return
            }

            if (n < 0) {
                // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE / ERROR_DEAD_OBJECT
                val err = n
                logger.w(TAG, "AudioRecord.read 返回错误码 $err")
                sink.onAudioError(IllegalStateException("AudioRecord.read=$err"))
                return
            }
            if (n == 0) continue

            accountLevel(pcm, n)
            feed(enc, pcm, n)
            drain(enc)
        }

        // 收尾：把编码器里剩下的一帧挤出来（否则尾巴上会丢约 20ms 声音）
        runCatching { drain(enc, flush = true) }
    }

    /**
     * 记录电平。
     *
     * ★ 这是**判断内录有没有真的录到声音的唯一客观手段**：
     *   失败最典型的样子是"帧数一直在涨、宿主也在播放，但录到的是全 0"
     *   （被捕获的应用退出捕获、或 ROM 阉割了内录），
     *   只有电平能把它和正常情况分开。
     */
    private fun accountLevel(pcm: ByteArray, n: Int) {
        val rms = AudioLevel.rmsDbfs(pcm, 0, n)
        val peak = AudioLevel.peakDbfs(pcm, 0, n)
        lastRmsDbfs = rms
        if (rms > maxRmsDbfs) maxRmsDbfs = rms
        if (peak > maxPeakDbfs) maxPeakDbfs = peak
        pcmBytes.addAndGet(n.toLong())
        if (rms <= SILENT_DBFS) framesSilent++ else framesAudible++
    }

    private fun feed(enc: MediaCodec, pcm: ByteArray, n: Int) {
        val index = try {
            enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        } catch (t: Throwable) {
            if (running.get()) logger.w(TAG, "音频 dequeueInputBuffer 失败: ${t.message}")
            return
        }
        if (index < 0) {
            inputTimeouts++
            return
        }
        val buf = enc.getInputBuffer(index)
        if (buf == null) {
            runCatching { enc.queueInputBuffer(index, 0, 0, 0, 0) }
            return
        }
        buf.clear()
        buf.put(pcm, 0, n)
        // pts 用与视频同一时基（System.nanoTime），便于宿主做 A/V 相关的统计
        val ptsUs = System.nanoTime() / 1000L
        try {
            enc.queueInputBuffer(index, 0, n, ptsUs, 0)
        } catch (t: Throwable) {
            if (running.get()) logger.w(TAG, "音频 queueInputBuffer 失败: ${t.message}")
        }
    }

    private fun drain(enc: MediaCodec, flush: Boolean = false) {
        while (true) {
            val index = try {
                enc.dequeueOutputBuffer(bufferInfo, if (flush) DEQUEUE_TIMEOUT_US else 0L)
            } catch (t: Throwable) {
                if (running.get() && !flush) logger.w(TAG, "音频 dequeueOutputBuffer 失败: ${t.message}")
                return
            }

            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> emitConfig(enc.outputFormat)

                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return

                index < 0 -> Unit

                else -> {
                    try {
                        val isCodecConfig =
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val size = bufferInfo.size
                        // ★ 必须过滤 size == 0：空帧的 payload 恰好 12 字节，
                        //   会被宿主当成 12B 流头解析（见 FrameCodec 的说明）。
                        if (size > 0 && !isCodecConfig) {
                            val b = enc.getOutputBuffer(index)
                            if (b != null) {
                                val data = ByteArray(size)
                                b.position(bufferInfo.offset)
                                b.limit(bufferInfo.offset + size)
                                b.get(data)
                                emit(data)
                            }
                        }
                    } catch (t: Throwable) {
                        logger.w(TAG, "读取音频输出缓冲失败: ${t.message}")
                    } finally {
                        runCatching { enc.releaseOutputBuffer(index, false) }
                    }
                }
            }
        }
    }

    private fun emit(data: ByteArray) {
        val pts = bufferInfo.presentationTimeUs.coerceIn(0L, Flags.PTS_MASK)
        // 音频没有关键帧概念，只带 pts
        val flags = Flags.pack(pts)
        val n = framesEncoded.incrementAndGet()
        // 约每 2 秒一条心跳：真机验证"到底有没有声音"时直接读这行的电平就够了
        if (n == 1L || n % 100 == 0L) {
            logger.i(
                TAG,
                "音频帧 #$n（${data.size}B，累计 ${pcmBytes.get() / 1024}KB PCM，" +
                    "rms=${f1(lastRmsDbfs)}dBFS 峰值=${f1(maxPeakDbfs)}dBFS，" +
                    "静音帧=$framesSilent 有声帧=$framesAudible）"
            )
        }
        sink.onAudioFrame(flags, data)
    }

    /**
     * 从 `INFO_OUTPUT_FORMAT_CHANGED` 取 `csd-0` 作为 CONFIG。
     *
     * AAC 只有一段 `AudioSpecificConfig`（不像 H.264 有 SPS+PPS 两段），
     * 宿主会把它整体作为 `csd-0` 注入解码器。
     */
    private fun emitConfig(format: MediaFormat) {
        val buf = format.getByteBuffer(CSD_KEY)
        if (buf == null) {
            logger.w(TAG, "AAC 编码器没给出 csd-0 —— 宿主将无法 configure 音频解码器")
            return
        }
        val dup = buf.duplicate()
        dup.rewind()
        val n = dup.remaining()
        if (n <= 0) {
            logger.w(TAG, "AAC csd-0 长度为 $n")
            return
        }
        val arr = ByteArray(n)
        dup.get(arr)
        lastConfig = arr
        configFrames.incrementAndGet()
        logger.i(TAG, "音频 CONFIG 产出：$n 字节（AAC AudioSpecificConfig）")
        sink.onAudioConfig(arr)
    }

    fun stats(): String =
        "rec=${sampleRate}Hz/${channels}ch frames=${framesEncoded.get()} config=${configFrames.get()} " +
            "pcm=${pcmBytes.get() / 1024}KB 最大rms=${f1(maxRmsDbfs)}dBFS 最大峰值=${f1(maxPeakDbfs)}dBFS " +
            "静音帧=$framesSilent 有声帧=$framesAudible 输入超时=$inputTimeouts"

    private fun f1(v: Float): String = String.format(Locale.US, "%.1f", v)

    private var inputTimeouts = 0L

    companion object {
        const val TAG = "UAV-Audio"

        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /** 判定"这一帧是静音"的阈值。低于它有声音也听不见，判静音更利于排错。 */
        private const val SILENT_DBFS = -80f

        private const val STOP_JOIN_MS = 500L

        private const val CSD_KEY = "csd-0"
    }
}
