plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.uav.accessory"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // 必须与 :app-host 不同，两端是两个独立 APK（计划 0 选型）
        applicationId = "com.uav.accessory"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
