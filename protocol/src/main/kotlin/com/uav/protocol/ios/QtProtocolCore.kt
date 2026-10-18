package com.uav.protocol.ios

/**
 * QuickTime 会话的**协议状态机**（不含任何 USB / 线程代码）。
 *
 * ## 为什么把它从 `QtSession` 里抽出来
 *
 * 这段约 140 行的 `when` 是全项目**踩坑最密**的地方，几乎每条分支都对应一次真机事故：
 * CWPA 要 `HPD1×2`、CVRP 回 `NEED`+clock、AFMT 必须应答、SKEW 要回 48000、
 * OG 的 fourcc 是 `go! ` 不是 `og! `、FEED 尾部的 avcC 才是 SPS/PPS 的来源……
 *
 * 而它原来和 `UsbDeviceConnection`、写队列、读循环缠在同一个类里，结果是**只能真机试**：
 * 想验证"CWPA 之后到底回了什么字节"，得插上 iPad 抓 USB。
 *
 * 抽出来之后：入站报文 → 出站字节的映射可以完全在 JVM 单测里钉住
 * （见 `QtProtocolCoreTest`，用真机抓的 `asyn-eat` 样本 + 构造报文）。
 *
 * ## 边界
 *
 * - **不做任何阻塞 I/O**：出站通过注入的 [enqueue]（队列写）/ [writeNow]（同步写）完成；
 * - **不管连接与生命周期**：由宿主决定何时喂报文、何时丢弃本对象；
 * - **不做缓冲拼包**：调用方负责把长度前缀切好，本类只处理"一个完整报文"。
 */
