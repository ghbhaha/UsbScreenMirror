package com.uav.host.debug

import com.uav.protocol.Bytes
import com.uav.protocol.ByteChannel
import com.uav.protocol.Command
import com.uav.protocol.Flags
import com.uav.protocol.Logger
import com.uav.protocol.StreamId
import com.uav.protocol.i
import com.uav.protocol.w
import java.io.File
import java.io.IOException

/**
 * 从本地 `.uav` 抓流文件回放的 [ByteChannel]（计划 4.0 / 第 7 节）。
 *
 * 这是缩短真机联调时间最有效的一件工具：把问题域一刀切成两半
 *  - **回放能出画面** ⇒ USB 握手 / 协议 / 传输都已通，剩余问题全在实时链路（超时、背压、线程）；
 *  - **回放也出不了画面** ⇒ 用 JVM 单测直接查协议解析与解码器配置，完全不占真机。
 *
 * 文件格式就是**原样的报文流**（24B 头 + payload，逐条拼接），
 * 也就是把 USB 上收到的字节直接落盘，不做任何封装 —— 这样回放路径与实时路径
 * 走的是同一套 [com.uav.protocol.AdbMessage] 解析代码，不存在"两套解析"的漂移风险。
 *
 * ## 为什么配速必须做在**这里**（真机实测教训）
 * 最初把配速放在宿主的消费线程上，结果完全无效：读线程是**磁盘速度**灌进深度为 4 的
 * 有界队列，4316 帧里被丢掉 4310 帧（连关键帧一起丢），解码器随即报错。
 * 配速只能做在**字节进入管线的地方**，也就是本类 —— 读的时候按帧 PTS 节流。
 */
class FileByteChannel(
    private val file: File,
    private val logger: Logger = com.uav.host.AndroidLogger,
    /** 是否按帧 PTS 还原真实播放速度（关掉就是"尽快灌完"，仅用于压测协议解析）。 */
    private val paceByPts: Boolean = true,
) : ByteChannel {

    override val label: String = "file:${file.name}"

    /** 一次性读入。抓流文件通常几十 MB，回放是调试场景，用内存换实现简单。 */
    private val data: ByteArray = file.readBytes()

    val totalBytes: Long = data.size.toLong()

    private var pos = 0
    private var closed = false

    /** 已回放字节数。 */
    var bytesRead: Long = 0
        private set

    // ---- 配速表：视频帧消息的起始偏移 -> 相对首个配速点的纳秒数 ----
    private val paceOffsets = ArrayList<Int>()
    private val paceNanos = ArrayList<Long>()
    private var paceIdx = 0
    private var paceStartNanos = 0L

    init {
        buildPaceTable()
        if (paceOffsets.isNotEmpty()) {
            val spanSec = paceNanos.last() / 1e9
            logger.i(
                TAG,
                "$label 载入 ${data.size} 字节，配速点 ${paceOffsets.size} 个，" +
                    "时间轴约 ${"%.1f".format(spanSec)}s（paceByPts=$paceByPts）"
            )
        }
    }

    /** 扫一遍报文，挑出"视频流的非 CONFIG 帧"，记下其起始偏移与 PTS。 */
    private fun buildPaceTable() {
        var off = 0
        var base = -1L
        var parsed = 0
        while (off + HEADER_SIZE <= data.size) {
            val command = Bytes.getIntLE(data, off)
            val arg0 = Bytes.getIntLE(data, off + 4)
            val dataLength = Bytes.getIntLE(data, off + 12)
            if (dataLength < 0 || off + HEADER_SIZE + dataLength > data.size) break // 截断，停止

            if (command == Command.WRTE.wire && arg0 == StreamId.VIDEO && dataLength > META_SIZE) {
                val ptsAndFlags = Bytes.getLongLE(data, off + HEADER_SIZE)
                if (!Flags.isConfig(ptsAndFlags)) {
                    val pts = Flags.pts(ptsAndFlags)
                    if (base < 0) base = pts
                    paceOffsets.add(off)
                    paceNanos.add((pts - base) * 1_000L)
                }
            }
            off += HEADER_SIZE + dataLength
            parsed++
        }
        logger.i(TAG, "$label 预扫描完成：$parsed 条报文，未解析残余 ${data.size - off} 字节")
    }

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("$label closed")
        if (len == 0) return 0
        if (pos >= data.size) return -1

        paceWaitIfNeeded()

        val n = minOf(len, data.size - pos)
        System.arraycopy(data, pos, dst, off, n)
        pos += n
        bytesRead += n.toLong()
        return n
    }

    /**
     * 若当前读取位置正好落在一个配速点上，就等到它的"应送达时间"。
     *
     * 之所以能按偏移精确命中：宿主是**逐条报文**读的（先 24B 头、再 payload），
     * 所以读取位置必然精确落在每条报文的起始偏移上。
     */
    private fun paceWaitIfNeeded() {
        if (!paceByPts) return
        while (paceIdx < paceOffsets.size && paceOffsets[paceIdx] < pos) paceIdx++
        if (paceIdx >= paceOffsets.size) return
        if (paceOffsets[paceIdx] != pos) return

        if (paceStartNanos == 0L) paceStartNanos = System.nanoTime()
        val target = paceStartNanos + paceNanos[paceIdx]
        paceIdx++

        var remaining = target - System.nanoTime()
        while (remaining > 0) {
            if (closed) throw IOException("$label closed during pacing")
            val stepMs = minOf(remaining / 1_000_000L, PACING_MAX_SLEEP_MS)
            try {
                if (stepMs > 0) Thread.sleep(stepMs) else Thread.yield()
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("$label interrupted during pacing", ie)
            }
            remaining = target - System.nanoTime()
        }
    }

    override fun write(src: ByteArray, off: Int, len: Int) {
        // 回放通道是只读的；写入静默丢弃，避免把回放会话的应答写坏文件
    }

    override fun close() {
        closed = true
    }

    fun progressText(): String =
        if (totalBytes <= 0) label else "$label ${bytesRead * 100 / totalBytes}% ($bytesRead/$totalBytes)"

    companion object {
        const val TAG = "UAV-Host"

        private const val HEADER_SIZE = 24
        private const val META_SIZE = 12

        /** 单次配速 sleep 的上限，保证 close() 能及时打断。 */
        private const val PACING_MAX_SLEEP_MS = 50L
    }
}
