// 顶层构建脚本：只声明插件版本，不 apply（各模块自行 apply）
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
