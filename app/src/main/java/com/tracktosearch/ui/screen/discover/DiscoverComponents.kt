package com.tracktosearch.ui.screen.discover

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
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
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.ui.component.LocalActivePosterClickSetter
import com.tracktosearch.ui.component.LocalActivePosterClickToken
import com.tracktosearch.ui.component.LocalActivePosterTmdbId
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalIsCurrentTab
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.AdaptiveTwoLineTitle
import com.tracktosearch.ui.component.DoubanRatingBadge
import com.tracktosearch.ui.component.RatingBadge
import com.tracktosearch.ui.component.YearBadge
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.neumorphicOuterShadow

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
    /** 评分来源是否为豆瓣：true 显示绿色填充样式，false 显示带星星样式 */
    isDoubanRating: Boolean = false,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    // 当前活跃海报 tmdbId(-1=都不启用 / 具体值=只有匹配的启用)
    val activePosterTmdbId = LocalActivePosterTmdbId.current
    val setActivePosterTmdbId = LocalActivePosterClickSetter.current
    // 当前活跃点击 token,每次点击递增;只有 token 匹配的卡片实例才启用 sharedElement
    val activeClickToken = LocalActivePosterClickToken.current
    var myClickToken by rememberSaveable { mutableStateOf(0) }
    // 只有"当前可见 tab"且"被用户点击激活"的海报才启用 sharedElement
    // clickToken 匹配避免同页面不同栏目下同 tmdbId 海报参与匹配(转场飘错根因)
    val isCurrentTab = LocalIsCurrentTab.current
    val enableShared = tmdbId > 0
        && tmdbId == activePosterTmdbId
        && isCurrentTab
        && myClickToken != 0
        && myClickToken == activeClickToken
    val posterUrl = posterPath?.let {
        if (it.startsWith("http")) it
        else if (it.toIntOrNull() != null) null // TMDB ID 无法直接拼海报 URL，需要通过详情接口获取
        else TmdbImageUrls.build(it)
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "movie_card_scale"
    )
    val ratingValue = rating?.toDoubleOrNull()
    val isDark = isAppDarkTheme()
    val posterShape = RoundedCornerShape(16.dp)
    // 缓存顶部高光渐变 Brush,避免每次重组创建新实例
    val topHighlightBrush = remember { Brush.verticalGradient(0f to Color.White.copy(alpha = 0.15f), 1f to Color.Transparent) }

    Column(
        modifier = Modifier
            .width(105.dp)
            .scale(scale)
    ) {
        // 当 enableShared 且两个 scope 可用时，给海报 Box 加 sharedElement 修饰（与详情页海报配对）
        val posterBoxModifier = if (enableShared && sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
            with(sharedTransitionScope) {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .sharedElement(
                        rememberSharedContentState(key = "poster-$tmdbId"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                    .clip(posterShape)
            }
        } else {
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(posterShape)
        }
        Box(
            modifier = posterBoxModifier
                .neumorphicOuterShadow(
                    shape = posterShape,
                    isDark = isDark,
                    elevation = 5.dp,
                    darkAlpha = if (isDark) 0.38f else 0.20f,
                    blurRadius = 16.dp,
                    shadowOffset = 5.dp
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.35f),
                    shape = posterShape
                )
                .clickable(
                    enabled = !isResolving,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        // 点击时记录当前海报为活跃状态,并获取新 token,确保只有这个卡片参与转场
                        if (tmdbId > 0) {
                            myClickToken = setActivePosterTmdbId(tmdbId)
                        }
                        onClick()
                    }
                )
        ) {
            if (posterUrl != null) {
                val imageRequest = remember(posterUrl) {
                    ImageRequest.Builder(context)
                        .data(posterUrl)
                        .size(264)
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
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title.take(2),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            // 顶部白色高光（C方案玻璃质感）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .height(40.dp)
                    .clip(posterShape)
                    .background(topHighlightBrush)
            )
            // 想看/已看角标（海报左上角）
            if (isWatched || isInWatchlist) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            color = if (isWatched) Color(0xCC000000)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                        )
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (isWatched) {
                        Icon(
                            imageVector = Icons.Rounded.Visibility,
                            contentDescription = stringResource(R.string.cd_watched_badge),
                            modifier = Modifier.size(12.dp),
                            tint = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Bookmark,
                            contentDescription = stringResource(R.string.cd_watchlist_badge),
                            modifier = Modifier.size(10.dp),
                            tint = Color.White
                        )
                    }
                }
            }
            if (ratingValue != null) {
                val badgeModifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                if (isDoubanRating) {
                    DoubanRatingBadge(rating = ratingValue, modifier = badgeModifier)
                } else {
                    RatingBadge(rating = ratingValue, modifier = badgeModifier)
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
            // 年份角标（海报右下角）：白色无填充 + 柔影
            if (year.isNotEmpty()) {
                YearBadge(
                    year = year,
                    fontSize = 10,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            AdaptiveTwoLineTitle(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                ),
                maxFontSize = 12.sp,
                minFontSize = 12.sp
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
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
            modifier = Modifier.clickable { showError = true },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        // 点击 info 图标弹出错误详情对话框
        IconButton(
            onClick = { showError = true },
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = stringResource(R.string.error_detail_title),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.error_retry), style = MaterialTheme.typography.labelSmall)
        }
    }

    if (showError) {
        AlertDialog(
            onDismissRequest = { showError = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
