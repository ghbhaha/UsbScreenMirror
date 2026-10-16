package com.uav.protocol

/** 报告里的一个槽位（一个触点位置）。`down=false` 表示该槽空闲。 */
data class TouchSlot(val index: Int, val x: Int, val y: Int, val down: Boolean)

/**
 * 「**多点**绝对坐标触摸屏」的 HID **报告描述符** + **报告编码**。
 *
 * ⚠️ **两者必须严格对应**：报告里每个字段的位宽与顺序完全由描述符决定，
 *    所以放在同一个文件里 —— 改一处必须同时改另一处（单测会钉住长度与关键字节）。
 *
 * ### 形状：fixed-slot Multi-Touch（照 AOA HID 的多点 profile）
 *
 * ```
 * Collection (Application)  Usage Page Digitizers / Usage Touch Screen
 *   × N 个【独立】的 Usage(Finger) / Collection(Logical)，每个只含一组：
 *       Tip Switch (0x42)  1 bit + 7 bit 补齐
 *       Contact Identifier (0x51)  8 bit      ← 固定 = 槽位下标
 *       X (0x30) 16 bit       Y (0x31) 16 bit
 *   Usage(Contact Count) 0x54  8 bit          ← 放在**最后**
 * End Collection
 * ```
 *
 * ### ★ 三个"照抄别人踩过的坑"才对的细节（都真机验证过是错的写法）
 *
 * 1. **每个手指是独立的 Logical Collection**，而不是"一个 collection + `Report Count = N`"。
 *    实测后者在这台手机的内核上只出来一个触点（第二指被忽略、且同一时刻就释放了）。
 * 2. **`Contact Count` 放在所有手指之后**，而不是开头。
 * 3. ★ **`Contact Count` = 本帧里 contact 记录的数量（含 Tip=0 的记录）**，
 *    **不是**"按下的触点数"。多点协议是按"帧"重组的：首报告带总数、续报告带 0。
 *    我们每帧固定发 N 条记录（未按下的也发，Tip=0），所以它恒等于 [MAX_FINGERS]。
 *
 * 来源：AOA HID 的多点 profile（`libaoahid` 的 `docs/PROFILES.md`，已在真机验证多点触摸）。
 *
 * ### 必须显式发 Tip = 0
 *
 * **默认的 `hid-multitouch` class 不会把"报告里没出现的触点"当成抬起**，
 * 所以抬手指时必须再发一条 `Tip Switch = 0` 的记录（带最终坐标）—— 不能靠"从报告里消失"。
 *
 * ### slot 数由谁定
 *
 * 描述符里**不声明** `Contact Count Maximum` 功能报告 —— 那条要通过 `GET_REPORT` 读，
 * 而 AOA 的 HID 通道里内核 `acc_hid_raw_request` 是**空实现**（读不到，见 [AoaHid]）。
 * 读不到时 `hid-multitouch` 用默认值 **`MT_DEFAULT_MAXCONTACT = 10`** 建槽位
 * （实测 `ABS_MT_SLOT 0..9`），≥ 我们的 [MAX_FINGERS]，够用。
 */
object HidTouchPanel {

    /**
     * 支持的触点数（= 报告里固定的记录数 = 描述符里 Finger collection 的个数）。
     *
     * 取 5：够双指缩放/多指点击。⚠️ 改这里必须同步 [DESCRIPTOR_SIZE] 与 [REPORT_SIZE]，
     * 单测会拦。
     */
    const val MAX_FINGERS = 5

    /**
     * 描述符长度（真常量：宽高只改变其中 4 个字节的取值）。
     *
     * 钉出来是为了让"改了描述符却忘了同步报告编码"立刻变成测试失败，
     * 而不是等真机上出现"坐标错位"这种难查的现象。
     *
     * 长度不小（> EP0 包长），所以注册时会**分多块顺序发送**。
     */
    val DESCRIPTOR_SIZE: Int = 5 * 63 + 6 + 12 + 1

    /** 报告长度：N ×(1 按下位+补齐 + 1 标识 + 2 X + 2 Y) + 1 触点数。 */
    const val REPORT_SIZE = MAX_FINGERS * 6 + 1

