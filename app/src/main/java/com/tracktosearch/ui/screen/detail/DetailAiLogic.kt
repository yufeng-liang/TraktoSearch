package com.tracktosearch.ui.screen.detail

import com.tracktosearch.data.ai.AiDetailRecommendation
import com.tracktosearch.data.ai.AiDetailRecommendationDto
import com.tracktosearch.data.ai.AiMediaIdsDto
import com.tracktosearch.data.ai.mediaKeyFor
import com.tracktosearch.data.repository.MediaType
import java.time.LocalDate

/** 详情页 AI 助理的场景优先级。 */
enum class DetailAiScene(val wireName: String) {
    UNMARKED("UNMARKED"),
    WATCHED_NEEDS_REVIEW("WATCHED_NEEDS_REVIEW"),
    WATCHED_REVIEWED("WATCHED_REVIEWED"),
    WATCHLIST_CONTEXT("WATCHLIST_CONTEXT")
}

/**
 * 已看状态优先于想看状态：同一部影视在旧数据中可能同时存在于两个列表，
 * 此处保证 AI 不会把已看用户误判成“现在适合观看”。
 */
fun detailAiScene(
    watched: Boolean,
    rating: Int?,
    comment: String?,
    watchlist: Boolean
): DetailAiScene = when {
    watched && rating != null && !comment.isNullOrBlank() -> DetailAiScene.WATCHED_REVIEWED
    watched -> DetailAiScene.WATCHED_NEEDS_REVIEW
    watchlist -> DetailAiScene.WATCHLIST_CONTEXT
    else -> DetailAiScene.UNMARKED
}

/** 详情页自动入口只负责轻量露头，面板必须由用户点击打开。 */
fun shouldRevealDetailSprite(
    eligible: Boolean,
    foregroundReady: Boolean,
    dwellMs: Long,
    alreadyRevealed: Boolean
): Boolean = eligible && foregroundReady && !alreadyRevealed && dwellMs >= DETAIL_SPRITE_REVEAL_MS

const val DETAIL_SPRITE_REVEAL_MS = 3_500L

/** 详情页内容的身份键，避免 NavHost 复用组合实例时沿用上一部影视的就绪状态。 */
fun detailContentIdentity(
    traktId: Int,
    tmdbId: Int,
    mediaType: MediaType,
    title: String,
    doubanId: String?
): String = listOf(
    mediaType.name,
    traktId.toString(),
    tmdbId.toString(),
    title,
    doubanId.orEmpty()
).joinToString("|")

fun detailEnvironmentKey(
    localDate: String,
    weekday: Int,
    timeOfDay: String,
    season: String,
    weatherTag: String?
): String = listOf(
    localDate,
    weekday,
    timeOfDay,
    season,
    weatherTag.orEmpty().ifBlank { "NONE" }
).joinToString("|")

fun detailTimeOfDay(hour: Int): String = when (hour.coerceIn(0, 23)) {
    in 5..11 -> "MORNING"
    in 12..17 -> "AFTERNOON"
    in 18..21 -> "EVENING"
    else -> "NIGHT"
}

fun detailSeason(month: Int): String = when (month.coerceIn(1, 12)) {
    3, 4, 5 -> "SPRING"
    6, 7, 8 -> "SUMMER"
    9, 10, 11 -> "AUTUMN"
    else -> "WINTER"
}

fun detailTodayEnvironment(
    date: LocalDate = LocalDate.now(),
    hour: Int = java.time.LocalTime.now().hour,
    weatherTag: String? = null
): DetailEnvironment = DetailEnvironment(
    localDate = date.toString(),
    weekday = date.dayOfWeek.value % 7,
    timeOfDay = detailTimeOfDay(hour),
    season = detailSeason(date.monthValue),
    weatherTag = weatherTag?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
)

/**
 * 测试和推荐合并都使用同一可靠媒体身份。标题不参与去重，避免译名或重命名造成串片。
 */
fun recommendationIdentity(item: RecommendationItem): String? = when {
    item.tmdbId > 0 -> "tmdb:${item.tmdbId}"
    item.traktId > 0 -> "trakt:${item.traktId}"
    item.imdbId.isNotBlank() -> "imdb:${item.imdbId.trim().lowercase()}"
    else -> null
}

