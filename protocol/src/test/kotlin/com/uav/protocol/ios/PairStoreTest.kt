package com.uav.protocol.ios

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 配对记录按设备分文件（[PairStore]）的单测。
 *
 * 背景：配对记录 = "主机 × 设备"这一对的关系（里面既有主机身份，也有设备专属的
 * `DeviceCertificate` / `EscrowBag`）。以前只有一个 `ios_pair.properties`，
 * 换一台 iPad 就把上一台的信任覆盖掉 ⇒ 换回来还要在 iPad 上重新点一次「信任」。
 */
class PairStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dir: File get() = tmp.root

    /** 造一条记录（证书内容只需可编码/解码，不需要真的是证书）。 */
    private fun rec(hostId: String, serial: String?): PairRecord = PairRecord(
        hostId = hostId,
        systemBuid = "BUID",
        wifiMac = "aa:bb:cc:dd:ee:ff",
        hostCertPem = "hostCert-$hostId".toByteArray(),
        hostKeyPem = "hostKey-$hostId".toByteArray(),
        rootCertPem = "rootCert-$hostId".toByteArray(),
        deviceCertPem = "deviceCert-$hostId".toByteArray(),
        escrowBag = byteArrayOf(1, 2, 3),
        deviceSerial = serial,
    )

    @Test
    fun `每台设备一条记录，互不覆盖`() {
        PairStore.save(dir, "SERIAL-A", rec("HOST-A", "SERIAL-A"))
        PairStore.save(dir, "SERIAL-B", rec("HOST-B", "SERIAL-B"))

        assertEquals(listOf("SERIAL-A", "SERIAL-B"), PairStore.knownKeys(dir))
        assertEquals("HOST-A", PairStore.load(dir, "SERIAL-A")?.hostId)
        assertEquals("HOST-B", PairStore.load(dir, "SERIAL-B")?.hostId)
        // 记录里的设备归属也写对了（迁移时靠它判定）
        assertEquals("SERIAL-A", PairStore.load(dir, "SERIAL-A")?.deviceSerial)
    }

    @Test
    fun `换回旧设备仍能读到它自己的记录`() {
        // ① 先用 A
        PairStore.save(dir, "SERIAL-A", rec("HOST-A", "SERIAL-A"))
        // ② 换成 B（配对会写 B 的文件，但绝不能动 A）
        val recA = rec("HOST-A", "SERIAL-A")
        PairStore.save(dir, "SERIAL-B", rec("HOST-B", "SERIAL-B"))
        // ③ 再换回 A：应当直接命中，不需要重新配对
        val back = PairStore.load(dir, "SERIAL-A")
        assertNotNull("换回旧设备必须能读到原记录（否则就要重新认证）", back)
        assertEquals("HOST-A", back!!.hostId)
        assertArrayEquals(recA.deviceCertPem, back.deviceCertPem)
        assertTrue(PairStore.has(dir, "SERIAL-A"))
    }

    @Test
    fun `旧版单文件先被暂时接管，跑通一次后才转正并清理`() {
        // 升级前的形态：只有一个 ios_pair.properties，且没有 deviceSerial
        File(dir, PairStore.LEGACY_FILE).writeText(rec("HOST-OLD", null).encode())
        assertTrue(PairStore.hasLegacy(dir))

        val adopted = PairStore.load(dir, "SERIAL-A") { }

        assertNotNull(adopted)
        assertEquals("HOST-OLD", adopted!!.hostId)
        assertEquals("SERIAL-A", adopted.deviceSerial)
        assertTrue("旧记录归属未知 ⇒ 只能先暂时接管", adopted.provisional)
        // ★ 旧文件先留着：万一认错人，还要还给对的那台设备
        assertTrue(PairStore.hasLegacy(dir))
        assertTrue(PairStore.recordFile(dir, "SERIAL-A").exists())

        // 真的跑通一次 TLS ⇒ 转正 + 清理旧单文件
        PairStore.confirm(dir, "SERIAL-A") { }
        val confirmed = PairStore.load(dir, "SERIAL-A")
        assertNotNull(confirmed)
        assertFalse(confirmed!!.provisional)
        assertFalse("转正之后旧单文件就该清掉", PairStore.hasLegacy(dir))
    }

    @Test
    fun `认错人时可作废临时记录，旧单文件仍留给对的那台`() {
        File(dir, PairStore.LEGACY_FILE).writeText(rec("HOST-OLD", null).encode())
        assertNotNull(PairStore.load(dir, "SERIAL-B"))

        PairStore.dropProvisional(dir, "SERIAL-B") { }

        assertFalse(PairStore.recordFile(dir, "SERIAL-B").exists())
        assertTrue("旧单文件必须留着，否则原设备就要重新认证", PairStore.hasLegacy(dir))
        // 插回原来那台（A）时照样能接管
        assertNotNull(PairStore.load(dir, "SERIAL-A"))
    }

    @Test
    fun `正常配对出来的记录不会被一次失败删掉`() {
        PairStore.save(dir, "SERIAL-A", rec("HOST-A", "SERIAL-A"))

        // 线材接触不良这类失败会走到 dropProvisional，但**不能**动正常记录
        PairStore.dropProvisional(dir, "SERIAL-A") { }

        assertTrue("正常记录不能被自动删，否则用户要重新点信任", PairStore.has(dir, "SERIAL-A"))
    }

    @Test
    fun `旧版记录属于别的设备时不接管`() {
        File(dir, PairStore.LEGACY_FILE).writeText(rec("HOST-OLD", "SERIAL-OTHER").encode())

        assertNull(PairStore.load(dir, "SERIAL-A"))
        assertTrue("没接管成功就不能删别人的记录", PairStore.hasLegacy(dir))
    }

    @Test
    fun `设备键不可用但只有一条记录时沿用它`() {
        PairStore.save(dir, "SERIAL-A", rec("HOST-A", "SERIAL-A"))

        // 序列号读不到（老机型 / 权限还没授予）⇒ 用唯一那条，别让用户白点一次信任
        assertEquals("HOST-A", PairStore.load(dir, PairStore.KEY_UNKNOWN)?.hostId)
    }

    @Test
    fun `设备键不可用且有多条记录时不猜`() {
        PairStore.save(dir, "SERIAL-A", rec("HOST-A", "SERIAL-A"))
        PairStore.save(dir, "SERIAL-B", rec("HOST-B", "SERIAL-B"))

        assertNull("两台以上就不能猜，宁可让用户在新设备上点一次信任", PairStore.load(dir, PairStore.KEY_UNKNOWN))
    }

    @Test
    fun `记录文件命名稳定`() {
        assertEquals("ios_pair_SERIAL-A.properties", PairStore.recordFile(dir, "SERIAL-A").name)
        assertEquals("ios_pair.properties", PairStore.recordFile(dir, PairStore.KEY_UNKNOWN).name)
    }
}
