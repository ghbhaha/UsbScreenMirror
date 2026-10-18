package com.uav.host.ios

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.uav.protocol.ios.SampleParser
import com.uav.protocol.ios.AudioParser
import com.uav.protocol.ios.LinkLossGate
import com.uav.protocol.ios.QtPacket
import com.uav.protocol.ios.QtProtocolCore
import com.uav.protocol.ios.QtStats
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

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
    private val requestAudio: Boolean = false,
    /**
     * 设备**是否还在系统设备表里**（拔线检测的权威判据）。
     *
     * 读循环只能看到「端点返回 -1」，而 Android 对超时和端点错误都用 -1 表示，
     * 靠耗时区分也只是启发式。真正能一锤定音的是 `UsbManager.deviceList` 里还有没有它。
     * 不给（null）时退化成纯计数判据。
     */
    private val linkAlive: (() -> Boolean)? = null
) {

    private var tail = ByteArray(0)

    /** 外部停止信号（点「停止投屏」时置位）。 */
    @Volatile
    var stopRequested = false

    /**
     * 非正常结束的原因（设备被拔 / 端点连续失效）；`null` = 正常停止或因 idle 提前返回。
     *
     * 上层用它决定 UI 文案：拔线是**用户的动作**（回空闲 + 提示插回），
     * 端点坏了才是**错误**（红药丸）。两者混在一起会让"拔线"看起来像故障。
     */
    @Volatile
    var abortReason: String? = null
        private set

    // ── 写路径：接收线程只入队，绝不等待 ────────────────────────────────────────
    // 参考实现（iPhoneMirror CaptureSession.cpp）的注释说得很直白：这是 USB 接收线程，
    // 绝不能在这里等——哪怕 20ms 的等待都会让 NEED/clock 应答变慢，某些 iOS 版本
    // 会因此直接结束 QuickTime 流。我们原来是同步写，一次 bulkTransfer 最多阻塞 1000ms。
    private val writeLock = ReentrantLock()
    private val writeCond = writeLock.newCondition()
    private val writeQueue = ArrayDeque<ByteArray>()
    private val writeQueueCap = 256
    @Volatile
    private var writerStop = false
    private var writerThread: Thread? = null

    /** 诊断：写队列满而丢弃的包数 / 单次写最长阻塞时间 */
    private var writeDropped = 0
    private var writeFailures = 0
    @Volatile
    private var writeMaxBlockMs = 0L

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
    ): QtStats {
        // 写线程与读循环同生共死：任何一条返回路径（含异常）都要收掉它
        abortReason = null
        startWriter()
        try {
            return runInner(maxSeconds, maxFrames, idleAbortSeconds)
        } finally {
            stopWriter()
        }
    }

    private fun runInner(
        maxSeconds: Int,
        maxFrames: Int = Int.MAX_VALUE,
        idleAbortSeconds: Int = 0
    ): QtStats {        // ★ 读缓冲必须小：1 MiB 的 bulk IN 在 xHCI 上会排成一长串传输描述符，
        //   而设备最先发的是 16B 的 PING（短包）——短包会让整串立即取消，
        //   取消 URB 正是会把设备端点弄坏的动作（本项目已踩过）。
        //   Windows 上 libusb0 没有这种语义，所以 1 MiB 在那边照样能跑。
        val readBuf = ByteArray(16 * 1024)
        val start = System.currentTimeMillis()
        val deadline = if (maxSeconds > 0) start + maxSeconds * 1000L else Long.MAX_VALUE
        val idleDeadline = if (idleAbortSeconds > 0) start + idleAbortSeconds * 1000L else Long.MAX_VALUE

        // ★ 协议状态机在 `:protocol/ios/QtProtocolCore` —— 本类只负责 USB 收发与线程。
        //   这样"入站报文 → 出站字节"的映射可以在 JVM 单测里钉住，不必插着 iPad 试。
        val core = QtProtocolCore(
            enqueue = { write(it) },
            writeNow = { data -> epOut?.let { conn.bulkTransfer(it, data, data.size, 1000) } ?: -1 },
            log = log,
            requestAudio = requestAudio,
            // 没有帧回调时不解析 NAL（纯抓包/探测场景省 CPU）—— 与改造前行为一致
            parseFrames = onFrame != null,
            startedAtMillis = start,
            listener = object : QtProtocolCore.Listener {
                override fun onCsd(csd: ByteArray) {
                    onCsd?.invoke(csd)
                }

                override fun onFrame(annexB: ByteArray) {
                    onFrame?.invoke(annexB)
                }

                override fun onAudio(pcm: ByteArray, format: AudioParser.Format) {
                    onAudio?.invoke(pcm, format)
                }

                /** 抓包转储：写 `[4B 长度][原始报文]`，与改造前逐字节一致 */
                override fun onFeedRaw(payload: ByteArray) {
                    sink?.let {
                        it.write(QtPacket.u32(payload.size))
                        it.write(payload)
                    }
                }
            },
        )
        val st = core.stats

        var timeouts = 0
        var lastReport = start
        var totalErrors = 0
        var lastErrorLog = 0L
        // 判据在 `:protocol`（阈值边界必须可单测，见 LinkLossGateTest）
        val lossGate = LinkLossGate()

        while (!stopRequested && System.currentTimeMillis() < deadline) {
            val t0 = System.currentTimeMillis()
            val n = conn.bulkTransfer(epIn, readBuf, readBuf.size, 500)
            val dt = System.currentTimeMillis() - t0

            if (n <= 0) {
                // Android 的 bulkTransfer 对「超时」和「端点错误」都返回 -1，
                // 只能用耗时区分：接近 timeout 的是超时，明显更短的是错误。
                val isTimeout = dt >= 450
                val now = System.currentTimeMillis()

                if (isTimeout) {
                    timeouts++
                    lossGate.onTimeout()
                } else {
                    // ★★ 这里是「拔掉 iPad 后界面卡死」的现场：
                    //    设备消失后 bulkTransfer 会以 ~0ms 返回 -1，若不加限流和出口，
                    //    这个分支就变成 1ms 一次的死循环 —— 而且每次都 log() 一次，
                    //    每条 log 都要在 UI 线程把上百行文本重新拼接 + fullScroll，
                    //    几秒内就把主线程消息队列彻底淹掉 ⇒ 界面看起来完全卡死。
                    totalErrors++
                    val cause = lossGate.onFastFailure(
                        // 只在失败时才查一次设备表（一次遍历，几微秒），
                        // 拿到就足以区分"拔线"和"端点坏了"
                        devicePresent = linkAlive?.invoke()
                    )
                    // 前 3 条如实打印，之后每 3 秒汇总一次（排障够用，且不会刷屏）
                    if (totalErrors <= 3 || now - lastErrorLog >= 3000) {
                        lastErrorLog = now
                        log(
                            "读错误 n=$n（${dt}ms，连续 ${lossGate.consecutiveErrors} 次" +
                                " / 累计 $totalErrors 次）"
                        )
                    }
                    if (cause != null) {
                        abortReason = when (cause) {
                            LinkLossGate.Cause.DEVICE_GONE ->
                                "iPad 已从 USB 设备表消失（线被拔掉 / 设备断电）"
                            LinkLossGate.Cause.ENDPOINT_DEAD ->
                                // 拔线也常落到这一支：USB 子系统清理设备表比端点失效慢几十毫秒，
                                // 8 次失败（约 20ms）内 `findAppleDevice()` 往往还看得到它。
                                // 所以别写死"端点失效"，否则日志会把人往错方向带。
                                "USB 端点连续 ${lossGate.consecutiveErrors} 次读失败（设备无响应 / 可能已被拔掉）"
                        }
                        log("✗ 链路中断：$abortReason")
                        return st
                    }
                }
                if (now - lastReport >= 3000) {
                    lastReport = now
                    log("  …空闲 ${(now - start) / 1000}s：超时 $timeouts 次 / 错误 $totalErrors 次 / 已收 ${st.bytes} B")
                }
                // 开头一直没有数据 ⇒ 设备侧没武装，别在这里空等
                if (st.bytes == 0L && now >= idleDeadline) {
                    log("  ${idleAbortSeconds}s 内没有任何数据，交回上层决定是否重新唤醒")
                    return st
                }
                continue
            }
            lossGate.onData()
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
                if (payload.size >= 4) core.handle(payload)
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
     * 把一包数据交给写线程 —— **本函数不会阻塞**。
     *
     * 调用点全在接收线程里（PING/CWPA/CVRP/FEED…），同步写会让读侧跟着停。
     */
    private fun write(data: ByteArray) {
        if (epOut == null) return
        writeLock.lock()
        try {
            // 队列满说明 OUT 已经跟不上：丢最老的（NEED 可以重发，时钟应答也会重来），
            // 保住最新的一包，别让新应答排在几百个旧包后面。
            if (writeQueue.size >= writeQueueCap) {
                writeQueue.removeFirst()
                writeDropped++
            }
            writeQueue.addLast(data)
            writeCond.signalAll()
        } finally {
            writeLock.unlock()
        }
    }

    private fun startWriter() {
        val ep = epOut ?: return
        writerStop = false
        writerThread = Thread({
            while (true) {
                var item: ByteArray? = null
                writeLock.lock()
                try {
                    while (writeQueue.isEmpty() && !writerStop) {
                        writeCond.await(200, TimeUnit.MILLISECONDS)
                    }
                    if (writeQueue.isNotEmpty()) item = writeQueue.removeFirst()
                } catch (_: InterruptedException) {
                    // 收尾时被打断：按停止处理
                } finally {
                    writeLock.unlock()
                }
                if (item == null) {
                    if (writerStop) break else continue
                }
                val t0 = System.currentTimeMillis()
                val w = conn.bulkTransfer(ep, item, item.size, 1000)
                val dt = System.currentTimeMillis() - t0
                if (dt > writeMaxBlockMs) writeMaxBlockMs = dt
                if (w != item.size) {
                    writeFailures++
                    if (writeFailures <= 5) {
                        log("✗ 写失败 ${QtPacket.fccString(item, 4)} 期望 ${item.size}B 实际 $w" +
                            if (w < 0) "（端点可能已 STALL）" else "")
                    }
                }
            }
        }, "qt-writer").apply { start() }
    }

    private fun stopWriter() {
        if (writerThread == null) return
        writeLock.lock()
        try {
            writerStop = true
            writeCond.signalAll()
        } finally {
            writeLock.unlock()
        }
        writerThread?.join(2000)
        writerThread = null
        val left = writeLock.withLockCount()
        log("写线程收尾：最长单次阻塞 ${writeMaxBlockMs}ms / 丢弃 $writeDropped / " +
            "失败 $writeFailures / 残余队列 $left")
    }

    private fun ReentrantLock.withLockCount(): Int {
        lock()
        return try { writeQueue.size } finally { unlock() }
    }
}
