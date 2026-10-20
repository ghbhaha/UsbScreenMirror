package com.uav.protocol.ui

import com.uav.protocol.aoa.CloseReason
import com.uav.protocol.aoa.StreamHealth

/**
 * 宿主面板的**状态机**（10.23）。
 *
 * ## 为什么需要它
 *
 * 以前面板那一行状态是**被事件流无条件覆盖**的：
 * ```kotlin
 * override fun onStatus(text: String) { runOnUiThread { statusText.text = text } }
 * ```
 * 而 `onStatus` 的入参其实混着**两种语义**：
 *
 * | 语义 | 例子 | 有效期 |
 * |---|---|---|
 * | 瞬时进度 | 「OPEN 已发送…」「会话已建立，等待视频流…」 | 只在"还没出画面"期间有意义 |
 * | 持久状态 | 「投屏中（首帧已输出）」「已断开」「ERROR」 | 直到下一次状态变化 |
 *
 * 一旦两者互相覆盖，界面就会说谎。真机踩到的就是这一条（10.23）：
 * **原地重协商**（10.18，切画质 / 显示区变化都会走它）在同一条连接上重发 `OPEN`，
 * 收到 `OKAY` 后照样发「会话已建立，等待视频流…」—— 可**画面从头到尾没断过**，
 * 而 `onFirstFrame()` 因为"首帧已报告过"不会再来，于是状态行被**永久**钉死在
 * "等待视频流"，同时视频正在播。药丸却是绿的（`onVideoSize` 单独改了它），
 * 于是出现"药丸说投屏中、状态行说等待视频流"的自相矛盾。
 *
 * ## 这里的职责边界
 *
 * 本类**只判定"现在处于哪个阶段"，不产出任何界面文案** ——
 * 文案与本地化留给 `:app-host`（`strings.xml`）。它因此可以零 Android 依赖地做 JVM 单测。
 *
 * ## 关键规则
 *
 * **进度事件不得降级已到达的阶段。** 这是修掉上面那个 bug 的唯一要点：
 * 画面已经在投，[onProgress] 里再怎么报"等待视频流"也只更新 [detail]（副行信息），
 * [phase] 保持 [Phase.MIRRORING]。
 *
 * ⚠️ 另一个方向的降级是**允许**的且必需的：[onRenegotiate] 可以把 `MIRRORING`
 * 降成 `RENEGOTIATING`（用户主动操作，不是乱序事件），[onClosed] 可以降成 `CONNECTING`，
 * [onLinkEstablished] 可以把 `ERROR` / `NO_SIGNAL` 拉回 `CONNECTING`（重连成功是**硬事实**，
 * 旧结论必须让位）。换句话说：**只有显式的用户动作、断链、以及"链路已建立"这类硬事实
 * 能改变阶段，随波逐流的进度事件不能。**
 */
class HostUiState {

    enum class Phase {
        /** 空闲：没有活动会话，等用户插线。 */
        IDLE,

        /** 连接中：从握手到首帧之前的全部阶段（CNXN / OPEN / 等 OKAY / 等首帧）。 */
        CONNECTING,

        /** 投屏中：首帧已输出，画面在播。 */
        MIRRORING,

        /** 重协商中：投屏过程中换了编码参数（切画质 / 显示区变化）。 */
        RENEGOTIATING,

        /** 回放中：读本地 `.uav` 文件，不走 USB 链路。 */
        REPLAY,

        /**
         * 对端没人应答：AOA 已切到配件模式，但对端在超时窗口内**一个字节都没回**。
         *
         * 与 [ERROR] 的区别很关键：USB 链路是通的、枚举也正常，只是**对端没有软件接管
         * 这条管道**。最典型的原因有三条，都指向同一个动作（去装/拉起配套 App）：
         *
         * 1. 没装 `com.uav.accessory`；
         * 2. 装了但被 `force-stop` 过 —— 系统此后不再派发配件 intent，只能手动打开；
         * 3. 权限被拒（首次插线时那句"是否打开该 App 处理 USB 配件"点了否）。
         *
         * 所以这个阶段必须和 [ERROR] 分开：ERROR 等自动重连，这里再等也没用。
         */
        NO_ACCESSORY_APP,

