package com.tracktosearch.ui.screen.discoverfilter

import android.content.Context
import com.tracktosearch.R
import java.util.Locale

/**
 * TMDB Discover 筛选预设数据
 *
 * genre ID 来源：TMDB 官方 genre 电影/电视剧列表
 * 地区代码：ISO 3166-1 alpha-2
 * 关键词 ID 来源：TMDB keywords 搜索（如漫威、DC、超级英雄等）
 *
 * 显示名一律走字符串资源或系统的地区本地化名，不再硬编码中文——之前英/日/韩用户在筛选页
 * 看到的是一片中文标签。地区名默认取 Locale.getDisplayCountry（系统已按当前语言给出译名），
 * 只有中国大陆/香港/台湾三条用资源覆盖，保持与项目其他文案一致的写法。
 */
object DiscoverFilterConstants {

    /** 电影类型（TMDB genre ID → 显示名资源） */
    val MOVIE_GENRES: List<Pair<Int, Int>> = listOf(
        28 to R.string.filter_genre_action,
        12 to R.string.filter_genre_adventure,
        16 to R.string.filter_genre_animation,
        35 to R.string.filter_genre_comedy,
        80 to R.string.filter_genre_crime,
        99 to R.string.filter_genre_documentary,
        18 to R.string.filter_genre_drama,
        10751 to R.string.filter_genre_family,
        14 to R.string.filter_genre_fantasy,
        36 to R.string.filter_genre_history,
        27 to R.string.filter_genre_horror,
        10402 to R.string.filter_genre_music,
        9648 to R.string.filter_genre_mystery,
        10749 to R.string.filter_genre_romance,
        878 to R.string.filter_genre_sci_fi,
        10770 to R.string.filter_genre_tv_movie,
        53 to R.string.filter_genre_thriller,
        10752 to R.string.filter_genre_war,
        37 to R.string.filter_genre_western
    )

    /** 电视剧类型（TMDB genre ID → 显示名资源） */
    val TV_GENRES: List<Pair<Int, Int>> = listOf(
        10759 to R.string.filter_genre_action_adventure,
        16 to R.string.filter_genre_animation,
        35 to R.string.filter_genre_comedy,
        80 to R.string.filter_genre_crime,
        99 to R.string.filter_genre_documentary,
        18 to R.string.filter_genre_drama,
        10751 to R.string.filter_genre_family,
        10762 to R.string.filter_genre_kids,
        9648 to R.string.filter_genre_mystery,
        10763 to R.string.filter_genre_news,
        10764 to R.string.filter_genre_reality,
        10765 to R.string.filter_genre_sci_fi_fantasy,
        10766 to R.string.filter_genre_soap,
        10767 to R.string.filter_genre_talk,
        10768 to R.string.filter_genre_war_politics,
        37 to R.string.filter_genre_western
    )

    /** 预设地区（ISO 3166-1 alpha-2，显示名见 [regionName]） */
    val REGION_CODES: List<String> = listOf(
        "CN", "HK", "TW", "JP", "KR", "IN", "TH", "ID", "PH", "VN", "MY", "SG",
        "US", "CA", "GB", "IE", "AU", "NZ",
        "FR", "DE", "IT", "ES", "PT", "NL", "BE", "CH", "AT",
        "SE", "NO", "DK", "FI", "PL", "CZ", "HU", "RU", "UA",
        "TR", "GR", "IL", "AE", "SA", "IR", "EG", "NG", "ZA",
        "MX", "BR", "AR", "CL", "CO"
    )

    /** 需要用资源覆盖系统译名的地区 */
    private val REGION_LABEL_OVERRIDES: Map<String, Int> = mapOf(
        "CN" to R.string.filter_region_cn,
        "HK" to R.string.filter_region_hk,
        "TW" to R.string.filter_region_tw
    )

