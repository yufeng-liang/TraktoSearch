package com.tracktosearch.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 公开影视元数据本地镜像。
 *
 * 一行对应一个 `(mediaType, tmdbId, locale)`，摘要与详情共用同一行，
 * 避免列表页和详情页各存一份标题/海报导致相互覆盖。
 */
@Entity(
    tableName = "media_metadata",
    indices = [
        Index(value = ["tmdbId"]),
        Index(value = ["updatedAt"])
    ]
)
data class MediaMetadataEntity(
    @PrimaryKey
    val mediaKey: String,
    val mediaType: String,
    val tmdbId: Int,
    val locale: String,
    val summaryJson: String? = null,
    val detailJson: String? = null,
    val schemaVersion: Int = 0,
    val summaryRefreshedAt: Long = 0L,
    val detailRefreshedAt: Long = 0L,
    val updatedAt: Long = 0L
)
