package com.uav.protocol.aoa


/**
 * AOA 2.0 的 **HID 注入**（路线图 P1「输入通道」的地基；方向是 **宿主 → 手机**）。
 *
 * ### 机制（AOA 2.0 规范 + 内核 `f_accessory.c` 实现）
 *
 * 1. `REGISTER_HID`：`value` = 我们自分配的 HID 设备 id，**`index` = 报告描述符总长度**；
 * 2. `SET_HID_REPORT_DESC`：`value` = id，**`index` = 该块在描述符里的字节偏移**。
 *    描述符超过 EP0 包长时**必须按偏移顺序分多次发**（规范明确要求"顺序发送"）；
 * 3. `SEND_HID_EVENT`：`value` = id，`index` = 0，`data` = **一条完整的 input report**（无任何封装）。
 *
 * 手机侧由内核 `f_accessory` 把描述符交给 HID 核心（`hid_parse_report()`）、
 * 把每条报告交给 `hid_report_raw_event(..., HID_INPUT_REPORT, ...)`，
 * 于是**系统里就"多出一个 USB 触摸屏"** —— 和插一个真实外接触摸屏走的是同一条路径。
 * ⚠️ 因此**手机端 App 完全不参与**（与 51/52/53 握手一样），这是宿主端单独就能做的功能。
 *
 * ### 两条容易踩的性质
 *
 * - ★ **全部走 EP0 控制传输**：不新增 USB 接口，也**不占视频用的 bulk 端点**。
 *   代价是每条报告一次控制传输，传输频率受 EP0 带宽限制（几十 Hz 量级够用）。
 * - ★ **单向**：内核里 `acc_hid_raw_request` 是空实现 → 拿不到输出报告，
 *   所以**别指望力反馈、键盘 LED** 这类需要反向通道的功能。
 * - ⚠️ HID 的 id **只在本次连接内有效**：拔线 / 重枚举后必须**重新注册并重发描述符**
 *   （见 `docs/实施计划.md` 10.8 的自动重连流程）。
 */
object AoaHid {

    /** 控制请求的 bRequestType：OUT | VENDOR | DEVICE（与 51/52/53 同族）。 */
    const val REQTYPE_OUT_VENDOR_DEVICE = 0x40

    /** 注册一个 HID 设备；`index` 必须是描述符总长度。 */
    const val REGISTER_HID = 54

    /** 注销 HID 设备（id 随 USB 断开也会自动失效）。 */
    const val UNREGISTER_HID = 55

    /** 分块发送报告描述符；`index` 是该块的偏移。 */
    const val SET_HID_REPORT_DESC = 56

    /** 发送一条输入报告。 */
    const val SEND_HID_EVENT = 57

    /** 控制传输超时：**严禁 0**（0 在 USB 语义里是无限等待，会挂死线程）。 */
    const val DEFAULT_TIMEOUT_MS = 1000

    /** 描述符分块，供 [SET_HID_REPORT_DESC] 逐条发送。 */
    data class DescriptorChunk(val offset: Int, val bytes: ByteArray) {
        // data class 里有 ByteArray，equals/hashCode 要手写才符合直觉（单测会用）
        override fun equals(other: Any?): Boolean =
            other is DescriptorChunk && offset == other.offset && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * offset + bytes.contentHashCode()
    }

    /**
     * 把描述符按 EP0 单次可传长度切块。
     *
     * 规范要求"描述符太长时分多次发，且必须**顺序**发送"，
     * 因此这里返回的块列表**必须按顺序**发出，`offset` 就是 `SET_HID_REPORT_DESC` 的 `index`。
     */
    fun chunkDescriptor(descriptor: ByteArray, maxChunk: Int): List<DescriptorChunk> {
        require(maxChunk > 0) { "maxChunk 必须为正数：$maxChunk" }
        if (descriptor.isEmpty()) return emptyList()
        val out = ArrayList<DescriptorChunk>((descriptor.size + maxChunk - 1) / maxChunk)
        var off = 0
        while (off < descriptor.size) {
            val n = minOf(maxChunk, descriptor.size - off)
            out.add(DescriptorChunk(off, descriptor.copyOfRange(off, off + n)))
            off += n
        }
        return out
    }
}
