package com.uav.protocol.input

/**
 * 触点到**槽位**的分配（纯逻辑，可 JVM 单测）。
 *
 * HID 多点触摸屏的报告是"固定 N 个槽位、每次全量上报"（见 [HidTouchPanel]），
 * 而操作系统的触摸事件是"按 pointerId 来去"。这个类就是两者之间的那层翻译：
 * pointerId → 槽位下标，并在手指抬起后**回收槽位**给后来的手指用。
 *
 * ### 为什么要有它
 *
 * 设备槽位是稀缺资源（[HidTouchPanel.MAX_FINGERS] 个），而 Android 的 pointerId
 * 是递增不复用的（而且多点场景下不保证从 0 开始）。直接把 pointerId 当槽位号会：
 * ① 超出槽位数就被截断；② 抬过的手指永远占着槽。所以必须显式分配/回收。
 *
 * ### 线程约定
 *
 * **非线程安全**，只在触摸事件的线程（UI 线程）上改；发送前取 [snapshot]，
 * 把不可变快照交给 worker 线程去编码/发送。
 */
class MultiTouchTracker(val maxFingers: Int = HidTouchPanel.MAX_FINGERS) {

    init {
        require(maxFingers in 1..MAX_SLOTS) { "maxFingers 必须落在 1..$MAX_SLOTS：$maxFingers" }
    }

    private class Slot {
        var pointerId = NO_POINTER
        var x = 0
        var y = 0
        var down = false
    }

    private val slots = Array(maxFingers) { Slot() }

    /** 当前按下的触点数。 */
    val activeCount: Int get() = slots.count { it.down }

    /**
     * 新手指按下。
     *
     * @return 是否被接受。**槽位满了就返回 false 并原样丢弃** ——
     *   不去挤掉已有的手指：挤掉会让用户正按着的那根手指"凭空飞走"。
     */
    fun down(pointerId: Int, x: Int, y: Int): Boolean {
        slotOf(pointerId)?.let { return move(pointerId, x, y) } // 重复 down：当移动处理
        val free = slots.indexOfFirst { !it.down }
        if (free < 0) return false
        slots[free].apply {
            this.pointerId = pointerId
            this.x = x
            this.y = y
            down = true
        }
        return true
    }

    /** 手指移动。未登记的 pointerId 返回 false（调用方据此不发报告）。 */
    fun move(pointerId: Int, x: Int, y: Int): Boolean {
        val s = slotOf(pointerId) ?: return false
        s.x = x
        s.y = y
        return true
    }

    /** 手指抬起：释放槽位（坐标留着，作为该槽最后一次上报的值）。 */
    fun up(pointerId: Int): Boolean {
        val s = slotOf(pointerId) ?: return false
        s.down = false
        s.pointerId = NO_POINTER
        return true
    }

    /** 全部抬起（手势被取消 / 宿主切后台）。 */
    fun cancelAll() {
        for (s in slots) {
            s.down = false
            s.pointerId = NO_POINTER
        }
    }

    /**
     * 取一份**不可变快照**，长度恒为 [maxFingers]、按槽位下标排列。
     *
     * 未按下的槽同样出现在里面（`down=false`）—— 报告要全量上报，见 [HidTouchPanel.report]。
     */
    fun snapshot(): List<TouchSlot> =
        slots.mapIndexed { i, s -> TouchSlot(index = i, x = s.x, y = s.y, down = s.down) }

    private fun slotOf(pointerId: Int): Slot? =
        slots.firstOrNull { it.down && it.pointerId == pointerId }

    companion object {
        /** 无 pointerId 的占位值（pointerId 本身非负）。 */
        const val NO_POINTER = -1

        /**
         * 槽位数上限（= HID 规范里 `Contact Identifier` 能表达的合理范围）。
         * 实际上限还受手机内核的 `MT_DEFAULT_MAXCONTACT`（10）约束。
         */
        const val MAX_SLOTS = 10
    }
}