fun mergeAiRecommendations(
    currentMediaKey: String,
    existing: List<RecommendationItem>,
    ai: List<RecommendationItem>
): List<RecommendationItem> {
    val currentIdentity = currentMediaKey.substringAfter(':', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
        ?.let { raw ->
            when {
                raw.startsWith("tmdb:") -> raw
                raw.toIntOrNull() != null -> "tmdb:$raw"
                raw.startsWith("trakt:") -> raw
                raw.startsWith("imdb:") -> raw.lowercase()
                raw.startsWith("douban:") -> raw.lowercase()
                else -> null
            }
        }
    val seen = linkedSetOf<String>()
    val result = mutableListOf<RecommendationItem>()
    fun append(item: RecommendationItem) {
        val identity = recommendationIdentity(item) ?: return
        if (identity == currentIdentity || !seen.add(identity)) return
        result += item
    }
    val existingIdentities = existing.mapNotNull(::recommendationIdentity).toSet()
    // AI 独有条目先展示；与既有推荐重合的条目仍由 AI 卡片胜出，但排在 AI 新条目之后。
    ai.filter { recommendationIdentity(it) !in existingIdentities }.forEach(::append)
    ai.filter { recommendationIdentity(it) in existingIdentities }.forEach(::append)
    existing.forEach(::append)
    return result
}

fun detailRecommendationCount(
    aiLoaded: Boolean,
    ai: List<RecommendationItem>,
    existing: List<RecommendationItem>
): Int = if (aiLoaded) ai.size else existing.size

/** 仅供纯逻辑测试构造一个最小推荐卡片。 */
fun detailRecommendation(
    tmdbId: Int,
    title: String,
    traktId: Int = 0,
    imdbId: String = ""
): RecommendationItem = RecommendationItem(
    traktId = traktId,
    tmdbId = tmdbId,
    title = title,
    displayTitle = title,
    year = null,
    genres = "",
    posterUrl = null,
    imdbId = imdbId
)

data class DetailEnvironment(
    val localDate: String,
    val weekday: Int,
    val timeOfDay: String,
    val season: String,
    val weatherTag: String?
)

fun weatherTagFor(code: Int, temperature: Double): String = when {
    code in 13..17 || code in 26..28 -> "SNOW"
    code in 4..12 || code in 19..25 -> "RAIN"
    temperature <= 8 -> "COLD"
    temperature >= 30 -> "HOT"
    code == 2 || code == 3 -> "CLOUDY"
    else -> "CLEAR"
}

/** 详情页停留不足十秒只作为低意向记录，不参与正向兴趣判断。 */
fun shouldRecordDetailDwell(durationMs: Long): Boolean = durationMs >= 10_000L

/** 将现有相关推荐卡片转换为 Worker 可识别的候选，标题不能作为媒体身份。 */
fun detailRecommendationWire(
    item: RecommendationItem,
    mediaType: String
): AiDetailRecommendationDto? {
    val ids = AiMediaIdsDto(
        tmdbId = item.tmdbId.takeIf { it > 0 },
        traktId = item.traktId.takeIf { it > 0 }?.toString(),
        imdbId = item.imdbId.trim().takeIf { it.isNotBlank() }
    )
    val mediaKey = runCatching {
        mediaKeyFor(
            mediaType = mediaType,
            tmdbId = ids.tmdbId,
            traktId = ids.traktId?.toIntOrNull(),
            imdbId = ids.imdbId
        )
    }.getOrNull() ?: return null
    return AiDetailRecommendationDto(
        mediaKey = mediaKey,
        mediaType = mediaType,
        title = item.title.ifBlank { item.displayTitle },
        year = item.year,
        genres = item.genres.split(Regex("\\s*(?:/|、|,|，)\\s*"))
            .map(String::trim)
            .filter(String::isNotBlank),
        mediaIds = ids,
        posterUrl = item.posterUrl
    )
}

/** Worker 可能只返回 mediaKey，优先使用显式 ID，再从 key 恢复可导航 ID。 */
fun detailRecommendationFromAi(item: AiDetailRecommendation): RecommendationItem? {
    val parsed = parseDetailMediaKey(item.mediaKey)
    val tmdbId = item.mediaIds.tmdbId ?: parsed.tmdbId ?: 0
    val traktId = item.mediaIds.traktId?.toIntOrNull() ?: parsed.traktId ?: 0
    val imdbId = item.mediaIds.imdbId ?: parsed.imdbId.orEmpty()
    if (tmdbId <= 0 && traktId <= 0 && imdbId.isBlank()) return null
    return RecommendationItem(
        traktId = traktId,
        tmdbId = tmdbId,
        title = item.title,
        displayTitle = item.title,
        year = item.year,
        genres = item.genres.joinToString(" / "),
        posterUrl = item.posterUrl,
        imdbId = imdbId
    )
}

private data class ParsedDetailMediaKey(
    val tmdbId: Int? = null,
    val traktId: Int? = null,
    val imdbId: String? = null
)

private fun parseDetailMediaKey(mediaKey: String): ParsedDetailMediaKey {
    val raw = mediaKey.substringAfter(':', missingDelimiterValue = "").trim()
    return when {
        raw.startsWith("trakt:", ignoreCase = true) ->
            ParsedDetailMediaKey(traktId = raw.substringAfter(':').toIntOrNull())
        raw.startsWith("imdb:", ignoreCase = true) ->
            ParsedDetailMediaKey(imdbId = raw.substringAfter(':').trim().takeIf(String::isNotBlank))
        raw.toIntOrNull() != null -> ParsedDetailMediaKey(tmdbId = raw.toInt())
        else -> ParsedDetailMediaKey()
    }
}
