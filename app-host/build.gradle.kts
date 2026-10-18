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

android {
    namespace = "com.uav.host"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.uav.host"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
}
