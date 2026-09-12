package com.tracktosearch.data.ai

import androidx.room.withTransaction
import com.tracktosearch.data.local.db.AiProfileBehaviorDailyEntity
import com.tracktosearch.data.local.db.AiProfileDao
import com.tracktosearch.data.local.db.AiProfileMediaEntity
import com.tracktosearch.data.local.db.AiProfileMediaSourceEntity
import com.tracktosearch.data.local.db.AiProfileOutboxEntity
import com.tracktosearch.data.local.db.AiProfileSettingsEntity
import com.tracktosearch.data.local.db.AppDatabase
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 画像本地协调器。
 *
 * 负责本地授权、镜像、日聚合和 outbox；网络同步由 [AiProfileSyncCoordinator] 消费 pending outbox。
 */
@Singleton
class AiProfileRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: AiProfileDao
) {

    suspend fun settings(friendId: String): AiProfileSettings? =
        requireFriendId(friendId).let { dao.getSettings(it)?.toModel() }

    suspend fun grantProfileConsent(
        friendId: String,
        behaviorConsent: Boolean = false,
        personalizationEnabled: Boolean = true,
        syncEnabled: Boolean = true,
        shouldAutoImport: Boolean = true,
        now: Long = System.currentTimeMillis()
    ): AiProfileSettings {
        val id = requireFriendId(friendId)
        val entity = AiProfileSettingsEntity(
            friendId = id,
            profileConsent = true,
            behaviorConsent = behaviorConsent,
            personalizationEnabled = personalizationEnabled,
            syncEnabled = syncEnabled,
            shouldAutoImport = shouldAutoImport,
            updatedAt = now,
            clearedAt = null
        )
        dao.upsertSettings(entity)
        return entity.toModel()
    }

    suspend fun setBehaviorConsent(
        friendId: String,
        enabled: Boolean,
        now: Long = System.currentTimeMillis()
    ): AiProfileSettings {
        val id = requireFriendId(friendId)
        val current = dao.getSettings(id) ?: AiProfileSettingsEntity(friendId = id)
        val updated = current.copy(
            behaviorConsent = enabled,
            updatedAt = now
        )
        dao.upsertSettings(updated)
        return updated.toModel()
    }

    suspend fun mirrorMedia(
        friendId: String,
        snapshot: MediaSourceSnapshot,
        now: Long = System.currentTimeMillis()
    ): NormalizedMedia {
        val id = requireFriendId(friendId)
        val normalized = AiMediaNormalizer.normalize(snapshot)
        database.withTransaction {
            dao.upsertMedia(
                AiProfileMediaEntity(
                    friendId = id,
                    mediaKey = normalized.mediaKey,
                    mediaType = normalized.mediaType,
                    tmdbId = normalized.tmdbId,
                    traktId = normalized.traktId,
                    imdbId = normalized.imdbId,
                    doubanId = normalized.doubanId,
                    title = normalized.title,
                    year = normalized.year,
                    genresJson = normalized.genres.takeIf { it.isNotEmpty() }?.joinToString(
                        ",",
                        prefix = "[\"",
                        postfix = "\"]"
                    ) {
                        it.replace("\\", "\\\\").replace("\"", "\\\"")
                    } ?: "[]",
                    publicRating = normalized.publicRating,
                    userRating = normalized.userRating,
                    userComment = normalized.userComment,
                    watchedAt = normalized.watchedAt,
                    isWatched = normalized.isWatched,
                    isWatchlist = normalized.isWatchlist,
                    updatedAt = now
                )
            )
            val sources = buildList {
                normalized.tmdbId?.let { add(AiProfileMediaSourceEntity(id, normalized.mediaKey, "tmdb", it.toString(), updatedAt = now)) }
                normalized.traktId?.let { add(AiProfileMediaSourceEntity(id, normalized.mediaKey, "trakt", it.toString(), updatedAt = now)) }
                normalized.imdbId?.let { add(AiProfileMediaSourceEntity(id, normalized.mediaKey, "imdb", it, updatedAt = now)) }
                normalized.doubanId?.let { add(AiProfileMediaSourceEntity(id, normalized.mediaKey, "douban", it, updatedAt = now)) }
            }
            if (sources.isNotEmpty()) dao.upsertMediaSources(sources)
        }
        return normalized
    }

    suspend fun recordBehavior(
        friendId: String,
        mediaKey: String,
        day: String,
        behavior: AiProfileBehavior,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val id = requireFriendId(friendId)
        if (dao.getSettings(id)?.behaviorConsent != true) return false
        val current = dao.getBehavior(id, mediaKey, day)
            ?: AiProfileBehaviorDailyEntity(friendId = id, mediaKey = mediaKey, day = day)
        val aggregate = applyBehavior(current.toBehaviorAggregate(), behavior)
        dao.upsertBehavior(
            current.copy(
                dwellIgnoredCount = aggregate.dwellIgnoredCount,
                dwell10To30Count = aggregate.dwell10To30Count,
                dwell30To120Count = aggregate.dwell30To120Count,
                dwell120PlusCount = aggregate.dwell120PlusCount,
                searchClickCount = aggregate.searchClickCount,
                episodeStartCount = aggregate.episodeStartCount,
                episodeCompleteCount = aggregate.episodeCompleteCount,
                progress25Count = aggregate.progress25Count,
                progress50Count = aggregate.progress50Count,
                progress75Count = aggregate.progress75Count,
                progress100Count = aggregate.progress100Count,
                updatedAt = now
            )
        )
        return true
    }

    suspend fun enqueue(
        friendId: String,
        batch: AiProfileSyncBatch,
        payloadJson: String,
        now: Long = System.currentTimeMillis()
    ) {
        val id = requireFriendId(friendId)
        dao.upsertOutbox(
            AiProfileOutboxEntity(
                friendId = id,
                batchId = batch.batchId,
                schemaVersion = batch.schemaVersion,
                payloadJson = payloadJson,
                payloadDigest = sha256(payloadJson),
                createdAt = now,
                updatedAt = now
            )
        )
    }

    /** 构造当前用户的完整画像快照，供同步协调器写入 outbox 后发送到 Worker。 */
    suspend fun buildSyncBatch(
        friendId: String,
        batchId: String = "profile-${UUID.randomUUID()}"
    ): AiProfileSyncBatch {
        val id = requireFriendId(friendId)
        val behaviorConsent = dao.getSettings(id)?.behaviorConsent == true
        val media = dao.getMedia(id)
            .sortedBy { it.mediaKey }
            .map { entity ->
                entity.toWirePayload(
                    sources = dao.getMediaSources(id, entity.mediaKey)
                        .sortedWith(compareBy<AiProfileMediaSourceEntity> { it.source }
                            .thenBy { it.sourceId })
                ).copy(genres = entity.genresJson
                    .let(::parseGenresForStablePayload)
                    .sorted())
            }
        val behavior = if (behaviorConsent) {
            dao.getBehaviors(id)
                .sortedWith(compareBy<AiProfileBehaviorDailyEntity> { it.mediaKey }
                    .thenBy { it.day })
                .map { it.toWirePayload() }
        } else {
            emptyList()
        }
        return AiProfileSyncBatch(batchId = batchId, media = media, behavior = behavior)
    }

    suspend fun pending(friendId: String): List<AiProfileOutboxEntity> =
        dao.getPendingOutbox(requireFriendId(friendId))

    suspend fun outbox(friendId: String, batchId: String): AiProfileOutboxEntity? =
        dao.getOutbox(requireFriendId(friendId), batchId)

    suspend fun markOutbox(
        friendId: String,
        batchId: String,
        status: String,
        attemptCount: Int,
        error: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        dao.updateOutboxStatus(requireFriendId(friendId), batchId, status, attemptCount, error, now)
    }

    suspend fun clear(friendId: String, now: Long = System.currentTimeMillis()) {
        dao.clearProfile(requireFriendId(friendId), now)
    }

    private fun requireFriendId(friendId: String): String = friendId.trim().also {
        require(it.isNotEmpty()) { "friendId must not be blank" }
    }

    private fun AiProfileSettingsEntity.toModel() = AiProfileSettings(
        friendId = friendId,
        profileConsent = profileConsent,
        behaviorConsent = behaviorConsent,
        personalizationEnabled = personalizationEnabled,
        syncEnabled = syncEnabled,
        shouldAutoImport = shouldAutoImport,
        updatedAt = updatedAt,
        clearedAt = clearedAt
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun parseGenresForStablePayload(value: String): List<String> = runCatching {
        Json.parseToJsonElement(value)
            .jsonArray
            .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())
}
