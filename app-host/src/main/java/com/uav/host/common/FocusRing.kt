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
 */
internal object FocusRing {

    fun install(root: View) {
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

    private fun walk(v: View, visit: (View) -> Unit) {
        visit(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), visit)
        }
    }
}
