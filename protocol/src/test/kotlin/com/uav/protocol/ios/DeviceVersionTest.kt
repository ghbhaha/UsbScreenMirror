package com.uav.protocol.ios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeviceVersion] 与配对记录里版本字段的往返。
 *
 * ★ 本类测的是**诊断信息**，不是协议行为 —— 所以最重要的几条都是"不许影响原有行为"：
 * 老记录没有版本字段时，必须一切照旧（值为 UNKNOWN，且 `needsExtraGating` 为 false）。
 */
class DeviceVersionTest {

    // ---------- 主版本解析 ----------

    @Test
    fun `主版本只取第一段`() {
        assertEquals(27, DeviceVersion("27.2", "iPad8,9").major)
        assertEquals(18, DeviceVersion("18.5.1", null).major)
        assertEquals(17, DeviceVersion("17.0", null).major)
    }

    /**
     * ★ 解析失败必须返回 null，**不能返回 0**。
     *
     * 返回 0 会被 `>= 27` 判成"老版本"，于是在日志/界面里给出**错误结论**
     * （说"这台是老系统不用管"，而它其实只是没报版本）。这跟"未知按最坏处理"是两回事。
     */
    @Test
    fun `解析不了就返回 null 而不是 0`() {
        assertNull(DeviceVersion(null, null).major)
        assertNull(DeviceVersion("", null).major)
        assertNull(DeviceVersion("未知", null).major)
        assertNull(DeviceVersion("beta", null).major)
    }

    @Test
    fun `版本号前后有空白也能解出来`() {
        assertEquals(27, DeviceVersion(" 27.2 ", null).major)
    }

    // ---------- 门控判定 ----------

    @Test
    fun `27 及以上认为有额外门控`() {
        assertTrue(DeviceVersion("27.0", null).needsExtraGating)
        assertTrue(DeviceVersion("27.2", null).needsExtraGating)
        assertTrue(DeviceVersion("28.0", null).needsExtraGating)
    }

    @Test
    fun `26 及以下没有额外门控`() {
        assertFalse(DeviceVersion("26.5", null).needsExtraGating)
        assertFalse(DeviceVersion("18.5", null).needsExtraGating)
    }

    /**
     * ★ 未知版本 ⇒ `false`（不做任何版本相关判断）。
     *
     * 这是向后兼容的底线：老设备/老记录一律按原路径走，
     * **绝不能因为"读不到版本"就改变行为**。
     */
    @Test
    fun `未知版本一律不判定门控`() {
        assertFalse(DeviceVersion.UNKNOWN.needsExtraGating)
        assertNull(DeviceVersion.UNKNOWN.failureHint())
        assertNull(DeviceVersion("垃圾", null).failureHint())
    }

    // ---------- 提示 ----------

    @Test
    fun `新系统才给失败提示`() {
        val hint = DeviceVersion("27.2", "iPad8,9").failureHint()
        assertTrue(hint!!.contains("27.2"))
        assertTrue(hint.contains("iPad8,9"))
        // 必须明说"这不是协议不兼容"，否则用户会去查错方向
        assertTrue(hint.contains("不是协议不兼容"))
    }

    // ---------- 从回包取值 ----------

    @Test
    fun `从 PValue 回包取值`() {
        val dict = PValue.dict(
            "ProductVersion" to PValue.Str("27.2"),
            "ProductType" to PValue.Str("iPad8,9"),
            "ScreenLocked" to PValue.Bool(false),
        )
        val v = DeviceVersion.fromReply(dict)
        assertEquals("27.2", v.productVersion)
        assertEquals("iPad8,9", v.productType)
        assertEquals(27, v.major)
    }

    @Test
    fun `回包缺字段就是未知而不是崩`() {
        val v = DeviceVersion.fromReply(PValue.dict("ScreenLocked" to PValue.Bool(true)))
        assertNull(v.productVersion)
        assertNull(v.productType)
        assertFalse(v.needsExtraGating)

        val empty = DeviceVersion.fromReply(null)
        assertNull(empty.productVersion)
        assertFalse(empty.needsExtraGating)
    }

    @Test
    fun `summary 未知时用问号`() {
        assertTrue(DeviceVersion.UNKNOWN.summary().contains("?"))
        assertTrue(DeviceVersion("27.2", "iPad8,9").summary().contains("iPad8,9"))
    }

    // ---------- 配对记录往返 ----------

    private fun sampleRecord() = PairRecord(
        hostId = "H",
        systemBuid = Lockdown.SYSTEM_BUID,
        wifiMac = "aa:bb",
        hostCertPem = byteArrayOf(1),
        hostKeyPem = byteArrayOf(2),
        rootCertPem = byteArrayOf(3),
        deviceCertPem = byteArrayOf(4),
        escrowBag = byteArrayOf(5),
        deviceSerial = "SERIAL1",
    )

    @Test
    fun `版本能随记录往返`() {
        val rec = sampleRecord().withDeviceVersion(DeviceVersion("27.2", "iPad8,9"))
        val back = PairRecord.decode(rec.encode())!!
        assertEquals("27.2", back.deviceVersion.productVersion)
        assertEquals("iPad8,9", back.deviceVersion.productType)
    }

    /**
     * ★ 老记录（没有版本字段的 properties）必须能照常解码，且版本为 UNKNOWN。
     *
     * 这是**升级不能破坏既有用户**的底线 —— 用户升级 App 后配对记录要继续能用。
     */
    @Test
    fun `老记录没有版本字段也能解码`() {
        val legacy = sampleRecord().encode()
            .lineSequence()
            .filterNot { it.startsWith("productVersion") || it.startsWith("productType") }
            .joinToString("\n")
        val back = PairRecord.decode(legacy)!!
        assertEquals("SERIAL1", back.deviceSerial)
        assertNull(back.deviceVersion.productVersion)
        assertNull(back.deviceVersion.productType)
        assertFalse(back.deviceVersion.needsExtraGating)
    }

    @Test
    fun `withDeviceVersion 不动其它字段`() {
        val orig = sampleRecord()
        val updated = orig.withDeviceVersion(DeviceVersion("27.2", null))
        assertEquals(orig.hostId, updated.hostId)
        assertEquals(orig.deviceSerial, updated.deviceSerial)
        assertEquals(orig.escrowBag!!.toList(), updated.escrowBag!!.toList())
        assertFalse(updated.provisional)
    }
}