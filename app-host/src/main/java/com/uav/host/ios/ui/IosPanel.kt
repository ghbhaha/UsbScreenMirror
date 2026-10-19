package com.uav.host.ios.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Looper
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.uav.host.R
import com.uav.host.ios.MuxLink
import com.uav.host.aoa.ui.HostPanel
import com.uav.host.aoa.video.AspectRatioSurfaceView

/**
 * iOS 采集端的**纯 View 层**（视觉与分层照 `app-host` 的 `HostPanel`）。
 *
 * ## 它只做一件事
 *
 * 把「当前处于哪个阶段」画出来 —— 状态药丸、主/副状态行、设备信息、日志、面板显隐。
 * **不做任何决策**：不认识 `MuxLink`、不碰 USB、不知道"配对"是什么流程。
 *
 * ## 主行 / 副行分离（照抄 app-host 10.23 的教训）
 *
 * 「进度事件」永远不能覆盖「已到达的阶段」：主行只说阶段，凡带"正在等你…"语义的一律进副行。
 * 否则一条迟到的进度回调就能把「投屏中」永久钉死成「等待视频流」。
 */
class IosPanel(private val activity: Activity) {

    /** 视频显示区。构造期就绑定 —— 必须在 `setContentView` 之后构造。 */
    val surfaceView: AspectRatioSurfaceView = view(R.id.surface)

    /**
     * 设置视频尺寸做信箱适配。
     *
     * ⚠️ 尺寸来自**解码器的输出回调（采集线程）**，而 `requestLayout()` 必须在 UI 线程，
     * 所以这里统一 post 一次 —— 调用点不必各自判线程。
     */
    fun setVideoSize(width: Int, height: Int) {
        surfaceView.post { surfaceView.setVideoSize(width, height) }
    }

    private val rootView: View = view(R.id.root)
    private val overlayView: View = view(R.id.overlay)
    private val statusText: TextView = view(R.id.status)
    private val statusDetail: TextView = view(R.id.status_detail)
    private val deviceInfo: TextView = view(R.id.device_info)
    private val chipView: View = view(R.id.status_chip)
    private val chipDot: View = view(R.id.chip_dot)
    private val chipText: TextView = view(R.id.chip_state)
    private val logView: TextView = view(R.id.log)
    private val logScroll: ScrollView = view(R.id.log_scroll)
    private val btnRearm: Button = view(R.id.btn_rearm)
    private val btnCollapse: View = view(R.id.btn_collapse)

    @Volatile
    var surfaceReady = false
        private set

    var menuShown = true
        private set

    /** 阶段 → 药丸文案 / 圆点颜色 / 主行文案。 */
    enum class Stage(val chip: Int, val dot: Int, val status: Int) {
        IDLE(R.string.ios_chip_idle, R.color.ios_idle, R.string.ios_status_idle),
        UNPAIRED(R.string.ios_chip_idle, R.color.ios_warn, R.string.ios_status_unpaired),
        PAIRING(R.string.ios_chip_pairing, R.color.ios_warn, R.string.ios_status_pairing),
        CONNECTING(R.string.ios_chip_connecting, R.color.ios_warn, R.string.ios_status_connecting),
        MIRRORING(R.string.ios_chip_mirroring, R.color.ios_ok, R.string.ios_status_mirroring),
        REARM(R.string.ios_chip_rearm, R.color.ios_warn, R.string.ios_status_rearm),
        REPLAY(R.string.ios_chip_replay, R.color.ios_accent, R.string.ios_status_replay),
        ERROR(R.string.ios_chip_error, R.color.ios_err, R.string.ios_status_error),
    }

