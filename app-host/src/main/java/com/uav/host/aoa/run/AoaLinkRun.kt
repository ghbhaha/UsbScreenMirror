package com.uav.host.aoa.run

import com.uav.protocol.Logger
import com.uav.protocol.aoa.LinkRetry
import com.uav.protocol.i
import com.uav.protocol.w
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 「链路动作」的**串行执行器 + 运行权**。
 *
 * 原来这两件事散在 `LinkCoordinator` 里：一个 `ioExecutor` 字段，
 * 外加两处一模一样、但各写了一遍的"队列关了怎么办"。
 *
 * ## 为什么必须串行
 *
 * 关会话与重新握手都碰 USB：旧连接没 `close()` 就去 claim 新接口会**抢端点**
 * （同一组 bulk IN/OUT 被两个 fd 同时持有）。单线程 FIFO 天然保证顺序，
 * 而且整段都不在主线程。
 *
 * ## 为什么必须有"另起线程"的兜底
 *
 * `ioExecutor.shutdown()` 之后（Activity 正在销毁）再 `execute` 会抛
 * [RejectedExecutionException]。这时**绝不能退回调用线程**：
 * 调用方可能是主线程，而 `close()` / 握手都是阻塞的（`MediaCodec.release()` +
 * `Connection.close()` 还要拿读线程正在持的锁）—— 退回主线程就是 ANR（10.33 踩过）。
 *
 * ## 运行权从哪来
 *
 * [LinkRetry.tryBeginAttempt]：连点合并靠它。本类只负责"抢不到就静默返回"，
 * 失不失败由 [LinkRetry] 记账 —— 两件事分开，成功/失败路径才不会互相污染。
 */
internal class AoaLinkRun(
    private val retry: LinkRetry,
    private val logger: Logger,
    private val tag: String,
) {

    /**
     * 单线程 FIFO。
     *
     * ⚠️ `close()` 里**不 shutdownNow**：正在跑的关闭任务被中断的话，fd 可能真的漏掉
     * （`/dev/usb_accessory` 独占，漏一个就永久连不回来）。
     */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "uav-link-io").apply { isDaemon = true }
    }

    /**
     * 抢占一次运行权，并把 [block] 丢进串行队列。
     *
     * @return `false` = 已有一次在排队/执行中，本次被合并掉（调用方**什么都不要做**）
     */
    fun restart(block: () -> Unit): Boolean {
        if (!retry.tryBeginAttempt()) {
            logger.i(tag, "已有一次链路重启在排队/执行中，忽略这次重复请求")
            return false
        }
        enqueue(
            Runnable {
                try {
                    block()
                } finally {
                    // ★ 无条件交还：漏一次，之后所有扫描/插线都会被当成重复请求丢掉
                    retry.endAttempt()
                }
            },
            "uav-link-io-fallback",
        )
        return true
    }

    /** 只丢一个关闭动作进同一条串行队列（不占运行权）。 */
    fun closeLater(block: () -> Unit) {
        enqueue(
            Runnable {
                runCatching { block() }
                    .onFailure { logger.w(tag, "关闭旧会话失败: ${it.message}") }
            },
            "uav-link-close",
        )
    }

    /** 关闭队列（不中断正在跑的任务，见类注释）。 */
    fun shutdown() {
        executor.shutdown()
    }

    private fun enqueue(job: Runnable, fallbackThreadName: String) {
        try {
            executor.execute(job)
        } catch (re: RejectedExecutionException) {
            // 队列已 shutdown（Activity 正在销毁）⇒ 另起线程，**绝不退回调用线程**
            Thread(job, fallbackThreadName).apply { isDaemon = true }.start()
        }
    }
}
