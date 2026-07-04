package com.tracktosearch.ui.screen.discoverfilter

/**
 * TMDB Discover 筛选预设数据
 *
 * genre ID 来源：TMDB 官方 genre 电影/电视剧列表
 * 地区代码：ISO 3166-1 alpha-2
 * 关键词 ID 来源：TMDB keywords 搜索（如漫威、DC、超级英雄等）
 */
object DiscoverFilterConstants {

    /** 电影类型（TMDB genre ID） */
    val MOVIE_GENRES: List<Pair<Int, String>> = listOf(
        28 to "动作",
        12 to "冒险",
        16 to "动画",
        35 to "喜剧",
        80 to "犯罪",
        99 to "纪录片",
        18 to "剧情",
        10751 to "家庭",
        14 to "奇幻",
        36 to "历史",
        27 to "恐怖",
        10402 to "音乐",
        9648 to "悬疑",
        10749 to "爱情",
        878 to "科幻",
        10770 to "电视电影",
        53 to "惊悚",
        10752 to "战争",
        37 to "西部"
    )

    /** 电视剧类型（TMDB genre ID） */
    val TV_GENRES: List<Pair<Int, String>> = listOf(
        10759 to "动作冒险",
        16 to "动画",
        35 to "喜剧",
        80 to "犯罪",
        99 to "纪录片",
        18 to "剧情",
        10751 to "家庭",
        10762 to "儿童",
        9648 to "悬疑",
        10763 to "新闻",
        10764 to "真人秀",
        10765 to "科幻奇幻",
        10766 to "肥皂剧",
        10767 to "谈话",
        10768 to "战争政治",
        37 to "西部"
    )

    /** 预设地区（ISO 3166-1 alpha-2 → 显示名） */
    val REGIONS: List<Pair<String, String>> = listOf(
        "CN" to "中国大陆",
        "HK" to "中国香港",
        "TW" to "中国台湾",
        "JP" to "日本",
        "KR" to "韩国",
        "IN" to "印度",
        "TH" to "泰国",
        "ID" to "印尼",
        "PH" to "菲律宾",
        "VN" to "越南",
        "MY" to "马来西亚",
        "SG" to "新加坡",
        "US" to "美国",
        "CA" to "加拿大",
        "GB" to "英国",
        "IE" to "爱尔兰",
        "AU" to "澳大利亚",
        "NZ" to "新西兰",
        "FR" to "法国",
        "DE" to "德国",
        "IT" to "意大利",
        "ES" to "西班牙",
        "PT" to "葡萄牙",
        "NL" to "荷兰",
        "BE" to "比利时",
        "CH" to "瑞士",
        "AT" to "奥地利",
        "SE" to "瑞典",
        "NO" to "挪威",
        "DK" to "丹麦",
        "FI" to "芬兰",
        "PL" to "波兰",
        "CZ" to "捷克",
        "HU" to "匈牙利",
        "RU" to "俄罗斯",
        "UA" to "乌克兰",
        "TR" to "土耳其",
        "GR" to "希腊",
        "IL" to "以色列",
        "AE" to "阿联酋",
        "SA" to "沙特",
        "IR" to "伊朗",
        "EG" to "埃及",
        "NG" to "尼日利亚",
        "ZA" to "南非",
        "MX" to "墨西哥",
        "BR" to "巴西",
        "AR" to "阿根廷",
        "CL" to "智利",
        "CO" to "哥伦比亚"
    )

    /**
     * 预设标签（TMDB keyword ID → 显示名）
     *
     * TMDB 没有"热门标签"列表 API，这里用常见影视主题的关键词 ID 实现。
     * 标签筛选通过 with_keywords 参数（OR 逻辑）传递给 Discover API。
     */
    val TAGS: List<Pair<Int, String>> = listOf(
        180547 to "漫威",
        2076 to "DC宇宙",
        9716 to "超级英雄",
        162355 to "校园",
        9840 to "僵尸",
        9937 to "吸血鬼",
        1251 to "丧尸",
        182985 to "末日",
        312 to "复仇",
        11183 to "时间旅行",
        9826 to "外星人",
        9717 to "反乌托邦",
        207820 to "赛博朋克",
        162349 to "太空",
        6152 to "战争",
        9951 to "间谍",
        13084 to "黑帮",
        9837 to "推理",
        4344 to "音乐",
        15245 to "舞蹈",
        6155 to "体育",
        1632 to "美食",
        6054 to "友谊",
        9830 to "爱情片",
        11121 to "成长",
        12552 to "公路",
        9799 to "荒野",
        314 to "翻拍",
        13085 to "续集",
        // 新增标签
        1612 to "武术",
        10188 to "功夫",
        3340 to "魔法",
        2075 to "圣诞",
        9817 to "绑架",
        610 to "狗",
        162353 to "猫",
        11456 to "人工智能",
        9718 to "童话",
        11096 to "警察",
        6084 to "侦探",
        15244 to "纪实",
        2092 to "黑客",
        9814 to "毒贩",
        14565 to "中世纪",
        208765 to "蒸汽朋克",
        9914 to "心理",
        9823 to "怪物",
        9934 to "鬼"
    )

