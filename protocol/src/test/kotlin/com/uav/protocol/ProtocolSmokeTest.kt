package com.uav.protocol

import com.uav.protocol.aoa.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 0 冒烟测试：确认 `:protocol` 确实是一个可跑单测的纯 JVM 库。
 * 真正的协议用例见计划阶段 1 的 9 项单测清单。
 */
class ProtocolSmokeTest {

    @Test
    fun protocolVersionIsOne() {
        assertEquals(1, Protocol.VERSION)
    }

    @Test
    fun headerSizeIs24Bytes() {
        assertEquals(24, Protocol.MESSAGE_HEADER_SIZE)
    }

    @Test
    fun limitsAreSaneAndOrdered() {
        assertTrue(Protocol.MAX_FRAME_SIZE <= Protocol.MAX_PAYLOAD_SIZE)
        assertTrue(Protocol.MAX_READ_REQUEST <= Protocol.MAX_PAYLOAD_SIZE)
        assertTrue(Protocol.QUEUE_DEPTH > 0)
    }

    @Test
    fun logTagMatchesProjectConvention() {
        assertEquals("UAV-Protocol", Protocol.TAG)
    }
}
