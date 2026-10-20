plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ---- release 签名 ----
// 密钥与口令放在**用户级** ~/.gradle/gradle.properties（`usbscreen.release.*`），绝不入库；
// 本文件只读属性、不含任何秘密。属性缺失（别人的机器 / CI）时 release 保持未签名，
// 产物仍是 `*-release-unsigned.apk` —— 与加这套配置之前的行为一致。
val ksPath = providers.gradleProperty("usbscreen.release.storeFile").orNull
val ksPass = providers.gradleProperty("usbscreen.release.storePassword").orNull
val ksAlias = providers.gradleProperty("usbscreen.release.keyAlias").orNull
val ksKeyPass = providers.gradleProperty("usbscreen.release.keyPassword").orNull
val hasReleaseKey = ksPath != null && ksPass != null && ksAlias != null &&
    ksKeyPass != null && file(ksPath).exists()

// ---- 平台（系统）签名 ----
// 只给"没有 SystemUI 的定制宿主"用（商显 / 一体机 / 广告机）：那种 ROM 里 USB 授权弹窗
// （`com.android.systemui.usb.UsbPermissionActivity`）不存在，普通包永远拿不到 USB 权限。
// 系统签名 + `android:sharedUserId="android.uid.system"`（见 src/system/AndroidManifest.xml）
// 让进程的 uid 变成 1000，而 AOSP 的判据是 `uid == Process.SYSTEM_UID` ⇒ 直接视为已授权。
// 属性缺失（别人的机器 / CI）时 system 变体保持**未签名**，不影响正常构建。
val platPath = providers.gradleProperty("usbscreen.platform.storeFile").orNull
val platPass = providers.gradleProperty("usbscreen.platform.storePassword").orNull
val platAlias = providers.gradleProperty("usbscreen.platform.keyAlias").orNull
val platKeyPass = providers.gradleProperty("usbscreen.platform.keyPassword").orNull
val hasPlatformKey = platPath != null && platPass != null && platAlias != null &&
    platKeyPass != null && file(platPath).exists()

// ---- 应用版本 ----
// ⚠️ 只改根目录 gradle.properties 的 `usbscreen.versionName` / `usbscreen.versionCode`：
//    :app-host 与 :app-accessory 读的是同一处，避免两端版本号对不上。
val appVersionName = providers.gradleProperty("usbscreen.versionName").orNull
    ?: error("缺少 usbscreen.versionName —— 检查根目录 gradle.properties")
val appVersionCode = providers.gradleProperty("usbscreen.versionCode").orNull?.toIntOrNull()
    ?: error("缺少或非法的 usbscreen.versionCode —— 检查根目录 gradle.properties")

android {
    namespace = "com.uav.host"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.uav.host"
        minSdk = 23
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // 计划 5.2：调试入口（回放 / adb extra / 周期统计日志）靠 BuildConfig.DEBUG 门控，
        // AGP 8.x 起 buildConfig 默认关闭，必须显式打开。
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
        if (hasPlatformKey) {
            create("platform") {
                storeFile = file(platPath!!)
                storePassword = platPass
                keyAlias = platAlias
                keyPassword = platKeyPass
            }
        }
    }

    buildTypes {
        release {
            // 没配密钥就不设签名：signingConfig = null 时 AGP 照旧产出 unsigned APK
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // ★ 与 release 共用同一张证书。
            // 两侧签名不同时，debug ↔ release 必须**先卸载**才能装，而卸载会连带清掉
            // filesDir 里的配对记录（iPad 上得重新点一次「信任」）。
            // 共用之后，两种包可以直接互相覆盖安装。
            // 没配密钥时（别人 clone、或没配 secret 的 CI）保持 AGP 默认的 debug.keystore。
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        /**
         * 系统签名包（只用于"没有 SystemUI 的定制宿主"）。
         *
         * 与 release 的唯一区别：
         * 1. 用**平台证书**签名（`usbscreen.platform.*`，仓库外）；
         * 2. `src/system/AndroidManifest.xml` 里带 `android:sharedUserId="android.uid.system"`
         *    ⇒ 进程 uid = 1000，`UsbSettingsManager.hasPermission()` 直接返回 true
         *    （AOSP：`if (uid == Process.SYSTEM_UID || mDisablePermissionDialogs) return true;`）。
         *
         * ⚠️ 装它必须**先卸载**现有包（uid 不同 ⇒ `INSTALL_FAILED_UID_CHANGED`），
         *    而卸载会清掉 filesDir（iOS 配对记录要重配）。
         * ⚠️ `sharedUserId` 自 API 29 起被标记弃用，但功能仍在；这个变体只面向老的定制宿主。
         */
        create("system") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("platform")
            isDebuggable = false
            // :protocol 只有 debug/release 两种，这里显式回落，否则解析依赖会报"没有匹配变体"
            matchingFallbacks += listOf("release")
        }
    }
}


kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.androidx.core.ktx)
    // 协程重构（P0 地基）：lifecycleScope + Dispatchers + ComponentActivity
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
}
