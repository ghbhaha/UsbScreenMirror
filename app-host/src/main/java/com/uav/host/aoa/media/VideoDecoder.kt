package com.uav.host.aoa.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.uav.host.common.AndroidLogger
import com.uav.protocol.Logger
import com.uav.protocol.aoa.Frame
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.w
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * 宿主侧 H.264 解码器（计划 4.5 / 4.6）。
 *
 * 两个冻结的实现细节：
 *
 * 1. **CONFIG 帧 payload 整体作为 `csd-0` 注入**，不预先按 `00 00 00 01` 切分。
 *    编码器输出的 CONFIG 本身就是 SPS+PPS 拼接，整体注入在绝大多数机型上可用；
 *    **仅当 configure 失败时才回退**为按起始码切分成 `csd-0`/`csd-1`。
 * 2. `configure` 的 `width`/`height` 取**流头**的值（唯一尺寸权威来源），
 *    **不解析 SPS 位流语法** —— MVP 完全不做位流解析。
 *
 * 线程模型：由调用方（[com.uav.host.session.HostSession] 的消费线程）驱动 [feed]，
 * 解码输出直接 `releaseOutputBuffer(index, true)` 渲到 [surface]。**本类自身不建线程**。
 */
class VideoDecoder(
    private val surface: Surface,
    private val logger: Logger = AndroidLogger,
    private val onConfigured: ((Int, Int) -> Unit)? = null,
    private val onError: ((Throwable) -> Unit)? = null,
) {

    private var codec: MediaCodec? = null

    @Volatile
    private var configuredSize: Pair<Int, Int>? = null

    val framesQueued = AtomicLong()
    val framesDecoded = AtomicLong()
    val inputStalls = AtomicLong()

    /**
     * 渲染门（A/V 同步用，10.20③）：在 `releaseOutputBuffer(index, true)` **之前**调用，
     * 返回 `false` 表示这一帧不渲染（直接丢弃，不交给 SurfaceFlinger）。
     *
     * ⚠️ 门内**允许阻塞一小会儿**（等待对齐）—— 它跑在解码线程里，所以只应做几十毫秒量级的等待。
     * 为 null 时行为与从前完全一样（逐帧直渲）。
     */
    var frameGate: ((ptsUs: Long, atNanos: Long) -> Boolean)? = null

    /** 被渲染门丢弃的帧数（诊断用）。 */
    val gateDropped = AtomicLong()
    val configureFailures = AtomicLong()

    /** 是否已完成 configure。 */
    val isConfigured: Boolean get() = codec != null

    /** Surface 是否已失效（失效后本实例不再渲染，需重建）。 */
    val isSurfaceLost: Boolean get() = surfaceLost

    /** 当前生效的解码尺寸（= 流头尺寸）。 */
    val size: Pair<Int, Int>? get() = configuredSize

    private val bufferInfo = MediaCodec.BufferInfo()

    /**
     * Surface 已失效（被销毁 / 被回收）。
     *
     * 一旦置位就**不再投喂、不再排空** —— 解码器绑定的 Surface 是 configure 那一刻捕获的，
     * 而 Android 侧 Surface 失效后任何 `releaseOutputBuffer(render=true)` 都会抛
     * `CodecException: rendering to non-initialized(obsolete) surface`。
     */
    @Volatile
    private var surfaceLost = false

    /**
     * 每解出一帧、即将交给 Surface 合成时回调 —— 端到端延迟测量用（计划 4.10）。
     *
     * @param ptsUs   该输出帧的 PTS（微秒，**手机侧时基**），用来反查它的采集时刻
     * @param atNanos 本机把这一帧提交给 SurfaceFlinger 的时刻（本机单调时钟）
     *
     * ⚠️ **口径**：这里量到的是「解码输出 → 提交合成」，**不含最后的上屏延迟**
     * —— SurfaceFlinger 要等下一个 vsync 才真正点亮像素（60Hz 下约 8~16ms）。
     * 所以"真实上屏延迟"≈ 本值 + 一个 vsync 周期，验收时按这个上界理解。
     */
    var onFrameDecoded: ((ptsUs: Long, atNanos: Long) -> Unit)? = null

    /**
     * 用 CONFIG 帧驱动的 configure。**已配置时也会先释放再重建**（CONFIG 永不吞掉）。
     *
     * @return 是否成功
     */
    fun configure(width: Int, height: Int, configPayload: ByteArray): Boolean {
        if (width <= 0 || height <= 0) {
            logger.e(TAG, "拒绝非法尺寸 ${width}x$height")
            return false
        }
        releaseCodec()

        // ---- 首选：整体注入 csd-0 ----
        if (tryConfigure(width, height, listOf(configPayload))) {
            configuredSize = width to height
            logger.i(TAG, "decoder configured ${width}x$height (csd-0 = ${configPayload.size}B 整体注入)")
            onConfigured?.invoke(width, height)
            return true
        }

        // ---- 回退：按起始码切分 ----
        val nals = splitAnnexB(configPayload)
        logger.w(TAG, "整体注入失败，回退按起始码切分为 ${nals.size} 段")
        if (nals.size >= 1 && tryConfigure(width, height, nals.take(2))) {
            configuredSize = width to height
            logger.i(TAG, "decoder configured ${width}x$height (csd fallback, ${nals.size} NALs)")
            onConfigured?.invoke(width, height)
            return true
        }

        configureFailures.incrementAndGet()
        logger.e(TAG, "decoder configure 失败（整体与切分两种方式都试过）")
        return false
    }

    /**
     * 逐个组合尝试 configure。
     *
     * ★ 为什么需要"去掉调优参数再试一次"（真机教训，RK356x 电视盒子）：
     *   `MediaFormat.setInteger(KEY_LOW_LATENCY, 1)` **永远不会失败** —— 它只是往包里塞一个键。
     *   真正的失败发生在 `configure()` 里：`ACodec` 会去 `setConfig(OMX_…lowLatency)`，
     *   而 RK 的 **legacy OMX 组件**（`OMX.rk.video_decoder.avc`）对这个 index 返回
     *   `UnsupportedIndex(0x8000101a)`，于是 `ACodec` **直接判 configureCodec 失败**：
     *   ```
     *   E/ACodec: decoder can not set low-latency to 1 (err -1010)
     *   E/ACodec: [OMX.rk.video_decoder.avc] configureCodec returning error -1010
     *   ```
     *   ⇒ 原来那句注释"不支持就忽略"是错的：它**会**把启动挡死，且只在老组件上出现
     *   （手机端 MTK/高通用的是 Codec2，能接受这个键，所以一直没暴露）。
     *   ⇒ 所以调优参数必须是**可退让的**：先带调优试一次，失败就用最小 format 再试。
     */
    private fun tryConfigure(width: Int, height: Int, csdUnits: List<ByteArray>): Boolean =
        tryConfigure(width, height, csdUnits, tune = true) ||
            tryConfigure(width, height, csdUnits, tune = false)

    /**
     * @param tune 是否附加 `low-latency` / `priority` 这类**非必需**调优键。
     *   老厂商 OMX 组件可能因为它们直接拒绝 configure（见上）。
     */
    private fun tryConfigure(
        width: Int,
        height: Int,
        csdUnits: List<ByteArray>,
        tune: Boolean,
    ): Boolean {
        var c: MediaCodec? = null
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
            csdUnits.forEachIndexed { i, unit ->
                format.setByteBuffer("csd-$i", ByteBuffer.wrap(unit))
            }
            if (tune) {
                // ---- 低延迟（10.31）----
                // ① 低延迟模式：解码器凑够一帧就立刻输出，不等后续参考帧（默认会攒几帧）。
                // ⚠️ 设不进去不报错，是 configure() 时才失败 —— 所以外面还有一轮不带调优的重试。
                runCatching { format.setInteger(KEY_LOW_LATENCY, 1) }
                    .onFailure { logger.w(TAG, "设置 low-latency 失败（忽略）: ${it.message}") }
                // ② 实时优先级：与编码端同理，别被系统降级。
                runCatching { format.setInteger(KEY_PRIORITY, 0) }
                    .onFailure { logger.w(TAG, "设置 priority 失败（忽略）: ${it.message}") }
            }
            c = MediaCodec.createDecoderByType(MIME_TYPE)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            if (!tune) {
                logger.w(TAG, "去掉 low-latency/priority 后才 configure 成功 —— 本机解码器不接受这些调优键（已降级）")
            }
            return true
        } catch (t: Throwable) {
            logger.w(
                TAG,
                "configure(csdUnits=${csdUnits.size}, tune=$tune) 抛异常: ${t.message}"
            )
            runCatching { c?.stop() }
            runCatching { c?.release() }
            return false
        }
    }

    /**
     * 投喂一帧并顺带把输出缓冲排空。
     *
     * ⚠️ `dequeueInputBuffer` 返回 -1 时**不要忙等死循环**（计划 4.6）——用带超时的
     * dequeue，拿不到就让出一次，下一帧再试。
     *
     * @return true 表示该帧已被送入解码器
     */
    fun feed(frame: Frame): Boolean {
        if (surfaceLost) return false
        val c = codec ?: return false

        val inIndex = try {
            c.dequeueInputBuffer(INPUT_TIMEOUT_US)
        } catch (t: Throwable) {
            onError?.invoke(t)
            return false
        }

        if (inIndex < 0) {
            // 输入缓冲暂不可用：不忙等，先排空输出
            inputStalls.incrementAndGet()
            drainOutput(c)
            return false
        }

        try {
            val buf = c.getInputBuffer(inIndex) ?: return false
            buf.clear()
            buf.put(frame.data)
            val flags = if (frame.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            c.queueInputBuffer(inIndex, 0, frame.data.size, frame.pts, flags)
            framesQueued.incrementAndGet()
        } catch (t: Throwable) {
            logger.e(TAG, "queueInputBuffer 失败: ${t.message}", t)
            onError?.invoke(t)
            return false
        }

        drainOutput(c)
        return true
    }

    /** 排空所有已就绪的输出缓冲，**直接渲染到 Surface**。 */
    fun drainOutput() {
        if (surfaceLost) return
        codec?.let { drainOutput(it) }
    }

    private fun drainOutput(c: MediaCodec) {
        while (true) {
            val outIndex = try {
                c.dequeueOutputBuffer(bufferInfo, 0)
            } catch (t: Throwable) {
                onError?.invoke(t)
                return
            }
            if (outIndex < 0) return // INFO_TRY_AGAIN_LATER / FORMAT_CHANGED / BUFFERS_CHANGED
            // 先取时间戳与时刻，再提交 —— 取在 release 之前更接近"交给 SF"的真实时刻
            val ptsUs = bufferInfo.presentationTimeUs
            val atNanos = System.nanoTime()
            // ★ 渲染门（10.20③）：不通过就不渲染，直接归还缓冲（不碰 Surface，天然安全）
            val gate = frameGate
            if (gate != null && !gate(ptsUs, atNanos)) {
                gateDropped.incrementAndGet()
                runCatching { c.releaseOutputBuffer(outIndex, false) }
                continue
            }
            // 直渲：releaseOutputBuffer(index, true) 把这一帧交给 SurfaceFlinger 合成
            try {
                c.releaseOutputBuffer(outIndex, true)
            } catch (t: Throwable) {
                // ★ 这里曾经**不加保护**，于是踩了一个整进程崩溃：
                //
                //   `SurfaceView` 的 Surface 失效（Activity 销毁/重建、屏幕关闭、
                //   系统回收）后，渲染会抛
                //   `CodecException: rendering to non-initialized(obsolete) surface`。
                //   而这一层就是解码线程（`uav-decode`）的最内层 —— 异常没人接住，
                //   线程直接带着 FATAL EXCEPTION 死掉，**整个 App 崩**。
                //
                //   而"Surface 已销毁、会话还没停"这段窗口是**真实存在**的：
                //   `surfaceDestroyed` 回调先于 `onDestroy`，中间解码线程完全可能
                //   正好在渲一帧。所以必须在最内层接住，把它降级成"画面停住 + 日志"。
                surfaceLost = true
                logger.w(
                    TAG,
                    "Surface 已失效，停止渲染（${t.javaClass.simpleName}: ${t.message}）" +
                        " —— 会话层会重建解码器",
                )
                onError?.invoke(t)
                return
            }
            framesDecoded.incrementAndGet()
            onFrameDecoded?.invoke(ptsUs, atNanos)
        }
    }

    fun release() {
        releaseCodec()
        configuredSize = null
        surfaceLost = false
    }

    private fun releaseCodec() {
        val c = codec ?: return
        codec = null
        runCatching { c.stop() }.onFailure { logger.w(TAG, "codec.stop 失败: ${it.message}") }
        runCatching { c.release() }.onFailure { logger.w(TAG, "codec.release 失败: ${it.message}") }
    }

    fun stats(): String =
        "decoded=${framesDecoded.get()} queued=${framesQueued.get()} " +
            "inputStalls=${inputStalls.get()} cfgFailures=${configureFailures.get()} " +
            "gateDropped=${gateDropped.get()} " +
            "size=${configuredSize?.let { "${it.first}x${it.second}" } ?: "-"}"

    companion object {
        const val TAG = "UAV-Video"

        /** H.264（与流头 codecId `avc1` 对应）。 */
        const val MIME_TYPE = "video/avc"

        /** 输入缓冲 dequeue 超时（微秒）；宁可短一些，避免拖住消费线程。 */
        const val INPUT_TIMEOUT_US = 10_000L

        /**
         * 低延迟解码（10.31）。
         *
         * ⚠️ 用字符串而非 `MediaFormat.KEY_LOW_LATENCY`：后者是 API 30 的常量，
         *   本模块 minSdk 23，直接引用会触发 lint NewApi；这些 key 的字符串值本身是稳定的。
         *   解码器不认识时会被忽略（configure 不会因此失败）。
         */
        private const val KEY_LOW_LATENCY = "low-latency"

        /** 实时优先级（0 = realtime），与编码端对齐。 */
        private const val KEY_PRIORITY = "priority"

        /**
         * 按 Annex-B 起始码切分 NAL。
         *
         * **只在整体注入 csd-0 失败时作为回退路径使用**，不是主路径。
         * 真正的实现在 `:protocol` 的 [com.uav.protocol.media.AnnexB] —— 纯字节运算放那里才能被 JVM 单测覆盖。
         */
        fun splitAnnexB(data: ByteArray): List<ByteArray> = com.uav.protocol.media.AnnexB.split(data)
    }
}
