package com.tracktosearch.data.repository

import com.tracktosearch.data.remote.dto.ResourceItem
import java.text.Normalizer
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

enum class TitleMatch {
    NONE,
    PARTIAL,
    DELIMITED,
    EXACT;

    val isStrong: Boolean
        get() = this == DELIMITED || this == EXACT
}

enum class ResourceContentType {
    UNKNOWN,
    VIDEO,
    AUDIO,
    BOOK
}

data class ResourceRelevance(
    val score: Int,
    val titleMatch: TitleMatch,
    val contentType: ResourceContentType,
    val hasYearConflict: Boolean,
    val hasMediaTypeConflict: Boolean,
    val hasRegionConflict: Boolean
) {
    val hasExplicitConflict: Boolean
        get() = hasYearConflict || hasMediaTypeConflict || hasRegionConflict

    val isHighRelevance: Boolean
        get() = when {
            contentType == ResourceContentType.AUDIO -> false
            contentType == ResourceContentType.BOOK -> false
            titleMatch.isStrong -> !hasExplicitConflict
            else -> score >= RelevanceScorerProvider.HIGH_RELEVANCE_THRESHOLD && !hasExplicitConflict
        }
}

/**
 * 相关度评分器接口。
 * 规则实现见 [RuleBasedRelevanceScorer]；将来若有离线 embedding 模型，
 * 可实现同一接口（如 EmbeddingRelevanceScorer）由 [RelevanceScorerProvider] 切换，调用方无感。
 */
interface ResourceRelevanceScorer {
    fun evaluate(item: ResourceItem, query: ResourceQuery): ResourceRelevance

