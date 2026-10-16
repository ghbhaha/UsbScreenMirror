plugins {
    alias(libs.plugins.kotlin.jvm)
}

// ★ 纯 Kotlin/JVM 库，零 Android 依赖 —— 这是「JVM 单测全绿」能真正验收的前提（计划 1.1 / A.2-7）
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
