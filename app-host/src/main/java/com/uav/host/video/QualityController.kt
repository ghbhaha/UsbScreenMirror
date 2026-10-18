package com.uav.host.video

import android.content.Context
import com.uav.host.HostPrefs
import com.uav.host.session.HostSession
import com.uav.protocol.Logger
import com.uav.protocol.i
import com.uav.protocol.media.Scale
import kotlin.math.abs

/**
 * **画质策略层**（10.24 分层重构，从 [com.uav.host.HostActivity] 抽出）。
 *
 * ## 职责边界
 *
 * 只管"**该编多大**"这一件事：
 * - 像素模式（1:1 ↔ 高清）的读取、切换、持久化；
 * - 显示区变了要不要重协商、怎么算新的 `max_size`。
 *
 * **不碰 View、不碰 USB** —— 切换模式时它只算出一个决定，再通过 [Listener] 让上层执行。
 *
 * ## 为什么单独一层
 *
 * 这段逻辑有两条**互不相干**的触发路径（用户点分段控件 / 系统旋转与分屏），
 * 却共享同一份"是否该重协商"的判断。混在 Activity 里时，改一处很容易漏另一处。
 */
class QualityController(
    private val context: Context,
    private val logger: Logger,
    /** 取当前活动会话（重协商需要它；没会话就退化成"重连"）。 */
    private val sessionProvider: () -> HostSession?,
    /** 取宿主视频显示区的可用尺寸（`null` = 还没布局）。 */
    private val displayArea: () -> IntArray?,
    private val listener: Listener,
) {

    interface Listener {
        /**
         * 需要切换画质并让新参数生效。
         *
         * @param oneToOne 目标模式
         * @param reason 人类可读的触发原因（会显示在副行）
         */
        fun onQualityChange(oneToOne: Boolean, reason: String)
    }

    /**
     * 像素模式：`true` = 1:1 像素映射（省算力），`false` = 高清旧口径（宿主端缩放更锐）。
     *
     * 决定 `OPEN` 协商的 `max_size`。初值从 [HostPrefs] 读，切换后写回。
     */
    var oneToOne: Boolean = HostPrefs.oneToOne(context)
        private set

    /**
     * 用户点了分段控件。
     *
     * ⚠️ **已经是该模式就直接返回** —— 点"已选中"的那一段必须是无副作用的 no-op，
     * 否则手滑点一下就会白白触发一次重协商（画面闪一下）。
     */
    fun setPixelMode(enabled: Boolean) {
        if (oneToOne == enabled) return
        oneToOne = enabled
        HostPrefs.setOneToOne(context, oneToOne)
        logger.i(TAG, "像素模式切换 → ${if (enabled) "1:1（按显示区编码）" else "高清（按面板长边编码）"}")
        listener.onQualityChange(
            enabled,
            if (enabled) "已切到 1:1 像素模式（按显示区编码）" else "已切到高清模式（按面板长边编码）",
        )
    }

    /**
     * 显示区变了 → 该编的尺寸也变 → 需要重协商。
     *
     * 由 [HostPanel] 的 `addOnLayoutChangeListener` 驱动。
     *
     * 宿主 Activity 声明了 `configChanges=orientation|screenSize|screenLayout|density`
     * ⇒ **旋转/分屏不会重建 Activity**，会话里的 `max_size` 会过期
     *（典型症状：画面变糊，因为手机按旧尺寸编、宿主放大显示）。
     *
     * @return 是否真的决定要重协商（已在这里完成判断与上报）
     */
    fun onDisplayAreaChanged(): Boolean {
        // 高清模式（1:1 关）下尺寸不依赖显示区，显示区变化无需重建
        if (!oneToOne) return false
        val s = sessionProvider() ?: return false
        val area = displayArea() ?: return false
        val pw = s.peerWidth
        val ph = s.peerHeight
        if (pw <= 0 || ph <= 0) return false

        val want = Scale.oneToOneMaxSize(pw, ph, area[0], area[1])
        val have = s.negotiatedMaxSize
        if (have <= 0 || want <= 0) return false

        val diff = abs(want - have).toDouble() / have
        // 10% 的阈值是为了躲开布局抖动与对齐取整带来的 1~2px 噪声 ——
        // 真正的横竖屏切换会让目标尺寸变化 40% 以上（1024 -> 1920）。
        if (diff <= AREA_RENEGOTIATE_RATIO) return false

        logger.i(
            TAG,
            "宿主显示区 ${area[0]}x${area[1]} 变化 -> 1:1 目标 max_size $have -> $want" +
                "（差 ${(diff * 100).toInt()}%），重协商",
        )
        listener.onQualityChange(oneToOne, "显示区变了（${area[0]}x${area[1]}），按新尺寸重协商…")
        return true
    }

    companion object {
        const val TAG = "UAV-Host"

        /** 宿主显示区变化多少才值得重建会话（1:1 像素映射，10.15）。 */
        const val AREA_RENEGOTIATE_RATIO = 0.10
    }
}