        /**
         * 手机端无输出（10.30）：链路**还连着**，但对方不再产帧。
         *
         * 与 [ERROR] 的区别很关键：ERROR 是"链路出了问题"，而这个是"链路好着、对面不动了"——
         * 用户的处置方式完全不同（前者等自动重连，后者通常要动手机端）。
         */
        NO_SIGNAL,

        /** 出错：链路错误且没有自动恢复中。 */
        ERROR,
    }

    /** 当前阶段。只读（只能通过下面的事件方法迁移）。 */
    var phase: Phase = Phase.IDLE
        private set

    /**
     * 最近一条细节信息（副行用）。
     *
     * 与 [phase] 分开的原因：进度文本**永远**要留给用户看（重协商成功后的新 `max_size`
     * 是有用的信息），但它**不该**再有资格决定主状态行显示什么。
     */
    var detail: String = ""
        private set

    /**
     * 是否正处于"对端用户主动停止"的状态（见 [onPeerStopped]）。
     *
     * 置位期间 [onProgress] 只丢弃，不更新 [detail] —— 这样宿主一边重连、
     * 一边还能把"为什么断的、怎么恢复"留在屏幕上。
     */
    var peerStopped: Boolean = false
        private set

    /**
     * 会话进度事件（对应 `HostSession.Listener.onStatus`）。
     *
     * ★ **绝不降级 [phase]** —— 已在 [Phase.MIRRORING] / [Phase.REPLAY] 时，
     *   这里只更新 [detail]，主状态行不动。这就是 10.23 修的那个 bug。
     *
     * @param text 进度文本（会成为 [detail]）
     */
    fun onProgress(text: String) {
        // 对端用户主动停止之后，原因要钉在副行上 —— 宿主紧接着就会自动重连，
        // 每秒一次的进度事件会把用户刚做的那个动作彻底刷掉（见 onPeerStopped）。
        if (peerStopped) return
        // 同理："对端没人应答"也要钉住。它是**结论**，而重连时的进度事件只是过程；
        // 让它被"CNXN 已发送…"刷掉，用户就永远不知道要去装 App，只觉得界面在转圈。
        if (phase == Phase.NO_ACCESSORY_APP) return
        detail = text
        // 只有"还没定下来"的阶段才会被进度事件推进；已到达的阶段保持不变。
        if (phase == Phase.IDLE || phase == Phase.CONNECTING) {
            phase = Phase.CONNECTING
        }
    }

    /** 首帧已输出 ⇒ 画面在播。这是**唯一**能宣告"投屏中"的事件。 */
    fun onFirstFrame() {
        peerStopped = false
        phase = Phase.MIRRORING
        detail = "首帧已输出"
    }

    /**
     * 用户主动重协商（切画质 / 显示区变化）。
     *
     * 允许把 [Phase.MIRRORING] 降级 —— 这是**显式用户动作**，不是乱序事件。
     *
     * ⚠️ 只在**本来就在投屏**时才升到 [Phase.RENEGOTIATING]：那个文案是
     * "投屏中 · 正在重协商…"，画面还没出来时说它是撒谎（那正是 10.23 要消灭的那类错误，
     * 只是方向相反）。首次连接期间的重协商保持 [Phase.CONNECTING]。
     */
    fun onRenegotiate() {
        if (phase == Phase.MIRRORING) phase = Phase.RENEGOTIATING
    }

    /**
     * 视频尺寸就绪（解码器 `configure` 成功）。
     *
     * 只用来把 [Phase.RENEGOTIATING] **收尾**成 [Phase.MIRRORING]（新参数已生效）；
     * 还没出首帧时**不能**提前报"投屏中" —— 那正是"药丸绿、状态行说等待"的另一种错法。
     */
    fun onVideoSize() {
        // 尺寸就绪说明链路已经重新接上 —— 用户停止留下的提示可以撤了。
        peerStopped = false
        if (phase == Phase.RENEGOTIATING) phase = Phase.MIRRORING
    }

