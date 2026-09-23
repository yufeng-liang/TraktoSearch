package com.tracktosearch.ui.screen.dailystamp

import androidx.compose.runtime.Immutable
import com.tracktosearch.data.local.SplashPosterUrls
import com.tracktosearch.data.local.SplashQuote
import com.tracktosearch.data.repository.DailyStamp
import java.time.LocalDate

/**
 * 日历里一格的内容。
 *
 * 格子上印的是关键词而不是日期数字——日签的主体是那个词，日期退成角上的小刻度。
 * [poster] 只作为格子底纹（压得很淡），让整月看起来像一版票根拼贴；海报没下载时为 null，
 * 格子退化成纯纸底，不画占位框。
 *
 * 这里不带「点不点得开」：能翻开的是哪些天由 DailyStampContent.sheets 说，那张表算过
 * 台词能不能排成一张卡，也含错过的和还没到的那些天——它们根本没有格子内容。
 */
@Immutable
data class DailyStampCellUi(
    val date: LocalDate,
    val keyword: String,
    val poster: Any?,
    /**
     * 海报要不要糊着画。
     *
     * 只有「错过签到、但那天的卡片还没读过」的那些天是 true，此时 [keyword] 一并留空：
     * 糊海报说的是「那天有一句话，你还没看」，关键词一印上去就等于先透了底。
     */
    val blurred: Boolean = false,
)

/**
 * 一格是哪一种日子。四种互斥，判定见 [dayKind]。
 *
 * 分这四种是因为「没有签到」不是一件事：你来之前那些天本来就不该算你的，你来之后
 * 没打开的那天才是错过，而还没到的日子既不是错过也不是空白。
 */
internal enum class DayKind {
    /** 那天来过，格子上有海报和关键词 */
    Stamped,

    /** 初次使用之后、今天（含）之前，没打开过 App */
    Missed,

    /** 早于初次使用：那时这个人还没来，纸上本来就是空的 */
    Unarrived,

    /** 还没到的日子 */
    Future,
}

/**
 * 这一天归哪一类。
 *
 * [firstUse] 为 null 表示一条签到都没有，那时所有过去的日子都算 [DayKind.Unarrived]——
 * 没有任何证据说明这个人来过，谈不上错过。
 */
internal fun dayKind(
    date: LocalDate,
    today: LocalDate,
    firstUse: LocalDate?,
    stamped: Boolean,
): DayKind = when {
    stamped -> DayKind.Stamped
    date.isAfter(today) -> DayKind.Future
    firstUse == null || date.isBefore(firstUse) -> DayKind.Unarrived
    else -> DayKind.Missed
}

/**
 * 浮层里一页的内容。
 *
 * 分两种而不是给 [DailyStampCardUi] 加一个布尔：未来那天的卡片没有台词、没有片名、
 * 没有印文可显示，硬塞进同一个数据类只会让每个字段都要写「未来那天是空的」。
 */
@Immutable
internal sealed interface DailyStampSheet {
    val date: LocalDate

    /** 完整的一张卡：那天签到过，或者错过之后回来补看 */
    @Immutable
    data class Line(val card: DailyStampCardUi) : DailyStampSheet {
        override val date: LocalDate get() = card.date
    }

    /**
     * 还没显影的一张卡：只有日期和一张糊到认不出的海报。
     *
     * [tinyPosterUrl] 是 w92 那一档的地址，卡片会把它解到十几个像素再放大——
     * 留下的是那天的色调，不是那部片。取不到地址时为 null，卡片只剩纸和纹理。
     */
    @Immutable
    data class Latent(
        override val date: LocalDate,
        val tinyPosterUrl: String?,
    ) : DailyStampSheet
}

/**
 * 日签卡片的内容，与开屏台词层同源。
 *
 * [poster] 这里是 Coil 的加载来源而不是解好的位图：卡片是点开之后才出现的，
 * 允许有一帧加载过程，不像开屏那样必须先把图解出来再放行。
 */