    /**
     * 生成报告描述符（结构见文件头）。
     *
     * @param width  面板逻辑宽度（= X 的逻辑上限）。宿主侧用 CNXN 协商到的**手机屏幕宽**。
     * @param height 面板逻辑高度（= Y 的逻辑上限）。
     */
    fun descriptor(width: Int, height: Int): ByteArray {
        require(width in 1..0xFFFF) { "width 必须落在 1..65535：$width" }
        require(height in 1..0xFFFF) { "height 必须落在 1..65535：$height" }
        val w = u16(width)
        val h = u16(height)

        val parts = ArrayList<ByteArray>()
        // Usage Page (Digitizers) / Usage (Touch Screen) / Collection (Application)
        parts += b(0x05, 0x0D, 0x09, 0x04, 0xA1, 0x01)
        // N 个独立的手指集合（顺序即槽位下标）
        repeat(MAX_FINGERS) {
            parts += b(
                // Usage (Finger) / Collection (Logical)
                0x09, 0x22, 0xA1, 0x02,
                //   Usage Page (Digitizers) / Usage (Tip Switch) 0..1，1 bit
                0x05, 0x0D, 0x09, 0x42, 0x15, 0x00, 0x25, 0x01,
                0x75, 0x01, 0x95, 0x01, 0x81, 0x02,
                //   补齐 7 bit 到字节边界
                0x75, 0x07, 0x95, 0x01, 0x81, 0x03,
                //   Usage (Contact Identifier) 0..N-1，8 bit（固定 = 槽位下标）
                0x09, 0x51, 0x15, 0x00, 0x25, MAX_FINGERS - 1,
                0x75, 0x08, 0x95, 0x01, 0x81, 0x02,
                //   Usage Page (Generic Desktop) / Usage (X) 0..width，16 bit
                0x05, 0x01, 0x09, 0x30, 0x15, 0x00, 0x26, w[0].toInt(), w[1].toInt(),
                0x75, 0x10, 0x95, 0x01, 0x81, 0x02,
                //   Usage (Y) 0..height，16 bit（沿用上面的 Usage Page / Report Size）
                0x09, 0x31, 0x26, h[0].toInt(), h[1].toInt(),
                0x75, 0x10, 0x95, 0x01, 0x81, 0x02,
                // End Collection（手指）
                0xC0,
            )
        }
        // ★ 触点数放在**所有手指之后**：本帧里的记录数（含 Tip=0），不是"按下的触点数"
        parts += b(0x09, 0x54, 0x15, 0x00, 0x25, MAX_FINGERS, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02)
        // End Collection（应用）
        parts += b(0xC0)
        return cat(*parts.toTypedArray())
    }

    /**
     * 编码一张多点报告（必须与 [descriptor] 的布局一致，见文件头的偏移表）。
     *
     * @param slots **长度必须等于 [MAX_FINGERS]**，按槽位下标排列。
     *              未按下的槽也要出现在报告里（`Tip Switch = 0`）——
     *              默认的 `hid-multitouch` **不把"没出现的触点"当抬起**，
     *              所以抬起必须显式发一条 Tip=0 的记录。
     */
    fun report(slots: List<TouchSlot>): ByteArray {
        require(slots.size == MAX_FINGERS) { "槽位数必须是 $MAX_FINGERS：${slots.size}" }
        val out = ByteArray(REPORT_SIZE)
        var p = 0
        for (i in 0 until MAX_FINGERS) {
            out[p++] = if (slots[i].down) 1 else 0   // Tip Switch（低 1 位，其余补齐位为 0）
            out[p++] = i.toByte()                    // Contact Identifier = 槽位下标
            putU16(out, p, slots[i].x)               // X
            p += 2
            putU16(out, p, slots[i].y)               // Y
            p += 2
        }
        // ★ 帧内记录数（恒为 MAX_FINGERS：未按下的槽也发了一条 Tip=0 记录）
        out[p++] = MAX_FINGERS.toByte()
        check(p == REPORT_SIZE) { "报告编码写满了 $p 字节，与 REPORT_SIZE=$REPORT_SIZE 不符" }
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

    /** 顺序拼接。 */
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
        val c = v.coerceIn(0, 0xFFFF)
        dst[off] = (c and 0xFF).toByte()
        dst[off + 1] = ((c ushr 8) and 0xFF).toByte()
    }
}
