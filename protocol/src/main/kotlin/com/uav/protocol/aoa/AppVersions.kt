package com.uav.protocol.aoa

/**
 * 两端 App 版本在**握手期**的协商（`CNXN.arg1` 承载本端 `versionCode`）。
 *
 * ## 为什么需要它
 *
 * 这个项目一直有「两端必须同版本」的约束，但**从来没有机制保证**：一个只升级了宿主的人，
 * 会看到画面正常、却在某个功能上静默不生效（典型是 `applyOptions` —— 像素模式靠它在
 * 握手后改编码参数，旧包上切换只会在宿主侧"看起来切了"）。这类问题排查成本极高，
 * 因为现象永远是"某个功能不好使"，而不是"版本不对"。
 *
 * 所以握手时双方各报一次 `versionCode`，对不上就**当场说清楚**。
 *
 * ## 为什么只提示、不拒绝
 *
 * 版本不同 ≠ 不能用：绝大多数差异只是少个新功能，投屏本身仍然工作。
 * 硬拒绝会让调试与回退变得非常难受（想用旧包复现老问题都做不到）。
 * 因此 [Compat] 只表达事实，**由调用方决定**提示还是拒绝 ——
 * 协议层不替产品做决定（同 `HostUiState` 的边界）。
 *
 * ## 为什么用 `arg1` 而不是塞进 payload
 *
 * `CNXN` 头里 `arg0/arg1` 是 AOA 自带的两个整数字段，`arg0` 已被协议版本占用，
 * `arg1` 一直空着。用它**完全不改变 payload 布局** —— 老包会直接忽略它，
 * 不会因为多出几个字节而把 `parsePayload` 弄坏（这条很关键：payload 一旦变长，
 * 老包的 `Bytes.intLE(payload, 0/4)` 虽然仍能读前 8 字节，但任何"按长度校验"的地方都会炸）。
 */
object AppVersions {

    /** 对端没上报版本 —— 老包（`arg1` 恒为 0）。 */
    const val UNKNOWN: Int = 0

    /** 两端 `versionCode` 的关系。 */
    enum class Compat {
        /** 相同，正常。 */
        SAME,

        /** 对端是没上报版本的旧包：功能可能缺失，但**无法确定**缺哪些。 */
        PEER_LEGACY,

        /** 明确不一致。 */
        DIFFERENT,
    }

    /**
     * 比较两端 `versionCode`。
     *
     * @param local 本端 `BuildConfig.VERSION_CODE`
     * @param peer  对端在 `CNXN.arg1` 里报的 `versionCode`（0 = 老包）
     */
    fun compat(local: Int, peer: Int): Compat = when {
        peer <= UNKNOWN -> Compat.PEER_LEGACY
        peer == local -> Compat.SAME
        else -> Compat.DIFFERENT
    }

    /** 是否值得在界面上提示（`SAME` 之外都要提）。 */
    fun needsWarning(compat: Compat): Boolean = compat != Compat.SAME
}
