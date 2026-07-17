package com.tracktosearch.data.repository

import com.tracktosearch.data.remote.dto.ResourceItem
import kotlin.math.sqrt

/**
 * 资源搜索的"目标影视"元数据。
 * 评分器据此对每条网盘资源结果计算相关度。
 * 任意字段为 null 时，对应的评分信号自动跳过（优雅降级）。
 *
 * [directors]/[cast] 为可选的强相关信号：标题命中导演名或演员名时加分，
 * 用于进一步区分同名作品（如"情书 岩井俊二"明显指向 1995 日本版）。
 */
data class ResourceQuery(
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val country: String? = null,
    val mediaType: MediaType? = null,
    val directors: List<String> = emptyList(),
    val cast: List<String> = emptyList()
)

/**
 * 相关度评分器接口。
 * 规则实现见 [RuleBasedRelevanceScorer]；将来若有离线 embedding 模型，
 * 可实现同一接口（如 EmbeddingRelevanceScorer）由 [RelevanceScorerProvider] 切换，调用方无感。
 *
 * 返回 [ScoredResource]（资源 + 分值）而非写回 ResourceItem 字段，
 * 避免 ResourceItem 作为可变共享状态在跨影视缓存场景下产生竞态。
 */
interface ResourceRelevanceScorer {
    fun score(item: ResourceItem, query: ResourceQuery): Int
}

/** 带分值的资源（不可变，避免共享缓存竞态）。 */
data class ScoredResource(val item: ResourceItem, val score: Int)

/**
 * 活跃评分器提供者（ML 预留点）。
 * 现返回规则评分器；后续接入向量模型时在此切换。
 */
object RelevanceScorerProvider {
    private val scorer: ResourceRelevanceScorer = RuleBasedRelevanceScorer()
    fun get(): ResourceRelevanceScorer = scorer

    /** 高相关阈值：低于此分的资源视为"低相关"（开关开启时隐藏）。 */
    const val HIGH_RELEVANCE_THRESHOLD = 45
}

/**
 * 纯 Kotlin 规则评分器：源无关（Zreso/PanSou/PanHub/自定义统一作用于 ResourceItem.name）。
 *
 * 设计依据：搜索 API 仅按关键词召回，返回结果都含关键词但未必是同一作品。
 * 本评分器通过 标题核心匹配 / 年份 / 类型 / 地区 / 内容类型排除 / 画质正信号 / 导演演员 多维度打分，
 * 把同名消歧、错类型、错年份、音乐/电子书等非影片结果沉底或隐藏。
 *
 * 规则收紧原则：所有"负信号"标记必须足够特异，避免误伤正常片名
 * （如"音乐之声"含"音乐"、"美人鱼"含"美"、"第一百次求婚"含"第"）。
 */
class RuleBasedRelevanceScorer : ResourceRelevanceScorer {

    override fun score(item: ResourceItem, query: ResourceQuery): Int {
        val name = item.name
        if (name.isBlank()) return 0
        var score = 0
        score += titleScore(name, query.title, query.originalTitle)
        score += yearScore(name, query.year)
        score += typeScore(name, query.mediaType)
        score += regionScore(name, query.country)
        score += contentScore(name)
        score += qualityScore(name)
        score += peopleScore(name, query.directors, query.cast)
        return score
    }

    // ===================== 标题核心匹配 =====================

    private fun titleScore(name: String, title: String, originalTitle: String?): Int {
        val t = title.trim()
        if (t.isEmpty()) return 0
        val best = maxOf(
            segmentMatchScore(name, t),
            originalTitle?.takeIf { it.isNotBlank() }?.let { segmentMatchScore(name, it) }
                ?: Int.MIN_VALUE
        )
        return if (best == Int.MIN_VALUE) 0 else best
    }

    /**
     * 命中等级：
     * - 整段相等 → +50
     * - 目标作为"定界片段"出现（前后为标点/数字/空格/括号/首尾，而非汉字/字母） → +40
     *   例："5025-情书"、"[夸克网盘]情书："、"Q 情书（199..." 命中；"两世情书""夜港情书""给阿嬷的情书" 不命中
     * - 否则按字符 bigram 余弦：≥0.9 → +25，0.7~0.9 → +12，<0.7 → 0
     *
     * 大小写不敏感：TMDB originalTitle 通常是首字母大写形式（如 "Socias por Accidente"），
     * 而网盘资源标题多为小写（如 "Socias por accidente 2026"）。若不统一大小写，
     * isDelimitedSegment 会因 indexOf 找不到而失败、cosineBigram 也会因大小写不同的 bigram
     * 而降级，导致 titleScore 从 +40 跌到 +12，原本高相关的资源被误隐藏。
     * 在入口统一 lowercase 后，下游 isDelimitedSegment/cosineBigram/bigrams 都不需要改。
     */
    private fun segmentMatchScore(name: String, target: String): Int {
        val n = name.trim().lowercase()
        val t = target.trim().lowercase()
        if (t.isEmpty()) return 0
        if (n == t) return 50
        if (isDelimitedSegment(n, t)) return 40
        val cos = cosineBigram(n, t)
        return when {
            cos >= 0.9 -> 25
            cos >= 0.7 -> 12
            else -> 0
        }
    }