    /** 保留排序层已有的分数调用契约。 */
    fun score(item: ResourceItem, query: ResourceQuery): Int = evaluate(item, query).score
}

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

    override fun evaluate(item: ResourceItem, query: ResourceQuery): ResourceRelevance {
        val rawName = item.name
        if (rawName.isBlank()) {
            return ResourceRelevance(
                score = 0,
                titleMatch = TitleMatch.NONE,
                contentType = ResourceContentType.UNKNOWN,
                hasYearConflict = false,
                hasMediaTypeConflict = false,
                hasRegionConflict = false
            )
        }

        val normalizedName = normalizeReleaseName(rawName)
        val titleSignal = titleSignal(normalizedName, query.title, query.originalTitle)
        val yearSignal = yearSignal(normalizedName, query.year)
        val hasMediaTypeConflict = hasMediaTypeConflict(normalizedName, query.mediaType)
        val hasRegionConflict = hasRegionConflict(normalizedName, query.country)
        val contentType = detectContentType(normalizedName)

        val score = titleSignal.score +
            yearSignal.score +
            if (hasMediaTypeConflict) -20 else 0 +
            if (hasRegionConflict) -15 else 0 +
            contentPenalty(contentType) +
            qualityScore(normalizedName) +
            peopleScore(normalizedName, query.directors, query.cast)

        return ResourceRelevance(
            score = score,
            titleMatch = titleSignal.match,
            contentType = contentType,
            hasYearConflict = yearSignal.hasConflict,
            hasMediaTypeConflict = hasMediaTypeConflict,
            hasRegionConflict = hasRegionConflict
        )
    }

    private data class TitleSignal(
        val match: TitleMatch,
        val score: Int
    )

    private data class YearSignal(
        val score: Int,
        val hasConflict: Boolean
    )

    // ===================== 标题核心匹配 =====================

    private fun titleSignal(name: String, title: String, originalTitle: String?): TitleSignal {
        return sequenceOf(title, originalTitle.orEmpty())
            .filter { it.isNotBlank() }
            .map { segmentSignal(name, it) }
            .maxByOrNull { it.score }
            ?: TitleSignal(TitleMatch.NONE, 0)
    }

    private fun segmentSignal(name: String, target: String): TitleSignal {
        val normalizedTarget = normalizeTitle(target)
        if (normalizedTarget.isEmpty()) return TitleSignal(TitleMatch.NONE, 0)
        if (name == normalizedTarget) return TitleSignal(TitleMatch.EXACT, 50)
        if (isDelimitedSegment(name, normalizedTarget)) return TitleSignal(TitleMatch.DELIMITED, 40)

        val cosine = cosineBigram(
            stripTechnicalMetadata(name),
            stripTechnicalMetadata(normalizedTarget)
        )
        return when {
            cosine >= 0.9 -> TitleSignal(TitleMatch.PARTIAL, 25)
            cosine >= 0.7 -> TitleSignal(TitleMatch.PARTIAL, 12)
            else -> TitleSignal(TitleMatch.NONE, 0)
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
        return !c.isLetter()
    }

    private fun bigrams(s: String): Set<String> {
        val clean = s.filter { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }
        if (clean.isEmpty()) return emptySet()
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

    private fun normalizeReleaseName(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase()
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun normalizeTitle(value: String): String = normalizeReleaseName(value)

    private fun stripTechnicalMetadata(value: String): String {
        return value
            .replace(YEAR_REGEX, " ")
            .replace(AUDIO_MARKER_REGEX, " ")
            .replace(BOOK_MARKER_REGEX, " ")
            .replace(VIDEO_MARKER_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    // ===================== 年份 =====================

    private fun yearSignal(name: String, year: Int?): YearSignal {
        if (year == null) return YearSignal(0, false)
        val movieYears = extractMovieYears(name)
        if (year in movieYears) return YearSignal(15, false)

        return if (movieYears.isNotEmpty()) {
            YearSignal(-15, true)
        } else {
            YearSignal(0, false)
        }
    }

    private fun extractMovieYears(name: String): Set<Int> {
        return MOVIE_YEAR_REGEX.findAll(name)
            .mapNotNull { match ->
                val yearGroup = match.groups[1] ?: return@mapNotNull null
                if (isDateLikeYear(name, yearGroup.range.last + 1)) {
                    null
                } else {
                    yearGroup.value.toInt()
                }
            }
            .toSet()
    }

    private fun isDateLikeYear(text: String, nextIndex: Int): Boolean {
        if (nextIndex >= text.length || text[nextIndex] != '.') return false

        var cursor = nextIndex + 1
        val firstPartStart = cursor
        while (cursor < text.length && text[cursor].isDigit()) cursor++
        val firstPartLength = cursor - firstPartStart
        if (firstPartLength !in 1..2) return false
        if (cursor >= text.length || text[cursor] != '.') return false

        cursor += 1
        val secondPartStart = cursor
        while (cursor < text.length && text[cursor].isDigit()) cursor++
        val secondPartLength = cursor - secondPartStart
        return secondPartLength in 1..2
    }

    // ===================== 类型 =====================

    private fun hasMediaTypeConflict(name: String, mediaType: MediaType?): Boolean {
        if (mediaType != MediaType.MOVIE) return false
        return SHOW_MARKERS_CN.any { it in name } ||
            SEASON_CN_REGEX.containsMatchIn(name) ||
            SEASON_EN_REGEX.containsMatchIn(name)
    }

    // ===================== 地区 =====================

    private fun hasRegionConflict(name: String, country: String?): Boolean {
        val expected = country?.let { COUNTRY_REGION_MARKERS[it] } ?: return false
        return ALL_REGION_MARKERS.any { marker -> marker in name && marker !in expected }
    }

    // ===================== 内容类型 =====================

    private fun detectContentType(name: String): ResourceContentType {
        return when {
            AUDIO_MARKER_REGEX.containsMatchIn(name) -> ResourceContentType.AUDIO
            BOOK_MARKER_REGEX.containsMatchIn(name) -> ResourceContentType.BOOK
            VIDEO_MARKER_REGEX.containsMatchIn(name) -> ResourceContentType.VIDEO
            else -> ResourceContentType.UNKNOWN
        }
    }

    private fun contentPenalty(contentType: ResourceContentType): Int {
        return when (contentType) {
            ResourceContentType.AUDIO,
            ResourceContentType.BOOK -> -30
            ResourceContentType.UNKNOWN,
            ResourceContentType.VIDEO -> 0
        }
    }

    // ===================== 画质正信号 =====================

    private fun qualityScore(name: String): Int {
        return if (QUALITY_MARKER_REGEX.containsMatchIn(name)) 10 else 0
    }

    // ===================== 导演/演员 =====================

    private fun peopleScore(name: String, directors: List<String>, cast: List<String>): Int {
        if (directors.isEmpty() && cast.isEmpty()) return 0
        var score = 0
        for (director in directors) {
            val normalizedDirector = normalizeTitle(director)
            if (normalizedDirector.length >= 2 && normalizedDirector in name) score += 15
        }
        for (actor in cast) {
            val normalizedActor = normalizeTitle(actor)
            if (normalizedActor.length >= 2 && normalizedActor in name) score += 8
        }
        return score.coerceAtMost(25)
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private const val DELIMITERS = "()[]{}<>/\\|.,，。-—:：;；'\"~@#%&*+=_!?！？"

        private val YEAR_REGEX = Regex("""(?:19|20)\d{2}""")
        private val MOVIE_YEAR_REGEX = Regex("""(?:^|[\s(\[（.])((?:19|20)\d{2})(?=$|[\s)\]）.])""")

        private val SHOW_MARKERS_CN = listOf(
            "短剧", "电视剧", "连续剧", "剧集", "综艺", "韩综", "动漫", "国漫", "全季", "合集"
        )
        private val SEASON_CN_REGEX = Regex("""第[\d一二三四五六七八九十百千]+季""")
        private val SEASON_EN_REGEX = Regex("""(?:^|[^\w])(?:season\s*\d{1,2}|s\d{2})(?:[^\w]|$)""")

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

        private val AUDIO_MARKER_REGEX = Regex(
            """(?i)(?:^|[^a-z0-9])(flac|mp3|wav|ape|alac|24bit|24-bit|48khz|96khz|qobuz|hi-res|hires|推广曲|主题曲|插曲|原声带|原声大碟|专辑|单曲|演唱会|配乐集|音频|soundtrack|ost)(?:[^a-z0-9]|$)"""
        )
        private val BOOK_MARKER_REGEX = Regex(
            """(?i)(?:^|[^a-z0-9])(epub|kindle|pdf|有声书|实体书|绘本)(?:[^a-z0-9]|$)"""
        )
        private val VIDEO_MARKER_REGEX = Regex(
            """(?i)(?:^|[^a-z0-9])(2160p|1080p|720p|4k|remux|bluray|bdrip|web-dl|hdtv|tc|cam|mkv|mp4|avi|h264|h265|hevc|高清|画质增强版|正片)(?:[^a-z0-9]|$)"""
        )
        private val QUALITY_MARKER_REGEX = Regex(
            """(?i)(?:^|[^a-z0-9])(2160p|1080p|720p|4k|remux|bluray|bdrip|web-dl|hdtv|tc|cam|mkv|mp4|avi|h264|h265|hevc|高清|画质增强版|正片)(?:[^a-z0-9]|$)"""
        )
    }
}
