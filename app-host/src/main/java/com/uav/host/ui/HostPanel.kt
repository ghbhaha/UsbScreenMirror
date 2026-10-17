package com.uav.host.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.uav.host.R
import com.uav.host.video.AspectRatioSurfaceView
import com.uav.protocol.HostUiState

/**
 * 宿主面板的**纯 View 层**（10.24 分层重构，从 [com.uav.host.HostActivity] 抽出）。
 *
 * ## 它只做一件事
 *
 * 把 [HostUiState] 的状态**画出来** —— 状态行、副行、状态药丸、菜单显隐、统计文本。
 * **不做任何决策**：不认识 `HostSession`、不碰 USB、不知道"重协商"是什么。
 *
 * ## 为什么值得单独抽
 *
 * 拆分前这些全是 Activity 里的字段（`statusText` / `chipDot` / `overlayView` / `menuShown` /
 * `statsTicker` / `setChip` / `setMenuVisible` / `renderUiState`，合计 400+ 行），
 * 会把"链路编排"淹没在"怎么画"里。现在：
 *
 * - 改动画 / 配色 / 布局 → 只动 [HostPanel]；
 * - 改链路策略（重协商、自愈、退避） → 只动 `LinkCoordinator`，**碰不到任何 View**。
 *
 * ## 线程约定
 *
 * 会话回调来自 worker 线程，所以所有公开方法都**自己判线程再转 UI 线程**
 * （调用点不必各自包一层 `runOnUiThread`）。这是拆分前就有的约定，抽出来时保留。
 */
class HostPanel(private val activity: Activity) {

    /**
     * 视频显示区。触摸转发要读它的 `videoWidth/Height`（映射到手机坐标的依据）。
     *
     * ★ **构造期就绑定，不放进 [bind]**（10.24 真机崩溃的根因）：`TouchForwarder` 与
     *   `setOnTouchListener` 都可能早于 `bind()` 触发，`lateinit` 那时还没赋值 ⇒
     *   `UninitializedPropertyAccessException` 直接崩在 `onCreate`。
     *   前提是**必须在 `setContentView` 之后构造 HostPanel**，否则 `findViewById` 拿不到。
     */
    val surfaceView: AspectRatioSurfaceView = activity.findViewById(R.id.surface)

    /** 面板整块（左侧竖栏）。菜单显隐动画作用在它身上。 */
    private lateinit var overlayView: View

    /** 根容器：视频显示区的"可用区"就是它（1:1 协商与"点空白处切菜单"都用它）。 */
    private lateinit var rootView: View

    private lateinit var statusText: TextView
    private lateinit var statusDetailText: TextView
    private lateinit var statsText: TextView
    private lateinit var chipView: View
    private lateinit var chipDot: View
    private lateinit var chipText: TextView

    /** 画质分段控件的两段（"1:1 像素" / "高清"）。 */
    private lateinit var segOneToOne: TextView
    private lateinit var segHighQuality: TextView

    /**
     * Surface 当前是否可用。
     *
     * ★ 这是**建链的前置条件**：没有 Surface 时握手也白搭（会话会等不到 Surface 而失败），
     *   而且每次握手都会让手机**重新枚举** USB —— 切后台时反复重枚举纯属浪费。
     */
    @Volatile
    var surfaceReady = false
        private set

    /** 菜单当前是否展开。 */
    var menuShown = true
        private set

    /**
     * 「首帧到达时自动收起一次」是否已经做过。
     *
     * 只做**一次**而不是每次首帧都收：重连后画面恢复同样会触发首帧，
     * 那时用户若正开着菜单看统计，被强行收掉会很恼人。
     */
    private var menuAutoCollapsed = false

    /**
     * 当前这次展开是**系统**叫回来的（报错 / 断链 / 拔出），不是用户自己点开的。
     *
     * 区别对待：系统展开是为了让用户看见告警，画面一恢复就该收回去；
     * 而用户手动展开（想看统计、想切画质）绝不能被首帧抢走。
     */
    private var autoRevealed = false

