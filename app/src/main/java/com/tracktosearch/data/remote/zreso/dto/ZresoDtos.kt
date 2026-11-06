package com.tracktosearch.data.remote.zreso.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ZresoResponse(
    val ads: List<JsonElement> = emptyList(),
    val data: ZresoData = ZresoData()
)

@Serializable
data class ZresoData(
    val total: Int = 0,
    val results: List<ZresoResult> = emptyList()
)

@Serializable
data class ZresoResult(
    val title: String = "",
    val datetime: String = "",
    val date: String = "",
    val tags: List<String>? = null,
    val links: List<ZresoLink> = emptyList(),
    val first_url: String = "",
    val cloud_type_name: String = "",
    val status: String = ""
)

@Serializable
data class ZresoLink(
    val type: String = "",
    val url: String = "",
    val status: String = ""
)

/**
 * zreso 中转链接解析结果：`GET /api/wash?t=<token>` → `{"ok":true,"raw_url":"https://pan.quark.cn/s/..."}`。
 */
@Serializable
data class ZresoWashResponse(
    val ok: Boolean = false,
    @SerialName("raw_url") val rawUrl: String = "",
    val msg: String = ""
)
