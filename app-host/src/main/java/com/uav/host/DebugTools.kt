package com.uav.host

/**
 * 宿主侧调试入口总开关（计划 5.2）。
 *
 * 把「能改变链路行为、能读本地文件、会持续刷日志」的入口集中到一处判断，
 * 避免散落各处后漏关一个。被门控的有三处：
 *
 *  1. **adb 驱动 extra**：`replay_path` / `scan` / `force_handshake` —— 见 [HostActivity.handleDebugIntent]
 *  2. **`.uav` 回放**：界面上的 Replay 按钮与 `FileByteChannel` 回放通道
 *  3. **周期 STATS 日志**：每 5 秒一条（[HostActivity]）
 *
 * [BuildConfig.DEBUG] 在 debug 构建下为 `true`、release 下为 `false`，
 * 因此被门控的分支在 release 包里**必然走不到**。
 *
 * ⚠️ **这里用 `val` 而不是 `const val`**：AGP 生成的 `BuildConfig.DEBUG` 是
 * `Boolean.parseBoolean("true")` 而不是字面量，Kotlin 不承认它是编译期常量
 * （写成 `const val` 会在编译期直接报 "Const 'val' initializer must be a constant value"）。
 * 运行时读取的语义完全相同；差别只在于：若要让 R8 把这些分支**整块从 release 包里删掉**
 * （而不只是"运行时走不到"），需要给 release 打开 `isMinifyEnabled` —— 当前未开。
 *
 * ⚠️ **为什么不直接删掉这些代码**：回放是联调定位故障的主要手段
 * （见 `docs/排错手册.md` 的「二分定位法」），debug 包必须保留它。
 * 用开关而不是硬编码 `if (false)`，是为了让同一份源码既能出**可诊断的 debug 包**、
 * 又能出**干净的 release 包**。
 */
internal object DebugTools {

    /**
     * 全部调试入口的总开关。
     *
     * debug = `true`（可回放、可 adb 驱动、带周期统计日志）；
     * release = `false`（上述入口全部失效）。
     */
    val ENABLED: Boolean = BuildConfig.DEBUG
}