    /** 空白区触摸的按下点，用来判断"这是点击"还是"从空白区划出去"。 */
    private var menuDownX = 0f
    private var menuDownY = 0f

    /** 判定"点击"的最大位移平方（`ViewConfiguration` 的 touchSlop 平方，免开方）。 */
    private var menuSlopSq = 0f

    /**
     * 绑定视图并接好面板自身的交互。
     *
     * @param onTogglePixelMode 用户点了画质分段控件（参数 = 要切到 1:1 吗）
     * @param onScan / [onForceRehandshake] 面板上的操作按钮
     * @param onPickReplay 回放按钮（release 下按钮已隐藏，这里也不会被点到）
     * @param onDisplayAreaChanged 显示区尺寸变了（1:1 像素映射需要重新协商）
     */
    fun bind(
        onTogglePixelMode: (oneToOne: Boolean) -> Unit,
        onScan: () -> Unit,
        onForceRehandshake: () -> Unit,
        onPickReplay: () -> Unit,
        onDisplayAreaChanged: () -> Unit,
    ) {
        val r = activity
        statusText = r.findViewById(R.id.status)
        statusDetailText = r.findViewById(R.id.status_detail)
        statsText = r.findViewById(R.id.stats)
        overlayView = r.findViewById(R.id.overlay)
        chipView = r.findViewById(R.id.status_chip)
        chipDot = r.findViewById(R.id.chip_dot)
        chipText = r.findViewById(R.id.chip_state)
        segOneToOne = r.findViewById(R.id.btn_pixel_11)
        segHighQuality = r.findViewById(R.id.btn_pixel_hd)
        rootView = r.findViewById(R.id.root)

        setUpMenuToggle(onDisplayAreaChanged)

        findViewById<android.widget.Button>(R.id.btn_scan).setOnClickListener { onScan() }
        findViewById<android.widget.Button>(R.id.btn_force).setOnClickListener { onForceRehandshake() }
        findViewById<android.widget.Button>(R.id.btn_replay).setOnClickListener { onPickReplay() }

        // 常驻状态药丸：面板收起后仍能看到链路状态；点它 = 打开面板（收起后不会"无路可回"）
        findViewById<View>(R.id.status_chip).setOnClickListener { toggleMenu() }
        // 面板右上角：收起
        findViewById<View>(R.id.btn_collapse).setOnClickListener { hideMenu() }

        segOneToOne.setOnClickListener { onTogglePixelMode(true) }
        segHighQuality.setOnClickListener { onTogglePixelMode(false) }
    }

    private inline fun <reified T : View> findViewById(id: Int): T = activity.findViewById(id)

    // ------------------------------------------------------------------ Surface

    /**
     * 接上「点画面外的空白处显示 / 隐藏菜单」。
     *
     * ## 为什么"空白处"就是信箱边，且这里不会和触摸转发打架
     *
     * 触摸事件的派发是**自上而下命中**的：某个位置的触摸只有**没有任何子 View 消费**时
     * 才会落到根容器的 `OnTouchListener` 上。而这块屏幕上的分工恰好是干净的：
     *
     * | 区域 | 谁消费 | 效果 |
     * |---|---|---|
     * | 视频显示区 | `SurfaceView`（由 [TouchForwarder] 挂转发 listener） | 转发成手机上的触摸 |
     * | 面板自身 | 面板自己（见下） | 按钮可点；面板上的空白点击**不会**误关菜单 |
     * | 其余（信箱边） | 根容器 ← 本方法 | 切换菜单 |
     *
     * 信箱边**本来就不转发**（`TouchMapper` 会把显示区之外的触点判为忽略），
     * 所以拿它当开关不会牺牲任何已有功能。
     *
     * ⚠️ 已知边界：当视频宽高比与宿主屏幕**完全一致**时不存在信箱边，
     *    也就没有空白处可点 —— 但那种情况下画面本来就占满整屏、无需收起菜单。
     */
    private fun setUpMenuToggle(onDisplayAreaChanged: () -> Unit) {
        menuSlopSq = ViewConfiguration.get(activity).scaledTouchSlop.toFloat().let { it * it }

        // 面板自己吃掉触摸：否则点在面板的空白处会被根容器当成"点空白处"而把菜单关掉
        overlayView.setOnTouchListener { _, _ -> true }

        rootView.setOnTouchListener { _, event -> handleBlankAreaTouch(event) }

        // 1:1 像素映射（10.15）：宿主**自己**旋转/分屏时显示区会变，而 Activity 声明了
        // `configChanges=orientation|screenSize|screenLayout|density` ⇒ **不会重建**，
        // 会话里的 max_size 就会过期（典型症状：画面变糊，因为手机按旧尺寸编、宿主放大显示）。
        rootView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onDisplayAreaChanged() }

