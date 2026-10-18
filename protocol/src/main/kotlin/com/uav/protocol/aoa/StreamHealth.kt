package com.uav.protocol.aoa

/**
 * 视频流**健康度**判定（10.30）。
 *
 * ## 它解决什么问题
 *
 * 手机端编码器可能**静默死掉** —— 不是抛异常（那条路已有 `CLSE("编码器错误: …")` 上报），
 * 而是进入一种"活着但不再产帧"的状态：虚拟屏还在、`dequeueOutputBuffer` 一直 `TRY_AGAIN`、
 * 链路也还连着。这时宿主看到的是"没有新帧"。
 *
 * 在引入本类之前，宿主界面**照旧显示「投屏中」**，用户只看到画面冻在最后一帧 ——
 * 完全无法判断是"画面本来就没动"还是"手机端挂了"。这是本项目最难受的一类失败：
 * **界面在说谎**（与 10.23 那个"事件流覆盖状态"是同一类问题，只是这次是"什么都不发生"）。
 *
 * ## 判据只有一个：最后一次视频帧距今多久
 *
 * ⚠️ **不能把"没帧"直接当异常**：
 * - 手机端设了 `KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100ms`，所以**静态画面也有 ~10fps**；
 * - 但"整 GOP 尾部丢弃"（10.20②）在等 IDR 期间会真的停帧，切后台暂停、重协商重建编码器
 *   也会短暂停帧。
 *
 * 因此分三档而不是两档：
 *
 * | 静默时长 | 状态 | 含义 |
 * |---|---|---|
 * | < [stallMs] | [State.HEALTHY] | 正常（含静态画面的 10fps） |
 * | [stallMs], [stopMs]) | [State.STALLED] | 可疑：可能是等 IDR / 重建，先提示不报警 |
 * | ≥ [stopMs] | [State.STOPPED] | 判定手机端不再产帧 |
 *
 * 纯逻辑、零 Android 依赖，有 JVM 单测（`StreamHealthTest`）。
 */
class StreamHealth(
    private val stallMs: Long = DEFAULT_STALL_MS,
    private val stopMs: Long = DEFAULT_STOP_MS,
) {

    init {
        require(stallMs > 0 && stopMs > stallMs) {
            "阈值必须递增且为正：stallMs=$stallMs stopMs=$stopMs"
        }
    }

    enum class State {
        /** 最近 [stallMs] 内有新帧。 */
        HEALTHY,

        /** 已经"可疑地安静"，但还在 [stopMs] 之内 —— 常见于等 IDR / 编码器重建。 */
        STALLED,

        /** 超过 [stopMs] 没有新帧 ⇒ 手机端不再产帧。 */
        STOPPED,
    }

    /**
     * 评估当前健康度。
     *
     * @param nowMs 当前单调时刻（`SystemClock.elapsedRealtime()`，**不能用墙钟**）
     * @param lastFrameAtMs 最后一次收到视频帧的单调时刻；`<= 0` 表示**还没收到过**
     *   （首帧之前）。那种情况一律返回 [State.HEALTHY] —— "等首帧"由连接流程负责，
     *   不该在这里报"无输出"，否则刚 OPEN 完就会误报。
     */
    fun evaluate(nowMs: Long, lastFrameAtMs: Long): State {
        if (lastFrameAtMs <= 0L) return State.HEALTHY
        val silent = nowMs - lastFrameAtMs
        // 时钟回绕/事件乱序的兜底：负数直接当健康，不要报假警。
        if (silent < 0L) return State.HEALTHY
        return when {
            silent >= stopMs -> State.STOPPED
            silent >= stallMs -> State.STALLED
            else -> State.HEALTHY
        }
    }

    /** 距离最后一次帧过了多久（给文案用）；还没收到过帧时返回 0。 */
    fun silentMs(nowMs: Long, lastFrameAtMs: Long): Long =
        if (lastFrameAtMs <= 0L) 0L else (nowMs - lastFrameAtMs).coerceAtLeast(0L)

    companion object {
        /**
         * 3 秒。静态画面是 ~10fps，正常绝不该安静 3 秒；而"等 IDR"通常 1~2 帧内就恢复，
         * 所以 3 秒足够把它和真故障区分开。
         */
        const val DEFAULT_STALL_MS = 3_000L

        /**
         * 10 秒。给足重协商 / 编码器重建 / 授权弹窗的时间，超过这个长度基本可以断定
         * 手机端不会自己恢复了。
         */
        const val DEFAULT_STOP_MS = 10_000L
    }
}
