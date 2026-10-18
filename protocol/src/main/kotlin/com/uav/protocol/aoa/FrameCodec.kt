package com.uav.protocol.aoa

import com.uav.protocol.Bytes
import com.uav.protocol.Hex
import com.uav.protocol.ProtocolException
import com.uav.protocol.adb.AdbMessage
import com.uav.protocol.media.AudioSpec
import com.uav.protocol.media.CodecId
import java.io.EOFException

/**
 * 12 字节流头（计划 4.4），只作为某条流的**首条 `WRTE` 的 payload** 出现，且仅一次：
 *
 * ```
 * codecId(4 ASCII) + A(4, LE) + B(4, LE)
 * ```
 *
 * 后两个 u32 的**语义随流的种类变化**（同一个线格式，不加新的报文类型）：
 *
 * | 流 | A | B | 权威含义 |
 * | --- | --- | --- | --- |
 * | 视频（[StreamId.VIDEO]） | `width` | `height` | ★ 编码器实际输出尺寸 |
 * | 音频（[StreamId.AUDIO]） | 采样率 | 声道数 | 解码器 / `AudioTrack` 的格式 |
 *
 * ★ **视频的 `width`/`height` 是唯一的尺寸权威来源**。
 * 宿主 `MediaCodec.configure` 的宽高与 `SurfaceView` 的宽高比都取此值。
 * **不来自 H.264 SPS 位流解析**，本项目不需要任何位流语法解析（计划 4.4）。
 *
 * ★ 音频沿用同一个 12B 长度**不是偷懒**：接收端识别"尺寸变更后重发的流头"靠的是
 * "payload 恰好 12 字节"（帧 payload 是 `12 + size`，size ≥ 1，所以 ≥ 13）。
 * 给音频换一个不同长度的头会让这条判据失效。
 * 音频不需要"旋转后重发"这套，但**重连后必须重发**（见 [audio]）。
 */
