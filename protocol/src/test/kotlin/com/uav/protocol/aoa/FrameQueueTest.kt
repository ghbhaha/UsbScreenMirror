package com.uav.protocol.aoa

import com.uav.protocol.media.CodecId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单测 8：有界队列的背压与丢帧策略（计划 1.5 / 4.7）。 */
class FrameQueueTest {

    private fun frame(marker: Int, key: Boolean = false, config: Boolean = false) =
        Frame(Flags.pack(marker.toLong(), keyFrame = key, config = config), byteArrayOf(marker.toByte()))

    @Test
    fun `队列深度默认为 4`() {
        assertEquals(4, FrameQueue(StreamId.VIDEO).capacity)
        assertEquals(4, Protocol.QUEUE_DEPTH)
    }

    @Test
    fun `未满时先进先出`() {
        val q = FrameQueue(StreamId.VIDEO)
        q.offer(frame(1))
        q.offer(frame(2))
        q.offer(frame(3))

        assertEquals(3, q.size)
        assertEquals(1L, q.poll()!!.pts)
        assertEquals(2L, q.poll()!!.pts)
        assertEquals(3L, q.poll()!!.pts)
        assertNull(q.poll())
    }

    @Test
    fun `队满时丢弃最旧的非关键帧而不是新帧`() {
        val q = FrameQueue(StreamId.VIDEO)
        q.offer(frame(1))                       // P
        q.offer(frame(2, key = true))           // KEY
        q.offer(frame(3))                       // P
        q.offer(frame(4))                       // P
        assertEquals(4, q.size)

        q.offer(frame(5))                       // 触发丢帧

        assertEquals(4, q.size)
        assertEquals(1L, q.droppedNonKey)
        assertEquals(0L, q.droppedKey)
        // 最旧的 P（pts=1）被丢，其余保持顺序
        val remaining = q.drain().map { it.pts }
        assertEquals(listOf(2L, 3L, 4L, 5L), remaining)
    }

    @Test
    fun `关键帧与 CONFIG 帧被优先保留`() {
        val q = FrameQueue(StreamId.VIDEO)
        q.offer(frame(1, config = true))        // CONFIG
        q.offer(frame(2, key = true))           // KEY
        q.offer(frame(3))                       // P
        q.offer(frame(4))                       // P

        repeat(2) { q.offer(frame(10 + it)) }   // 触发两次丢帧

        val remaining = q.drain()
        assertEquals(4, remaining.size)
        assertTrue("CONFIG 必须还在", remaining.any { it.isConfig })
        assertTrue("KEY 必须还在", remaining.any { it.isKeyFrame })
        assertEquals(2L, q.droppedNonKey)
        assertEquals(0L, q.droppedKey)
    }

    @Test
    fun `队内全是关键帧时退化为丢队首并单独计数`() {
        val q = FrameQueue(StreamId.VIDEO)
        repeat(4) { q.offer(frame(10 + it, key = true)) }

        q.offer(frame(99, key = true))

        assertEquals(4, q.size)
        assertEquals(0L, q.droppedNonKey)
        assertEquals(1L, q.droppedKey)
        assertEquals(1L, q.droppedTotal)
        // 队首（pts=10）被丢
        assertEquals(listOf(11L, 12L, 13L, 99L), q.drain().map { it.pts })
    }

    @Test
    fun `丢帧回调被触发且计数一致`() {
        val dropped = mutableListOf<Long>()
        val q = FrameQueue(StreamId.VIDEO, onDrop = { dropped.add(it.pts) })
        q.offer(frame(1))
        q.offer(frame(2))
        q.offer(frame(3))
        q.offer(frame(4))
        q.offer(frame(5))

        assertEquals(listOf(1L), dropped)
        assertEquals(1L, q.droppedTotal)
    }

    @Test
    fun `关闭后继续投递被拒绝且唤醒等待者`() {
        val q = FrameQueue(StreamId.VIDEO)
        var taken: Frame? = frame(0)
        val consumer = Thread { taken = q.take(5_000) }
        consumer.start()
        Thread.sleep(50)

        q.close()

        consumer.join(2_000)
        assertNull("关闭后 take 必须返回 null 而不是永久阻塞", taken)
        assertFalse(consumer.isAlive)
        assertFalse("已关闭的队列应拒绝新帧", q.offer(frame(1)))
    }

    @Test
    fun `队列为空且未关闭时 take 超时返回 null`() {
        val q = FrameQueue(StreamId.VIDEO)
        val start = System.nanoTime()
        assertNull(q.take(100))
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertTrue("应在约 100ms 内返回，实际 ${elapsed}ms", elapsed in 80..1_500)
    }

    @Test
    fun `流头被记录`() {
        val q = FrameQueue(StreamId.VIDEO)
        assertNull(q.header)
        q.onHeader(StreamHeader(CodecId.AVC1, 720, 1600))
        val h = q.header
        assertNotNull(h)
        assertEquals(720, h!!.width)
        assertEquals(1_600, h.height)
        assertTrue(h.isPortrait)
    }

    @Test
    fun `清空不影响后续投递`() {
        val q = FrameQueue(StreamId.VIDEO)
        q.offer(frame(1))
        q.offer(frame(2))
        q.clear()
        assertEquals(0, q.size)
        q.offer(frame(3))
        assertEquals(3L, q.poll()!!.pts)
    }

    @Test
    fun `队列名带流名便于日志定位`() {
        assertTrue(FrameQueue(StreamId.VIDEO).toString().contains("video"))
    }
}
