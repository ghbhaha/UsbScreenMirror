package com.uav.protocol.aoa

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 链路重试的**运行权 + 退避**状态机（纯逻辑，可单测）。
 *
 * ## 为什么把它抽出来
 *
 * 这三件事原本散在 `LinkCoordinator` 里、各用一个原子量：
 * `restartQueued`（连点合并）、`consecutiveFailures`（退避计数）、
 * `nextPollDelayMs()` 里的移位算术。它们**互相耦合**（成功要清零、失败要计数、
 * 计数决定轮询间隔），分散写的风险是"某条路径忘了复位"：
 *
 * - `restartQueued` 忘了 `finally` 复位 ⇒ 之后再点扫描/插线**全部被当成重复请求丢掉**，
 *   界面看起来就是"插上没反应"；
 * - 失败计数忘了清零 ⇒ 偶发几次断线就把退避顶到 30s，自动重连慢到像是失效。
 *
 * iOS 侧同一个坑的代价更直观：`rearm` 里有 4 个 `return` 绕过了复位，
 * 那条入口再也起不来。所以这里把"谁有权跑一次"和"失败怎么退避"合成一个对象，
 * 边界用单测钉住。
 *
 * ## 用法（顺序有依赖）
 *
 * ```kotlin
 * if (!retry.tryBeginAttempt()) return          // 连点合并
 * try {
 *     ... 关旧会话 → 重新握手 ...
 * } finally {
 *     retry.endAttempt()                        // ★ 必须无条件
 * }
 * ```
 *
 * ⚠️ `tryBeginAttempt` / `endAttempt` 是**运行权**，与"这次尝试成功还是失败"无关 ——
 * 成功失败分别走 [onAttemptSucceeded] / [onAttemptFailed]。
 */
class LinkRetry(
    /**
     * 最快档的轮询间隔（毫秒）。
     *
     * 2s 是实测折中：本机插入不派发 ATTACHED（只能轮询），2s 让"插上线到出画面"的
     * 感知是连续的；再密只是白耗电。
     */
    private val baseDelayMs: Long = 2_000L,
    /** 退避最多翻几番（`base shl shift`）。到顶后按 [capDelayMs] 封顶。 */
    private val maxShift: Int = 4,
    /** 间隔上限：再退也不会超过它。 */
    private val capDelayMs: Long = 30_000L,
) {

    private val attempting = AtomicBoolean(false)

    /**
     * 连续失败次数。
     *
     * 到达 [maxShift] 后**不再增长** —— 再涨对间隔没有意义（后面会封顶），
     * 却会让"清零"看起来像丢信息。建链成功即归零。
     */
    @Volatile
    var consecutiveFailures: Int = 0
        private set

    /** 有没有一次"链路重启 / 握手"正在执行。 */
    val isAttempting: Boolean get() = attempting.get()

    /** 抢占一次运行权。抢不到返回 `false` —— 调用方**什么都不要做**（连日志都不必重复打）。 */
    fun tryBeginAttempt(): Boolean = attempting.compareAndSet(false, true)

    /** 交还运行权。**必须放在 `finally` 里**，否则入口会永久锁死。 */
    fun endAttempt() {
        attempting.set(false)
    }

    /** 建链成功：退避归零，轮询回到最快档。 */
    fun onAttemptSucceeded() {
        consecutiveFailures = 0
    }

    /** 一次失败（握手超时 / 会话报错 / 对端 CLSE）。 */
    fun onAttemptFailed() {
        if (consecutiveFailures < maxShift) consecutiveFailures++
    }

    /**
     * 当前应该用的轮询间隔：`base shl clamp(failures)`，再按 [capDelayMs] 封顶。
     *
     * 即 2s → 4s → 8s → 16s → 30s（`2s shl 4 = 32s` 被封顶）。
     */
    fun pollDelayMs(): Long =
        (baseDelayMs shl consecutiveFailures.coerceIn(0, maxShift)).coerceAtMost(capDelayMs)
}
