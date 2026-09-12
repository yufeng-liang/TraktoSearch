package com.tracktosearch.data.ai

import com.tracktosearch.data.local.db.AiProfileBehaviorDailyEntity
import com.tracktosearch.data.local.db.AiProfileMediaEntity
import com.tracktosearch.data.local.db.AiProfileMediaSourceEntity
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 将 Room 中便于聚合的本地模型转换为 Worker 接收的稳定 JSON 契约。
 * 本地计数仍然保留细分桶；上传时只发送 Worker 认可的结构化字段。
 */
fun AiProfileMediaEntity.toWirePayload(
    sources: List<AiProfileMediaSourceEntity>,
    sourceUpdatedAt: Long = updatedAt
): AiProfileMediaPayload = AiProfileMediaPayload(
    mediaKey = mediaKey,
    mediaType = mediaType,
    title = title,
    year = year,
    genres = parseGenresJson(genresJson),
    posterUrl = null,
    mediaIds = AiMediaIdsDto(
        tmdbId = tmdbId,
        traktId = traktId?.toString(),
        imdbId = imdbId,
        doubanId = doubanId
    ),
    sources = sources.map { source ->
        AiProfileMediaSourcePayload(
            source = source.source,
            sourceMediaId = source.sourceId,
            watchlist = isWatchlist,
            watched = isWatched,
            rating = userRating,
            ratingScale = userRating?.let { 10 },
            comment = userComment,
            watchedAt = watchedAt?.let(::isoInstant),
            sourceUpdatedAt = source.updatedAt.coerceAtLeast(sourceUpdatedAt).toEpochSeconds(),
            clientUpdatedAt = source.updatedAt.toEpochSeconds(),
            tombstone = if (source.isTombstone) mapOf("deletedAt" to isoInstant(source.updatedAt)) else null
        )
    }.ifEmpty {
        listOf(
            AiProfileMediaSourcePayload(
                source = "local",
                sourceMediaId = mediaKey,
                watchlist = isWatchlist,
                watched = isWatched,
                rating = userRating,
                ratingScale = userRating?.let { 10 },
                comment = userComment,
                watchedAt = watchedAt?.let(::isoInstant),
                sourceUpdatedAt = sourceUpdatedAt.toEpochSeconds(),
                clientUpdatedAt = sourceUpdatedAt.toEpochSeconds()
            )
        )
    }
)

fun AiProfileBehaviorDailyEntity.toWirePayload(): AiProfileBehaviorPayload =
    AiProfileBehaviorPayload(
        mediaKey = mediaKey,
        eventDay = day,
        detailDwellBucket = when {
            dwell120PlusCount > 0 -> "OVER_ONE_HUNDRED_TWENTY_SECONDS"
            dwell30To120Count > 0 -> "THIRTY_TO_ONE_HUNDRED_TWENTY_SECONDS"
            dwell10To30Count > 0 -> "TEN_TO_THIRTY_SECONDS"
            dwellIgnoredCount > 0 -> "UNDER_TEN_SECONDS"
            else -> null
        },
        searchClickCount = searchClickCount,
        playerProgressBuckets = buildMap {
            if (progress25Count > 0) put("25", progress25Count)
            if (progress50Count > 0) put("50", progress50Count)
            if (progress75Count > 0) put("75", progress75Count)
            if (progress100Count > 0) put("completed", progress100Count)
        },
        episodeStartedCount = episodeStartCount,
        episodeCompletedCount = episodeCompleteCount,
        lastEventAt = updatedAt.toEpochSeconds()
    )

private val wireJson = Json { ignoreUnknownKeys = true }

private fun parseGenresJson(value: String): List<String> = runCatching {
    wireJson.parseToJsonElement(value).jsonArray
        .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
}.getOrDefault(emptyList())

private fun Long.toEpochSeconds(): Long = div(1_000L).coerceAtLeast(0L)

private fun isoInstant(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs.coerceAtLeast(0L)).atOffset(ZoneOffset.UTC).toString()
