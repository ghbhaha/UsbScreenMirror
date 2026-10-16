package com.uav.accessory.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.uav.accessory.AndroidLogger
import com.uav.accessory.DebugTools
import com.uav.protocol.AudioSpec
import com.uav.protocol.Logger
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w
import kotlin.math.PI
import kotlin.math.sin
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 调试用测试音（计划 10.13）—— **探测模式**，只在 debug 包生效。
 *
 * ## 为什么必须有它
 *
 * 验证"内录到底有没有录到声音"需要一个**已知的、可脚本化的 `USAGE_MEDIA` 声源**。
 * 实测本机（HONOR MAA-AN10）上：
 *  - 没有自带音乐播放器，`/sdcard` 里也没有任何音频文件；
 *  - 系统铃声虽然现成，但播放它是 `USAGE_NOTIFICATION_RINGTONE`
 *    —— **不在可捕获的 usage 列表里**（只允许 MEDIA / GAME / UNKNOWN），
 *    拿它做验证会得出"内录坏了"的假结论。
 *
 * 所以这里直接播一段 **`USAGE_MEDIA` 的 1 kHz 正弦**：与真实媒体播放走的是
 * **同一条可捕获路径**（官方文档原文：capturing audio signals played by other apps
 * **"(and yours)"**，即捕获方自己的播放同样在内）。
 *
 * 副作用已确认无害：`ROUTE_FLAG_LOOP_BACK | ROUTE_FLAG_RENDER` 表示
 * **回送给捕获方的同时仍从扬声器播放** —— 所以手机上也会响，这是预期行为。
 *
 * 顺带一提，这段音也是后续做 A/V 同步对照测量的现成工具（一声"嘀" + 一次视觉闪烁）。
 */
class TestTonePlayer(private val logger: Logger = AndroidLogger) {

    private var track: AudioTrack? = null

    @Volatile
    private var thread: Thread? = null

    private val running = AtomicBoolean(false)

    val isPlaying: Boolean get() = running.get()

    /**
     * 播放一段正弦测试音。
     *
     * @param durationMs 时长；<= 0 表示一直播到 [stop]
     * @param freqHz 频率（默认 1 kHz：语音频段正中，任何音量下都听得见、也不刺耳）
     * @param amplitude 幅度占满幅的比例（默认 0.5 ⇒ 约 -6 dBFS，明显高于静音又不削顶）
     */
    fun play(
        durationMs: Int = DEFAULT_DURATION_MS,
        freqHz: Int = DEFAULT_FREQ_HZ,
        amplitude: Double = DEFAULT_AMPLITUDE,
    ): Boolean {
        if (!DebugTools.PROBE_ENABLED) {
            logger.w(TAG, "release 构建：测试音已关闭")
            return false
        }
        if (!running.compareAndSet(false, true)) {
            logger.i(TAG, "测试音已在播放，忽略重复请求")
            return true
        }

        return try {
            val channelMask = AudioFormat.CHANNEL_OUT_STEREO
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    // ★ USAGE_MEDIA 是这里的关键：换成 NOTIFICATION / SONIFICATION 就捕获不到
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AudioSpec.SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(AudioSpec.PCM_FRAME_BYTES * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { t.release() }
                throw IllegalStateException("AudioTrack 未初始化（state=${t.state}）")
            }
            track = t
            t.play()

            val worker = Thread({ loop(t, durationMs, freqHz, amplitude) }, "uav-tone").apply {
                isDaemon = true
            }
            thread = worker
            worker.start()
            logger.i(
                TAG,
                "测试音开始：${freqHz}Hz ${if (durationMs > 0) "${durationMs}ms" else "持续"}，" +
                    "usage=USAGE_MEDIA（可被内录捕获，同时会从手机扬声器响）"
            )
            true
        } catch (t: Throwable) {
            logger.e(TAG, "测试音启动失败: ${t.message}", t)
            stop()
            false
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
        track?.let { t ->
            runCatching { if (t.playState != AudioTrack.PLAYSTATE_STOPPED) t.stop() }
            runCatching { t.release() }
        }
        track = null
    }

    private fun loop(t: AudioTrack, durationMs: Int, freqHz: Int, amplitude: Double) {
        val chunk = ByteArray(AudioSpec.PCM_FRAME_BYTES)
        val framesPerChunk = chunk.size / (2 * AudioSpec.CHANNELS)
        val step = 2.0 * PI * freqHz / AudioSpec.SAMPLE_RATE
        val peak = (amplitude.coerceIn(0.0, 0.95) * SHORT_MAX).toInt()

        var phase = 0.0
        val startedAt = System.nanoTime()
        var written = 0L

        while (running.get() && !Thread.currentThread().isInterrupted) {
            if (durationMs > 0 && (System.nanoTime() - startedAt) / 1_000_000L >= durationMs) break

            var i = 0
            while (i < framesPerChunk) {
                val v = (sin(phase) * peak).toInt()
                // 立体声：两个声道写同一个值（按小端 16 bit）
                writeSample(chunk, i * 4, v)
                writeSample(chunk, i * 4 + 2, v)
                phase += step
                if (phase > 2.0 * PI) phase -= 2.0 * PI
                i++
            }
            val n = t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) {
                logger.w(TAG, "测试音写入返回 $n，提前结束")
                break
            }
            written += n
        }
        logger.i(TAG, "测试音结束（共写出 ${written / 1024}KB PCM）")
        stop()
    }

    private fun writeSample(dst: ByteArray, at: Int, v: Int) {
        dst[at] = (v and 0xFF).toByte()
        dst[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    companion object {
        const val TAG = "UAV-Audio"

        /** 默认播 10 秒：够覆盖"切到宿主看日志"的时间。 */
        const val DEFAULT_DURATION_MS = 10_000

        const val DEFAULT_FREQ_HZ = 1_000

        const val DEFAULT_AMPLITUDE = 0.5

        private const val SHORT_MAX = 32767
    }
}
