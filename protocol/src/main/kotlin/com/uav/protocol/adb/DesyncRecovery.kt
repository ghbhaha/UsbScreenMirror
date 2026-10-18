package com.uav.protocol.adb

import com.uav.protocol.Bytes
import com.uav.protocol.DesyncException
import com.uav.protocol.aoa.Command
import com.uav.protocol.aoa.FrameCodec
import com.uav.protocol.aoa.Protocol
import com.uav.protocol.aoa.StreamId
import com.uav.protocol.aoa.Transport

/**
 * 字节流**重新同步**：在失步（[DesyncException]）之后前移到下一个合法报文头。
 *
 * ## 为什么必须存在（真机教训）
 *
 * AOA 是一条"点对点、字节流、没有事务边界"的协议：报文 = 24B 头 + payload，
 * 一旦读指针偏了哪怕 1 个字节，之后每条报文头都会被读成垃圾 —— magic 立刻不符。
 *
 * 而"读指针偏了"在真实链路上**不可能避免**，因为宿主连上来的那一刻，
 * 配件端可能正卡在某条报文的中间（详见 [DesyncException] 的说明）。旧实现在这里
 * 直接判链路死 ⇒ 重连 ⇒ 又从头读到同一坨错位字节 ⇒ 再死：
 * **在 RK356x 盒子上表现为"永远连不上"**（宿主每 330ms 重试，配件端每次都回 CNXN，
 * 但宿主一个合法报文都解不出来）。
 *
 * ## 判据为什么够硬（不会把码流里的随机字节当成报文头）
 *
 * 候选头必须同时满足：`magic == command xor 0xffffffff`（32 位全等）、
 * command ∈ 已知命令字、`data_length` 在协议上限内、且 `arg0` 必须是已知流 id。
 * 随机字节命中的概率 ≈ 2⁻³² ；扫完 [DEFAULT_MAX_SKIP]（4 MiB）的累计误判率仍在
 * 千分之一量级，而这个量级只需要用户再重试一次即可自愈。
 *
 * ## 为什么**逐字节**读（看起来低效，其实不对）
 *
 * [Transport.readFully] 的 `len` 会一路传到 `bulkTransfer`：申请少于端点包长（512B）
 * 会踩 MTK 主机栈的 usercopy BUG（内核 panic，见 [com.uav.protocol.BufferedByteChannel]）。
 * 所以宿主侧通道一律是 `BufferedByteChannel`：它向内核永远整块读（16 KiB），
 * 把多到的字节留在内存里 —— **逐字节请求打在内存上，不产生任何小包 USB 传输**。
 */
object DesyncRecovery {

    /**
     * 一次重同步最多允许跳过的字节数。
     *
     * 实测残留量是"几十 KB 的半条报文"级别；4 MiB 已经宽松到足以吸收
     * "配件端仍在推流时我们才开始读"的最坏情况，又不至于把真·垃圾流扫成假同步。
     */
    const val DEFAULT_MAX_SKIP: Int = 4 * 1024 * 1024

    /**
     * 24B 缓冲是否"看起来是"一个合法报文头（**不校验 payload 的 checksum**）。
     *
     * checksum 校验交给 [AdbMessage.readFrom] —— 它需要把 payload 读进来。
     */
    fun isPlausibleHeader(header: ByteArray, off: Int = 0): Boolean {
        if (off < 0 || off + AdbMessage.HEADER_SIZE > header.size) return false

        val command = Bytes.getIntLE(header, off)
        val arg0 = Bytes.getIntLE(header, off + 4)
        val dataLength = Bytes.getIntLE(header, off + 12)
        val magic = Bytes.getIntLE(header, off + 20)

        // ① magic 必须与命令字严格互补（这一步就挡掉 99.99…% 的随机字节）
        if (magic != (command xor -1)) return false

        // ② 命令字必须是我们认识的那几个
        val cmd = Command.fromWire(command) ?: return false

        // ③ 长度必须自洽
        if (dataLength < 0 || dataLength > Protocol.MAX_PAYLOAD_SIZE) return false

        // ④ arg0 的语义按命令字再收一遍 —— 这是把误判率再压低两个数量级的关键
        return when (cmd) {
            // 首条 WRTE 的 payload 是 12B 流头；其余是 12B 元数据 + 码流
            Command.WRTE -> StreamId.isKnown(arg0) && dataLength >= FrameCodec.META_SIZE
            Command.CLSE -> arg0 == StreamId.CLOSE_ALL || StreamId.isKnown(arg0)
            Command.OKAY, Command.RQID -> StreamId.isKnown(arg0)
            Command.OPEN, Command.CNXN -> true
        }
    }

    /**
     * 从 [transport] 里逐字节前移，直到找到可信的报文头。
     *
     * 找到后把这 24 字节写进 [dst]（调用方随后交给
     * [AdbMessage.readFrom] 的 `header` 参数，避免再读一次）。
     *
     * @return 被跳过的字节数（= 该报文头相对"失步位置"的偏移量，0 表示其实没错位）
     * @throws DesyncException 跳过 [maxSkip] 字节仍没找到合法报文头
     * @throws java.io.EOFException 对端关闭
     */
    fun scanForHeader(
        transport: Transport,
        dst: ByteArray,
        maxSkip: Int = DEFAULT_MAX_SKIP,
    ): Int {
        require(dst.size >= AdbMessage.HEADER_SIZE) {
            "scanForHeader 的目标缓冲至少 ${AdbMessage.HEADER_SIZE} 字节，实际 ${dst.size}"
        }

        val win = ByteArray(AdbMessage.HEADER_SIZE)
        // 先读满一个窗口：此时 skipped = 0（头就在当前位置）
        transport.readFully(win, 0, AdbMessage.HEADER_SIZE)

        var skipped = 0
        while (true) {
            if (isPlausibleHeader(win, 0)) {
                System.arraycopy(win, 0, dst, 0, AdbMessage.HEADER_SIZE)
                return skipped
            }
            if (skipped >= maxSkip) {
                throw DesyncException(
                    "重新同步失败：连续跳过 $skipped 字节仍没有合法报文头" +
                        "（最后 24 字节 ${hex(win)}）"
                )
            }
            // 窗口左移 1 字节，再从流里补 1 字节
            System.arraycopy(win, 1, win, 0, AdbMessage.HEADER_SIZE - 1)
            transport.readFully(win, AdbMessage.HEADER_SIZE - 1, 1)
            skipped++
        }
    }

    private fun hex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }
}
