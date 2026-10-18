package com.uav.protocol.aoa

/**
 * 整 GOP 尾部丢弃（10.20②）。
 *
 * ## 要解决的问题
 * 编码器是一个**连续**的 GOP 序列（IDR + 一串 P/B）。而宿主每次"新会话接入"
 * —— 首次建链、重连、原地重协商、编码器重建 —— 都会新建一个**从零开始**的解码器。
 * 如果这时手机照旧把当前 GOP 的中间帧（P 帧）推过去，新解码器手里**没有它所依赖的那个 IDR**，
 * 于是会花屏，直到下一个 I 帧到来（实测 I 帧周期 **3s** ⇒ 最长花 3 秒）。
 *
 * 丢弃整个尾巴 = 在新会话开始时**先不推帧**，一直等到下一个 IDR。
 * 配合 `MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME`（见编码器侧），通常只需等 ~1 帧（几十毫秒）。
 *
 * ## 为什么必须有超时兜底
 * 万一编码器迟迟不给 IDR（静态画面 + 某些 ROM 的行为），无脑等下去就是**黑屏** ——
 * 那比花屏更糟。所以超过 [timeoutNanos] 就放行：宁可短暂花屏，也不要一直不出画面。
 *
 * 纯逻辑、可单测（时钟可注入）。
 */
class GopTailGate(
    private val timeoutNanos: Long = DEFAULT_TIMEOUT_NANOS,
    private val nanoTime: () -> Long = { System.nanoTime() },
) {

    @Volatile
    private var waiting = false

    @Volatile
    private var armedAtNanos = 0L

    /** 当前是否正在"等 IDR"（诊断用）。 */
    val isWaiting: Boolean get() = waiting

    /** 新会话接入：开始等 IDR。 */
    fun arm() {
        armedAtNanos = nanoTime()
        waiting = true
    }

    /** 不再等（例如已停编）。 */
    fun disarm() {
        waiting = false
    }

    /**
     * 这一帧要不要推给宿主。
     *
     * @param isKeyFrame 该帧是否 IDR（`MediaCodec.BUFFER_FLAG_KEY_FRAME`）
     * @return `true` = 推送；`false` = 丢弃（它是缺 IDR 的尾巴）
     */
    fun shouldEmit(isKeyFrame: Boolean): Boolean {
        if (!waiting) return true
        if (isKeyFrame) {
            waiting = false
            return true
        }
        if (nanoTime() - armedAtNanos >= timeoutNanos) {
            // 兜底：等太久了，放行（黑屏比花屏更糟）
            waiting = false
            return true
        }
        return false
    }

    companion object {
        /** 兜底超时 600ms：正常路径 ~1 帧就拿到 IDR；静态画面等满 3s 太久。 */
        const val DEFAULT_TIMEOUT_NANOS = 600_000_000L
    }
}
