package com.uav.host.ios.capture

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlin.coroutines.resume

/**
 * USB 权限：请求 / 等广播。
 *
 * 单独成类是因为它有一套自己的状态 —— 待续体 + 广播接收器 —— 而这两样只在
 * "要不要授权"这条路上用。留在 Activity 里会和 USB 业务逻辑混成一片。
 *
 * ## 为什么是挂起函数
 *
 * 等广播本来就是"等到有回音为止"，天然该挂起。从前用 `CountDownLatch.await(25s)`，
 * 再在外面包一层 `runBlocking` —— 那会白占一条 IO 线程 25 秒。
 *
 * ## 续体的三条纪律（每一条都对应一个踩过/差点踩的崩溃）
 *
 * 1. **先摘再 resume**：广播可能重复投递（同一个 `requestPermission` 的 PendingIntent
 *    requestCode 固定为 0，ROM 重发 / 用户重复点都会再来一次），对已完成的
 *    `CancellableContinuation` 再 `resume` 会抛 `IllegalStateException: Already resumed`，
 *    而这里跑在**主线程的广播接收器**上 ⇒ 直接崩。所以 `cont` 必须原子地取走，
 *    并用 `tryResume/completeResume` 兜住竞态。
 * 2. **拒绝也叫醒**：`granted == false` 同样是结论。以前只等 `granted == true`，
 *    用户点了"拒绝"要白等满 [timeoutMs] 才有反馈。
 * 3. **异常/取消路径也要清 `cont`**：否则会残留一个已死的续体，下一次广播去 resume 它。
 *    清理按**身份**判断（`if (cont === mine)`），避免清掉别人的续体。
 *
 * 最终结果**一律以 `usb.hasPermission` 为准**：广播只负责提前结束等待。
 */
internal class IosUsbPermission(private val usb: UsbManager) {

    @Volatile
    private var cont: CancellableContinuation<Unit>? = null

    /** 收到权限广播时调用（主线程，续体会在它自己的调度器上恢复）。 */
    fun onBroadcastReceived(granted: Boolean) {
        // granted 不区分：拒绝同样是结论，真实结果 await 会自己去查 hasPermission
        val c = cont ?: return
        cont = null
        // ★ 不用 tryResume/completeResume：它们标了 @InternalCoroutinesApi，编译期就是 error。
        //   `isActive` 是公开状态位，配合"先摘再 resume"已经把所有并发路径堵住了。
        if (!c.isActive) return
        c.resume(Unit)
    }

    /**
     * 确保拿到权限；没有就先 [request] 再等广播（最多 [timeoutMs]）。
     *
     * 同一时刻**只认一个等待者**：并发第二次调用会覆盖前一个续体（它只能等到超时），
     * 实际上不会发生 —— 三条入口（自动投屏 / 重新武装 / 配对）共用同一个运行标志。
     */
    suspend fun await(dev: UsbDevice, timeoutMs: Long, request: () -> Unit): Boolean {
        if (usb.hasPermission(dev)) return true
        var mine: CancellableContinuation<Unit>? = null
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { c ->
                    mine = c
                    cont = c
                    // 取消 / 超时后迟到的广播不能再 resume 这个已死的续体
                    c.invokeOnCancellation { if (cont === c) cont = null }
                    request()
                }
            }
        } finally {
            if (cont === mine) cont = null
        }
        return usb.hasPermission(dev)
    }
}
