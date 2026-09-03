import java.util.Properties
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.VersionCatalogsExtension
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
    alias(libs.plugins.androidx.baselineprofile)
    id("com.huawei.agconnect") apply false
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val openSourceVersionCatalog = versionCatalog.versionAliases
    .sorted()
    .joinToString("\n") { alias ->
        val constraint = versionCatalog.findVersion(alias).orElseThrow()
        val resolvedVersion = constraint.requiredVersion
            .ifBlank { constraint.strictVersion }
            .ifBlank { constraint.preferredVersion }
        check(resolvedVersion.isNotBlank()) {
            "Version catalog alias '$alias' does not declare a concrete version"
        }
        "$alias=$resolvedVersion"
    }
val openSourceVersionCatalogBase64 = Base64.getEncoder()
    .encodeToString(openSourceVersionCatalog.toByteArray(Charsets.UTF_8))

android {
    namespace = "com.tracktosearch"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tracktosearch"
        minSdk = 26
        targetSdk = 37
        versionCode = 64
        versionName = "3.6.0"

        // sherpa-onnx 全 ABI 太重：只保留 arm64-v8a（覆盖全部主流真机），
        // 模拟器（x86_64）与老 32 位设备不再可装
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "com.tracktosearch.CustomTestRunner"

        buildConfigField("String", "FEEDBACK_BASE_URL", "\"${properties.getProperty("feedback.base.url", "https://tracktosearch-gateway.pages.dev/gateway-api")}\"")
        // 云端配置热更新:加密 key(与 app-config/.env 中 CONFIG_AES_KEY 一致)+ 配置服务 baseUrl
        buildConfigField("String", "CONFIG_AES_KEY", "\"${properties.getProperty("config.aes.key", "")}\"")
        buildConfigField("String", "CONFIG_BASE_URL", "\"${properties.getProperty("config.base.url", "https://app-config-1qe.pages.dev/")}\"")
        // 授权网关根域名（固定，不可被远程配置替换）
        buildConfigField("String", "GATEWAY_BASE_URL", "\"${properties.getProperty("gateway.base.url", "https://tracktosearch-gateway.pages.dev/gateway-api")}\"")
        // 开源相关页的版本号由 Version Catalog 构建期快照提供；升级 libs.versions.toml 后页面自动同步。
        buildConfigField(
            "String",
            "OPEN_SOURCE_VERSION_CATALOG_BASE64",
            "\"$openSourceVersionCatalogBase64\""
        )
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
        // Macrobenchmark 与 baseline profile 采集专用。继承 release 的 R8/资源压缩配置，
        // 测的才是用户实际安装的产物形态 —— 现有性能报告的全部数据来自 debug 构建，
        // 解释器占 51% 采样，主线程侧的瓶颈排序不能直接采信。
        //
        // 签名换回 debug，两个理由：
        // 1) release 签名由 verifyReleaseSigning 校验证书指纹，基准构建不该动用正式发布密钥；
        // 2) 覆盖安装要求签名一致 —— 设备上那份 3.6.0 是 debug 构建
        //    （dumpsys package 显示 flags=[ DEBUGGABLE ]），换 release 签名会 INSTALL_FAILED_UPDATE_INCOMPATIBLE，
        //    只能卸载重装、清空想看已看数据，滚动基准就测不到真实数据量了。
        // 该构建类型名不匹配 preReleaseBuild/assembleRelease/bundleRelease，不会触发那项校验。
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
        // 霉粉彩蛋（126s 序列）的截图迭代专用包。彩蛋在正式包里藏在一道算术题之后、
        // 只能从头顺序播，想看第 9 张专辑卡片得等 60 多秒 —— 视觉细节改一次要几分钟
        // 才能看到结果。这个变体点开就是彩蛋，可跳到任意时刻并定格，见
        // app/src/eggpreview/ 与 scripts/egg-shot.sh。
        //
        // 独立 applicationId（com.tracktosearch.eggpreview）而不是直接 installDebug，
        // 两个理由：
        // 1) 设备上那份 3.6.0 也是 debug 签名，同包名装上去就是覆盖安装 —— 会顶掉
        //    用户的真实数据（想看列表、豆瓣凭据、日签记录），而这些数据本身就是
        //    平时验证功能的素材，重建一次代价远大于多装一个包；
        // 2) 改后缀之后两者共存，可以一边开着正式包对照一边刷预览包。
        //    manifest 里所有 authority 都写成 ${applicationId}.xxx，跟着一起换，
        //    不会与已装的包抢 provider。
        //
        // initWith(debug) 而不是 release：预览包一天要重装十几次，只服务视觉迭代。
        // 过 R8 + 资源压缩纯粹是拿构建时间换一个不需要的产物形态，
        // 而且 debug 签名正好省掉 release keystore —— 它不在版本库里，工作树里通常没有。
        //
        // matchingFallbacks：依赖侧（:benchmark 模块与各 AAR）没有 eggpreview 变体，
        // 不给回落，变体解析阶段会直接失败而不是自己去找 debug。
        create("eggpreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".eggpreview"
            versionNameSuffix = "-egg"
            matchingFallbacks += listOf("debug")
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
            // MockK 通过 ByteBuddy agent 插桩，JDK 17+ 默认禁止 self-attach，不加会抛
            // "Could not self-attach to current VM"，所有用到 mockk 的测试类直接类加载失败
            all { it.jvmArgs("-Djdk.attach.allowAttachSelf=true") }
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/LICENSE.md"
        resources.excludes += "/META-INF/LICENSE-notice.md"
        // sherpa-onnx 的 .so 较大（~125MB 原始），压缩进 APK 控制分发体积；
        // 代价是安装后解压占用与首次加载略慢，可接受
        jniLibs.useLegacyPackaging = true
    }
}

// Compose 编译器指标/报告，用于定位 restartable 但不 skippable 的 composable 与不稳定参数。
// 由 gradle.properties 的 kotlin.compose.compiler.metrics / kotlin.compose.compiler.reports
// 门控，默认关闭（开启会拖慢 Kotlin 编译）。
//
// Kotlin 2.x 的 org.jetbrains.kotlin.plugin.compose 不读这两个 Gradle 属性 —— 那是旧
// androidx compose compiler 时代的写法，必须走 composeCompiler DSL，这里显式转接一次。
//
// 用法：./gradlew :app:assembleDebug -Pkotlin.compose.compiler.reports=true \
//           -Pkotlin.compose.compiler.metrics=true
// 产物：app/build/compose-reports/
//   *-composables.txt      每个 composable 的 restartable / skippable 判定
//   *-composables.csv      同上，便于筛选
//   *-classes.txt          参数类型的稳定性判定
//   *-module.json          模块级汇总计数
// baselineprofile 插件会自己派生一个 nonMinifiedRelease 构建类型（initWith release，
// 关掉 R8 才能采到未混淆的方法签名），连 release 的 signingConfig 一起继承过去。
// 采集 profile 不该动用正式发布密钥；而且工作树里通常没有 release.jks
// （keystore 不在版本库），继承下来会直接卡在 validateSigningNonMinifiedRelease。
// 换 debug 签名还有一个必要理由：设备上已装的包是 debug 签名，覆盖安装要求签名一致。
//
// 必须用 finalizeDsl 而不是 buildTypes.configureEach：插件是在自己的配置阶段里
// 创建并设置该构建类型的，容器级 configureEach 会被它随后的赋值覆盖掉。
// finalizeDsl 是 AGP 给出的「DSL 锁定前最后一次修改」钩子，在所有插件配置完之后才跑。
androidComponents {
    finalizeDsl { extension ->
        val debugSigning = extension.signingConfigs.getByName("debug")
        extension.buildTypes.forEach { buildType ->
            if (buildType.name.startsWith("nonMinified") || buildType.name == "benchmark") {
                buildType.signingConfig = debugSigning
            }
        }
    }
}

composeCompiler {
    val reportsDir = layout.buildDirectory.dir("compose-reports")
    if (providers.gradleProperty("kotlin.compose.compiler.metrics").orNull == "true") {
        metricsDestination.set(reportsDir)
    }
    if (providers.gradleProperty("kotlin.compose.compiler.reports").orNull == "true") {
        reportsDestination.set(reportsDir)
    }
}

baselineProfile {
    // 生成结果落到 app/src/release/generated/baselineProfiles/，与手工维护的
    // src/main/baseline-prof.txt 并存 —— AGP 会把两份都作为 profile 源合并，profman 去重。
    // 先跑起来比对覆盖率，确认新流程不比手工那份差，再决定是否替换。
    // 生成命令：./gradlew :app:generateReleaseBaselineProfile
    saveInSrc = true
    // 不挂到普通构建上：生成需要连真机跑 instrumentation，
    // 自动触发会让没插设备的 assembleRelease 直接失败。
    automaticGenerationDuringBuild = false
    // BaselineProfileGenerator.startup() 标了 includeInStartupProfile，产出 startup-prof.txt，
    // 交给 AGP 做 dex 类布局优化 —— 本项目此前完全没有这一项。
    dexLayoutOptimization = true
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
    // sherpa-onnx：精灵语音激活的本地关键词识别（KWS）引擎，AAR 含全 ABI JNI 库
    implementation(files("libs/sherpa-onnx-1.13.6.aar"))
    /**
     * 把 `androidx.concurrent:concurrent-futures{,-ktx}` 抬到 1.2.0。
     *
     * AGP 会把 `debugAndroidTestRuntimeClasspath` 上的每个模块按 app 自己
     * `debugRuntimeClasspath` 解析出的版本钉成 `strictly`（Gradle 把这类约束报成
     * “from lock file”），目的是让测试 APK 与被测 APK 用同一份类路径。
     *
     * app 这边 `androidx.glance:1.1.1` 与 `androidx.work:work-runtime:2.11.2`
     * 只要到 1.1.0，于是 androidTest 被钉在 strictly 1.1.0；而 androidTest 专属的
     * `androidx.test:core:1.7.0` 依赖 `concurrent-futures-ktx:1.2.0`。
     * strictly 1.1.0 与 1.2.0 无法调和，整个 androidTest 源集连编译都进不去。
     *
     * 从 app 这一侧抬版本，AGP 钉出来的就是 strictly 1.2.0，两边自然一致。
     * 反方向（把 androidx.test:core 降到要 1.1.0 的版本）会丢测试 API，不划算。
     *
     * constraints 只提版本、不引入新依赖：这两个模块本来就在 app 图里。
     */
    constraints {
        implementation(libs.concurrent.futures)
        implementation(libs.concurrent.futures.ktx)
    }

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
    implementation(libs.telephoto.zoomable.image.coil)
    implementation(libs.compose.mesh.gradient)
    implementation(libs.mirage)

    // Baseline Profile
    implementation(libs.profileinstaller)
    // :benchmark 模块产出的 baseline / startup profile 的消费方声明
    baselineProfile(project(":benchmark"))

    // Security
    implementation(libs.security.crypto)

    // SQLCipher（Room 数据库加密）
    implementation(libs.sqlcipher)

    // 中文分词
    implementation(libs.jieba.analysis)

    // 中文转拼音（看单搜索拼音匹配）
    implementation(libs.pinyin4j)

implementation(libs.skydoves.colorpicker)

    /**
     * RichTap 触感 SDK（瑞声科技），vendored 二进制，96 KiB。
     *
     * 没有 maven 坐标 —— 官方只把它捆在 GitHub 上的 MIT 示例工程里，所以只能落到 libs/。
     * 版本与校验和记在 `THIRD-PARTY-NOTICES.md`，升级时一并更新。
     *
     * **取的是 `richtap_sdk_lite.aar`（来自 RichTapBounce），不是 RichTapDynamics /
     * RichTapAudioPlayer / RichTapVideoPlayer 里那个 `RichTap_ASDK_2.2.0_..._NETWORK_release.aar`。**
     * 后者的 `RichTapUtils.init(Context)` 会反射调用 `com.richtap.sdk.network.RTAPIService.track()`，
     * POST 到 `https://platform.richtap-haptics.com/richtap/sys/eventTrackingSdk/saveTrackingSdk`。
     * 本应用有隐私政策页，不接受未声明的第三方上报。lite 版里 `com/richtap/sdk/network/`
     * 一个类都没有（实测 0 个），那次反射调用取不到类会被 catch 掉，只留一行 Log.d。
     *
     * 只要 `VIBRATE`（app 已声明）。aar 自带
     * `<uses-library android:name="richtap-api" android:required="false" />` ——
     * `richtap-api` 是 ROM 侧共享库，没有它照样装得上，运行期用
     * `RichTapUtils.isSupportedRichTap()` 判定，不支持就整层退到 AOSP 通路。
     */
    implementation(files("libs/richtap_sdk_lite.aar"))

    // 单元测试
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.core.testing)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")

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
