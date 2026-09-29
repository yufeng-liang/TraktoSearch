package com.tracktosearch.data.remote.dto

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class DiskType {
    QUARK, BAIDU, ALI, XUNLEI, UC, ONEONEFIVE, MAGNET, OTHER
}

@Immutable
@Serializable
data class ResourceItem(
    val name: String,
    val diskType: DiskType,
    val fileSize: String,
    val fileDate: String = "",
    val fileCount: Int = 0,
    val status: String = "",
    val url: String,
    val source: String
) {
    /** 资源可能失效（基于 status 字段或 fileCount 判断） */
    val isInvalid: Boolean get() = status.equals("fail", ignoreCase = true)
            || status.equals("expired", ignoreCase = true)
            || status.equals("invalid", ignoreCase = true)
            || fileCount == 0
}
