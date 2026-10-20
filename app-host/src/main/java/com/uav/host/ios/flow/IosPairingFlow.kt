package com.uav.host.ios.flow

import android.hardware.usb.UsbDevice
import kotlinx.coroutines.delay
import com.uav.host.ios.capture.Identity
import com.uav.host.ios.capture.IosFeatures
import com.uav.host.ios.capture.MuxLink
import com.uav.host.ios.capture.Pairing
import com.uav.host.ios.capture.QtTransport
import com.uav.protocol.ios.PairStore

/**
 * 「让 iPad 信任本机」这条路：配对 → 自证信任。
 *
 * 从 `IosActivity` 里整块搬出来的（思路、顺序、日志文案都没改），因为它是**一条独立流程**：
 * 输入是"设备 + 权限"，输出是"有没有配对记录"，中间不碰收流、不碰解码、不碰面板状态机。
 * 原先夹在"自动开始"和"重新武装"之间，读代码要跳三段才能看清。
 *
 * 本类**完全不碰界面**：配对前显示什么、配完切哪个 Stage，都是调用方的事
 * （见 `IosActivity.autoRun`）。这里只留纯函数式出口 —— 结论和一句日志。
 *
 * ## 为什么配对必须在**普通配置**下做
 *
 * 采集配置（5）里没有可用的 usbmux 会话上下文；多一步 activate 重枚举纯属自找。
 * 见 [Pairing] 的头注释。
 */
internal class IosPairingFlow(
    private val transport: QtTransport,
    private val filesDir: java.io.File,
    private val log: (String) -> Unit,
    /** 确保拿到 USB 权限（`IosActivity.ensurePermission`）。放这儿是为了让本类自成一条流程。 */
    private val ensurePermission: suspend (UsbDevice) -> Boolean,
    /** 把设备从采集配置切回普通配置。 */
    private val restoreToNormal: (UsbDevice) -> Boolean,
) {

    /**
     * 让 iPad 信任本机 —— 走一次完整的 lockdownd `Pair`。
     *
     * 这是「不依赖 PC 导凭证」的那一步：配对记录会存进 `filesDir/ios_pair.properties`，
     * 之后 [Identity.load] 优先用它。配对必须在**普通配置**下做，所以这里不 activate。
     *
     * **挂起版本**：自动流程要按「配对 → 投屏」的顺序串起来，返回 true 才继续投屏。
     */
    suspend fun pairNow(): Boolean {
        val dev = transport.findAppleDevice()
        if (dev == null) {
            log("未找到 Apple 设备（VID 05ac），请先插线")
            return false
        }
        log("配对目标：${dev.deviceName} pid=0x${dev.productId.toString(16)}")
        if (!ensurePermission(dev)) {
            log("未取得 USB 权限，中止")
            return false
        }
        transport.dumpConfigs(dev)

        val pairing = Pairing(transport, filesDir, log)
        // 上一轮若留在采集配置，先还原到普通态再配对
        if (dev.configurationCount >= IosFeatures.CAPTURE_CONFIG_COUNT) {
            log("设备停在采集配置 ⇒ 先还原到普通配置")
            restoreToNormal(dev)
            delay(400)
            val dev2 = transport.findAppleDevice()
            if (dev2 == null) {
                log("还原后设备消失")
                return false
            }
            if (!ensurePermission(dev2)) {
                log("还原后未取得权限")
                return false
            }
            if (pairing.run(dev2) == null) {
                log("配对未完成")
                return false
            }
        } else if (pairing.run(dev) == null) {
            log("配对未完成")
            return false
        }

        // 配对成功后立刻自证一次：StartSession → TLS → GetValue
        val key = currentDeviceKey() ?: Pairing.KEY_UNKNOWN
        val rec = Pairing.loadRecord(filesDir, key, log)
        if (rec != null && IosFeatures.TLS_WAIT_MS >= 8_000L) {
            log("自检：用刚配好的身份走一次 StartSession + TLS…")
            verifyTrust(rec.hostId)
        } else if (rec != null) {
            // TLS 窗口被缩短 ⇒ 握手必然来不及完成，自证只会打一行"失败"误导人
            log("（TLS 窗口 ${IosFeatures.TLS_WAIT_MS}ms ⇒ 跳过信任自检；配对结果以上面的写入为准）")
        }
        return Pairing.hasRecord(filesDir, key)
    }

    /** 用已保存的配对记录走一遍「StartSession → TLS → GetValue」，输出信任结论。 */
    fun verifyTrust(hostId: String) {
        val dev = transport.findAppleDevice()
        if (dev == null) { log("自检跳过：设备不可见"); return }
        runCatching {
            val rec = Pairing.loadRecord(filesDir, Pairing.deviceKey(dev)) ?: return
            val identity = Identity.fromRecord(rec)
            val cfg = (0 until dev.configurationCount).mapNotNull {
                runCatching { dev.getConfiguration(it) }.getOrNull()
            }.firstOrNull { transport.muxInterfaceIn(it) != null } ?: run {
                log("自检跳过：没有含 usbmux 的配置"); return
            }
            val itf = transport.muxInterfaceIn(cfg) ?: return
            val conn = transport.open(dev) ?: return
            try {
                transport.setConfiguration(conn, cfg)
                if (!transport.claim(conn, itf)) { log("自检：claim 失败"); return }
                val (epIn, epOut) = transport.bulkEndpoints(itf)
                if (epIn == null || epOut == null) { log("自检：端点不全"); return }
                val link = MuxLink(conn, epIn, epOut, identity, log)
                if (link.openRaw()) {
                    link.establishSession(identity)
                    val devKey = Pairing.deviceKey(dev)
                    if (link.trusted) {
                        log("★★ 自检通过：设备已信任 HostID=$hostId，可以点「开始采集」")
                        PairStore.confirm(filesDir, devKey, log)
                        // 顺手补记版本（纯诊断；老记录没这个字段，补上不用重新配对）
                        PairStore.recordVersion(filesDir, devKey, link.deviceVersion)
                    } else {
                        log("✗ 自检未通过：TLS=${link.tlsReady} 原因=${link.failed ?: "未取得 Value"}")
                        PairStore.dropProvisional(filesDir, devKey, log)
                    }
                } else {
                    log("✗ 自检隧道失败：${link.failed}")
                }
                link.shutdown("自检结束")
                runCatching { transport.release(conn, itf) }
            } finally {
                runCatching { conn.close() }
            }
        }.onFailure { log("自检异常：${it.message}") }
    }

    private fun currentDeviceKey(): String? =
        transport.findAppleDevice()?.let { Pairing.deviceKey(it) }
}