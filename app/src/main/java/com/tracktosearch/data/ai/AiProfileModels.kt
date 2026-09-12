package com.tracktosearch.data.ai

import kotlinx.serialization.Serializable

/** 画像数据使用的最小影视来源快照。标题只用于展示，不参与身份判定。 */
data class MediaSourceSnapshot(
    val mediaType: String,
    val tmdbId: Int? = null,
    val traktId: Int? = null,
    val imdbId: String? = null,
    val doubanId: String? = null,
    val title: String = "",
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val publicRating: Double? = null,
    val userRating: Double? = null,
    val userComment: String? = null,
    val watchedAt: Long? = null,
    val isWatched: Boolean = false,
    val isWatchlist: Boolean = false
)

/** 归一化后的媒体身份和可供画像镜像保存的快照。 */
data class NormalizedMedia(
    val mediaKey: String,
    val mediaType: String,
    val tmdbId: Int?,
    val traktId: Int?,
    val imdbId: String?,
    val doubanId: String?,
    val title: String,
    val year: Int?,
    val genres: List<String>,
    val publicRating: Double?,
    val userRating: Double?,
    val userComment: String?,
    val watchedAt: Long?,
    val isWatched: Boolean,
    val isWatchlist: Boolean
)

@Serializable
data class AiProfileSyncBatch(
    val batchId: String,
    val schemaVersion: Int = 1,
    val media: List<AiProfileMediaPayload> = emptyList(),
    val behavior: List<AiProfileBehaviorPayload> = emptyList()
)

@Serializable
data class AiProfileMediaPayload(
    val mediaKey: String,
    val mediaType: String,
    val title: String = "",
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val posterUrl: String? = null,
    val mediaIds: AiMediaIdsDto = AiMediaIdsDto(),
    val sources: List<AiProfileMediaSourcePayload> = emptyList()
)

@Serializable
data class AiProfileMediaSourcePayload(
    val source: String,
    val sourceMediaId: String? = null,
    val watchlist: Boolean = false,
    val watched: Boolean = false,
    val rating: Double? = null,
    val ratingScale: Int? = null,
    val comment: String? = null,
    val watchedAt: String? = null,
    val sourceUpdatedAt: Long,
    val clientUpdatedAt: Long? = null,
    val tombstone: Map<String, String>? = null
)

@Serializable
data class AiProfileBehaviorPayload(
    val mediaKey: String,
    val eventDay: String,
    val detailDwellBucket: String? = null,
    val searchClickCount: Int = 0,
    val playerProgressBuckets: Map<String, Int> = emptyMap(),
    val episodeStartedCount: Int = 0,
    val episodeCompletedCount: Int = 0,
    val lastEventAt: Long? = null
)

data class AiProfileSettings(
    val friendId: String,
    val profileConsent: Boolean,
    val behaviorConsent: Boolean,
    val personalizationEnabled: Boolean,
    val syncEnabled: Boolean,
    val shouldAutoImport: Boolean,
    val updatedAt: Long,
    val clearedAt: Long?
)

data class AiProfileSnapshot(
    val friendId: String,
    val profileVersion: Long,
    val status: String,
    val summaryJson: String,
    val generatedAt: Long?,
    val updatedAt: Long
)

@Serializable
data class AiProfileSettingsDto(
    val profileConsent: Boolean = false,
    val behaviorConsent: Boolean = false,
    val personalizationEnabled: Boolean = false,
    val syncEnabled: Boolean = true,
    val consentVersion: String = "1",
    val profileVersion: Long = 0L,
    val profileStatus: String = "READY"
)

@Serializable
data class AiProfileSettingsRequest(
    val consentVersion: String = "1",
    val profileConsent: Boolean? = null,
    val behaviorConsent: Boolean? = null,
    val personalizationEnabled: Boolean? = null,
    val syncEnabled: Boolean? = null
)

@Serializable
data class AiProfileSyncResultDto(
    val accepted: Boolean = false,
    val batchId: String = "",
    val profileVersion: Long = 0L,
    val profileStatus: String = "PENDING",
    val mediaAccepted: Int = 0,
    val behaviorAccepted: Int = 0
)

fun AiProfileSettingsDto.toDomain(friendId: String): AiProfileSettings = AiProfileSettings(
    friendId = friendId,
    profileConsent = profileConsent,
    behaviorConsent = behaviorConsent,
    personalizationEnabled = personalizationEnabled,
    syncEnabled = syncEnabled,
    shouldAutoImport = false,
    updatedAt = 0L,
    clearedAt = null
)
