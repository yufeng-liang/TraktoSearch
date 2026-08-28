import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 不带版本号：AGP 已在 classpath 上（由 :app 的 com.android.application 引入），
    // 再声明版本会报 "plugin is already on the classpath with an unknown version"。
    // Kotlin 插件同样不需要单独声明 —— AGP 9 的 android 插件内置 Kotlin 编译支持，
    // :app 也没有 org.jetbrains.kotlin.android。
    id("com.android.test")
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.tracktosearch.benchmark"
    compileSdk = 37

    defaultConfig {
        // Macrobenchmark 要 API 29+：profileable、StartupTimingMetric 的完整分段
        // （bindApplication / activityStart / initialDisplay）都从 29 起才有。
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildTypes {
        // 与 :app 的 benchmark 构建类型配对。R8 保持开启、debuggable=false，
        // 测的是接近用户实际安装包的产物 —— 这是搭这个模块的首要目的：
        // 现有性能报告的全部数据来自 debug 构建，解释器占 51% 采样，瓶颈排序不可直接采信。
        create("benchmark") {
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // 用已连接的真机采集（本项目基准设备：Redmi shennong 1440×3200@120Hz）
    useConnectedDevices = true
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
