package com.tracktosearch.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserReviewDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserReviewEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<UserReviewEntity>)

    @Query("SELECT * FROM user_review WHERE traktId = :traktId")
    suspend fun getByTraktId(traktId: Long): UserReviewEntity?

    @Query("SELECT * FROM user_review")
    suspend fun getAll(): List<UserReviewEntity>

    @Query("SELECT * FROM user_review WHERE mediaType = :mediaType")
    suspend fun getByMediaType(mediaType: String): List<UserReviewEntity>

    @Query("DELETE FROM user_review WHERE traktId = :traktId")
    suspend fun deleteByTraktId(traktId: Long)

    @Query("DELETE FROM user_review")
    suspend fun clear()
}
