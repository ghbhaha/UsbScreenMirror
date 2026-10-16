package com.uav.protocol

/**
 * 端到端延迟的滚动统计（计划 4.10）。
 *
 * 只保留最近 [capacity] 个样本，长跑时内存与排序开销都有界；
 * 分位数在读取时现算（几秒一次、样本量几百，开销可忽略）。
 *
 * **为什么必须看分位数而不是只看平均**：投屏的体感由**尾部**决定 ——
 * "平均 40ms、但 p95 是 150ms"的链路，用户会明显感到偶尔卡一下；
 * 反过来 "平均 80ms、p95 也是 90ms" 反而更顺。所以 4.10 的验收应看 p95/p99。
 *
 * 线程安全：不加锁。调用方保证只在解码输出线程写、在统计线程读；
 * [snapshot] 里 `sorted()` 会先复制，读到并发写入的中间态最多是某几帧偏差，
 * 对统计结论无影响（这是刻意的取舍：加锁会给每帧解码路径引入竞争）。
 */
class LatencyStats(private val capacity: Int = DEFAULT_CAPACITY) {

    private val samples = ArrayDeque<Long>(capacity)

    /** 累计样本总数（含被判为异常的）。 */
    private var total: Long = 0

    /** 被判为异常、未参与分位数计算的样本数。 */
    private var anomalous: Long = 0

    /** 当前窗口内样本之和；必须随 `removeFirst` 同步扣减，否则 avg 会越算越大。 */
    private var sumNanos: Long = 0

    /** 累计样本数（含异常）。 */
    val count: Long get() = total

    /** 异常样本数。 */
    val anomalyCount: Long get() = anomalous

    /** 当前窗口内的样本数。 */
    val windowSize: Int get() = samples.size

    /**
     * 记一个延迟样本。
     *
     * 超出 `[MIN_PLAUSIBLE_NANOS, MAX_PLAUSIBLE_NANOS]` 的记为**异常**，只计数不进入分布：
     *
     *  - **负数**：正常不该出现。小幅负值来自时钟标定误差（±rtt/2），
     *    大幅负值说明 pts 异常或对端时钟跳变。
     *  - **超大值**：首帧、编码器重配置、或设备休眠唤醒会造成秒级离群值。
     *
     * 若把离群值混进分布，一个 8 秒的样本就能把 p99 拉爆成误导性的结论。
     *
     * @return 该样本是否被接受（未接受即计为异常）
     */
    fun add(latencyNanos: Long): Boolean {
        total++
        if (latencyNanos < MIN_PLAUSIBLE_NANOS || latencyNanos > MAX_PLAUSIBLE_NANOS) {
            anomalous++
            return false
        }
        if (samples.size >= capacity) {
            sumNanos -= samples.removeFirst()
        }
        samples.addLast(latencyNanos)
        sumNanos += latencyNanos
        return true
    }

    /** 平均值（纳秒）。空窗口返回 0。 */
    fun averageNanos(): Long = if (samples.isEmpty()) 0L else sumNanos / samples.size

    /**
     * 分位数（纳秒）。
     *
     * @param p 0.0 ~ 1.0，例如 0.95 表示 p95
     */
    fun percentileNanos(p: Double): Long {
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        val idx = Math.round((sorted.size - 1) * p.coerceIn(0.0, 1.0)).toInt()
            .coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /** 当前窗口内的最大值（纳秒）。 */
    fun maxNanos(): Long = samples.maxOrNull() ?: 0L

    /** 当前窗口内的最小值（纳秒）。 */
    fun minNanos(): Long = samples.minOrNull() ?: 0L

    fun reset() {
        samples.clear()
        total = 0
        anomalous = 0
        sumNanos = 0
    }

    /**
     * 一行摘要，供 `STATS` 日志与 UI 使用。
     *
     * @param label 行首标签，便于把多个口径（如全量与"仅新内容帧"）区分开
     *
     * 形如：`delay avg=12.3 p50=11.8 p95=24.1 p99=31.0 max=48.2ms n=300 bad=0`
     */
    fun snapshot(label: String = "delay"): String {
        if (samples.isEmpty()) {
            return "$label n=0 bad=$anomalous"
        }
        // 排一次序，所有分位数共用，避免 snapshot 内部重复排序
        val sorted = samples.sorted()
        fun at(p: Double): Long {
            val idx = Math.round((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }
        val avg = sumNanos / sorted.size
        return buildString {
            append(label).append(" avg=").append(ms(avg))
            append(" p50=").append(ms(at(0.50)))
            append(" p95=").append(ms(at(0.95)))
            append(" p99=").append(ms(at(0.99)))
            append(" max=").append(ms(sorted.last()))
            append("ms n=").append(sorted.size)
            append(" bad=").append(anomalous)
        }
    }

    private fun ms(nanos: Long): String =
        // 用整数运算拼一位小数，避免 String.format 在解码热路径上的区域设置开销
        if (nanos >= 0) {
            "${nanos / 1_000_000}.${(nanos % 1_000_000) / 100_000}"
        } else {
            val v = -nanos
            "-${v / 1_000_000}.${(v % 1_000_000) / 100_000}"
        }

    companion object {
        /** 默认窗口：约 10~30 秒的帧量（30fps 时约 10s，10fps 时约 30s）。 */
        const val DEFAULT_CAPACITY: Int = 300

        /**
         * 可接受的下界。
         *
         * 留一点负值余量，是因为时钟标定的不确定度本身就是 ±rtt/2（毫秒级），
         * 真实延迟极小时可能量到小幅负值 —— 那属于标定误差，不是数据错误。
         */
        const val MIN_PLAUSIBLE_NANOS: Long = -5_000_000L

        /** 可接受的上界：超过 10 秒的样本必然不是稳态延迟（首帧 / 重配置 / 唤醒）。 */
        const val MAX_PLAUSIBLE_NANOS: Long = 10_000_000_000L
    }
}
