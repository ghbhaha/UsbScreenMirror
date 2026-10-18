package com.uav.iosqt

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 解码 + 直接渲染到 [Surface]。
 *
 * 数据形态（实测 2026-10-18，552 个 FEED 包）：
 *  - 每个 FEED 的样本体是 `[4B 长度]['tads'][4B 长度前缀的 NAL][4B 长度前缀的 NAL]…`（AVCC）；
 *  - NAL 类型只有 `1`（P 帧）/ `5`（IDR）/ `6`（SEI），**没有 SPS/PPS**——
 *    它们随 `ASYN/sprp` 一起下发，所以必须先等 [start] 拿到 csd 才能解。
 */
internal class H264Decoder(
    private val surface: Surface,
    private val log: (String) -> Unit
) {

    private var codec: MediaCodec? = null
    private var ptsUs = 0L
    private var frames = 0

    /** 用 Annex-B 形式的 SPS+PPS 起解码器。成功返回 true。 */
    fun start(csd: ByteArray): Boolean {
        if (codec != null) return true
        return runCatching {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(fmt, surface, null, 0)
            c.start()
            codec = c
            log("解码器已启动（csd ${csd.size}B）")
            true
        }.getOrElse {
            log("✗ 解码器启动失败：${it.message}")
            false
        }
    }

    /** 送一帧（Annex-B）。 */
    fun submit(annexB: ByteArray) {
        val c = codec ?: return
        runCatching {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx) ?: return
                buf.clear()
                if (buf.remaining() < annexB.size) {
                    // 输入缓冲不够：这一帧丢掉（正常不会发生，单个 NAL 远小于 1MB）
                    c.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                    return
                }
                buf.put(annexB)
                ptsUs += 16_666
                c.queueInputBuffer(inIdx, 0, annexB.size, ptsUs, 0)
            }
            drain(c)
        }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0)
            if (outIdx >= 0) {
                c.releaseOutputBuffer(outIdx, true)   // true = 直接渲染到 Surface
                frames++
                if (frames == 1 || frames % 120 == 0) log("渲染 $frames 帧（${info.size}B）")
            } else {
                break
            }
        }
    }

    fun stop() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        if (frames > 0) log("解码结束：共渲染 $frames 帧")
    }

    val renderedFrames: Int get() = frames
}
