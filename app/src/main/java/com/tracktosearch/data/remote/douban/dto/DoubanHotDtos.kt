package com.tracktosearch.data.remote.douban.dto

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
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
    val ratingCount: String = ""
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
    val actors: String = ""
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
    val quote: String = ""
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
    val url: String = ""
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
