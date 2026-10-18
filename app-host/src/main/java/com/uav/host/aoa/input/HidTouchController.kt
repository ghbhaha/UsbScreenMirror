package com.uav.host.aoa.input

import android.hardware.usb.UsbDeviceConnection
import com.uav.host.common.AndroidLogger
import com.uav.host.aoa.link.LinkCoordinator
import com.uav.host.aoa.usb.HidInjector
import com.uav.protocol.Logger
import com.uav.protocol.d
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.input.HidTouchPanel
import com.uav.protocol.input.MultiTouchTracker
import com.uav.protocol.input.TouchMapper
import com.uav.protocol.input.TouchSlot
import com.uav.protocol.w
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 宿主屏幕触摸 → 手机（AOA **HID 触摸屏**注入，支持**多点**）。
 *
 * ### 分工
 *
 * - 「触点 → 槽位」的分配是纯逻辑，在 `:protocol` 的 [MultiTouchTracker]（有单测）；
 * - 坐标换算同样是纯逻辑，在 `:protocol` 的 [TouchMapper]（有单测）；
 * - 本类只管**状态与串行发送**：注册时机、坐标空间变化（旋转）时的重注册、
 *   以及"断链时手指还按着"的重发。
 *
 * ### 四条必须守住的规则（都是真机上踩出来的）
 *
 * 1. **所有 USB 操作在 worker 线程**（本类自带单线程执行器）。
 *    用单线程而不是线程池，是因为 HID 报告**必须保序** —— 顺序错了手机上就会出现
 *    幽灵点击或手指"卡住"。
 * 2. ★ **HID 的 id 只在本次连接内有效**：重枚举后旧 id 作废，
 *    必须重新 `REGISTER_HID` + 重发描述符，并**重发当前触摸状态**。
 *    见 `docs/实施计划.md` 10.8 / 10.11。
 * 3. ★ **注册前要扫掉残留**：HID 注册挂在手机的配件功能上，只有**真重枚举**才拆得掉；
 *    而"已是配件"的快路径**不重枚举** → 每次重连都会在手机上多留一个幽灵触摸屏。
 * 4. ★ **坐标空间随旋转变**：手机屏幕物理尺寸只在 `CNXN` 报一次，旋转后要靠**视频方向**
 *    推（[TouchMapper.logicalPhoneSize]）。空间变了必须**重新注册**。
 *
 * ### 线程约定
 *
 * 触摸事件相关方法（[down]/[move]/[up]/[cancelAll]）**只在触摸线程（UI 线程）调用** ——
 * [tracker] 不是线程安全的。快照取好后交给 worker 去编码/发送。
 */
