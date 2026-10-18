package com.uav.iosqt

import android.hardware.usb.UsbDeviceConnection
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
    private val onFrame: ((ByteArray) -> Unit)? = null
) {

    data class Stats(
        var ping: Int = 0,
        var cwpa: Int = 0,
        var cvrp: Int = 0,
        var afmt: Int = 0,
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
                        write(QtPacket.hpd1())
                        write(QtPacket.clockReply(corr, audioClock + 0x1000))
                        write(QtPacket.hpa1(audioClock))
                        log("CWPA audio=$audioClock → HPD1+clock+HPA1")
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
                        write(QtPacket.afmtReply(corr))
                        log("AFMT → reply")
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
        if (w < 0) log("写失败（$w），端点可能已 STALL")
    }
}
