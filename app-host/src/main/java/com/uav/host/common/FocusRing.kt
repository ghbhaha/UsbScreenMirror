package com.uav.host.common

import android.view.View
import android.view.ViewGroup
import com.uav.host.R

/**
 * 遥控器 / D-pad 的**焦点可视化**。
 *
 * ## 为什么必须自己画
 *
 * 1. `android:defaultFocusHighlightEnabled`（系统自带的焦点高亮）**API 26 才有**。
 *    机顶盒普遍是 Android 7 / 9，焦点移过去屏幕**没有任何变化** —— 用户以为遥控器坏了。
 * 2. 更不能靠 `<selector android:state_focused="true">`：实测这台 Android 7.1 上
 *    `view.hasFocus() == true`，但 `state_focused` **不进 drawable state**
 *    （触摸模式下由 `focusableInTouchMode` 拿到的焦点不参与视觉状态）。
 *    ⇒ 只能自己监听 `onFocusChange` 手动挂/摘前景。
 *
 * 用法：面板 `bind()` 末尾调 [install]（传根视图），内部递归给所有可聚焦控件挂钩。
 * 只对 `isFocusable` 的控件生效；不可聚焦的文本/容器不受影响。
 *
 * ⚠️ **触摸设备直接跳过**（见 [FocusMode]）：平板上一按按钮就多出一圈蓝边，
 * 是噪音而不是提示。
 */
internal object FocusRing {

    /**
     * 按本机能力二选一：
     * - **遥控器/机顶盒** → [installRings]：挂焦点环；
     * - **触摸设备** → [disarmTouchFocus]：把为遥控器加的 `focusableInTouchMode` 撤掉。
     *
     * 为什么触摸设备必须"反过来做"而不是"什么都不做"：布局里那批
     * `focusableInTouchMode="true"` 一旦留着，在"窗口里还没有任何焦点"的时候
     * **第一次触摸只用来拿焦点**，第二次才触发点击 —— 表现就是"要点两下"
     * （2026-10-20 实机反馈）。以前焦点模式开着、启动就 requestFocus 了，这个坑被掩盖着。
     */
    fun install(root: View) {
        if (FocusMode.wanted()) installRings(root) else disarmTouchFocus(root)
    }

    private fun installRings(root: View) {
        val ring = runCatching { root.context.getDrawable(R.drawable.focus_ring) }.getOrNull()
            ?: return
        walk(root) { v ->
            if (!v.isFocusable) return@walk
            // 注意：这里**不能**读 `v.isFocused` 来决定初始值 —— 安装时机可能晚于首次聚焦，
            // 而 onFocusChange 只在"变化"时回调。装的时候先按当前状态刷一次。
            v.foreground = if (v.isFocused) ring else null
            v.setOnFocusChangeListener { view, hasFocus ->
                view.foreground = if (hasFocus) ring else null
            }
        }
    }

    /**
     * 撤掉触摸焦点：只关 `focusableInTouchMode`，**不动 `clickable`/`focusable`**。
     *
     * 这样点击照常一次生效，而"点哪儿哪儿被选中"也不会再发生；
     * 真接了鼠标键盘的设备仍可移焦（只是没有环，因为本来就不该给它画）。
     */
    private fun disarmTouchFocus(root: View) {
        var n = 0
        walk(root) { v ->
            if (v.isFocusableInTouchMode) {
                v.isFocusableInTouchMode = false
                n++
            }
        }
        android.util.Log.i("UAV-Focus", "触摸设备：已撤掉 $n 个控件的 focusableInTouchMode")
    }

    private fun walk(v: View, visit: (View) -> Unit) {
        visit(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), visit)
        }
    }
}
