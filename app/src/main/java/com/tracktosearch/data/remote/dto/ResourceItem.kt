package com.tracktosearch.data.remote.dto

enum class DiskType {
    QUARK, BAIDU, ALI, XUNLEI, UC, ONEONEFIVE, OTHER
}

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
    /** 资源可能失效（基于 status 字段判断） */
    val isInvalid: Boolean get() = status.equals("fail", ignoreCase = true)
            || status.equals("expired", ignoreCase = true)
            || status.equals("invalid", ignoreCase = true)
}
