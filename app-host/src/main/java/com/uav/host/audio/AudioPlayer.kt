package com.uav.host.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import com.uav.host.AndroidLogger
import com.uav.protocol.AudioLevel
import com.uav.protocol.AudioSpec
import com.uav.protocol.Frame
import com.uav.protocol.Logger
import com.uav.protocol.StreamHeader
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 宿主端：AAC 解码 + `AudioTrack` 播放（计划 10.13）。
 *
 * ## 为什么单独一条线程、不跟视频共用
 *
 * 视频消费线程在丢帧/重建解码器时会短暂停顿（Surface 切换、等关键帧），
 * 音频**必须在实时节拍上连续输出**，被拖一下就是可听见的咔哒声。
 * 所以音频有自己的队列 + 自己的消费线程，只在 `close()` 时收敛。
 *
 * ## 播放节奏由谁决定
 *
 * 刻意**不做**基于 pts 的自定义配速：`AudioTrack` 的流式写入本身就是实时节拍，
 * `write(..., WRITE_BLOCKING)` 会在缓冲满时阻塞，天然把节奏锁到采样率上。
 * 自己再叠一层 pts 配速只会引入重采样伪影。
 *
 * ## A/V 同步
 *
 * 音频与视频是两条独立管线，端到端延迟不同：视频有"采集→编码→USB→解码→合成"，
 * 音频少一段（无合成/无 vsync），实测视频 p95 约 150ms，所以音频**默认晚 150ms 起播**
 * 去凑齐这个差。这是一个**粗补偿**：只对齐起播时刻，不做长期漂移校正；
 * 精细同步（以及音频时钟主导的配速）留给后续项。
 * 延迟量可用 `--ei audio_delay_ms N` 现场调。
 *
 * ⚠️ 任何音频侧失败都只影响声音，**绝不能让视频会话跟着挂** —— 所以这里的异常
 * 一律收敛成"停用音频 + 记日志"。
 *
 * ## 后台暂停（10.22）
 * 宿主切后台时 [pauseForBackground] 暂停播放、回前台时 [resumeFromBackground] 恢复。
 * ⚠️ **暂停期间调用方必须停止喂帧**（`feed` 内部也有一道兜底）：`AudioTrack` 暂停后不再
 * 消费缓冲，而写出是 `WRITE_BLOCKING` ⇒ 继续喂会把音频消费线程永久阻塞。
 * ⚠️ 恢复时必须**重锚音频时钟**：后台期间手机一直在采集，回前台后帧 pts 已跳到当前时刻，
 * 而播放头还停在暂停处，两者会错开几十秒。
 */
