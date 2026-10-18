package com.uav.protocol.aoa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 握手期版本协商（`CNXN.arg1`）。
 *
 * 这里钉的是**判定边界**，不是文案：`0` 必须被判成"老包"而不是"版本不一致"——
 * 两者对用户的含义完全不同（前者要"升级另一端"，后者要"两端对齐"），
 * 混在一起会让提示永远说不清到底该动哪台机器。
 */
class AppVersionsTest {

    @Test
    fun `版本相同`() {
        assertEquals(AppVersions.Compat.SAME, AppVersions.compat(3, 3))
        assertFalse(AppVersions.needsWarning(AppVersions.Compat.SAME))
    }

    @Test
    fun `对端没上报算老包而不是不一致`() {
        assertEquals(AppVersions.Compat.PEER_LEGACY, AppVersions.compat(3, 0))
        assertTrue(AppVersions.needsWarning(AppVersions.Compat.PEER_LEGACY))
    }

    @Test
    fun `明确不一致`() {
        assertEquals(AppVersions.Compat.DIFFERENT, AppVersions.compat(3, 2))
        assertEquals(AppVersions.Compat.DIFFERENT, AppVersions.compat(2, 3))
        assertTrue(AppVersions.needsWarning(AppVersions.Compat.DIFFERENT))
    }

    @Test
    fun `负数或异常值按老包处理`() {
        // arg1 是从线上字节读出来的，理论上不该为负；真出现了也别让它变成"版本不一致"
        assertEquals(AppVersions.Compat.PEER_LEGACY, AppVersions.compat(3, -1))
    }
}
