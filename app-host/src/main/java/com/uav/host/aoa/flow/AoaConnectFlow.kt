package com.uav.host.aoa.flow

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.uav.host.aoa.capture.Aoa
import com.uav.host.aoa.capture.AoaHandshake
import com.uav.host.aoa.capture.BulkPair
import com.uav.host.aoa.capture.claimBulkPair
import com.uav.protocol.Logger

/**
 * 「一台设备 → 一条已 claim 好的 bulk 连接」的全部动作。
 *
 * 从 `UsbAttachRouter.runPipeline()` / `connectToAccessory()` 整块搬出来的。
 * 分界线的依据是**依赖**：
 *
 * - 需要 `BroadcastReceiver`、`PendingIntent`、`permissionUiMissing` 的 => 留在 router（权限）；
 * - 只跟 `UsbManager` / `AoaHandshake` / `BulkPair` 打交道的 => 在这里（连接）。
 *
 * 权限那一步通过 [Permission] 回调注入：它是 receiver 驱动的异步等待，
 * 搬进来就得把 receiver 一起搬，反而更乱。
 *
 * ## 顺序红线（逐字保留）
 *
 * 1. **51/52/53 必须在 `openDevice()` 之后直接发，不能先 claimInterface**（AOA 规范）。
 * 2. **53 号发出后手机立即重枚举，旧句柄失效 ⇒ 必须马上 `close()`**。
 * 3. 重枚举后是"新设备"，**权限要重新申请**（不继承）。
 * 4. 重枚举判据分两种：普通模式必得等"新出现的那台"；本来就在配件模式（强制重握手）
 *    则**不会重枚举**，必须接受同一台，否则必然超时。
 */
internal class AoaConnectFlow(
    private val usbManager: UsbManager,
    private val aoa: AoaHandshake,
    private val logger: Logger,
) {

    /** 权限回调：成功返回 `null`，失败返回**给用户看的**原因。`isAccessory` 只影响措辞。 */
    fun interface Permission {
        fun ensure(device: UsbDevice, isAccessory: Boolean): String?
    }

    sealed interface Outcome {
        /** 拿到一条 claim 好的连接，可以交给 [AoaStreamFlow] 建会话了。 */
        class Linked(
            val connection: UsbDeviceConnection,
            val pair: BulkPair,
            val aoaVersion: Int,
            val device: UsbDevice,
        ) : Outcome

        /** 失败原因**已经是人话**，调用方直接上报即可。 */
        class Failed(val message: String) : Outcome
    }

    /**
     * 跑一次连接尝试。**阻塞**（控制请求 + 等重枚举 + claim），只能在 worker 线程调用。
     *
     * @param forceHandshake 即使设备已是配件模式，也强制走完整 AOA 握手（用于自愈）
     */
    fun connect(
        device: UsbDevice,
        forceHandshake: Boolean,
        status: (String) -> Unit,
        permission: Permission,
    ): Outcome {
        // 已经是配件设备（例如 App 重启后线还插着）→ 先试最快的直连路径。
        // ⚠️ 这条路径**无法确认配件身份是不是我们的**，所以上层在 CNXN 超时后
        //    会用 forceHandshake = true 再回来一次（自愈）。
        if (Aoa.isAccessoryPid(device.productId) && !forceHandshake) {
            status("检测到配件设备，直接建立通道…")
            return connectToAccessory(device, aoaVersion = 0, status, permission)
        }

        if (Aoa.isAccessoryPid(device.productId)) {
            status("强制重握手：把配件身份改写为 UAV/usbscreen…")
        } else {
            status("检测到设备，等待 USB 授权…")
        }
        permission.ensure(device, isAccessory = false)?.let { return Outcome.Failed(it) }

        status("握手中（AOA v2）…")
        val connection = usbManager.openDevice(device)
            ?: return Outcome.Failed("openDevice 返回 null —— 通常是没有 USB 权限")

        val version: Int
        try {
            // ★ 直接发控制请求，不要先 claimInterface（计划 2.3 红线）
            version = aoa.handshake(device, connection)
            if (version < 2) return Outcome.Failed("设备不支持 AOAv2（version=$version）")
        } finally {
            // ★ 53 号发送后手机立即重枚举，旧句柄失效，必须马上关闭（计划 2.5）
            runCatching { connection.close() }
        }

        status("等待手机切换为配件模式…")
        // ⚠️ 两种情形必须区别对待（实测踩到）：
        //
        //  (a) 手机原本在**普通模式** → 53 号会触发真正的 USB 重枚举，
        //      此时必须等到"新出现的那台"（deviceId 变化），否则会立刻拿到旧设备。
        //  (b) 手机**本来就在配件模式**（强制重握手）→ 实测 53 号**不会重新枚举**，
        //      只是按新字符串把 USB_ACCESSORY_ATTACHED 重新派发给匹配的 App
        //      （USB 描述符不变，宿主侧 deviceId 保持不变）。
        //      此时如果死等"新设备"必然超时 —— 于是退而接受同一台设备。
        val alreadyAccessory = Aoa.isAccessoryPid(device.productId)
        val accessory = aoa.awaitAccessoryDevice(
            previousDeviceId = device.deviceId,
            timeoutMs = if (alreadyAccessory) RENUM_TIMEOUT_ALREADY_ACCESSORY_MS else RENUM_TIMEOUT_MS,
            allowSameDevice = alreadyAccessory,
        ) ?: return Outcome.Failed(
            "等待重枚举超时。检查：① 手机是否弹出『打开该 App 处理 USB 配件』；" +
                "② OTG 线是否支持数据传输；" +
                "③ 若手机原本已被其它宿主 App 占用为配件，请拔线重插一次。" +
                "（会自动重试：手机端若持着一个收不到应答的配件 fd，会在约 12s 后自行释放，" +
                "届时下一次握手即可成功）"
        )

        return connectToAccessory(accessory, version, status, permission)
    }

    private fun connectToAccessory(
        accessory: UsbDevice,
        aoaVersion: Int,
        status: (String) -> Unit,
        permission: Permission,
    ): Outcome {
        // ★ 重枚举后是"新设备"，权限可能没继承过来，必须重新申请（计划 2.7）
        status("等待配件授权…")
        permission.ensure(accessory, isAccessory = true)?.let { return Outcome.Failed(it) }

        val connection = usbManager.openDevice(accessory)
            ?: return Outcome.Failed("openDevice(accessory) 返回 null")

        val pair = claimBulkPair(connection, accessory, logger)
            ?: run {
                runCatching { connection.close() }
                return Outcome.Failed("claimInterface 失败（接口被占用或无权限）")
            }

        status("已连接，开始协议会话")
        return Outcome.Linked(connection, pair, aoaVersion, accessory)
    }

    companion object {
        /** 普通模式手机切换为配件时的重枚举等待（200ms × 25 = 5s）。 */
        const val RENUM_TIMEOUT_MS = 5_000L

        /**
         * 手机**本来就在配件模式**时的等待：预期它不会重新枚举，
         * 所以只短暂确认一下"有没有新设备冒出来"，然后退回复用原设备。
         */
        const val RENUM_TIMEOUT_ALREADY_ACCESSORY_MS = 1_500L
    }
}
