package com.uav.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.content.IntentCompat
import com.uav.host.ios.IosActivity
import com.uav.host.usb.BulkPair
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chooser)

        findViewById<View>(R.id.card_aoa).setOnClickListener {
            startActivity(Intent(this, HostActivity::class.java))
        }
        findViewById<View>(R.id.card_ios).setOnClickListener {
            startActivity(Intent(this, IosActivity::class.java))
        }

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
    }

    /**
     * 插线直达：能确定设备类型就跳过选择页。
     *
     * ⚠️ 这里**只读设备描述符**，不发任何控制请求 —— 分类错了顶多是"没自动跳"，
     * 不会把对方设备弄成怪状态。
     */
    private fun handleAttach(intent: Intent?) {
        val device = intent?.let {
            IntentCompat.getParcelableExtra(it, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } ?: return
        when (UsbDeviceClassify.kindOf(device.vendorId, device.productId)) {
            UsbDeviceKind.IOS -> {
                startActivity(Intent(this, IosActivity::class.java))
                finish()
            }
            UsbDeviceKind.AOA_ACCESSORY -> {
                // 已经是配件态：AOA 页会有更快的一条直连路径
                startActivity(Intent(this, HostActivity::class.java))
                finish()
            }
            UsbDeviceKind.IGNORE -> {
                // 普通模式下的手机：PID 是厂商自己的，分类只知道"不是配件、不是 Apple"。
                // 这里退回合并前的老启发式（有 bulk 端点对就当候选），否则"插线即达"
                // 会退化成"每次都要手点一下"——那是明确的行为倒退。
                // 内建 HID 触摸屏 / 键鼠都是中断端点，没有 bulk 对，不会被误挑。
                if (BulkPair.hasBulkPair(device)) {
                    startActivity(Intent(this, HostActivity::class.java))
                    finish()
                }
                // 其余（触摸屏、键鼠、U 盘）停在选择页，不做任何动作
            }
        }
    }

    /** 把"现在插着什么"如实显示一行，帮用户判断该点哪个。 */
    private fun refreshDetected() {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usb.deviceList.values
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
}
