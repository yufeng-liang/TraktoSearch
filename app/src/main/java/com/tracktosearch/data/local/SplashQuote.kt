package com.tracktosearch.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 开屏「每日一句」台词条目。
 *
 * 台词按行存储而不是整段：换行位置是排版的一部分——断在哪里决定了停顿的节奏，
 * 交给 Text 自动折行会把「说好了是一辈子。」拆在错误的位置。
 *
 * 海报走 TMDB：[tmdbId] + [posterPath] 组合出网关地址，命中 App 已有的图片链路；
 * 存绝对 URL 的话第三方图源一改就全挂。
 */
@Serializable
data class SplashQuote(
    val id: String,
    val year: Int,
    val tmdbId: Int,
    val posterPath: String,
    /** 是否随 APK 内置了海报：随包的 5 条保证首次安装当天就有画面 */
    val bundled: Boolean = false,
    /** 语言标签（en/zh/ja/ko）到逐行台词的映射 */
    val lines: Map<String, List<String>>,
    /** 语言标签到片名的映射，用于破折号后的出处标注 */
    val title: Map<String, String>,
) {
    /** 按语言取台词行，缺失语言回落英文 */
    fun linesFor(lang: String): List<String> =
        lines[lang] ?: lines[FALLBACK_LANG] ?: emptyList()

    /** 按语言取片名，缺失语言回落英文 */
    fun titleFor(lang: String): String =
        title[lang] ?: title[FALLBACK_LANG] ?: id

    companion object {
        const val FALLBACK_LANG = "en"
    }
}

@Serializable
private data class QuoteCatalogFile(
    @SerialName("version") val version: Int = 1,
    @SerialName("quotes") val quotes: List<SplashQuote> = emptyList(),
)

/**
 * 台词库：从 assets/quotes.json 读一次并常驻内存。
 *
 * 整个库只有几十条、总量几十 KB，解析一次比按需读盘简单得多；
 * 解析失败返回空列表而不是抛异常——开屏台词是锦上添花，不该让 App 起不来。
 */
@Singleton
class SplashQuoteCatalog @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: List<SplashQuote>? = null

    suspend fun quotes(): List<SplashQuote> {
        cached?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            try {
                val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
                json.decodeFromString<QuoteCatalogFile>(text).quotes
            } catch (e: Exception) {
                emptyList()
            }
        }
        cached = loaded
        return loaded
    }

    companion object {
        private const val ASSET_PATH = "quotes.json"
    }
}
