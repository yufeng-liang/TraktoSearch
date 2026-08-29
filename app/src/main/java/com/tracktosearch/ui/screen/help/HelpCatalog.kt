package com.tracktosearch.ui.screen.help

import androidx.annotation.StringRes
import com.tracktosearch.R

/**
 * 帮助页段落分组。
 *
 * 十四段平铺是一堵墙，读的人没有落脚点。分组不改变段落顺序，只在滚动时给出
 * 「还在同一类里」的信号。
 */
internal enum class HelpGroup(@StringRes val label: Int) {
    /** 装上就会用到的 */
    BASICS(R.string.help_group_basics),

    /** 看过什么、标记在哪、怎么导出 */
    RECORDS(R.string.help_group_records),

    /** 要先动手配一下才用得上的 */
    ADVANCED(R.string.help_group_advanced),

    /** 谈不上入门也谈不上进阶的 */
    EXTRAS(R.string.help_group_extras),
}

/**
 * 段落里除条目外的手写内容。
 *
 * 表格和代码块没法用一串 StringRes 表达，目录只记「这段还有什么」，
 * 具体怎么画在 [HelpRichContent] 那一组 Composable 里。
 */
internal enum class HelpExtra {
    /** 数据管理：导出 / IMDb 导入 / 豆瓣同步三张卡 */
    DATA_SOURCES,

    /** 自定义搜索源：参数表 + 解析说明 + 三段示例 JSON */
    CUSTOM_SOURCE_SPEC,

    /** 一致性检查：豆瓣 × Trakt 的四列对照表 */
    CONSISTENCY_TABLE,
}

/** 一个帮助段落的全部描述。渲染、搜索、深链定位都只读这里。 */
internal data class HelpSectionSpec(
    /** 功能页深链用的 key，取值见 [HelpSections] */
    val key: String,
    val group: HelpGroup,
    @StringRes val title: Int,
    /** 正文条目，按序编号 */
    val bullets: List<Int>,
    val extra: HelpExtra? = null,
    /**
     * 只出现在 [extra] 里、不进条目列表的文案。
     *
     * 搜「listPath」得能命中自定义搜索源那段，但参数表里的十四行不该变成十四条正文。
     */
    val searchOnly: List<Int> = emptyList(),
) {
    /** 段内搜索要扫的全部文案 */
    val searchable: List<Int> get() = listOf(title) + bullets + searchOnly
}

/**
 * 帮助页的段落目录，全页唯一真源。
 *
 * 以前段落信息散在四处：手写的搜索索引、key→序号映射、key 常量、以及十四个几乎
 * 一样的 item 块。加一段要改四处，漏一处不会报错——只是搜不到，或者深链跳到别的段。
 *
 * 列表顺序就是页面顺序，也是编号顺序：下标 0 显示成「1」，下标 13 显示成「14」。
 * 同一分组的段落必须连续，否则分组小标题会在页面上出现两次（见 HelpCatalogTest）。
 */
