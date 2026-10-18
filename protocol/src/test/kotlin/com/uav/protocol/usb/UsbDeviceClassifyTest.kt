package com.uav.protocol.usb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设备分类的回归测试。
 *
 * 这些用例对应真实设备：
 *  - iPad8,9 普通态：`VID 05AC / PID 12AB`
 *  - 同一台 iPad 在 QT 采集配置：PID 仍是 `12AB`（**不会**变成 AOA 配件 PID）
 *  - 安卓手机被 AOA 握手后：`0x2D01`（v2）/ `0x2D00`（v1）/ `0x2D02`（含音频）
 *  - 宿主内建 HID 触摸屏、普通键鼠：都不该被投屏逻辑碰
 */
class UsbDeviceClassifyTest {

    @Test
    fun `iPad 走 iOS 路径`() {
        assertEquals(UsbDeviceKind.IOS, UsbDeviceClassify.kindOf(0x05AC, 0x12AB))
        assertEquals(UsbDeviceKind.IOS, UsbDeviceClassify.kindOf(0x05AC, 0x12A8))  // iPhone
    }

    @Test
    fun `AOA 配件 PID 都认`() {
        for (pid in listOf(0x2D00, 0x2D01, 0x2D02)) {
            assertEquals("pid=0x${pid.toString(16)}", UsbDeviceKind.AOA_ACCESSORY,
                UsbDeviceClassify.kindOf(0x2717, pid))
        }
    }

    @Test
    fun `手机普通模式不是 AOA 配件`() {
        // 普通态 PID 是厂商自己的，分类只知道"它不是配件、也不是 Apple"
        assertEquals(UsbDeviceKind.IGNORE, UsbDeviceClassify.kindOf(0x2717, 0x4EE1))
    }

    @Test
    fun `内建 HID 触摸屏与键鼠必须被忽略`() {
        assertEquals(UsbDeviceKind.IGNORE, UsbDeviceClassify.kindOf(0x1A86, 0xFE07))  // 触摸屏
        assertEquals(UsbDeviceKind.IGNORE, UsbDeviceClassify.kindOf(0x046D, 0xC52B))  // 罗技接收器
        assertEquals(UsbDeviceKind.IGNORE, UsbDeviceClassify.kindOf(0x0781, 0x5583))  // U 盘
    }

    /**
     * 回归点：iPad 的 PID 永远不是 AOA 的 `0x2D0x`，所以"先判配件 PID"不会误伤；
     * 反过来若把 Apple 判在配件之前，一旦某天 Apple 用了 0x2D01 就会走错路。
     * 这条用例把优先级写死。
     */
    @Test
    fun `配件 PID 优先于厂商号`() {
        assertEquals(UsbDeviceKind.AOA_ACCESSORY, UsbDeviceClassify.kindOf(0x05AC, 0x2D01))
    }
}
