package com.uav.protocol.aoa

import com.uav.protocol.Bytes
import com.uav.protocol.bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单测 6：`ptsAndFlags` 的位复用安全性（计划 4.4）。 */
class FlagsTest {

    @Test
    fun `位常量与冻结清单一致`() {
        assertEquals(Long.MIN_VALUE, Flags.CONFIG) // 1<<63
        assertEquals(1L shl 62, Flags.KEY_FRAME)
        assertEquals(1L shl 61, Flags.DIRECTION)
        assertEquals((1L shl 61) - 1, Flags.PTS_MASK)
    }

    @Test
    fun `CONFIG 与 KEY_FRAME 可单独识别`() {
        val config = Flags.pack(pts = 0, config = true)
        assertTrue(Flags.isConfig(config))
        assertFalse(Flags.isKeyFrame(config))

        val key = Flags.pack(pts = 0, keyFrame = true)
        assertTrue(Flags.isKeyFrame(key))
        assertFalse(Flags.isConfig(key))
    }

    @Test
    fun `CONFIG 与 KEY_FRAME 可同时置位`() {
        val both = Flags.pack(pts = 123, config = true, keyFrame = true)
        assertTrue(Flags.isConfig(both))
        assertTrue(Flags.isKeyFrame(both))
        assertEquals(123L, Flags.pts(both))
    }

    @Test
    fun `普通 PTS 含秒级大值不会被误判为标志位`() {
        // 秒级/分钟级/小时级 PTS：1s、60s、1h、24h（微秒）
        val ptsValues = longArrayOf(
            1_000_000L,
            60_000_000L,
            3_600_000_000L,
            86_400_000_000L, // 24h ≈ 2^36.3，远小于 2^61
        )
        for (pts in ptsValues) {
            val v = Flags.pack(pts)
            assertFalse("pts=$pts 被误判为 CONFIG", Flags.isConfig(v))
            assertFalse("pts=$pts 被误判为 KEY_FRAME", Flags.isKeyFrame(v))
            assertFalse("pts=$pts 被误判为 DIRECTION", Flags.isDirection(v))
            assertEquals(pts, Flags.pts(v))
        }
    }

    @Test
    fun `PTS 取到掩码上限仍不溢出到标志位`() {
        val v = Flags.pack(Flags.PTS_MASK)
        assertEquals(Flags.PTS_MASK, Flags.pts(v))
        assertFalse(Flags.isConfig(v))
        assertFalse(Flags.isKeyFrame(v))
        assertFalse(Flags.isDirection(v))
    }

    @Test
    fun `超出掩码的 PTS 被拒绝而非静默截断`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            Flags.pack(Flags.PTS_MASK + 1)
        }
    }

    @Test
    fun `大小端往返后高位标志仍然正确`() {
        val v = Flags.pack(pts = 987_654_321L, keyFrame = true)
        val bytes = Bytes.longLE(v)
        assertEquals(v, Bytes.getLongLE(bytes, 0))
        assertTrue(Flags.isKeyFrame(Bytes.getLongLE(bytes, 0)))
    }
}
