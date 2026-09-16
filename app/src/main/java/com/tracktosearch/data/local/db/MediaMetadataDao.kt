package com.tracktosearch.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaMetadataDao {

    @Query("SELECT * FROM media_metadata WHERE mediaKey IN (:keys)")
    suspend fun getByKeys(keys: List<String>): List<MediaMetadataEntity>

    @Query("SELECT * FROM media_metadata WHERE mediaKey = :key LIMIT 1")
    suspend fun getByKey(key: String): MediaMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaMetadataEntity>)

    @Query("DELETE FROM media_metadata WHERE updatedAt < :before")
    suspend fun deleteOlderThan(before: Long): Int
}
