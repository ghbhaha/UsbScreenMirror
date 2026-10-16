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

    // ------------------------------------------------------------------ 1:1 像素映射（10.15）

    @Test
    fun `fitInside 复现信箱适配：竖屏源放进横屏容器`() {
        // 真实数字：手机 1224x2700（比例 0.4533）放进宿主根视图 1920x1023
        val out = Scale.fitInside(1920, 1023, 1224, 2700)
        assertEquals(1023, out[1])                       // 受高度约束
        assertEquals(463, out[0])                        // 1023 * 0.4533 = 463.7 -> 463
        assertEquals(0, out[0] - 463)
    }

    @Test
    fun `fitInside 竖屏容器里竖屏源受高度约束`() {
        val out = Scale.fitInside(1080, 1884, 1224, 2700)
        assertEquals(1884, out[1])
        assertEquals(854, out[0])                        // 1884 * 1224 / 2700 = 854.08 -> 854
    }

    @Test
    fun `fitInside 宽源放进窄容器时受宽度约束`() {
        // 手机旋转成横屏 2700x1224（比例 2.206）放进 1920x1023
        val out = Scale.fitInside(1920, 1023, 2700, 1224)
        assertEquals(1920, out[0])
        assertEquals(870, out[1])                        // 1920 / 2.206 = 870.3 -> 870
    }

    @Test
    fun `fitInside 比例相同时正好铺满`() {
        assertArrayEquals(intArrayOf(1080, 1920), Scale.fitInside(1080, 1920, 540, 960))
        // 比例只差一点点时按取整规则走，不强行凑满
        assertEquals(1919, Scale.fitInside(1080, 1920, 1083, 1925)[1])
    }

    @Test
    fun `fitInside 容器为零时退化为 1x1 而不是崩`() {
        assertArrayEquals(intArrayOf(1, 1), Scale.fitInside(0, 0, 1224, 2700))
        assertThrows(IllegalArgumentException::class.java) { Scale.fitInside(100, 100, 0, 10) }
    }

    @Test
    fun `1比1：宿主横屏时按显示区取尺寸，而不是宿主长边`() {
        // ★ 这就是本次改动的核心收益：显示区 1920x1023 -> 适配后长边 1023 -> max_size 1024
        //   旧口径会给出 1920（宿主长边），编码 872x1920 = 1.67 Mpx，而实际只显示 464x1023 = 0.47 Mpx
        val maxSize = Scale.oneToOneMaxSize(1224, 2700, 1920, 1023)
        assertEquals(1024, maxSize)

        val enc = Scale.scaledSize(1224, 2700, maxSize)
        assertArrayEquals(intArrayOf(464, 1024), enc)
        // 与显示区 463x1023 只差 1px（对齐 8 的代价），不是 3.5 倍
        assertTrue(kotlin.math.abs(enc[0] - 463) <= 1)
        assertTrue(kotlin.math.abs(enc[1] - 1023) <= 1)
    }

    @Test
    fun `1比1：宿主竖屏时几乎不变（本来就接近 1比1）`() {
        // 显示区 1080x1884 -> 适配后长边 1884 -> max_size 1888 -> 编码 856x1888
        assertEquals(1888, Scale.oneToOneMaxSize(1224, 2700, 1080, 1884))
        assertArrayEquals(intArrayOf(856, 1888), Scale.scaledSize(1224, 2700, 1888))
    }

    @Test
    fun `1比1：手机旋转后同一显示区会算出新的正确尺寸`() {
        // 同一块宿主显示区，手机竖屏 vs 横屏 —— 手机端旋转时现算即可，不必重新协商
        val portrait = Scale.oneToOneMaxSize(1224, 2700, 1920, 1023)
        val landscape = Scale.oneToOneMaxSize(2700, 1224, 1920, 1023)
        assertEquals(1024, portrait)
        assertEquals(1920, landscape)                    // 横屏时画面能铺满整宽 -> 需要更大
        assertTrue("旋转后必须给出不同尺寸，否则会放大模糊", landscape > portrait)
    }

    @Test
    fun `1比1：显示区比手机大时不放大源`() {
        // 宿主 1440x3200 比手机 1224x2700 大：再编大只是放大空像素
        val maxSize = Scale.oneToOneMaxSize(1224, 2700, 1440, 3200)
        assertTrue("不得超出源长边 2700，实际 $maxSize", maxSize <= 2700)
        assertArrayEquals(intArrayOf(1224, 2700), Scale.scaledSize(1224, 2700, maxSize))
    }

    @Test
    fun `1比1：显示区未知时退回旧口径`() {
        assertEquals(
            Scale.computeMaxSize(1224, 2700, 1920, 1080),
            Scale.oneToOneMaxSize(1224, 2700, 0, 0, 1920, 1080),
        )
        // 面板也没给：退回源长边（对齐后不超过源）
        val v = Scale.oneToOneMaxSize(1224, 2700, 0, 0)
        assertTrue(v <= 2700)
    }

    @Test
    fun `1比1：极小显示区被下界保护住`() {
        val v = Scale.oneToOneMaxSize(1224, 2700, 100, 100)
        assertEquals(Scale.MIN_ONE_TO_ONE_LONG_SIDE, v)
        // 但源本身更小时，仍以源为准（不可能放大）
        assertEquals(200, Scale.oneToOneMaxSize(200, 120, 100, 100))
    }

    @Test
    fun `1比1：结果 8 对齐，除非被"不放大"夹住`() {
        for (w in intArrayOf(320, 720, 1080, 1440, 1920, 2160)) {
            for (h in intArrayOf(480, 1023, 1080, 1884, 2400, 3200)) {
                val v = Scale.oneToOneMaxSize(1224, 2700, w, h)
                // 正常路径 8 对齐；显示区比源大时会被夹到源长边 2700（本身不是 8 的倍数），
                // 那时 scaledSize 走"原样返回"，编码尺寸 = 源尺寸，不做任何缩放 —— 这是最优结果
                assertTrue(
                    "avail ${w}x$h -> $v 既不是 8 对齐，也不等于源长边",
                    v % 8 == 0 || v == Scale.longSide(1224, 2700),
                )
            }
        }
    }
}
