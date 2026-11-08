package com.tracktosearch.data.remote.melost.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** melost（影盘社，melost.cn）搜索请求体 */
@Serializable
data class MelostSearchRequest(
    val q: String,
    val page: Int = 1,
    val size: Int = 30
)

/**
 * melost 搜索响应。
 * 实测（2026-11）：`POST /v1/search/disk` → `{"code":200,"data":{"total":N,"list":[...]}}`。
 */
@Serializable
data class MelostSearchResponse(
    val code: Int = 0,
    val data: MelostData? = null
)

@Serializable
data class MelostData(
    val total: Int = 0,
    val list: List<MelostItem> = emptyList()
)

/**
 * 单条网盘分享。`disk_name` 常带 `<em>` 搜索高亮标签需清洗；
 * `files` 是多行文本，每行以 `file:` 开头，可用来数文件数；
 * `disk_type` 取值 QUARK / UC（站点以这两家为主）。
 */
@Serializable
data class MelostItem(
    @SerialName("disk_id") val diskId: String = "",
    @SerialName("disk_name") val diskName: String = "",
    @SerialName("disk_pass") val diskPass: String = "",
    @SerialName("disk_type") val diskType: String = "",
    val files: String = "",
    val link: String = "",
    @SerialName("shared_time") val sharedTime: String = "",
    val enabled: Boolean = true,
    val status: Int = 0,
    val size: Long = 0
)
