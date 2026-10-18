package com.uav.iosqt

import android.hardware.usb.UsbDeviceConnection
import com.uav.protocol.ios.AudioParser
import android.hardware.usb.UsbEndpoint
import java.io.OutputStream

/**
 * QuickTime 会话状态机 + 收流。
 *
 * 对应 Windows 上已跑通的实现（tools/qt-repro/qt_stream.py），流程：
 * ```
 * 设备发 PING   → 原样回 PING
 * 设备发 PING   → 再回一次（实测 Windows 上会发两次，第二次之后才到 CWPA）
 * SYNC/CWPA     → HPD1 + clock_reply(audio+0x1000) + HPA1
 * SYNC/CVRP     → NEED + clock_reply(video+0x1000af)
 * SYNC/AFMT     → afmt_reply
 * SYNC/CLOK     → clock_reply
 * SYNC/TIME     → time_reply
 * ASYN/FEED     → 视频样本：落盘 + 回 NEED 维持推流
 * ```
 */
internal class QtSession(
    private val conn: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint?,
    private val sink: OutputStream?,
    private val log: (String) -> Unit,
    /** 收到 SPS/PPS（Annex-B）时回调：用于配置解码器 */
    private val onCsd: ((ByteArray) -> Unit)? = null,
    /** 收到一帧（Annex-B）时回调：用于送解码器 */
    private val onFrame: ((ByteArray) -> Unit)? = null,
    /** 收到一包音频 PCM 时回调（裸 PCM + 该帧在 fdsc 里声明的真实格式） */
    private val onAudio: ((ByteArray, AudioParser.Format) -> Unit)? = null,
    /**
     * 是否向设备声明"本机有音频接收能力"（`ASYN/HPA1`）。
     *
     * ⚠️ **默认关闭**，这是真机踩出来的：发了 HPA1 之后，iPad 会把音频输出路由到 USB，
     * 但本端收不到 EAT 帧，结果就是 **iPad 上点播放立刻暂停**（播放器在等一个永远不来的声道）。
     * 在没有把握真正消费掉音频之前，宁可不要音频，也不能让设备放不出声。
     */
    private val requestAudio: Boolean = false
) {

    data class Stats(
        var ping: Int = 0,
        var cwpa: Int = 0,
        var cvrp: Int = 0,
        var afmt: Int = 0,
        var og: Int = 0,
        var skew: Int = 0,
        var eat: Int = 0,
        var audioBytes: Long = 0,
        var clok: Int = 0,
        var time: Int = 0,
        var feed: Int = 0,
        var other: Int = 0,
        var bytes: Long = 0,
        var feedBytes: Long = 0
    )

    private var audioClock = 0L
    private var videoClock = 0L
    private var tail = ByteArray(0)
    /** 上一次交给上层的 SPS/PPS —— 内容变了（iPad 转屏）才需要重建解码器 */
    private var lastCsd: ByteArray? = null

    /** HPD1 是否已发过：设备会连发两次 CWPA，显示描述只该发一轮 */
    private var hpd1Sent = false

    /** 外部停止信号（点「停止投屏」时置位）。 */
    @Volatile
    var stopRequested = false

    /**
     * 持续收流。
     *
     * @param maxSeconds `<= 0` = 一直投到 [stopRequested] 被置位（投屏模式）；
     *                   `> 0` = 到点自动结束（探测/回放模式）
     * @param maxFrames 收满即停（回放用）
     * @param idleAbortSeconds 开头这么久还**一个字节都没收到**就提前返回，把"要不要重新武装"
     *                         的决定权交回上层；`<= 0` = 不提前放弃
     */
    fun run(
        maxSeconds: Int,
        maxFrames: Int = Int.MAX_VALUE,
        idleAbortSeconds: Int = 0
    ): Stats {
        val st = Stats()
        // ★ 读缓冲必须小：1 MiB 的 bulk IN 在 xHCI 上会排成一长串传输描述符，
        //   而设备最先发的是 16B 的 PING（短包）——短包会让整串立即取消，
        //   取消 URB 正是会把设备端点弄坏的动作（本项目已踩过）。
        //   Windows 上 libusb0 没有这种语义，所以 1 MiB 在那边照样能跑。
        val readBuf = ByteArray(16 * 1024)
        val start = System.currentTimeMillis()
        val deadline = if (maxSeconds > 0) start + maxSeconds * 1000L else Long.MAX_VALUE
        val idleDeadline = if (idleAbortSeconds > 0) start + idleAbortSeconds * 1000L else Long.MAX_VALUE

        var timeouts = 0
        var lastReport = start

        while (!stopRequested && System.currentTimeMillis() < deadline) {
            val t0 = System.currentTimeMillis()
            val n = conn.bulkTransfer(epIn, readBuf, readBuf.size, 500)
            val dt = System.currentTimeMillis() - t0

            if (n <= 0) {
                // Android 的 bulkTransfer 对「超时」和「端点错误」都返回 -1，
                // 只能用耗时区分：接近 timeout 的是超时，明显更短的是错误。
                val isTimeout = dt >= 450
                if (isTimeout) {
                    timeouts++
                } else {
                    log("读错误 n=$n（${dt}ms）")
                }
                val now = System.currentTimeMillis()
                if (now - lastReport >= 3000) {
                    lastReport = now
                    log("  …空闲 ${(now - start) / 1000}s：超时 $timeouts 次 / 已收 ${st.bytes} B")
                }
                // 开头一直没有数据 ⇒ 设备侧没武装，别在这里空等
                if (st.bytes == 0L && now >= idleDeadline) {
                    log("  ${idleAbortSeconds}s 内没有任何数据，交回上层决定是否重新唤醒")
                    return st
                }
                continue
            }
            st.bytes += n
            var buf = tail + readBuf.copyOf(n)
            while (buf.size >= 4) {
                val len = QtPacket.u32At(buf, 0)
                if (len < 8 || len > (8 shl 20)) {
                    log("非法包长 $len，丢弃缓冲重同步")
                    buf = ByteArray(0)
                    break
                }
                if (buf.size < len) break               // 半包，等下一次
                val payload = buf.copyOfRange(4, len)
                buf = buf.copyOfRange(len, buf.size)
                if (payload.size >= 4) handle(payload, st, start)
                if (st.feed >= maxFrames) {
                    log("已收满 $maxFrames 帧，停止")
                    tail = ByteArray(0)
                    return st
                }
            }
            tail = buf
        }
        return st
    }

    /**
     * 把 SPS/PPS 交给上层 —— **只在内容变化时**交。
     *
     * iPad 转屏（竖⇄横）会让编码分辨率改变，设备随下一个 FEED 送来新的 avcC。
     * 早期这里用 `csdSent` 标志"只发一次"，于是转屏后解码器仍拿着旧分辨率的 csd：
     * 收流照旧、**渲染直接停住**（FEED 一直在来，帧数不动）。
     */
    private fun deliverCsd(csd: ByteArray, from: String) {
        val prev = lastCsd
        if (prev != null && prev.contentEquals(csd)) return
        lastCsd = csd
        if (prev == null) log("★ 从 $from 解出 SPS/PPS ${csd.size}B")
        else log("★ 从 $from 解出新 SPS/PPS ${csd.size}B（分辨率变了，重建解码器）")
        onCsd?.invoke(csd)
    }

    private fun handle(p: ByteArray, st: Stats, startMs: Long) {
        when {
            QtPacket.matches(p, 0, "ping") -> {
                st.ping++
                log("PING#${st.ping} 收到 ${p.size}B: " + p.joinToString(" ") { "%02x".format(it) })
                val w = epOut?.let { conn.bulkTransfer(it, QtPacket.ping(), 16, 1000) } ?: -1
                log("PING#${st.ping} → 回写 $w B")
            }

            QtPacket.matches(p, 0, "sync") && p.size >= 16 -> {
                val corr = QtPacket.u64At(p, 16)
                when {
                    QtPacket.matches(p, 12, "cwpa") && p.size >= 32 -> {
                        st.cwpa++
                        audioClock = QtPacket.u64At(p, 24)
                        // 参考实现每个 CWPA 连发两个 HPD1；设备自己也会连发两次 CWPA，
                        // 所以这里按「只发一轮」处理，免得包数翻倍。
                        if (!hpd1Sent) {
                            hpd1Sent = true
                            write(QtPacket.hpd1())
                            write(QtPacket.hpd1())
                        }
                        write(QtPacket.clockReply(corr, audioClock + 0x1000))
                        if (requestAudio) {
                            write(QtPacket.hpa1(audioClock))
                            log("CWPA audio=$audioClock → clock+HPA1（已声明音频）")
                        } else {
                            log("CWPA audio=$audioClock → clock（未声明音频，避免 iPad 播放被掐）")
                        }
                    }

                    QtPacket.matches(p, 12, "cvrp") && p.size >= 32 -> {
                        st.cvrp++
                        videoClock = QtPacket.u64At(p, 24)
                        write(QtPacket.need(videoClock))
                        write(QtPacket.clockReply(corr, videoClock + 0x1000af))
                        log("CVRP video=$videoClock → NEED+clock")
                    }

                    QtPacket.matches(p, 12, "afmt") -> {
                        st.afmt++
                        // 设备在这里声明它**将要用的**音频格式；打出来便于对照本端能否解码
                        val want = AudioParser.afmtFormat(p)
                        write(QtPacket.afmtReply(corr))
                        log("AFMT → reply（设备要求 $want）")
                    }

                    // ★ 设备问「当前音频时钟偏差多少」。
                    //   参考实现（iPhoneMirror 的 QuickTimeSession.cpp）回的是 **48000.0**
                    //   （= 采样率），而不是 0 —— 已按它对齐。
                    QtPacket.matches(p, 12, "skew") -> {
                        st.skew++
                        write(QtPacket.skewReply(corr, 48_000.0))
                        log("SKEW → reply(48000.0)")
                    }

                    // ★ 线上 fourcc 是 20 21 6F 67，读出来像 "go! "；含义未知，
                    //   但它是音频开始推的前置之一（不回它，设备只推视频）。
                    QtPacket.matches(p, 12, "og! ") -> {
                        st.og++
                        write(QtPacket.ogReply(corr))
                        log("OG → reply")
                    }

                    QtPacket.matches(p, 12, "clok") -> {
                        st.clok++
                        write(QtPacket.clockReply(corr, QtPacket.u64At(p, 4) + 0x10000))
                    }

                    QtPacket.matches(p, 12, "time") -> {
                        st.time++
                        write(QtPacket.timeReply(corr, (System.currentTimeMillis() - startMs) * 1_000_000))
                    }

                    else -> {
                        st.other++
                        log("SYNC ${QtPacket.fccString(p, 12)}")
                    }
                }
            }

            QtPacket.matches(p, 0, "asyn") && p.size >= 16 -> {
                if (QtPacket.matches(p, 12, "sprp")) {
                    st.other++
                    val body = if (p.size > 16) p.copyOfRange(16, p.size) else ByteArray(0)
                    log("ASYN sprp ${p.size}B hex=${body.joinToString(" ") { "%02x".format(it) }}")
                    SampleParser.csd(body)?.let { deliverCsd(it, "sprp") }
                } else if (QtPacket.matches(p, 12, "feed")) {
                    st.feed++
                    st.feedBytes += p.size
                    sink?.let {
                        it.write(QtPacket.u32(p.size))
                        it.write(p)
                    }
                    if (st.feed <= 3 || st.feed % 60 == 0) {
                        log("FEED #${st.feed}  ${p.size} B")
                    }
                    // ★ SPS/PPS 不在 ASYN/sprp 里（那条只是 ObeyEmptyMediaMarkers 之类的键值），
                    //   而是嵌在 **FEED 样本尾部的字典**里：
                    //   `… 'datv' | 4B 长度 | 01 64 00 2a ff e1 00 10 <SPS16> 01 00 04 <PPS4>`
                    //   ⇒ 每个 FEED 都试一次，拿到就不再试。
                    SampleParser.csd(p)?.let { deliverCsd(it, "FEED 尾部") }
                    if (onFrame != null) {
                        val frame = SampleParser.frame(p)
                        if (frame != null) onFrame.invoke(frame)
                    }
                    if (videoClock != 0L) write(QtPacket.need(videoClock))
                } else if (QtPacket.matches(p, 12, "eat!")) {
                    // 音频样本：与 FEED 同构的 sbuf，只是 sdat 里装裸 PCM
                    st.eat++
                    val pcm = AudioParser.pcm(p)
                    if (pcm != null) {
                        st.audioBytes += pcm.size
                        if (st.eat <= 3 || st.eat % 200 == 0) {
                            log("EAT #${st.eat}  ${p.size}B → PCM ${pcm.size}B " +
                                "（累计 ${st.audioBytes} B，${AudioParser.sampleCount(p)} 样本，" +
                                "pts=${"%.3f".format(AudioParser.ptsSeconds(p))}s）")
                        }
                        onAudio?.invoke(pcm, AudioParser.format(p) ?: AudioPlayer.DEFAULT_FORMAT)
                    } else {
                        log("EAT #${st.eat} 结构未识别（${p.size}B）")
                    }
                } else {
                    st.other++
                    log("ASYN ${QtPacket.fccString(p, 12)} ${p.size}B")
                }
            }

            QtPacket.matches(p, 0, "rply") -> st.other++

            else -> {
                st.other++
                log("未知 ${QtPacket.fccString(p, 0)} ${p.size}B")
            }
        }
    }

    private fun write(data: ByteArray) {
        val ep = epOut ?: return
        val w = conn.bulkTransfer(ep, data, data.size, 1000)
        // 写不出去必须报出来：视频的 NEED 每帧都写，一旦 OUT 拥塞，
        // 排在后面的 HPA1/clock_reply 会静默丢掉 —— 表现就是"设备收不到我们的音频应答"。
        if (w != data.size) {
            log("✗ 写失败 ${QtPacket.fccString(data, 4)} 期望 ${data.size}B 实际 $w" +
                if (w < 0) "（端点可能已 STALL）" else "")
        }
    }
}