    private fun isDelimitedSegment(text: String, target: String): Boolean {
        var idx = text.indexOf(target)
        while (idx >= 0) {
            val beforeOk = idx == 0 || isDelimiter(text[idx - 1])
            val afterOk = idx + target.length >= text.length || isDelimiter(text[idx + target.length])
            if (beforeOk && afterOk) return true
            idx = text.indexOf(target, idx + 1)
        }
        return false
    }

    private fun isDelimiter(c: Char): Boolean {
        if (c.isWhitespace()) return true
        if (c in DELIMITERS) return true
        if (c.isDigit()) return true
        // 任何字母（中文/英文）都不是定界符，保证"电子情书""夜港情书"不被误判为片段
        if (c.isLetter()) return false
        return true
    }

    private fun bigrams(s: String): Set<String> {
        val clean = s.filter { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }
        if (clean.length < 2) return setOf(clean)
        return (0 until clean.length - 1).map { clean.substring(it, it + 2) }.toSet()
    }

    private fun cosineBigram(a: String, b: String): Double {
        val sa = bigrams(a)
        val sb = bigrams(b)
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        val inter = sa.intersect(sb).size
        return inter.toDouble() / sqrt(sa.size.toDouble() * sb.size.toDouble())
    }

    // ===================== 年份 =====================

    private fun yearScore(name: String, year: Int?): Int {
        if (year == null) return 0
        val allYears = YEAR_REGEX.findAll(name).map { it.value.toInt() }.toSet()
        if (year in allYears) return 15
        // 含"影片年记号"（括号/点/空格包裹的 19xx/20xx）且不等于目标年 → 惩罚
        val movieYears = MOVIE_YEAR_REGEX.findAll(name).map { it.groupValues[1].toInt() }.toSet()
        return if (movieYears.isNotEmpty() && year !in movieYears) -15 else 0
    }

    // ===================== 类型 =====================

    /**
     * 目标是电影时，标题含明确剧集标记 → 惩罚（它是剧不是电影）。
     * 目标是剧集时不做惩罚（剧集标记反而是预期）。
     *
     * 注意：标记必须带数字或为完整词，避免误伤
     * - "第" 单字会误伤"第一百次求婚" → 改为 `第\d+季`/`第[一二三四...]+季`
     * - "s0/s1/s2" 子串会误伤英文词 → 改为 `s\d{1,2}` 且前后需边界
     */
    private fun typeScore(name: String, mediaType: MediaType?): Int {
        if (mediaType == MediaType.MOVIE) {
            val lower = name.lowercase()
            // 中文剧集标记：完整词（短剧/电视剧/连续剧/剧集/综艺/韩综/动漫/国漫/全季/合集）
            if (SHOW_MARKERS_CN.any { it in name }) return -20
            // "第X季"：中文数字或阿拉伯数字
            if (SEASON_CN_REGEX.containsMatchIn(name)) return -20
            // 英文剧集标记：season N / s0X / sXX（前后需边界，避免误伤单词）
            if (SEASON_EN_REGEX.containsMatchIn(lower)) return -20
        }
        return 0
    }

    // ===================== 地区 =====================

    /**
     * 目标国家已知时，标题含与该国冲突的地区标记 → 惩罚。
     * 例如目标=日本，标题含"韩综/韩剧/美剧"等 → 惩罚。
     *
     * 注意：单字"韩/日/美/港/台"误伤率极高（"美人鱼""东京物语""港囧"），
     * 仅保留**组合词**（韩剧/韩综/美剧/日剧/日影/港剧/台剧/国漫/国产/大陆/华语/欧美）参与匹配。
     */
    private fun regionScore(name: String, country: String?): Int {
        val expected = country?.let { COUNTRY_REGION_MARKERS[it] } ?: return 0
        for (marker in ALL_REGION_MARKERS) {
            // marker 已是组合词（长度≥2），直接子串匹配即可
            if (marker in name && marker !in expected) return -15
        }
        return 0
    }

    // ===================== 内容类型排除 =====================

