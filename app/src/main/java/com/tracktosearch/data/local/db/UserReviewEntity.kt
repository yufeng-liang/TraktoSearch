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
// 主键必须含 (traktId, mediaType)：Trakt 电影/剧集 ID 分属不同命名空间可能同号，
// 裸 traktId 主键会让两边的评分/短评互相覆盖，且 traktCommentId 会被张冠李戴
// （编辑短评时可能改到另一部作品的 Trakt 线上评论），见 MIGRATION_19_20。
@Entity(
    tableName = "user_review",
    primaryKeys = ["traktId", "mediaType"],
    indices = [Index("mediaType"), Index("tmdbId"), Index("imdbId")]
)
data class UserReviewEntity(
    val traktId: Long,                      // Trakt 影视 ID（与 mediaType 组成复合主键）
    val tmdbId: Int?,                       // TMDB ID（可选）
    val imdbId: String?,                    // IMDB ID（可选）
    val mediaType: String,                  // "movie" / "show"（复合主键之一）
    val title: String?,                     // 影视标题
    val year: Int?,                        // 上映/首播年份
    val rating: Float?,                     // Trakt 评分 1-10
    val comment: String?,                   // 短评文本
    val traktCommentId: Int? = null,        // Trakt 短评 ID（编辑时使用）
    val commentCheckedAt: Long? = null,     // 上次校准本人短评缓存的时间（毫秒）
    val liked: Boolean?,                    // 是否点赞（Trakt like）
    val createdAt: Long?,                   // Trakt 创建时间（毫秒）
    val updatedAt: Long?,                   // Trakt 更新时间（毫秒）
    val syncedAt: Long = System.currentTimeMillis() // 本地写盘时间
)
