package com.uav.protocol

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
         * @throws EOFException     对端关闭（读头或读 payload 时）
         * @throws ProtocolException magic 不符 / 长度超限 / checksum 不符 / 收到已裁剪命令
         */
        fun readFrom(transport: Transport): AdbMessage {
            val header = ByteArray(HEADER_SIZE)
            try {
                transport.readFully(header, 0, HEADER_SIZE)
            } catch (eof: EOFException) {
                throw EOFException("EOF while reading message header: ${eof.message}")
            }

            val command = Bytes.getIntLE(header, 0)
            val arg0 = Bytes.getIntLE(header, 4)
            val arg1 = Bytes.getIntLE(header, 8)
            val dataLength = Bytes.getIntLE(header, 12)
            val dataCheck = Bytes.getIntLE(header, 16)
            val magic = Bytes.getIntLE(header, 20)

            // 1) magic 校验 —— 错位的第一道防线
            if (magic != (command xor -1)) {
                throw ProtocolException(
                    "bad magic: got 0x${Integer.toHexString(magic)} expected 0x" +
                        Integer.toHexString(command xor -1) +
                        " (command=0x${Integer.toHexString(command)})\n" +
                        Hex.dump(header, 0, HEADER_SIZE)
                )
            }

            // 2) 长度上限 —— 防伪造头导致 OOM
            if (dataLength < 0 || dataLength > Protocol.MAX_PAYLOAD_SIZE) {
                throw ProtocolException(
                    "data_length out of range: $dataLength (max ${Protocol.MAX_PAYLOAD_SIZE}), " +
                        "command=0x${Integer.toHexString(command)}"
                )
            }

            // 3) 已裁剪命令（AUTH / STLS）—— 收到即协议错误
            Command.removedName(command)?.let { name ->
                throw ProtocolException("removed command '$name' received (command=0x${Integer.toHexString(command)})")
            }

            val payload = if (dataLength > 0) ByteArray(dataLength) else EMPTY
            if (dataLength > 0) {
                transport.readFully(payload, 0, dataLength)
            }

            // 4) payload 校验和
            val actual = checksumOf(payload, 0, payload.size)
            if (actual != dataCheck) {
                throw ProtocolException(
                    "bad checksum for ${Command.fromWire(command)?.name ?: "0x${Integer.toHexString(command)}"}: " +
                        "got 0x${Integer.toHexString(actual)} expected 0x${Integer.toHexString(dataCheck)}"
                )
            }

            return AdbMessage(command, arg0, arg1, payload)
        }
    }
}
