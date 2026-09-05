package com.tracktosearch.ui.navigation

import com.tracktosearch.ui.component.SharedOrigin

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
 * 同一次点击还会记下 [Seed.origin]，即「点的是哪个列表里的哪一张卡」。详情页据此拼出
 * 与来源侧完全相同的共享元素 key，海报转场因此只会认被点击的那一张卡片，
 * 不需要活跃 id、点击 token 一类的全局运行期状态。
 *
 * 只在进程内有效、容量上限 [MAX_ENTRIES]，只存三个小字段，不做持久化：
 * 种子丢了最坏情况是退回「等详情接口」加「无海报转场」，没有正确性影响。
 */
object DetailSeedStore {

    private const val MAX_ENTRIES = 300

    data class Seed(
        val posterUrl: String?,
        val year: Int?,
        val origin: String = SharedOrigin.ANY,
    )

    private val seeds = object : LinkedHashMap<Int, Seed>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Seed>?): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * 记录一条种子；tmdbId 无效或三个字段都无信息时不占用容量。
     *
     * [origin] 每次都覆盖，它表达的是「最近一次点击来自哪里」；海报与年份缺省时保留旧值，
     * 这样只为记录来源而写入的调用不会把先前拿到的海报抹掉。
     */
    fun remember(
        tmdbId: Int,
        posterUrl: String? = null,
        year: Int? = null,
        origin: String = SharedOrigin.ANY,
    ) {
        if (tmdbId <= 0) return
        if (posterUrl.isNullOrBlank() && year == null && origin == SharedOrigin.ANY) return
        synchronized(seeds) {
            val previous = seeds[tmdbId]
            seeds[tmdbId] = Seed(
                posterUrl = posterUrl?.takeIf { it.isNotBlank() } ?: previous?.posterUrl,
                year = year ?: previous?.year,
                origin = origin,
            )
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
