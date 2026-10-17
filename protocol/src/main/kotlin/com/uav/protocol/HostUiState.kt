package com.uav.protocol

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
 * 降成 `RENEGOTIATING`（用户主动操作，不是乱序事件），[onClosed] 可以降成 `CONNECTING`。
 * 换句话说：**只有显式的用户动作 / 断链能降级，随波逐流的进度事件不能。**
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
     * 会话进度事件（对应 `HostSession.Listener.onStatus`）。
     *
     * ★ **绝不降级 [phase]** —— 已在 [Phase.MIRRORING] / [Phase.REPLAY] 时，
     *   这里只更新 [detail]，主状态行不动。这就是 10.23 修的那个 bug。
     *
     * @param text 进度文本（会成为 [detail]）
     */
    fun onProgress(text: String) {
        detail = text
        // 只有"还没定下来"的阶段才会被进度事件推进；已到达的阶段保持不变。
        if (phase == Phase.IDLE || phase == Phase.CONNECTING) {
            phase = Phase.CONNECTING
        }
    }

    /** 首帧已输出 ⇒ 画面在播。这是**唯一**能宣告"投屏中"的事件。 */
    fun onFirstFrame() {
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
        if (phase == Phase.RENEGOTIATING) phase = Phase.MIRRORING
    }

    /** 链路断开（`onClosed`）⇒ 回到连接中（前台轮询会自动重试，不是终态）。 */
    fun onClosed(reason: String) {
        phase = Phase.CONNECTING
        detail = "已断开：$reason"
    }

    /** 链路出错。终态：要么用户点重试，要么 `scanAndAttach` 把它带走。 */
    fun onError(message: String) {
        phase = Phase.ERROR
        detail = "ERROR: $message"
    }

    /** USB 拔出 / 强制重握手前清场 ⇒ 空闲。 */
    fun onDetached(reason: String) {
        phase = Phase.IDLE
        detail = reason
    }

    /** 本地回放。 */
    fun onReplay(text: String) {
        phase = Phase.REPLAY
        detail = text
    }

    /** 用户点了 Scan / Retry ⇒ 从任何阶段回到连接中。 */
    fun onScanRequested() {
        if (phase == Phase.REPLAY) return
        phase = Phase.CONNECTING
        detail = "正在扫描配件手机…"
    }
}