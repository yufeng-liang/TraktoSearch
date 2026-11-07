package com.tracktosearch.data.remote.ataw.dto

import kotlinx.serialization.Serializable

/**
 * 阿涛搜（so.ataw.top）站内搜索响应。
 *
 * 只声明本项目用得到的字段（全局 Json 已开 ignoreUnknownKeys），
 * 服务端还会返回 searchId / platformCounts / tracking 等调试信息，一律忽略。
 */
@Serializable
data class AtawSearchResponse(
    val items: List<AtawItem> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
    val degraded: Boolean = false
)

/**
 * 单条资源。
 *
 * 注意：[link] 可能是空串（部分条目把推广链接塞在 description 里），真实可用的网盘地址
 * 应以 [links] 为准；[mainPlatform] 在不指定 platform 参数查询时为 null。
 */
@Serializable
data class AtawItem(
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    val link: String = "",
    val size: String = "",
    val tags: String = "",
    val submitTime: String = "",
    val botBiz: String = "",
    val mainPlatform: String? = null,
    val platforms: List<String> = emptyList(),
    val links: List<AtawLink> = emptyList(),
    val filePreview: AtawFilePreview? = null,
    /** 站点侧已删除/失效标记；true 时卡片展示「已失效」徽章。 */
    val isDeleted: Boolean = false
)

@Serializable
data class AtawLink(
    val platform: String = "",
    val url: String = ""
)

@Serializable
data class AtawFilePreview(
    val fileCount: Int = 0,
    val truncated: Boolean = false
)
