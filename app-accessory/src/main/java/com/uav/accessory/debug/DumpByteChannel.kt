package com.uav.accessory.debug

import com.uav.accessory.AndroidLogger
import com.uav.protocol.ByteChannel
import com.uav.protocol.Logger
import com.uav.protocol.i
import com.uav.protocol.w
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 抓流：把**发出去**的原始字节同时落盘成 `.uav`（计划 3.12 / 第 7 节）。
 *
 * 关键设计：它包装在 [ByteChannel] 这一层，`write` 时原样转发并顺手追加到文件 ——
 * 于是落盘的字节**就是 USB 线上的真实字节**（24B 报文头 + payload 逐条拼接），
 * 宿主侧 [com.uav.host.debug.FileByteChannel] 可以直接当报文流回放，
 * **实时路径与回放路径共用同一套解析代码**，不存在"两套解析"漂移的风险。
 *
 * 只 tee **写**方向：回放只需要"手机发给宿主"的那一半。
 */
class DumpByteChannel(
    private val delegate: ByteChannel,
    dumpFile: File,
    private val logger: Logger = AndroidLogger,
    /** 是否同时把落盘内容打进日志（头部若干字节），联调时很有用。 */
    private val verbose: Boolean = false,
) : ByteChannel {

    override val label: String get() = "${delegate.label}+dump"

    private val out: FileOutputStream? = try {
        dumpFile.parentFile?.mkdirs()
        FileOutputStream(dumpFile, false).also {
            logger.i(TAG, "抓流已开启 -> ${dumpFile.absolutePath}")
        }
    } catch (t: Throwable) {
        logger.w(TAG, "无法创建抓流文件 ${dumpFile.absolutePath}: ${t.message}，仅转发不落盘")
        null
    }

    val dumpedBytes = AtomicLong()

    override fun read(dst: ByteArray, off: Int, len: Int): Int = delegate.read(dst, off, len)

    override fun write(src: ByteArray, off: Int, len: Int) {
        delegate.write(src, off, len)
        val o = out ?: return
        try {
            o.write(src, off, len)
            dumpedBytes.addAndGet(len.toLong())
            if (verbose) logger.i(TAG, "dump ${len}B")
        } catch (t: Throwable) {
            logger.w(TAG, "写抓流文件失败（已忽略，不影响实时链路）: ${t.message}")
        }
    }

    override fun close() {
        runCatching { out?.flush() }
        runCatching { out?.close() }
        if (out != null) logger.i(TAG, "抓流已结束，共 $dumpedBytes 字节")
        delegate.close()
    }

    fun isDumping(): Boolean = out != null

    companion object {
        const val TAG = "UAV-Accessory"
    }
}
