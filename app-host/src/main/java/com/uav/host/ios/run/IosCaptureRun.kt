package com.uav.host.ios.run

import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「一次投屏 / 一次回放」的登记与收尾。
 *
 * 存在的理由只有一条：**运行标志的复位必须无条件发生**。
 * 从前它写在 `Thread` 体末尾，`return@Thread` 就跳过复位，而入口处 `compareAndSet`
 * 已经置位 ⇒ 那条入口再也起不来（rearm 里 4 个 `return` 就是这么踩的）。
 *
 * 第二个理由：三个后台流程（自动投屏 / 重新武装 / 回放）用同一份复位逻辑。
 * 各写一遍迟早各改一遍，界面就会停在"半复位"的样子。
 *
 * ⚠️ 协程在这里只提供**归属**，不提供**中断**：`bulkTransfer` 不可中断，
 * 停止仍然只认各流程自己的停止信号（见 [IosActivity.stopRequested]）。
 */
internal class IosCaptureRun(
    private val scope: LifecycleCoroutineScope,
    private val log: (String) -> Unit,
    private val onReset: () -> Unit,
) {
    /** 有一次流程在跑（[compareAndSet] 防重入：轮询与「重新唤醒」可能撞在一起）。 */
    private val running = AtomicBoolean(false)

    /** 只登记、只用于排查（线程 dump / 单测里看归属）。**Job 不是锁**。 */
    var current: Job? = null
        private set

    fun isRunning(): Boolean = running.get()

    /** 直接置位（`onDestroy` 用它兜底：先假定在跑，让复位路径一定走一遍）。 */
    fun markRunning() = running.set(true)

    /**
     * 抢占一次运行权。抢不到返回 `false` —— 调用方**不要**做任何界面动作。
     */
    fun tryStart(): Boolean = running.compareAndSet(false, true)

    /**
     * 起一个后台流程：占用 [running]、跑 [block]、**无论怎么结束都复位**。
     *
     * @param name 进 logcat 的线程名（`ios-auto` / `ios-rearm` / `replay`）
     */
    fun launch(name: String, block: suspend () -> Unit): Job {
        val job = scope.launch(Dispatchers.IO + CoroutineName(name)) {
            try {
                block()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                log("$name 异常：${t.javaClass.simpleName}: ${t.message}")
            } finally {
                // 收尾也要能跑在取消路径上：面板复位是"最后一句话"
                withContext(NonCancellable) {
                    running.set(false)
                    onReset()
                }
            }
        }
        current = job
        return job
    }
}