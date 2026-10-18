package com.uav.protocol.usb

/**
 * 插进来的 USB 设备属于哪套投屏协议。
 *
 * ## 为什么这个分类必须存在
 *
 * 宿主同时支持两条完全不同的链路：
 *  - **AOA v2**：宿主把安卓手机"变成"配件（发 51/52×6/53），然后走 accessory fd；
 *  - **QuickTime USB**：iPad 本来就是 USB 设备，宿主去 claim 它的 `0xFF/0x2A` 接口。
 *
 * 而 AOA 侧的设备选择逻辑是「有 bulk 端点对就当候选、否则取最近枚举的那台」——
 * **iPad 恰好有 bulk 端点对**（`if2` 的 `0x86`/`0x05`），于是一台 iPad 插上来
 * 会被当成待握手的手机，对它发 AOA 握手请求。这在合并成一个 App 之后是必然踩到的坑。
 *
 * ⇒ 分类必须是**纯函数、放在协议层、可 JVM 单测**，并且在任何 USB 操作之前调用。
 */
enum class UsbDeviceKind {
    /** 已经是配件模式（AOA 握手过）——直接 claim 快路径。 */
    AOA_ACCESSORY,

    /** Apple 设备（iPad/iPhone），走 QuickTime USB 投屏。 */
    IOS,

    /** 不认识的设备：内建 HID 触摸屏、键鼠、U 盘……一律不碰。 */
    IGNORE,
}

object UsbDeviceClassify {

    /** Apple 的 USB 厂商号。 */
    const val APPLE_VID = 0x05AC

    /**
     * AOA 配件模式的 PID（USB 配件规范固定这几个值）。
     *
     * 注意：手机**普通模式**下 PID 是它自己的（比如 0x4EE1），此时不能靠 PID 判断，
     * 只能靠"是不是 Apple 设备"来排除 —— 剩下的候选才交给 AOA 握手去试。
     */
    val AOA_ACCESSORY_PIDS = setOf(0x2D00, 0x2D01, 0x2D02)

    /**
     * 按 VID/PID 分类。
     *
     * ⚠️ 判定顺序有讲究：先认 AOA 配件（配件 PID 也可能被某些厂商复用），
     * 再认 Apple，最后才 IGNORE。**绝不**在没分类前就对设备发任何控制请求。
     */
    fun kindOf(vendorId: Int, productId: Int): UsbDeviceKind = when {
        productId in AOA_ACCESSORY_PIDS -> UsbDeviceKind.AOA_ACCESSORY
        vendorId == APPLE_VID -> UsbDeviceKind.IOS
        else -> UsbDeviceKind.IGNORE
    }
}
