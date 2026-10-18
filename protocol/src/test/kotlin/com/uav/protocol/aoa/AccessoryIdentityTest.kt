package com.uav.protocol.aoa

import com.uav.protocol.bytes
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
    fun `字符串按 UTF-8 编码且只带一个结尾 NUL`() {
        val bytes = AccessoryIdentity.encodeSendString("UAV")
        // ★ 真机实测：必须是 UTF-8 + 单个 NUL。用 UTF-16LE 会让手机只认出首字符
        //   （"UAV" -> 55 00 41 00 56 00 00 00，框架按 NUL 切分后只剩 "U"）。
        assertArrayEquals(byteArrayOf(0x55, 0x41, 0x56, 0x00), bytes)
        assertEquals(4, bytes.size)
        // 除结尾外不得出现 NUL 字节 —— 出现就意味着编码方式选错了
        for (i in 0 until bytes.size - 1) {
            assertTrue("位置 $i 出现了内嵌 NUL，说明编码不是 UTF-8", bytes[i] != 0.toByte())
        }
    }

    @Test
    fun `非 ASCII 字符按 UTF-8 多字节编码`() {
        val bytes = AccessoryIdentity.encodeSendString("投屏")
        // "投" U+6295 -> UTF-8 E6 8A 95；"屏" U+5C4F -> E5 B1 8F；再加 1 个 NUL
        assertEquals(7, bytes.size)
        assertArrayEquals(
            byteArrayOf(0xE6.toByte(), 0x8A.toByte(), 0x95.toByte(),
                0xE5.toByte(), 0xB1.toByte(), 0x8F.toByte(), 0x00),
            bytes,
        )
        for (i in 0 until bytes.size - 1) {
            assertTrue("UTF-8 多字节串里不该出现 NUL", bytes[i] != 0.toByte())
        }
    }

    @Test
    fun `空字符串只有一个 NUL`() {
        assertArrayEquals(byteArrayOf(0x00), AccessoryIdentity.encodeSendString(""))
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
        assertEquals(AccessoryIdentity.MAX_STRING_LENGTH + 1, bytes.size)
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

    @Test
    fun `URI 必须指向真实发布页而不是占位符`() {
        // AOA SEND_STRING[4] 是"没装 App 时去哪下载"的唯一出口。
        // 以前填的是 https://uav.example.com/usbscreen —— 一个不存在的域名，
        // 等于把用户的自救路径指进了死路。这条用例防它被改回去。
        val uri = AccessoryIdentity.URI
        assertTrue("URI 不能是 example.com 占位符：$uri", !uri.contains("example.com"))
        assertTrue("URI 必须是 https：$uri", uri.startsWith("https://"))
        assertTrue(
            "URI 应指向 GitHub Releases 最新版：$uri",
            uri.contains("github.com") && uri.endsWith("/releases/latest")
        )
    }
}
