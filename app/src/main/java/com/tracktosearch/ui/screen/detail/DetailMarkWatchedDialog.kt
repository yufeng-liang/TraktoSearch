package com.tracktosearch.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.remote.trakt.dto.TraktEpisode
import com.tracktosearch.data.remote.trakt.dto.TraktSeason
import com.tracktosearch.ui.component.AppAlertDialog
import com.tracktosearch.ui.component.DialogAction
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.theme.DesignToken
import com.tracktosearch.ui.theme.WatchedGreen
import kotlinx.coroutines.flow.MutableSharedFlow

// ==================== 标记已看弹窗（电视剧季/集勾选） ====================

/** 季集加载失败信号：ViewModel 同包写入，弹窗收集后按季显示错误态与重试入口（无状态，弹窗关闭即清） */
internal val markWatchedEpisodeLoadFailures = MutableSharedFlow<Int>(extraBufferCapacity = 16)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkWatchedDialog(
    seasons: List<TraktSeason>,
    episodes: Map<Int, List<TraktEpisode>>,
    watchedEpisodeNumbers: Map<Int, Set<Int>>,
    onDismiss: () -> Unit,
    onSubmit: (List<Int>) -> Unit,
    onLoadEpisodes: (Int) -> Unit
) {
    // 已勾选的集：季号 -> 已勾选集号集合
    val selectedEpisodes = remember {
        val initial = mutableMapOf<Int, MutableSet<Int>>()
        // 预填已看的集
        watchedEpisodeNumbers.forEach { (season, eps) ->
            initial[season] = eps.toMutableSet()
        }
        mutableStateOf(initial)
    }
    // 已展开的季
    val expandedSeasons = remember { mutableStateOf(setOf<Int>()) }
    // 加载失败的季集合（rememberSaveable，配置更改后保留，用于显示错误态与重试）
    var failedSeasons by rememberSaveable { mutableStateOf(setOf<Int>()) }
    // 收集信号时读取最新的 episodes，避免闭包捕获首次组合的旧 Map
    val currentEpisodes by rememberUpdatedState(episodes)

    // 收集 ViewModel 的加载失败信号，合并进失败集合（已成功到达的季忽略）
    LaunchedEffect(Unit) {
        markWatchedEpisodeLoadFailures.collect { seasonNumber ->
            if (currentEpisodes[seasonNumber] == null) {
                failedSeasons = failedSeasons + seasonNumber
            }
        }
    }

    val toggleSeasonExpand: (Int) -> Unit = { seasonNumber ->
        val isExpanding = seasonNumber !in expandedSeasons.value
        expandedSeasons.value = if (isExpanding) {
            // 展开时若未加载集信息，触发加载
            if (seasonNumber !in episodes) {
                onLoadEpisodes(seasonNumber)
            }
            expandedSeasons.value + seasonNumber
        } else {
            expandedSeasons.value - seasonNumber
        }
    }

    // 所有已勾选季的集列表均已加载完成后才允许确认，避免未加载季的勾选被静默丢弃
    val confirmEnabled = selectedEpisodes.value.entries.all { (seasonNum, epNums) ->
        epNums.isEmpty() || episodes[seasonNum] != null
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.detail_mark_watched_title),
        // 内容自带 LazyColumn，关掉组件那层 verticalScroll 让列表自己滚
        contentScrollable = false,
        content = {
            // content 槽在自己的组合作用域里，Checkbox 的触感句柄单独取一份
            val haptics = rememberAppHaptics()
            // 第0季（特别篇）放到最后
            val sortedSeasons = remember(seasons) {
                seasons.filter { it.number > 0 } + seasons.filter { it.number == 0 }
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(sortedSeasons.size, key = { sortedSeasons[it].number }) { index ->
                    val season = sortedSeasons[index]
                    val isSpecial = season.number == 0
                    val isExpanded = season.number in expandedSeasons.value
                    val seasonSelected = selectedEpisodes.value[season.number] ?: mutableSetOf()
                    val allEpisodeNumbers = episodes[season.number]?.map { it.number }
                        ?: (1..season.episode_count).toList()
                    val allSelected = allEpisodeNumbers.isNotEmpty() && allEpisodeNumbers.all { it in seasonSelected }
                    val watchedCount = seasonSelected.size
                    val totalCount = season.episode_count

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(DesignToken.Card)
                            .hapticClickable(
                                semantic = if (isExpanded) HapticSemantic.TOGGLE_OFF else HapticSemantic.TOGGLE_ON
                            ) { toggleSeasonExpand(season.number) },
                        shape = DesignToken.Card,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            // 季标题行
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = allSelected,
                                    onCheckedChange = { checked ->
                                        haptics.toggle(checked)
                                        // 季未展开时 episodes 可能为 null，需要先加载
                                        if (episodes[season.number] == null) {
                                            onLoadEpisodes(season.number)
                                        }
                                        val current = selectedEpisodes.value.toMutableMap()
                                        val seasonSet = current[season.number]?.toMutableSet() ?: mutableSetOf()
                                        if (checked) {
                                            // 优先用已加载的 episodes，否则用 episode_count 生成
                                            val epNumbers = episodes[season.number]?.map { it.number }
                                                ?: (1..season.episode_count).toList()
                                            epNumbers.forEach { seasonSet.add(it) }
                                        } else {
                                            seasonSet.clear()
                                        }
                                        current[season.number] = seasonSet
                                        selectedEpisodes.value = current
                                    },
                                    modifier = Modifier.size(28.dp)
                                )
                                SeasonBadge(season.number)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isSpecial) stringResource(R.string.detail_specials)
                                    else {
                                        val seasonLabel = stringResource(R.string.detail_season, season.number)
                                        if (season.first_aired.isNotBlank()) "$seasonLabel (${season.first_aired.take(4)})"
                                        else seasonLabel
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                // 已选/总数
                                Text(
                                    text = "$watchedCount/$totalCount",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (watchedCount > 0) WatchedGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // 进度条
                            if (totalCount > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                WatchedProgressBar(watchedCount, totalCount)
                            }

                            // 展开的集列表
                            if (isExpanded) {
                                val episodeList = episodes[season.number]
                                Spacer(modifier = Modifier.height(4.dp))
                                if (episodeList == null) {
                                    if (season.number in failedSeasons) {
                                        // 加载失败：错误文案 + 点击重试（点击清失败记录并重新加载）
                                        Text(
                                            text = stringResource(R.string.detail_load_error),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier
                                                .padding(start = 36.dp, bottom = 4.dp)
                                                .hapticClickable(semantic = HapticSemantic.TAP) {
                                                    failedSeasons = failedSeasons - season.number
                                                    onLoadEpisodes(season.number)
                                                }
                                        )
                                    } else {
                                        Text(
                                            text = stringResource(R.string.detail_loading_episodes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 36.dp, bottom = 4.dp)
                                        )
                                    }
                                } else {
                                    episodeList.forEach { ep ->
                                        val epSelected = ep.number in seasonSelected
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 28.dp, top = 2.dp, bottom = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = epSelected,
                                                onCheckedChange = { checked ->
                                                    haptics.toggle(checked)
                                                    val current = selectedEpisodes.value.toMutableMap()
                                                    val seasonSet = current[season.number]?.toMutableSet() ?: mutableSetOf()
                                                    if (checked) seasonSet.add(ep.number) else seasonSet.remove(ep.number)
                                                    current[season.number] = seasonSet
                                                    selectedEpisodes.value = current
                                                },
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.detail_episode, ep.number, ep.title),
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirm = DialogAction(
            label = stringResource(R.string.common_confirm),
            enabled = confirmEnabled,
            onClick = {
                // 收集所有已勾选集的 trakt ID；已看过的集仅预勾展示，提交时排除避免 Trakt history 重复
                val selectedIds = mutableListOf<Int>()
                selectedEpisodes.value.forEach { (seasonNum, epNums) ->
                    val watched = watchedEpisodeNumbers[seasonNum] ?: emptySet()
                    epNums.forEach { epNum ->
                        if (epNum !in watched) {
                            episodes[seasonNum]?.find { it.number == epNum }?.ids?.trakt?.let {
                                selectedIds.add(it)
                            }
                        }
                    }
                }
                // 过滤后没有新增集（全部已看过）则不提交直接关闭
                if (selectedIds.isNotEmpty()) {
                    onSubmit(selectedIds)
                } else {
                    onDismiss()
                }
            }
        ),
        dismiss = DialogAction(
            label = stringResource(R.string.common_cancel),
            onClick = { onDismiss() }
        )
    )
}
