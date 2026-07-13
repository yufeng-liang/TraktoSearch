package com.tracktosearch.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户对某影视的评分+短评本地缓存实体。
 *
 * 用于:
 * - 详情页优先读取本地评分/短评，避免每次进入都请求 Trakt
 * - 后续"短评词云"功能复用本表做词频统计
 */
@Entity(
    tableName = "user_review",
    indices = [Index("mediaType"), Index("tmdbId"), Index("imdbId")]
)
data class UserReviewEntity(
    @PrimaryKey val traktId: Long,          // Trakt 影视 ID（主键）
    val tmdbId: Int?,                       // TMDB ID（可选）
    val imdbId: String?,                    // IMDB ID（可选）
    val mediaType: String,                  // "movie" / "show"
    val title: String?,                     // 影视标题
    val year: Int?,                        // 上映/首播年份
    val rating: Float?,                     // Trakt 评分 1-10
    val comment: String?,                   // 短评文本
    val liked: Boolean?,                    // 是否点赞（Trakt like）
    val createdAt: Long?,                   // Trakt 创建时间（毫秒）
    val updatedAt: Long?,                   // Trakt 更新时间（毫秒）
    val syncedAt: Long = System.currentTimeMillis() // 本地写盘时间
)
