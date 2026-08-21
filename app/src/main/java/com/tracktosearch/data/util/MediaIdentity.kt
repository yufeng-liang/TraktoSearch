package com.tracktosearch.data.util

/**
 * 跨数据源（Trakt / 豆瓣）影视条目去重用的多标识集合。
 *
 * 按「单一优选 key」去重会漏判：豆瓣条目可能只有 traktId，而 Trakt 侧同一部影视
 * 优选 imdb 作为 key，两边永远对不上，同一部影视会被重复计数。
 * 这里为每条记录收集**全部**可用标识，任一标识相交即认为是同一部影视。
 */
object MediaIdentity {

    /**
     * 为一条记录构建全部可用标识。空白、非正值的标识会被丢弃。
     *
     * 标题只在同时有年份时才参与匹配，避免同名不同片被误判为同一部；
     * 跨源标题语言通常不同（Trakt 英文、豆瓣中文），该维度实际只在两边都富化过时生效。
     */
    fun keysOf(
        imdbId: String? = null,
        traktId: Int? = null,
        tmdbId: Int? = null,
        titles: List<String?> = emptyList(),
        year: Int? = null
    ): Set<String> {
        val keys = mutableSetOf<String>()
        imdbId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { keys += "imdb:$it" }
        traktId?.takeIf { it > 0 }?.let { keys += "trakt:$it" }
        tmdbId?.takeIf { it > 0 }?.let { keys += "tmdb:$it" }
        if (year != null && year > 0) {
            titles.forEach { title ->
                title?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { keys += "title:$it:$year" }
            }
        }
        return keys
    }

    /**
     * 判断某条记录的标识集合是否已被 [known] 覆盖（即已在另一数据源中出现过）。
     * 标识为空时视为无法比对，按「未出现」处理，避免无标识条目被全部合并掉。
     */
    fun isKnown(keys: Set<String>, known: Set<String>): Boolean =
        keys.isNotEmpty() && keys.any { it in known }
}
