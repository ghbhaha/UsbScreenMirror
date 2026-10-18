package com.uav.host.aoa.input

import android.view.MotionEvent
import com.uav.host.common.DebugTools
import com.uav.host.aoa.input.HidTouchController
import com.uav.host.aoa.video.AspectRatioSurfaceView
import com.uav.protocol.Logger
import com.uav.protocol.i
import com.uav.protocol.input.TouchMapper
import com.uav.protocol.input.TouchPoint
import com.uav.protocol.w

/**
 * **触摸转发层**（10.24 分层重构，从 [com.uav.host.aoa.HostActivity] 抽出）。
 *
 * ## 职责边界
 *
 * 把「宿主屏幕上的 `MotionEvent`」翻译成「手机上的触摸事件」并交给 HID 注入器。
 * **不认识链路状态、不碰菜单、不做重协商。**
 *
 * ## 这里的规则（都是踩出来的）
 *
 * - 只有落在**视频显示区**内的触点才转发（信箱边忽略，见 [TouchMapper]）；
 * - 按 `pointerId` 逐个跟踪，抬起要显式发（否则手机上那根手指会"一直按住"）；
 * - 某个触点被拖出显示区 → 视同抬起，避免手机上的手指"卡"在边缘；
 * - 槽位分配（pointerId → 槽位号）交给 HID 控制器内部的 `MultiTouchTracker`，
 *   它满了会**拒绝**新手指而不是挤掉已有的。
 *
 * ⚠️ 坐标换算本身在 `:protocol/TouchMapper`（纯逻辑、有 JVM 单测），本类只做接线。
 */