    /**
     * 对端用户主动停止（`CLSE` 原因 = [CloseReason.USER_STOP]，10.32）。
     *
     * ★ 这个原因必须**粘住**（[peerStopped]），不能用普通 [onProgress] 处理：
     *   宿主收到 `CLSE` 之后会**立刻开始自动重连**（这是对的，手机端用户接下来大概率
     *   要点「授予录屏权限」，链路得随时能接上），于是每秒的进度事件几秒内就会把
     *   "用户停止投屏"刷成「CNXN 已发送…」。用户刚按了按钮，却只看到界面在转圈，
     *   会以为没生效、或者以为是故障。
     *
     * 语义上与 [onClosed] 的区别：
     * | | 普通断线 | 用户主动停止 |
     * |---|---|---|
     * | 主行 | 连接中 | 连接中（我们确实在重连） |
     * | 副行 | 随进度走 | **钉住**"手机端已停止投屏"，直到真的重新连上 |
     */
    fun onPeerStopped() {
        phase = Phase.CONNECTING
        peerStopped = true
        detail = "手机端已停止投屏 —— 在手机上点「授予录屏权限」即可恢复"
    }

    /**
     * 链路**已建立**（AOA 握手完成、会话对象已在跑，还没等 CNXN / 首帧）。
     *
     * ★ 这是**硬事实**，不是进度事件 —— 所以它有权把已经落到 [Phase.ERROR] /
     *   [Phase.NO_SIGNAL] 的主状态行拉回 [Phase.CONNECTING]。
     *
     * 为什么必须单独开一个事件：以前这里只发一条 `onProgress("链路已建立…")`，
     * 而 [onProgress] 按"进度事件不得降级"的规矩不动主行 ⇒ 自动重连明明成功了，
     * 主行却永远钉在「链路出错」，副行却在刷"链路已建立"（2026-10-20 真机：
     * 手机端息屏导致旧会话读超时 → 轮询 0.9 秒后重连成功、音频已恢复，
     * 界面仍显示出错）。**结论（ERROR）对了，但它已经不是当前结论了。**
     *
     * 反方向的克制同样重要：这里**只**处理 ERROR / NO_SIGNAL，
     * [Phase.MIRRORING] / [Phase.REPLAY] / [Phase.NO_ACCESSORY_APP] 一律不动 ——
     * 它们要么比"连接中"更靠后，要么是必须粘住的结论（见 [onPeerAppMissing]）。
     *
     * @param text 副行文案；空串表示"别动副行"（[peerStopped] 置位时也不覆盖，
     *   那条"手机端已停止投屏"要一直留到真的重新出画面）
     */
    fun onLinkEstablished(text: String = "") {
        if (phase == Phase.ERROR || phase == Phase.NO_SIGNAL) {
            phase = Phase.CONNECTING
        }
        if (text.isNotEmpty() && !peerStopped) detail = text
    }

    /** 链路断开（`onClosed`）⇒ 回到连接中（前台轮询会自动重试，不是终态）。 */
    fun onClosed(reason: String) {
        peerStopped = false
        phase = Phase.CONNECTING
        // 剥掉 `Connection` 层的日志措辞前缀，否则用户看到的是
        // "已断开：peer CLSE: 用户停止投屏" —— 既是术语又重复。
        detail = "已断开：${reason.removePrefix(PEER_CLSE_PREFIX)}"
    }

    /** 链路出错。终态：要么用户点重试，要么 `scanAndAttach` 把它带走。 */
    fun onError(message: String) {
        peerStopped = false
        phase = Phase.ERROR
        detail = "ERROR: $message"
    }

    /** USB 拔出 / 强制重握手前清场 ⇒ 空闲。 */
    fun onDetached(reason: String) {
        peerStopped = false
        phase = Phase.IDLE
        detail = reason
    }

    /** 本地回放。 */
    fun onReplay(text: String) {
        peerStopped = false
        phase = Phase.REPLAY
        detail = text
    }

