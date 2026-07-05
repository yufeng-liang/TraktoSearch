package com.tracktosearch.ui.screen.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalActivePosterTmdbId
import com.tracktosearch.ui.component.LocalActivePosterTmdbIdSetter
import com.tracktosearch.ui.component.LocalIsCurrentTab
import com.tracktosearch.ui.component.LocalSharedTransitionScope

/** 通用电影卡片（复用豆瓣卡片样式） */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun MovieCard(
    title: String,
    posterPath: String?,
    year: String,
    rating: String?,
    subtitle: String? = null,
    isResolving: Boolean,
    isInWatchlist: Boolean = false,
    isWatched: Boolean = false,
    tmdbId: Int = 0,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    // 当前活跃海报 tmdbId（-1=都不启用 / 具体值=只有匹配的启用）
    val activePosterTmdbId = LocalActivePosterTmdbId.current
    val setActivePosterTmdbId = LocalActivePosterTmdbIdSetter.current
    // 只有"当前可见 tab"且"被用户点击激活"的海报才启用 sharedElement
    val isCurrentTab = LocalIsCurrentTab.current
    val enableShared = tmdbId > 0 && tmdbId == activePosterTmdbId && isCurrentTab
    val posterUrl = posterPath?.let {
        if (it.startsWith("http")) it
        else if (it.toIntOrNull() != null) null // TMDB ID 无法直接拼海报 URL，需要通过详情接口获取
        else "https://image.tmdb.org/t/p/w500$it"
    }

    Card(
        modifier = Modifier
            .width(105.dp)
            .clickable(enabled = !isResolving) {
                // 点击时记录当前海报为活跃状态，确保只有这个卡片参与转场
                if (tmdbId > 0) setActivePosterTmdbId(tmdbId)
                onClick()
            },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // 当 enableShared 且两个 scope 可用时，给海报 Box 加 sharedElement 修饰（与详情页海报配对）
            val posterBoxModifier = if (enableShared && sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .sharedElement(
                            rememberSharedContentState(key = "poster-$tmdbId"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                }
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            }
            Box(
                modifier = posterBoxModifier
            ) {
                if (posterUrl != null) {
                    val imageRequest = remember(posterUrl) {
                        ImageRequest.Builder(context)
                            .data(posterUrl)
                            .size(200)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title.take(2),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
                // 想看/已看角标（海报左上角）
                if (isWatched || isInWatchlist) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                color = if (isWatched) Color(0xCC000000)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (isWatched) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = Color.White
                            )
                            Text(
                                text = stringResource(R.string.cd_watched_badge),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Bookmark,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = Color.White
                            )
                            Text(
                                text = stringResource(R.string.cd_watchlist_badge),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                    }
                }
                if (rating != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF68BD5B)
                    ) {
                        Text(
                            text = rating,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                if (isResolving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    }
                }
                // 年份角标（海报右下角）
                if (year.isNotEmpty()) {
                    Text(
                        text = year,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.Black,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.9f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun ErrorRetryRow(error: String, onRetry: () -> Unit) {
    var showError by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.common_load_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        // 点击 info 图标弹出错误详情对话框
        IconButton(
            onClick = { showError = true },
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.error_retry), style = MaterialTheme.typography.labelSmall)
        }
    }

    if (showError) {
        AlertDialog(
            onDismissRequest = { showError = false },
            title = { Text(stringResource(R.string.error_detail_title)) },
            text = {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { showError = false }) {
                    Text(stringResource(R.string.error_detail_close))
                }
            }
        )
    }
}

@Composable
internal fun EmptyRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.empty_default),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/** 通用栏目标题 */
@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
internal fun doubanCategoryLabel(categoryId: String): String = when (categoryId) {
    "douban-movie" -> stringResource(R.string.discover_douban_new_movies)
    "douban-weekly" -> stringResource(R.string.discover_douban_weekly)
    "douban-top250" -> stringResource(R.string.discover_douban_top250)
    "douban-nowplaying" -> stringResource(R.string.discover_douban_nowplaying)
    else -> categoryId
}
