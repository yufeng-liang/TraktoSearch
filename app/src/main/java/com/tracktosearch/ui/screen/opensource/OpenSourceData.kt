package com.tracktosearch.ui.screen.opensource

import androidx.annotation.StringRes
import com.tracktosearch.R

/**
 * 开源库条目。名称/版本/许可证/开发者是专有名词不翻译；
 * 用途说明走字符串资源（opensource_use_*），四语言同步维护。
 * 版本号与 gradle/libs.versions.toml 对齐，升级依赖时同步更新。
 */
data class OssLibrary(
    val name: String,
    val version: String,
    val license: String,
    val developer: String,
    @StringRes val usageRes: Int,
    val repoUrl: String
)

/** 分组小节：标题 + 该组下的库列表 */
data class OssGroup(
    @StringRes val titleRes: Int,
    val libraries: List<OssLibrary>
)

private const val ANDROIDX_REPO = "https://github.com/androidx/androidx"
private const val APACHE = "Apache License 2.0"

object OpenSourceData {

    val groups: List<OssGroup> = listOf(
        OssGroup(R.string.opensource_group_ui, listOf(
            OssLibrary(
                name = "Jetpack Compose & Material 3",
                version = "BOM 2026.06.01",
                license = APACHE,
                developer = "Google",
                usageRes = R.string.opensource_use_compose,
                repoUrl = ANDROIDX_REPO
            ),
            OssLibrary(
                name = "Navigation Compose",
                version = "2.9.8",
                license = APACHE,
                developer = "Google",
                usageRes = R.string.opensource_use_navigation,
                repoUrl = ANDROIDX_REPO
            ),
            OssLibrary(
                name = "Haze",
                version = "2.0.0-beta01",
                license = APACHE,
                developer = "Chris Banes",
                usageRes = R.string.opensource_use_haze,
                repoUrl = "https://github.com/chrisbanes/haze"
            ),
            OssLibrary(
                name = "Lottie Compose",
                version = "6.7.1",
                license = APACHE,
                developer = "Airbnb",
                usageRes = R.string.opensource_use_lottie,
                repoUrl = "https://github.com/airbnb/lottie-android"
            ),
            OssLibrary(
                name = "Compose RichText",
                version = "0.20.0",
                license = APACHE,
                developer = "halilibo",
                usageRes = R.string.opensource_use_richtext,
                repoUrl = "https://github.com/halilozercan/compose-richtext"
            ),
            OssLibrary(
                name = "Reorderable",
                version = "3.1.0",
                license = APACHE,
                developer = "Calvin Liang",
                usageRes = R.string.opensource_use_reorderable,
                repoUrl = "https://github.com/Calvin-LL/Reorderable"
            ),
            OssLibrary(
                name = "Zoomable",
                version = "2.13.0",
                license = APACHE,
                developer = "Albert Chang",
                usageRes = R.string.opensource_use_zoomable,
                repoUrl = "https://github.com/mxalbert1996/Zoomable"
            ),
            OssLibrary(
                name = "Backdrop",
                version = "2.0.0",
                license = APACHE,
                developer = "Kyant0",
                usageRes = R.string.opensource_use_backdrop,
                repoUrl = "https://github.com/Kyant0/Backdrop"
            ),
            OssLibrary(
                name = "Compose Mesh Gradient",
                version = "0.3.0",
                license = APACHE,
                developer = "Omkar Deshmukh",
                usageRes = R.string.opensource_use_mesh_gradient,
                repoUrl = "https://github.com/om252345/ComposeMeshGradient"
            ),
            OssLibrary(
                name = "Mirage",
                version = "0.1.0",
                license = APACHE,
                developer = "Ranbir Singh",
                usageRes = R.string.opensource_use_mirage,
                repoUrl = "https://github.com/androidpoet/Mirage"
            ),
            OssLibrary(
                name = "ColorPicker Compose",
                version = "1.2.0",
                license = APACHE,
                developer = "skydoves",
                usageRes = R.string.opensource_use_colorpicker,
                repoUrl = "https://github.com/skydoves/colorpicker-compose"
            )
        )),
        OssGroup(R.string.opensource_group_framework, listOf(
            OssLibrary(
                name = "Kotlin Coroutines & Serialization",
                version = "1.11.0 / 1.7.3",
                license = APACHE,
                developer = "JetBrains",
                usageRes = R.string.opensource_use_kotlin,
                repoUrl = "https://github.com/Kotlin/kotlinx.coroutines"
            ),
            OssLibrary(
                name = "Hilt",
                version = "2.60.1",
                license = APACHE,
                developer = "Google",
                usageRes = R.string.opensource_use_hilt,
                repoUrl = "https://github.com/google/dagger"
            ),
            OssLibrary(
                name = "Retrofit & OkHttp",
                version = "2.11.0 / 5.5.0",
                license = APACHE,
                developer = "Square, Inc.",
                usageRes = R.string.opensource_use_square,
                repoUrl = "https://github.com/square/retrofit"
            )
        )),
        OssGroup(R.string.opensource_group_data, listOf(
            OssLibrary(
                name = "Room",
                version = "2.8.4",
                license = APACHE,
                developer = "Google",
                usageRes = R.string.opensource_use_room,
                repoUrl = ANDROIDX_REPO
            ),
            OssLibrary(
                name = "SQLCipher for Android",
                version = "4.5.4",
                license = "BSD-style License",
                developer = "Zetetic LLC",
                usageRes = R.string.opensource_use_sqlcipher,
                repoUrl = "https://github.com/sqlcipher/android-database-sqlcipher"
            ),
            OssLibrary(
                name = "DataStore",
                version = "1.2.1",
                license = APACHE,
                developer = "Google",
                usageRes = R.string.opensource_use_datastore,
                repoUrl = ANDROIDX_REPO
            ),
            OssLibrary(
                name = "WorkManager",
                version = "2.11.2",
                license = APACHE,
                developer = "Google",
                usageRes = R.string.opensource_use_workmanager,
                repoUrl = ANDROIDX_REPO
            ),
            OssLibrary(
                name = "Glance",
                version = "1.1.1",
                license = APACHE,
                developer = "Google",
                usageRes = R.string.opensource_use_glance,
                repoUrl = ANDROIDX_REPO
            )
        )),
        OssGroup(R.string.opensource_group_image, listOf(
            OssLibrary(
                name = "Coil",
                version = "2.7.0",
                license = APACHE,
                developer = "Coil Contributors",
                usageRes = R.string.opensource_use_coil,
                repoUrl = "https://github.com/coil-kt/coil"
            )
        )),
        OssGroup(R.string.opensource_group_text, listOf(
            OssLibrary(
                name = "jsoup",
                version = "1.23.1",
                license = "MIT License",
                developer = "Jonathan Hedley",
                usageRes = R.string.opensource_use_jsoup,
                repoUrl = "https://github.com/jhy/jsoup"
            ),
            OssLibrary(
                name = "jieba-analysis",
                version = "1.0.2",
                license = APACHE,
                developer = "Huaban Inc.",
                usageRes = R.string.opensource_use_jieba,
                repoUrl = "https://github.com/huaban/jieba-analysis"
            ),
            OssLibrary(
                name = "pinyin4j",
                version = "2.5.1",
                license = "BSD License",
                developer = "belerweb",
                usageRes = R.string.opensource_use_pinyin,
                repoUrl = "https://github.com/belerweb/pinyin4j"
            )
        ))
    )
}
