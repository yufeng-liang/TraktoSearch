package com.tracktosearch.ui.screen.statistics

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Hero 小结：从统计数据里挑出「这个人最突出的那一项」，用一句话 + 一个大数字讲出来。
 *
 * 只挑一条，按固定优先级取第一条成立的。随机挑会让同一份数据每次进页面说不同的话，
 * 固定一套又会在数据形态差别很大的用户之间说得同样空洞。
 */
@Immutable
data class StatisticsHighlight(
    val kind: Kind,
    /** 卡片主数字 */
    val value: Int,
    /** 副数字，仅 WATCH_TIME（换算天数）与 TOP_GENRE（占比百分数）使用 */
    val extra: Int = 0,
    /** 类型原始 key，仅 TOP_GENRE 使用，展示前需过 localizedGenreName */
    val genre: String? = null,
) {
    enum class Kind { STREAK, YEAR_COUNT, WATCH_TIME, TOP_GENRE, STARTER }
}

/** 连看阈值：低于 3 天算不上「连着看」，不值得放到 Hero。 */
private const val STREAK_THRESHOLD = 3

/** 年度观影数阈值 */
private const val YEAR_COUNT_THRESHOLD = 50

/** 累计观影小时阈值 */
private const val WATCH_HOURS_THRESHOLD = 100

/** 偏爱类型占比阈值（百分数）：低于它说明口味均衡，说「偏爱」不准确。 */
private const val TOP_GENRE_PERCENT_THRESHOLD = 25

/**
 * 最长连续观影天数。
 *
 * [heatmapData] 的 key 为 `yyyy-MM-dd`（写入侧用 Locale.US 固定格式），value 为当天观看数。
 * 只统计 value > 0 的日期；无法解析的 key 直接跳过，不让脏数据把整段统计打断。
 */
internal fun longestWatchStreak(heatmapData: Map<String, Int>): Int {
    val days = heatmapData.asSequence()
        .filter { it.value > 0 }
        .mapNotNull { entry ->
            try {
                LocalDate.parse(entry.key)
            } catch (e: DateTimeParseException) {
                null
            }
        }
        .distinct()
        .sorted()
        .toList()
    if (days.isEmpty()) return 0

    var best = 1
    var current = 1
    for (i in 1 until days.size) {
        current = if (days[i - 1].plusDays(1) == days[i]) current + 1 else 1
        if (current > best) best = current
    }
    return best
}

/**
 * 按优先级挑出 Hero 要讲的那一条。
 *
 * 顺序：连看天数 → 本年观影数 → 累计时长 → 偏爱类型 → 兜底。
 * 兜底一定要有：新用户各项都不达标，没有兜底 Hero 就是一片空白。
 */
internal fun pickHighlight(
    streakDays: Int,
    thisYearWatched: Int,
    totalWatchMinutes: Long,
    genreDistribution: Map<String, Int>,
    totalWatchedCount: Int,
): StatisticsHighlight {
    val hours = (totalWatchMinutes / 60).toInt()
    if (streakDays >= STREAK_THRESHOLD) {
        return StatisticsHighlight(StatisticsHighlight.Kind.STREAK, streakDays)
    }
    if (thisYearWatched >= YEAR_COUNT_THRESHOLD) {
        return StatisticsHighlight(StatisticsHighlight.Kind.YEAR_COUNT, thisYearWatched)
    }
    if (hours >= WATCH_HOURS_THRESHOLD) {
        return StatisticsHighlight(StatisticsHighlight.Kind.WATCH_TIME, hours, extra = hours / 24)
    }
    val total = genreDistribution.values.sum()
    val top = genreDistribution.maxByOrNull { it.value }
    if (total > 0 && top != null) {
        val percent = (top.value * 100.0 / total).toInt()
        if (percent >= TOP_GENRE_PERCENT_THRESHOLD) {
            return StatisticsHighlight(
                StatisticsHighlight.Kind.TOP_GENRE,
                top.value,
                extra = percent,
                genre = top.key,
            )
        }
    }
    return StatisticsHighlight(StatisticsHighlight.Kind.STARTER, totalWatchedCount)
}
