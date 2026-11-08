package com.tracktosearch.data.remote.baoys.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 宝藏郎（baoys.baozang.asia）搜索响应。
 * 实测（2026-11）：`GET /api?keyword=` 免鉴权，返回 `{"results":[...]}`；
 * 响应慢（约 10-25 秒），单次返回几十条。
 */
@Serializable
data class BaoysResponse(
    val results: List<BaoysItem> = emptyList()
)

/**
 * 单条结果。只有网盘中文名 + 标题 + 直链，无日期/大小/状态字段；
 * `cloud_name` 取值如「夸克网盘」「阿里云盘」「PikPak」。
 */
@Serializable
data class BaoysItem(
    @SerialName("cloud_name") val cloudName: String = "",
    val name: String = "",
    @SerialName("share_link") val shareLink: String = "",
    val source: String = ""
)