    /**
     * 装配（`setContentView` 之后调用一次）。
     *
     * 界面已经没有「开始 / 配对」按钮了：插上就自动配对、自动投屏，拔掉就停。
     * 人工只剩一个兜底入口 [onRearm]（设备侧没武装时用）。
     *
     * 录制 / 离线回放**不再有界面入口**，改由 adb 驱动（见 `DebugTools.IOS_EXTRA_*`）：
     * 它们本来只在排障时用，放在界面上反而容易被误触、也会持续写盘。
     *
     * @param onSurface 参数为 true=Surface 就绪 / false=消失。建链前必须等它为 true。
     */
    fun bind(
        onSurface: (Boolean) -> Unit,
        onRearm: () -> Unit
    ) {
        surfaceView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                surfaceReady = true
                onSurface(true)
            }

            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                surfaceReady = false
                onSurface(false)
            }
        })

        btnRearm.setOnClickListener { onRearm() }
        btnCollapse.setOnClickListener { setMenuVisible(false) }
        chipView.setOnClickListener { toggleMenu() }

        // 面板展开时才吃掉触摸（否则面板上的空白点击会被当成"点空白处收起面板"）。
        // ★ 必须跟着 menuShown 走：收起动画期间若仍无条件返回 true，那 180ms 里它会挡住 chip。
        overlayView.setOnTouchListener { _, _ -> menuShown }

        // 点画面空白处切换面板（视频区在 root 之下，事件会先到 root 的 listener 吗？
        // 不会 —— SurfaceView 不消费触摸，所以这里的 listener 能收到）
        rootView.setOnClickListener { toggleMenu() }

        setMenuVisible(true, animate = false)
        render(Stage.IDLE, null)
        hideSystemBars()

        // 遥控器：给所有可聚焦控件挂焦点环（API 25 没有系统自带的高亮，见 FocusRing 说明）
        com.uav.host.common.FocusRing.install(rootView)

        // ★ 日志区绝不能吃焦点：`ScrollView` 在**构造期**自己 `setFocusable(true)`，
        //   XML 里的 `focusable="false"` 会被它覆盖；而且我们每 120ms `fullScroll()` 一次，
        //   它会顺势把焦点抢过来 —— 表现就是"遥控器按了半天，焦点永远停在日志上"。
        logScroll.isFocusable = false
        logScroll.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS

        // ★ 日志「插入即贴底」的真正收口在这里。
        //   `logView.text = …` 之后立刻 fullScroll 是滚不动的（滚动范围还没随布局更新），
        //   表现为"新日志被切在底部看不见"。内容高度一变就滚 —— 这时范围才是对的。
        //   只在布局真变化时触发，所以用户往上翻看历史不会被反复打断。
        logView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** 隐藏系统栏（沉浸式）。投屏工具的默认姿态。 */
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------------------------------------------------------------- 状态绘制

    /**
     * 推进阶段。
     *
     * @param detail 副行（进度/原因）。null = 收起副行。
     */
    fun render(stage: Stage, detail: String?) {
        onUi {
            chipText.setText(stage.chip)
            chipDot.backgroundTintList = ColorStateList.valueOf(
                activity.getColor(stage.dot)
            )
            statusText.setText(stage.status)
            if (detail.isNullOrBlank()) {
                statusDetail.visibility = View.GONE
            } else {
                statusDetail.visibility = View.VISIBLE
                statusDetail.text = detail
            }
        }
    }

    /** 设备信息卡片（几行等宽文本）。 */
    fun setDeviceInfo(text: String) = onUi {
        deviceInfo.text = text
    }

    /** 配对 / 唤醒进行中：唯一的兜底按钮也禁用（同一时刻只能有一个线程碰 USB）。 */
    fun setBusy(busy: Boolean) = onUi {
        btnRearm.isEnabled = !busy
        btnRearm.alpha = if (busy) 0.5f else 1f
    }

    /**
     * 投屏进行中：**重新唤醒要禁用** —— 它会在收流中途动 USB 配置，直接把流打断。
     *
     * 注意界面已经没有「停止」按钮了：停止的方式是拔线（拔掉即停，见 QtSession 的断开判定）。
     */
    fun setMirroring(mirroring: Boolean) = onUi {
        btnRearm.isEnabled = !mirroring
        btnRearm.alpha = if (mirroring) 0.5f else 1f
    }

    // ---------------------------------------------------------------- 日志

    /**
     * 日志环形缓冲（**只保留最近 [MAX_LINES] 条**）。
     *
     * ⚠️ 只允许 UI 线程访问 —— 跨线程读 `ArrayDeque` 会读到 null 槽位（本项目已踩过 NPE）。
     */
    private val logLines = ArrayDeque<String>()

    /** 待刷入的日志，任何线程都可写（用 [logLock] 保护） */
    private val logQueue = ArrayDeque<String>()
    private val logLock = Any()
    private var flushScheduled = false

    /**
     * 追加一行日志。
     *
     * ## 为什么不是直接改 TextView
     *
     * 这条路径曾经把界面彻底卡死：收流线程在设备被拔后进入忙循环，每毫秒调一次，
     * 而每次调用都在 UI 线程做「拼接上百行文本 + fullScroll」。几秒内消息队列被淹，
     * 界面就再也不响应了。
     *
     * 现在改成：**调用方只入队**（代价是一次加锁），UI 线程每 [LOG_FLUSH_MS] 最多刷一次。
     * 这样无论上游多高频，主线程的日志开销都是恒定的 —— 这是"防淹"的结构性保证，
     * 而不是寄希望于上游不刷屏。
     */
    fun log(line: String) {
        val needSchedule = synchronized(logLock) {
            logQueue.addLast(line)
            // 积压超过上限就丢最旧的：反正最终也只显示 MAX_LINES 条
            while (logQueue.size > MAX_LINES) logQueue.removeFirst()
            if (flushScheduled) {
                false
            } else {
                flushScheduled = true
                true
            }
        }
        if (!needSchedule) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            flushLog()
        } else {
            logView.postDelayed({ flushLog() }, LOG_FLUSH_MS)
        }
    }

    /** UI 线程执行：把积压的日志一次搬进环形缓冲并刷新一次文本。 */
    private fun flushLog() {
        var batch: List<String>? = null
        synchronized(logLock) {
            flushScheduled = false
            if (logQueue.isNotEmpty()) {
                batch = logQueue.toList()
                logQueue.clear()
            }
        }
        val lines = batch ?: return
        for (l in lines) {
            logLines.addLast(l)
            while (logLines.size > MAX_LINES) logLines.removeFirst()
        }
        logView.text = logLines.joinToString("\n")
        // ★ 只在这里调 fullScroll 是不够的：text 刚换掉时 ScrollView 的滚动范围
        //   还是上一版的高度，滚过去会停在"旧底部"，新日志被切掉一截 ——
        //   看起来就像"没有自动跟到底"。真正的收口在 layout 监听里（见 bind）。
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- 面板显隐

    fun toggleMenu() = setMenuVisible(!menuShown)

    /**
     * 展开 / 收起面板。
     *
     * ## 这里曾经有个"隐藏后永远打不开"的真 bug
     *
     * 旧实现只做 `animate().alpha(0).translationX(-w)`，靠 `withEndAction` 收尾设 `GONE`。
     * 一旦动画被下一次 `cancel()` 打断（快速连点、或首帧自动收起撞上用户操作），
     * `withEndAction` 就不执行 —— 面板变成**看得见 0、占位 450px、还吃掉所有触摸**的隐形墙，
     * 左上角药丸永远点不到。
     *
     * 修法两条同时上：① 触摸拦截按 [menuShown] 判断；② 收起时把面板显式 `GONE`，
     * 并把药丸露出来 —— 药丸是唯一的"叫回面板"入口，绝不能被挡住。
     */
    fun setMenuVisible(visible: Boolean, animate: Boolean = true) = onUi {
        menuShown = visible
        android.util.Log.i("UAV-IosQt", "面板 ${if (visible) "展开" else "收起"}（animate=$animate）")
        val slide = overlayView.width.takeIf { it > 0 }
            ?: (PANEL_DP * overlayView.resources.displayMetrics.density).toInt()

        overlayView.animate().cancel()
        // 药丸与面板在左上角重叠：展开时藏药丸（不挡标题），收起时露药丸（当唯一入口）
        chipView.visibility = if (visible) View.GONE else View.VISIBLE

        if (!visible && !animate) {
            overlayView.alpha = 0f
            overlayView.translationX = -slide.toFloat()
            overlayView.visibility = View.GONE
            return@onUi
        }
        overlayView.visibility = View.VISIBLE
        if (visible) {
            if (animate) {
                overlayView.alpha = 0f
                overlayView.translationX = -slide.toFloat()
                overlayView.animate().alpha(1f).translationX(0f)
                    .setDuration(ANIM_MS)
                    .withEndAction { settleMenu() }
                    .start()
            } else {
                overlayView.alpha = 1f
                overlayView.translationX = 0f
                settleMenu()
            }
        } else {
            overlayView.animate().alpha(0f).translationX(-slide.toFloat())
                .setDuration(ANIM_MS)
                .withEndAction { settleMenu() }
                .start()
            // 动画可能被 cancel（withEndAction 就不会跑）—— 兜底再校一次
            overlayView.postDelayed({ settleMenu() }, ANIM_MS + 80)
        }
    }

    /** 按 [menuShown] 把面板的可见性/可点击性对齐，幂等。 */
    private fun settleMenu() {
        if (menuShown) {
            overlayView.visibility = View.VISIBLE
            overlayView.alpha = 1f
            overlayView.translationX = 0f
        } else {
            overlayView.visibility = View.GONE
        }
        chipView.visibility = if (menuShown) View.GONE else View.VISIBLE
        syncFocusToMenu()
    }

    /**
     * 遥控器/D-pad：把焦点放到"当前唯一可见的入口"（面板展开→收起按钮，收起→药丸）。
     *
     * ⚠️ 必须显式做：机顶盒没有触摸屏，也没有鼠标 —— 一旦焦点落在 `GONE` 的控件上
     * （收起动画结束后就是这种情况），方向键就**完全无处可去**，用户以为遥控器坏了。
     */
    private fun syncFocusToMenu() {
        val target = if (menuShown) btnCollapse else chipView
        if (target.isShown && !target.hasFocus() && !target.requestFocus()) {
            android.util.Log.w("UAV-IosQt", "焦点请求失败（${if (menuShown) "面板" else "药丸"}）")
        }
    }

    /**
     * 界面起来时先给一个焦点（否则方向键第一次按下去什么都不发生）。
     *
     * ⚠️ 用 `post`：`onCreate` 里 View 还没 attach，`isShown` 是 false，`requestFocus()`
     * 会被静默丢掉 —— 必须等这一帧布局完再请求。
     */
    fun focusFirst() = onUi { chipView.post { syncFocusToMenu() } }

    fun currentSurface(): Surface? = if (surfaceReady) surfaceView.holder.surface else null

    private fun onUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else activity.runOnUiThread(block)
    }

    private inline fun <reified T : View> view(id: Int): T = activity.findViewById<T>(id)

    private companion object {
        const val ANIM_MS = 180L

        /**
         * 日志只保留最近这么多条。
         *
         * 投屏的握手日志集中在开头十几行，之后全是 FEED/NEED 噪音；
         * 50 条足够看清"走到哪一步、为什么失败"，再多只是让每次刷新更贵。
         */
        const val MAX_LINES = 50

        /** 日志刷新节流：多次 log 合并成一次 UI 刷新（最长延迟一帧多一点） */
        const val LOG_FLUSH_MS = 120L

        /** 与 layout 里 overlay 的宽度一致（动画位移用它兜底，此时还没测量出 width） */
        const val PANEL_DP = 300
    }
}
