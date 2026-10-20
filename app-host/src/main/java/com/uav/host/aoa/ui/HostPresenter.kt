package com.uav.host.aoa.ui

import android.os.Handler
import android.os.SystemClock
import com.uav.host.common.DebugTools
import com.uav.host.aoa.flow.LinkCoordinator
import com.uav.host.aoa.capture.session.HostSession
import com.uav.host.aoa.media.QualityController
import com.uav.protocol.Logger
import com.uav.protocol.aoa.CloseReason
import com.uav.protocol.aoa.StreamHealth
import com.uav.protocol.e
import com.uav.protocol.i
import com.uav.protocol.ui.HostUiState
import com.uav.protocol.w

/**
 * 把**链路 / 画质 / 流健康**三类事件翻译成面板上的一个状态（10.34）。
 *
 * ## 为什么搬出来
 *
 * 这三类事件原本是 `HostActivity` 里的三个匿名对象（`linkListener` / `qualityListener`
 * / `statsTicker`），一共 ~140 行。它们**一条都不碰生命周期**：不读 Intent、不碰
 * Surface、不建会话，只做"事件 → [HostUiState] → 面板"的翻译，却和 Activity 的
 * `onCreate` / `onDestroy` / 回放 / 调试入口挤在同一个 660 行的文件里。
 *
 * 搬出来之后判据很清楚：
 *
 * | 谁 | 管什么 |
 * |---|---|
 * | `HostActivity` | 生命周期、装配、Intent、Surface 回调、用户动作入口 |
 * | 本类 | 事件 → 状态机 → 面板；**主状态行的唯一推进者** |
 *
 * ## 三条不能破坏的规矩（都是从真机事故里长出来的）
 *
 * 1. **进度事件不得降级已到达的阶段** —— 见 [HostUiState.onProgress] 的注释。
 *    画面在播时收到「会话已建立，等待视频流…」只更新副行，否则状态会被永久钉死。
 * 2. **只有硬事实能改主行**：[onLinkEstablished]（重连成功）、[onFirstFrame]、
 *    [QualityController.Listener.onQualityChange]（用户动作）、[onClosed]（断链）。
 * 3. **流健康只在"状态或秒数真的变了"时才重绘** —— 否则每秒重排一次面板
 *    （10.26「反复显示隐藏」的同款陷阱）。
 */
