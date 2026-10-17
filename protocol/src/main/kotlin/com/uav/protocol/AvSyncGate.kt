package com.uav.protocol

/**
 * 音频时钟主导的视频节奏（10.20③ · 精细 A/V 同步）。
 *
 * ## 为什么需要它
 * 原先只做了"起播时刻的常量补偿"（在第一段 PCM 前固定睡 N 毫秒，见 `AudioPlayer.applyStartupDelay`）——
 * 那只对齐了**起点**，之后两端各自的时钟漂移没人管（真机实测：两台设备晶体不同，
 * 长时间播放会逐渐跑开，于是"看的和听的"越差越多）。
 *
 * ## 能做什么、不能做什么（先说清边界）
 * 视频是 `releaseOutputBuffer(index, true)` 交给 `SurfaceFlinger` 合成/上屏的，
 * **没法给视频重新定时**（改 pts 需要 `Surface.setFrameRate` 之外的手段）。
 * 所以只能用两种手段纠正：
 * - **丢帧**：某一帧相对音频已经晚了太多，播出去只会是"跳一下"，不如丢掉追上下一个；
 * - **有界等待**：某一帧比音频早太多时，先睡一小会儿再交上去（上限 [maxHoldNanos]，
 *   否则会卡住解码线程）。
 *
 * ## 偏移怎么学
 * 每帧都能观测到 `measured = 音频已播到的时刻 − 该帧被采集的时刻`，
 * 其中混着两件事：**系统性的渲染管线延迟**（解码 + 缓冲 + vsync，恒定）与**真实漂移**（缓慢变化）。
 * 所以不把 `measured` 直接当误差，而是用**限幅的 EMA** 让 [offsetNanos] 慢慢吃掉系统性延迟，
 * 剩下的残差才是我们要纠正的抖动/漂移。
 *
 * 纯逻辑、可单测（时钟由调用方传入 ⇒ 没有隐藏的时间源）。
 */
class AvSyncGate(
    /** 渲染管线自身的延迟容差：在这个范围内不算"晚"。 */
    private val lateToleranceNanos: Long = DEFAULT_LATE_TOLERANCE_NANOS,
    /** 超出容差后还要再晚这么多才丢帧。 */
    private val dropThresholdNanos: Long = DEFAULT_DROP_THRESHOLD_NANOS,
    /** 早这么多就开始等（比容差低，保守：宁可少等也别抖）。 */
    private val holdThresholdNanos: Long = DEFAULT_HOLD_THRESHOLD_NANOS,
    /** 单帧最多等这么久 —— 解码线程被卡住太久会反过来制造掉帧。 */
    private val maxHoldNanos: Long = DEFAULT_MAX_HOLD_NANOS,
    /** EMA 系数：越小跟得越慢（对抖动不敏感），越大纠偏越快。 */
    private val correctionAlpha: Double = 0.08,
    /** 单次校正的最大步进 —— 防止把一次异常帧放大成系统性偏移。 */
    private val maxCorrectionStepNanos: Long = 4_000_000L,
    /** 预热帧数：这段时间只学习偏移，不丢不等（否则开局 offset 还没收敛会误丢）。 */
    private val warmupFrames: Int = 60,
) {

    /** 一帧的处置决定。 */
    class Decision(val render: Boolean, val waitNanos: Long = 0L)

    /** 学到的系统性偏移（视频相对音频的固定延迟，正数=视频天生更晚）。 */
    @Volatile
    var offsetNanos: Long = 0L
        private set

    @Volatile
    var droppedFrames: Long = 0L
        private set

    @Volatile
    var heldFrames: Long = 0L
        private set

    private var seenFrames = 0L

    fun reset() {
        offsetNanos = 0L
        droppedFrames = 0L
        heldFrames = 0L
        seenFrames = 0L
    }

    /**
     * @param frameHostPtsNanos 该帧被采集的时刻（已换算到**宿主单调时钟**）
     * @param audioNowNanos     音频**已经播出**到的时刻（同一时钟域）
     */
    fun decide(frameHostPtsNanos: Long, audioNowNanos: Long): Decision {
        seenFrames++
        val measured = audioNowNanos - frameHostPtsNanos

        // 限幅 EMA：只吃"缓慢变化"的部分
        val delta = measured - offsetNanos
        val step = (delta * correctionAlpha).toLong()
            .coerceIn(-maxCorrectionStepNanos, maxCorrectionStepNanos)
        offsetNanos += step

        // 预热期只学习，不干预
        if (seenFrames <= warmupFrames) return Decision(render = true)

        // ⚠️ 容差**只用来吸收"晚"**（渲染管线天生晚于音频）。若双向都减，
        //    一个"正好同步"的帧会被算成"早 30ms"而触发等待 —— 自己踩过（见单测）。
        val lateError = measured - offsetNanos
        val earlyError = -lateError
        return when {
            lateError >= lateToleranceNanos + dropThresholdNanos -> {
                droppedFrames++
                Decision(render = false)
            }

            earlyError >= holdThresholdNanos -> {
                heldFrames++
                Decision(render = true, waitNanos = earlyError.coerceAtMost(maxHoldNanos))
            }

            else -> Decision(render = true)
        }
    }

    /** 一行摘要，进 STATS。 */
    fun stats(): String =
        "offset=${offsetNanos / 1_000_000}ms drop=$droppedFrames hold=$heldFrames frames=$seenFrames"

    companion object {
        /** 渲染管线容差 30ms（解码 + 缓冲 + 一次 vsync 的量级）。 */
        const val DEFAULT_LATE_TOLERANCE_NANOS = 30_000_000L

        /** 超容差再晚 60ms 才丢 —— 再晚就是"明显跳一下"，追不回来。 */
        const val DEFAULT_DROP_THRESHOLD_NANOS = 60_000_000L

        /** 早 30ms（约一帧）才开始等：更小的抖动放过去，不值得为它卡解码线程。 */
        const val DEFAULT_HOLD_THRESHOLD_NANOS = 30_000_000L

        /** 单帧最多等 40ms。 */
        const val DEFAULT_MAX_HOLD_NANOS = 40_000_000L
    }
}
