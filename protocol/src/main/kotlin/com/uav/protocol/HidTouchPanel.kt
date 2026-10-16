package com.uav.protocol

/**
 * 「单点绝对坐标触摸屏」的 HID **报告描述符** + **报告编码**。
 *
 * ⚠️ **两者必须严格对应**：报告里每个字段的位宽与顺序完全由描述符决定，
 *    所以放在同一个文件里 —— 改一处必须同时改另一处（单测会钉住长度与关键字节）。
 *
 * ### 报告布局（共 [REPORT_SIZE] 字节，小端）
 *
 * | 偏移 | 含义 |
 * | --- | --- |
 * | 0 | `Contact Count`：本报告里的触点数（按下 1 / 抬起 0） |
 * | 1 | `Contact Identifier`：触点编号（单点固定 0） |
 * | 2 | `bit0` = `Tip Switch`（按下 = 1），`bit1..7` 填充 |
 * | 3..4 | X（`uint16`，0 .. [descriptor] 里声明的 width-1） |
 * | 5..6 | Y（`uint16`，0 .. 声明的 height-1） |
 *
 * ### 为什么用"Contact Count + Finger 集合"这个形状
 *
 * 它正是**真实触摸屏**（Windows Precision Touch 那一套）的写法，
 * Linux 的 `hid-multitouch` / `hid-generic` 对它的接受度最高。
 * 更省字节的"裸单点"形状（只有 Tip Switch + X/Y）也常见，但用 `Pen` 之类
 * 写错 Usage 时会被识别成**鼠标指针 / 圆形光标**而不是触摸屏（有实测记录）。
 *
 * 将来要做多点：把 Finger 集合的 `Report Count` 提到 N、并给出 N 组触点字段，
 * 同时 `Contact Count` 的 Logical Maximum 也相应放宽即可。
 */
object HidTouchPanel {

    /** 报告长度（必须与描述符推导出的位宽一致，见文件头的布局表）。 */
    const val REPORT_SIZE = 7

    /**
     * 描述符长度。
     *
     * 宽高只改变其中 4 个字节的**取值**、不改变长度，所以这是个真常量；
     * 写出来是为了让单测能钉住它 —— 一旦有人增删描述符条目而忘了同步 [REPORT_SIZE]，
     * 测试立刻报错，而不是等真机上出现"坐标错位"这种难查的现象。
     */
    const val DESCRIPTOR_SIZE = 76

    /** 触点编号的逻辑上限（单点用不上，但 hid-multitouch 要求它非 0 才肯绑定）。 */
    const val MAX_CONTACT_ID = 0x0F

    /**
     * 生成报告描述符。
     *
     * @param width  面板逻辑宽度（= X 的逻辑上限）。宿主侧直接用 CNXN 协商到的**手机屏幕宽**。
     * @param height 面板逻辑高度（= Y 的逻辑上限）。
     */
    fun descriptor(width: Int, height: Int): ByteArray {
        require(width in 1..0xFFFF) { "width 必须落在 1..65535：$width" }
        require(height in 1..0xFFFF) { "height 必须落在 1..65535：$height" }
        val w = u16(width)
        val h = u16(height)
        return cat(
            // Usage Page (Digitizers) / Usage (Touch Screen) / Collection (Application)
            b(0x05, 0x0D, 0x09, 0x04, 0xA1, 0x01),
            //   Usage (Contact Count) 0..1（单点），8 bit
            b(0x09, 0x54, 0x15, 0x00, 0x25, 0x01),
            b(0x75, 0x08, 0x95, 0x01, 0x81, 0x02),
            //   Usage (Finger) / Collection (Logical)（与 MS 精密触摸屏的写法一致）
            b(0x09, 0x22, 0xA1, 0x02),
            //     Usage (Contact Identifier) 0..MAX_CONTACT_ID，8 bit
            b(0x09, 0x51, 0x25, MAX_CONTACT_ID),
            b(0x75, 0x08, 0x95, 0x01, 0x81, 0x02),
            //     Usage (Tip Switch) 0..1，1 bit
            b(0x09, 0x42, 0x25, 0x01),
            b(0x75, 0x01, 0x95, 0x01, 0x81, 0x02),
            //     填充 7 bit 凑满整字节
            b(0x75, 0x07, 0x95, 0x01, 0x81, 0x03),
            //     Usage Page (Generic Desktop) / Usage (X) 0..width，16 bit
            b(0x05, 0x01, 0x09, 0x30, 0x15, 0x00, 0x26, w[0].toInt(), w[1].toInt()),
            b(0x75, 0x10, 0x95, 0x01, 0x81, 0x02),
            //     Usage (Y) 0..height，16 bit（沿用上面的 Usage Page / Report Size）
            b(0x09, 0x31, 0x26, h[0].toInt(), h[1].toInt()),
            b(0x75, 0x10, 0x95, 0x01, 0x81, 0x02),
            //   End Collection / End Collection
            b(0xC0, 0xC0),
        )
    }

    /**
     * 编码一条单点报告（必须与 [descriptor] 的布局一致，见文件头）。
     *
     * @param x        X 坐标，单位与描述符的逻辑上限一致（即**手机屏幕像素**）
     * @param y        Y 坐标
     * @param tipDown  按下 = true，抬起 = false
     */
    fun report(x: Int, y: Int, tipDown: Boolean): ByteArray {
        val cx = x.coerceIn(0, 0xFFFF)
        val cy = y.coerceIn(0, 0xFFFF)
        val out = ByteArray(REPORT_SIZE)
        out[0] = if (tipDown) 1 else 0          // Contact Count：按下时报告里有 1 个触点
        out[1] = 0                              // Contact Identifier：单点固定 0
        out[2] = if (tipDown) 1 else 0          // bit0 = Tip Switch
        putU16(out, 3, cx)
        putU16(out, 5, cy)
        return out
    }

    private fun u16(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    /**
     * 按 Int 写字节，内部转 Byte。
     *
     * 刻意不用 `byteArrayOf`：它要求每个元素都是 `Byte`，而 `0x95`(=149)、`0xA1`(=161)、
     * `0xC0`(=192) 这类**超过 127 的字面量**不能隐式转换，必须手写 `.toByte()` —— 实测漏了
     * 一处就编译不过。描述符是逐个字节的 dump，按 Int 写更贴近它的本来面貌。
     */
    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    /** 顺序拼接（描述符分段的书写方式，见 [descriptor]）。 */
    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }

    private fun putU16(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }
}
