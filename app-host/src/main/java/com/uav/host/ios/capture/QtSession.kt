package com.uav.host.ios.capture

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.uav.protocol.ios.SampleParser
import com.uav.protocol.ios.AudioParser
import com.uav.protocol.ios.LinkLossGate
import com.uav.protocol.ios.QtPacket
import com.uav.protocol.ios.QtProtocolCore
import com.uav.protocol.ios.QtStats
import com.uav.protocol.ios.ReadTimer
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * QT 通道 bulk IN 的单次超时（毫秒）。
 *
 * 这个值同时决定"多久算没数据"：判据在 [ReadTimer]，**读满窗口 = 超时**（链路还活着），
 * 明显更短 = 端点出错。两者原始返回值都是 -1，只能靠耗时区分。
 */
private const val READ_TIMEOUT_MS = 500

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
    private val linkAlive: (() -> Boolean)? = null,
    /**
     * ★ **Activity 级停止判据**（2026-10-19 补）。
     *
     * 为什么必须有它：读循环只看自己的 [stopRequested]，而上层要停它有两条路 ——
     * 点「停止投屏」按钮（置 [stopRequested]，这条路本来就通）、以及
     * **点返回键**（Activity 走 `onDestroy`，那时 [IosActivity.activeSession]
     * 已经被`finishRun` 置 null，`activeSession?.stopRequested = true` 变成空操作）。
     *
     * 实测后果：返回键关掉界面后后台继续无限收流（`FEED` 计数照涨），
     * USB 被长期占用、`ios-auto` 线程不退出。
     *
     * ⇒ 把 Activity 的 `stopRequested` 以**函数**形式传进来（不是快照值），
     * 读循环每轮重新求值，Activity 那边置位即刻生效。
     *
     * 不给（null）时退化成只看 [stopRequested]，行为与从前一致。
     */
    private val stopSignal: (() -> Boolean)? = null,
    /**
     * 高频日志的去重器（`key` → 是否放行）。
     *
     * SKEW 应答、渲染计数每秒好几条，而面板只有 50 条缓冲 —— 以前是界面层按前缀
     * **硬编码丢弃**（`IosDecoderHost.log` 里的 `startsWith("SKEW")`），等于让 UI 认识业务报文名。
     * 现在由产生方声明"这条是高频道"，`null` = 不限流。logcat 那边一条不少。
     */
    private val logThrottle: ((key: String, msg: String) -> Unit)? = null,
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

    /** 录制流已写坏（磁盘满等）：置位后不再尝试，投屏继续 */
    @Volatile
    private var sinkBroken = false

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
    ): QtStats {
        // ★ 读缓冲必须小：1 MiB 的 bulk IN 在 xHCI 上会排成一长串传输描述符，
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
                    val s = sink ?: return
                    if (sinkBroken) return
                    // ★ 录制是排障用的旁路，**绝不能因为它失败而打断投屏**：
                    //   磁盘满 / 存储卸载时 `write` 抛 IOException，原样冒出去会一路
                    //   冲出读循环，表现成"投屏莫名断了"。这里就地止损：关录制，继续投。
                    runCatching {
                        s.write(QtPacket.u32(payload.size))
                        s.write(payload)
                    }.onFailure {
                        sinkBroken = true
                        log("✗ 录制写入失败，已关闭录制（投屏继续）：${it.javaClass.simpleName}: ${it.message}")
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

        // ★ `shouldStop()` 把 [stopRequested] 与外部 [stopSignal] 合成一个判据，
        //   循环只认它—— 这样"谁想停"与"怎么停"就分开了（见构造参数 stopSignal 的说明）。
        fun shouldStop(): Boolean =
            stopRequested || (stopSignal?.invoke() == true)

        while (!shouldStop() && System.currentTimeMillis() < deadline) {
            val t0 = System.currentTimeMillis()
            val n = conn.bulkTransfer(epIn, readBuf, readBuf.size, READ_TIMEOUT_MS)
            val dt = System.currentTimeMillis() - t0

            if (n <= 0) {
                // Android 的 bulkTransfer 对「超时」和「端点错误」都返回 -1，
                // 只能用耗时区分：读满窗口的是超时，明显更短的是错误。
                val isTimeout = ReadTimer.isTimeout(dt, READ_TIMEOUT_MS)
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
                    // 这行每 3 秒一条，且紧跟在错误风暴后面 —— 限流，否则面板会被"空闲"刷满
                    logHot("idle", "  …空闲 ${(now - start) / 1000}s：超时 $timeouts 次 / 错误 $totalErrors 次 / 已收 ${st.bytes} B")
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
    /**
     * 高频诊断日志：走 [logThrottle] 限流（没配就原样打）。
     *
     * 只用于"每秒好几次"的排障输出（SKEW 应答、渲染计数、空闲心跳）；
     * 状态转折、错误、首帧一律走 [log]，**不能被这里挡掉**。
     */
    private fun logHot(key: String, msg: String) {
        val f = logThrottle
        if (f == null) log(msg) else f(key, msg)
    }

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

    /**
     * 发停止序列：`HPA0` → `HPD0`，然后停写线程。
     *
     * ★ **必须同步写，不能走 [write] 队列**。
     *   理由：调用点一定在"收尾"路径上 —— 紧接着就会 [stopWriter]、释放接口、close。
     *   若走队列，这两条报文会**永远留在队列里被丢掉**，等于什么都没发
     *   （这正是补这个功能时最容易犯的错：函数写了、日志打了、实际一字节没上线）。
     *
     * ★ **必须在释放接口之前调用**。顺序错了：接口一 release，OUT 端点就不可写，
     *   `bulkTransfer` 直接返回 -1，且**不报错也看不出**。
     *
     * 依据：`RayrenSX/iPhoneMirror` `docs/PROTOCOL.md` §4 结束段
     * 「停止顺序是 HPA0、HPD0、处理 STOP/RPLY 与 RELS，然后释放接口」。
     * `STOP/RELS` 是设备→主机方向，由 [run] 的读循环被动接收即可，本方法不处理。
     *
     * @return 两条各写多少字节（`-1` = 失败）。**失败不抛异常**：
     *         收尾路径不该因为"礼貌通知没送到"而中断释放。
     */
    fun sendStopSequence(
        displayWidth: Int = QtPacket.DEFAULT_DISPLAY_WIDTH,
        displayHeight: Int = QtPacket.DEFAULT_DISPLAY_HEIGHT,
    ): Pair<Int, Int> {
        val ep = epOut
        if (ep == null) {
            log("停止序列：无 OUT 端点，跳过")
            return -1 to -1
        }
        var audioBytes = -1
        var videoBytes = -1
        // ⚠️ 别用 fccString(packet, 16) 反推子类型名：偏移 16 之后是 payload
        //   （dict 的第一个键），会把 "deviceUID" 之类当成报文名打进日志。
        //   报文是自己造的，子类型名直接跟着走，最不容易错。
        for ((label, packet) in listOf(
            "ASYN/hpa0" to QtPacket.hpa0(0L),
            "ASYN/hpd0" to QtPacket.hpd0(displayWidth, displayHeight),
        )) {
            // ★ 300ms 足够：这两个包都只有几十字节，不该像 NEED 那样等 1s。
            //   超时只记日志，不重试 —— 停止报文重发没有意义。
            val w = runCatching {
                conn.bulkTransfer(ep, packet, packet.size, 300)
            }.getOrDefault(-1)
            log("  停止 $label → $w/${packet.size}B${if (w != packet.size) "（未送达）" else ""}")
            if (label.endsWith("hpa0")) audioBytes = w else videoBytes = w
        }
        stopWriter()
        return audioBytes to videoBytes
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