package com.uav.host.ios

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.uav.protocol.ios.AudioParser

/**
 * 音频回放：把 `ASYN/EAT!` 里的裸 PCM 直接喂给 [AudioTrack]。
 *
 * ## 格式自适应
 *
 * 不写死采样率 —— 每包 PCM 都带着 `fdsc` 里解析出的 [AudioParser.Format]，
 * 与当前轨道不一致就**重建轨道**（设备换格式时不用改代码）。
 * 只在 `fdsc` 缺失时才回退到 `HPA1` 声明的 48k/16bit/stereo。
 *
 * ## 两个刻意的取舍
 *
 * 1. **只用 `WRITE_NON_BLOCKING`**：喂数据的是收 USB 的线程，
 *    一旦用阻塞写，音频缓冲满了就会把视频收流一起卡死（本仓库踩过同类坑：
 *    `AudioTrack.pause()` 之后阻塞写会永久挂住消费线程）。
 *    队列满时**丢这一包**，代价是几十毫秒空洞，比整条链路停住强得多。
 * 2. **先置 `playing=false` 再 `pause/flush`**：顺序反了会写进已暂停的轨道。
 */
internal class PcmAudioPlayer(private val log: (String) -> Unit) {

    companion object {
        /** `fdsc` 缺失时的兜底（与 HPA1 里声明的候选一致） */
        val DEFAULT_FORMAT = AudioParser.Format(
            sampleRate = 48_000,
            channels = 2,
            bitsPerChannel = 16,
            codec = "lpcm",
            formatFlags = 12
        )
        /** 约 200ms 的缓冲：够吸收 USB 抖动，又不至于让音画差太多 */
        private const val BUFFER_MS = 200
    }

    private var track: AudioTrack? = null
    private var current: AudioParser.Format? = null
    private var bytesWritten = 0L
    private var bytesDropped = 0L
    private var firstPacket = true

    /**
     * 建（或按新格式重建）轨道。
     *
     * ⚠️ 比较时**故意忽略 `formatFlags`**：设备前几包的 fdsc 里这个字段会在 12 与 0 之间
     * 跳一下，而 codec/采样率/位深/声道完全没变 —— 直接比整个 data class 会让播放器
     * 在建轨瞬间白白重建一次（真机日志里就出现过「lpcm 48k/16bit/2ch → 同一串，重建轨道」）。
     * 真正影响听感与轨道参数的只有这四项。
     */
    private fun ensure(fmt: AudioParser.Format): AudioTrack? {
        val same = current?.let {
            it.sampleRate == fmt.sampleRate && it.channels == fmt.channels &&
                it.bitsPerChannel == fmt.bitsPerChannel && it.codec == fmt.codec
        } ?: false
        if (track != null && same) return track
        if (track != null) {
            log("音频格式变化（${current} → ${fmt}），重建轨道")
            releaseTrack()
        }
        return runCatching {
            val channelMask = when (fmt.channels) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                else -> {
                    log("✗ 音频：暂不支持 ${fmt.channels} 声道")
                    return null
                }
            }
            val encoding = when (fmt.bitsPerChannel) {
                8 -> AudioFormat.ENCODING_PCM_8BIT
                16 -> AudioFormat.ENCODING_PCM_16BIT
                32 -> AudioFormat.ENCODING_PCM_FLOAT
                else -> {
                    log("✗ 音频：暂不支持 ${fmt.bitsPerChannel}bit")
                    return null
                }
            }
            val minBuf = AudioTrack.getMinBufferSize(fmt.sampleRate, channelMask, encoding)
            if (minBuf <= 0) {
                log("✗ 音频：getMinBufferSize=$minBuf（${fmt.sampleRate}Hz 不被支持）")
                return null
            }
            val bufSize = maxOf(minBuf, fmt.sampleRate * fmt.bytesPerFrame * BUFFER_MS / 1000)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(fmt.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
            current = fmt
            log("★ 音频轨道已开：$fmt，缓冲 ${bufSize}B")
            t
        }.getOrElse {
            log("✗ 音频轨道创建失败：${it.message}")
            null
        }
    }

    /** 喂一包 PCM（调用方是收流的 worker 线程，本方法**不阻塞**）。 */
    fun feed(pcm: ByteArray, fmt: AudioParser.Format = DEFAULT_FORMAT) {
        if (pcm.isEmpty()) return
        val t = ensure(fmt) ?: return
        val n = runCatching {
            t.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
        }.getOrElse { -1 }
        when {
            n > 0 -> {
                bytesWritten += n
                if (n < pcm.size) bytesDropped += (pcm.size - n)
                if (firstPacket) {
                    firstPacket = false
                    log("♪ 首包音频已写入 ${n}B")
                }
            }
            // 队列满：丢这包，绝不阻塞收流
            else -> bytesDropped += pcm.size
        }
    }

    fun stop() {
        if (track == null) return
        if (bytesWritten > 0 || bytesDropped > 0) {
            log("音频结束：写入 $bytesWritten B / 丢弃 $bytesDropped B（${droppedPercent()}%）")
        }
        releaseTrack()
        bytesWritten = 0
        bytesDropped = 0
        firstPacket = true
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        current = null
        runCatching { t.stop() }
        runCatching { t.flush() }
        runCatching { t.release() }
    }

    private fun droppedPercent(): Int {
        val total = bytesWritten + bytesDropped
        return if (total <= 0) 0 else ((bytesDropped * 100) / total).toInt()
    }
}
