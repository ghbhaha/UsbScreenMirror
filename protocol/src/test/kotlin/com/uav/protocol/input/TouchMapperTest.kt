package com.uav.protocol.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 触摸映射的纯逻辑单测。
 *
 * 这里最值钱的两条：
 *  1. **信箱边必须返回 null** —— 否则会在手机上点到画面以外看不见的地方；
 *  2. **旋转后坐标空间要跟着转** —— 手机屏幕物理尺寸只在 CNXN 报一次，
 *     旋转后只能靠视频方向推，推错了就是"点左边、手机响应右边"。
 */
class TouchMapperTest {

    // ------------------------------------------------------------------ 显示矩形

    @Test
    fun `视图比例与视频一致时矩形就是整个视图`() {
        // 872x1920 的视频，放在同比例（继承 onMeasure 结果）的视图里
        val r = TouchMapper.videoRect(872, 1920, 872, 1920)
        assertNotNull(r)
        assertEquals(IntRect(0, 0, 872, 1920), r)
    }

    @Test
    fun `容器更宽时左右留信箱边且居中`() {
        // 视图 1000x1000，视频 500x1000（竖屏）→ 受高度约束，宽 500，左右各留 250
        val r = TouchMapper.videoRect(1000, 1000, 500, 1000)!!
        assertEquals(0, r.top)
        assertEquals(1000, r.bottom)
        assertEquals(250, r.left)
        assertEquals(750, r.right)
    }

    @Test
    fun `容器更高时上下留信箱边且居中`() {
        // 视图 1000x1000，视频 1000x500（横屏）→ 受宽度约束，高 500，上下各留 250
        val r = TouchMapper.videoRect(1000, 1000, 1000, 500)!!
        assertEquals(0, r.left)
        assertEquals(1000, r.right)
        assertEquals(250, r.top)
        assertEquals(750, r.bottom)
    }

    @Test
    fun `尺寸非法时没有矩形`() {
        assertNull(TouchMapper.videoRect(0, 100, 10, 10))
        assertNull(TouchMapper.videoRect(100, 100, 0, 10))
        assertNull(TouchMapper.videoRect(100, 100, -1, 10))
    }

    // ------------------------------------------------------------------ 映射

    @Test
    fun `同比例视图下四角与中心都映射正确`() {
        // 视图 = 视频 872x1920，手机屏幕 1224x2700
        fun map(x: Int, y: Int) = TouchMapper.mapTouch(x, y, 872, 1920, 872, 1920, 1224, 2700)!!

        assertEquals(TouchPoint(0, 0), map(0, 0))
        val c = map(436, 960)
        assertEquals(612, c.x)   // 1224 / 2
        assertEquals(1350, c.y)  // 2700 / 2

        // 右下角：显示区是**半开区间**（x ∈ [left, right)），最后一列是 871 / 最后一行是 1919
        val last = map(871, 1919)
        assertEquals(1223, last.x)
        assertEquals(2699, last.y)
    }

    @Test
    fun `右边界与下边界本身不算在显示区内`() {
        // 半开区间的好处：触摸永远不会落成 (width, height) 这种"屏幕外一格"的坐标
        assertNull(TouchMapper.mapTouch(872, 10, 872, 1920, 872, 1920, 1224, 2700))
        assertNull(TouchMapper.mapTouch(10, 1920, 872, 1920, 872, 1920, 1224, 2700))
        assertNotNull(TouchMapper.mapTouch(871, 1919, 872, 1920, 872, 1920, 1224, 2700))
    }

    @Test
    fun `信箱边上的触摸返回 null`() {
        // 视图 1000x1000，视频 500x1000 → 可点区域 x ∈ [250, 750)
        assertNull(TouchMapper.mapTouch(0, 500, 1000, 1000, 500, 1000, 1224, 2700))
        assertNull(TouchMapper.mapTouch(249, 500, 1000, 1000, 500, 1000, 1224, 2700))
        assertNull(TouchMapper.mapTouch(750, 500, 1000, 1000, 500, 1000, 1224, 2700))
        assertNotNull(TouchMapper.mapTouch(250, 500, 1000, 1000, 500, 1000, 1224, 2700))
        assertNotNull(TouchMapper.mapTouch(749, 500, 1000, 1000, 500, 1000, 1224, 2700))
    }

    @Test
    fun `超出视图的坐标被拒绝而不是夹紧`() {
        assertNull(TouchMapper.mapTouch(-1, 10, 872, 1920, 872, 1920, 1224, 2700))
        assertNull(TouchMapper.mapTouch(10, 1920, 872, 1920, 872, 1920, 1224, 2700))
    }

    @Test
    fun `尺寸信息不全时拒绝映射`() {
        assertNull(TouchMapper.mapTouch(10, 10, 0, 100, 10, 10, 1224, 2700))
        assertNull(TouchMapper.mapTouch(10, 10, 100, 100, 10, 10, 0, 0))
    }

    // ------------------------------------------------------------------ 旋转

    @Test
    fun `竖屏时坐标空间就是物理尺寸`() {
        val s = TouchMapper.logicalPhoneSize(1224, 2700, 872, 1920)!!
        assertEquals(IntSize(1224, 2700), s)
    }

    @Test
    fun `横屏视频要把手机屏幕长宽互换`() {
        // 同一个物理屏幕 1224x2700，旋转后视频变成横的（1920x872）
        val s = TouchMapper.logicalPhoneSize(1224, 2700, 1920, 872)!!
        assertEquals(IntSize(2700, 1224), s)
    }

    @Test
    fun `横屏下映射用互换后的空间`() {
        // 视图与视频同为 1920x872；逻辑空间应为 2700x1224
        val last = TouchMapper.mapTouch(1919, 871, 1920, 872, 1920, 872, 1224, 2700)!!
        assertEquals(2699, last.x)
        assertEquals(1223, last.y)
        val mid = TouchMapper.mapTouch(960, 436, 1920, 872, 1920, 872, 1224, 2700)!!
        assertEquals(1350, mid.x)
        assertEquals(612, mid.y)
    }

    @Test
    fun `没有视频尺寸时按物理朝向返回`() {
        assertEquals(IntSize(1224, 2700), TouchMapper.logicalPhoneSize(1224, 2700, 0, 0))
        assertEquals(IntSize(2700, 1224), TouchMapper.logicalPhoneSize(2700, 1224, -1, 0))
    }

    @Test
    fun `物理尺寸非法时拒绝`() {
        assertNull(TouchMapper.logicalPhoneSize(0, 2700, 872, 1920))
        assertNull(TouchMapper.logicalPhoneSize(1224, -1, 872, 1920))
    }
}
