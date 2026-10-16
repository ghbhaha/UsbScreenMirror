package com.uav.protocol

import java.io.EOFException

/**
 * 12 字节流头（计划 4.4），只作为某条流的**首条 `WRTE` 的 payload** 出现，且仅一次：
 *
 * ```
 * codecId(4 ASCII) + width(4, LE) + height(4, LE)
 * ```
 *
 * ★ **`width`/`height` 是唯一的尺寸权威来源** —— 即编码器实际输出尺寸。
 * 宿主 `MediaCodec.configure` 的宽高与 `SurfaceView` 的宽高比都取此值。
 * **不来自 H.264 SPS 位流解析**，MVP 不需要任何位流语法解析（计划 4.4）。
 */
data class StreamHeader(
    val codecId: String,
    val width: Int,
    val height: Int,
) {

    val isPortrait: Boolean get() = height >= width

    /** 宽高比，用于 `SurfaceView.onMeasure`。 */
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()

    fun encode(): ByteArray {
        val out = ByteArray(SIZE)
        System.arraycopy(CodecId.encode(codecId), 0, out, 0, CodecId.SIZE)
        Bytes.putIntLE(out, 4, width)
        Bytes.putIntLE(out, 8, height)
        return out
    }

    override fun toString(): String = "StreamHeader($codecId ${width}x$height)"

    companion object {
        const val SIZE = 12

        /** 合法尺寸下界：小于此值的 "宽/高" 只可能是把帧数据误当流头解析出来的垃圾。 */
        const val MIN_DIM = 16

        /** 合法尺寸上界（8K 级别已远超本项目需求）。 */
        const val MAX_DIM = 8192

        /**
         * 解析 12B 流头。
         *
         * ★ 这里做**严格校验**，原因是实测踩过的坑：上一次会话在 USB FIFO 里留下的帧数据，
         *   会被"该流首个 `WRTE` 就是流头"这条约定误当成流头解析，
         *   得出 `StreamHeader(乱码 8x466)` 这种荒谬结果，然后**静默地把解码器配错**。
         *   严格校验让这种情况变成可识别的错误，上层据此把它当"杂散 payload"丢弃并继续等待真流头。
         *
         * @param expectVideoDims `width/height` 是否应当是"视频像素尺寸"。
         *   只有视频流才以宽高作尺寸权威；音频流的这两个字段语义未定义（MVP 不启用音频），
         *   因此对非视频流只校验 `codecId`，不校验数值范围。
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
            val codecId = CodecId.decode(buf, off)

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
