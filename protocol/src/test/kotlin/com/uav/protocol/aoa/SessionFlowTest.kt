package com.uav.protocol.aoa

import com.uav.protocol.Bytes
import com.uav.protocol.MemoryPipe
import com.uav.protocol.adb.AdbMessage
import com.uav.protocol.bytes
import com.uav.protocol.media.AnnexB
import com.uav.protocol.media.AudioSpec
import com.uav.protocol.media.CodecId
import com.uav.protocol.media.Options
import com.uav.protocol.media.ParsedOpen
import com.uav.protocol.media.Scale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **端到端协议联调**：两端各跑一个真实 [Connection]，中间用内存管道相连，
 * 完整走一遍 `CNXN → CNXN → OPEN → OKAY → 流头 → CONFIG → 普通帧`。
 *
 * 这是"不占真机就能发现两端不一致"的最有效手段 —— 计划第 7 节的离线回放
 * 想解决的问题，在协议层可以进一步用这个测试直接覆盖：
 *
 *  - **本用例通过** ⇒ 命令语义表、流分流、帧编码、Options 协商、CONFIG 传递全部自洽；
 *    真机联调时只剩 USB 层与编解码层的变量。
 *  - **本用例失败** ⇒ 一定是协议冻结清单被改坏了，**根本不必上真机**。
 */
class SessionFlowTest {

    /** 模拟手机端的极简实现：收到 CNXN 回 CNXN；收到 OPEN 回 OKAY 并推流。 */
    private class FakeAccessory(
        private val transport: Transport,
        private val screenWidth: Int,
        private val screenHeight: Int,
        private val header: StreamHeader,
        private val configPayload: ByteArray,
        private val frames: List<Pair<Long, ByteArray>>,
        /** 回报的 `CNXN.arg1`（= 配件端 versionCode）。默认 0 = 老包，不发版本。 */
        private val appVersion: Int = 0,
    ) {
        val gotOpen = AtomicReference<ParsedOpen?>()
        val openLatch = CountDownLatch(1)
        private var conn: Connection? = null

        fun start() {
            val c = Connection(transport)
            conn = c
            c.setListener(object : ConnectionListener {
                override fun onMessage(message: AdbMessage) {
                    when (message.commandType) {
                        Command.CNXN -> {
                            val payload = Bytes.intLE(screenWidth) + Bytes.intLE(screenHeight)
                            c.send(Command.CNXN, Protocol.VERSION, appVersion, payload)
                        }

                        Command.OPEN -> {
                            val parsed = Options.parsePayload(message.payload)
                            gotOpen.set(parsed)
                            c.sendOkay(StreamId.CONTROL)
                            streamVideo()
                            openLatch.countDown()
                        }

                        else -> Unit
                    }
                }

                override fun onError(cause: Throwable) = Unit
                override fun onClosed(reason: String) = Unit
            })
            c.start()
        }

        private fun streamVideo() {
            val c = conn ?: return
            // 该流首条 WRTE = 12B 流头
            c.send(Command.WRTE, StreamId.VIDEO, 0, header.encode())
            // CONFIG 帧（SPS+PPS 整体）
            c.send(
                Command.WRTE, StreamId.VIDEO, 0,
                FrameCodec.encodeFrame(Flags.pack(0, config = true), configPayload),
            )
            // 普通帧
            for ((pts, data) in frames) {
                val flag = Flags.pack(pts, keyFrame = pts == frames.first().first)
                c.send(Command.WRTE, StreamId.VIDEO, 0, FrameCodec.encodeFrame(flag, data))
            }
        }

        fun close() {
            conn?.close()
        }
    }

