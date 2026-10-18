package com.uav.protocol.media

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
 * ## 线程安全（这里踩过一次崩溃，务必保持）
 *
 * 写发生在**解码输出线程**（每帧一次），读发生在**UI 线程**（每秒一次）。
 *
 * ⚠️ 最初这里刻意不加锁，注释还写着"最坏只是读到几帧偏差" —— **这是错的**。
 * [java.util.ArrayDeque] 并发读不是"读到旧值"，而是可能读到**未初始化的 null 槽位**，
 * 于是 `sorted()` 在 `Long.compareTo(null)` 上直接 NPE，把宿主 App 打崩。
 * 所以现在**所有**对内部状态的访问都在 [lock] 内；排序这种 O(n log n) 的操作
 * 则先复制出来、在锁外做，把持锁时间压到最短。
 */
class LatencyStats(private val capacity: Int = DEFAULT_CAPACITY) {

    private val lock = Any()

    private val samples = ArrayDeque<Long>(capacity)

    /** 累计样本总数（含被判为异常的）。 */
    private var total: Long = 0

    /** 被判为异常、未参与分位数计算的样本数。 */
    private var anomalous: Long = 0

    /** 当前窗口内样本之和；必须随 `removeFirst` 同步扣减，否则 avg 会越算越大。 */
    private var sumNanos: Long = 0

    /** 累计样本数（含异常）。 */
    val count: Long get() = synchronized(lock) { total }

    /** 异常样本数。 */
    val anomalyCount: Long get() = synchronized(lock) { anomalous }

    /** 当前窗口内的样本数。 */
    val windowSize: Int get() = synchronized(lock) { samples.size }

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
    fun add(latencyNanos: Long): Boolean = synchronized(lock) {
        total++
        if (latencyNanos < MIN_PLAUSIBLE_NANOS || latencyNanos > MAX_PLAUSIBLE_NANOS) {
            anomalous++
            return@synchronized false
        }
        if (samples.size >= capacity) {
            sumNanos -= samples.removeFirst()
        }
        samples.addLast(latencyNanos)
        sumNanos += latencyNanos
        true
    }

    /** 平均值（纳秒）。空窗口返回 0。 */
    fun averageNanos(): Long = synchronized(lock) {
        if (samples.isEmpty()) 0L else sumNanos / samples.size
    }

    /**
     * 分位数（纳秒）。
     *
     * @param p 0.0 ~ 1.0，例如 0.95 表示 p95
     */
    fun percentileNanos(p: Double): Long {
        val arr = copySorted()
        if (arr.isEmpty()) return 0L
        return arr[indexOf(arr.size, p)]
    }

    /** 当前窗口内的最大值（纳秒）。 */
    fun maxNanos(): Long = synchronized(lock) { samples.maxOrNull() ?: 0L }

    /** 当前窗口内的最小值（纳秒）。 */
    fun minNanos(): Long = synchronized(lock) { samples.minOrNull() ?: 0L }

    fun reset() = synchronized(lock) {
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
        // 一次加锁取走「样本 + 求和 + 异常计数」，保证三者出自同一时刻 ——
        // 分三次读会出现"样本数变了但 sum 没变"这类内部不一致
        val snap = synchronized(lock) {
            Snapshot(samples.toLongArray(), sumNanos, anomalous)
        }
        if (snap.values.isEmpty()) {
            return "$label n=0 bad=${snap.anomalous}"
        }
        // 排序在锁外做：样本已复制，怎么排都不影响别的线程
        val sorted = snap.values.sortedArray()
        val avg = snap.sumNanos / sorted.size
        return buildString {
            append(label).append(" avg=").append(ms(avg))
            append(" p50=").append(ms(sorted[indexOf(sorted.size, 0.50)]))
            append(" p95=").append(ms(sorted[indexOf(sorted.size, 0.95)]))
            append(" p99=").append(ms(sorted[indexOf(sorted.size, 0.99)]))
            append(" max=").append(ms(sorted[sorted.size - 1]))
            append("ms n=").append(sorted.size)
            append(" bad=").append(snap.anomalous)
        }
    }

    /** 锁内复制并排序（供单值分位数查询使用）。 */
    private fun copySorted(): LongArray =
        synchronized(lock) { samples.toLongArray() }.also { it.sort() }

    /** 与 `snapshot` 里 `at(p)` 同一套下标规则，保证两条路径给出同样的分位数。 */
    private fun indexOf(size: Int, p: Double): Int =
        Math.round((size - 1) * p.coerceIn(0.0, 1.0)).toInt().coerceIn(0, size - 1)

    private fun ms(nanos: Long): String =
        // 用整数运算拼一位小数，避免 String.format 在解码热路径上的区域设置开销
        if (nanos >= 0) {
            "${nanos / 1_000_000}.${(nanos % 1_000_000) / 100_000}"
        } else {
            val v = -nanos
            "-${v / 1_000_000}.${(v % 1_000_000) / 100_000}"
        }

    /** 同一时刻的内部状态副本（一次加锁取齐，避免分次读取自相矛盾）。 */
    private class Snapshot(val values: LongArray, val sumNanos: Long, val anomalous: Long)

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
