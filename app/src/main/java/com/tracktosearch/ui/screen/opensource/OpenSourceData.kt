package com.tracktosearch.ui.screen.opensource

import androidx.annotation.StringRes
import com.tracktosearch.BuildConfig
import com.tracktosearch.R
import java.util.Base64

/**
 * 开源库条目。名称/许可证/开发者是专有名词不翻译；
 * 用途说明走字符串资源（opensource_use_*），四语言同步维护。
 * 版本号通过 [versionAliases] 从构建期 Version Catalog 快照解析，不再手工复制。
 */
data class OssLibrary(
    val name: String,
    internal val versionAliases: List<String>,
    internal val versionPrefix: String = "",
    val license: String,
    val developer: String,
    @StringRes val usageRes: Int,
    val repoUrl: String
) {
    val version: String = OpenSourceVersionCatalog.display(
        aliases = versionAliases,
        prefix = versionPrefix
    )
}

/** 分组小节：标题 + 该组下的库列表 */
data class OssGroup(
    @StringRes val titleRes: Int,
    val libraries: List<OssLibrary>
)

private const val ANDROIDX_REPO = "https://github.com/androidx/androidx"
private const val APACHE = "Apache License 2.0"

internal object OpenSourceVersionCatalog {
    val versions: Map<String, String> by lazy {
        decode(BuildConfig.OPEN_SOURCE_VERSION_CATALOG_BASE64)
    }

    fun display(
        aliases: List<String>,
        prefix: String = "",
        separator: String = " / "
    ): String {
        require(aliases.isNotEmpty()) { "Open-source library must reference at least one version alias" }
        return aliases.joinToString(separator = separator, prefix = prefix, transform = ::version)
    }

    fun version(alias: String): String {
        val normalizedAlias = alias.replace('-', '.').replace('_', '.')
        return checkNotNull(versions[normalizedAlias]) {
            "Missing version alias '$alias' in generated Version Catalog snapshot"
        }
    }

    internal fun decode(encodedCatalog: String): Map<String, String> {
        val decoded = Base64.getDecoder().decode(encodedCatalog).toString(Charsets.UTF_8)
        val entries = decoded.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val separatorIndex = line.indexOf('=')
                require(separatorIndex in 1 until line.lastIndex) {
                    "Invalid Version Catalog snapshot entry: $line"
                }
                line.substring(0, separatorIndex) to line.substring(separatorIndex + 1)
            }
            .toList()
        return entries.toMap().also { versions ->
            require(versions.size == entries.size) { "Duplicate aliases in Version Catalog snapshot" }
        }
    }
}

private fun versionAliases(vararg aliases: String): List<String> = aliases.toList()

object OpenSourceData {