    /**
     * 含明确非影片标记 → 强惩罚。
     *
     * 注意：单独的"音乐""原声"会误伤"音乐之声""原声大盗"等正常片名，
     * 收紧为组合标记或需后跟"专辑/原声/配乐/大碟"等限定词。
     */
    private fun contentScore(name: String): Int {
        val lower = name.lowercase()
        // 强信号：格式后缀（几乎不会误伤）
        if (CONTENT_STRONG_MARKERS.any { it in lower }) return -30
        // 弱信号：需组合出现（如"音乐+专辑/原声/配乐"）
        if (hasMusicCombo(name, lower)) return -30
        return 0
    }

    private fun hasMusicCombo(name: String, lower: String): Boolean {
        // 音乐/原声/歌曲 后跟 专辑/大碟/原声带/配乐/OSS/OST 才触发
        if (("音乐" in name || "原声" in name || "歌曲" in name) &&
            MUSIC_COMBO_SUFFIX.any { it in name || it in lower }) return true
        return false
    }

    // ===================== 画质正信号 =====================

    /** 含 1080p/4K/remux/BluRay/MKV 等 → 是真实视频文件的正信号。 */
    private fun qualityScore(name: String): Int {
        val n = name.lowercase()
        return if (QUALITY_MARKERS.any { it in n }) 10 else 0
    }

    // ===================== 导演/演员 =====================

    /**
     * 标题命中导演名或演员名 → 强相关正信号。
     * 例如"情书 岩井俊二 1080p"明显指向 1995 日本版。
     * 三字及以上中文姓名才参与匹配，避免"日""美"等单字误伤。
     */
    private fun peopleScore(name: String, directors: List<String>, cast: List<String>): Int {
        if (directors.isEmpty() && cast.isEmpty()) return 0
        var s = 0
        for (d in directors) {
            if (d.length >= 2 && d in name) s += 15
        }
        for (c in cast) {
            if (c.length >= 2 && c in name) s += 8
        }
        return s.coerceAtMost(25) // 上限避免多个演员名堆分
    }

    companion object {
        private const val DELIMITERS = "()[]{}（）【】〈〉《》<>/\\|.,。，-—:：;；'\"\"'~@#%&*+=_"

        private val YEAR_REGEX = Regex("""(?:19|20)\d{2}""")
        // 影片年记号：前后为边界（括号/点/空格/首尾），避免把 "2025.05.11" 这种日期当影片年
        private val MOVIE_YEAR_REGEX = Regex("""(?:^|[\s(\[.（])((?:19|20)\d{2})(?=$|[\s)\]）.]|$)""")

        // 中文剧集标记：完整词，避免单字"第"误伤
        private val SHOW_MARKERS_CN = listOf(
            "短剧", "电视剧", "连续剧", "剧集", "综艺", "韩综", "动漫", "国漫", "全季", "合集"
        )
        // "第X季"：中文数字或阿拉伯数字（如"第一季""第2季"）
        private val SEASON_CN_REGEX = Regex("""第[\d一二三四五六七八九十百千]+季""")
        // 英文剧集标记：season N / s01 / s02（前后需非字母数字边界，避免误伤单词）
        private val SEASON_EN_REGEX = Regex("""(?:^|[^\w])(?:season\s*\d{1,2}|s\d{2})(?:[^\w]|$)""")

        // 仅保留组合词地区标记，移除单字（韩/日/美/港/台）避免误伤
        private val ALL_REGION_MARKERS = listOf(
            "韩剧", "韩综", "韩国",
            "美剧", "欧美",
            "日剧", "日影",
            "国产", "大陆", "华语", "国漫",
            "港剧",
            "台剧"
        )

        private val COUNTRY_REGION_MARKERS: Map<String, Set<String>> = mapOf(
            "日本" to setOf("日剧", "日影"),
            "韩国" to setOf("韩剧", "韩综", "韩国"),
            "美国" to setOf("美剧", "欧美"),
            "中国大陆" to setOf("国产", "大陆", "华语", "国漫"),
            "大陆" to setOf("国产", "大陆", "华语", "国漫"),
            "香港" to setOf("港剧"),
            "台湾" to setOf("台剧")
        )

        // 强信号：几乎不会误伤的格式后缀
        private val CONTENT_STRONG_MARKERS = listOf(
            "flac", "hi-res", "hires", "24bit", "24-bit", "96khz", "48khz",
            "qobuz", "epub", "kindle", "pdf", "有声书", "实体书", "绘本",
            "专辑", "单曲", "演唱会", "原声带", "原声大碟", "配乐集"
        )
        // 音乐类组合后缀：与"音乐/原声/歌曲"组合才触发
        private val MUSIC_COMBO_SUFFIX = listOf("专辑", "大碟", "原声带", "配乐", "ost", "原声")

        private val QUALITY_MARKERS = listOf(
            "1080p", "720p", "4k", "2160p", "remux", "bluray", "bdrip",
            "web-dl", "hd", "高清", "mkv", "mp4", "h264", "h265", "hevc"
        )
    }
}
