import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

val localProps = rootProject.file("local.properties")
val properties = Properties()
if (localProps.exists()) {
    properties.load(localProps.inputStream())
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    id("com.huawei.agconnect") apply false
}

android {
    namespace = "com.tracktosearch"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tracktosearch"
        minSdk = 26
        targetSdk = 37
        versionCode = 63
        versionName = "3.5.0"

        testInstrumentationRunner = "com.tracktosearch.CustomTestRunner"

        buildConfigField("String", "GITEE_ACCESS_TOKEN", "\"${properties.getProperty("gitee.access.token", "")}\"")
        buildConfigField("String", "GITHUB_UPDATE_TOKEN", "\"${properties.getProperty("github.update.token", "")}\"")
        buildConfigField("String", "FEEDBACK_BASE_URL", "\"${properties.getProperty("feedback.base.url", "https://tracktosearch-gateway.pages.dev/gateway-api")}\"")
        buildConfigField("String", "JPUSH_APPKEY", "\"${properties.getProperty("jpush.appkey", "")}\"")
        buildConfigField("String", "BAIDU_APP_ID", "\"${properties.getProperty("baidu.app.id", "")}\"")
        buildConfigField("String", "BAIDU_SECRET_KEY", "\"${properties.getProperty("baidu.secret.key", "")}\"")
        buildConfigField("String", "BAIDU_API_KEY", "\"${properties.getProperty("baidu.api.key", "")}\"")
        buildConfigField("String", "CRASH_LOG_API_URL", "\"${properties.getProperty("crash.log.api.url", "https://app-config-1qe.pages.dev/api/crash-logs")}\"")
        buildConfigField("String", "CRASH_LOG_API_TOKEN", "\"${properties.getProperty("crash.log.api.token", "")}\"")
        // 云端配置热更新:加密 key(与 app-config/.env 中 CONFIG_AES_KEY 一致)+ 配置服务 baseUrl
        buildConfigField("String", "CONFIG_AES_KEY", "\"${properties.getProperty("config.aes.key", "")}\"")
        buildConfigField("String", "CONFIG_BASE_URL", "\"${properties.getProperty("config.base.url", "https://app-config-1qe.pages.dev/")}\"")
        // 授权网关根域名（固定，不可被远程配置替换）
        buildConfigField("String", "GATEWAY_BASE_URL", "\"${properties.getProperty("gateway.base.url", "https://tracktosearch-gateway.pages.dev/gateway-api")}\"")

        val appId = applicationId ?: "com.tracktosearch"
        manifestPlaceholders["JPUSH_PKGNAME"] = appId
        manifestPlaceholders["JPUSH_APPKEY"] = properties.getProperty("jpush.appkey", "")
        manifestPlaceholders["JPUSH_CHANNEL"] = "developer-default"
        manifestPlaceholders["XIAOMI_APPID"] = properties.getProperty("xiaomi.app.id", "")
        manifestPlaceholders["XIAOMI_APPKEY"] = properties.getProperty("xiaomi.app.key", "")
        manifestPlaceholders["OPPO_APPKEY"] = properties.getProperty("oppo.app.key", "")
        manifestPlaceholders["OPPO_APPID"] = properties.getProperty("oppo.app.id", "")
        manifestPlaceholders["OPPO_APPSECRET"] = properties.getProperty("oppo.app.secret", "")
        manifestPlaceholders["VIVO_APPKEY"] = properties.getProperty("vivo.app.key", "")
        manifestPlaceholders["VIVO_APPID"] = properties.getProperty("vivo.app.id", "")
    }

    signingConfigs {
        getByName("debug") {
            // 使用默认 debug 签名
        }
        // 仅在配置了 release 签名信息时创建，避免空属性导致配置阶段报错
        val releaseStoreFile = properties.getProperty("release.store.file", "")
        if (releaseStoreFile.isNotBlank()) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = properties.getProperty("release.store.password", "")
                keyAlias = properties.getProperty("release.key.alias", "")
                keyPassword = properties.getProperty("release.key.password", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/LICENSE.md"
        resources.excludes += "/META-INF/LICENSE-notice.md"
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.errorprone.annotations)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Image
    implementation(libs.coil.compose)

    // Palette（海报主色调提取）
    implementation(libs.androidx.palette)

    // Lottie 动画
    implementation(libs.lottie.compose)

    // HTML Parsing
    implementation(libs.jsoup)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Haze (frosted glass blur)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.materials)

    // Markdown
    implementation(libs.richtext.commonmark)
    implementation(libs.richtext.ui.material)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler.work)

    // Glance (Widget)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.reorderable)
    implementation(libs.zoomable)

    // Baseline Profile
    implementation(libs.profileinstaller)

    // Security
    implementation(libs.security.crypto)

    // 极光推送
    implementation(libs.jpush)

    // 中文分词
    implementation(libs.jieba.analysis)

    // 单元测试
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.core.testing)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Compose UI 测试（Robolectric 组件测试用）
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Instrumented 测试（页面测试用）
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.truth)
    kspAndroidTest(libs.hilt.compiler)
    // 厂商通道
    //implementation("cn.jiguang.sdk.plugin:xiaomi:5.8.0")
    //implementation("cn.jiguang.sdk.plugin:huawei:5.8.0")
    //implementation("cn.jiguang.sdk.plugin:oppo:5.8.0")
    //implementation("cn.jiguang.sdk.plugin:vivo:5.8.0")
}

// JPush SDK 的 SchedulerReceiver 字节码缺少 stackmap frame,
// Robolectric 加载 merged manifest 时会触发 VerifyError。
// -Xverify:none 在 JDK 17 中 deprecated 但仍可用,跳过字节码校验让 Robolectric 测试能初始化。
// -Xmx4g: 1195+ 测试用例（含 Robolectric）默认 512MB 堆内存不足，WatchlistViewModelTest 会 OOM。
tasks.withType<Test>().configureEach {
    jvmArgs("-Xverify:none")
    maxHeapSize = "4g"
}
