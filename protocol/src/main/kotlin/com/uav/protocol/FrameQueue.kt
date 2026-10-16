package com.uav.protocol

import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 每流一条**有界队列** + 丢帧策略（计划 1.5 / 4.7）。
 *
 * **为什么必须有上界**：没有上界时，任何一次瞬时卡顿都会把延迟**永久**推高
 * （队列越积越多），"5 分钟压测延迟不增长"就无法达标。
 * 有界队列把延迟钉死在 `深度 × 帧间隔`（4 帧 ≈ 130ms@30fps）。
 *
 * **丢帧策略（两端对称）**：队满时丢弃**队内最旧的、非关键帧**的那一帧；
 * `CONFIG` / `KEY_FRAME` 优先保留（丢关键帧会破坏解码链）。
 * 若队内全是关键帧（极端情况），退化为丢弃队首并单独计数。
 *
 * ⚠️ **代价联动**：丢弃 P 帧会让后续引用它的帧短暂花屏，**花屏最长时间 = I 帧间隔**
 * —— 这就是计划 4.5 把 I 帧间隔从 10s 收紧到 3s 的原因。两项是一组决定，不能只改一个。
 *
 * 同步用 `ReentrantLock` + `Condition`（Kotlin 的 `Any` 不暴露 `wait`/`notify`，
 * 且 `Condition` 能直接支持带纳秒精度的超时等待）。
 */
class FrameQueue(
    val streamId: Int,
    val capacity: Int = Protocol.QUEUE_DEPTH,
    private val logger: Logger = Logger.NONE,
    private val tag: String = Protocol.TAG,
    private val onDrop: ((Frame) -> Unit)? = null,
) : Closeable, StreamSink {

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val queue = ArrayDeque<Frame>()

    @Volatile
    private var closed = false

    /** 该流的流头（首条 `WRTE` 的 payload 解析而来）。 */
    @Volatile
    var header: StreamHeader? = null
        private set

    var offered: Long = 0; private set

    var taken: Long = 0; private set

    /** 因队满被丢弃的**非关键帧**数（正常背压）。 */
    var droppedNonKey: Long = 0; private set

    /** 因队满且全是关键帧而被迫丢弃的帧数（异常，应告警）。 */
    var droppedKey: Long = 0; private set

    val droppedTotal: Long get() = droppedNonKey + droppedKey

    val size: Int get() = lock.withLock { queue.size }

    val isEmpty: Boolean get() = lock.withLock { queue.isEmpty() }

    val isClosed: Boolean get() = closed

    override fun onHeader(header: StreamHeader) {
        this.header = header
        logger.i(tag, "stream[${StreamId.name(streamId)}] header: $header")
    }

    /** 投递一帧（读线程调用，**必须非阻塞** —— 读线程绝不能被消费端拖住，计划 4.8）。 */
    override fun onFrame(frame: Frame) {
        offer(frame)
    }

    /**
     * @return true 已入队；false 队列已关闭，帧被直接丢弃
     */
    fun offer(frame: Frame): Boolean = lock.withLock {
        if (closed) return false
        offered++

        if (queue.size < capacity) {
            queue.addLast(frame)
            notEmpty.signalAll()
            return true
        }

        // 队满：找最旧的、非关键帧的那一帧丢
        var dropIndex = -1
        for (i in queue.indices) {
            val f = queue[i]
            if (!f.isConfig && !f.isKeyFrame) {
                dropIndex = i
                break
            }
        }
        if (dropIndex >= 0) {
            droppedNonKey++
            reportDrop(queue.removeAt(dropIndex))
        } else {
            // 全是关键帧：丢队首并告警
            droppedKey++
            reportDrop(queue.removeFirst())
            logger.w(
                tag,
                "stream[${StreamId.name(streamId)}] queue full of key/config frames, " +
                    "dropped oldest (droppedKey=$droppedKey) — 链路已严重拥塞"
            )
        }

        queue.addLast(frame)
        notEmpty.signalAll()
        return true
    }

    /** 阻塞取一帧；队列已关闭且为空时返回 null（消费线程据此退出）。 */
    fun take(): Frame? = try {
        take(0L)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    /**
     * 带超时取一帧。
     *
     * @param timeoutMs `<= 0` 表示无限等待
     * @return null 表示「队列已关闭且为空」或「超时」
     */
    fun take(timeoutMs: Long): Frame? = lock.withLock {
        var remainingNanos = timeoutMs * 1_000_000L
        while (queue.isEmpty()) {
            if (closed) return null
            if (timeoutMs <= 0) {
                notEmpty.await()
            } else {
                if (remainingNanos <= 0) return null
                remainingNanos = notEmpty.awaitNanos(remainingNanos)
            }
        }
        taken++
        return queue.removeFirst()
    }

    /** 非阻塞取一帧。 */
    fun poll(): Frame? = lock.withLock {
        if (queue.isEmpty()) return null
        taken++
        queue.removeFirst()
    }

    /** 取当前所有帧并清空（调试/回放用）。 */
    fun drain(): List<Frame> = lock.withLock {
        val out = queue.toList()
        queue.clear()
        out
    }

    /** 清空但不关闭（例如收到新 CONFIG 后要丢弃旧 GOP）。 */
    fun clear() = lock.withLock {
        queue.clear()
        notEmpty.signalAll()
    }

    /** 关闭并唤醒所有等待者（计划 1.5：`CLSE`/异常时必须唤醒）。 */
    override fun close() {
        lock.withLock {
            closed = true
            queue.clear()
            notEmpty.signalAll()
        }
    }

    private fun reportDrop(frame: Frame) {
        onDrop?.invoke(frame)
    }

    override fun toString(): String =
        "FrameQueue(${StreamId.name(streamId)} size=$size/$capacity " +
            "dropped=$droppedTotal[$droppedNonKey nonKey/$droppedKey key])"
}
