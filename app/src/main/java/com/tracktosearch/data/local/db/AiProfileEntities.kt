package com.tracktosearch.data.local.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "ai_profile_settings",
    indices = [Index(value = ["updatedAt"])]
)
data class AiProfileSettingsEntity(
    @androidx.room.PrimaryKey val friendId: String,
    val profileConsent: Boolean = false,
    val behaviorConsent: Boolean = false,
    val personalizationEnabled: Boolean = false,
    val syncEnabled: Boolean = true,
    val shouldAutoImport: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val clearedAt: Long? = null
)

@Entity(
    tableName = "ai_profile_media",
    primaryKeys = ["friendId", "mediaKey"],
    indices = [
        Index(value = ["friendId"], name = "index_ai_profile_media_friendId"),
        Index(value = ["friendId", "mediaType"], name = "index_ai_profile_media_friendId_mediaType"),
        Index(value = ["friendId", "updatedAt"], name = "index_ai_profile_media_friendId_updatedAt")
    ]
)
data class AiProfileMediaEntity(
    val friendId: String,
    val mediaKey: String,
    val mediaType: String,
    val tmdbId: Int? = null,
    val traktId: Int? = null,
    val imdbId: String? = null,
    val doubanId: String? = null,
    val title: String = "",
    val year: Int? = null,
    val genresJson: String = "[]",
    val publicRating: Double? = null,
    val userRating: Double? = null,
    val userComment: String? = null,
    val watchedAt: Long? = null,
    val isWatched: Boolean = false,
    val isWatchlist: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ai_profile_media_source",
    primaryKeys = ["friendId", "mediaKey", "source"],
    indices = [
        Index(value = ["friendId"], name = "index_ai_profile_media_source_friendId"),
        Index(value = ["friendId", "mediaKey"], name = "index_ai_profile_media_source_friendId_mediaKey"),
        Index(value = ["friendId", "sourceId"], name = "index_ai_profile_media_source_friendId_sourceId")
    ]
)
data class AiProfileMediaSourceEntity(
    val friendId: String,
    val mediaKey: String,
    val source: String,
    val sourceId: String,
    val isTombstone: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ai_profile_behavior_daily",
    primaryKeys = ["friendId", "mediaKey", "day"],
    indices = [
        Index(value = ["friendId"], name = "index_ai_profile_behavior_daily_friendId"),
        Index(value = ["friendId", "day"], name = "index_ai_profile_behavior_daily_friendId_day")
    ]
)
data class AiProfileBehaviorDailyEntity(
    val friendId: String,
    val mediaKey: String,
    val day: String,
    val dwellIgnoredCount: Int = 0,
    val dwell10To30Count: Int = 0,
    val dwell30To120Count: Int = 0,
    val dwell120PlusCount: Int = 0,
    val searchClickCount: Int = 0,
    val episodeStartCount: Int = 0,
    val episodeCompleteCount: Int = 0,
    val progress25Count: Int = 0,
    val progress50Count: Int = 0,
    val progress75Count: Int = 0,
    val progress100Count: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ai_profile_outbox",
    primaryKeys = ["friendId", "batchId"],
    indices = [
        Index(value = ["friendId"], name = "index_ai_profile_outbox_friendId"),
        Index(value = ["friendId", "status"], name = "index_ai_profile_outbox_friendId_status"),
        Index(value = ["friendId", "createdAt"], name = "index_ai_profile_outbox_friendId_createdAt")
    ]
)
data class AiProfileOutboxEntity(
    val friendId: String,
    val batchId: String,
    val schemaVersion: Int = 1,
    val payloadJson: String,
    val payloadDigest: String,
    val status: String = "PENDING",
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ai_profile_snapshot",
    indices = [Index(value = ["updatedAt"])]
)
data class AiProfileSnapshotEntity(
    @androidx.room.PrimaryKey val friendId: String,
    val profileVersion: Long = 0L,
    val status: String = "EMPTY",
    val summaryJson: String = "{}",
    val generatedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
