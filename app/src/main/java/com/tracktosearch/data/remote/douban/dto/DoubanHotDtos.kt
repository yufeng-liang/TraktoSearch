package com.tracktosearch.data.remote.douban.dto

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

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

@Immutable
@Serializable
data class DoubanHotItem(
    val id: Int? = null,
    val title: String = "",
    val cover: String? = null,
    val desc: String = "",
    val hot: Int? = null,
    val url: String = ""
)
