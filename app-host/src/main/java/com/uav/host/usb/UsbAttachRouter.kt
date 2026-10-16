package com.uav.host.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.uav.host.AndroidLogger
import com.uav.protocol.Logger
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把「插线 → 权限 → AOA 握手 → 等重枚举 → 再授权 → claim 接口」整条流程收敛到一个类（计划 2.1~2.10）。
 *
 * 所有 USB 调用都在**单线程 executor** 上执行（计划 2.11：UI 线程做 USB 操作会 ANR）。
 *
 * 两个特别容易被忽略、但必须做对的点：
 *
 * 1. **重枚举之后要重新申请权限。** 配件设备是一个"新设备"（deviceId 变了），
 *    靠插线时的自动授权在部分机型上不够（计划 2.7）。
 * 2. **不能依赖 `EXTRA_PERMISSION_GRANTED` 这个 extra。**
 *    计划 2.2 要求 PendingIntent 必须带 `FLAG_IMMUTABLE`（API 31+ 否则直接崩），
 *    而 IMMUTABLE 的 PendingIntent 在系统 `send()` 填充 extras 时会被拒绝填充，
 *    该 extra 可能拿不到。**稳健做法是收到广播后直接 `usbManager.hasPermission(device)` 复核。**
 */
class UsbAttachRouter(
    private val context: Context,
    private val logger: Logger = AndroidLogger,
    private val callback: Callback,
) {

    interface Callback {
        /** UI 状态文本（扫描中 / 等待授权 / 握手中 / 已连接 / 错误）。 */
        fun onStatus(text: String) {}

        /** 链路就绪，可以开始协议会话。 */
        fun onReady(
            connection: UsbDeviceConnection,
            pair: BulkPair,
            aoaVersion: Int,
            device: UsbDevice,
        )

        /** 任一环节失败（统一收敛点，计划 2.10）。 */
        fun onError(message: String, cause: Throwable? = null) {}
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val aoa = AoaHandshake(usbManager, logger)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "uav-usb-worker").apply { isDaemon = true }
    }

    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * 已尝试过握手的 deviceId -> 次数。避免轮询路径反复重试同一台失败设备，
     * 但对**瞬时不稳**（实测偶发重枚举超时，重插即好）给出有限次自愈机会。
     */
    private val attemptCounts: MutableMap<Int, Int> = java.util.concurrent.ConcurrentHashMap()

    @Volatile
    private var permissionLatch: CountDownLatch = CountDownLatch(0)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            // ★ 不信 extra，直接复核真实权限状态
            val granted = device != null && usbManager.hasPermission(device)
            logger.i(TAG, "permission broadcast: device=${device?.deviceId} granted=$granted")
            permissionLatch.countDown()
        }
    }

    fun start() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * 入口：收到 `USB_DEVICE_ATTACHED`（或用户手动点"扫描"）时调用。
     *
     * @param forceHandshake 强制走完整 AOA 握手（51→52×6→53），**即使设备已经是配件模式**。
     *
     * ★ 实测踩到的坑：配件身份（manufacturer/model/version）是**上一次做握手的那个宿主 App**
     *   写进手机的，而当前宿主**无法把手机上的配件身份读回来**。因此当手机已被"别的宿主 App"
     *   切成配件模式时，跳过握手直连只会连到一个**没有 App 认领的孤儿链路**上 ——
     *   现象是 `interface claimed` 成功 → `等待手机 CNXN 超时` → bulk 写返回 -1。
     */
    fun onDeviceAttached(device: UsbDevice, forceHandshake: Boolean = false) {
        if (closed.get()) return
        if (!busy.compareAndSet(false, true)) {
            logger.i(TAG, "已有握手在进行中，忽略重复 attach")
            return
        }
        executor.execute {
            try {
                runPipeline(device, forceHandshake)
            } catch (t: Throwable) {
                logger.e(TAG, "USB pipeline failed: ${t.message}", t)
                callback.onError(t.message ?: t.javaClass.simpleName, t)
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * 从当前已连接设备里挑一个候选（用户手动触发扫描时用）。
     *
     * 选择规则（**不能瞎挑，宿主上还挂着内建面板的 HID 触摸控制器**）：
     *  1. 只考虑含 BULK IN/OUT 端点对的设备（见 [BulkPair.hasBulkPair]）；
     *  2. 优先已经是配件模式的；
     *  3. 否则取 `deviceId` 最大的（即最近一次枚举进来的那台）。
     *
     * @param forceHandshake 见 [onDeviceAttached]
     */
    fun scanAndAttach(forceHandshake: Boolean = false) {
        // 手动扫描 = 用户明确要求重试，清空自动重试的计数
        attemptCounts.clear()
        val all = usbManager.deviceList.values
        if (all.isEmpty()) {
            callback.onStatus("未发现 USB 设备，请插线")
            return
        }
        val target = pickCandidate()
        if (target == null) {
            callback.onStatus(
                "未找到可用的手机设备（当前 ${all.size} 个 USB 设备，均无 BULK 端点）"
            )
            logger.w(TAG, "scan 后无候选：${aoa.describeDevices()}")
            return
        }
        logger.i(
            TAG,
            "scan picked id=${target.deviceId} PID=0x${Integer.toHexString(target.productId)} " +
                "(forceHandshake=$forceHandshake) 全部设备: ${aoa.describeDevices()}"
        )
        onDeviceAttached(target, forceHandshake)
    }

    /**
     * **前台轮询兜底**：不依赖 `USB_DEVICE_ATTACHED` 广播。
     *
     * 实测宿主设备（BOE P40UCH）在手机重枚举后**只派发了 DETACHED、没有派发 ATTACHED** ——
     * 只靠广播会导致"插线即投"完全失效。这里由 Activity 在前台定时调用，
     * 发现没尝试过的设备就自动走一遍握手，对广播的可靠性不做任何假设。
     */
    fun pollOnce() {
        if (closed.get() || busy.get()) return
        val target = pickCandidate() ?: return
        // 同一 deviceId 最多重试 MAX_ATTEMPTS_PER_DEVICE 次 —— 实测重枚举偶发超时（重插即好），
        // 给几次自愈机会；超出后停下，等用户手动 Scan 或重新插拔
        val n = (attemptCounts[target.deviceId] ?: 0) + 1
        attemptCounts[target.deviceId] = n
        if (n > MAX_ATTEMPTS_PER_DEVICE) return
        logger.i(TAG, "轮询发现设备 id=${target.deviceId}（第 $n 次尝试），自动建立连接")
        onDeviceAttached(target, forceHandshake = Aoa.isAccessoryPid(target.productId))
    }

    private fun pickCandidate(): UsbDevice? {
        val withBulk = usbManager.deviceList.values.filter { BulkPair.hasBulkPair(it) }
        if (withBulk.isEmpty()) return null
        return withBulk.firstOrNull { Aoa.isAccessoryPid(it.productId) }
            ?: withBulk.maxByOrNull { it.deviceId }
    }

    private fun runPipeline(device: UsbDevice, forceHandshake: Boolean) {
        logger.i(
            TAG,
            "attached: id=${device.deviceId} VID=0x${Integer.toHexString(device.vendorId)} " +
                "PID=0x${Integer.toHexString(device.productId)} name=${device.productName} " +
                "forceHandshake=$forceHandshake"
        )

        // 已经是配件设备（例如 App 重启后线还插着）-> 先试最快的直连路径。
        // ⚠️ 这条路径**无法确认配件身份是不是我们的**，所以上层在 CNXN 超时后
        //    会用 forceHandshake = true 再回来一次（自愈，见 HostActivity）。
        if (Aoa.isAccessoryPid(device.productId) && !forceHandshake) {
            callback.onStatus("检测到配件设备，直接建立通道…")
            connectToAccessory(device, aoaVersion = 0)
            return
        }

        if (Aoa.isAccessoryPid(device.productId)) {
            callback.onStatus("强制重握手：把配件身份改写为 UAV/usbscreen…")
        } else {
            callback.onStatus("检测到设备，等待 USB 授权…")
        }
        if (!ensurePermission(device)) {
            callback.onError("USB 授权被拒绝或超时（设备 ${device.deviceId}）")
            return
        }

        callback.onStatus("握手中（AOA v2）…")
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            callback.onError("openDevice 返回 null —— 通常是没有 USB 权限")
            return
        }

        val version: Int
        try {
            // ★ 直接发控制请求，不要先 claimInterface（计划 2.3 红线）
            version = aoa.handshake(device, connection)
            if (version < 2) {
                callback.onError("设备不支持 AOAv2（version=$version）")
                return
            }
        } finally {
            // ★ 53 号发送后手机立即重枚举，旧句柄失效，必须马上关闭（计划 2.5）
            runCatching { connection.close() }
        }

        callback.onStatus("等待手机切换为配件模式…")
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
        )
        if (accessory == null) {
            callback.onError(
                "等待重枚举超时。检查：① 手机是否弹出『打开该 App 处理 USB 配件』；" +
                    "② OTG 线是否支持数据传输；" +
                    "③ 若手机原本已被其它宿主 App 占用为配件，请拔线重插一次。" +
                    "（会自动重试：手机端若持着一个收不到应答的配件 fd，会在约 12s 后自行释放，" +
                    "届时下一次握手即可成功）"
            )
            return
        }

        connectToAccessory(accessory, version)
    }

    private fun connectToAccessory(accessory: UsbDevice, aoaVersion: Int) {
        // ★ 重枚举后是"新设备"，权限可能没继承过来，必须重新申请（计划 2.7）
        callback.onStatus("等待配件授权…")
        if (!ensurePermission(accessory)) {
            callback.onError("配件设备授权被拒绝或超时（id=${accessory.deviceId}）")
            return
        }

        val connection = usbManager.openDevice(accessory)
        if (connection == null) {
            callback.onError("openDevice(accessory) 返回 null")
            return
        }

        val pair = claimBulkPair(connection, accessory, logger)
        if (pair == null) {
            runCatching { connection.close() }
            callback.onError("claimInterface 失败（接口被占用或无权限）")
            return
        }

        callback.onStatus("已连接，开始协议会话")
        callback.onReady(connection, pair, aoaVersion, accessory)
    }

    /**
     * 确保拿到设备的 USB 权限，必要时弹系统授权框并等待广播。
     *
     * PendingIntent 必须带 `FLAG_IMMUTABLE`（计划 2.2 / 红线），API 31+ 不指定会直接抛异常。
     */
    private fun ensurePermission(device: UsbDevice, timeoutMs: Long = 60_000): Boolean {
        if (usbManager.hasPermission(device)) {
            logger.i(TAG, "already has permission for device ${device.deviceId}")
            return true
        }

        val latch = CountDownLatch(1)
        permissionLatch = latch

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent, flags)

        logger.i(TAG, "requesting permission for device ${device.deviceId}")
        usbManager.requestPermission(device, pending)

        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ok) {
            logger.w(TAG, "permission wait timed out after ${timeoutMs}ms")
            return false
        }
        return usbManager.hasPermission(device)
    }

    /** 设备拔出（计划 2.10：与读写异常统一收敛到 onError）。 */
    fun onDetached(device: UsbDevice?) {
        logger.i(TAG, "USB detached: ${device?.deviceId}")
        // 允许同一台设备重新插入后从头再试
        device?.let { attemptCounts.remove(it.deviceId) }
        callback.onError("USB 设备已拔出")
    }

    /**
     * 重置自动重试计数（链路断开时由上层调用）。
     *
     * 断线是**新情况** —— 设备可能马上回来、也可能刚被重新枚举。若不重置，
     * 几次偶发断线就会把 [MAX_ATTEMPTS_PER_DEVICE] 的配额耗光，
     * 「自动重连」便永久失效（这正是加自动重连前的老毛病）。
     */
    fun resetAttempts() {
        attemptCounts.clear()
    }

    /** 当前是否已有设备被判定为"试够了"。仅用于日志/诊断。 */
    fun exhaustedDeviceIds(): List<Int> =
        attemptCounts.filterValues { it >= MAX_ATTEMPTS_PER_DEVICE }.keys.toList()

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { context.unregisterReceiver(receiver) }
        executor.shutdownNow()
    }

    companion object {
        const val TAG = "UAV-Host"

        /** 普通模式手机切换为配件时的重枚举等待（计划 2.6：200ms × 25 = 5s）。 */
        const val RENUM_TIMEOUT_MS = 5_000L

        /**
         * 手机**本来就在配件模式**时的等待：预期它不会重新枚举，
         * 所以只短暂确认一下"有没有新设备冒出来"，然后退回复用原设备。
         */
        const val RENUM_TIMEOUT_ALREADY_ACCESSORY_MS = 1_500L

        /** 轮询路径对同一台设备的最大重试次数。 */
        const val MAX_ATTEMPTS_PER_DEVICE = 3

        /** 自定义权限广播 action（包内私有）。 */
        const val ACTION_USB_PERMISSION = "com.uav.host.USB_PERMISSION"

        /** 构建 attach/detach 的 IntentFilter（供 Activity 注册 DETACHED 用）。 */
        fun detachFilter(): IntentFilter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
                }
            }
    }
}
