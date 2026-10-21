package com.uav.host.ios.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.uav.host.aoa.ui.HostPanel
import com.uav.host.aoa.ui.AspectRatioSurfaceView
import com.uav.host.R

/**
 * iOS 采集端的**纯 View 层**（视觉与分层照 `app-host` 的 `HostPanel`）。
 *
 * ## 它只做一件事
 *
 * 把「当前处于哪个阶段」画出来 —— 状态药丸、主/副状态行、设备信息、日志、面板显隐。
 * **不做任何决策**：不碰 USB、不知道"配对"是什么流程、不认识任何协议/传输类。
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
    private val btnStop: Button = view(R.id.btn_stop)
    private val btnCollapse: View = view(R.id.btn_collapse)

    @Volatile
    var surfaceReady = false
        private set

    var menuShown = true
        private set

    /** 阶段 → 药丸文案 / 圆点颜色 / 主行文案。 */
    enum class Stage(val chip: Int, val dot: Int, val status: Int) {
        IDLE(R.string.host_chip_idle, R.color.host_idle, R.string.ios_status_idle),
        UNPAIRED(R.string.host_chip_idle, R.color.host_warn, R.string.ios_status_unpaired),
        PAIRING(R.string.ios_chip_pairing, R.color.host_warn, R.string.ios_status_pairing),
        CONNECTING(R.string.host_chip_connecting, R.color.host_warn, R.string.ios_status_connecting),
        MIRRORING(R.string.host_chip_mirroring, R.color.host_ok, R.string.host_status_mirroring),
        REPLAY(R.string.host_chip_replay, R.color.host_accent, R.string.ios_status_replay),
        ERROR(R.string.host_chip_error, R.color.host_err, R.string.host_status_error),
    }

    /**
     * 装配（`setContentView` 之后调用一次）。
     *
     * 界面已经没有独立的「开始 / 配对 / 重新唤醒」按钮了：插上就自动配对、自动投屏，拔掉就停。
     * 人工只剩底部一个双态按钮（停止 ⇄ 开始），见 [Stage] 与 [setMirroring]。
     *
     * 录制 / 离线回放**不再有界面入口**，改由 adb 驱动（见 `DebugTools.IOS_EXTRA_*`）：
     * 它们本来只在排障时用，放在界面上反而容易被误触、也会持续写盘。
     *
     * @param onSurface 参数为 true=Surface 就绪 / false=消失。建链前必须等它为 true。
     */
    fun bind(
        onSurface: (Boolean) -> Unit,
        onStop: () -> Unit,
        onStart: () -> Unit
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

        // ★ 这个按钮是**双态**的：
        //   投屏中 = 「停止投屏」→ [onStop]（只置停止信号，收尾在 capture 的 finally 里做）
        //   已停止 = 「开始投屏」→ [onStart]（重新走一遍自动流程，和"退出 App 再进"同一条路）
        //   状态由 [setMirroring] 维护，点击时按 [displaying] 分发 —— 两者都在 UI 线程，不存在竞态。
        btnStop.setOnClickListener {
            if (displaying) onStop() else onStart()
        }
        btnCollapse.setOnClickListener {
            Log.i(TAG, "点击来源=收起按钮")
            setMenuVisible(false)
        }
        chipView.setOnClickListener {
            Log.i(TAG, "点击来源=药丸")
            toggleMenu()
        }

        // 面板展开时才吃掉触摸（否则面板上的空白点击会被当成"点空白处收起面板"）。
        // ★ 必须跟着 menuShown 走：收起动画期间若仍无条件返回 true，那 180ms 里它会挡住药丸。
        // ★ 吃到触摸时顺手自愈：意图展开却不可见时，这一层会挡住所有触摸 —— 先把自己拉回可见。
        overlayView.setOnTouchListener { _, _ ->
            if (!menuShown) return@setOnTouchListener false
            healIfInvisible()
            true
        }

        // 点画面空白处：切换面板（展开 ⇄ 收起）。
        // ★ 但意图是"展开"而实际看不见时，这一下应该是"叫出来"而不是"再收起一次" ——
        //   否则药丸已藏、面板也看不见，用户就彻底没有入口了（实测连点 11~30 次全部空转）。
        rootView.setOnClickListener {
            Log.i(TAG, "点击来源=画面空白（menuShown=$menuShown）")
            if (!healIfInvisible()) toggleMenu()
        }

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

    /** 配对 / 启动投屏进行中：底部按钮禁用（同一时刻只能有一个流程碰 USB）。 */
    fun setBusy(busy: Boolean) = onUi {
        btnStop.isEnabled = !busy
        btnStop.alpha = if (busy) 0.5f else 1f
    }

    /**
     * 投屏进行中 ⇄ 已停止：决定底部那个按钮是「停止投屏」还是「开始投屏」。
     */
    fun setMirroring(value: Boolean) = onUi {
        displaying = value
        btnStop.isEnabled = true
        btnStop.alpha = 1f
    }

    /**
     * 是否正在投屏 —— 决定底部那个按钮是「停止投屏」还是「开始投屏」。
     *
     * ★ **只由 [setMirroring] 写**，且只在 UI 线程读写（点击回调也在 UI 线程）。
     * ★ 字段名刻意不叫 `mirroring`：Kotlin 会为它生成 `setMirroring(Boolean)`，
     *   与公开方法 [setMirroring] 的 JVM 签名撞车（Platform declaration clash）。
     */
    private var displaying = false
        set(value) {
            if (field == value) return
            field = value
            btnStop.setText(if (value) R.string.ios_btn_stop else R.string.ios_btn_start)
            btnStop.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (value) R.drawable.ic_close else R.drawable.ic_play, 0, 0, 0
            )
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
     * 展开 / 收起面板 —— **唯一入口**。
     *
     * ## 三条硬规则（2026-10-20 真机换来的，详见 `docs/排错手册.md` §3.16）
     *
     * 1. **显隐只认 `visibility`**。`alpha` / 位移只做装饰 —— 一旦用它们表达"显示"，
     *    动画被 `cancel()` 时属性就停在中间值，面板成了一张看不见却照吃触摸的墙
     *    （日志里 `vis=VISIBLE alpha=1.0 margin=0` 全对，屏幕上什么都没有）。
     * 2. **滑动用 `leftMargin`，不用 `translationX`**。这台 MTK 设备上 View 离开可视区后
     *    渲染节点会被合成器剔除，属性回位也不重绘（全宽位移 ⇒ 面板彻底消失）。
     * 3. **收尾不靠动画回调**。`withEndAction` 在动画被 `cancel()` 时**永不执行**，
     *    所以另挂 [snapToEnd] 守卫，到点无条件把终态钉死。
     */
    fun setMenuVisible(visible: Boolean, animate: Boolean = true) = onUi {
        Log.i(
            TAG,
            "面板${if (visible) "展开" else "收起"}（animate=$animate） 前状态 " +
                "vis=${overlayView.visibility} alpha=${overlayView.alpha} margin=${menuMargin()}"
        )
        menuShown = visible
        cancelAnimations()

        if (visible) {
            overlayView.visibility = View.VISIBLE
            // SurfaceView 是独立图层，View 侧也显式提到最上（双保险）
            overlayView.bringToFront()
            chipView.visibility = View.GONE
            if (animate) {
                overlayView.alpha = 0f
                slideMargin(from = -slidePx(), to = 0)
                overlayView.animate().alpha(1f).setDuration(ANIM_MS).start()
            } else {
                overlayView.alpha = 1f
                setMenuMargin(0)
            }
        } else {
            chipView.visibility = View.VISIBLE
            if (animate) {
                slideMargin(from = 0, to = -slidePx())
                overlayView.animate().alpha(0f).setDuration(ANIM_MS).start()
            }
        }

        // 守卫用 `ANIM_MS * 3` 而不是 `+ 80`：系统"动画时长缩放"可能 2~3x，
        // 太短的守卫会把动画腰斩成"滑一半突然没了"。
        overlayView.removeCallbacks(snapToEnd)
        overlayView.postDelayed(snapToEnd, ANIM_MS * 3)
        startWatchdog()
        syncFocusToMenu()
    }

    /** 停掉一切会改 `alpha` / `leftMargin` 的动画。 */
    private fun cancelAnimations() {
        overlayView.animate().cancel()
        slideAnimator?.cancel()
        // 位移一律走 leftMargin（见 setMenuVisible 规则 2），这里顺手把残留归零
        overlayView.translationX = 0f
    }

    // -- 布局位移（唯一被允许的滑动方式）-----------------------------------

    /** 布局位移动画（见 [slideMargin]）。只动 `leftMargin`，不碰 `translationX`。 */
    private var slideAnimator: ValueAnimator? = null

    /** 滑动幅度（px）。必须远小于面板宽度，保证动画期间面板始终有一部分在屏幕内。 */
    private fun slidePx(): Int =
        (SLIDE_DP * overlayView.resources.displayMetrics.density).toInt()

    /** 当前布局位移（`leftMargin`）。0 = 完全贴合左边、完整可见。 */
    private fun menuMargin(): Int =
        (overlayView.layoutParams as? FrameLayout.LayoutParams)?.leftMargin ?: 0

    private fun setMenuMargin(px: Int) {
        val lp = (overlayView.layoutParams as? FrameLayout.LayoutParams) ?: return
        if (lp.leftMargin == px) return
        lp.leftMargin = px
        overlayView.layoutParams = lp
    }

    /** 逐帧改 `leftMargin` 做滑动：真实布局位移，走 measure/layout，不会触发渲染节点剔除。 */
    private fun slideMargin(from: Int, to: Int) {
        slideAnimator?.cancel()
        setMenuMargin(from)
        slideAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = ANIM_MS
            addUpdateListener { setMenuMargin(it.animatedValue as Int) }
            start()
        }
    }

    // -- 落定 / 自愈 -----------------------------------------------------

    /**
     * 把面板钉到 [menuShown] 对应的终态。**幂等**。
     *
     * 顺序不能反：先停动画（它们每帧都在重写 `alpha` / `leftMargin`），再归零位移，
     * 最后按意图设可见性。`alpha` 即使在 `GONE` 时也复位成 1 —— 否则下次展开从"全透明"
     * 起步，动画一旦不跑就又是一张隐形墙。
     */
    private val snapToEnd = Runnable {
        cancelAnimations()
        setMenuMargin(0)
        overlayView.alpha = 1f
        overlayView.visibility = if (menuShown) View.VISIBLE else View.GONE
        Log.i(
            TAG,
            "面板落定：menuShown=$menuShown vis=${overlayView.visibility} " +
                "alpha=${overlayView.alpha} margin=${menuMargin()}"
        )
    }

    /** 落定 + 同步药丸与焦点。任何路径都可调，幂等。 */
    private fun snapMenu() {
        snapToEnd.run()
        if (menuShown) overlayView.bringToFront()
        chipView.visibility = if (menuShown) View.GONE else View.VISIBLE
        syncFocusToMenu()
    }

    /** 「逻辑上该显示」而且「真的看得见」。 */
    private fun visuallyShown(): Boolean =
        overlayView.visibility == View.VISIBLE &&
            overlayView.alpha > 0.5f &&
            menuMargin() == 0

    /**
     * 自愈：意图是展开、实际却看不见 ⇒ 立刻强制显示。
     *
     * 为什么必须有：`menuShown = true` 时药丸已经藏了，如果面板又不可见，用户就**没有任何入口**
     * （点画面只会"再收起一次"）。实测连点 11~30 次全部空转，日志却一直显示"已展开"。
     *
     * @return 这次调用是否做了自愈
     */
    private fun healIfInvisible(): Boolean {
        if (!menuShown || visuallyShown()) return false
        Log.w(
            TAG,
            "面板自愈：意图展开但不可见（vis=${overlayView.visibility} " +
                "alpha=${overlayView.alpha} margin=${menuMargin()}）⇒ 强制显示"
        )
        snapMenu()
        return true
    }

    /**
     * 显示后 [WATCH_MS] 校验一次"真的看得见"，不对就复位。
     *
     * 这个面板出现过两次"逻辑可见、肉眼不可见"（当时所有可查属性都正常），
     * 留一条看门狗日志，是为了下次能直接看到"落定之后到底是不是真的可见"。
     */
    private val watchdog = Runnable {
        if (!menuShown) return@Runnable
        if (visuallyShown()) {
            Log.i(TAG, "面板看门狗：可见")
            return@Runnable
        }
        Log.w(
            TAG,
            "面板看门狗：不可见（vis=${overlayView.visibility} alpha=${overlayView.alpha} " +
                "margin=${menuMargin()} isShown=${overlayView.isShown}）⇒ 复位"
        )
        snapMenu()
    }

    private fun startWatchdog() {
        overlayView.removeCallbacks(watchdog)
        overlayView.postDelayed(watchdog, WATCH_MS)
    }

    /**
     * 遥控器/D-pad：把焦点放到"当前唯一可见的入口"（面板展开→收起按钮，收起→药丸）。
     *
     * ⚠️ 必须显式做：机顶盒没有触摸屏，也没有鼠标 —— 一旦焦点落在 `GONE` 的控件上
     * （收起动画结束后就是这种情况），方向键就**完全无处可去**，用户以为遥控器坏了。
     */
    private fun syncFocusToMenu() {
        // 触摸设备：没有遥控器就没有"无处可去"的问题，抢焦点反而让控件莫名高亮
        if (!com.uav.host.common.FocusMode.wanted()) return
        val target = if (menuShown) btnCollapse else chipView
        if (target.isShown && !target.hasFocus() && !target.requestFocus()) {
            Log.w(TAG, "焦点请求失败（${if (menuShown) "面板" else "药丸"}）")
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
        /** logcat tag（与解码器那条链路一致，排障时一条命令就能捞全）。 */
        const val TAG = "UAV-IosQt"

        /** 面板滑入/滑出时长。 */
        const val ANIM_MS = 180L

        /** 显示后多久校验一次"真的看得见"（见 [watchdog]）。 */
        const val WATCH_MS = 700L

        /**
         * 位移动画幅度。
         *
         * ★ 必须远小于面板宽度（layout 里 300dp）：整块移出可视区会让合成器剔除渲染节点，
         *   之后属性回位也不重绘（见 [slideMargin]）。16dp 与 AOA 侧 `HostPanel` 一致。
         */
        const val SLIDE_DP = 16

        /**
         * 日志只保留最近这么多条。
         *
         * 投屏的握手日志集中在开头十几行，之后全是 FEED/NEED 噪音；
         * 50 条足够看清"走到哪一步、为什么失败"，再多只是让每次刷新更贵。
         */
        const val MAX_LINES = 50

        /** 日志刷新节流：多次 log 合并成一次 UI 刷新（最长延迟一帧多一点） */
        const val LOG_FLUSH_MS = 120L
    }
}