package com.tracktosearch.data.remote.pansou.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
