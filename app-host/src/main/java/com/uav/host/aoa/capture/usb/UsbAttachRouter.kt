package com.uav.host.aoa.capture.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.uav.host.aoa.HostActivity
import com.uav.host.common.AndroidLogger
import com.uav.host.aoa.flow.AoaConnectFlow
import com.uav.host.aoa.flow.LinkCoordinator
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

    /** 连接段的流程（握手 / 等重枚举 / claim），本类只负责权限与回调翻译。 */
    private val connectFlow = AoaConnectFlow(usbManager, aoa, logger)

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
        // ★ 10.32：这里**绝不能**按"配件 PID"去强制重握手。
        //
        //   配件 PID 只说明设备已经处在配件配置里 —— 这正是手机端可能已经打开
        //   `/dev/usb_accessory` 在等我们的状态，此时该做的是**直接 claim + CNXN**（快路径），
        //   而不是再发一轮 AOA：`SET_/START_ACCESSORY` 会重置配件配置，把对端刚打开的
        //   fd 一起踢掉。之后手机端 12s 空闲看门狗释放 fd、我们更连不上，下一轮轮询又重握手
        //   —— 形成"越重连越连不上"的死循环（实测宿主日志里连续出现 START_ACCESSORY）。
        //
        //   "这个配件到底是不是我们的"由快路径失败后的自愈兜底：
        //   见 [LinkCoordinator] 的 `fastPathUnverified` / `onSelfHealRequest`，
        //   它会补一次 `forceHandshake = true`。
        onDeviceAttached(target, forceHandshake = false)
    }

    private fun pickCandidate(): UsbDevice? {
        val withBulk = usbManager.deviceList.values.filter { BulkPair.hasBulkPair(it) }
        if (withBulk.isEmpty()) return null
        return withBulk.firstOrNull { Aoa.isAccessoryPid(it.productId) }
            ?: withBulk.maxByOrNull { it.deviceId }
    }

    /**
     * 一次连接尝试：握手 / 等重枚举 / claim 全在 [AoaConnectFlow] 里。
     *
     * 这里只剩三件事：日志、权限（receiver 绑定在本类，搬不走）、把结果翻译成回调。
     */
    private fun runPipeline(device: UsbDevice, forceHandshake: Boolean) {
        logger.i(
            TAG,
            "attached: id=${device.deviceId} VID=0x${Integer.toHexString(device.vendorId)} " +
                "PID=0x${Integer.toHexString(device.productId)} name=${device.productName} " +
                "forceHandshake=$forceHandshake"
        )

        when (val outcome = connectFlow.connect(
            device = device,
            forceHandshake = forceHandshake,
            status = callback::onStatus,
            permission = ::permissionOrError,
        )) {
            is AoaConnectFlow.Outcome.Linked ->
                callback.onReady(outcome.connection, outcome.pair, outcome.aoaVersion, outcome.device)

            is AoaConnectFlow.Outcome.Failed -> callback.onError(outcome.message)
        }
    }

    /**
     * 拿权限，失败时给一句**能照做**的话。
     *
     * 两种失败要分开说：没有授权界面（定制 ROM 裁了 SystemUI）与"弹了但被拒/超时"，
     * 用户能做的事完全不同。
     */
    private fun permissionOrError(device: UsbDevice, isAccessory: Boolean): String? {
        if (ensurePermission(device)) return null
        return when {
            permissionUiMissing ->
                "本机没有「USB 授权」界面，拿不到 USB 权限 —— 这台设备无法投屏"
            isAccessory -> "配件设备授权被拒绝或超时（id=${device.deviceId}）"
            else -> "USB 授权被拒绝或超时（设备 ${device.deviceId}）"
        }
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

        // ★ 先确认"这台机器上有没有 USB 授权界面"。
        //
        //   `UsbManager.requestPermission()` 的弹窗由 AOSP **硬编码在 SystemUI** 里
        //   （`com.android.systemui.usb.UsbPermissionActivity`）。商显 / 一体机 / 广告机
        //   的定制 ROM 常把整个 SystemUI 裁掉（实测 CVTE V31103 / Android 7.1.2：
        //   `/system/priv-app` 里连 SystemUI.apk 都没有）——
        //   这时 requestPermission 既不弹窗也不回调，只能干等 60s 超时，然后每 60s 循环一次，
        //   界面永远停在"等待 USB 授权…"（用户只会以为程序坏了）。
        //   ⇒ 探一次组件，缺失就**立刻**失败并说明原因，一次都不重试。
        if (!hasUsbPermissionUi()) {
            permissionUiMissing = true
            logger.e(
                TAG,
                "本机没有 USB 授权界面（SystemUI 被裁剪：com.android.systemui.usb.UsbPermissionActivity 不存在）" +
                    " ⇒ 拿不到 USB 权限，AOA 投屏在这台设备上不可用"
            )
            callback.onStatus("本机没有 USB 授权界面（系统被裁剪）")
            return false
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

    /** 本机是否根本没有 USB 授权界面（见 [ensurePermission] 里的说明）。 */
    @Volatile
    private var permissionUiMissing = false

    /**
     * 本机有没有「USB 授权」界面。
     *
     * AOSP 把 USB 权限弹窗放在 **SystemUI** 里，所以判据就是"这两个组件存不存在"。
     * 商显/一体机/广告机的定制 ROM 会把 SystemUI 整个裁掉，此时这个探针直接命中。
     */
    private fun hasUsbPermissionUi(): Boolean {
        val pm = context.packageManager
        return USB_PERMISSION_ACTIVITIES.any { cls ->
            runCatching {
                pm.getActivityInfo(android.content.ComponentName("com.android.systemui", cls), 0)
            }.isSuccess
        }
    }

    /** 设备拔出（计划 2.10：与读写异常统一收敛到 onError）。 */    fun onDetached(device: UsbDevice?) {
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

        /** 轮询路径对同一台设备的最大重试次数。 */
        const val MAX_ATTEMPTS_PER_DEVICE = 3

        /** 自定义权限广播 action（包内私有）。 */
        const val ACTION_USB_PERMISSION = "com.uav.host.USB_PERMISSION"

        /**
         * USB 授权弹窗的组件名（**SystemUI 里**，AOSP 硬编码）。
         *
         * 两个都要试：正常授权用 `UsbPermissionActivity`；"已设置过默认行为"时走
         * `UsbConfirmActivity`。任一存在即说明这台机器有授权界面。
         */
        private val USB_PERMISSION_ACTIVITIES = listOf(
            "com.android.systemui.usb.UsbPermissionActivity",
            "com.android.systemui.usb.UsbConfirmActivity",
        )

        /** 构建 attach/detach 的 IntentFilter（供 Activity 注册 DETACHED 用）。 */
        fun detachFilter(): IntentFilter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                // 配件方向的拔出。`ACTION_USB_ACCESSORY_DETACHED` 是 API 21 常量，
                // 而 minSdk = 23 ⇒ 恒可用，不必再判 SDK_INT（10.28 降 minSdk 时顺手清掉的）。
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            }
    }
}
