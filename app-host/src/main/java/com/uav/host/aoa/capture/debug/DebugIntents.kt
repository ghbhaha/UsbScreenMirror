package com.uav.host.aoa.capture.debug

import android.content.Intent
import com.uav.host.common.DebugTools
import com.uav.host.aoa.capture.session.HostSession
import com.uav.protocol.Logger
import com.uav.protocol.i
import com.uav.protocol.w
import java.io.File

/**
 * **调试入口解析层**（10.24 分层重构，从 [com.uav.host.aoa.HostActivity] 抽出）。
 *
 * ## 职责边界
 *
 * 只做一件事：**把 `adb am start` 传来的 intent extra 翻译成"要干什么"**，
 * 然后交给 [Handler] 执行。它不碰 View、不碰链路。
 *
 * ## ⚠️ 安全边界（计划 5.2）
 *
 * 这层是"不碰屏幕就能驱动联调"的入口，**release 包必须全部失效** ——
 * 否则发布版仍可被 `--es replay_path` 读任意本地文件、被 `--ez force_handshake`
 * 强制打断正常链路。
 *
 * 三道闸：
 * 1. 本类的 [parse] 在 `!DebugTools.ENABLED` 时**只打一条 warn 就返回 null**；
 * 2. 每个动作在 [Handler] 里再查一次（如 `ReplayController` 自己也拦一道）；
 * 3. 回放通道的 UI 入口（按钮）在 release 下整块隐藏。
 *
 * 单靠第 1 条不够 —— 调用方可能绕过本类直接调 [Handler]（如按钮点击）。
 */