@Immutable
data class DailyStampCardUi(
    val date: LocalDate,
    val lines: List<String>,
    /** 英文台词用斜体衬线、字号小一档，排版规则和 CJK 不同 */
    val isEnglish: Boolean,
    val title: String,
    val titleWrap: Pair<String, String>,
    val year: Int,
    val keyword: String,
    /**
     * 刻在印面上的字形：中文是繁体，其余语言与 [keyword] 相同。
     *
     * 和 [keyword] 分开存而不是就地替换：读屏念的该是界面语言的字形，只有印面走繁体。
     * 见 SplashQuote.sealKeywordFor。
     */
    val sealKeyword: String,
    /** [sealKeyword] 那个字形所属的语言，印章用它选字体。见 QuoteSeal.sealTypeface */
    val sealLang: String,
    /** 印在关键词下方的英文小字；界面本来就是英文时为 null */
    val keywordLatin: String?,
    val poster: Any?,
    /** 进影视详情页要用的 TMDB id */
    val tmdbId: Int,
    /**
     * [tmdbId] 所属的 TMDB 命名空间：`movie` 或 `show`。
     *
     * 详情页按它决定查电影还是剧集接口——两个命名空间各自编号，传错会打开另一部作品。
     * 见 SplashQuote.mediaType。
     */
    val mediaType: String,
    /**
     * 详情页首屏要用的海报网络地址；本地那份是给卡片自己显示的。
     *
     * 与开屏下载、详情页富化、沉浸取色缓存用的是同一个地址（见 SplashPosterUrls）。
     * 三处对齐才有两个好处：图片请求只发一次，以及开屏之后就算好的主色能被详情页直接命中，
     * 沉浸背景不必等海报加载完再补上。
     */
    val posterUrl: String,
)

/** 关键词取不到时格子上留一枚空印，日期数字照旧，不写「无」这种字样占位 */
internal fun DailyStamp.toCell(lang: String) = DailyStampCellUi(
    date = date,
    keyword = quote?.keywordFor(lang).orEmpty(),
    poster = poster,
)

/**
 * 错过签到那天的格子。
 *
 * 没读过只给一张糊海报，卡片展示过一次之后才和签到格一样印清晰的图和词。关键词一并留空
 * 是同一件事的两半：糊海报说的是「那天有一句话，你还没看」，词先印上去等于把答案透了一半。
 *
 * 纸色不在这里管——格子按 [DayKind.Missed] 取旧那一档，和读没读过无关。
 */
internal fun DailyStamp.toMissedCell(lang: String, read: Boolean): DailyStampCellUi {
    val cell = toCell(lang)
    return if (read) cell else cell.copy(keyword = "", blurred = true)
}

/**
 * 台词解析不出来（id 已下线）时返回 null，调用方不开卡片。
 *
 * 台词可能走英文原文而片名始终跟界面语言，与开屏同一套规则：
 * 混排时片名才认得出是哪部片。
 */
internal fun DailyStamp.toCard(lang: String): DailyStampCardUi? {
    val quote = quote ?: return null
    val lineLang = quote.lineLang(lang)
    val lines = quote.linesFor(lineLang)
    if (lines.isEmpty()) return null
    return DailyStampCardUi(
        date = date,
        lines = lines,
        isEnglish = lineLang == SplashQuote.FALLBACK_LANG,
        title = quote.titleFor(lang),
        titleWrap = SplashQuote.titleWrap(lang),
        year = quote.year,
        keyword = quote.keywordFor(lang),
        sealKeyword = quote.sealKeywordFor(lang),
        sealLang = lang,
        keywordLatin = quote.keywordFor(SplashQuote.FALLBACK_LANG)
            .takeIf { lang != SplashQuote.FALLBACK_LANG && it.isNotBlank() },
        poster = poster,
        tmdbId = quote.tmdbId,
        mediaType = quote.mediaType,
        posterUrl = SplashPosterUrls.url(quote, lang),
    )
}

/**
 * 未来那天的卡片：只把海报地址带过去，台词、片名、印文一个字都不带。
 *
 * 不显示不是「显示了再遮住」——遮罩会随实现走样（Modifier.blur 在 API 30 及以下是
 * 空操作），而这些字段一旦进了 UI 层就总有一天会被谁渲染出来。真正的保密是不带出来。
 */
internal fun DailyStamp.toLatent(lang: String) = DailyStampSheet.Latent(
    date = date,
    tinyPosterUrl = quote?.let { SplashPosterUrls.tinyUrl(it, lang) },
)