    /**
     * 预设标签（TMDB keyword ID → 显示名资源）
     *
     * TMDB 没有"热门标签"列表 API，这里用常见影视主题的关键词 ID 实现。
     * 标签筛选通过 with_keywords 参数（OR 逻辑）传递给 Discover API。
     */
    val TAGS: List<Pair<Int, Int>> = listOf(
        180547 to R.string.filter_tag_marvel,
        2076 to R.string.filter_tag_dc,
        9716 to R.string.filter_tag_superhero,
        162355 to R.string.filter_tag_campus,
        9840 to R.string.filter_tag_zombie,
        9937 to R.string.filter_tag_vampire,
        1251 to R.string.filter_tag_undead,
        182985 to R.string.filter_tag_apocalypse,
        312 to R.string.filter_tag_revenge,
        11183 to R.string.filter_tag_time_travel,
        9826 to R.string.filter_tag_alien,
        9717 to R.string.filter_tag_dystopia,
        207820 to R.string.filter_tag_cyberpunk,
        162349 to R.string.filter_tag_space,
        6152 to R.string.filter_tag_war,
        9951 to R.string.filter_tag_spy,
        13084 to R.string.filter_tag_gangster,
        9837 to R.string.filter_tag_whodunit,
        4344 to R.string.filter_tag_musical,
        15245 to R.string.filter_tag_dance,
        6155 to R.string.filter_tag_sports,
        1632 to R.string.filter_tag_food,
        6054 to R.string.filter_tag_friendship,
        9830 to R.string.filter_tag_love_story,
        11121 to R.string.filter_tag_coming_of_age,
        12552 to R.string.filter_tag_road_trip,
        9799 to R.string.filter_tag_wilderness,
        314 to R.string.filter_tag_remake,
        13085 to R.string.filter_tag_sequel,
        1612 to R.string.filter_tag_martial_arts,
        10188 to R.string.filter_tag_kung_fu,
        3340 to R.string.filter_tag_magic,
        2075 to R.string.filter_tag_christmas,
        9817 to R.string.filter_tag_kidnapping,
        610 to R.string.filter_tag_dog,
        162353 to R.string.filter_tag_cat,
        11456 to R.string.filter_tag_ai,
        9718 to R.string.filter_tag_fairy_tale,
        11096 to R.string.filter_tag_police,
        6084 to R.string.filter_tag_detective,
        15244 to R.string.filter_tag_docudrama,
        2092 to R.string.filter_tag_hacker,
        9814 to R.string.filter_tag_drug_trade,
        14565 to R.string.filter_tag_medieval,
        208765 to R.string.filter_tag_steampunk,
        9914 to R.string.filter_tag_psychological,
        9823 to R.string.filter_tag_monster,
        9934 to R.string.filter_tag_ghost
    )

    /**
     * 年代选项（value = 起始年份..结束年份）
     *
     * - specialLabelRes != null：使用该字符串资源作为 label（用于"全部"、"更早"）
     * - specialLabelRes == null：用 decade_format 格式化 startYear 作为 label（如"2020年代"）
     *
     * 注意：TMDB Discover 的日期筛选是连续范围（gte/lte），多选不连续年代时
     * 会合并为最小起始到最大结束的连续范围。
     */
    data class DecadeOption(
        val startYear: Int,
        val endYear: Int,
        val specialLabelRes: Int? = null
    ) {
        /** 唯一标识符，用于选中状态存储 */
        val key: String get() = "$startYear-$endYear"
        /** 是否为"全部"选项（清空筛选） */
        val isAll: Boolean get() = startYear == 0 && endYear == 0
    }

    /** 生成年代选项列表，"今年"动态取当前年份 */
    fun decadeOptions(currentYear: Int): List<DecadeOption> {
        val list = mutableListOf<DecadeOption>()
        list.add(DecadeOption(0, 0, specialLabelRes = R.string.discover_filter_decade_all))
        // 2026、2025、2024、2023、2022、2021、2020、2019
        for (y in currentYear downTo 2019) {
            list.add(DecadeOption(y, y))
        }
        list.add(DecadeOption(2010, 2019))
        list.add(DecadeOption(2000, 2009))
        list.add(DecadeOption(1990, 1999))
        list.add(DecadeOption(1980, 1989))
        list.add(DecadeOption(1970, 1979))
        list.add(DecadeOption(1960, 1969))
        list.add(DecadeOption(0, 1959, specialLabelRes = R.string.discover_filter_decade_earlier))
        return list
    }

    /** 地区显示名：优先用资源覆盖，其余交给系统按当前语言给译名 */
    fun regionName(context: Context, code: String): String {
        REGION_LABEL_OVERRIDES[code]?.let { return context.getString(it) }
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val display = Locale.Builder().setRegion(code).build().getDisplayCountry(locale)
        return display.ifBlank { code }
    }

    /** 根据 genre ID 查找显示名 */
    fun genreName(context: Context, genreId: Int, isMovie: Boolean): String {
        val list = if (isMovie) MOVIE_GENRES else TV_GENRES
        val res = list.firstOrNull { it.first == genreId }?.second ?: return ""
        return context.getString(res)
    }

    /** 根据 keyword ID 查找标签名 */
    fun tagName(context: Context, keywordId: Int): String {
        val res = TAGS.firstOrNull { it.first == keywordId }?.second ?: return ""
        return context.getString(res)
    }
}