class AudioPlayer(
    private val logger: Logger = AndroidLogger,
    private val volume: Float = 1.0f,
) {

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null

    private val bufferInfo = MediaCodec.BufferInfo()

    private var sampleRate = AudioSpec.SAMPLE_RATE
    private var channels = AudioSpec.CHANNELS

    @Volatile
    private var started = false

    /** 宿主切后台导致的暂停（10.22）；与"没在跑/出错"是两回事，所以单独记。 */
    @Volatile
    private var paused = false

    @Volatile
    var isRunning: Boolean = false
        private set

    /** 还需要等多久才写出第一段 PCM（A/V 粗补偿）。 */
    @Volatile
    private var delayMs = 0

    private var delayApplied = false

    /**
     * `play()` 的时刻（宿主单调时钟）。
     *
     * ★ A/V 同步的**音频时钟基准**（10.20③）：[playedUntilNanos] 要靠它把"播放头帧数"换算成时间。
     */
    @Volatile
    private var playbackStartedAtNanos = 0L

    /**
     * 音频**已经播出**到的时刻（宿主单调时钟域，nanos）。
     *
     * 用 `AudioTrack.getPlaybackHeadPosition()`（已渲染到输出的帧数）而不是"已写入"——
     * 后者含 AudioTrack 的缓冲，会把音频位置算得比实际**靠前**好几帧。
     *
     * @return 还没开始播 / 没有 track / 采样率未知 → `null`（调用方据此放弃 A/V 同步）
     */
    fun playedUntilNanos(): Long? {
        val t = track ?: return null
        val start = playbackStartedAtNanos
        if (start <= 0L) return null
        val sr = sampleRate
        if (sr <= 0) return null
        // 播放头是无符号 32 位帧计数，约 12 小时会绕回；按标准做法屏蔽符号位
        val frames = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrNull() ?: return null
        return start + frames * 1_000_000_000L / sr
    }

    // ---- 统计 ----

    val framesDecoded = AtomicLong()
    val pcmBytesWritten = AtomicLong()
    val inputTimeouts = AtomicLong()

    /** 解码后 PCM 的电平 —— 用来区分"解码器在跑"和"真的听到声音"。 */
    @Volatile
    var maxRmsDbfs = AudioLevel.SILENCE_DBFS
        private set

    @Volatile
    private var lastRmsDbfs = AudioLevel.SILENCE_DBFS

    /** 最近一段 PCM 的电平（dBFS）—— 界面上的"电平"读数用它（内录静默失败时恒为静音值）。 */
    val lastRms: Float get() = lastRmsDbfs

    /** 解码器实际配置的采样率 / 声道数（界面显示用）。 */
    val configuredSampleRate: Int get() = sampleRate
    val configuredChannels: Int get() = channels

    private var scratch = ByteArray(0)

    /**
     * 按流头 + CONFIG 配置解码器与播放器。
     *
     * @param csd0 音频 CONFIG 帧 payload（AAC 的 `AudioSpecificConfig`），整体作 `csd-0`
     * @param delayMs 首段 PCM 写出前的等待（A/V 粗补偿），0 = 立即出声
     * @return false 表示配置失败（会自行收敛，调用方只需继续跑视频）
     */
    fun start(header: StreamHeader, csd0: ByteArray, delayMs: Int = 0): Boolean {
        release()
        return try {
            sampleRate = header.audioSampleRate
            channels = header.audioChannels
            this.delayMs = delayMs.coerceAtLeast(0)
            delayApplied = false

            val fmt = MediaFormat.createAudioFormat(AudioSpec.MIME, sampleRate, channels).apply {
                setByteBuffer(CSD_KEY, ByteBuffer.wrap(csd0))
            }
            val c = MediaCodec.createDecoderByType(AudioSpec.MIME)
            c.configure(fmt, null, null, 0)
            c.start()
            codec = c

            val channelMask =
                if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                throw IllegalStateException("AudioTrack.getMinBufferSize 返回 $minBuf")
            }
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                // 视频侧队列本身有界，这里给足缓冲吸收抖动
                .setBufferSizeInBytes(maxOf(minBuf, AudioSpec.PCM_FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            runCatching { t.setVolume(volume) }
            t.play()
            track = t
            // 音频时钟起点：必须在 play() 之后取，否则会把建轨的时间算进去
            playbackStartedAtNanos = System.nanoTime()

            isRunning = true
            started = true
            logger.i(
                TAG,
                "音频输出已就绪：${sampleRate}Hz/${channels}ch，CONFIG=${csd0.size}B，" +
                    "A/V 补偿 ${this.delayMs}ms，播放缓冲 $minBuf B"
            )
            true
        } catch (t: Throwable) {
            logger.e(TAG, "音频输出初始化失败，本次没有声音: ${t.message}", t)
            release()
            false
        }
    }

    /** 喂一帧 AAC（在音频消费线程调用；内部会阻塞以维持实时节拍）。 */
    fun feed(frame: Frame) {
        // ⚠️ 兜底：后台暂停期间**绝不能喂**。`AudioTrack` 暂停后不再消费缓冲，
        //   而下面是 `write(WRITE_BLOCKING)` —— 缓冲写满会把这线程永久钉死。
        //   调用方（HostSession.audioLoop）本来就该拦，这里再挡一层，防止将来漏改。
        if (paused) return
        val c = codec ?: return
        val t = track ?: return

        val index = try {
            c.dequeueInputBuffer(INPUT_TIMEOUT_US)
        } catch (ex: Throwable) {
            logger.w(TAG, "音频 dequeueInputBuffer 失败: ${ex.message}")
            return
        }
        if (index < 0) {
            inputTimeouts.incrementAndGet()
            return
        }
        try {
            val buf = c.getInputBuffer(index)
            if (buf == null) {
                runCatching { c.queueInputBuffer(index, 0, 0, 0, 0) }
                return
            }
            buf.clear()
            buf.put(frame.data)
            c.queueInputBuffer(index, 0, frame.data.size, frame.pts, 0)
        } catch (ex: Throwable) {
            logger.w(TAG, "音频入队失败: ${ex.message}")
            runCatching { c.releaseOutputBuffer(index, false) }
            return
        }

        drain(c, t)
    }

    private fun drain(c: MediaCodec, t: AudioTrack) {
        while (true) {
            val out = try {
                c.dequeueOutputBuffer(bufferInfo, 0L)
            } catch (ex: Throwable) {
                logger.w(TAG, "音频 dequeueOutputBuffer 失败: ${ex.message}")
                return
            }

            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    logger.i(TAG, "音频解码器输出格式: ${c.outputFormat}")

                out == MediaCodec.INFO_TRY_AGAIN_LATER -> return

                out < 0 -> Unit

                else -> {
                    try {
                        val size = bufferInfo.size
                        val b = if (size > 0) c.getOutputBuffer(out) else null
                        if (b != null) {
                            b.position(bufferInfo.offset)
                            b.limit(bufferInfo.offset + size)
                            sampleLevel(b, size)
                            applyStartupDelay()
                            // ★ 阻塞写：缓冲满时在这里等，节奏就被 AudioTrack 锁到采样率上
                            val n = t.write(b, size, AudioTrack.WRITE_BLOCKING)
                            if (n > 0) {
                                pcmBytesWritten.addAndGet(n.toLong())
                                framesDecoded.incrementAndGet()
                            } else if (paused) {
                                // ★ 暂停瞬间撞上正在进行的 write：会返回 0/负数，这是**预期**的。
                                //   `pauseForBackground` 已经先置 `audioPaused` 再 `pause()`，
                                //   所以这里绝不能阻塞重试（缓冲永远排不空），直接放掉即可。
                                //   不记日志 —— 每次切后台都会命中一条 warn，纯噪音。
                            } else {
                                logger.w(TAG, "AudioTrack.write 返回 $n")
                            }
                        }
                    } catch (ex: Throwable) {
                        logger.w(TAG, "写出 PCM 失败: ${ex.message}")
                    } finally {
                        runCatching { c.releaseOutputBuffer(out, false) }
                    }
                }
            }
        }
    }

    /** A/V 粗补偿：只在第一段 PCM 前等一次。 */
    private fun applyStartupDelay() {
        if (delayApplied) return
        delayApplied = true
        if (delayMs <= 0) return
        try {
            Thread.sleep(delayMs.toLong())
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 抽样计算解码后 PCM 的电平。
     *
     * 只每 [LEVEL_SAMPLE_EVERY] 段算一次：电平是给排错用的，没必要每帧都算
     * （每段都算等于给音频线程白加一次全缓冲遍历）。
     */
    private fun sampleLevel(b: ByteBuffer, size: Int) {
        if (framesDecoded.get() % LEVEL_SAMPLE_EVERY != 0L) return
        try {
            if (scratch.size < size) scratch = ByteArray(size)
            // ⚠️ 不要 `position(0)`：`b` 的 position 已经是 `bufferInfo.offset`，
            //    归零会让读取窗口整体前移（offset > 0 时读到的是上一帧的尾巴）。
            val dup = b.duplicate()
            dup.get(scratch, 0, size)
            val rms = AudioLevel.rmsDbfs(scratch, 0, size)
            lastRmsDbfs = rms
            if (rms > maxRmsDbfs) maxRmsDbfs = rms
        } catch (t: Throwable) {
            logger.w(TAG, "电平抽样失败: ${t.message}")
        }
    }

    /**
     * 宿主切到后台：**暂停播放**（10.22），但**不释放**解码器与 `AudioTrack`。
     *
     * ## 为什么是 pause 而不是 release
     * - `release` 掉 `AudioTrack` 再重建要走一遍 `AudioTrack.Builder`，回到前台会有一小段
     *   空档，而且重建失败的风险比 `pause()` 高得多；
     * - `MediaCodec`（AAC）本来就没有"输出 surface 不能改绑"那类问题，配置还在就能继续解。
     *
     * ⚠️ **调用方必须同时停止喂帧**（见 `HostSession.audioLoop`）：`AudioTrack` 暂停后
     *   就不再消费缓冲，而 `feed()` 里是 `write(..., WRITE_BLOCKING)` ——
     *   一旦继续喂，缓冲写满后**这条线程会永久阻塞**，把音频消费线程钉死。
     *   （这正是"置 null 不等于关闭"之外的另一类坑：暂停了却不管上游。）
     */
    fun pauseForBackground() {
        val t = track ?: return
        if (!isRunning) return
        // ⚠️ **必须先置 `paused` 再 `pause()`**：反过来的话，音频消费线程可能正好在
        //   `write()` 的中途撞上 track 变暂停 → 返回 0 → 走进 warn 分支刷一条无意义的日志，
        //   而这在每次切后台时都必现。
        paused = true
        runCatching {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
        }.onFailure { logger.w(TAG, "AudioTrack.pause 失败: ${it.message}") }
        logger.i(TAG, "宿主切后台：音频已暂停（解码器与 AudioTrack 保留）")
    }

    /**
     * 回前台：**丢掉暂停期间积压的缓冲，从"现在"重新起播**（10.22）。
     *
     * ## 为什么必须 flush
     * 暂停期间上游已经在继续解码/送数据，`AudioTrack` 里可能压着几十毫秒的**旧** PCM；
     * 直接 `play()` 会先把这批过期声音播出去（能听出来是一小段"回声/延迟"）。
     * `flush()` 把缓冲丢掉、`playbackHeadPosition` 归零，正好和下面重设时钟起点配套。
     *
     * ## 为什么必须重设 [playbackStartedAtNanos]
     * A/V 同步把"音频已播到哪一刻"算成 `playbackStartedAtNanos + 播放头帧数/采样率`。
     * 后台这几十秒里手机**一直在采集编码**，回前台后新帧的 pts 已经跳到当前时刻，
     * 而音频播放头还停在暂停处 ⇒ 两者错开几十秒，门控会把每一帧都判成"早几十秒"。
     * 所以恢复时必须**重新锚定**音频时钟，并让调用方重置 A/V 学习状态。
     */
    fun resumeFromBackground() {
        val t = track ?: return
        if (!paused) return
        runCatching {
            if (t.playState == AudioTrack.PLAYSTATE_PAUSED) {
                t.flush()
                t.play()
            } else if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                // 少见：暂停期间被别处恢复了，flush 仍要执行以清掉积压
                t.flush()
            } else {
                // STOPPED：flush 之后无法直接 play，退回重建整条输出
                logger.w(TAG, "AudioTrack 处于 STOPPED，无法原地恢复")
                return
            }
        }.onFailure {
            logger.w(TAG, "AudioTrack 恢复失败: ${it.message}")
            return
        }
        // 时钟重新锚定：播放头已归零，起点取"此刻"
        playbackStartedAtNanos = System.nanoTime()
        // A/V 粗补偿要重新生效（否则恢复后视频会领先音频一个固定量）
        delayApplied = false
        paused = false
        logger.i(TAG, "宿主回前台：音频已恢复（丢弃暂停期间积压的缓冲，时钟重新锚定）")
    }

    /** 是否处于"后台暂停"状态。 */
    val isPaused: Boolean get() = paused

    fun release() {
        isRunning = false
        started = false
        paused = false
        codec?.let { c ->
            runCatching { c.stop() }.onFailure { logger.w(TAG, "audio codec.stop 失败: ${it.message}") }
            runCatching { c.release() }.onFailure { logger.w(TAG, "audio codec.release 失败: ${it.message}") }
        }
        codec = null
        track?.let { t ->
            runCatching { if (t.playState != AudioTrack.PLAYSTATE_STOPPED) t.stop() }
            runCatching { t.release() }
        }
        track = null
    }

    fun stats(): String =
        "audio ${sampleRate}Hz/${channels}ch frames=${framesDecoded.get()} " +
            "pcm=${pcmBytesWritten.get() / 1024}KB 最大rms=${f1(maxRmsDbfs)}dBFS " +
            "最近rms=${f1(lastRmsDbfs)}dBFS 输入超时=${inputTimeouts.get()}"

    private fun f1(v: Float): String = String.format(Locale.US, "%.1f", v)

    companion object {
        const val TAG = "UAV-Audio"

        private const val INPUT_TIMEOUT_US = 10_000L

        private const val CSD_KEY = "csd-0"

        private const val LEVEL_SAMPLE_EVERY = 50L
    }
}
