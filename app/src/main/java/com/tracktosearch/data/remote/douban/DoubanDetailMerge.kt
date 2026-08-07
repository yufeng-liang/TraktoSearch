package com.tracktosearch.data.remote.douban

import com.tracktosearch.data.local.db.DoubanSyncedItem

/**
 * 详情页展示所需的豆瓣字段。Rexxar 负责基础字段，HTML 详情负责扩展字段。
 */
data class DoubanDetailPresentation(
    val doubanId: String? = null,
    val mediaType: DoubanRexxarMediaType? = null,
    val title: String? = null,
    val originalTitle: String? = null,
    val year: Int? = null,
    val releaseDates: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val overview: String? = null,
    val score: Double? = null,
    val ratingCount: Int? = null,
    val runtime: String? = null,
    val episodeCount: Int? = null,
    val episodeDuration: String? = null,
    val imdbId: String? = null,
    val posterUrl: String? = null,
    /** 本次 Rexxar 响应中的大图 URL，供已有同步记录回写。 */
    val largePosterUrl: String? = null,
    val ratingDistribution: List<Double> = emptyList(),
    val celebrities: List<DoubanCelebrityCacheEntry> = emptyList()
)

/**
 * 将 Rexxar、HTML、豆瓣同步快照和已有展示值按字段合并。
 * 基础字段优先级为 Rexxar → HTML → 同步快照 → 既有值；列表字段采用非空优先。
 */
fun mergeDoubanDetail(
    rexxar: DoubanRexxarDetail?,
    html: DoubanDetailCacheEntry?,
    snapshot: DoubanSyncedItem?,
    existing: DoubanDetailPresentation? = null
): DoubanDetailPresentation {
    val rexxarPoster = rexxar?.poster
    val snapshotGenres = snapshot?.genres
        ?.split("/")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()

    return DoubanDetailPresentation(
        doubanId = rexxar?.doubanId.present() ?: snapshot?.doubanId.present() ?: existing?.doubanId,
        mediaType = rexxar?.type ?: html?.let { if (it.isTvShow) DoubanRexxarMediaType.TV else DoubanRexxarMediaType.MOVIE }
            ?: existing?.mediaType,
        title = rexxar?.title.present() ?: html?.title.present()
            ?: snapshot?.displayTitle.present() ?: snapshot?.title.present() ?: existing?.title,
        originalTitle = rexxar?.originalTitle.present() ?: snapshot?.subtitle.present() ?: existing?.originalTitle,
        year = rexxar?.year.toYear() ?: html?.year.toYear() ?: snapshot?.year ?: existing?.year,
        releaseDates = firstNonEmpty(
            rexxar?.initialReleaseDates,
            html?.initialReleaseDates,
            existing?.releaseDates
        ),
        genres = firstNonEmpty(rexxar?.genres, html?.genres, snapshotGenres, existing?.genres),
        countries = firstNonEmpty(rexxar?.countries, html?.countries, existing?.countries),
        directors = firstNonEmpty(rexxar?.directors, html?.directors, existing?.directors),
        writers = firstNonEmpty(rexxar?.writers, html?.writers, existing?.writers),
        cast = firstNonEmpty(rexxar?.cast, html?.cast, existing?.cast),
        languages = firstNonEmpty(rexxar?.languages, html?.languages, existing?.languages),
        aliases = firstNonEmpty(rexxar?.aka, html?.aka, existing?.aliases),
        overview = rexxar?.summary.present() ?: html?.summary.present() ?: existing?.overview,
        score = rexxar?.score ?: html?.doubanRating ?: existing?.score,
        ratingCount = rexxar?.ratingCount ?: html?.ratingCount ?: existing?.ratingCount,
        runtime = rexxar?.runtime.present() ?: rexxar?.durations?.firstOrNull().present()
            ?: html?.runtime.present() ?: existing?.runtime,
        // Rexxar 当前 DTO 没有这些 HTML 扩展字段，必须单独保留。
        episodeCount = html?.episodeCount ?: existing?.episodeCount,
        episodeDuration = html?.episodeDuration.present() ?: existing?.episodeDuration,
        imdbId = rexxar?.imdbId.present() ?: html?.imdbId.present()
            ?: snapshot?.imdbId.present() ?: existing?.imdbId,
        posterUrl = choosePosterUrl(
            existing = existing?.posterUrl,
            rexxarPoster = rexxarPoster,
            htmlPoster = html?.posterUrl,
            snapshotPoster = snapshot?.posterUrl
        ),
        largePosterUrl = rexxarPoster?.largeUrl.present() ?: existing?.largePosterUrl,
        ratingDistribution = html?.ratingDistribution.takeIf { !it.isNullOrEmpty() }
            ?: existing?.ratingDistribution.orEmpty(),
        celebrities = html?.celebrities.takeIf { !it.isNullOrEmpty() }
            ?: existing?.celebrities.orEmpty()
    )
}

/**
 * 选择海报 URL：大图可替换旧图；旧图存在时普通图和小图不覆盖；没有旧图时才使用降级图。
 */
fun choosePosterUrl(
    existing: String?,
    rexxarPoster: DoubanRexxarImage?,
    htmlPoster: String?,
    snapshotPoster: String?
): String? {
    rexxarPoster?.largeUrl.present()?.let { return it }
    existing.present()?.let { return it }
    return rexxarPoster?.normalUrl.present()
        ?: rexxarPoster?.smallUrl.present()
        ?: htmlPoster.present()
        ?: snapshotPoster.present()
}

private fun String?.present(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun String?.toYear(): Int? = this?.let {
    YEAR_PATTERN.find(it)?.value?.toIntOrNull()
}

private fun <T> firstNonEmpty(vararg values: List<T>?): List<T> =
    values.firstOrNull { !it.isNullOrEmpty() }.orEmpty()

private val YEAR_PATTERN = Regex("\\d{4}")
