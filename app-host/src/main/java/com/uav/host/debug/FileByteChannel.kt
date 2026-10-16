package com.uav.host.debug

import com.uav.protocol.ByteChannel
import com.uav.protocol.Logger
import com.uav.protocol.i
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 从本地 `.uav` 抓流文件回放的 [ByteChannel]（计划 4.0 / 第 7 节）。
 *
 * 这是缩短真机联调时间最有效的一件工具：把问题域一刀切成两半
 *  - **回放能出画面** ⇒ USB 握手 / 协议 / 传输都已通，剩余问题全在实时链路（超时、背压、线程）；
 *  - **回放也出不了画面** ⇒ 用 JVM 单测直接查协议解析与解码器配置，完全不占真机。
 *
 * 副产品：同一份抓流数据也是 `:protocol` 单测的回归夹具。
 *
 * 文件格式就是**原样的报文流**（24B 头 + payload，逐条拼接），
 * 也就是把 USB 上收到的字节直接落盘，不做任何封装 —— 这样回放路径与实时路径
 * 走的是同一套 [com.uav.protocol.AdbMessage] 解析代码，不存在"两套解析"的漂移风险。
 */
class FileByteChannel(
    private val file: File,
    private val logger: Logger = com.uav.host.AndroidLogger,
    /** 是否循环回放（文件读完后从头再来）。 */
    private val loop: Boolean = false,
) : ByteChannel {

    override val label: String = "file:${file.name}"

    private val raf: RandomAccessFile = RandomAccessFile(file, "r")

    private var closed = false
    private var loops = 0

    /** 文件总长（字节），用于 UI 显示进度。 */
    val totalBytes: Long = raf.length()

    /** 已回放字节数。 */
    var bytesRead: Long = 0
        private set

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("$label closed")
        if (len == 0) return 0

        var n = raf.read(dst, off, len)
        if (n <= 0) {
            if (loop) {
                loops++
                raf.seek(0)
                logger.i(TAG, "$label 回放完成，第 $loops 次循环")
                n = raf.read(dst, off, len)
            }
            if (n <= 0) {
                logger.i(TAG, "$label 回放结束（共 $bytesRead 字节）")
                return -1
            }
        }
        bytesRead += n.toLong()
        return n
    }

    override fun write(src: ByteArray, off: Int, len: Int) {
        // 回放通道是只读的；写入静默丢弃，避免把回放会话的应答写坏文件
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { raf.close() }
    }

    fun progressText(): String =
        if (totalBytes <= 0) "$label" else "$label ${bytesRead * 100 / totalBytes}% ($bytesRead/$totalBytes)"

    companion object {
        const val TAG = "UAV-Host"
    }
}