class HidTouchController(private val logger: Logger = AndroidLogger) {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "uav-hid-touch").apply { isDaemon = true }
    }

    /** 触点 → 槽位分配（只在触摸线程改）。 */
    private val tracker = MultiTouchTracker(HidTouchPanel.MAX_FINGERS)

    /**
     * 最近一次的触点快照（不可变）。
     *
     * 重连/重注册后据此把"当前按着"补发回去，否则手机上手指会"卡住"不抬。
     */
    @Volatile
    private var lastSnapshot: List<TouchSlot> = emptyList()

    /** 当前连接对应的注入器；断链置 null。 */
    @Volatile
    private var injector: HidInjector? = null

    /** 已注册的 HID id；0 = 未注册。 */
    @Volatile
    private var registeredId = 0

    /** HID 逻辑空间（= 手机屏幕在当前方向下的像素尺寸）。 */
    @Volatile
    private var spaceWidth = 0

    @Volatile
    private var spaceHeight = 0

    /** 移动事件的节流时刻（只在触摸线程读写）。 */
    private var lastMoveNanos = 0L

    /** id 递增分配：同一个 id 重复 REGISTER_HID 语义不明确，换个新的更省心。 */
    private val idSeq = AtomicInteger(0)

    /** 本次连接是否已经扫过残留 id（只做一次）。 */
    private var sweptThisConnection = false

    /**
     * 取"当前那条 USB 连接"（链路层提供）。
     *
     * 只用于 [rebindInjectorLocked] 的自愈兜底；正常路径仍走 [onLinkReady]。
     */
    @Volatile
    var connectionProvider: () -> UsbDeviceConnection? = { null }

    // ------------------------------------------------------------------ 链路

    /**
     * 链路就绪：绑定本次连接的 `UsbDeviceConnection`。
     *
     * ⚠️ 重枚举后旧的 HID 注册已失效（id 作废），所以这里把 id 清零，
     *    等 [updateGeometry] 重新注册。
     */
    fun onLinkReady(connection: UsbDeviceConnection) {
        worker.execute {
            injector = HidInjector(connection, logger)
            logger.i(TAG, "链路就绪：已绑定注入器")
            registeredId = 0
            idSeq.set(0)
            sweptThisConnection = false
            if (lastSnapshot.any { it.down }) {
                logger.i(TAG, "链路重建：手指仍按着，注册后会重发")
            }
        }
    }

    /** 链路断开：清掉注入器与 id（**保留**触点状态，重连后据此重发）。 */
    fun onLinkLost() {
        worker.execute {
            logger.i(TAG, "链路断开：作废注入器（registeredId=$registeredId）")
            injector = null
            registeredId = 0
        }
    }

    // ------------------------------------------------------------------ 坐标空间

    /**
     * 更新几何信息并（必要时）注册/重注册触摸屏。
     *
     * 由"拿到视频尺寸"的时刻调用（解码器 configure 成功），因为只有那时
     * 手机屏幕尺寸（CNXN）与视频方向都已知。
     */
    fun updateGeometry(screenW: Int, screenH: Int, videoW: Int, videoH: Int) {
        val size = TouchMapper.logicalPhoneSize(screenW, screenH, videoW, videoH)
        if (size == null) {
            // 回放模式没有 CNXN，拿不到手机屏幕尺寸 —— 正常，静默跳过
            logger.d(TAG, "几何信息不全（screen=${screenW}x$screenH），暂不注册触摸屏")
            return
        }
        worker.execute {
            if (spaceWidth == size.width && spaceHeight == size.height) {
                ensureRegisteredLocked()
                return@execute
            }
            if (registeredId != 0) {
                // 描述符里的 Logical Max 就是空间尺寸，改不了 —— 只能注销后按新空间重新注册
                logger.i(
                    TAG,
                    "坐标空间变化（${spaceWidth}x$spaceHeight → ${size.width}x${size.height}），重新注册触摸屏",
                )
                injector?.unregister(registeredId)
                registeredId = 0
            }
            spaceWidth = size.width
            spaceHeight = size.height
            ensureRegisteredLocked()
        }
    }

    /** 强制重新注册（排障用，走 `--ez hid_probe true`）。 */
    fun forceReRegister() {
        worker.execute {
            if (registeredId != 0) {
                injector?.unregister(registeredId)
                registeredId = 0
            }
            ensureRegisteredLocked()
        }
    }

    /**
     * 注入器丢了就再要一次当前连接（**自愈**）。
     *
     * 为什么需要：注入器只在 [onLinkReady] 里绑，而 [onLinkLost] 会把它清成 null。
     * 重连路径上"清掉"和"重新绑"本来是配对的，但**不配对的情况真实存在**：
     * `LinkCoordinator.stopSession()` 在错误收口、重协商失败重试、轮询接管等多处被调，
     * 其中若有任何一条后续没走到 `onReady`（例如握手被 `busy` 挡掉、或直接失败），
     * 注入器就永久为 null —— 表现是**画面正常、但触摸从此完全没反应**，
     * 且只有一条 W 级日志，极难发现（真机踩到过）。
     *
     * 兜底思路：只要能拿到"当前那条 USB 连接"，触摸就不该失效
     * —— 连接失效时 `registerAndLoad` 自己会失败并打日志，不会误报成功。
     */
    private fun rebindInjectorLocked(): HidInjector? {
        val connection = connectionProvider() ?: return null
        val fresh = HidInjector(connection, logger)
        injector = fresh
        registeredId = 0
        logger.i(TAG, "注入器自愈：从当前连接重新绑定（此前 onLinkReady 与 onLinkLost 不配对）")
        return fresh
    }

    /** 只在 worker 线程调用。 */
    private fun ensureRegisteredLocked() {
        val inj = injector ?: rebindInjectorLocked()
        if (inj == null) {
            // 这条路**必须留日志**：曾经因为静默 return，排查"触摸屏怎么都注册不上"
            // 时多花了不少时间（真因是宿主侧调用顺序把刚绑好的连接又清掉了）。
            logger.w(TAG, "尚未绑定连接（链路未就绪），暂不注册触摸屏")
            return
        }
        if (registeredId != 0) return
        if (spaceWidth <= 0 || spaceHeight <= 0) {
            logger.w(TAG, "还没有触摸坐标空间（缺 CNXN / 流头），暂不注册")
            return
        }

        // ★ 注册前先扫掉**上一次连接残留**的 HID 设备。
        //
        //   为什么需要：HID 注册挂在手机的**配件功能**上，只有真正的 USB 重枚举才会把它拆掉。
        //   而实际运行里大量路径（宿主 App 重启后走"已是配件"快路径、手机不重枚举）
        //   **不会重枚举** —— 于是每次重连都往手机上多留一个"幽灵触摸屏"（实测 9→10→…）。
        //   旧 id 在新连接上仍然有效（同一个 gadget function），所以扫一遍能真的清掉；
        //   不存在的 id 请求会失败，属正常，不打警告。
        if (!sweptThisConnection) {
            sweptThisConnection = true
            var removed = 0
            for (probe in STALE_ID_CANDIDATES) {
                if (inj.unregister(probe, logFailure = false)) removed++
            }
            if (removed > 0) {
                logger.i(TAG, "清理了 $removed 个上一次连接残留的 HID 设备（避免手机累积幽灵触摸屏）")
            }
        }

        val id = idSeq.incrementAndGet()
        val descriptor = HidTouchPanel.descriptor(spaceWidth, spaceHeight)
        if (!inj.registerAndLoad(id, descriptor)) {
            // ⚠️ 用 WARN 而不是 ERROR：**机顶盒 / 车机根本没有触摸屏**，注册失败是正常结果
            //   （实测 RK356x 盒子 REGISTER_HID 直接返回 n=-1）。真正的失败情形是
            //   "触屏宿主上注册不上" —— 那才需要查手机是否在配件模式。
            logger.w(
                TAG,
                "HID 触摸回传未注册（画面不受影响）。触屏宿主请检查手机是否在配件模式；" +
                    "机顶盒/车机没有触摸源，可忽略。",
            )
            return
        }
        registeredId = id
        logger.i(
            TAG,
            "HID 触摸屏已注册 id=$id，逻辑空间 ${spaceWidth}x$spaceHeight" +
                "，最多 ${HidTouchPanel.MAX_FINGERS} 点（描述符 ${descriptor.size}B）",
        )
        // ★ 重连/重注册后必须把"当前按着"这一状态补发回去，否则手机上手指会"卡住"不抬
        val snap = lastSnapshot
        if (snap.any { it.down }) {
            logger.i(TAG, "重发当前触点（${snap.count { it.down }} 个）")
            rawSendLocked(snap)
        }
    }

    // ------------------------------------------------------------------ 触摸事件（触摸线程）

    /** 手指按下。 */
    fun down(pointerId: Int, x: Int, y: Int) {
        if (!tracker.down(pointerId, x, y)) {
            logger.w(TAG, "触点已满（${HidTouchPanel.MAX_FINGERS}），忽略 pointerId=$pointerId")
            return
        }
        publish()
    }

    /**
     * 手指移动。
     *
     * 拖动会以屏幕刷新率产生事件（60~120Hz），所以先在**调用线程**做一次节流 ——
     * 否则执行器队列会被排满，触摸延迟会越拖越大。
     */
    fun move(pointerId: Int, x: Int, y: Int) {
        if (!tracker.move(pointerId, x, y)) return
        val now = System.nanoTime()
        if (now - lastMoveNanos < MOVE_INTERVAL_NANOS) return
        lastMoveNanos = now
        publish()
    }

    /** 手指抬起。 */
    fun up(pointerId: Int) {
        if (!tracker.up(pointerId)) return
        publish()
    }

    /** 全部抬起（手势取消 / 宿主切后台）。 */
    fun cancelAll() {
        if (tracker.activeCount == 0) return
        tracker.cancelAll()
        publish()
    }

    /** 取快照 → 存起来（供重连重发）→ 交给 worker 编码发送。 */
    private fun publish() {
        val snap = tracker.snapshot()
        lastSnapshot = snap
        worker.execute { sendLocked(snap) }
    }

    /**
     * 发一次点按（按住 [holdMs] 后抬起）。
     *
     * 坐标是**手机屏幕像素**（与探针阶段一致）。用于 `--ez hid_tap true` 排障：
     * 不用真去点宿主屏幕就能验证注入链路。
     *
     * 给了 [x2]/[y2] 就是**双指同时按** —— 这是验证"多点报告真的被手机当成两根手指"
     * 最直接的手段（宿主侧没法简单地凭空造出双指手势）。
     */
    fun debugTap(x: Int, y: Int, holdMs: Int, x2: Int? = null, y2: Int? = null) {
        val second = if (x2 != null && y2 != null) x2 to y2 else null
        val down = List(HidTouchPanel.MAX_FINGERS) { i ->
            when (i) {
                0 -> TouchSlot(i, x, y, true)
                1 -> if (second != null) TouchSlot(i, second.first, second.second, true) else TouchSlot(i, 0, 0, false)
                else -> TouchSlot(i, 0, 0, false)
            }
        }
        val up = List(HidTouchPanel.MAX_FINGERS) { i -> TouchSlot(i, 0, 0, false) }
        worker.execute {
            if (registeredId == 0) ensureRegisteredLocked()
            if (registeredId == 0) {
                logger.w(TAG, "HID 点按：尚未注册（先看日志里注册失败的原因）")
                return@execute
            }
            val hold = holdMs.coerceIn(1, MAX_TAP_HOLD_MS)
            val okDown = rawSendLocked(down)
            try {
                Thread.sleep(hold.toLong())
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val okUp = rawSendLocked(up)
            val n = if (second == null) 1 else 2
            logger.i(TAG, "HID 点按 ${n} 指 ($x,$y) 按住 ${hold}ms：按下=$okDown 抬起=$okUp")
        }
    }

    // ------------------------------------------------------------------ 发送（worker 线程）

    /** 只在 worker 线程调用：必要时先注册，再发一条报告。 */
    private fun sendLocked(snapshot: List<TouchSlot>) {
        if (registeredId == 0) ensureRegisteredLocked()
        if (registeredId == 0) return
        rawSendLocked(snapshot)
    }

    /** 只在 worker 线程调用：不做注册检查，直接发。 */
    private fun rawSendLocked(snapshot: List<TouchSlot>): Boolean {
        val inj = injector ?: return false
        val id = registeredId
        if (id == 0) return false
        return if (inj.sendReport(id, HidTouchPanel.report(snapshot))) {
            true
        } else {
            // 发失败通常意味着链路已经没了；把 id 作废，等重新注册
            logger.w(TAG, "SEND_HID_EVENT[$id] 失败，作废该注册（等链路重建后重新注册）")
            registeredId = 0
            false
        }
    }

    fun close() {
        tracker.cancelAll()
        lastSnapshot = emptyList()
        injector = null
        registeredId = 0
        worker.shutdownNow()
    }

    /** 供状态行显示。 */
    fun describe(): String =
        if (registeredId != 0) {
            "触摸 ${spaceWidth}x$spaceHeight/${HidTouchPanel.MAX_FINGERS}点"
        } else {
            "触摸未注册"
        }

    companion object {
        const val TAG = "UAV-Host"

        /** 移动事件的最小间隔（≈60Hz）。EP0 每条报告一次控制传输，没必要更密。 */
        const val MOVE_INTERVAL_NANOS = 16_000_000L

        /** 排障点按的按住时长上限，防止传个离谱的值把 worker 占住。 */
        const val MAX_TAP_HOLD_MS = 10_000

        /**
         * 清理残留时扫描的 id 空间。
         *
         * id 由宿主自分配、从 1 递增（见 [idSeq]），一次连接内不会用到这么大；
         * 范围取宽一点是为了容忍"一次连接里旋转重注册很多次"的极端情况。
         */
        val STALE_ID_CANDIDATES: IntRange = 1..12
    }
}
