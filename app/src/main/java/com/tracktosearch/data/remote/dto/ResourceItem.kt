package com.tracktosearch.data.remote.dto

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class DiskType {
    QUARK, BAIDU, ALI, XUNLEI, UC, ONEONEFIVE, MAGNET, OTHER
}

/** 影视类型筛选 */
enum class ResourceType {
    ALL, MOVIE, SHOW
}

/**
 * 根据资源名称推断影视类型（电影/电视剧）。
 * 无明确类型字段时，从标题中的关键词和季集信息推断。
 */
private val SHOW_PATTERNS = listOf(
    Regex("第\\s*\\d+\\s*季"),
    Regex("第\\s*\\d+\\s*集"),
    Regex("全\\s*\\d+\\s*集"),
    Regex("\\d+\\s*[-~]\\s*\\d+\\s*季"),
    Regex("s\\d{1,2}\\s*e\\d{1,2}"),
    Regex("(?<![a-z])s\\d{1,2}\\b"),
    Regex("(?<![a-z])e\\d{1,2}\\b"),
    Regex("(?<![a-z])ep\\.?\\d")
)

fun inferResourceType(name: String): ResourceType {
    val n = name.lowercase()
    val isShow = n.contains("电视剧") ||
        n.contains("连续剧") ||
        n.contains("剧集") ||
        n.contains("短剧") ||
        n.contains("完结") ||
        n.contains("更新至") ||
        n.contains("全季") ||
        n.contains("season") ||
        SHOW_PATTERNS.any { it.containsMatchIn(n) }
    return if (isShow) ResourceType.SHOW else ResourceType.MOVIE
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