        setUpSystemBarInsets()
    }

    /**
     * 全屏后系统栏不再占位，但**刘海 / 挖孔 / 手势条仍然压在屏幕上**
     * —— 面板顶部会被挖孔挡住、底部那行提示会被手势条盖住。
     *
     * 所以要把 insets 折进面板的内边距和药丸的上外边距。
     *
     * ⚠️ 基准值必须在**首次**回调前记下来：insets 会随旋转 / 键盘 / 划出系统栏多次回调，
     *   每次都拿"当前 padding"当基准会一路累加，面板会越缩越窄。
     */
    private fun setUpSystemBarInsets() {
        val basePadding = intArrayOf(
            overlayView.paddingLeft, overlayView.paddingTop,
            overlayView.paddingRight, overlayView.paddingBottom,
        )
        val chipParams = chipView.layoutParams as? FrameLayout.LayoutParams
        val baseChipTop = chipParams?.topMargin ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            overlayView.setPadding(
                basePadding[0],
                basePadding[1] + bars.top,
                basePadding[2],
                basePadding[3] + bars.bottom,
            )
            chipParams?.let {
                val want = baseChipTop + bars.top
                if (it.topMargin != want) {
                    it.topMargin = want
                    chipView.layoutParams = it
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    /**
     * 空白区的触摸 → 切换菜单。
     *
     * 只在"按下与抬起基本落在同一处"时才算点击 —— 否则从空白区划出去也会误触发。
     * 一律返回 true：这块区域没有别的用途，吃掉事件可避免继续上抛。
     */
    private fun handleBlankAreaTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                menuDownX = event.x
                menuDownY = event.y
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.x - menuDownX
                val dy = event.y - menuDownY
                if (dx * dx + dy * dy <= menuSlopSq) toggleMenu()
            }
        }
        return true
    }

    fun toggleMenu() {
        autoRevealed = false
        setMenuVisible(!menuShown)
    }

    /**
     * **系统**要把菜单叫出来（报错 / 断链 / 拔出）。
     *
     * 与 [toggleMenu] 的区别只有一个：这样展开的菜单会在下一次首帧后自动收回去
     * （见 [autoCollapseOnFirstFrame]）。
     */
    fun revealMenu() {
        autoRevealed = true
        setMenuVisible(true)
    }

    /** 用户主动收起（面板右下角的收起按钮）。 */
    fun hideMenu() {
        autoRevealed = false
        setMenuVisible(false)
    }

    /** 当前 Surface（已失效时返回 null）。 */
    fun currentSurface(): Surface? = if (surfaceReady) surfaceView.holder.surface else null

    fun onSurfaceReady() {
        surfaceReady = true
    }

    fun onSurfaceGone() {
        surfaceReady = false
    }

    // ------------------------------------------------------------------ 菜单显隐

    /**
     * 显示 / 隐藏菜单（状态 + 统计 + 按钮那一整块）。
     *
     * 线程安全：链路/会话回调来自 worker 线程，所以这里自己判线程再转 UI 线程。
     *
     * ★ **幂等**：已经是目标状态就直接返回，**绝不重放动画**。
     *   踩过的坑：链路层在启动初期会连续发 `onClosed` / `onError`（握手失败、坏 magic、
     *   自动重试…），而调用点为了"让用户看得见"每次都调 `setMenuVisible(true)`。
     *   旧实现无条件把 `alpha` 从 0 拉回 1、`translationX` 从 -16dp 归零再 animate，
     *   于是**已经展开的菜单被反复重播淡入动画** —— 视觉上就是"进去之后面板一顿乱闪"。
     *   判据用 `menuShown` + `visibility` 一起看：这样"收起动画进行中又被叫回来"
     *   （`menuShown` 已 false 但 visibility 还是 VISIBLE）仍能正确重新展开。
     */
    fun setMenuVisible(visible: Boolean, animate: Boolean = true) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { setMenuVisible(visible, animate) }
            return
        }
        val settledVisibility = if (menuShown) View.VISIBLE else View.GONE
        if (menuShown == visible && overlayView.visibility == settledVisibility) {
            // 已经到位，直接返回：让正在跑的动画自己跑完，绝不重播
            return
        }
        menuShown = visible
        // 面板是**左侧竖栏**，所以从左边"滑入 / 滑回"一点距离（比纯淡入淡出更像一块抽屉）。
        // 位移取固定 16dp，不依赖测量宽度 —— 首次展开时 width 还是 0。
        val slidePx = 16f * activity.resources.displayMetrics.density

        if (visible) {
            overlayView.animate().cancel()
            overlayView.visibility = View.VISIBLE
            if (animate) {
                overlayView.alpha = 0f
                overlayView.translationX = -slidePx
                overlayView.animate().alpha(1f).translationX(0f).setDuration(MENU_ANIM_MS).start()
            } else {
                overlayView.alpha = 1f
                overlayView.translationX = 0f
            }
        } else if (animate) {
            overlayView.animate().cancel()
            overlayView.animate().alpha(0f).translationX(-slidePx).setDuration(MENU_ANIM_MS).withEndAction {
                // 收起动画期间可能又被叫回来（连着点两下），收尾时再确认一次
                if (!menuShown) {
                    overlayView.visibility = View.GONE
                    overlayView.translationX = 0f
                }
            }.start()
        } else {
            overlayView.animate().cancel()
            overlayView.visibility = View.GONE
            overlayView.translationX = 0f
        }
    }

    /**
     * 画面出来了就把菜单收起来，让视频干净地占满整屏。
     *
     * 两条规则：
     * 1. **启动后只自动收一次** —— 否则每次重连恢复都强行收，用户正开着菜单看统计会很恼人；
     * 2. **系统因报错/断链叫回来的那次，必须收**（[autoRevealed]），
     *    否则重连之后菜单会一直挂在画面上不动 —— 而它本来只是来报错的。
     *
     * 用户自己点开的菜单（`autoRevealed == false` 且已经收过一次）不碰。
     */
    fun autoCollapseOnFirstFrame() {
        if (menuAutoCollapsed && !autoRevealed) return
        menuAutoCollapsed = true
        autoRevealed = false
        setMenuVisible(false)
    }

    // ------------------------------------------------------------------ 状态渲染

    /** 常驻状态药丸的状态（决定圆点颜色）。 */
    private enum class ChipState { IDLE, CONNECTING, ACTIVE, ERROR }

    /**
     * 按 [HostUiState] 的当前阶段刷新**主状态行 + 副行 + 药丸**（10.23）。
     *
     * 主状态行只反映"现在处于哪个阶段"，进度文本（`max_size=…` 这类有用信息）落到副行。
     * 这样无论事件怎么乱序，**主状态行都不会说谎**。
     */
    fun render(state: HostUiState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { render(state) }
            return
        }
        val phase = state.phase
        statusText.text = activity.getString(
            when (phase) {
                HostUiState.Phase.IDLE -> R.string.host_status_idle
                HostUiState.Phase.CONNECTING -> R.string.host_status_connecting
                HostUiState.Phase.MIRRORING -> R.string.host_status_mirroring
                HostUiState.Phase.RENEGOTIATING -> R.string.host_status_renegotiating
                HostUiState.Phase.REPLAY -> R.string.host_status_replay
                HostUiState.Phase.ERROR -> R.string.host_status_error
            }
        )
        // 副行：进度文本。与主行同色系但更淡，避免和主状态抢注意力。
        statusDetailText.text = state.detail
        statusDetailText.visibility =
            if (state.detail.isBlank() || phase == HostUiState.Phase.ERROR) View.GONE else View.VISIBLE

        setChip(
            when (phase) {
                HostUiState.Phase.IDLE -> ChipState.IDLE
                HostUiState.Phase.CONNECTING -> ChipState.CONNECTING
                // 重协商时点保持绿：画面确实还在播（原地重协商不中断渲染）
                HostUiState.Phase.MIRRORING, HostUiState.Phase.RENEGOTIATING -> ChipState.ACTIVE
                HostUiState.Phase.REPLAY -> ChipState.ACTIVE
                HostUiState.Phase.ERROR -> ChipState.ERROR
            },
            activity.getString(
                when (phase) {
                    HostUiState.Phase.IDLE -> R.string.host_chip_idle
                    HostUiState.Phase.CONNECTING -> R.string.host_chip_connecting
                    HostUiState.Phase.MIRRORING -> R.string.host_chip_mirroring
                    HostUiState.Phase.RENEGOTIATING -> R.string.host_chip_renegotiating
                    HostUiState.Phase.REPLAY -> R.string.host_chip_replay
                    HostUiState.Phase.ERROR -> R.string.host_chip_error
                }
            ),
        )
    }

    private fun setChip(state: ChipState, text: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { setChip(state, text) }
            return
        }
        chipText.text = text
        val color = activity.getColor(
            when (state) {
                ChipState.IDLE -> R.color.host_idle
                ChipState.CONNECTING -> R.color.host_warn
                ChipState.ACTIVE -> R.color.host_ok
                ChipState.ERROR -> R.color.host_err
            }
        )
        chipDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    /** 把分段控件刷成"当前模式"：选中段用强调色实底，另一段留描边。 */
    fun applyPixelModeUi(oneToOne: Boolean) {
        val onBg = activity.getDrawable(R.drawable.bg_segment_on)
        val offBg = activity.getDrawable(R.drawable.bg_segment_off)
        segOneToOne.background = if (oneToOne) onBg else offBg
        segHighQuality.background = if (oneToOne) offBg else onBg
        segOneToOne.setTextColor(activity.getColor(if (oneToOne) R.color.host_text else R.color.host_text_dim))
        segHighQuality.setTextColor(activity.getColor(if (oneToOne) R.color.host_text_dim else R.color.host_text))
    }

    /** 根视图当前尺寸（= 视频显示区的可用区）。未布局时返回 null。 */
    fun displayArea(): IntArray? {
        if (!::rootView.isInitialized) return null
        val w = rootView.width
        val h = rootView.height
        return if (w > 0 && h > 0) intArrayOf(w, h) else null
    }

    fun setVideoSize(width: Int, height: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { setVideoSize(width, height) }
            return
        }
        surfaceView.setVideoSize(width, height)
    }

    /** 刷新链路统计文本。 */
    fun setStats(text: String) {
        statsText.text = text
    }

    /** 回放按钮是否应显示（release 下整块隐藏，见计划 5.2）。 */
    fun setReplayVisible(visible: Boolean) {
        findViewById<android.widget.Button>(R.id.btn_replay).visibility =
            if (visible) View.VISIBLE else View.GONE
    }

    /** 菜单动画时长。 */
    companion object {
        const val MENU_ANIM_MS = 160L
    }
}