package com.tracktosearch.data.util

import com.tracktosearch.data.remote.tmdb.TmdbImageUrls

/**
 * 海报主色缓存使用过的 TMDB 尺寸集合。
 *
 * 同一张海报在列表页与详情页可能分别用 w342 / w780 写入，缓存 key 因此不一致；
 * 读取时需要把所有尺寸都试一遍。
 */
private val POSTER_CACHE_SIZES = listOf(
    TmdbImageUrls.W185,
    TmdbImageUrls.W342,
    TmdbImageUrls.W500,
    TmdbImageUrls.W780,
    TmdbImageUrls.H632
)

/**
 * 把一张海报的 URL 展开成它在主色缓存里可能出现的全部 key。
 *
 * - TMDB 图片：按已知尺寸逐个替换路径里的尺寸段
 * - 其它完整 URL（Trakt / 豆瓣等）：原样一个 key
 * - TMDB 相对路径：补齐成各尺寸的完整 URL
 */
fun posterCacheKeyCandidates(path: String): List<String> {
    val value = path.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
    val tmdbPattern = Regex("^(https?://[^/]+/.*?/t/p/)([^/]+)(/.*)$")
    val match = tmdbPattern.matchEntire(value)
    if (match != null) {
        return POSTER_CACHE_SIZES.map { size ->
            val sizeName = size.substringAfterLast('/')
            "${match.groupValues[1]}$sizeName${match.groupValues[3]}"
        }
    }
    if (value.startsWith("http", ignoreCase = true)) return listOf(value)
    return POSTER_CACHE_SIZES.map { size -> TmdbImageUrls.build(value, size) }
}
