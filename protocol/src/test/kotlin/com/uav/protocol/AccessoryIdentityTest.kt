package com.uav.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配件身份与 `SEND_STRING` 编码（计划 2.4 红线）。
 *
 * 这些值同时被两处使用，必须逐字一致：
 *  - 宿主侧 `AOA SEND_STRING`(52) × 6
 *  - 手机侧 `res/xml/accessory_filter.xml`（XML 无法引用 Kotlin 常量，属手工镜像）
 */
class AccessoryIdentityTest {

    @Test
    fun `SEND_STRING 顺序与 AOA 规范一致`() {
        val order = AccessoryIdentity.SEND_STRING_ORDER
        assertEquals(6, order.size)
        assertEquals(AccessoryIdentity.MANUFACTURER, order[0])
        assertEquals(AccessoryIdentity.MODEL, order[1])
        assertEquals(AccessoryIdentity.DESCRIPTION, order[2])
        assertEquals(AccessoryIdentity.VERSION, order[3])
        assertEquals(AccessoryIdentity.URI, order[4])
        assertEquals(AccessoryIdentity.SERIAL, order[5])
    }

    @Test
    fun `参与匹配的三个值不为空`() {
        // manufacturer / model / version 参与 accessory_filter 匹配，空值会导致手机永远不启动
        assertTrue(AccessoryIdentity.MANUFACTURER.isNotEmpty())
        assertTrue(AccessoryIdentity.MODEL.isNotEmpty())
        assertTrue(AccessoryIdentity.VERSION.isNotEmpty())
    }

    @Test
    fun `字符串编码为 UTF-16LE 且含结尾 NUL`() {
        val bytes = AccessoryIdentity.encodeSendString("UAV")
        // "UAV" = 3 字符 -> 6 字节 + 结尾 NUL 2 字节 = 8
        assertEquals(8, bytes.size)
        assertArrayEquals(
            byteArrayOf('U'.code.toByte(), 0, 'A'.code.toByte(), 0, 'V'.code.toByte(), 0, 0, 0),
            bytes,
        )
    }

    @Test
    fun `非 ASCII 字符同样按 UTF-16LE 编码`() {
        val bytes = AccessoryIdentity.encodeSendString("投屏")
        assertEquals(2 * 2 + 2, bytes.size)
        // "投" U+6295 -> LE 为 0x95 0x62
        assertEquals(0x95.toByte(), bytes[0])
        assertEquals(0x62.toByte(), bytes[1])
    }

    @Test
    fun `空字符串也带结尾 NUL`() {
        assertArrayEquals(byteArrayOf(0, 0), AccessoryIdentity.encodeSendString(""))
    }

    @Test
    fun `超过 255 字符被拒绝`() {
        val tooLong = "x".repeat(AccessoryIdentity.MAX_STRING_LENGTH + 1)
        assertThrows(IllegalArgumentException::class.java) {
            AccessoryIdentity.encodeSendString(tooLong)
        }
    }

    @Test
    fun `恰好 255 字符被接受`() {
        val max = "x".repeat(AccessoryIdentity.MAX_STRING_LENGTH)
        val bytes = AccessoryIdentity.encodeSendString(max)
        assertEquals(AccessoryIdentity.MAX_STRING_LENGTH * 2 + 2, bytes.size)
    }

    @Test
    fun `encodeSendStrings 返回 6 段`() {
        val all = AccessoryIdentity.encodeSendStrings()
        assertEquals(6, all.size)
        // 第 4 段（index 3）= version
        assertArrayEquals(
            AccessoryIdentity.encodeSendString(AccessoryIdentity.VERSION),
            all[3],
        )
    }
}
