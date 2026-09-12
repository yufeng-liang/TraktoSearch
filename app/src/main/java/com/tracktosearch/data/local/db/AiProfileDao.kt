package com.tracktosearch.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AiProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(entity: AiProfileSettingsEntity)

    @Query("SELECT * FROM ai_profile_settings WHERE friendId = :friendId LIMIT 1")
    suspend fun getSettings(friendId: String): AiProfileSettingsEntity?

    @Query(
        "UPDATE ai_profile_settings SET profileConsent = :profileConsent, " +
            "behaviorConsent = :behaviorConsent, personalizationEnabled = :personalizationEnabled, " +
            "syncEnabled = :syncEnabled, shouldAutoImport = :shouldAutoImport, " +
            "updatedAt = :updatedAt, clearedAt = :clearedAt WHERE friendId = :friendId"
    )
    suspend fun updateSettings(
        friendId: String,
        profileConsent: Boolean,
        behaviorConsent: Boolean,
        personalizationEnabled: Boolean,
        syncEnabled: Boolean,
        shouldAutoImport: Boolean,
        updatedAt: Long,
        clearedAt: Long?
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMedia(entity: AiProfileMediaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaSources(entities: List<AiProfileMediaSourceEntity>)

    @Query("SELECT * FROM ai_profile_media WHERE friendId = :friendId AND mediaKey = :mediaKey LIMIT 1")
    suspend fun getMedia(friendId: String, mediaKey: String): AiProfileMediaEntity?

    @Query("SELECT * FROM ai_profile_media WHERE friendId = :friendId ORDER BY updatedAt DESC")
    suspend fun getMedia(friendId: String): List<AiProfileMediaEntity>

    @Query("SELECT * FROM ai_profile_media_source WHERE friendId = :friendId AND mediaKey = :mediaKey")
    suspend fun getMediaSources(friendId: String, mediaKey: String): List<AiProfileMediaSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBehavior(entity: AiProfileBehaviorDailyEntity)

    @Query(
        "SELECT * FROM ai_profile_behavior_daily " +
            "WHERE friendId = :friendId AND mediaKey = :mediaKey AND day = :day LIMIT 1"
    )
    suspend fun getBehavior(friendId: String, mediaKey: String, day: String): AiProfileBehaviorDailyEntity?

    @Query("SELECT * FROM ai_profile_behavior_daily WHERE friendId = :friendId ORDER BY day DESC")
    suspend fun getBehaviors(friendId: String): List<AiProfileBehaviorDailyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutbox(entity: AiProfileOutboxEntity)

    @Query(
        "SELECT * FROM ai_profile_outbox WHERE friendId = :friendId " +
            "AND status IN ('PENDING', 'RETRY') ORDER BY createdAt ASC"
    )
    suspend fun getPendingOutbox(friendId: String): List<AiProfileOutboxEntity>

    @Query("SELECT * FROM ai_profile_outbox WHERE friendId = :friendId AND batchId = :batchId LIMIT 1")
    suspend fun getOutbox(friendId: String, batchId: String): AiProfileOutboxEntity?

    @Query("UPDATE ai_profile_outbox SET status = :status, attemptCount = :attemptCount, lastError = :lastError, updatedAt = :updatedAt WHERE friendId = :friendId AND batchId = :batchId")
    suspend fun updateOutboxStatus(
        friendId: String,
        batchId: String,
        status: String,
        attemptCount: Int,
        lastError: String?,
        updatedAt: Long
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(entity: AiProfileSnapshotEntity)

    @Query("SELECT * FROM ai_profile_snapshot WHERE friendId = :friendId LIMIT 1")
    suspend fun getSnapshot(friendId: String): AiProfileSnapshotEntity?

    @Query("DELETE FROM ai_profile_media WHERE friendId = :friendId")
    suspend fun deleteMedia(friendId: String): Int

    @Query("DELETE FROM ai_profile_media_source WHERE friendId = :friendId")
    suspend fun deleteMediaSources(friendId: String): Int

    @Query("DELETE FROM ai_profile_behavior_daily WHERE friendId = :friendId")
    suspend fun deleteBehaviors(friendId: String): Int

    @Query("DELETE FROM ai_profile_outbox WHERE friendId = :friendId")
    suspend fun deleteOutbox(friendId: String): Int

    @Query("DELETE FROM ai_profile_snapshot WHERE friendId = :friendId")
    suspend fun deleteSnapshot(friendId: String): Int

    @Transaction
    suspend fun clearProfile(friendId: String, clearedAt: Long) {
        deleteMedia(friendId)
        deleteMediaSources(friendId)
        deleteBehaviors(friendId)
        deleteOutbox(friendId)
        deleteSnapshot(friendId)
        val current = getSettings(friendId)
        if (current == null) {
            upsertSettings(
                AiProfileSettingsEntity(
                    friendId = friendId,
                    profileConsent = false,
                    behaviorConsent = false,
                    personalizationEnabled = false,
                    syncEnabled = current?.syncEnabled ?: true,
                    shouldAutoImport = false,
                    updatedAt = clearedAt,
                    clearedAt = clearedAt
                )
            )
        } else {
            upsertSettings(
                current.copy(
                    profileConsent = false,
                    behaviorConsent = false,
                    personalizationEnabled = false,
                    shouldAutoImport = false,
                    updatedAt = clearedAt,
                    clearedAt = clearedAt
                )
            )
        }
    }
}
