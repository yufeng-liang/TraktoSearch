package com.tracktosearch.ui.screen.detail
import com.tracktosearch.ui.component.hazeModalSurface

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic

// ==================== 标记已看弹窗（电视剧季/集勾选） ====================

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
    val view = LocalView.current
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

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.hazeModalSurface(ultraThick = true),
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f),
        title = { Text(stringResource(R.string.detail_mark_watched_title)) },
        text = {
            // 第0季（特别篇）放到最后
            val sortedSeasons = remember(seasons) {
                seasons.filter { it.number > 0 } + seasons.filter { it.number == 0 }
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 400.dp)
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
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { toggleSeasonExpand(season.number) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
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
                                        view.performHaptic(HapticType.CLICK)
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
                                    color = if (watchedCount > 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    Text(
                                        text = stringResource(R.string.detail_loading_episodes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 36.dp, bottom = 4.dp)
                                    )
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
                                                    view.performHaptic(HapticType.CLICK)
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
        confirmButton = {
            TextButton(onClick = {
                // 收集所有已勾选集的 trakt ID
                val selectedIds = mutableListOf<Int>()
                selectedEpisodes.value.forEach { (seasonNum, epNums) ->
                    epNums.forEach { epNum ->
                        episodes[seasonNum]?.find { it.number == epNum }?.ids?.trakt?.let {
                            selectedIds.add(it)
                        }
                    }
                }
                onSubmit(selectedIds)
            }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
