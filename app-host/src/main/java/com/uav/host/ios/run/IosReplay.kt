package com.uav.host.ios.run

import com.uav.protocol.ios.SampleParser
import kotlinx.coroutines.delay
import java.io.File

/**
 * 离线回放 `feed.bin`：不插 iPad 也能验证「解析 → 解码 → 上屏」整条链路。
 *
 * 只由 adb 开关进入（`am start … --ez replay true`，见 [IosActivity.applyDebugExtras]），
 * 界面不留入口 —— 它是排障工具，不是功能。
 */
internal class IosReplay(
    /** 回放的目标目录（`getExternalFilesDir(null) ?: filesDir`）。 */
    private val dir: File,
    private val log: (String) -> Unit,
    private val onCsd: (ByteArray) -> Unit,
    private val onFrame: (ByteArray) -> Unit,
) {

    /**
     * 跑一遍回放。文件缺失 / 太短 / 报文头坏了都只打一行日志，不抛异常 ——
     * 它是排障工具，不该把自动流程搞崩。
     */
    suspend fun play() {
        val f = File(dir, FEED_NAME)
        if (!f.exists() || f.length() < MIN_BYTES) {
            log("没有可回放的 feed.bin —— 先用 adb 录一次：$RECORD_HINT")
            return
        }
        log("回放 ${f.absolutePath}（${f.length()} B）")
        runCatching {
            f.inputStream().buffered().use { ins ->
                var n = 0
                var sent = 0
                while (true) {
                    val hdr = ByteArray(4)
                    if (ins.read(hdr) != 4) break
                    // 落盘时用的是 QtPacket.u32（**小端**），这里必须按小端读回
                    val total = (hdr[3].toInt() and 0xFF shl 24) or (hdr[2].toInt() and 0xFF shl 16) or
                        (hdr[1].toInt() and 0xFF shl 8) or (hdr[0].toInt() and 0xFF)
                    if (total <= 0 || total > MAX_PACKET_BYTES) break
                    val payload = ByteArray(total)
                    var off = 0
                    while (off < total) {
                        val r = ins.read(payload, off, total - off)
                        if (r <= 0) break
                        off += r
                    }
                    if (off < total) break
                    n++
                    SampleParser.csd(payload)?.let(onCsd)
                    SampleParser.frame(payload)?.let { fr ->
                        onFrame(fr)
                        sent++
                    }
                    // 每 30 帧歇一帧的时长：太快会把解码器喂爆，肉眼也看不出"在放"
                    if (sent % (SLEEP_EVERY) == 0) delay(16)
                    if (sent >= MAX_FRAMES) break
                }
                log("回放结束：包 $n / 送解码 $sent")
            }
        }.onFailure { log("回放异常：${it.message}") }
    }

    private companion object {
        const val FEED_NAME = "feed.bin"

        /** 小于 1KB 的文件不可能是有效录制（连一个报文头都不够）。 */
        const val MIN_BYTES = 1024L

        /** 单包上限 8 MB：再大只能说明文件坏了，继续读会 OOM。 */
        const val MAX_PACKET_BYTES = 8 shl 20

        const val MAX_FRAMES = 600
        const val SLEEP_EVERY = 30

        const val RECORD_HINT =
            "am start -n com.uav.host/.ios.IosActivity --ez record true"
    }
}