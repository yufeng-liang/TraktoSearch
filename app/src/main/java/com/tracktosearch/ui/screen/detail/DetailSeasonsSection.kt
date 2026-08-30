package com.tracktosearch.ui.screen.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.remote.tmdb.dto.TmdbCollectionResponse
import com.tracktosearch.data.remote.trakt.dto.TraktEpisode
import com.tracktosearch.data.remote.trakt.dto.TraktSeason
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.neumorphicOuterShadow
import com.tracktosearch.ui.component.usesNeumorphicDecoration
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.theme.WatchedGreen

// ==================== 季/集信息 ====================

/** 季号标签（S1/S2/SP） */
@Composable
internal fun SeasonBadge(seasonNumber: Int) {
    val (text, bgColor, textColor) = if (seasonNumber == 0) {
        Triple("SP", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
    } else {
        Triple("S$seasonNumber", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** 已看进度条 */
@Composable
internal fun WatchedProgressBar(watchedCount: Int, totalCount: Int) {
    val progress = if (totalCount > 0) watchedCount.toFloat() / totalCount else 0f
    val barColor = if (watchedCount > 0) WatchedGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    Column {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
internal fun SeasonsSection(
    seasons: List<TraktSeason>,
    episodes: Map<Int, List<TraktEpisode>>,
    expandedSeasons: Set<Int>,
    watchedEpisodeNumbers: Map<Int, Set<Int>>,
    togglingEpisode: Pair<Int, Int>?,
    onToggleSeason: (Int) -> Unit,
    onToggleEpisodeWatched: (seasonNumber: Int, episodeNumber: Int, episodeTraktId: Int) -> Unit
) {
    val view = LocalView.current
    val isDark = isAppDarkTheme()
    // 过滤掉第0季（特别篇），单独展示为"特别篇"
    val regularSeasons = seasons.filter { it.number > 0 }
    val specialSeasons = seasons.filter { it.number == 0 }
    val allSeasons = regularSeasons + specialSeasons

    // 默认显示前3季，点击展开全部
    var showAllSeasons by rememberSaveable { mutableStateOf(false) }
    val defaultShowCount = 3
    val visibleSeasons = if (showAllSeasons || allSeasons.size <= defaultShowCount) {
        allSeasons
    } else {
        allSeasons.take(defaultShowCount)
    }
    val hasMore = allSeasons.size > defaultShowCount

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.detail_seasons),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = LocalContentColor.current,
                modifier = Modifier.weight(1f)
            )
            // 展开/折叠更多季（与标题同行）
            if (hasMore) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { view.performHaptic(HapticType.TICK); showAllSeasons = !showAllSeasons }
                        .padding(vertical = 2.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showAllSeasons) stringResource(R.string.detail_seasons_collapse)
                        else stringResource(R.string.detail_seasons_show_all, allSeasons.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = if (showAllSeasons) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 正常季 + 特别篇，统一用卡片样式
        visibleSeasons.forEach { season ->
            val isSpecial = season.number == 0
            if (isSpecial && season.episode_count == 0) return@forEach

            val isExpanded = season.number in expandedSeasons
            val watchedCount = (watchedEpisodeNumbers[season.number]?.size ?: 0)
            val totalCount = season.episode_count
            val seasonShape = RoundedCornerShape(12.dp)
            val useNeumorphicDecoration = usesNeumorphicDecoration(LocalVisualEffectMode.current)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .then(
                        if (useNeumorphicDecoration) {
                            Modifier.neumorphicOuterShadow(
                                shape = seasonShape,
                                isDark = isDark,
                                elevation = 4.dp,
                                darkAlpha = if (isDark) 0.36f else 0.16f,
                                blurRadius = 9.dp,
                                shadowOffset = 2.dp
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clip(seasonShape)
                    .clickable { view.performHaptic(HapticType.CLICK); onToggleSeason(season.number) },
                shape = seasonShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isDark) {
                        Color.White.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                    }
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    // 季标题行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SeasonBadge(season.number)
                        Spacer(modifier = Modifier.width(8.dp))
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
                        // 已看/总数
                        Text(
                            text = "$watchedCount/$totalCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (watchedCount > 0) WatchedGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 进度条
                    if (totalCount > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        WatchedProgressBar(watchedCount, totalCount)
                    }

                    // 展开的集列表
                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        val episodeList = episodes[season.number] ?: emptyList()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (episodeList.isEmpty()) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp).padding(2.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                episodeList.forEach { ep ->
                                    EpisodeRow(
                                        episode = ep,
                                        seasonNumber = season.number,
                                        isWatched = ep.number in (watchedEpisodeNumbers[season.number] ?: emptySet()),
                                        isToggling = togglingEpisode == Pair(season.number, ep.number),
                                        onToggleWatched = {
                                            onToggleEpisodeWatched(season.number, ep.number, ep.ids.trakt)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    }
}

/** 单集行：集号+标题 + 已看切换图标 */
@Composable
internal fun EpisodeRow(
    episode: TraktEpisode,
    seasonNumber: Int,
    isWatched: Boolean,
    isToggling: Boolean,
    onToggleWatched: () -> Unit
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { view.performHaptic(HapticType.CLICK); onToggleWatched() }
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.detail_episode, episode.number, episode.title),
            style = MaterialTheme.typography.bodySmall,
            color = if (isWatched) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (isToggling) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp).padding(2.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = if (isWatched) stringResource(R.string.detail_watched) else stringResource(R.string.detail_not_watched),
                modifier = Modifier.size(18.dp),
                tint = if (isWatched) WatchedGreen
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
        }
    }
}

// ==================== 系列卡片 ====================

@Composable
internal fun CollectionSection(
    collection: TmdbCollectionResponse,
    currentTmdbId: Int,
    onMovieClick: (tmdbId: Int, title: String) -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        DetailSectionHeader(title = stringResource(R.string.detail_collection_title, collection.name))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            items(
                count = collection.parts.size,
                key = { collection.parts[it].id },
                contentType = { "collection_movie" }
            ) { index ->
                val part = collection.parts[index]
                val posterUrl = part.poster_path?.let { TmdbImageUrls.build(it, TmdbImageUrls.W185) }
                val isCurrent = part.id == currentTmdbId
                Column(
                    modifier = Modifier
                        .width(80.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !isCurrent) { onMovieClick(part.id, part.title) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = if (isCurrent) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .width(80.dp)
                            .height(110.dp)
                    ) {
                        if (posterUrl != null) {
                            AsyncImage(
                                model = posterUrl,
                                contentDescription = part.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = part.title.ifEmpty { part.original_title },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
