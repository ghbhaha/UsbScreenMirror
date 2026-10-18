package com.uav.protocol.adb

import com.uav.protocol.Bytes
import com.uav.protocol.DesyncException
import com.uav.protocol.Hex
import com.uav.protocol.ProtocolException
import com.uav.protocol.aoa.Command
import com.uav.protocol.aoa.Frame
import com.uav.protocol.aoa.FrameCodec
import com.uav.protocol.aoa.Protocol
import com.uav.protocol.aoa.StreamId
import com.uav.protocol.aoa.Transport
import java.io.EOFException

/**
 * 24 字节报文头 + payload（计划 4.1）。
 *
 * ```c
 * struct message {
 *     unsigned command;       // 命令字（见 Command）
 *     unsigned arg0;          // 语义见计划 4.2 表
 *     unsigned arg1;
 *     unsigned data_length;   // payload 长度，上限 8 MiB
 *     unsigned data_check;    // payload 逐字节无符号累加（u32 截断）
 *     unsigned magic;         // command ^ 0xffffffff
 * };
 * ```
 *
 * 全小端。校验策略（计划 1.2）：magic / checksum 不符、长度超限 —— **一律抛错，不静默跳过**。
 */
class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray,
    /**
     * 帧感知读（10.34）时，`WRTE` 帧已经在这里了 —— 此时 [payload] 为**空**
     * （码流被直接读进了 `frame.data`，没有中转缓冲）。
     *
     * 为 `null` 表示走通用路径：`payload` 是该报文的完整载荷（含 12B 帧元数据），
     * 由调用方自己 `FrameCodec.decodeFrame` 解出来。
     */
    val frame: Frame? = null,
) {

    val commandType: Command? get() = Command.fromWire(command)

    val commandName: String get() = commandType?.name ?: String.format("0x%08X", command)

    val magic: Int get() = command xor -1

    val checksum: Int get() = checksumOf(payload, 0, payload.size)

    fun isCommand(c: Command): Boolean = command == c.wire

    /** 24B 头 + payload（调试/单测用；正式发送走 [writeTo] 以免整帧拷贝）。 */
    fun encode(): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        writeHeader(out, 0, command, arg0, arg1, payload.size, checksum)
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }

    override fun toString(): String =
        "AdbMessage($commandName arg0=${formatArg0()} arg1=$arg1 len=${payload.size})"

    private fun formatArg0(): String = when (commandType) {
        Command.WRTE, Command.CLSE -> "${arg0}(${StreamId.name(arg0)})"
        else -> arg0.toString()
    }

    companion object {

        const val HEADER_SIZE = 24

        val EMPTY = ByteArray(0)

        /** payload 逐字节无符号累加，u32 截断。 */
        fun checksumOf(payload: ByteArray, off: Int = 0, len: Int = payload.size - off): Int {
            var sum = 0
            for (i in off until off + len) {
                sum += payload[i].toInt() and 0xFF
            }
            return sum
        }

        fun writeHeader(
            dst: ByteArray,
            off: Int,
            command: Int,
            arg0: Int,
            arg1: Int,
            dataLength: Int,
            dataCheck: Int,
        ) {
            Bytes.putIntLE(dst, off, command)
            Bytes.putIntLE(dst, off + 4, arg0)
            Bytes.putIntLE(dst, off + 8, arg1)
            Bytes.putIntLE(dst, off + 12, dataLength)
            Bytes.putIntLE(dst, off + 16, dataCheck)
            Bytes.putIntLE(dst, off + 20, command xor -1)
        }

        /**
         * 写入一条报文：**先写 24B 头、再写 payload**，两次连续写。
         *
         * 不合并成一个大缓冲是为了避免为大帧再做一次整帧拷贝；
         * 无交错风险由 [Transport] 的写锁 + 单写线程保证（计划 4.8）。
         */
        fun writeTo(
            transport: Transport,
            command: Int,
            arg0: Int,
            arg1: Int,
            payload: ByteArray = EMPTY,
            payloadOffset: Int = 0,
            payloadLength: Int = payload.size,
        ) {
            require(payloadLength <= Protocol.MAX_PAYLOAD_SIZE) {
                "payload too large: $payloadLength > ${Protocol.MAX_PAYLOAD_SIZE}"
            }
            val header = ByteArray(HEADER_SIZE)
            writeHeader(
                header, 0, command, arg0, arg1, payloadLength,
                checksumOf(payload, payloadOffset, payloadLength),
            )
            transport.write(header, 0, HEADER_SIZE)
            if (payloadLength > 0) {
                transport.write(payload, payloadOffset, payloadLength)
            }
        }

        fun writeTo(transport: Transport, command: Command, arg0: Int, arg1: Int, payload: ByteArray = EMPTY) =
            writeTo(transport, command.wire, arg0, arg1, payload)

        /**
         * 从流里读一条完整报文。
         *
         * @param frameMeta **帧感知读**（10.34）。传一个长度 ≥ [FrameCodec.META_SIZE] 的
         *   可复用缓冲，则 `WRTE` 帧会被拆成「12B 元数据 + 码流」两段读：元数据进这个缓冲、
         *   码流**直接读进它自己的数组**，结果放在 [AdbMessage.frame] 里（[AdbMessage.payload] 为空）。
         *   省掉原来「整包 ByteArray → decodeFrame 再 arraycopy 一份」的那次拷贝和一次分配。
         *
         *   ⚠️ 只有 `WRTE` 且 `data_length > 12` 才拆：载荷恰为 12B 的 `WRTE` 是**流头**，
         *   必须留在 `payload` 里走通用的流头解析。传 `null` = 老行为，单测/回放不受影响。
         *
         *   ⚠️ 这个缓冲**由调用方持有**（每连接一个），因为只有读线程用它 —— 不要跨连接共享。
         *
         * @param header **已经读好的 24B 报文头**（失步恢复用，见 [DesyncRecovery]）。
         *   传非 null 就跳过"读头"这一步 —— 扫描器已经把这 24 字节从流里取出来了，
         *   再读一次就会把后面的 payload 吃掉。
         *
         * @throws EOFException     对端关闭（读头或读 payload 时）
         * @throws DesyncException  字节流错位（magic / 长度 / checksum 不符）—— **可重同步**
         * @throws ProtocolException 收到已裁剪命令 —— 不可恢复
         */
        fun readFrom(
            transport: Transport,
            frameMeta: ByteArray? = null,
            header: ByteArray? = null,
        ): AdbMessage {
            val hdr = header ?: ByteArray(HEADER_SIZE).also {
                try {
                    transport.readFully(it, 0, HEADER_SIZE)
                } catch (eof: EOFException) {
                    throw EOFException("EOF while reading message header: ${eof.message}")
                }
            }

            val command = Bytes.getIntLE(hdr, 0)
            val arg0 = Bytes.getIntLE(hdr, 4)
            val arg1 = Bytes.getIntLE(hdr, 8)
            val dataLength = Bytes.getIntLE(hdr, 12)
            val dataCheck = Bytes.getIntLE(hdr, 16)
            val magic = Bytes.getIntLE(hdr, 20)

            // 1) magic 校验 —— 错位的第一道防线（★ 判为 DesyncException：可重同步）
            if (magic != (command xor -1)) {
                throw DesyncException(
                    "bad magic: got 0x${Integer.toHexString(magic)} expected 0x" +
                        Integer.toHexString(command xor -1) +
                        " (command=0x${Integer.toHexString(command)})\n" +
                        Hex.dump(hdr, 0, HEADER_SIZE)
                )
            }

            // 2) 长度上限 —— 防伪造头导致 OOM
            if (dataLength < 0 || dataLength > Protocol.MAX_PAYLOAD_SIZE) {
                throw DesyncException(
                    "data_length out of range: $dataLength (max ${Protocol.MAX_PAYLOAD_SIZE}), " +
                        "command=0x${Integer.toHexString(command)}"
                )
            }

            // 3) 已裁剪命令（AUTH / STLS）—— 收到即协议错误
            Command.removedName(command)?.let { name ->
                throw ProtocolException("removed command '$name' received (command=0x${Integer.toHexString(command)})")
            }

            // ★ 4) 帧感知快路径（10.34）：见 [readFrom] 的 frameMeta 说明。
            if (frameMeta != null && command == Command.WRTE.wire && dataLength > FrameCodec.META_SIZE) {
                transport.readFully(frameMeta, 0, FrameCodec.META_SIZE)
                val ptsAndFlags = Bytes.getLongLE(frameMeta, 0)
                val size = Bytes.getIntLE(frameMeta, 8)
                if (size < 0 || size > Protocol.MAX_FRAME_SIZE) {
                    throw DesyncException("frame size out of range: $size (max ${Protocol.MAX_FRAME_SIZE})")
                }
                val declared = dataLength - FrameCodec.META_SIZE
                if (size != declared) {
                    throw DesyncException("frame size mismatch: declared $size, actual $declared")
                }
                val data = ByteArray(size)
                transport.readFully(data, 0, size)
                // checksum 是逐字节累加 ⇒ 可以分段相加（8 MiB 上限不会溢出 Int）
                val actual = checksumOf(frameMeta, 0, FrameCodec.META_SIZE) + checksumOf(data, 0, size)
                if (actual != dataCheck) {
                    throw DesyncException(
                        "bad checksum for WRTE: got 0x${Integer.toHexString(actual)} " +
                            "expected 0x${Integer.toHexString(dataCheck)}"
                    )
                }
                return AdbMessage(command, arg0, arg1, EMPTY, Frame(ptsAndFlags, data))
            }

            val payload = if (dataLength > 0) ByteArray(dataLength) else EMPTY
            if (dataLength > 0) {
                transport.readFully(payload, 0, dataLength)
            }

            // 4) payload 校验和
            val actual = checksumOf(payload, 0, payload.size)
            if (actual != dataCheck) {
                throw DesyncException(
                    "bad checksum for ${Command.fromWire(command)?.name ?: "0x${Integer.toHexString(command)}"}: " +
                        "got 0x${Integer.toHexString(actual)} expected 0x${Integer.toHexString(dataCheck)}"
                )
            }

            return AdbMessage(command, arg0, arg1, payload)
        }
    }
}