    /**
     * 对端在配件模式下**没有任何应答**（见 [Phase.NO_ACCESSORY_APP]）。
     *
     * ⚠️ 已经出过画面就不降级：那时对端 App 明明在跑，这条只可能是重连过程中的瞬时现象，
     * 把「投屏中」改成「缺 App」反而是撒谎（与 [onProgress] 同一条规矩：**结论性的状态
     * 不能被过程性的事件冲掉**）。
     *
     * @param detail 副行说明（本地化文案由 `:app-host` 传入）
     */
    fun onPeerAppMissing(
        detail: String = "对端在配件模式下没有任何应答 —— 通常是没装配套 App"
    ) {
        if (phase == Phase.MIRRORING || phase == Phase.RENEGOTIATING || phase == Phase.REPLAY) return
        peerStopped = false
        phase = Phase.NO_ACCESSORY_APP
        this.detail = detail
    }

    /** 用户点了 Scan / Retry ⇒ 从任何阶段回到连接中。 */
    fun onScanRequested() {        if (phase == Phase.REPLAY) return
        // 用户主动点了扫描 ⇒ 他已经在推动恢复，那条"请去手机上点授权"的提示可以撤了。
        peerStopped = false
        phase = Phase.CONNECTING
        detail = "正在扫描配件手机…"
    }

    /**
     * 流健康度变化（10.30，判据见 [StreamHealth]）。
     *
     * 这是**真实的状态变化**（不是乱序进度事件），所以允许降级主状态行 —— 但只在
     * 本来就在投屏 / 重协商时才降：
     *
     * | 健康度 | 动作 |
     * |---|---|
     * | [StreamHealth.State.STALLED] | 只更新 [detail]（"画面安静 Ns"），**不动主行** |
     * | [StreamHealth.State.STOPPED] | [Phase.MIRRORING]/[Phase.RENEGOTIATING] → [Phase.NO_SIGNAL] |
     * | [StreamHealth.State.HEALTHY] | [Phase.NO_SIGNAL] → [Phase.MIRRORING]（自动恢复） |
     *
     * ⚠️ STALLED **刻意不降级**：等 IDR / 重建编码器都会短暂停帧，那时画面其实马上就回来，
     *   把主行改成"无输出"再改回去会造成闪烁 —— 这正是 10.26 那个"面板乱闪"的同款陷阱。
     */
    fun onStreamHealth(state: StreamHealth.State, silentMs: Long) {
        when (state) {
            StreamHealth.State.HEALTHY -> {
                if (phase == Phase.NO_SIGNAL) {
                    phase = Phase.MIRRORING
                    detail = "画面已恢复"
                }
            }

            StreamHealth.State.STALLED -> {
                // 只在已经投屏/无输出时提示；连接中（还没首帧）不掺和。
                if (phase == Phase.MIRRORING || phase == Phase.RENEGOTIATING ||
                    phase == Phase.NO_SIGNAL
                ) {
                    detail = "画面已 ${secs(silentMs)} 秒无新帧…"
                }
            }

            StreamHealth.State.STOPPED -> {
                if (phase == Phase.MIRRORING || phase == Phase.RENEGOTIATING) {
                    phase = Phase.NO_SIGNAL
                }
                if (phase == Phase.NO_SIGNAL) {
                    detail = "手机端已 ${secs(silentMs)} 秒没有输出画面，请检查手机上的「投屏发送」"
                }
            }
        }
    }

    /** 秒数显示下限取 1 —— "已 0 秒没输出"读起来像 bug（压阈值测 UI 时真出现过）。 */
    private fun secs(ms: Long): Long = (ms / 1000).coerceAtLeast(1)

    companion object {
        /**
         * `Connection` 收到对端 `CLSE(CLOSE_ALL)` 时加在原因前面的日志措辞。
         *
         * 这里做一次剥离纯属展示层容错：协议层那句话是给日志看的，
         * 直接透给用户会变成"已断开：peer CLSE: 用户停止投屏"。
         * 两边文案一旦不一致，最坏也只是多显示一个前缀，不影响状态判定。
         */
        private const val PEER_CLSE_PREFIX = "peer CLSE: "
    }
}