class TouchForwarder(
    private val surfaceView: AspectRatioSurfaceView,
    private val logger: Logger,
) {

    /** 手机屏幕尺寸（来自 `CNXN`）；`0` 表示还没收到，映射无法进行。 */
    @Volatile
    var peerWidth = 0

    @Volatile
    var peerHeight = 0

    /** HID 注入器；链路未就绪时为 null，此时所有事件被静默丢弃。 */
    @Volatile
    var injector: HidTouchController? = null

    /** 链路断开时作废旧注入（触点状态**保留**，重连后补发"还按着的那些手指"）。 */
    fun onLinkLost() {
        injector?.onLinkLost()
    }

    /**
     * 视频尺寸就绪 → 更新坐标空间。
     *
     * ★ 手机屏幕的物理尺寸只在 `CNXN` 报一次，旋转后不会重报，
     *   只能靠视频方向推出它当前的长宽（这正是 [TouchMapper.logicalPhoneSize] 的作用）。
     */
    fun onGeometry(peerW: Int, peerH: Int, videoW: Int, videoH: Int) {
        peerWidth = peerW
        peerHeight = peerH
    }

    /**
     * 处理视频显示区上的触摸，返回是否消费了该事件。
     *
     * 返回 false 表示"条件不满足，交给别人处理"（尺寸未知等）。
     */
    fun onTouch(event: MotionEvent): Boolean {
        val viewW = surfaceView.width
        val viewH = surfaceView.height
        val screenW = peerWidth
        val screenH = peerHeight
        if (viewW <= 0 || viewH <= 0 || screenW <= 0 || screenH <= 0) return false

        /** 把第 [pointerIndex] 个触点映射成手机坐标；不在显示区内返回 null。 */
        fun mapPointer(pointerIndex: Int) = TouchMapper.mapTouch(
            viewX = event.getX(pointerIndex).toInt(),
            viewY = event.getY(pointerIndex).toInt(),
            viewW = viewW,
            viewH = viewH,
            videoW = surfaceView.videoWidth,
            videoH = surfaceView.videoHeight,
            screenW = screenW,
            screenH = screenH,
        )

        /** 只在 debug 包打，用于判断"点了没反应"是落在信箱边还是映射错了。 */
        fun logDown(pointerIndex: Int, phone: TouchPoint?) {
            if (!DebugTools.ENABLED) return
            val inView = "(${event.getX(pointerIndex).toInt()},${event.getY(pointerIndex).toInt()})"
            val geom = "/ view=${viewW}x$viewH video=" +
                "${surfaceView.videoWidth}x${surfaceView.videoHeight} 手机屏=${screenW}x$screenH"
            // 带上 pointer 信息：多点时要能一眼看出"事件流里到底有几个 pointer、id 各是多少"
            val who = "pointer=${event.getPointerId(pointerIndex)} (共 ${event.pointerCount})"
            logger.i(
                TAG,
                if (phone == null) {
                    "触摸忽略：视图内 $inView $who 不在显示区内（$geom）"
                } else {
                    "触摸转发：视图内 $inView $who $geom → 手机 (${phone.x},${phone.y})"
                },
            )
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val phone = mapPointer(idx)
                logDown(idx, phone)
                if (phone != null) {
                    injector?.down(event.getPointerId(idx), phone.x, phone.y)
                }
                true
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val phone = mapPointer(i)
                    if (phone == null) {
                        // 拖到信箱边/显示区外：等价于抬指，否则手机上会一直按着
                        injector?.up(pid)
                    } else {
                        injector?.move(pid, phone.x, phone.y)
                    }
                }
                true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                injector?.up(event.getPointerId(event.actionIndex))
                true
            }

            MotionEvent.ACTION_UP -> {
                // 抬起前先按最终坐标更新一次，避免"最后一跳"
                for (i in 0 until event.pointerCount) {
                    mapPointer(i)?.let { injector?.move(event.getPointerId(i), it.x, it.y) }
                }
                for (i in 0 until event.pointerCount) {
                    injector?.up(event.getPointerId(i))
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                injector?.cancelAll()
                true
            }

            else -> false
        }
    }

    /**
     * 在**手机坐标**上发点按（排障用）。给了第二组坐标就是**双指同时按**。
     *
     * 不传坐标时落在一个**安全位置**（屏幕上方 6% 处的正中，通常是状态栏下方的空白区），
     * 避免误触手机上的按钮（手机上就有"停止投屏"这种一按就断链的按钮）。
     *
     * @return 是否成功发出（尺寸未知时返回 false）
     */
    fun debugTap(xArg: Int, yArg: Int, x2Arg: Int, y2Arg: Int, holdMs: Int): Boolean {
        val w = peerWidth
        val h = peerHeight
        if (w <= 0 || h <= 0) {
            logger.w(TAG, "HID 点按：还没收到手机的 CNXN（拿不到屏幕尺寸）")
            return false
        }
        val size = TouchMapper.logicalPhoneSize(
            w, h, surfaceView.videoWidth, surfaceView.videoHeight,
        ) ?: return false

        val x = if (xArg == Int.MIN_VALUE) size.width / 2 else xArg
        val y = if (yArg == Int.MIN_VALUE) (size.height * 0.06f).toInt() else yArg

        // 第二根手指：默认在屏幕上方居右（与第一根拉开的两个独立坐标）
        val twoFingers = x2Arg != Int.MIN_VALUE
        val x2 = if (!twoFingers) 0 else if (x2Arg == Int.MIN_VALUE) (size.width * 0.75f).toInt() else x2Arg
        val y2 = if (y2Arg == Int.MIN_VALUE) (size.height * 0.12f).toInt() else y2Arg

        logger.i(
            TAG,
            if (twoFingers) {
                "HID 双指点按指令：手机坐标 ($x,$y) + ($x2,$y2) 按住 ${holdMs}ms" +
                    "（逻辑空间 ${size.width}x${size.height}）"
            } else {
                "HID 点按指令：手机坐标 ($x,$y) 按住 ${holdMs}ms（逻辑空间 ${size.width}x${size.height}）"
            },
        )
        val ctl = injector ?: return false
        ctl.debugTap(x, y, holdMs, x2.takeIf { twoFingers }, y2.takeIf { twoFingers })
        return true
    }

    companion object {
        const val TAG = "UAV-Host"
    }
}