internal val HelpCatalog: List<HelpSectionSpec> = listOf(
    HelpSectionSpec(
        key = HelpSections.SEARCH,
        group = HelpGroup.BASICS,
        title = R.string.help_search,
        bullets = listOf(
            R.string.help_search_b1,
            R.string.help_search_b2,
            R.string.help_search_b3,
            R.string.help_search_b4,
            R.string.help_person_search,
        ),
    ),
    HelpSectionSpec(
        key = HelpSections.WATCHLIST,
        group = HelpGroup.BASICS,
        title = R.string.help_watchlist,
        bullets = listOf(
            R.string.help_watchlist_b1,
            R.string.help_watchlist_b2,
            R.string.help_watchlist_b3,
            R.string.help_watchlist_b4,
        ),
    ),
    HelpSectionSpec(
        key = HelpSections.DETAIL,
        group = HelpGroup.BASICS,
        title = R.string.help_detail,
        bullets = listOf(
            R.string.help_detail_custom,
            R.string.help_videos_images,
        ),
    ),
    HelpSectionSpec(
        key = HelpSections.DISCOVER_FILTER,
        group = HelpGroup.BASICS,
        title = R.string.help_discover_filter,
        bullets = listOf(
            R.string.help_discover_filter_b1,
            R.string.help_discover_filter_b2,
            R.string.help_discover_filter_b3,
            R.string.help_discover_filter_b4,
            R.string.help_discover_filter_b6,
        ),
    ),
    HelpSectionSpec(
        key = HelpSections.STATISTICS,
        group = HelpGroup.RECORDS,
        title = R.string.help_statistics,
        bullets = listOf(
            R.string.help_statistics_b1,
            R.string.help_statistics_b2,
            R.string.help_statistics_b3,
            R.string.help_statistics_b6,
        ),
    ),
    HelpSectionSpec(
        key = HelpSections.MARK_RECORDS,
        group = HelpGroup.RECORDS,
        title = R.string.mark_records_settings_entry,
        bullets = listOf(
            R.string.mark_records_help_entry_location,
            R.string.mark_records_help_data_source,
            R.string.mark_records_help_history_limit,
        ),
    ),
    HelpSectionSpec(
        key = HelpSections.DATA,
        group = HelpGroup.RECORDS,
        title = R.string.help_data,
        // 三张导入导出卡是这段的主体，唯一的正文条目是卡片下面那句冷却时间说明
        bullets = listOf(R.string.help_dc_cooldown),
        extra = HelpExtra.DATA_SOURCES,
        searchOnly = listOf(
            R.string.help_data_table_title,
            R.string.help_dc_export_t,
            R.string.help_dc_export_entry,
            R.string.help_dc_export_fmt,
            R.string.help_dc_export_desc,
            R.string.help_dc_imdb_t,
            R.string.help_dc_imdb_entry,
            R.string.help_dc_imdb_fmt,
            R.string.help_dc_imdb_desc,
            R.string.help_dc_douban_t,
            R.string.help_dc_douban_entry,
            R.string.help_dc_douban_fmt,
            R.string.help_dc_douban_desc,
        ),
    ),
    HelpSectionSpec(
        key = HelpSections.CONSISTENCY_CHECK,
        group = HelpGroup.RECORDS,
        title = R.string.help_consistency_check,
        bullets = listOf(R.string.help_consistency_intro),
        extra = HelpExtra.CONSISTENCY_TABLE,
        searchOnly = listOf(
            R.string.help_consistency_col_douban,
            R.string.help_consistency_col_trakt,
            R.string.help_consistency_col_result,
            R.string.help_consistency_col_action,
        ),
    ),
    HelpSectionSpec(
        key = HelpSections.CUSTOM_SOURCE,
        group = HelpGroup.ADVANCED,
        title = R.string.help_custom_source,
        bullets = listOf(
            R.string.help_custom_source_b1,
            R.string.help_custom_source_b2,
            R.string.help_custom_source_b3,
            R.string.help_custom_source_b4,
            R.string.help_custom_source_b5,
            R.string.help_custom_source_b6,
        ),
        extra = HelpExtra.CUSTOM_SOURCE_SPEC,
        searchOnly = listOf(
            R.string.help_custom_source_params,
            R.string.help_custom_source_parse_title,
            R.string.help_custom_source_parse_b1,
            R.string.help_custom_source_parse_b2,
            R.string.help_custom_source_parse_b3,
            R.string.help_custom_source_example_title,
        ),
    ),
    HelpSectionSpec(
        key = HelpSections.DOUBAN_WRITEBACK,
        group = HelpGroup.ADVANCED,
        title = R.string.help_douban_writeback,
        bullets = listOf(
            R.string.help_douban_writeback_b1,
            R.string.help_douban_writeback_b3,
        ),
    ),
    HelpSectionSpec(
        key = HelpSections.VPN,
        group = HelpGroup.ADVANCED,
        title = R.string.help_vpn,
        bullets = listOf(
            R.string.help_vpn_b1,
            R.string.help_vpn_b2,
        ),
    ),
    HelpSectionSpec(
        key = HelpSections.AI_SPRITE,
        group = HelpGroup.ADVANCED,
        title = R.string.help_ai_sprite,
        bullets = listOf(
            R.string.ai_sprite_long_press_hint,
            R.string.help_ai_sprite_b2,
            R.string.help_ai_sprite_b3,
            R.string.help_ai_sprite_b4,
            R.string.help_ai_sprite_b5,
            R.string.help_ai_sprite_b7,
        ),
    ),
    HelpSectionSpec(
        key = HelpSections.NOTIFICATION,
        group = HelpGroup.EXTRAS,
        title = R.string.help_notification,
        bullets = listOf(
            R.string.help_notification_b1,
            R.string.help_notification_b2,
            R.string.help_notification_b5,
        ),
    ),
    HelpSectionSpec(
        key = HelpSections.TIPS,
        group = HelpGroup.EXTRAS,
        title = R.string.help_tips,
        bullets = listOf(
            R.string.help_tips_b1,
            R.string.help_tips_b2,
            R.string.help_tips_b5,
            R.string.help_tips_b12,
        ),
    ),
)

