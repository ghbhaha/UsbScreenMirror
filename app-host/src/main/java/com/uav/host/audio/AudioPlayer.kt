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

    @Volatile
    var isRunning: Boolean = false
        private set

    /** 还需要等多久才写出第一段 PCM（A/V 粗补偿）。 */
    @Volatile
    private var delayMs = 0

    private var delayApplied = false

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

    fun release() {
        isRunning = false
        started = false
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
