package com.uav.host.ios.flow

import com.uav.host.ios.capture.Pairing
import com.uav.protocol.ios.QtStats
import java.io.File

/**
 * 全自动流程：**配对（如需要）→ 投屏**。
 *
 * 它只做"顺序"这一件事，两个动作各自的实现都在 [IosPairingFlow] / [IosCaptureFlow] 里。
 * 之所以单独成类而不是留在 `IosActivity`：这条流程有**自己的一句失败话术**
 * （"配对未完成，本次不投屏"）和**自己的界面转折**（配对提示 → 投屏），
 * 而 Activity 只该关心"生命周期 + 把事实写到面板上"。
 *
 * ## 为什么配对要先查记录
 *
 * 记录按设备分开存（见 [Pairing]），换过一台 iPad 只影响新的那台。
 * 已经有记录就直接投屏 —— 每次插上都重新配对会让用户每插一次都要点一次「信任」。
 *
 * @return 收流统计；**配对没做成就返回 `null`**（调用方据此判失败态）
 */
internal class IosAutoFlow(
    private val filesDir: File,
    private val log: (String) -> Unit,
    private val pairingFlow: IosPairingFlow,
    private val captureFlow: IosCaptureFlow,
    private val deviceKey: () -> String?,
    /** 流程走到了哪一步（界面转折由调用方翻译成面板状态）。 */
    private val onStage: (Stage) -> Unit,
) {

    enum class Stage {
        /** 准备开始配对，该显示"请在 iPad 上点信任"了 */
        PAIRING,

        /** 配对没成功：本次不投屏 */
        PAIRING_SKIPPED,
    }

    suspend fun run(): QtStats? {
        // ① 这台设备还没有配对记录 ⇒ 先配对（iPad 上点一次「信任」）
        //    ★ 记录按设备分开：换过一台 iPad 只影响新的那台，旧的记录原样保留
        val pairKey = deviceKey() ?: Pairing.KEY_UNKNOWN
        log("本次设备键=$pairKey（配对记录按它区分设备）")
        if (!Pairing.hasRecord(filesDir, pairKey)) {
            onStage(Stage.PAIRING)
            if (!pairingFlow.pairNow()) {
                onStage(Stage.PAIRING_SKIPPED)
                return null
            }
        }
        // ② 投屏：激活 / 切配置 / 建 lockdown / claim QT / 收流，全在 capture 里
        return captureFlow.capture()
    }
}