/**
 * 帮助段落 key 常量，供功能页带参数跳转（`Routes.helpRoute(HelpSections.X)`）。
 *
 * 这些字符串会进导航路由，改名等于改 URL，动它之前先搜调用方。
 */
object HelpSections {
    const val SEARCH = "search"
    const val WATCHLIST = "watchlist"
    const val STATISTICS = "statistics"
    const val MARK_RECORDS = "markRecords"
    const val NOTIFICATION = "notification"
    const val DATA = "data"
    const val CUSTOM_SOURCE = "customSource"
    const val DETAIL = "detail"
    const val DISCOVER_FILTER = "discoverFilter"
    const val TIPS = "tips"
    const val VPN = "vpn"
    const val DOUBAN_WRITEBACK = "doubanWriteback"
    const val CONSISTENCY_CHECK = "consistencyCheck"
    const val AI_SPRITE = "aiSprite"
}

/** 一个分组和它名下的段落。[IndexedValue.index] 是段落在 [HelpCatalog] 里的下标，也就是它的编号。 */
internal data class HelpGroupBlock(
    val group: HelpGroup,
    val sections: List<IndexedValue<HelpSectionSpec>>,
)

/**
 * 按分组切开的目录。
 *
 * 用 groupBy 而不是遍历 [HelpGroup] 枚举：这样页面顺序永远等于编号顺序。
 * 代价是同组段落必须在 [HelpCatalog] 里连续摆放，有测试守着。
 */
internal val HelpGroupBlocks: List<HelpGroupBlock> = HelpCatalog
    .withIndex()
    .groupBy { it.value.group }
    .map { (group, sections) -> HelpGroupBlock(group, sections) }

/** 深链用：段落 key → 它在 [HelpCatalog] 里的下标，认不出的 key 返回 null。 */
internal fun helpIndexOf(key: String?): Int? {
    if (key == null) return null
    val index = HelpCatalog.indexOfFirst { it.key == key }
    return index.takeIf { it >= 0 }
}

/**
 * 段落是否命中搜索词。
 *
 * 只吃已经取好的字符串、不碰 Context，这样匹配规则能被纯 JVM 单测锁住。
 * 空词一律算命中——空词不是「没有结果」，是「没在筛」。
 */
internal fun helpMatchesQuery(texts: List<String>, query: String): Boolean {
    val keyword = query.trim()
    if (keyword.isEmpty()) return true
    return texts.any { it.contains(keyword, ignoreCase = true) }
}
