package com.tracktosearch.ui.screen.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.ui.component.localizedGenreName

/**
 * Hero 的四段文案：标签 / 大数字 / 单位 / 一句话。
 *
 * 抽出来是因为分享长图要用同一套文案，而长图在 android.graphics 层绘制，
 * 拿不到 stringResource；由本函数在组合里取好，两边共用同一份取值规则。
 */
@Immutable
data class StatisticsHeroText(
    val label: String,
    val value: String,
    val unit: String,
    val sentence: String,
)

@Composable
fun statisticsHeroText(highlight: StatisticsHighlight): StatisticsHeroText {
    val genreName = highlight.genre?.let { localizedGenreName(it) }.orEmpty()
    val label = stringResource(
        when (highlight.kind) {
            StatisticsHighlight.Kind.STREAK -> R.string.statistics_hero_label_streak
            StatisticsHighlight.Kind.YEAR_COUNT -> R.string.statistics_hero_label_year
            StatisticsHighlight.Kind.WATCH_TIME -> R.string.statistics_hero_label_time
            StatisticsHighlight.Kind.TOP_GENRE -> R.string.statistics_hero_label_genre
            StatisticsHighlight.Kind.STARTER -> R.string.statistics_hero_label_starter
        }
    )
    val unit = stringResource(
        when (highlight.kind) {
            StatisticsHighlight.Kind.STREAK -> R.string.statistics_unit_days
            StatisticsHighlight.Kind.WATCH_TIME -> R.string.statistics_unit_hours
            StatisticsHighlight.Kind.TOP_GENRE -> R.string.statistics_unit_percent
            else -> R.string.statistics_unit_titles
        }
    )
    val sentence = when (highlight.kind) {
        StatisticsHighlight.Kind.STREAK ->
            stringResource(R.string.statistics_hero_streak, highlight.value)
        StatisticsHighlight.Kind.YEAR_COUNT ->
            stringResource(R.string.statistics_hero_year, highlight.value)
        StatisticsHighlight.Kind.WATCH_TIME ->
            stringResource(R.string.statistics_hero_time, highlight.extra)
        StatisticsHighlight.Kind.TOP_GENRE ->
            stringResource(R.string.statistics_hero_genre, genreName, highlight.extra)
        StatisticsHighlight.Kind.STARTER ->
            stringResource(R.string.statistics_hero_starter, highlight.value)
    }
    // 偏爱类型的大数字是占比而不是部数：说「偏爱剧情 42%」，42 才是主角
    val bigValue = if (highlight.kind == StatisticsHighlight.Kind.TOP_GENRE) {
        highlight.extra
    } else {
        highlight.value
    }
    return StatisticsHeroText(
        label = label,
        value = bigValue.toString(),
        unit = unit,
        sentence = sentence,
    )
}

/**
 * Hero 小结卡片：一句话 + 一个大数字 + 三项小指标。
 *
 * 页面第一屏若直接堆总览数字，用户看到的是一堆没有解读的计数；Hero 负责把
 * 「你最突出的一项」讲成人话，下面的总览再给全量数字。
 */
@Composable
fun StatisticsHeroCard(
    highlight: StatisticsHighlight,
    thisYearWatched: Int,
    totalHours: Int,
    streakDays: Int,
    modifier: Modifier = Modifier,
) {
    val heroText = statisticsHeroText(highlight)
    val primary = MaterialTheme.colorScheme.primary
    StatsCard(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.background(
                Brush.linearGradient(
                    listOf(primary.copy(alpha = 0.12f), primary.copy(alpha = 0.02f))
                )
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = heroText.label,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 2.sp,
                    color = primary,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = heroText.value,
                        fontSize = 46.sp,
                        lineHeight = 50.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = heroText.unit,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 7.dp),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = heroText.sentence,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    HeroMetric(
                        value = thisYearWatched.toString(),
                        label = stringResource(R.string.statistics_hero_metric_year),
                        modifier = Modifier.weight(1f),
                    )
                    HeroMetric(
                        value = totalHours.toString(),
                        label = stringResource(R.string.statistics_hero_metric_hours),
                        modifier = Modifier.weight(1f),
                    )
                    HeroMetric(
                        value = streakDays.toString(),
                        label = stringResource(R.string.statistics_hero_metric_streak),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