    /**
     * 年代选项（value = 起始年份..结束年份，0..0 表示"更早"）
     *
     * 注意：TMDB Discover 的日期筛选是连续范围（gte/lte），多选不连续年代时
     * 会合并为最小起始到最大结束的连续范围。
     */
    data class DecadeOption(val label: String, val startYear: Int, val endYear: Int)

    /** 生成年代选项列表，"今年"动态取当前年份 */
    fun decadeOptions(currentYear: Int): List<DecadeOption> {
        val list = mutableListOf<DecadeOption>()
        list.add(DecadeOption("全部", 0, 0))
        list.add(DecadeOption("2020年代", 2020, 2029))
        // 2026、2025、2024、2023、2022、2021、2020、2019
        for (y in currentYear downTo 2019) {
            list.add(DecadeOption(y.toString(), y, y))
        }
        list.add(DecadeOption("2010年代", 2010, 2019))
        list.add(DecadeOption("2000年代", 2000, 2009))
        list.add(DecadeOption("90年代", 1990, 1999))
        list.add(DecadeOption("80年代", 1980, 1989))
        list.add(DecadeOption("70年代", 1970, 1979))
        list.add(DecadeOption("60年代", 1960, 1969))
        list.add(DecadeOption("更早", 0, 1959))
        return list
    }

    /** 根据 genre ID 查找显示名 */
    fun genreNameById(genreId: Int, isMovie: Boolean): String {
        val list = if (isMovie) MOVIE_GENRES else TV_GENRES
        return list.firstOrNull { it.first == genreId }?.second ?: ""
    }

    /** 根据国家代码查找显示名 */
    fun regionNameByCode(code: String): String {
        return REGIONS.firstOrNull { it.first == code }?.second ?: code
    }

    /** 语言代码 → 国家代码映射（discover/movie 不返回 origin_country，用 original_language 回退推断） */
    private val LANGUAGE_TO_COUNTRY: Map<String, Pair<String, String>> = mapOf(
        "zh" to ("CN" to "中国大陆"),
        "yue" to ("HK" to "中国香港"),
        "nan" to ("TW" to "中国台湾"),
        "ja" to ("JP" to "日本"),
        "ko" to ("KR" to "韩国"),
        "en" to ("US" to "美国"),
        "fr" to ("FR" to "法国"),
        "de" to ("DE" to "德国"),
        "it" to ("IT" to "意大利"),
        "es" to ("ES" to "西班牙"),
        "pt" to ("PT" to "葡萄牙"),
        "ru" to ("RU" to "俄罗斯"),
        "hi" to ("IN" to "印度"),
        "th" to ("TH" to "泰国"),
        "tr" to ("TR" to "土耳其"),
        "ar" to ("SA" to "沙特"),
        "fa" to ("IR" to "伊朗"),
        "pl" to ("PL" to "波兰"),
        "nl" to ("NL" to "荷兰"),
        "sv" to ("SE" to "瑞典"),
        "da" to ("DK" to "丹麦"),
        "fi" to ("FI" to "芬兰"),
        "no" to ("NO" to "挪威"),
        "cs" to ("CZ" to "捷克"),
        "hu" to ("HU" to "匈牙利"),
        "el" to ("GR" to "希腊"),
        "he" to ("IL" to "以色列"),
        "id" to ("ID" to "印尼"),
        "ms" to ("MY" to "马来西亚"),
        "vi" to ("VN" to "越南"),
        "tl" to ("PH" to "菲律宾"),
        "uk" to ("UA" to "乌克兰")
    )

    /**
     * 根据原始语言代码推断国家信息。
     * discover/movie 返回 original_language 但不返回 origin_country，用此回退。
     * @return (国家代码, 国家名)，无法推断时返回 null
     */
    fun countryByLanguage(languageCode: String): Pair<String, String>? {
        return LANGUAGE_TO_COUNTRY[languageCode]
    }

    /** 根据 keyword ID 查找标签名 */
    fun tagNameById(keywordId: Int): String {
        return TAGS.firstOrNull { it.first == keywordId }?.second ?: ""
    }
}
