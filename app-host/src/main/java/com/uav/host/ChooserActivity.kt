package com.uav.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.content.IntentCompat
import com.uav.host.common.DebugTools
import com.uav.host.common.FocusRing
import com.uav.host.ios.IosActivity
import com.uav.host.aoa.HostActivity
import com.uav.host.aoa.usb.BulkPair
import com.uav.host.aoa.usb.UsbAttachRouter
import com.uav.protocol.usb.UsbDeviceClassify
import com.uav.protocol.usb.UsbDeviceKind

/**
 * 入口选择页：决定这次走哪条投屏链路。
 *
 * ## 为什么要有这一页
 *
 * 两条链路对 USB 的**角色是相反的**，不能在一个 Activity 里"顺便支持一下"：
 *  - AOA：宿主去改写手机（51/52×6/53），之后手机变成配件，宿主从 accessory fd 收流；
 *  - QuickTime：宿主是标准 USB 主机，去 claim iPad 的 `0xFF/0x2A` 接口收流。
 *
 * 更要紧的是：AOA 侧挑设备的规则是「有 bulk 端点就当候选」，而 **iPad 也有 bulk 端点**，
 * 所以把设备判定放在握手之前、并且放在一个不碰 USB 的地方（本页），是最省事的隔离。
 *
 * ## 两件事它只做一件
 *
 * 1. 用户点卡片 → 直接进对应页面；
 * 2. 插线拉起了本页（带 `EXTRA_DEVICE`）→ 按 VID/PID 分类，**能确定就直接跳**，
 *    不确定就停在这一页让用户自己点。
 */
class ChooserActivity : Activity() {

    /**
     * 已经见过的 USB 设备（`deviceId` 基线）。
     *
     * 轮询只对**基线之外新出现**的设备做跳转判定 —— 这条约束是体验上的硬要求：
     * 用户手动点图标进本页、而 iPad 正插着时，不该被轮询立刻踢去 iPad 页，
     * 否则"想选安卓手机"就永远点不到了。设备拔出时从基线移除，同一条线再插回来仍会触发。
     */
    private val seenDeviceIds = HashSet<Int>()