data class StreamHeader(
    val codecId: String,
    val width: Int,
    val height: Int,
) {

    val isPortrait: Boolean get() = height >= width

    /** 宽高比，用于 `SurfaceView.onMeasure`。 */
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()

    /** 音频流时：A 位即采样率。视频流上无意义。 */
    val audioSampleRate: Int get() = width

    /** 音频流时：B 位即声道数。视频流上无意义。 */
    val audioChannels: Int get() = height

    fun encode(): ByteArray {
        val out = ByteArray(SIZE)
        System.arraycopy(CodecId.encode(codecId), 0, out, 0, CodecId.SIZE)
        Bytes.putIntLE(out, 4, width)
        Bytes.putIntLE(out, 8, height)
        return out
    }

    override fun toString(): String = when (codecId) {
        // 音频把两个字段叫 width/height 会误导排错（日志里出现 "48000x2"），单独渲染。
        // codecId 由 `decode` / 构造路径统一为 4 字符规范形态，可直接比。
        AudioSpec.CODEC_ID -> "StreamHeader($codecId ${width}Hz ${height}ch)"

        else -> "StreamHeader($codecId ${width}x$height)"
    }

    companion object {
        const val SIZE = 12

        /** 合法尺寸下界：小于此值的 "宽/高" 只可能是把帧数据误当流头解析出来的垃圾。 */
        const val MIN_DIM = 16

        /** 合法尺寸上界（8K 级别已远超本项目需求）。 */
        const val MAX_DIM = 8192

        /**
         * 构造音频流头：`sampleRate` / `channels` 落在 A / B 两个 u32 上。
         *
         * ⚠️ 音频流头**在每次新连接上都必须重发**（哪怕编码器是复用的）——
         * 宿主侧的解码器是**按连接**新建的，不给流头它就一直等不到格式。
         */
        fun audio(
            sampleRate: Int = AudioSpec.SAMPLE_RATE,
            channels: Int = AudioSpec.CHANNELS,
            codecId: String = AudioSpec.CODEC_ID,
        ): StreamHeader {
            require(sampleRate in AudioSpec.SAMPLE_RATE_RANGE) {
                "音频采样率越界：$sampleRate（合法 ${AudioSpec.SAMPLE_RATE_RANGE}）"
            }
            require(channels in AudioSpec.CHANNELS_RANGE) {
                "音频声道数越界：$channels（合法 ${AudioSpec.CHANNELS_RANGE}）"
            }
            return StreamHeader(codecId, sampleRate, channels)
        }

        /**
         * 解析**音频**流的 12B 流头：比 [decode] 多校验采样率与声道数的范围。
         *
         * 这里不能复用视频的 `MIN_DIM..MAX_DIM` 校验 —— 48000 Hz 会被它判成非法。
         * 但也**不能完全不校验**：USB FIFO 里残留的帧数据同样可能凑出 12 字节，
         * 拿到垃圾去 configure 解码器会静默配错采样率（听感上只是"音调不对"，
         * 极难定位），所以必须挡在门口。
         */
        fun decodeAudio(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): StreamHeader {
            val h = decode(buf, off, len, expectVideoDims = false)
            // codecId 已由 [decode] 规范化，可直接与常量比对
            if (h.codecId !in AUDIO_CODEC_IDS) {
                throw ProtocolException(
                    "音频流头的 codecId '${h.codecId}' 不是已知音频编码 " +
                        "(${AUDIO_CODEC_IDS.joinToString("/")}) —— 多半是把帧数据误当流头"
                )
            }
            if (h.audioSampleRate !in AudioSpec.SAMPLE_RATE_RANGE) {
                throw ProtocolException(
                    "音频流头采样率不合理：${h.audioSampleRate} Hz " +
                        "(合法 ${AudioSpec.SAMPLE_RATE_RANGE}) —— 多半是把帧数据误当流头"
                )
            }
            if (h.audioChannels !in AudioSpec.CHANNELS_RANGE) {
                throw ProtocolException(
                    "音频流头声道数不合理：${h.audioChannels} " +
                        "(合法 ${AudioSpec.CHANNELS_RANGE}) —— 多半是把帧数据误当流头"
                )
            }
            return h
        }

        /** 允许出现在音频流头上的 `codecId`（本项目实际只用 [AudioSpec.CODEC_ID]）。 */
        val AUDIO_CODEC_IDS = listOf(
            AudioSpec.CODEC_ID, CodecId.OPUS, CodecId.FLAC, CodecId.RAW,
        )

        /**
         * 解析 12B 流头。
         *
         * ★ 这里做**严格校验**，原因是实测踩过的坑：上一次会话在 USB FIFO 里留下的帧数据，
         *   会被"该流首个 `WRTE` 就是流头"这条约定误当成流头解析，
         *   得出 `StreamHeader(乱码 8x466)` 这种荒谬结果，然后**静默地把解码器配错**。
         *   严格校验让这种情况变成可识别的错误，上层据此把它当"杂散 payload"丢弃并继续等待真流头。
         *
         * @param expectVideoDims `width/height` 是否应当是"视频像素尺寸"。
         *   只有视频流才以宽高作尺寸权威。音频流的这两个字段是**采样率/声道数**
         *   （必须用 [decodeAudio] 解析，它会换成音频的范围校验）；其它流的数值语义
         *   未定义，只校验 `codecId` 可打印。
         */
        fun decode(
            buf: ByteArray,
            off: Int = 0,
            len: Int = buf.size - off,
            expectVideoDims: Boolean = true,
        ): StreamHeader {
            if (len < SIZE) {
                throw ProtocolException("stream header too short: $len < $SIZE")
            }

            // codecId 必须是 4 个可打印 ASCII 字符（补位空格合法，控制字符/高位字节不合法）。
            // 帧数据的头 4 字节是 PTS 低位，几乎必然含非可打印字节 —— 这一条就能挡掉大部分误解析。
            for (i in 0 until CodecId.SIZE) {
                val b = buf[off + i].toInt() and 0xFF
                if (b < 0x20 || b > 0x7E) {
                    throw ProtocolException(
                        "stream header codecId 含非可打印字节 0x${Integer.toHexString(b)} " +
                            "(${Hex.toHexString(buf, off, SIZE)}) —— 多半是把帧数据误当流头"
                    )
                }
            }
            // 4 个字节全是空格（0x20 是可打印字符，能过上面那道可打印性检查）会解出空串，
            // 让后面的 `CodecId.normalize` 抛 IllegalArgumentException —— 那不是
            // ProtocolException，会**穿透调用方的 catch** 把读线程打挂。先在这里挡住。
            val raw = CodecId.decode(buf, off)
            if (raw.isEmpty()) {
                throw ProtocolException(
                    "stream header codecId 全为空格 (${Hex.toHexString(buf, off, SIZE)}) " +
                        "—— 多半是把帧数据误当流头"
                )
            }

            // ★ 统一成**规范形态**（右补空格到 4 字符）。
            //
            //   原因：`CodecId.decode` 会去掉右填充空格（"aac " -> "aac"），而构造路径
            //   （`StreamHeader(CodecId.AAC, ...)`）带着补齐后的空格。两种形态混在一起会让
            //   `data class` 的 `equals` **不可靠** —— "解码出来的头"与"构造的头"不相等，
            //   而 `Connection` 正是靠 `updated != headers[streamId]` 判断"流头变了没"。
            //   规范化之后 `codecId` 恒为 4 字符，相等性才有意义。
            val codecId = CodecId.normalize(raw)

            val width = Bytes.getIntLE(buf, off + 4)
            val height = Bytes.getIntLE(buf, off + 8)
            if (expectVideoDims && (width !in MIN_DIM..MAX_DIM || height !in MIN_DIM..MAX_DIM)) {
                throw ProtocolException(
                    "stream header 尺寸不合理: ${width}x$height (合法范围 $MIN_DIM..$MAX_DIM) " +
                        "—— 多半是把帧数据误当流头"
                )
            }
            return StreamHeader(codecId, width, height)
        }
    }
}

