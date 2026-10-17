package com.uav.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 单测：`RQID`（宿主 → 手机，请立刻吐一个 IDR，10.21）。
 *
 * ## 为什么要有这条命令
 * 宿主每次"换 Surface"（切后台→回前台）都必须新建解码器 —— `MediaCodec` 的 output surface
 * 在 `configure` 时固定、无法改绑 —— 而新解码器**没有任何参考帧**，必须先见 IDR。
 * 手机却还在按自己的 I 帧周期（3s）推 P 帧 ⇒ 宿主最长黑屏 3 秒。
 *
 * 整 GOP 尾部丢弃（[GopTailGate]）只在"新会话接入"时 arm，切后台**不是**新会话，
 * 所以必须给宿主一条主动开口的路。
 */
class RequestIdrTest {

    private class Recorder : ConnectionListener {
        val messages = java.util.Collections.synchronizedList(mutableListOf<AdbMessage>())
        val gotIdr = CountDownLatch(1)

        override fun onMessage(message: AdbMessage) {
            messages.add(message)
            if (message.commandType == Command.RQID) gotIdr.countDown()
        }

        override fun onError(cause: Throwable) = Unit
        override fun onClosed(reason: String) = Unit
    }

    // ------------------------------------------------------------------ 命令字本身

    @Test
    fun `RQID 的 wire 值就是 ASCII RQID 的小端 u32`() {
        assertEquals(Command.of("RQID"), Command.RQID.wire)
        assertEquals(0x44495152, Command.RQID.wire)
        // magic 规则对所有命令字一视同仁（含这个新增的）
        assertEquals(Command.RQID.wire xor -1, Command.RQID.magic)
    }

    @Test
    fun `RQID 能编码成报文并原样解回`() {
        val (hostSide, phoneSide) = MemoryPipe.pair()
        val host = Connection(Transport(hostSide), listener = null)
        val phone = Connection(Transport(phoneSide), listener = null)
        host.start()
        phone.start()
        try {
            val msg = AdbMessage(Command.RQID.wire, StreamId.CONTROL, 0, AdbMessage.EMPTY)
            host.transport.write(msg.encode(), 0, msg.encode().size)
            // 读回来：magic / 长度 / checksum 三道校验都要过
            val decoded = AdbMessage.readFrom(phone.transport)
            assertEquals(Command.RQID, decoded.commandType)
            assertEquals(StreamId.CONTROL, decoded.arg0)
            assertEquals(0, decoded.payload.size)
        } finally {
            host.close()
            phone.close()
        }
    }

    // ------------------------------------------------------------------ 端到端投递

    @Test
    fun `RQID 从宿主发到手机会被派发给会话监听器`() {
        val (hostSide, phoneSide) = MemoryPipe.pair()
        val hostListener = Recorder()
        val phoneListener = Recorder()

        val host = Connection(Transport(hostSide), listener = hostListener)
        val phone = Connection(Transport(phoneSide), listener = phoneListener)
        host.start()
        phone.start()
        try {
            assertTrue("RQID 应成功入队", host.send(Command.RQID, StreamId.CONTROL, 0))
            assertTrue(
                "手机端 3s 内应收到 RQID",
                phoneListener.gotIdr.await(3, TimeUnit.SECONDS),
            )
            assertEquals(Command.RQID, phoneListener.messages.first().commandType)

            // ★ 反向不应成立：这是单向命令（手机发 RQID 宿主不该当消息处理）
            assertTrue(host.send(Command.CNXN, Protocol.VERSION, 0, AdbMessage.EMPTY))
            Thread.sleep(200)
            assertTrue(
                "宿主不应收到 RQID",
                hostListener.messages.none { it.commandType == Command.RQID },
            )
        } finally {
            host.close()
            phone.close()
        }
    }

    @Test
    fun `RQID 不带 payload 也不会被当成流数据丢掉`() {
        // 回归点：dispatch 里必须把它放进 notifyMessage 那一支。
        // 若漏了，它会掉进 dispatchWrite 的默认分支 → "WRTE for unregistered stream" 路径，
        // 或者被当未知命令忽略 —— 结果都是手机端永远收不到关键帧请求。
        val (hostSide, phoneSide) = MemoryPipe.pair()
        val phoneListener = Recorder()
        val host = Connection(Transport(hostSide), listener = Recorder())
        val phone = Connection(Transport(phoneSide), listener = phoneListener)
        host.start()
        phone.start()
        try {
            repeat(3) { host.send(Command.RQID, StreamId.CONTROL, 0) }
            Thread.sleep(300)
            assertEquals(
                "三条 RQID 都应原样到达（不得被当成流头/杂散 payload 丢弃）",
                3,
                phoneListener.messages.count { it.commandType == Command.RQID },
            )
            assertNotNull(Command.fromWire(Command.RQID.wire))
        } finally {
            host.close()
            phone.close()
        }
    }
}