    private val handler = Handler(Looper.getMainLooper())

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollNewDevices()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chooser)

        val cardAoa = findViewById<View>(R.id.card_aoa)
        val cardIos = findViewById<View>(R.id.card_ios)
        cardAoa.setOnClickListener {
            startActivity(Intent(this, HostActivity::class.java))
        }
        cardIos.setOnClickListener {
            startActivity(Intent(this, IosActivity::class.java))
        }

        // ★ 遥控器/D-pad 焦点：本页也是机顶盒的主要入口，必须能移焦。
        //   1) 焦点环 —— 系统自带高亮是 API 26 才有，这台是 Android 7.1，
        //      而且 selector 的 state_focused 在触摸模式下不生效 ⇒ 统一走 FocusRing。
        //   2) 初始焦点 —— 不给的话第一下方向键没有"起点"，看着像遥控器坏了。
        //      用 post{} 推到下一帧：onCreate 时 View 还没 attach，
        //      requestFocus() 会因 isShown==false **静默失败**。
        FocusRing.install(findViewById(android.R.id.content))
        cardAoa.post { if (!cardAoa.hasFocus()) cardAoa.requestFocus() }

        // 基线 = 进本页时已经插着的东西，它们不参与"新插入"判定
        seenDeviceIds.addAll(usbDevices().map { it.deviceId })

        refreshDetected()
        // 由插线拉起时（intent 带 EXTRA_DEVICE）能确定类型就直接跳
        handleAttach(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAttach(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshDetected()
        // ★ 每次可见都对齐基线，不能只在 onCreate 建一次：
        //   iPad 在 iOS 页里被拔插过会拿到**新的 deviceId**，若基线还停在进去之前的值，
        //   一按返回键回到本页就会被判成"新插入"，立刻又被推回 iOS 页 —— 用户退不出来。
        //   反过来也不破坏插线直达：本页可见期间插上的设备仍然不在基线里。
        seenDeviceIds.addAll(usbDevices().map { it.deviceId })
        // ★ 前台轮询兜底：本机型（BOE P40UCH）的 USB host 栈在设备插入时**不派发**
        //   `USB_DEVICE_ATTACHED`，只派发 DETACHED（AOA 侧早就因此加了轮询，见
        //   `UsbAttachRouter.startForegroundPolling`）。合并后入口搬到这里，
        //   如果只依赖 attach intent，插 iPad / 插手机都会停在选择页不动。
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    /**
     * 插线直达：能确定设备类型就跳过选择页。
     *
     * ⚠️ 这里**只读设备描述符**，不发任何控制请求 —— 分类错了顶多是"没自动跳"，
     * 不会把对方设备弄成怪状态。
     */
    private fun handleAttach(intent: Intent?) {
        val action = intent?.action
        if (DebugTools.ENABLED) Log.i(TAG, "handleAttach action=$action")

        val extra = intent?.let {
            IntentCompat.getParcelableExtra(it, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        }

        // 只有在「确实是被插线拉起」时才允许跳转：
        // 用户手动点图标进选择页（action=MAIN）时不该被正插着的设备踢走。
        val attached = action == UsbManager.ACTION_USB_DEVICE_ATTACHED || extra != null
        if (!attached) {
            if (DebugTools.ENABLED) Log.i(TAG, "  非插线拉起，停在本页")
            return
        }

        // ★ ROM 差异：部分机型（以及走 meta-data 过滤器匹配时）派发的 intent 里
        //   不带 EXTRA_DEVICE。此时退回「当前设备表里唯一的 Apple 设备」——
        //   选择页存在的意义就是"能确定才直达"，所以只认同类设备唯一的情况，
        //   有两台及以上就老老实实停在本页让用户选。
        val device = extra ?: soleAppleDevice()
        if (DebugTools.ENABLED) {
            Log.i(
                TAG,
                "  extra=${extra?.let { "${it.vendorId.toString(16)}:${it.productId.toString(16)}" }}" +
                    " 选定=${device?.let { "${it.vendorId.toString(16)}:${it.productId.toString(16)}" }}",
            )
        }
        if (device == null) return
        navigateFor(device)
    }

    /**
     * 轮询兜底：本机型不派发 attach intent 时，靠设备表的变化补上"插线即达"。
     *
     * 只处理**新出现**的设备（基线之外的），已插着的不算 —— 见 [seenDeviceIds]。
     */
    private fun pollNewDevices() {
        val devices = usbDevices()
        val current = devices.map { it.deviceId }.toSet()
        // 拔掉的从基线里移出，同一条线再插回来仍然算"新出现"
        seenDeviceIds.retainAll(current)

        val fresh = devices.filter { it.deviceId !in seenDeviceIds }
        seenDeviceIds.addAll(current)
        if (fresh.isEmpty()) return

        if (DebugTools.ENABLED) {
            Log.i(TAG, "轮询到新设备：" + fresh.joinToString { "${it.deviceId}=${it.vendorId.toString(16)}:${it.productId.toString(16)}" })
        }
        refreshDetected()
        // 一次只跳一台：多台同时插入时按 first 处理，其余留在选择页
        for (device in fresh) {
            if (navigateFor(device)) return
        }
    }

    /**
     * 按分类跳到对应投屏页。
     *
     * @return 已发生跳转（Activity 已 finish）为 true。
     */
    private fun navigateFor(device: UsbDevice): Boolean {
        when (UsbDeviceClassify.kindOf(device.vendorId, device.productId)) {
            UsbDeviceKind.IOS -> {
                if (DebugTools.ENABLED) Log.i(TAG, "  → iOS 投屏页")
                startActivity(Intent(this, IosActivity::class.java))
                finish()
                return true
            }
            UsbDeviceKind.AOA_ACCESSORY -> {
                // 已经是配件态：AOA 页会有更快的一条直连路径
                if (DebugTools.ENABLED) Log.i(TAG, "  → AOA 投屏页（配件态）")
                startActivity(Intent(this, HostActivity::class.java))
                finish()
                return true
            }
            UsbDeviceKind.IGNORE -> {
                // 普通模式下的手机：PID 是厂商自己的，分类只知道"不是配件、不是 Apple"。
                // 这里退回合并前的老启发式（有 bulk 端点对就当候选），否则"插线即达"
                // 会退化成"每次都要手点一下"——那是明确的行为倒退。
                // 内建 HID 触摸屏 / 键鼠都是中断端点，没有 bulk 对，不会被误挑。
                if (BulkPair.hasBulkPair(device)) {
                    if (DebugTools.ENABLED) Log.i(TAG, "  → AOA 投屏页（bulk 端点对兜底）")
                    startActivity(Intent(this, HostActivity::class.java))
                    finish()
                    return true
                }
                // 其余（触摸屏、键鼠、U 盘）停在选择页，不做任何动作
            }
        }
        return false
    }

    private fun usbDevices(): List<UsbDevice> {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        return usb.deviceList.values.toList()
    }

    /** 设备表里唯一的 Apple 设备；0 台或多台都返回 null（不猜）。 */
    private fun soleAppleDevice(): UsbDevice? =
        usbDevices().filter {
            UsbDeviceClassify.kindOf(it.vendorId, it.productId) == UsbDeviceKind.IOS
        }.singleOrNull()

    /** 把"现在插着什么"如实显示一行，帮用户判断该点哪个。 */
    private fun refreshDetected() {
        val devices = usbDevices()
        val aoas = devices.filter {
            UsbDeviceClassify.kindOf(it.vendorId, it.productId) == UsbDeviceKind.AOA_ACCESSORY
        }
        val apples = devices.filter {
            UsbDeviceClassify.kindOf(it.vendorId, it.productId) == UsbDeviceKind.IOS
        }
        val text = when {
            aoas.isNotEmpty() -> getString(R.string.chooser_detected_aoa, aoas.size)
            apples.isNotEmpty() -> getString(R.string.chooser_detected_ios, apples.size)
            else -> getString(R.string.chooser_detected_none)
        }
        findViewById<TextView>(R.id.chooser_detected).text = text
    }

    private companion object {
        /** 入口分类的日志标签 —— 这是"插线不直达"唯一的现场证据。 */
        const val TAG = "Chooser"

        /**
         * 轮询间隔。选择页是纯静态页、只在可见时轮询，1 秒的开销可以忽略；
         * 再长会让"插上就进"的体感变钝。
         */
        const val POLL_INTERVAL_MS = 1000L
    }
}
