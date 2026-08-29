package com.tracktosearch.ui.screen.dailystamp

import androidx.compose.runtime.Immutable
import com.tracktosearch.data.local.SplashQuote
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.repository.DailyStamp
import java.time.LocalDate

/**
 * 日历里一格的内容。
 *
 * 格子上印的是关键词而不是日期数字——日签的主体是那个词，日期退成角上的小刻度。
 * [poster] 只作为格子底纹（压得很淡），让整月看起来像一版票根拼贴；海报没下载时为 null，
 * 格子退化成纯纸底，不画占位框。
 */
@Immutable
data class DailyStampCellUi(
    val date: LocalDate,
    val keyword: String,
    val poster: Any?,
    /** 台词解析不出来时格子仍是「来过」，只是点不开卡片 */
    val openable: Boolean,
)

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
    /** 印在关键词下方的英文小字；界面本来就是英文时为 null */
    val keywordLatin: String?,
    val poster: Any?,
    /** 进影视详情页要用的 TMDB id */
    val tmdbId: Int,
    /** 详情页首屏要用的海报网络地址；本地那份是给卡片自己显示的 */
    val posterUrl: String,
)

/** 关键词取不到时格子上留一枚空印，日期数字照旧，不写「无」这种字样占位 */
internal fun DailyStamp.toCell(lang: String) = DailyStampCellUi(
    date = date,
    keyword = quote?.keywordFor(lang).orEmpty(),
    poster = poster,
    openable = openable,
)

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
        keywordLatin = quote.keywordFor(SplashQuote.FALLBACK_LANG)
            .takeIf { lang != SplashQuote.FALLBACK_LANG && it.isNotBlank() },
        poster = poster,
        tmdbId = quote.tmdbId,
        posterUrl = TmdbImageUrls.build(quote.posterPath, TmdbImageUrls.W342),
    )
}
