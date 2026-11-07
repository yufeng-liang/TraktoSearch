package com.tracktosearch.data.remote.pansou.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PanSouResponse(
    val code: Int = 0,
    val message: String = "",
    val data: PanSouData? = null
)

@Serializable
data class PanSouData(
    val total: Int = 0,
    val merged_by_type: Map<String, List<PanSouMergedLink>> = emptyMap()
)

@Serializable
data class PanSouMergedLink(
    val url: String = "",
    val password: String = "",
    val note: String = "",
    val datetime: String = "",
    val source: String = "",
    val images: List<String> = emptyList()
)

/**
 * PanSou 系站点（so.252035.xyz、pan.useai.sbs）的链接有效性检测请求体。
 * 服务端限制单次最多 256 条，且内部串行检测，App 侧只按单条卡片触发。
 */
@Serializable
data class PanSouCheckRequest(
    val items: List<PanSouCheckItem> = emptyList()
)

@Serializable
data class PanSouCheckItem(
    @SerialName("disk_type") val diskType: String,
    val url: String,
    val password: String = ""
)

@Serializable
data class PanSouCheckResponse(
    val results: List<PanSouCheckResult> = emptyList()
)

@Serializable
data class PanSouCheckResult(
    @SerialName("disk_type") val diskType: String = "",
    val url: String = "",
    @SerialName("normalized_url") val normalizedUrl: String = "",
    /** idle / pending / ok / bad / locked / unsupported / uncertain */
    val state: String = "",
    @SerialName("cache_hit") val cacheHit: Boolean = false,
    @SerialName("checked_at") val checkedAt: Long = 0,
    @SerialName("expires_at") val expiresAt: Long = 0,
    val summary: String = ""
)