class QtProtocolCore(
    /**
     * 出站（非阻塞，进写队列）。
     *
     * 绝大多数应答走这里：调用点在接收线程上，同步写会让读侧跟着停，
     * 一次 `bulkTransfer` 最多阻塞 1000ms —— 某些 iOS 版本会因此直接结束流。
     */
    private val enqueue: (ByteArray) -> Unit,
    /** 出站（同步写），仅 `PING` 用；返回实际写入字节数（`-1` = 失败），仅用于日志。 */
    private val writeNow: (ByteArray) -> Int,
    private val log: (String) -> Unit,
    /** 是否向设备声明音频能力（`ASYN/HPA1`）。关掉时 CWPA 只回 `HPD1×2+clock`。 */
    private val requestAudio: Boolean = false,
    /** 是否需要视频帧解析。`false` 时**不解析 NAL**（省 CPU，纯抓包场景）。 */
    private val parseFrames: Boolean = true,
    /** `EAT!` 里没有 `fdsc` 时的兜底格式。 */
    private val fallbackAudioFormat: AudioParser.Format = AudioParser.DEFAULT_FORMAT,
    /** 会话起始时刻，用于 `SYNC/time` 应答的纳秒时间戳。 */
    private val startedAtMillis: Long,
    /** 可注入的时钟（单测用来钉住 `time` 应答的字节）。 */
    private val now: () -> Long = { System.currentTimeMillis() },
    private val listener: Listener,
) {

    interface Listener {
        /** 解出 SPS/PPS（**内容变化时才会调用**，转屏会带来新的）。 */
        fun onCsd(csd: ByteArray)

        /** 一帧视频（Annex-B，每个 NAL 前带 `00 00 00 01`）。 */
        fun onFrame(annexB: ByteArray)

        /** 一帧音频（裸 PCM + 设备声明的格式）。 */
        fun onAudio(pcm: ByteArray, format: AudioParser.Format)

        /** FEED 原始报文，仅供抓包转储。默认忽略。 */
        fun onFeedRaw(payload: ByteArray) {}
    }

    val stats = QtStats()

    /** 上一次交给上层的 SPS/PPS —— 内容变了（iPad 转屏）才需要重建解码器。 */
    private var lastCsd: ByteArray? = null

    private var audioClock = 0L
    private var videoClock = 0L

    /** 处理**一个完整报文**（长度前缀已由调用方剥掉）。 */
    fun handle(p: ByteArray) {
        when {
            QtPacket.matches(p, 0, "ping") -> {
                stats.ping++
                log("PING#${stats.ping} 收到 ${p.size}B: " + p.joinToString(" ") { "%02x".format(it) })
                // ★ PING 是唯一**同步**写：它是设备等着的握手，不能排在队列后面。
                val w = writeNow(QtPacket.ping())
                log("PING#${stats.ping} → 回写 $w B")
            }

            QtPacket.matches(p, 0, "sync") && p.size >= 16 -> {
                val corr = QtPacket.u64At(p, 16)
                when {
                    QtPacket.matches(p, 12, "cwpa") && p.size >= 32 -> {
                        stats.cwpa++
                        audioClock = QtPacket.u64At(p, 24)
                        // ★ 每个 CWPA 都发 HPD1×2 —— 与参考实现逐字节一致。
                        //   曾经为"免得包数翻倍"改成整个会话只发一轮，但从那以后音频再没出现过；
                        //   设备自己会连发两次 CWPA，对第二个只回 clock+HPA1 与参考实现不同，
                        //   而这段正是音频协商路径，不能自作聪明。
                        enqueue(QtPacket.hpd1())
                        enqueue(QtPacket.hpd1())
                        enqueue(QtPacket.clockReply(corr, audioClock + 0x1000))
                        if (requestAudio) {
                            enqueue(QtPacket.hpa1(audioClock))
                            log("CWPA#${stats.cwpa} audio=$audioClock → HPD1×2+clock+HPA1")
                        } else {
                            log("CWPA#${stats.cwpa} audio=$audioClock → HPD1×2+clock（未声明音频）")
                        }
                    }

                    QtPacket.matches(p, 12, "cvrp") && p.size >= 32 -> {
                        stats.cvrp++
                        videoClock = QtPacket.u64At(p, 24)
                        enqueue(QtPacket.need(videoClock))
                        enqueue(QtPacket.clockReply(corr, videoClock + 0x1000af))
                        log("CVRP video=$videoClock → NEED+clock")
                    }

                    QtPacket.matches(p, 12, "afmt") -> {
                        stats.afmt++
                        // 设备在这里声明它**将要用的**音频格式；打出来便于对照本端能否解码
                        val want = AudioParser.afmtFormat(p)
                        enqueue(QtPacket.afmtReply(corr))
                        log("AFMT → reply（设备要求 $want）")
                    }

                    // ★ 设备问「当前音频时钟偏差多少」。
                    //   参考实现（iPhoneMirror 的 QuickTimeSession.cpp）回的是 **48000.0**
                    //   （= 采样率），而不是 0 —— 已按它对齐。
                    QtPacket.matches(p, 12, "skew") -> {
                        stats.skew++
                        enqueue(QtPacket.skewReply(corr, 48_000.0))
                        log("SKEW → reply(48000.0)")
                    }

                    // ★ 线上字节 20 21 6F 67 ⇒ fourcc 名是 "go! "（早先误写成 "og! "，反转后永远匹配不上）；
                    //   但它是音频开始推的前置之一（不回它，设备只推视频）。
                    QtPacket.matches(p, 12, "go! ") -> {
                        stats.og++
                        enqueue(QtPacket.ogReply(corr))
                        log("OG → reply")
                    }

                    QtPacket.matches(p, 12, "clok") -> {
                        stats.clok++
                        enqueue(QtPacket.clockReply(corr, QtPacket.u64At(p, 4) + 0x10000))
                    }

                    QtPacket.matches(p, 12, "time") -> {
                        stats.time++
                        enqueue(QtPacket.timeReply(corr, (now() - startedAtMillis) * 1_000_000))
                    }

                    else -> {
                        stats.other++
                        log("SYNC ${QtPacket.fccString(p, 12)}")
                    }
                }
            }

            QtPacket.matches(p, 0, "asyn") && p.size >= 16 -> {
                if (QtPacket.matches(p, 12, "sprp")) {
                    stats.other++
                    val body = if (p.size > 16) p.copyOfRange(16, p.size) else ByteArray(0)
                    log("ASYN sprp ${p.size}B hex=${body.joinToString(" ") { "%02x".format(it) }}")
                    SampleParser.csd(body)?.let { deliverCsd(it, "sprp") }
                } else if (QtPacket.matches(p, 12, "feed")) {
                    stats.feed++
                    stats.feedBytes += p.size
                    listener.onFeedRaw(p)
                    if (stats.feed <= 3 || stats.feed % 60 == 0) {
                        log("FEED #${stats.feed}  ${p.size} B")
                    }
                    // ★ SPS/PPS 不在 ASYN/sprp 里（那条只是 ObeyEmptyMediaMarkers 之类的键值），
                    //   而是嵌在 **FEED 样本尾部的字典**里：
                    //   `… 'datv' | 4B 长度 | 01 64 00 2a ff e1 00 10 <SPS16> 01 00 04 <PPS4>`
                    //   ⇒ 每个 FEED 都试一次，拿到就不再试。
                    SampleParser.csd(p)?.let { deliverCsd(it, "FEED 尾部") }
                    if (parseFrames) {
                        SampleParser.frame(p)?.let { listener.onFrame(it) }
                    }
                    if (videoClock != 0L) enqueue(QtPacket.need(videoClock))
                } else if (QtPacket.matches(p, 12, "eat!")) {
                    // 音频样本：与 FEED 同构的 sbuf，只是 sdat 里装裸 PCM
                    stats.eat++
                    val pcm = AudioParser.pcm(p)
                    if (pcm != null) {
                        stats.audioBytes += pcm.size
                        if (stats.eat <= 3 || stats.eat % 200 == 0) {
                            log(
                                "EAT #${stats.eat}  ${p.size}B → PCM ${pcm.size}B " +
                                    "（累计 ${stats.audioBytes} B，${AudioParser.sampleCount(p)} 样本，" +
                                    "pts=${"%.3f".format(AudioParser.ptsSeconds(p))}s）",
                            )
                        }
                        listener.onAudio(pcm, AudioParser.format(p) ?: fallbackAudioFormat)
                    } else {
                        log("EAT #${stats.eat} 结构未识别（${p.size}B）")
                    }
                } else {
                    stats.other++
                    log("ASYN ${QtPacket.fccString(p, 12)} ${p.size}B")
                }
            }

            QtPacket.matches(p, 0, "rply") -> stats.other++

            else -> {
                stats.other++
                log("未知 ${QtPacket.fccString(p, 0)} ${p.size}B")
            }
        }
    }

    /**
     * 把 SPS/PPS 交给上层 —— **只在内容变化时**交。
     *
     * iPad 转屏（竖⇄横）会让编码分辨率改变，设备随下一个 FEED 送来新的 avcC。
     * 早期这里是 `csdSent` 标志"只发一次"，于是转屏后解码器仍拿着旧分辨率的 csd：
     * 收流照旧、**渲染直接停住**（FEED 一直在来，帧数不动）。
     */
    private fun deliverCsd(csd: ByteArray, from: String) {
        val prev = lastCsd
        if (prev != null && prev.contentEquals(csd)) return
        lastCsd = csd
        if (prev == null) log("★ 从 $from 解出 SPS/PPS ${csd.size}B")
        else log("★ 从 $from 解出新的 SPS/PPS ${csd.size}B（分辨率变了，重建解码器）")
        listener.onCsd(csd)
    }
}
