package com.uav.host.ios.flow

import com.uav.host.ios.capture.Pairing
import com.uav.protocol.ios.PairRecord
import com.uav.protocol.ios.QtStats
import kotlinx.coroutines.delay
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
 * ## 「每次插上都要点信任」是怎么来的（2026-10-21 修）
 *
 * 设备重枚举后，`UsbDevice.serialNumber` 要 1.5~2 秒才读得到；在那之前设备键是
 * [Pairing.KEY_UNKNOWN]。原来这里用 `Pairing.hasRecord(...)` 判断 —— 它**没有任何兜底**，
 * 查 `unknown` 永远查不到 ⇒ 判成新设备 ⇒ 每次都强制配对。
 *
 * 修法两条一起上：
 *  1. [resolveDeviceKey] 重试 3 秒，覆盖 USB 枚举 + 授权那段时间；
 *  2. 判据换成 `loadRecord`（它内部有兜底），并在**多台设备都有记录**时
 *     先拿最近用过的那条试一次 —— 用错只会让本次 TLS 失败（点一次「开始投屏」即可重试），
 *     比"每次都必须点信任"代价小得多。
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
        // ① 这台设备有没有可用的配对记录
        //    ★ 记录按设备分开：换过一台 iPad 只影响新的那台，旧的记录原样保留
        val pairKey = resolveDeviceKey()
        log("本次设备键=${pairKey ?: "未就绪"}（配对记录按它区分设备）")
        if (resolveRecord(pairKey) == null) {
            onStage(Stage.PAIRING)
            if (!pairingFlow.pairNow()) {
                onStage(Stage.PAIRING_SKIPPED)
                return null
            }
        }
        // ② 投屏：激活 / 切配置 / 建 lockdown / claim QT / 收流，全在 capture 里
        return captureFlow.capture()
    }

    /**
     * 读设备键，带重试。
     *
     * `deviceKey()` 返回 `null` 表示"没找到 Apple 设备"，返回 [Pairing.KEY_UNKNOWN]
     * 表示"设备在、但序列号还没就绪"—— 两种都算没拿到，都要重试。
     * 实测设备重枚举后约 1.5~2 秒才读得到，这里给 3 秒余量。
     */
    private suspend fun resolveDeviceKey(): String? {
        repeat(KEY_RETRY_TIMES) { i ->
            val key = deviceKey()
            if (key != null && key != Pairing.KEY_UNKNOWN) return key
            if (i < KEY_RETRY_TIMES - 1) delay(KEY_RETRY_DELAY_MS)
        }
        return null
    }

    /**
     * 找这台设备的配对记录。
     *
     * @param pairKey 已经读到序列号时用它精确匹配；`null` 表示序列号没就绪。
     */
    private fun resolveRecord(pairKey: String?): PairRecord? {
        if (pairKey != null) return Pairing.loadRecord(filesDir, pairKey, log)

        // 序列号没就绪：先让 PairStore 走"本地只有一条记录就直接用"的兜底
        Pairing.loadRecord(filesDir, Pairing.KEY_UNKNOWN, log)?.let { return it }

        // 多台设备都存过记录（兜底不成立）⇒ 拿**最近用过**的那条先试。
        // 用错了代价只是本次 GetValue 失败、不出画面；点一次「开始投屏」时序列号已经就绪，
        // 立刻就会按真 key 匹配上。这比"每次都得在 iPad 上点信任"便宜太多了。
        val keys = Pairing.knownDeviceKeys(filesDir)
        if (keys.isEmpty()) return null
        val newest = keys.maxByOrNull { Pairing.recordFile(filesDir, it).lastModified() } ?: return null
        log(
            "序列号未就绪、本地有多台设备的记录 ⇒ 先按最近使用的 $newest 试一次" +
                "（这一次若没出画面，点「开始投屏」重试即可，不需要点信任）"
        )
        return Pairing.loadRecord(filesDir, newest, log)
    }

    private companion object {
        /** 读设备键的重试次数与间隔：3 秒，覆盖 USB 枚举 + 授权那段时间。 */
        const val KEY_RETRY_TIMES = 12
        const val KEY_RETRY_DELAY_MS = 250L
    }
}
