package com.uav.host.common

import android.util.Log
import android.view.InputDevice

/**
 * 「要不要开焦点模式」—— 遥控器 / D-pad 的焦点环与开机自动聚焦。
 *
 * ## 只给**没有触摸屏**的设备开
 *
 * 焦点环是为了解决"机顶盒只有遥控器、屏幕上完全看不出移焦"（见 [FocusRing]）。
 * 但在平板/一体机这类**触摸设备**上，它只是噪音：
 * 手指点一下按钮，`focusableInTouchMode` 就会给它焦点，于是画面上多出一圈蓝边；
 * 开机还会自动把焦点抢到某张卡片上。用户点哪儿哪儿"被选中"，很像 bug。
 *
 * ## 判据为什么不用 `PackageManager`
 *
 * `pm list features` 里的 `android.hardware.touchscreen` **不可信**：
 * 机顶盒为了能装只支持触摸的 App，普遍会把这个 feature 声明上（本项目的 RK356x 盒子就是）。
 * 唯一可靠的判据是**输入设备表里有没有触摸源**（等价于 `dumpsys input` 里
 * 各设备的 `Sources` 有没有 `0x1002` 位）。
 *
 * 判定结果缓存：触摸能力在运行期不会变，而 `install()` 会在每次建页面时被调用。
 */
internal object FocusMode {

    private const val TAG = "UAV-Focus"

    /** null = 还没判定过。 */
    @Volatile
    private var touchDevice: Boolean? = null

    /** 要不要开焦点模式：**只有非触摸设备才开**。 */
    fun wanted(): Boolean = !isTouchDevice()

    /** 本机是否有触摸源。 */
    fun isTouchDevice(): Boolean {
        touchDevice?.let { return it }
        val touch = detect()
        touchDevice = touch
        Log.i(
            TAG,
            if (touch) "检测到触摸源 ⇒ 关闭焦点模式（不挂焦点环、不自动聚焦）"
            else "无触摸源（遥控器 / 机顶盒）⇒ 启用焦点模式"
        )
        return touch
    }

    private fun detect(): Boolean = InputDevice.getDeviceIds().any { id ->
        val dev = InputDevice.getDevice(id) ?: return@any false
        (dev.sources and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
    }
}
