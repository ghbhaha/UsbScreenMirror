package com.uav.protocol

import com.uav.protocol.adb.AdbMessage
import com.uav.protocol.aoa.Command
import com.uav.protocol.aoa.Transport
import java.io.ByteArrayOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 脚本化 [ByteChannel]：按预设数据喂给读侧，可注入**短读**与**"返回 0 字节"**（ZLP / 读超时）。
 *
 * 这是整份单测里最重要的一个替身 —— 计划单测 4 要验证的
 * "注入偶尔返回 0 字节的假 ByteChannel，readFully 仍凑满且不越界"
 * 正是靠 `zeroEvery` 实现的。
 */
class FakeByteChannel(
    private val data: ByteArray,
    private val chunk: Int = Int.MAX_VALUE,
    private val zeroEvery: Int = 0,
) : ByteChannel {

    private var pos = 0
    private var calls = 0

    /** 收到的写入字节（用于断言发送路径）。 */
    val written = ByteArrayOutputStream()

    var zeroReturns: Int = 0
        private set

    /** 底层收到过的最大单次请求长度（用于断言"单次请求 ≤ 64 KiB"）。 */
    var maxRequested: Int = 0
        private set

    override val label: String get() = "fake(chunk=$chunk,zeroEvery=$zeroEvery)"

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        calls++
        if (len > maxRequested) maxRequested = len
        if (pos >= data.size) return -1
        if (zeroEvery > 0 && calls % zeroEvery == 0) {
            // ★ 模拟 ZLP / 读超时：返回 0 而非 -1，必须被 Transport 丢弃重试
            zeroReturns++
            return 0
        }
        val n = minOf(len, chunk, data.size - pos)
        System.arraycopy(data, pos, dst, off, n)
        pos += n
        return n
    }

    override fun write(src: ByteArray, off: Int, len: Int) {
        written.write(src, off, len)
    }

    override fun close() = Unit
}

/**
 * 内存单向管道。`read` 在无数据时阻塞（带 5s 上限避免测试挂死），`write` 立即完成。
 *
 * Kotlin 的 `Any` 不暴露 `wait`/`notify`，故用 `ReentrantLock` + `Condition`。
 */
class MemoryPipe(private val name: String) {

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private var buf = ByteArray(8192)
    private var len = 0

    @Volatile
    private var closed = false

    fun write(src: ByteArray, off: Int, n: Int) {
        if (n == 0) return
        lock.withLock {
            if (closed) throw java.io.IOException("$name: pipe closed")
            if (len + n > buf.size) {
                var cap = buf.size * 2
                while (cap < len + n) cap *= 2
                buf = buf.copyOf(cap)
            }
            System.arraycopy(src, off, buf, len, n)
            len += n
            notEmpty.signalAll()
        }
    }

    fun read(dst: ByteArray, off: Int, max: Int): Int = lock.withLock {
        var remaining = 5_000_000_000L
        while (len == 0) {
            if (closed) return -1
            if (remaining <= 0) return 0
            remaining = notEmpty.awaitNanos(remaining)
        }
        val n = minOf(max, len)
        System.arraycopy(buf, 0, dst, off, n)
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
        return n
    }

    fun close() {
        lock.withLock {
            closed = true
            notEmpty.signalAll()
        }
    }

    companion object {
        /**
         * 造一对**交叉相连**的通道：
         * `A.write -> B.read`，`B.write -> A.read`。用于全双工会话测试。
         */
        fun pair(): Pair<MemoryPipeChannel, MemoryPipeChannel> {
            val ab = MemoryPipe("a->b")
            val ba = MemoryPipe("b->a")
            return MemoryPipeChannel(ab, ba, "sideA") to MemoryPipeChannel(ba, ab, "sideB")
        }
    }
}

/** [MemoryPipe] 的通道视图：写进 [sink]，从 [source] 读。 */
class MemoryPipeChannel(
    private val sink: MemoryPipe,
    private val source: MemoryPipe,
    override val label: String,
) : ByteChannel {

    override fun read(dst: ByteArray, off: Int, len: Int): Int = source.read(dst, off, len)

    override fun write(src: ByteArray, off: Int, len: Int) = sink.write(src, off, len)

    override fun close() {
        source.close()
        sink.close()
    }
}

/**
 * 构造含 `> 0x7F` 字节的测试数据。
 *
 * `byteArrayOf(...)` 的参数类型是 `Byte`，而 `0x95`、`0xCE` 这类字面量在 Kotlin 里是 `Int`
 * 且超出 Byte 范围，直接写会编译不过。这个辅助函数统一按"字节的数值"来理解入参。
 */
fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/** 组装一条未校验的原始报文（用于构造"坏 magic / 坏 checksum"等非法输入）。 */
fun rawMessage(
    command: Int,
    arg0: Int = 0,
    arg1: Int = 0,
    payload: ByteArray = ByteArray(0),
    declaredLength: Int = payload.size,
    declaredChecksum: Int = AdbMessage.checksumOf(payload),
): ByteArray {
    val out = ByteArray(AdbMessage.HEADER_SIZE + payload.size)
    AdbMessage.writeHeader(out, 0, command, arg0, arg1, declaredLength, declaredChecksum)
    System.arraycopy(payload, 0, out, AdbMessage.HEADER_SIZE, payload.size)
    return out
}

/** 便于直接传 [Command] 枚举的重载。 */
fun rawMessage(
    command: Command,
    arg0: Int = 0,
    arg1: Int = 0,
    payload: ByteArray = ByteArray(0),
    declaredLength: Int = payload.size,
    declaredChecksum: Int = AdbMessage.checksumOf(payload),
): ByteArray = rawMessage(command.wire, arg0, arg1, payload, declaredLength, declaredChecksum)
