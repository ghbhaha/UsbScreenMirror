package com.uav.iosqt.ui

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
import com.uav.iosqt.R
import com.uav.iosqt.video.AspectRatioSurfaceView

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
    private val btnStart: Button = view(R.id.btn_start)
    private val btnPair: Button = view(R.id.btn_pair)
    private val btnRearm: Button = view(R.id.btn_rearm)
    private val btnCollapse: View = view(R.id.btn_collapse)

    @Volatile
    var surfaceReady = false
        private set

    var menuShown = true
        private set

    private val logLines = ArrayDeque<String>()

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
     * @param onSurface 参数为 true=Surface 就绪 / false=消失。建链前必须等它为 true。
     */
    fun bind(
        onSurface: (Boolean) -> Unit,
        onStart: () -> Unit,
        onStartLongClick: () -> Boolean,
        onPair: () -> Unit,
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

        btnStart.setOnClickListener { onStart() }
        btnStart.setOnLongClickListener { onStartLongClick() }
        btnPair.setOnClickListener { onPair() }
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

    /** 配对 / 唤醒进行中：三个按钮全禁用（同一时刻只能有一个线程碰 USB）。 */
    fun setBusy(busy: Boolean) = onUi {
        btnStart.isEnabled = !busy
        btnPair.isEnabled = !busy
        btnRearm.isEnabled = !busy
        btnStart.alpha = if (busy) 0.5f else 1f
        btnPair.alpha = if (busy) 0.5f else 1f
        btnRearm.alpha = if (busy) 0.5f else 1f
    }

    /**
     * 投屏进行中：主按钮变成「停止投屏」且**保持可点**，
     * 另外两个禁用（投屏期间动配置会直接把流打断）。
     */
    fun setMirroring(mirroring: Boolean) = onUi {
        btnStart.setText(if (mirroring) R.string.ios_btn_stop else R.string.ios_btn_start)
        btnStart.isEnabled = true
        btnStart.alpha = 1f
        btnPair.isEnabled = !mirroring
        btnRearm.isEnabled = !mirroring
        btnPair.alpha = if (mirroring) 0.5f else 1f
        btnRearm.alpha = if (mirroring) 0.5f else 1f
    }

    // ---------------------------------------------------------------- 日志

    /** 追加一行日志（自动滚到底，最多保留 [MAX_LINES] 行）。 */
    fun log(line: String) = onUi {
        logLines.addLast(line)
        while (logLines.size > MAX_LINES) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
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
    }

    fun currentSurface(): Surface? = if (surfaceReady) surfaceView.holder.surface else null

    private fun onUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else activity.runOnUiThread(block)
    }

    private inline fun <reified T : View> view(id: Int): T = activity.findViewById<T>(id)

    private companion object {
        const val ANIM_MS = 180L
        const val MAX_LINES = 400
        /** 与 layout 里 overlay 的宽度一致（动画位移用它兜底，此时还没测量出 width） */
        const val PANEL_DP = 300
    }
}
