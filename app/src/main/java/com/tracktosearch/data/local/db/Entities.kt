package com.tracktosearch.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 想看/已看列表的离线缓存实体
 * type: "watchlist_movie" / "watchlist_show" / "history_movie" / "history_show"
 */
@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey
    val traktId: Int,
    val tmdbId: Int,
    val type: String,          // watchlist_movie / watchlist_show / history_movie / history_show
    val title: String,
    val displayTitle: String,
    val year: Int?,
    val genres: String,
    val posterUrl: String?,
    val imdbId: String,
    val traktRating: Double,
    val listedAt: String,
    val cachedAt: Long = System.currentTimeMillis()
)

/**
 * 详情页基本信息的离线缓存实体
 */
@Entity(tableName = "media_details")
data class MediaDetailEntity(
    @PrimaryKey
    val traktId: Int,
    val tmdbId: Int,
    val mediaType: String,     // movie / show
    val title: String,
    val displayTitle: String,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: Int?,
    val genres: String,
    val rating: Double,
    val runtime: Int?,
    val releaseDate: String,
    val cachedAt: Long = System.currentTimeMillis()
)

/**
 * 通知记录实体：跟踪已发送的通知，避免重复推送
 * type: "release" (上映) / "new_season" (新季)
 */
@Entity(tableName = "notification_records")
data class NotificationRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val traktId: Int,
    val tmdbId: Int,
    val mediaType: String,    // movie / show
    val title: String,
    val type: String,         // release / new_season
    val payload: String,      // 附加信息（如上映日期、季数）
    val notifiedAt: Long = System.currentTimeMillis()
)
