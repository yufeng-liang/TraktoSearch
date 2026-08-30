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
 *
 * 入库标准：1960 年以后首映/首播的影视。更早的作品台词大多经过几十年转述，
 * 引文版本互相矛盾，逐条核到原片不现实。
 */
@Serializable
data class SplashQuote(
    val id: String,
    val year: Int,
    /**
     * TMDB 命名空间：电影 `movie`、剧集 `show`。
     *
     * [tmdbId] 在两个命名空间里各自编号，同一个数字在 `/movie/` 和 `/tv/` 下是两部
     * 不同的作品，所以点进详情页必须带上这个字段。海报路径不分命名空间，取图不受影响。
     *
     * 默认电影：库里绝大多数条目是电影，逐条写一遍 `"mediaType": "movie"` 只是噪音。
     */
    val mediaType: String = MEDIA_TYPE_MOVIE,
    val tmdbId: Int,
    val posterPath: String,
    /** 是否随 APK 内置了海报：随包的 5 条保证首次安装当天就有画面 */
    val bundled: Boolean = false,
    /**
     * 台词是否以英文原文示人。
     *
     * 只打在英语原片、且原句本身就是英文世界会引用的名句上：
     * 「like tears in rain」译成中文再准，也丢了原句的音节。
     */
    val preferOriginal: Boolean = false,
    /** 语言标签（en/zh/ja/ko）到逐行台词的映射 */
    val lines: Map<String, List<String>>,
    /** 语言标签到片名的映射，用于破折号后的出处标注 */
    val title: Map<String, String>,
    /**
     * 语言标签到日签关键词的映射。
     *
     * 关键词参照整部片的主旨提炼，不取自台词原文；中文限 2–4 字以便刻进方形印章，
     * 其余语言不限长度。
     *
     * 除四种界面语言外还有一个 [SEAL_LANG_ZH] 键，存中文关键词的繁体字形，只给印章用，
     * 见 [sealKeywordFor]。
     */
    val keyword: Map<String, String> = emptyMap(),
) {
    /** 按语言取台词行，缺失语言回落英文 */
    fun linesFor(lang: String): List<String> =
        lines[lang] ?: lines[FALLBACK_LANG] ?: emptyList()

    /** 按语言取片名，缺失语言回落英文 */
    fun titleFor(lang: String): String =
        title[lang] ?: title[FALLBACK_LANG] ?: id

    /** 按语言取关键词，缺失语言回落英文 */
    fun keywordFor(lang: String): String =
        keyword[lang] ?: keyword[FALLBACK_LANG] ?: ""

    /**
     * 印章上刻的关键词：中文走繁体，其余语言与 [keywordFor] 相同。
     *
     * 篆刻里没有简体——印章是这套设计里唯一「刻」出来的东西，用简体字形会立刻塌成
     * 一个普通标签。但界面正文和无障碍朗读仍走 [keywordFor]：读屏念的应该是界面语言，
     * 日历格子上的词也得跟界面一致，所以繁体只用在印面上，不替换关键词本身。
     *
     * 繁体字形是逐条写定在 assets/quotes.json 里的，不在运行时查简繁字表：一对多的字
     * （余/餘、后/後、里/裡、发/發、尽/盡、游/遊、舍/捨…）选哪个得看词义，字表会在
     * 「余情」「表里」这类词上给出错解，而这个错字是要印在图片上分享出去的。
     * 繁体缺失时回落到简体，宁可字形不对也不能空着一枚印。
     */
    fun sealKeywordFor(lang: String): String =
        if (lang == "zh") keyword[SEAL_LANG_ZH] ?: keywordFor(lang) else keywordFor(lang)

    /**
     * 台词实际渲染用的语言。
     *
     * [preferOriginal] 的条目一律走英文；片名不跟着切，界面语言下的片名才认得出是哪部片。
     */
    fun lineLang(lang: String): String =
        if (preferOriginal) FALLBACK_LANG else lang

    companion object {
        const val FALLBACK_LANG = "en"

        /** [mediaType] 的两个取值，与 Routes.detailRoute 的 type 参数同名 */
        const val MEDIA_TYPE_MOVIE = "movie"
        const val MEDIA_TYPE_SHOW = "show"

        /** [keyword] 里存繁体字形的键，只有中文有，只给印章用 */
        const val SEAL_LANG_ZH = "zh-Hant"

        /** 台词库覆盖的语言 */
        private val SUPPORTED_LANGS = setOf("en", "zh", "ja", "ko")

        /**
         * 把界面语言或系统语言收敛到台词库支持的四种，落不到的一律走英文。
         *
         * 开屏和日签共用同一份判断：两处若各写一遍，某天加了第五种语言就会出现
         * 开屏是中文、日签是英文的错配。
         */
        fun resolveLang(tag: String): String =
            if (tag in SUPPORTED_LANGS) tag else FALLBACK_LANG

        /** 片名的书名号：中韩《》、日『』、英文不加。开屏与日签卡片共用 */
        fun titleWrap(lang: String): Pair<String, String> = when (lang) {
            "zh", "ko" -> "《" to "》"
            "ja" -> "『" to "』"
            else -> "" to ""
        }
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
 * 全年 365 条、约 240 KB，解析一次比按需读盘或建索引简单得多；一天只用其中一条，
 * 但常驻的是解析后的 data class，内存代价可以忽略。
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
