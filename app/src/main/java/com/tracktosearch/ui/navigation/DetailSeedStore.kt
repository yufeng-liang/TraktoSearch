package com.tracktosearch.ui.navigation

/**
 * 详情页首帧种子的进程内暂存。
 *
 * 解决的问题：详情页靠同步 peek TMDB 内存缓存来实现「第一帧就有海报」，
 * 但发现页/榜单这些栏目的卡片海报来自 TMDB **列表接口**的 poster_path
 * （`TmdbSearchResult`），列表接口不会写入 movieDetailCache。
 * 也就是说从这些入口进详情页时 peek 必然落空，海报只能等详情接口回来才出现。
 *
 * 列表卡片被点击时把它已经渲染出来的海报和年份记在这里，详情页 peek 落空时兜底读取，
 * 于是所有走 [com.tracktosearch.ui.component.MovieCard] 的入口都能首帧直出海报。
 *
 * 只在进程内有效、容量上限 [MAX_ENTRIES]，仅存两个可空的小字段，不做持久化：
 * 种子丢了最坏情况就是退回原来的「等详情接口」行为，没有正确性影响。
 */
object DetailSeedStore {

    private const val MAX_ENTRIES = 300

    data class Seed(val posterUrl: String?, val year: Int?)

    private val seeds = object : LinkedHashMap<Int, Seed>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Seed>?): Boolean =
            size > MAX_ENTRIES
    }

    /** 记录一条种子；tmdbId 无效或海报与年份都为空时不占用容量。 */
    fun remember(tmdbId: Int, posterUrl: String?, year: Int?) {
        if (tmdbId <= 0) return
        if (posterUrl.isNullOrBlank() && year == null) return
        synchronized(seeds) {
            seeds[tmdbId] = Seed(posterUrl?.takeIf { it.isNotBlank() }, year)
        }
    }

    fun peek(tmdbId: Int): Seed? {
        if (tmdbId <= 0) return null
        return synchronized(seeds) { seeds[tmdbId] }
    }

    /** 测试用：清空暂存。 */
    fun clear() {
        synchronized(seeds) { seeds.clear() }
    }
}
