package com.uav.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触点 → 槽位 的分配逻辑单测。
 *
 * 盯住的三件事：**别越界**（槽位有限）、**别挤掉已有手指**、**抬起要能回收槽位**
 * —— 任何一条错了，真机上都会表现成"手感不对"这种极难查的现象。
 */
class MultiTouchTrackerTest {

    private val t = MultiTouchTracker(maxFingers = 3)

    @Test
    fun `按下分配空闲槽并按顺序给出快照`() {
        assertTrue(t.down(7, 100, 200))
        assertTrue(t.down(8, 300, 400))
        assertEquals(2, t.activeCount)

        val s = t.snapshot()
        assertEquals(3, s.size)                        // 快照长度恒等于槽位数
        assertEquals(TouchSlot(0, 100, 200, true), s[0])
        assertEquals(TouchSlot(1, 300, 400, true), s[1])
        assertEquals(TouchSlot(2, 0, 0, false), s[2])  // 未按下的槽也在快照里
    }

    @Test
    fun `pointerId 与槽位下标无关`() {
        // Android 的 pointerId 不保证从 0 开始，也不保证连续
        t.down(99, 5, 6)
        assertEquals(5, t.snapshot()[0].x)
        assertTrue(t.move(99, 50, 60))
        assertEquals(50, t.snapshot()[0].x)
    }

    @Test
    fun `槽位满了不挤掉已有手指`() {
        assertTrue(t.down(1, 1, 1))
        assertTrue(t.down(2, 2, 2))
        assertTrue(t.down(3, 3, 3))
        // 第 4 根手指：拒绝，且前 3 根不受影响
        assertFalse(t.down(4, 4, 4))
        assertEquals(3, t.activeCount)
        assertEquals(TouchSlot(2, 3, 3, true), t.snapshot()[2])
    }

    @Test
    fun `抬起后槽位可被后来的手指回收`() {
        t.down(1, 1, 1)
        t.down(2, 2, 2)
        t.down(3, 3, 3)
        assertTrue(t.up(2))
        assertEquals(2, t.activeCount)

        // 新手指应该占用刚空出来的槽 1
        assertTrue(t.down(4, 40, 40))
        val s = t.snapshot()
        assertEquals(TouchSlot(0, 1, 1, true), s[0])
        assertEquals(TouchSlot(1, 40, 40, true), s[1])
        assertEquals(TouchSlot(2, 3, 3, true), s[2])
    }

    @Test
    fun `未登记的手指移动与抬起都不接受`() {
        assertFalse(t.move(5, 1, 1))
        assertFalse(t.up(5))
        assertEquals(0, t.activeCount)
    }

    @Test
    fun `重复 down 同一个 pointerId 当成移动`() {
        t.down(1, 10, 10)
        assertTrue(t.down(1, 20, 20))
        assertEquals(1, t.activeCount)
        assertEquals(TouchSlot(0, 20, 20, true), t.snapshot()[0])
    }

    @Test
    fun `抬起后坐标保留在快照里作为最后一帧`() {
        t.down(1, 10, 10)
        t.down(2, 20, 20)
        t.up(1)
        val s = t.snapshot()
        assertEquals(TouchSlot(0, 10, 10, false), s[0])   // 坐标留着，down=false
        assertEquals(TouchSlot(1, 20, 20, true), s[1])
    }

    @Test
    fun `cancelAll 清空所有触点`() {
        t.down(1, 1, 1)
        t.down(2, 2, 2)
        t.cancelAll()
        assertEquals(0, t.activeCount)
        assertTrue(t.snapshot().none { it.down })
    }

    @Test
    fun `槽位数非法时拒绝构造`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MultiTouchTracker(maxFingers = 0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MultiTouchTracker(maxFingers = MultiTouchTracker.MAX_SLOTS + 1)
        }
    }

    @Test
    fun `默认槽位数与描述符声明的指头数一致`() {
        assertEquals(HidTouchPanel.MAX_FINGERS, MultiTouchTracker().maxFingers)
        assertEquals(HidTouchPanel.MAX_FINGERS, MultiTouchTracker().snapshot().size)
    }
}
