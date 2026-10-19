package com.uav.host.ios

/**
 * iOS 采集的功能开关（产品级，不是调试入口）。
 *
 * 与 [com.uav.host.common.DebugTools] 的区别：那边是"联调才开"的入口，
 * 这边是"某个环节在这台设备上走不通、先用别的策略"的取舍开关。
 */
internal object IosFeatures {

    /**
     * lockdown 会话里**等 TLS 握手**的时长（毫秒），到点就带着"半开"的连接继续投屏。
     *
     * ## 为什么这个窗口不能省成 0
     *
     * 设备要**看到我们发 `ClientHello`**、并且给足这段时间，才会开始推 QT：
     *
     * | 配置 | 实测结果 |
     * |---|---|
     * | 什么都不发（不升级 TLS） | 回 1 次 `PING` 后**永久沉默**（27s 零 CWPA/FEED） |
     * | 等 2s 就继续 | **0 字节**，连 PING 都没有 |
     * | 等 1s 就继续 | 正常：PING×2 → CWPA → FEED → 首帧 |
     *
     * ⇒ 握手**成功与否不重要**（iOS 27 上它根本不回 `ServerHello`），重要的是
     *   "发了 ClientHello 并且等一会儿"这个动作本身。1s 是实测能用的最小值。
     *
     * ## 代价
     *
     * `GetValue` 不会执行 ⇒ [MuxLink.trusted] 恒为 false。只影响控制面
     * （设备信息、唤醒/解锁、音量、事件注入，均未实现），
     * **配对与投屏都不依赖它**：配对全程在明文 lockdown 上（`GetValue(DevicePublicKey)` → `Pair`），
     * QT 音视频通道更是全程明文。
     */
    const val TLS_WAIT_MS = 1_000L
}
