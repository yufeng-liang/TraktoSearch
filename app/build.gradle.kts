import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        versionCode = 57
        versionName = "3.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${properties.getProperty("trakt.client.id", "")}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${properties.getProperty("trakt.client.secret", "")}\"")
        buildConfigField("String", "TRAKT_REDIRECT_URI", "\"${properties.getProperty("trakt.redirect.uri", "tracktosearch://oauth/callback")}\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${properties.getProperty("tmdb.api.key", "")}\"")
        buildConfigField("String", "DOUBAN_API_KEY", "\"${properties.getProperty("douban.api.key", "")}\"")
        buildConfigField("String", "OMDB_API_KEY", "\"${properties.getProperty("omdb.api.key", "")}\"")
        buildConfigField("String", "GITEE_ACCESS_TOKEN", "\"${properties.getProperty("gitee.access.token", "")}\"")
        buildConfigField("String", "GITHUB_UPDATE_TOKEN", "\"${properties.getProperty("github.update.token", "")}\"")
        buildConfigField("String", "JPUSH_APPKEY", "\"${properties.getProperty("jpush.appkey", "")}\"")
        buildConfigField("String", "BAIDU_APP_ID", "\"${properties.getProperty("baidu.app.id", "")}\"")
        buildConfigField("String", "BAIDU_SECRET_KEY", "\"${properties.getProperty("baidu.secret.key", "")}\"")
        buildConfigField("String", "BAIDU_API_KEY", "\"${properties.getProperty("baidu.api.key", "")}\"")
        // 云端配置热更新:加密 key(与 app-config/.env 中 CONFIG_AES_KEY 一致)+ 配置服务 baseUrl
        buildConfigField("String", "CONFIG_AES_KEY", "\"${properties.getProperty("config.aes.key", "")}\"")
        buildConfigField("String", "CONFIG_BASE_URL", "\"${properties.getProperty("config.base.url", "https://app-config-1qe.pages.dev/")}\"")

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
        create("release") {
            storeFile = file(properties.getProperty("release.store.file", ""))
            storePassword = properties.getProperty("release.store.password", "")
            keyAlias = properties.getProperty("release.key.alias", "")
            keyPassword = properties.getProperty("release.key.password", "")
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
            signingConfig = signingConfigs.getByName("release")
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
    implementation(libs.haze.materials)

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
    // 厂商通道
    //implementation("cn.jiguang.sdk.plugin:xiaomi:5.8.0")
    //implementation("cn.jiguang.sdk.plugin:huawei:5.8.0")
    //implementation("cn.jiguang.sdk.plugin:oppo:5.8.0")
    //implementation("cn.jiguang.sdk.plugin:vivo:5.8.0")
}
