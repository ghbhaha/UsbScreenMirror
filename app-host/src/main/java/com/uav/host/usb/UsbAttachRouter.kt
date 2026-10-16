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
     */
    fun onDeviceAttached(device: UsbDevice) {
        if (closed.get()) return
        if (!busy.compareAndSet(false, true)) {
            logger.i(TAG, "已有握手在进行中，忽略重复 attach")
            return
        }
        executor.execute {
            try {
                runPipeline(device)
            } catch (t: Throwable) {
                logger.e(TAG, "USB pipeline failed: ${t.message}", t)
                callback.onError(t.message ?: t.javaClass.simpleName, t)
            } finally {
                busy.set(false)
            }
        }
    }

    /** 从当前已连接设备里挑一个候选（用户手动触发扫描时用）。 */
    fun scanAndAttach() {
        val candidates = usbManager.deviceList.values
        if (candidates.isEmpty()) {
            callback.onStatus("未发现 USB 设备，请插线")
            return
        }
        // 已经是配件模式 -> 直接走
        val accessory = candidates.firstOrNull { Aoa.isAccessoryPid(it.productId) }
        val target = accessory ?: candidates.first()
        logger.i(TAG, "scan picked: ${aoa.describeDevices()}")
        onDeviceAttached(target)
    }

    private fun runPipeline(device: UsbDevice) {
        logger.i(
            TAG,
            "attached: id=${device.deviceId} VID=0x${Integer.toHexString(device.vendorId)} " +
                "PID=0x${Integer.toHexString(device.productId)} name=${device.productName}"
        )

        // 已经是配件设备（例如 App 重启后线还插着）-> 跳过握手，直接走后半段
        if (Aoa.isAccessoryPid(device.productId)) {
            callback.onStatus("检测到配件设备，直接建立通道")
            connectToAccessory(device, aoaVersion = 0)
            return
        }

        callback.onStatus("检测到设备，等待 USB 授权…")
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
        val accessory = aoa.awaitAccessoryDevice(device.deviceId)
        if (accessory == null) {
            callback.onError(
                "等待重枚举超时（5s）。检查：手机是否弹出『打开该 App 处理 USB 配件』、" +
                    "OTG 线是否支持数据传输"
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
        callback.onError("USB 设备已拔出")
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { context.unregisterReceiver(receiver) }
        executor.shutdownNow()
    }

    companion object {
        const val TAG = "UAV-Host"

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
