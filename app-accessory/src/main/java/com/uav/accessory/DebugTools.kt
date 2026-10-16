package com.uav.accessory

/**
 * 配件侧调试入口总开关（计划 5.2）。
 *
 * 这里门控的是**抓流落盘**（[com.uav.accessory.debug.DumpByteChannel]）。
 *
 * ⚠️ 这不是"代码整洁"问题而是**真实的产品缺陷**：抓流是**全量**写出的
 * （实测 110 秒 ≈ 28 MB），若不门控，release 包会在用户手机上持续写盘，
 * 长时投屏下既耗电又占空间，而且用户完全不知情。
 *
 * [BuildConfig.DEBUG] 在 debug 构建下为 `true`、release 下为 `false`，
 * 因此 release 包里 [com.uav.accessory.CaptureService] 会直接把 `FdByteChannel`
 * 交给 `Transport`，**根本不构造** [com.uav.accessory.debug.DumpByteChannel]。
 *
 * ⚠️ **这里用 `val` 而不是 `const val`**：AGP 生成的 `BuildConfig.DEBUG` 是
 * `Boolean.parseBoolean("true")` 而不是字面量，Kotlin 不承认它是编译期常量。
 * 运行时读取的语义完全相同；若要让 R8 把这些分支整块从 release 包里删掉，
 * 需要给 release 打开 `isMinifyEnabled`（当前未开）。
 */
internal object DebugTools {

    /**
     * 是否启用抓流落盘。
     *
     * debug = `true`（写出 `.uav` 供宿主离线回放）；
     * release = `false`（只转发，不落盘）。
     */
    val DUMP_ENABLED: Boolean = BuildConfig.DEBUG
}