internal class HostPresenter(
    private val host: Host,
    private val mainHandler: Handler,
    private val logger: Logger,
) : LinkCoordinator.Listener, QualityController.Listener {

    /**
     * 本类需要宿主提供的东西。
     *
     * ⚠️ `panel` / `link` 都是 Activity 里的 `lateinit` 字段，**只能在真正要用的时候取**，
     * 所以这里用 getter 而不是构造期快照（与 `AoaReplay.Deps` 同一个理由）。
     */
    interface Host {
        /** 面板视图。 */
        val panel: HostPanel

        /** 链路调度器（`stopSession` / `requestRenegotiate` 等）。 */
        val link: LinkCoordinator

        /** 当前是否 1:1 像素模式（只影响重协商成功的副行文案）。 */
        fun oneToOne(): Boolean

        /**
         * 视频尺寸变了 ⇒ 触摸坐标空间跟着换。
         *
         * 由 Activity 分发给 `TouchForwarder` 与 `HidTouchController` —— 它是**唯一**
         * 同时持有这两者的人。
         */
        fun onVideoGeometry(peerWidth: Int, peerHeight: Int, width: Int, height: Int)

        /** 「对端没人应答」的引导文案（本地化在 `:app-host` 的 `strings.xml`）。 */
        fun peerAppMissingDetail(): String
    }

    /**
     * 面板状态机（纯逻辑在 `:protocol/HostUiState`，有 JVM 单测）。
     *
     * ★ 只在本类里推进：Activity 不再直接碰它，否则"主行的唯一权威"就又散了。
     */
    private val uiState = HostUiState()

    /**
     * 流健康度判定（纯逻辑在 `:protocol/StreamHealth`，有 JVM 单测）。
     *
     * ⚠️ 它要解决的不是"链路断了"（那种有 onClosed/onError），而是**链路好着、对面不动了**。
     */
    private val streamHealth = StreamHealth()

    /** 上一拍的健康度与"静默秒数"，用来避免每秒都重绘面板。 */
    private var lastHealthState = StreamHealth.State.HEALTHY
    private var lastSilentSec = -1L

    private var tickCount = 0L

    // ------------------------------------------------------------------ 给宿主的入口

    /** 首次绘制（`onCreate` 末尾）。 */
    fun refresh() = render()

    /** 开始每秒的统计 / 健康度刷新（`onCreate` 里 `post` 一次，之后自递归）。 */
    fun startStats() = mainHandler.post(statsTicker)

    /**
     * 停止周期刷新（`onDestroy`）。
     *
     * ⚠️ 不拔掉的话这条 `postDelayed` 会一直自递归下去，而它握着 `host`（Activity）——
     *   页面已经销毁，每秒还在读 `link.session` 并往 `panel` 上写。以前 `onDestroy`
     *   里有一句 `mainHandler.removeCallbacks(statsTicker)`，搬家时必须一起带过来。
     */
    fun stopStats() = mainHandler.removeCallbacks(statsTicker)

    /** USB 拔出：清场 + 把提示叫到眼前。 */
    fun onDetached(reason: String = "USB 已拔出，请重新插线或点 Scan/Retry") {
        uiState.onDetached(reason)
        render()
        // 这条提示藏在收起的菜单里就等于没提示
        host.panel.revealMenu()
    }

    /** 用户点「扫描 / 重试」（也用于调试入口 `--ez scan true`）。 */
    fun onScanRequested() {
        uiState.onScanRequested()
        render()
    }

    /** 用户点「强制重握手」。 */
    fun onForceHandshakeHint() {
        uiState.onProgress("强制 AOA 重握手中…")
        render()
    }

    /** 本地回放开始（调试通路）。 */
    fun onReplayStarted(label: String) {
        uiState.onReplay(label)
        render()
    }

    // ------------------------------------------------------------------ 链路事件

    override fun onProgress(text: String) {
        // ★ 10.23：进度文本**不再**直接写主状态行，只进副行 + 由状态机决定主行。
        //   原地重协商时画面根本没断，但这里照样会发「会话已建立，等待视频流…」——
        //   旧代码直接覆盖主行，而 onFirstFrame 不会再来，于是状态被永久钉死。
        uiState.onProgress(text)
        render()
    }

    override fun onLinkEstablished(text: String) {
        // ★ 硬事实：重连成功要把上一次的「链路出错 / 无输出」拉回「连接中」。
        //   以前这里只有一条 onProgress，而进度事件按规矩不动主行 ⇒ 自动重连
        //   明明成功了（音频都恢复了），主行还挂着 ERROR（2026-10-20 真机）。
        uiState.onLinkEstablished(text)
        render()
    }

    override fun onVideoSize(width: Int, height: Int) {
        // ★ 10.23：尺寸就绪只用来给"重协商中"收尾，**不能**提前宣告投屏中
        //   （还没出画面就报"投屏中"是同一类错误，只是方向相反）。
        //   同时把副行换成"新参数已生效"：否则重协商结束后副行会一直挂着
        //   「会话已建立，等待视频流…」—— 那句话在画面正在播的时候读起来很别扭。
        val wasRenegotiating = uiState.phase == HostUiState.Phase.RENEGOTIATING
        uiState.onVideoSize()
        if (wasRenegotiating) {
            uiState.onProgress(
                "已生效：${width}x$height" + if (host.oneToOne()) "（1:1 像素）" else "（高清）"
            )
        }
        render()
        host.panel.setVideoSize(width, height)
        // ★ 视频尺寸是**触摸坐标空间**的权威线索：手机屏幕的物理尺寸只在 CNXN 报一次，
        //   旋转后不会重报，只能靠视频方向推出它当前的长宽（见 TouchMapper）。
        //   空间变了控制器会自动重新注册 HID（描述符里的 Logical Max 就是空间尺寸）。
        host.link.session?.let {
            host.onVideoGeometry(it.peerWidth, it.peerHeight, width, height)
        }
    }

    override fun onFirstFrame() {
        // ★ 10.23：这是**唯一**能宣告"投屏中"的事件（首帧真的输出了）。
        uiState.onFirstFrame()
        render()
        // 画面出来了就把菜单收起来，让视频干净地占满整屏（只自动收一次）。
        host.panel.autoCollapseOnFirstFrame()
    }

    override fun onClosed(reason: String) {
        // 链路断开不是终态：前台轮询会自动重试，所以回"连接中"而非"空闲"。
        //
        // ★ 10.32：用户主动停止要和"意外断线"分开。前者要在界面上把原因**钉住**
        //   （否则紧接着的自动重连会把"用户停止投屏"刷成"CNXN 已发送…"），
        //   后者照旧随进度走。判据用协议层的同一个常量，两端不会失配。
        if (reason.contains(CloseReason.USER_STOP)) {
            uiState.onPeerStopped()
        } else {
            uiState.onClosed(reason)
        }
        render()
        host.panel.revealMenu()
    }

    override fun onError(message: String, cause: Throwable?) {
        // ★ 出错必须把菜单叫回来：面板收起时错误信息是看不见的，
        //   用户只会看到"画面不动"，无从判断是拔线还是授权被拒。
        uiState.onError(message)
        render()
        host.panel.revealMenu()
        logger.e(TAG, message, cause)
        // 链路层已负责 stopSession / 退避计数 / 自愈判定，这里只管"让用户可见"。
    }

    /**
     * 对端在配件模式下没人应答（典型：手机端没装配套 App）。
     *
     * 走独立回调而不是 [onError]：这不是链路故障，等自动重连永远不会成功，
     * 界面要给的是**可操作的去处**（下载按钮），而不是"错误"两个字。
     */
    override fun onPeerAppMissing() {
        uiState.onPeerAppMissing(host.peerAppMissingDetail())
        render()
        // 面板收起时这条引导是看不见的，必须主动叫回来
        host.panel.revealMenu()
    }

    override fun onSelfHealRequest(device: android.hardware.usb.UsbDevice) {
        uiState.onProgress("快路径未验证通过，强制重握手（改写配件身份）…")
        render()
        host.link.stopSession()
        host.link.onDeviceAttached(device)
    }

    // ------------------------------------------------------------------ 画质事件

    override fun onQualityChange(oneToOne: Boolean, reason: String) {
        uiState.onRenegotiate()
        uiState.onProgress(reason)
        render()
        host.panel.applyPixelModeUi(oneToOne)
        // ⚠️ 必须**先**打"预期内重开"标志，**再**发起重协商：
        //   一旦 renegotiate() 已返回 false 并开始回退重连，那期间的失败就不再是"预期内"的。
        host.link.beginExpectedRestart()
        host.link.requestRenegotiate()
    }

    // ------------------------------------------------------------------ 周期刷新

    /** 链路统计的周期刷新。UI 用紧凑形状，日志用完整形状（见 [HostSession.statsCompact]）。 */
    private val statsTicker = object : Runnable {
        override fun run() {
            val s = host.link.session
            val uiText = when {
                s != null -> s.statsCompact()
                else -> host.link.bulkStats() ?: "无活动会话"
            }
            host.panel.setStats(uiText)
            // 每 5 秒把统计打进日志：5 分钟压测（计划 4.10）要靠它读首末对比，
            // 也避免依赖 UI 取值（画面持续刷新时 uiautomator dump 会拿不到稳定帧）。
            // release 下关掉：发布包每 5 秒刷一条 logcat 没有意义（计划 5.2）。
            tickCount++
            if (DebugTools.ENABLED && tickCount % STATS_LOG_EVERY_TICKS == 0L) {
                logger.i(TAG, "STATS ${s?.stats() ?: uiText}")
            }
            checkStreamHealth(s)
            mainHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    /**
     * 每秒评估一次流健康度（10.30）。
     *
     * 只在**状态或秒数真的变了**才推进状态机并重绘 —— 否则每秒 render 一次，
     * 面板会持续重排（10.26 那个"反复显示隐藏"的教训：UI 动作要由真实变化驱动）。
     */
    private fun checkStreamHealth(session: HostSession?) {
        val now = SystemClock.elapsedRealtime()
        val lastAt = session?.lastVideoFrameAtMs ?: 0L
        val state = streamHealth.evaluate(now, lastAt)
        val silentMs = streamHealth.silentMs(now, lastAt)
        val silentSec = silentMs / 1000

        val changed = state != lastHealthState ||
            (state == StreamHealth.State.STALLED && silentSec != lastSilentSec) ||
            (state == StreamHealth.State.STOPPED && silentSec != lastSilentSec)
        if (!changed) return

        lastHealthState = state
        lastSilentSec = silentSec
        uiState.onStreamHealth(state, silentMs)
        render()
        if (DebugTools.ENABLED) {
            logger.w(
                TAG,
                "流健康：$state，已 ${silentSec}s 无新帧" +
                    "（判定阈值 stall=${StreamHealth.DEFAULT_STALL_MS}ms stop=${StreamHealth.DEFAULT_STOP_MS}ms）"
            )
        }
    }

    // ------------------------------------------------------------------ 渲染

    /** 把状态机画到面板上（唯一的主状态行渲染入口）。 */
    private fun render() {
        host.panel.render(uiState)
    }

    companion object {
        const val TAG = "UAV-Host"
        const val STATS_INTERVAL_MS = 1_000L
        const val STATS_LOG_EVERY_TICKS = 5L
    }
}