/**
 * 一帧（`WRTE` payload 去掉 12B 帧元数据后的码流数据）。
 *
 * @param ptsAndFlags 见 [Flags]
 * @param data 码流数据；**长度 = 帧元数据里声明的 size**
 */
class Frame(
    val ptsAndFlags: Long,
    val data: ByteArray,
) {

    val isConfig: Boolean get() = Flags.isConfig(ptsAndFlags)

    val isKeyFrame: Boolean get() = Flags.isKeyFrame(ptsAndFlags)

    val pts: Long get() = Flags.pts(ptsAndFlags)

    val size: Int get() = data.size

    override fun toString(): String {
        val kind = when {
            isConfig -> "CONFIG"
            isKeyFrame -> "KEY"
            else -> "P"
        }
        return "Frame($kind pts=${pts}us size=$size)"
    }
}

/**
 * 流内帧的编解码（计划 1.6 / 4.4）。
 *
 * ```
 * WRTE #1 (首个)   payload = [12B 流头]
 * WRTE #2..N       payload = [8B ptsAndFlags][4B size][size 字节 码流数据]
 * ```
 *
 * 一条 `WRTE` 恰好承载一帧（发送端"合并写"保证），**接收端无需跨报文重组**。
 */
object FrameCodec {

    /** 帧元数据长度：8B ptsAndFlags + 4B size。 */
    const val META_SIZE = 12

    /** 组装帧元数据。 */
    fun encodeMeta(ptsAndFlags: Long, size: Int): ByteArray {
        val meta = ByteArray(META_SIZE)
        Bytes.putLongLE(meta, 0, ptsAndFlags)
        Bytes.putIntLE(meta, 8, size)
        return meta
    }

    /** 组装「12B 元数据 + payload」的完整 `WRTE` payload（非 UTF-8，纯二进制）。 */
    fun encodeFrame(ptsAndFlags: Long, data: ByteArray, off: Int = 0, len: Int = data.size): ByteArray {
        require(len <= Protocol.MAX_FRAME_SIZE) {
            "frame too large: $len > ${Protocol.MAX_FRAME_SIZE}"
        }
        val out = ByteArray(META_SIZE + len)
        Bytes.putLongLE(out, 0, ptsAndFlags)
        Bytes.putIntLE(out, 8, len)
        System.arraycopy(data, off, out, META_SIZE, len)
        return out
    }

    /**
     * 从 `WRTE` 的 payload 解析出一帧。
     *
     * @throws ProtocolException 长度不足 / size 与实测不符 / size 超限
     */
    fun decodeFrame(payload: ByteArray, off: Int = 0, len: Int = payload.size - off): Frame {
        if (len < META_SIZE) {
            throw ProtocolException("frame payload too short: $len < $META_SIZE")
        }
        val ptsAndFlags = Bytes.getLongLE(payload, off)
        val size = Bytes.getIntLE(payload, off + 8)

        if (size < 0 || size > Protocol.MAX_FRAME_SIZE) {
            throw ProtocolException("frame size out of range: $size (max ${Protocol.MAX_FRAME_SIZE})")
        }
        if (size != len - META_SIZE) {
            throw ProtocolException("frame size mismatch: declared $size, actual ${len - META_SIZE}")
        }

        val data = ByteArray(size)
        System.arraycopy(payload, off + META_SIZE, data, 0, size)
        return Frame(ptsAndFlags, data)
    }

    /**
     * 发送一帧：**一次 `write`** 把「12B 元数据 + 码流」整体投出（计划 4.8 合并写）。
     *
     * 这样接收端一条 `WRTE` 恰好一帧，无需重组。
     */
    fun writeFrame(transport: Transport, streamId: Int, ptsAndFlags: Long, data: ByteArray) {
        val payload = encodeFrame(ptsAndFlags, data, 0, data.size)
        AdbMessage.writeTo(transport, Command.WRTE, streamId, 0, payload)
    }

    /** 发送某条流的流头（该流的首条 `WRTE`）。 */
    fun writeStreamHeader(transport: Transport, streamId: Int, header: StreamHeader) {
        AdbMessage.writeTo(transport, Command.WRTE, streamId, 0, header.encode())
    }

    /**
     * 从流里直接读一帧（离线回放 / 单测用）。
     * 注意：正式链路里帧是从 `WRTE` 报文里取的，不走这里。
     */
    fun readFrame(transport: Transport): Frame {
        val meta = ByteArray(META_SIZE)
        transport.readFully(meta, 0, META_SIZE)
        val ptsAndFlags = Bytes.getLongLE(meta, 0)
        val size = Bytes.getIntLE(meta, 8)
        if (size < 0 || size > Protocol.MAX_FRAME_SIZE) {
            throw ProtocolException("frame size out of range: $size")
        }
        if (size == 0) return Frame(ptsAndFlags, ByteArray(0))
        val data = ByteArray(size)
        try {
            transport.readFully(data, 0, size)
        } catch (eof: EOFException) {
            throw ProtocolException("truncated frame: wanted $size bytes", eof)
        }
        return Frame(ptsAndFlags, data)
    }
}