class DebugIntents(
    private val logger: Logger,
    private val handler: Handler,
) {

    /** 解析出来的动作。`null` = 没有调试指令，走正常 intent 流程。 */
    sealed interface Action {
        /** 从指定路径回放抓流。 */
        data class Replay(val path: String) : Action

        /** 普通扫描（等价于点 Scan / Retry 按钮）。 */
        data object Scan : Action

        /** 强制走完整 AOA 握手（手机已被别的宿主 App 切成配件模式时用）。 */
        data object ForceHandshake : Action

        /** 强制重新注册 HID 触摸屏（排查"手机上没出现触摸设备"用）。 */
        data object HidProbe : Action

        /** 在手机坐标上发点按；给了第二组坐标就是双指同时按。 */
        data class HidTap(
            val x: Int,
            val y: Int,
            val x2: Int,
            val y2: Int,
            val holdMs: Int,
        ) : Action
    }

    interface Handler {
        fun onReplay(path: String, file: File)
        fun onScan()
        fun onForceHandshake()
        fun onHidProbe()

        /** @return 是否成功发出（尺寸未知时为 false） */
        fun onHidTap(x: Int, y: Int, x2: Int, y2: Int, holdMs: Int): Boolean

        /**
         * 音频开关与 A/V 粗补偿（10.13）。
         *
         * ⚠️ 这两个**不接管 intent**（它们只改参数，不是一个独立动作），
         *   所以在 [parse] 之外单独提供。
         */
        fun onAudioTuning(noAudio: Boolean?, audioDelayMs: Int?)
    }

    /**
     * 消化 intent 里的调试 extra。
     *
     * @return 命中的动作；`null` 表示"没有调试指令"或"被 release 门控拦下"，
     *   调用方应继续按 `action` 正常分发。
     */
    fun parse(intent: Intent?): Action? {
        if (!DebugTools.ENABLED) {
            // release 下若仍传了调试 extra，明确说一句"被门控忽略"。
            // 否则调用方只会看到"extra 毫无反应"，无从判断是漏实现还是被门控（计划 5.2）。
            if (intent != null && hasAnyDebugExtra(intent)) {
                logger.w(TAG, "release 构建：调试入口已关闭，忽略 intent extra")
            }
            return null
        }

        // am start -n com.uav.host/.aoa.HostActivity --es replay_path /data/data/com.uav.host/files/replay.uav
        //   从指定文件回放抓流（计划 4.0），跳过整个 USB 与握手。
        intent?.getStringExtra(EXTRA_REPLAY_PATH)?.takeIf { it.isNotEmpty() }?.let { path ->
            logger.i(TAG, "收到回放指令: $path")
            return Action.Replay(path)
        }

        // am start -n com.uav.host/.aoa.HostActivity --ez scan true
        if (intent?.getBooleanExtra(EXTRA_SCAN, false) == true) {
            logger.i(TAG, "收到扫描指令")
            return Action.Scan
        }

        // am start -n com.uav.host/.aoa.HostActivity --ez force_handshake true
        //   强制走完整 AOA 握手，用于"手机已被别的宿主 App 切成配件模式"的场景。
        if (intent?.getBooleanExtra(EXTRA_FORCE_HANDSHAKE, false) == true) {
            logger.i(TAG, "收到强制重握手指令")
            return Action.ForceHandshake
        }

        // am start -n com.uav.host/.aoa.HostActivity --ez hid_probe true
        //   强制重新注册触摸屏（排查"手机上没出现触摸设备"用）。
        if (intent?.getBooleanExtra(EXTRA_HID_PROBE, false) == true) {
            logger.i(TAG, "收到指令：强制重新注册 HID 触摸屏")
            return Action.HidProbe
        }

        // am start -n com.uav.host/.aoa.HostActivity --ez hid_tap true [--ei tap_x X --ei tap_y Y --ei hold_ms Ms]
        //   在**手机坐标**上发一次点按，不用真去点宿主屏幕就能验证注入链路。
        //   再加 --ei tap2_x X --ei tap2_y Y 就是**双指同时按**（验证多点，见 10.12）。
        if (intent?.getBooleanExtra(EXTRA_HID_TAP, false) == true) {
            return Action.HidTap(
                x = intent.getIntExtra(EXTRA_TAP_X, Int.MIN_VALUE),
                y = intent.getIntExtra(EXTRA_TAP_Y, Int.MIN_VALUE),
                x2 = intent.getIntExtra(EXTRA_TAP2_X, Int.MIN_VALUE),
                y2 = intent.getIntExtra(EXTRA_TAP2_Y, Int.MIN_VALUE),
                holdMs = intent.getIntExtra(EXTRA_HOLD_MS, DEFAULT_TAP_HOLD_MS),
            )
        }

        return null
    }

    /**
     * 音频开关与 A/V 粗补偿（10.13）—— **不接管 intent**，只改参数。
     *
     * ```
     * am start -n com.uav.host/.aoa.HostActivity --ez scan true --ez no_audio true
     *   关掉音频（A/B 对比"到底有没有声音"、或手机上没给录音权限时用）。
     * am start -n com.uav.host/.aoa.HostActivity --ez scan true --ei audio_delay_ms 200
     *   调整音频起播延迟（对齐音画；默认见 HostSession.DEFAULT_AUDIO_DELAY_MS）。
     * ```
     */
    fun applyAudioTuning(intent: Intent?) {
        if (!DebugTools.ENABLED || intent == null) return
        if (intent.hasExtra(EXTRA_NO_AUDIO)) {
            handler.onAudioTuning(noAudio = intent.getBooleanExtra(EXTRA_NO_AUDIO, false), audioDelayMs = null)
        }
        if (intent.hasExtra(EXTRA_AUDIO_DELAY_MS)) {
            handler.onAudioTuning(noAudio = null, audioDelayMs = intent.getIntExtra(EXTRA_AUDIO_DELAY_MS, 0))
        }
    }

    /** 把解析出来的动作执行掉。 */
    fun dispatch(action: Action) {
        when (action) {
            is Action.Replay -> handler.onReplay(action.path, File(action.path))
            Action.Scan -> handler.onScan()
            Action.ForceHandshake -> handler.onForceHandshake()
            Action.HidProbe -> handler.onHidProbe()
            is Action.HidTap -> handler.onHidTap(action.x, action.y, action.x2, action.y2, action.holdMs)
        }
    }

    private fun hasAnyDebugExtra(intent: Intent): Boolean = intent.hasExtra(EXTRA_REPLAY_PATH) ||
        intent.hasExtra(EXTRA_SCAN) ||
        intent.hasExtra(EXTRA_FORCE_HANDSHAKE) ||
        intent.hasExtra(EXTRA_HID_PROBE) ||
        intent.hasExtra(EXTRA_HID_TAP) ||
        intent.hasExtra(EXTRA_HOLD_MS) ||
        intent.hasExtra(EXTRA_TAP2_X) ||
        intent.hasExtra(EXTRA_TAP2_Y) ||
        intent.hasExtra(EXTRA_NO_AUDIO) ||
        intent.hasExtra(EXTRA_AUDIO_DELAY_MS)

    companion object {
        const val TAG = "UAV-Host"

        const val EXTRA_REPLAY_PATH = "replay_path"
        const val EXTRA_FORCE_HANDSHAKE = "force_handshake"
        const val EXTRA_SCAN = "scan"
        const val EXTRA_HID_PROBE = "hid_probe"

        /** `--ez hid_tap true [--ei tap_x N --ei tap_y M --ei hold_ms ms]`。 */
        const val EXTRA_HID_TAP = "hid_tap"
        const val EXTRA_TAP_X = "tap_x"
        const val EXTRA_TAP_Y = "tap_y"

        /** 第二根手指（给了就是双指同时按，用于验证多点）。 */
        const val EXTRA_TAP2_X = "tap2_x"
        const val EXTRA_TAP2_Y = "tap2_y"

        /** 点按的按住时长（毫秒）。留证时拉长，普通点按用默认值。 */
        const val EXTRA_HOLD_MS = "hold_ms"

        /** 排障点按的默认按住时长：够手机侧产生明确的按下/抬起两条事件。 */
        const val DEFAULT_TAP_HOLD_MS = 80

        const val EXTRA_NO_AUDIO = "no_audio"
        const val EXTRA_AUDIO_DELAY_MS = "audio_delay_ms"
    }
}