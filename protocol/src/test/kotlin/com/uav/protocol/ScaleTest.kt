package com.uav.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `max_size` 计算与等比缩放（计划 3.7 / 4.3 / 4.5）。 */
class ScaleTest {

    @Test
    fun `两台 1080x2400 手机计算出的 max_size 为 2400`() {
        val maxSize = Scale.computeMaxSize(1080, 2400, 1080, 2400)
        assertEquals(2_400, maxSize)

        // 且此时不需要任何缩放（宿主端零放大，最清晰）
        val out = Scale.scaledSize(1080, 2400, maxSize)
        assertArrayEquals(intArrayOf(1080, 2400), out)
        assertTrue(Scale.isIdentity(1080, 2400, out[0], out[1]))
    }

    @Test
    fun `max_size 取两边长边的较小者`() {
        // 手机长边 2400，宿主长边 1600 -> 取 1600
        assertEquals(1_600, Scale.computeMaxSize(1080, 2400, 720, 1600))
        // 手机长边 1600，宿主长边 2400 -> 取 1600
        assertEquals(1_600, Scale.computeMaxSize(720, 1600, 1080, 2400))
    }

    @Test
    fun `max_size 对齐 8 字节`() {
        // 手机长边 1081、宿主长边 3000 -> 取 1081，对齐到 1088
        assertEquals(1_088, Scale.computeMaxSize(1081, 1000, 3000, 3000))
        for (v in intArrayOf(1081, 2401, 999, 1001, 720)) {
            assertEquals(0, Scale.computeMaxSize(v, 2000, 3000, 3000) % 8)
        }
    }

    @Test
    fun `缩放后长边精确落在 max_size 且两轴对齐 8`() {
        val out = Scale.scaledSize(1080, 2400, 1600)
        assertEquals(1600, Scale.longSide(out[0], out[1]))
        assertEquals(0, out[0] % 8)
        assertEquals(0, out[1] % 8)
        // 竖屏保持竖屏
        assertTrue(out[1] > out[0])
    }

    @Test
    fun `横屏源等比缩放后仍是横屏`() {
        val out = Scale.scaledSize(2400, 1080, 1600)
        assertTrue(out[0] > out[1])
        assertEquals(1600, Scale.longSide(out[0], out[1]))
    }

    @Test
    fun `源尺寸不超过 max_size 时不缩放`() {
        val out = Scale.scaledSize(720, 1600, 1600)
        assertArrayEquals(intArrayOf(720, 1600), out)
        assertTrue(Scale.isIdentity(720, 1600, out[0], out[1]))
    }

    @Test
    fun `对齐 8 之后不会出现 0 尺寸`() {
        // 极端窄屏：宽 10、高 4000，缩到长边 1600 时宽会很小
        val out = Scale.scaledSize(10, 4000, 1600)
        assertTrue("width=${out[0]} height=${out[1]}", out[0] >= 8)
        assertTrue(out[1] >= 8)
        assertEquals(0, out[0] % 8)
        assertEquals(0, out[1] % 8)
    }

    @Test
    fun `缩放比例合理不畸变`() {
        val srcW = 1080
        val srcH = 2400
        val out = Scale.scaledSize(srcW, srcH, 1600)
        val srcRatio = srcW.toDouble() / srcH
        val outRatio = out[0].toDouble() / out[1]
        // 允许对齐全带来的少量误差（<2%）
        assertTrue("宽高比偏离过大: $srcRatio vs $outRatio", Math.abs(srcRatio - outRatio) < 0.02)
    }

    @Test
    fun `非法入参被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { Scale.scaledSize(0, 100, 100) }
        assertThrows(IllegalArgumentException::class.java) { Scale.scaledSize(100, 100, 0) }
    }

    @Test
    fun `align8 向上取整`() {
        assertEquals(8, Bytes.align8(1))
        assertEquals(8, Bytes.align8(8))
        assertEquals(16, Bytes.align8(9))
        assertEquals(0, Bytes.align8(0))
    }

    @Test
    fun `长边判定不区分横竖屏`() {
        assertEquals(2400, Scale.longSide(1080, 2400))
        assertEquals(2400, Scale.longSide(2400, 1080))
    }

    @Test
    fun `isIdentity 在尺寸变化时返回 false`() {
        assertFalse(Scale.isIdentity(1080, 2400, 720, 1600))
    }
}
