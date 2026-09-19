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

    // 电影/剧集 traktId 分属不同命名空间，按 key 查询必须带 mediaType
    @Query("SELECT * FROM user_review WHERE traktId = :traktId AND mediaType = :mediaType")
    suspend fun getByKey(traktId: Long, mediaType: String): UserReviewEntity?

    @Query("SELECT * FROM user_review")
    suspend fun getAll(): List<UserReviewEntity>

    @Query("SELECT * FROM user_review WHERE mediaType = :mediaType")
    suspend fun getByMediaType(mediaType: String): List<UserReviewEntity>

    @Query("DELETE FROM user_review WHERE traktId = :traktId AND mediaType = :mediaType")
    suspend fun deleteByKey(traktId: Long, mediaType: String)

    @Query("DELETE FROM user_review")
    suspend fun clear()
}
