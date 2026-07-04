package com.tracktosearch.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.res.stringResource
import com.tracktosearch.R

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun MovieCard(
    title: String,
    year: Int?,
    genres: String,
    posterUrl: String?,
    tmdbId: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isInWatchlist: Boolean = false,
    isWatched: Boolean = false
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    // 当前活跃海报 tmdbId（-1=都不启用 / 具体值=只有匹配的启用）
    val activePosterTmdbId = LocalActivePosterTmdbId.current
    val setActivePosterTmdbId = LocalActivePosterTmdbIdSetter.current
    // 只有"当前可见 tab"且"被用户点击激活"的海报才启用 sharedElement
    // isCurrentTab 避免 HorizontalPager 常驻的非当前 tab 同 tmdbId 海报参与匹配
    val isCurrentTab = LocalIsCurrentTab.current
    val enableShared = tmdbId == activePosterTmdbId && isCurrentTab
    val context = LocalContext.current
    val imageRequest = remember(posterUrl) {
        ImageRequest.Builder(context)
            .data(posterUrl)
            .size(200)
            .crossfade(false)
            .build()
    }

    // 包装点击回调：点击时记录当前海报为活跃状态
    val wrappedOnClick = {
        if (tmdbId > 0) setActivePosterTmdbId(tmdbId)
        onClick()
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = wrappedOnClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = wrappedOnClick)
                }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            val imageModifier = if (enableShared && sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    Modifier
                        .sharedElement(
                            rememberSharedContentState(key = "poster-$tmdbId"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                }
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            }

            Box {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = title,
                    modifier = imageModifier,
                    contentScale = ContentScale.Crop,
                    placeholder = null,
                    error = null,
                    fallback = null
                )

                // 海报左上角状态角标
                if (isWatched || isInWatchlist) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(8.dp))
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
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
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
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = Color.White
                            )
                        }
                    }
                }
                // 年份角标（海报右下角）
                if (year != null) {
                    Text(
                        text = "$year",
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
            Column(
                modifier = Modifier.padding(6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (genres.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = genres,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
