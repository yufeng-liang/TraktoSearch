import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.tracktosearch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tracktosearch"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 从 local.properties 读取 API keys
        val localProps = rootProject.file("local.properties")
        val properties = Properties()
        if (localProps.exists()) {
            properties.load(localProps.inputStream())
        }

        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${properties.getProperty("trakt.client.id", "")}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${properties.getProperty("trakt.client.secret", "")}\"")
        buildConfigField("String", "TRAKT_REDIRECT_URI", "\"${properties.getProperty("trakt.redirect.uri", "tracktosearch://oauth/callback")}\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${properties.getProperty("tmdb.api.key", "")}\"")
        buildConfigField("String", "OMDB_API_KEY", "\"${properties.getProperty("omdb.api.key", "")}\"")
    }

    signingConfigs {
        getByName("debug") {
            // 使用默认 debug 签名
        }
        create("release") {
            val localProps = rootProject.file("local.properties")
            val properties = Properties()
            if (localProps.exists()) {
                properties.load(localProps.inputStream())
            }
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

    kotlinOptions {
        jvmTarget = "17"
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

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Image
    implementation(libs.coil.compose)

    // HTML Parsing
    implementation(libs.jsoup)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)
}