    @Test
    fun `完整会话在协议层走通`() {
        val (hostSide, accSide) = MemoryPipe.pair()

        val hostTransport = Transport(hostSide)
        val accTransport = Transport(accSide)

        // 手机端假实现：1080x2400 屏，输出 720x1600
        val videoHeader = StreamHeader(CodecId.AVC1, 720, 1600)
        val spsPps = bytes(
            0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E, // SPS
            0, 0, 0, 1, 0x68, 0xCE, 0x38, 0x80, // PPS
        )
        val frames = listOf(
            1000L to bytes(1, 2, 3, 4, 5),
            2000L to bytes(6, 7),
            3000L to bytes(8, 9, 10),
        )
        val accessory = FakeAccessory(
            accTransport, 1080, 2400, videoHeader, spsPps, frames,
        ).also { it.start() }

        // ---- 宿主端：用真实 Connection + FrameQueue ----
        val hostConn = Connection(hostTransport)
        val videoQueue = FrameQueue(StreamId.VIDEO)
        hostConn.registerSink(StreamId.VIDEO, videoQueue)

        val phoneCnxn = CountDownLatch(1)
        val okayLatch = CountDownLatch(1)
        val phoneSize = AtomicReference<Pair<Int, Int>>()
        hostConn.setListener(object : ConnectionListener {
            override fun onMessage(message: AdbMessage) {
                when (message.commandType) {
                    Command.CNXN -> {
                        phoneSize.set(
                            Bytes.getIntLE(message.payload, 0) to Bytes.getIntLE(message.payload, 4)
                        )
                        phoneCnxn.countDown()
                    }

                    Command.OKAY -> okayLatch.countDown()
                    else -> Unit
                }
            }

            override fun onError(cause: Throwable) = Unit
            override fun onClosed(reason: String) = Unit
        })
        hostConn.start()

        // 1) 宿主发 CNXN（本机 1080x2400）
        hostConn.send(
            Command.CNXN, Protocol.VERSION, 0,
            Bytes.intLE(1080) + Bytes.intLE(2400),
        )

        // 2) 等到手机 CNXN
        assertTrue("未收到手机 CNXN", phoneCnxn.await(3, TimeUnit.SECONDS))
        assertEquals(1080 to 2400, phoneSize.get())

        // 3) 宿主算 max_size 并发 OPEN
        val maxSize = Scale.computeMaxSize(1080, 2400, 1080, 2400)
        assertEquals(2400, maxSize)
        val options = Options.defaultOptions(maxSize)
        hostConn.send(Command.OPEN, StreamId.CONTROL, 0, Options.buildPayload(options))

        // 4) 等到 OKAY
        assertTrue("未收到 OKAY", okayLatch.await(3, TimeUnit.SECONDS))

        // 5) 手机端解析到的参数必须与宿主下发的完全一致
        val parsed = accessory.gotOpen.get()
        assertNotNull(parsed)
        assertEquals(StreamId.VIDEO, parsed!!.videoId)
        assertEquals(StreamId.AUDIO, parsed.audioId)
        assertEquals(maxSize, parsed.maxSize)
        assertEquals(8_000_000, parsed.videoBitRate)
        assertEquals(30, parsed.maxFps)
        assertTrue("10.13 起宿主默认请求音频", parsed.audio)
        assertEquals(AudioSpec.DEFAULT_BIT_RATE, parsed.options.audioBitRate)

        // 6) 流头
        val deadline = System.currentTimeMillis() + 3_000
        while (videoQueue.header == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(videoHeader, videoQueue.header)
        assertEquals(720, videoQueue.header!!.width)
        assertEquals(1600, videoQueue.header!!.height)

        // 7) CONFIG 帧必须到达且整体可注入 csd-0
        val configFrame = videoQueue.take(3_000)
        assertNotNull("未收到 CONFIG 帧", configFrame)
        assertTrue("首帧必须是 CONFIG", configFrame!!.isConfig)
        assertArrayEquals(spsPps, configFrame.data)
        // 兜底路径也要可用
        assertEquals(2, AnnexB.split(configFrame.data).size)

        // 8) 普通帧按序到达
        val got = (0 until 3).map { videoQueue.take(3_000) }
        assertEquals(3, got.filterNotNull().size)
        assertTrue(got[0]!!.isKeyFrame)
        assertArrayEquals(bytes(1, 2, 3, 4, 5), got[0]!!.data)
        assertEquals(1000L, got[0]!!.pts)
        assertEquals(2000L, got[1]!!.pts)
        assertEquals(3000L, got[2]!!.pts)

        // 9) 统计口径：frames 只算媒体帧（CONFIG 不计，否则帧率压测会偏大），
        //    bytes 是链路口径，含 CONFIG 的 payload
        assertEquals(3L, hostConn.stats.frames(StreamId.VIDEO))
        assertEquals(1L, hostConn.stats.configFrames(StreamId.VIDEO))
        assertEquals((5 + 2 + 3 + spsPps.size).toLong(), hostConn.stats.bytes(StreamId.VIDEO))

        hostConn.close()
        accessory.close()
    }

    @Test
    fun `两端口径不一致会被立刻发现 - codecId 写成 h264 而宿主按 avc1 断言`() {
        // 这个用例刻意演示"冻结清单被改坏"时测试会怎样报警：
        // 若有人把 CodecId.AVC1 改成 scrcpy 风格的 "h264"，这里会失败。
        assertEquals("avc1", CodecId.AVC1)
        assertEquals("avc1", StreamHeader(CodecId.AVC1, 720, 1600).codecId)
    }

    @Test
    fun `流头尺寸是唯一权威来源而非 SPS 解析`() {
        // 流头声明 720x1600，VUI 里的实际画布可能是别的值 —— 宿主只用流头，不解析 SPS
        val header = StreamHeader(CodecId.AVC1, 720, 1600)
        val decoded = StreamHeader.decode(header.encode())
        assertEquals(720, decoded.width)
        assertEquals(1_600, decoded.height)
        assertEquals(720f / 1600f, decoded.aspectRatio, 1e-6f)
    }

    // ---------------------------------------------------------------- 握手版本协商（10.39）

    @Test
    fun `旧配件端不发版本号时宿主照常完成会话`() {
        // appVersion 默认 0 = 老包。宿主必须把它当成"老包"而不是"版本冲突"，
        // 否则一升级宿主，所有没升级的手机就全废了 —— 这是硬约束。
        runFlowWithPeerVersion(0)
    }

    @Test
    fun `协议层不因版本差异阻断消息流`() {
        // 这一层只负责搬字节，**版本策略在上层**（HostSession 用 AppVersions.hostCanServe 判定）：
        // 对端更新时宿主会拒绝，但那是在 CNXN 之后由 sessionFlow 收口的，不是 Connection 的事。
        runFlowWithPeerVersion(99)
    }

    /** 走完整会话，只断言"能不能连通并出帧"，版本号只影响提示。 */
    private fun runFlowWithPeerVersion(peerAppVersion: Int) {
        val (hostSide, accSide) = MemoryPipe.pair()
        val hostTransport = Transport(hostSide)
        val accTransport = Transport(accSide)

        val videoHeader = StreamHeader(CodecId.AVC1, 720, 1600)
        val spsPps = bytes(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E)
        val frames = listOf(1000L to bytes(1, 2, 3, 4))
        val accessory = FakeAccessory(
            accTransport, 1080, 2400, videoHeader, spsPps, frames,
            appVersion = peerAppVersion,
        ).also { it.start() }

        val hostConn = Connection(hostTransport)
        val videoQueue = FrameQueue(StreamId.VIDEO)
        hostConn.registerSink(StreamId.VIDEO, videoQueue)

        val peerApp = AtomicReference<Int>()
        val phoneCnxn = CountDownLatch(1)
        val okayLatch = CountDownLatch(1)
        hostConn.setListener(object : ConnectionListener {
            override fun onMessage(message: AdbMessage) {
                when (message.commandType) {
                    Command.CNXN -> {
                        peerApp.set(message.arg1)
                        phoneCnxn.countDown()
                    }

                    Command.OKAY -> okayLatch.countDown()
                    else -> Unit
                }
            }

            override fun onError(cause: Throwable) = Unit
            override fun onClosed(reason: String) = Unit
        })
        hostConn.start()

        hostConn.send(Command.CNXN, Protocol.VERSION, 2, Bytes.intLE(1080) + Bytes.intLE(2400))
        assertTrue("未收到手机 CNXN", phoneCnxn.await(3, TimeUnit.SECONDS))
        assertEquals("arg1 应原样透传", peerAppVersion, peerApp.get())

        hostConn.send(
            Command.OPEN, StreamId.CONTROL, 0,
            Options.buildPayload(Options.defaultOptions(2400)),
        )
        assertTrue("未收到 OKAY —— 版本不同不该阻断握手", okayLatch.await(3, TimeUnit.SECONDS))

        // 能出帧才算真兼容（不只看握手）
        assertTrue("版本不同不该阻断出帧", videoQueue.take(3_000) != null)

        hostConn.close()
        accessory.close()
    }
}
