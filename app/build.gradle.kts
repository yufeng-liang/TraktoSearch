import java.util.Properties
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

val localProps = rootProject.file("local.properties")
val properties = Properties()
if (localProps.exists()) {
    properties.load(localProps.inputStream())
}

val releaseStoreFilePath = properties.getProperty("release.store.file", "").trim()
val releaseStorePassword = properties.getProperty("release.store.password", "")
val releaseKeyAlias = properties.getProperty("release.key.alias", "").trim()
val releaseKeyPassword = properties.getProperty("release.key.password", "")

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
        versionCode = 64
        versionName = "3.6.0"

        testInstrumentationRunner = "com.tracktosearch.CustomTestRunner"

        buildConfigField("String", "FEEDBACK_BASE_URL", "\"${properties.getProperty("feedback.base.url", "https://tracktosearch-gateway.pages.dev/gateway-api")}\"")
        // 云端配置热更新:加密 key(与 app-config/.env 中 CONFIG_AES_KEY 一致)+ 配置服务 baseUrl
        buildConfigField("String", "CONFIG_AES_KEY", "\"${properties.getProperty("config.aes.key", "")}\"")
        buildConfigField("String", "CONFIG_BASE_URL", "\"${properties.getProperty("config.base.url", "https://app-config-1qe.pages.dev/")}\"")
        // 授权网关根域名（固定，不可被远程配置替换）
        buildConfigField("String", "GATEWAY_BASE_URL", "\"${properties.getProperty("gateway.base.url", "https://tracktosearch-gateway.pages.dev/gateway-api")}\"")
        // 已迁移到 auth-worker Secrets 的密钥（不再编译进 APK）：
        // GITEE_ACCESS_TOKEN / GITHUB_UPDATE_TOKEN / BAIDU_APP_ID / BAIDU_SECRET_KEY / BAIDU_API_KEY
    }

    signingConfigs {
        getByName("debug") {
            // 使用默认 debug 签名
        }
        // 仅在配置了 release 签名信息时创建，避免空属性导致配置阶段报错
        if (releaseStoreFilePath.isNotBlank()) {
            create("release") {
                storeFile = file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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

abstract class VerifyReleaseSigningTask : DefaultTask() {
    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    @get:Internal
    abstract val localPropertiesFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val signingProperties = Properties()
        val propertiesFile = localPropertiesFile.get().asFile
        if (propertiesFile.isFile) {
            FileInputStream(propertiesFile).use { input ->
                signingProperties.load(input)
            }
        }

        val storeFilePath = signingProperties.getProperty("release.store.file", "").trim()
        val storePassword = signingProperties.getProperty("release.store.password", "")
        val keyAlias = signingProperties.getProperty("release.key.alias", "").trim()
        val keyPassword = signingProperties.getProperty("release.key.password", "")
        check(storeFilePath.isNotBlank()) {
            "Release signing is required. Configure release.store.file in local.properties."
        }
        check(storePassword.isNotBlank() && keyAlias.isNotBlank() && keyPassword.isNotBlank()) {
            "Release signing credentials are incomplete in local.properties."
        }

        val configuredStoreFile = File(storeFilePath)
        val keystoreFile = if (configuredStoreFile.isAbsolute) {
            configuredStoreFile
        } else {
            File(projectDirectory.get().asFile, storeFilePath)
        }
        check(keystoreFile.isFile) {
            "Release keystore does not exist: ${keystoreFile.absolutePath}"
        }

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        FileInputStream(keystoreFile).use { input ->
            keyStore.load(input, storePassword.toCharArray())
        }
        val certificate = keyStore.getCertificate(keyAlias)
            ?: error("Release certificate alias does not exist: $keyAlias")
        val actualFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { byte -> "%02x".format(byte) }
        check(actualFingerprint.equals("5ece267c52985b64bd23f40f5be6a8732e5689b10248fa7846e9e16f8cf5adb8", ignoreCase = true)) {
            "Unexpected release certificate fingerprint: $actualFingerprint"
        }
    }
}

val verifyReleaseSigning = tasks.register<VerifyReleaseSigningTask>("verifyReleaseSigning") {
    projectDirectory.set(layout.projectDirectory)
    localPropertiesFile.set(rootProject.layout.projectDirectory.file("local.properties"))
}

tasks.configureEach {
    if (name == "preReleaseBuild" || name == "assembleRelease" || name == "bundleRelease") {
        dependsOn(verifyReleaseSigning)
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
    implementation(libs.backdrop)

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

    // SQLCipher（Room 数据库加密）
    implementation(libs.sqlcipher)

    // 中文分词
    implementation(libs.jieba.analysis)

    // 中文转拼音（看单搜索拼音匹配）
    implementation(libs.pinyin4j)

    // See More 展开收起文本
    implementation(libs.seymour.text)

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
}

// -Xmx4g: 1195+ 测试用例（含 Robolectric）默认 512MB 堆内存不足，WatchlistViewModelTest 会 OOM。
tasks.withType<Test>().configureEach {
    maxHeapSize = "4g"
}
