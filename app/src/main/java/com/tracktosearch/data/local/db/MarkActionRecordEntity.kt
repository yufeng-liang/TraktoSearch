package com.tracktosearch.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 标记操作流水实体。
 * 记录 App 内发生的「加想看/移除想看/取消已看」操作（已看不记录，走 Trakt /sync/history）。
 */
@Entity(
    tableName = "mark_action_record",
    indices = [
        Index("actionType"),
        Index("actedAt"),
        Index("mediaType"),
        Index("traktId"),
        Index(value = ["actionType", "actedAt"]),
        Index(value = ["mediaType", "actedAt"]),
        Index(value = ["actionType", "mediaType", "actedAt"])
    ]
)
data class MarkActionRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val traktId: Int,
    val tmdbId: Int,
    val imdbId: String,
    val mediaType: String,         // "movie" / "show"
    val title: String,             // 快照，避免缓存失效时列表变空白
    val displayTitle: String,
    val posterUrl: String?,
    val year: Int?,
    val actionType: String,        // ADD_WATCHLIST / REMOVE_WATCHLIST / UNMARK_WATCHED
    val actedAt: Long,             // 操作时间戳（毫秒）
    val episodeInfo: String?       // "S01E03" / "S01E03-E05"，仅取消单集已看时填
)

/** 操作类型枚举 */
enum class MarkActionType(val value: String) {
    ADD_WATCHLIST("ADD_WATCHLIST"),
    REMOVE_WATCHLIST("REMOVE_WATCHLIST"),
    UNMARK_WATCHED("UNMARK_WATCHED");

    companion object {
        fun fromValue(v: String) = entries.firstOrNull { it.value == v }
    }
}
