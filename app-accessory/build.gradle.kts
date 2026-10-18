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

// ---- 应用版本 ----
// ⚠️ 只改根目录 gradle.properties 的 `usbscreen.versionName` / `usbscreen.versionCode`：
//    :app-host 与 :app-accessory 读的是同一处，避免两端版本号对不上。
val appVersionName = providers.gradleProperty("usbscreen.versionName").orNull
    ?: error("缺少 usbscreen.versionName —— 检查根目录 gradle.properties")
val appVersionCode = providers.gradleProperty("usbscreen.versionCode").orNull?.toIntOrNull()
    ?: error("缺少或非法的 usbscreen.versionCode —— 检查根目录 gradle.properties")

android {
    namespace = "com.uav.accessory"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // 必须与 :app-host 不同，两端是两个独立 APK（计划 0 选型）
        applicationId = "com.uav.accessory"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // 计划 5.2：抓流落盘（DumpByteChannel）靠 BuildConfig.DEBUG 门控，
        // 否则 release 包会在用户手机上持续写 28MB+ 的 .uav 文件。
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
            // ★ 与 release 共用同一张证书，理由见 :app-host 的同名字段。
            // 没配密钥时保持 AGP 默认的 debug.keystore。
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
