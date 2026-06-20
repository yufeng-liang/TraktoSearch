package com.tracktosearch.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaItemDao {

    @Query("SELECT * FROM media_items WHERE type = :type ORDER BY listedAt DESC")
    fun getByType(type: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE type = :type ORDER BY listedAt DESC")
    suspend fun getByTypeList(type: String): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_items WHERE type = :type")
    suspend fun countByType(type: String): Int

    @Query("SELECT COUNT(*) FROM media_details")
    suspend fun countDetails(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItemEntity>)

    @Query("DELETE FROM media_items WHERE type = :type")
    suspend fun deleteByType(type: String)

    @Query("DELETE FROM media_items WHERE type = :type AND traktId = :traktId")
    suspend fun deleteItem(type: String, traktId: Int)
}

@Dao
interface MediaDetailDao {

    @Query("SELECT * FROM media_details WHERE traktId = :traktId")
    suspend fun getByTraktId(traktId: Int): MediaDetailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(detail: MediaDetailEntity)

    @Query("DELETE FROM media_details WHERE traktId = :traktId")
    suspend fun delete(traktId: Int)

    @Query("DELETE FROM media_details")
    suspend fun clearAll()
}

@Dao
interface NotificationRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: NotificationRecordEntity): Long

    @Query("SELECT * FROM notification_records WHERE traktId = :traktId AND type = :type AND payload = :payload LIMIT 1")
    suspend fun find(traktId: Int, type: String, payload: String): NotificationRecordEntity?

    @Query("SELECT * FROM notification_records WHERE traktId = :traktId AND type = :type")
    suspend fun findByTraktId(traktId: Int, type: String): List<NotificationRecordEntity>

    @Query("DELETE FROM notification_records WHERE notifiedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM notification_records")
    suspend fun clearAll()
}