    val groups: List<OssGroup> = listOf(
        OssGroup(R.string.opensource_group_ui, listOf(
            OssLibrary(
                name = "Jetpack Compose & Material 3",
                versionAliases = versionAliases("composeBom"),
                versionPrefix = "BOM ",
                license = APACHE,
                developer = "Google",
                usageRes = R.string.opensource_use_compose,
                repoUrl = ANDROIDX_REPO
            ),
            OssLibrary(
                name = "Navigation Compose",
                versionAliases = versionAliases("navigationCompose"),
                license = APACHE,
                developer = "Google",
                usageRes = R.string.opensource_use_navigation,
                repoUrl = ANDROIDX_REPO
            ),
            OssLibrary(
                name = "Haze",
                versionAliases = versionAliases("haze"),
                license = APACHE,
                developer = "Chris Banes",
                usageRes = R.string.opensource_use_haze,
                repoUrl = "https://github.com/chrisbanes/haze"
            ),
            OssLibrary(
                name = "Lottie Compose",
                versionAliases = versionAliases("lottie"),
                license = APACHE,
                developer = "Airbnb",
                usageRes = R.string.opensource_use_lottie,
                repoUrl = "https://github.com/airbnb/lottie-android"
            ),
            OssLibrary(
                name = "Compose RichText",
                versionAliases = versionAliases("richtext"),
                license = APACHE,
                developer = "halilibo",
                usageRes = R.string.opensource_use_richtext,
                repoUrl = "https://github.com/halilozercan/compose-richtext"
            ),
            OssLibrary(
                name = "Reorderable",
                versionAliases = versionAliases("reorderable"),
                license = APACHE,
                developer = "Calvin Liang",
                usageRes = R.string.opensource_use_reorderable,
                repoUrl = "https://github.com/Calvin-LL/Reorderable"
            ),
            OssLibrary(
                name = "Zoomable",
                versionAliases = versionAliases("zoomable"),
                license = APACHE,
                developer = "Albert Chang",
                usageRes = R.string.opensource_use_zoomable,
                repoUrl = "https://github.com/mxalbert1996/Zoomable"
            ),
            OssLibrary(
                name = "Backdrop",
                versionAliases = versionAliases("backdrop"),
                license = APACHE,
                developer = "Kyant0",
                usageRes = R.string.opensource_use_backdrop,
                repoUrl = "https://github.com/Kyant0/Backdrop"
            ),
            OssLibrary(
                name = "Compose Mesh Gradient",
                versionAliases = versionAliases("composeMeshGradient"),
                license = APACHE,
                developer = "Omkar Deshmukh",
                usageRes = R.string.opensource_use_mesh_gradient,
                repoUrl = "https://github.com/om252345/ComposeMeshGradient"
            ),
            OssLibrary(
                name = "Mirage",
                versionAliases = versionAliases("mirage"),
                license = APACHE,
                developer = "Ranbir Singh",
                usageRes = R.string.opensource_use_mirage,
                repoUrl = "https://github.com/androidpoet/Mirage"
            ),
            OssLibrary(
                name = "ColorPicker Compose",
                versionAliases = versionAliases("skydovesColorPicker"),
                license = APACHE,
                developer = "skydoves",
                usageRes = R.string.opensource_use_colorpicker,
                repoUrl = "https://github.com/skydoves/colorpicker-compose"
            )
        )),
        OssGroup(R.string.opensource_group_framework, listOf(
            OssLibrary(
                name = "Kotlin Coroutines & Serialization",
                versionAliases = versionAliases("coroutines", "serialization"),
                license = APACHE,
                developer = "JetBrains",
                usageRes = R.string.opensource_use_kotlin,
                repoUrl = "https://github.com/Kotlin/kotlinx.coroutines"
            ),
            OssLibrary(
                name = "Hilt",
                versionAliases = versionAliases("hilt"),
                license = APACHE,
                developer = "Google",
                usageRes = R.string.opensource_use_hilt,
                repoUrl = "https://github.com/google/dagger"
            ),
            OssLibrary(
                name = "Retrofit & OkHttp",
                versionAliases = versionAliases("retrofit", "okhttp"),
                license = APACHE,
                developer = "Square, Inc.",
                usageRes = R.string.opensource_use_square,
                repoUrl = "https://github.com/square/retrofit"
            )
        )),
        OssGroup(R.string.opensource_group_data, listOf(
            OssLibrary(
                name = "Room",
                versionAliases = versionAliases("room"),
                license = APACHE,
                developer = "Google",
                usageRes = R.string.opensource_use_room,
                repoUrl = ANDROIDX_REPO
            ),
            OssLibrary(
                name = "SQLCipher for Android",
                versionAliases = versionAliases("sqlcipher"),
                license = "BSD-style License",
                developer = "Zetetic LLC",
                usageRes = R.string.opensource_use_sqlcipher,
                repoUrl = "https://github.com/sqlcipher/android-database-sqlcipher"
            ),
            OssLibrary(
                name = "DataStore",
                versionAliases = versionAliases("datastore"),
                license = APACHE,
                developer = "Google",
                usageRes = R.string.opensource_use_datastore,
                repoUrl = ANDROIDX_REPO
            ),
            OssLibrary(
                name = "WorkManager",
                versionAliases = versionAliases("workManager"),
                license = APACHE,
                developer = "Google",
                usageRes = R.string.opensource_use_workmanager,
                repoUrl = ANDROIDX_REPO
            ),
            OssLibrary(
                name = "Glance",
                versionAliases = versionAliases("glance"),
                license = APACHE,
                developer = "Google",
                usageRes = R.string.opensource_use_glance,
                repoUrl = ANDROIDX_REPO
            )
        )),
        OssGroup(R.string.opensource_group_image, listOf(
            OssLibrary(
                name = "Coil",
                versionAliases = versionAliases("coil"),
                license = APACHE,
                developer = "Coil Contributors",
                usageRes = R.string.opensource_use_coil,
                repoUrl = "https://github.com/coil-kt/coil"
            )
        )),
        OssGroup(R.string.opensource_group_text, listOf(
            OssLibrary(
                name = "jsoup",
                versionAliases = versionAliases("jsoup"),
                license = "MIT License",
                developer = "Jonathan Hedley",
                usageRes = R.string.opensource_use_jsoup,
                repoUrl = "https://github.com/jhy/jsoup"
            ),
            OssLibrary(
                name = "jieba-analysis",
                versionAliases = versionAliases("jieba-analysis"),
                license = APACHE,
                developer = "Huaban Inc.",
                usageRes = R.string.opensource_use_jieba,
                repoUrl = "https://github.com/huaban/jieba-analysis"
            ),
            OssLibrary(
                name = "pinyin4j",
                versionAliases = versionAliases("pinyin4j"),
                license = "BSD License",
                developer = "belerweb",
                usageRes = R.string.opensource_use_pinyin,
                repoUrl = "https://github.com/belerweb/pinyin4j"
            )
        ))
    )
}
