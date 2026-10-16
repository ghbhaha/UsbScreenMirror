package com.uav.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单测 1/2/3/5 的载体：24B 报文头的编解码、校验与边界（计划 1.2）。
 */
class AdbMessageTest {

    // ------------------------------------------------------------------ 单测 1

    @Test
    fun `报文往返编解码一致`() {
        val (host, phone) = MemoryPipe.pair()
        val tHost = Transport(host)
        val tPhone = Transport(phone)

        val payload = "uav-hello".toByteArray(Charsets.UTF_8)
        AdbMessage.writeTo(tHost, Command.CNXN, 1, 0, payload)

        val msg = AdbMessage.readFrom(tPhone)

        assertEquals(Command.CNXN.wire, msg.command)
        assertEquals(Command.CNXN, msg.commandType)
        assertEquals(1, msg.arg0)
        assertEquals(0, msg.arg1)
        assertEquals(payload.size, msg.payload.size)
        assertEquals("uav-hello", msg.payload.toString(Charsets.UTF_8))
        // magic = command xor 0xffffffff
        assertEquals(Command.CNXN.wire xor -1, msg.magic)
        assertEquals(AdbMessage.checksumOf(payload), msg.checksum)
        assertTrue(msg.isCommand(Command.CNXN))
    }

    @Test
    fun `空 payload 的报文也能往返`() {
        val (host, phone) = MemoryPipe.pair()
        val tHost = Transport(host)
        val tPhone = Transport(phone)

        AdbMessage.writeTo(tHost, Command.OKAY, StreamId.CONTROL, 0)

        val msg = AdbMessage.readFrom(tPhone)
        assertEquals(Command.OKAY, msg.commandType)
        assertEquals(0, msg.payload.size)
        assertEquals(0, msg.checksum)
    }

    @Test
    fun `命令字常量与 ASCII 字面量一致`() {
        assertEquals(Command.CNXN.wire, Command.of("CNXN"))
        assertEquals(Command.OPEN.wire, Command.of("OPEN"))
        assertEquals(Command.OKAY.wire, Command.of("OKAY"))
        assertEquals(Command.CLSE.wire, Command.of("CLSE"))
        assertEquals(Command.WRTE.wire, Command.of("WRTE"))
        // 小端 u32 = 首字节在最低位；"CNXN" -> 0x4E584E43
        assertEquals(0x4E584E43.toInt(), Command.CNXN.wire)
    }

    @Test
    fun `AUTH 与 STLS 已被裁剪`() {
        assertNull(Command.fromWire(Command.of("AUTH")))
        assertNull(Command.fromWire(Command.of("STLS")))
        assertEquals("AUTH", Command.removedName(Command.of("AUTH")))
        assertEquals("STLS", Command.removedName(Command.of("STLS")))
    }

    @Test
    fun `收到已裁剪的 AUTH 必须抛协议错误`() {
        val data = rawMessage(Command.of("AUTH"))
        val t = Transport(FakeByteChannel(data), readTimeoutMs = 2_000)
        val e = org.junit.Assert.assertThrows(ProtocolException::class.java) {
            AdbMessage.readFrom(t)
        }
        assertTrue(e.message!!.contains("AUTH"))
    }

    // ------------------------------------------------------------------ 单测 2

    @Test
    fun `magic 错被拒绝且不静默跳过`() {
        val data = rawMessage(Command.CNXN, arg0 = 1, payload = byteArrayOf(1, 2, 3))
        data[20] = (data[20] + 1).toByte() // 破坏 magic
        val t = Transport(FakeByteChannel(data), readTimeoutMs = 2_000)

        val e = org.junit.Assert.assertThrows(ProtocolException::class.java) { AdbMessage.readFrom(t) }
        assertTrue(e.message!!.contains("magic"))
    }

    @Test
    fun `checksum 错被拒绝`() {
        val payload = byteArrayOf(10, 20, 30, 40)
        // 故意声明一个错误的 checksum
        val data = rawMessage(
            Command.WRTE,
            arg0 = StreamId.VIDEO,
            payload = payload,
            declaredChecksum = AdbMessage.checksumOf(payload) + 1,
        )
        val t = Transport(FakeByteChannel(data), readTimeoutMs = 2_000)

        val e = org.junit.Assert.assertThrows(ProtocolException::class.java) { AdbMessage.readFrom(t) }
        assertTrue(e.message!!.contains("checksum"))
    }

    // ------------------------------------------------------------------ 单测 3

    @Test
    fun `报文头被拆成 3 段投喂仍能正确解析`() {
        val payload = "abcdefgh".toByteArray(Charsets.UTF_8)
        val data = rawMessage(Command.OPEN, arg0 = 0, payload = payload)

        // chunk = 8 -> 24B 头正好被拆成 3 次返回
        val ch = FakeByteChannel(data, chunk = 8)
        val t = Transport(ch, readTimeoutMs = 2_000)

        val msg = AdbMessage.readFrom(t)
        assertEquals(Command.OPEN, msg.commandType)
        assertEquals("abcdefgh", msg.payload.toString(Charsets.UTF_8))
    }

    @Test
    fun `payload 被拆碎成每字节一次也能正确解析`() {
        val payload = ByteArray(50) { (it * 3).toByte() }
        val data = rawMessage(Command.WRTE, arg0 = StreamId.VIDEO, payload = payload)

        val t = Transport(FakeByteChannel(data, chunk = 1), readTimeoutMs = 5_000)
        val msg = AdbMessage.readFrom(t)

        assertEquals(Command.WRTE, msg.commandType)
        assertEquals(StreamId.VIDEO, msg.arg0)
        org.junit.Assert.assertArrayEquals(payload, msg.payload)
    }

    // ------------------------------------------------------------------ 单测 5

    @Test
    fun `data_length 声明 16 MiB 时抛协议错误而不是分配缓冲`() {
        val huge = 16 * 1024 * 1024
        val data = rawMessage(Command.WRTE, declaredLength = huge)
        val t = Transport(FakeByteChannel(data), readTimeoutMs = 2_000)

        val e = org.junit.Assert.assertThrows(ProtocolException::class.java) { AdbMessage.readFrom(t) }
        assertTrue(e.message!!.contains("data_length"))
        // 关键：抛错发生在读取 payload 之前，绝不会先分配 16 MiB
        assertTrue(e.message!!.contains(huge.toString()))
    }

    @Test
    fun `data_length 为负数同样被拒绝`() {
        val data = rawMessage(Command.WRTE, declaredLength = -1)
        val t = Transport(FakeByteChannel(data), readTimeoutMs = 2_000)
        org.junit.Assert.assertThrows(ProtocolException::class.java) { AdbMessage.readFrom(t) }
    }

    @Test
    fun `payload 上限刚好等于 8 MiB 是允许的`() {
        val max = Protocol.MAX_PAYLOAD_SIZE
        assertEquals(8 * 1024 * 1024, max)
        // 只声明长度、不给数据：不应因长度本身被拒绝（会因 EOF 失败）
        val data = rawMessage(Command.WRTE, declaredLength = max)
        val t = Transport(FakeByteChannel(data), readTimeoutMs = 2_000)
        val e = org.junit.Assert.assertThrows(Exception::class.java) { AdbMessage.readFrom(t) }
        assertFalse(e is ProtocolException && e.message!!.contains("out of range"))
    }
}
