package com.tracktosearch.data.remote.douban.dto

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
data class DoubanChartResponse(
    val code: Int = -1,
    val data: List<DoubanChartItem> = emptyList(),
    val total: Int = 0,
    val cached: Boolean = false,
    val updatedAt: String = ""
)

@Serializable
data class DoubanNowPlayingResponse(
    val code: Int = -1,
    val data: List<DoubanNowPlayingItem> = emptyList(),
    val total: Int = 0,
    val cached: Boolean = false,
    val updatedAt: String = ""
)

@Serializable
data class DoubanTop250Response(
    val code: Int = -1,
    val data: List<DoubanTop250Item> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 25,
    val cached: Boolean = false,
    val updatedAt: String = ""
)

@Immutable
@Serializable
data class DoubanChartItem(
    val rank: Int = 0,
    val title: String = "",
    val subtitle: String = "",
    val id: String = "",
    val url: String = "",
    val poster: String = "",
    val rating: String = "",
    val ratingCount: String = "",
    val tmdbId: Int = 0,
    val traktId: Int = 0,
    val imdbId: String = "",
    val mediaType: String = "movie"
)

@Immutable
@Serializable
data class DoubanNowPlayingItem(
    val title: String = "",
    val id: String = "",
    val url: String = "",
    val poster: String = "",
    val rating: String = "",
    val ratingCount: String = "",
    val release: String = "",
    val duration: String = "",
    val region: String = "",
    val director: String = "",
    val actors: String = "",
    val tmdbId: Int = 0,
    val traktId: Int = 0,
    val imdbId: String = "",
    val mediaType: String = "movie"
)

@Immutable
@Serializable
data class DoubanTop250Item(
    val rank: Int = 0,
    val title: String = "",
    val otherTitle: String = "",
    val id: String = "",
    val url: String = "",
    val poster: String = "",
    val rating: String = "",
    val ratingCount: String = "",
    val director: String = "",
    val year: String = "",
    val region: String = "",
    val quote: String = "",
    val tmdbId: Int = 0,
    val traktId: Int = 0,
    val imdbId: String = "",
    val mediaType: String = "movie"
)

/** 统一的豆瓣热榜条目，供 UI 层使用 */
@Immutable
@Serializable
data class DoubanHotItem(
    val id: Int? = null,
    val title: String = "",
    val cover: String? = null,
    val desc: String = "",
    val rating: String = "",
    val url: String = "",
    val tmdbId: Int = 0,
    val traktId: Int = 0,
    val imdbId: String = "",
    val mediaType: String = "movie"
)

// ==================== Rexxar subject_collection 接口（App 直连豆瓣移动端） ====================

/** Rexxar 榜单集合页响应：subject_collection_items 即榜单条目列表。 */
@Immutable
@Serializable
data class DoubanRexxarCollectionPage(
    val start: Int = 0,
    val count: Int = 0,
    val total: Int = 0,
    val subject_collection_items: List<DoubanRexxarCollectionItem> = emptyList()
)

/** Rexxar 榜单条目：含豆瓣海报与评分（rating.value/count）。 */
@Immutable
@Serializable
data class DoubanRexxarCollectionItem(
    val id: String = "",
    val title: String = "",
    /** 豆瓣海报（中等尺寸 m_ratio_poster）。新片榜/热门/Top250 用 cover 对象，口碑榜用 cover_url。 */
    val cover_url: String? = null,
    val cover: DoubanRexxarCover? = null,
    val pic: DoubanRexxarPic? = null,
    val rating: DoubanRexxarRating? = null,
    val url: String = "",
    val sharing_url: String = "",
    val subtype: String = "movie"
)

@Immutable
@Serializable
data class DoubanRexxarCover(
    val url: String? = null
)

@Immutable
@Serializable
data class DoubanRexxarPic(
    val large: String? = null,
    val normal: String? = null
)

@Immutable
@Serializable
data class DoubanRexxarRating(
    val count: Int = 0,
    val value: Double = 0.0
)

/**
 * Rexxar 榜单条目 → 统一热榜条目。
 * 评分直接用接口 rating.value（豆瓣海报优先取 pic.large）；标题不再内嵌评分。
 * tmdbId/traktId/imdbId 由预解析链路填充（Rexxar 不含这些字段）。
 */
fun DoubanRexxarCollectionItem.toDoubanHotItem(): DoubanHotItem = DoubanHotItem(
    id = id.hashCode(),
    title = title,
    cover = pic?.large ?: cover?.url ?: cover_url,
    desc = rating?.count?.takeIf { it > 0 }?.let { it.toString() + "人评价" } ?: "",
    rating = rating?.value?.takeIf { it > 0 }?.let { String.format("%.1f", it) } ?: "暂无评分",
    url = url.ifBlank { sharing_url },
    mediaType = if (subtype == "tv") "tv" else "movie"
)

/** 兼容旧的 DoubanHotResponse（如有引用） */
@Serializable
data class DoubanHotResponse(
    val code: Int = -1,
    val message: String = "",
    val data: DoubanHotData = DoubanHotData()
)

@Serializable
data class DoubanHotData(
    val category: String = "",
    val items: List<DoubanHotItem> = emptyList(),
    val hasMore: Boolean = false,
    val page: Int = 1,
    val limit: Int = 25,
    val total: Int = 0
)
