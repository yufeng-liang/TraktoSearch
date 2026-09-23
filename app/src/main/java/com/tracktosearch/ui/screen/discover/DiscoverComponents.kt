package com.tracktosearch.ui.screen.discover

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.tracktosearch.ui.component.AdaptiveTwoLineTitle
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.ui.component.AppErrorVariant
import com.tracktosearch.ui.component.AppVisualSurface
import com.tracktosearch.ui.component.DoubanRatingBadge
import com.tracktosearch.ui.component.EmptyStateCard
import com.tracktosearch.ui.component.RatingBadge
import com.tracktosearch.ui.component.VisualSurfaceKind
import com.tracktosearch.ui.component.YearBadge
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.component.SharedOrigin
import com.tracktosearch.ui.navigation.DetailSeedStore

/**
 * 发现页某个栏目的 origin。
 *
 * 各栏目共用一套卡片，同一部片子同时出现在「热门」和「为你推荐」是常态，
 * 所以按栏目 id 再分一层，把点击来源记进详情页首帧种子。
 */
internal fun discoverSectionOrigin(sectionId: String): String =
    SharedOrigin.of(SharedOrigin.DISCOVER, sectionId)

/** 通用电影卡片（复用豆瓣卡片样式） */
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
    /**
     * 列表数据里的原始标题（TMDB 的 original_title / original_name）。
     *
     * 详情页首帧用它填「原名」那一行；不传的话原名要等详情富化回来才插入，
     * 会把评分卡与下方内容整体下推一截。
     */
    originalTitle: String? = null,
    /** 评分来源是否为豆瓣：true 显示绿色填充样式，false 显示带星星样式 */
    isDoubanRating: Boolean = false,
    /** 本卡片所属栏目，见 [discoverSectionOrigin]；与 tmdbId 一起写入详情页首帧种子。 */
    origin: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // posterUrl 仅依赖 posterPath，包进 remember 避免每次重组重复 toIntOrNull/TmdbImageUrls.build 解析
    val posterUrl = remember(posterPath) {
        posterPath?.let {
            if (it.startsWith("http")) it
            else if (it.toIntOrNull() != null) null // TMDB ID 无法直接拼海报 URL，需要通过详情接口获取
            else TmdbImageUrls.build(it)
        }
    }
    // 点击时把海报与来源交给详情页：本栏目卡片的海报来自 TMDB 列表接口，
    // 详情页 peek 详情缓存必然落空
    val wrappedOnClick = remember(onClick, tmdbId, posterUrl, year, origin, originalTitle) {
        {
            if (tmdbId > 0) {
                DetailSeedStore.remember(
                    tmdbId,
                    posterUrl,
                    year.toIntOrNull(),
                    origin,
                    originalTitle = originalTitle
                )
            }
            onClick()
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "movie_card_scale"
    )
    val ratingValue = rating?.toDoubleOrNull()
    // 影视卡片与右上角评分徽章统一圆角规格，避免同一张海报出现两套圆角。
    val posterShape = RoundedCornerShape(7.dp)
    // 缓存顶部高光渐变 Brush,避免每次重组创建新实例
    val topHighlightBrush = remember { Brush.verticalGradient(0f to Color.White.copy(alpha = 0.15f), 1f to Color.Transparent) }

    Column(
        modifier = Modifier
            .width(105.dp)
            .scale(scale)
    ) {
        val posterBoxModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(posterShape)
        AppVisualSurface(
            kind = VisualSurfaceKind.Content,
            modifier = posterBoxModifier.hapticClickable(
                enabled = !isResolving,
                interactionSource = interactionSource,
                indication = null,
                // 点海报卡是看图/导航，不震（用户规则：点击图片无触感）
                semantic = null,
                onClick = wrappedOnClick
            ),
            shape = posterShape,
            backgroundColor = Color.Transparent,
            borderColor = Color.White.copy(alpha = 0.35f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
            if (posterUrl != null) {
                val imageRequest = remember(posterUrl) {
                    ImageRequest.Builder(context)
                        .data(posterUrl)
                        .size(342)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = imageRequest,
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxSize(),
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
            if (LocalVisualEffectMode.current == VisualEffectMode.BLUR) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .height(40.dp)
                        .clip(posterShape)
                        .background(topHighlightBrush)
                )
            }
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
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            if (ratingValue != null) {
                // 评分角标：RatingBadge/DoubanRatingBadge 组件自身已含 scrim 半透明底 + 圆角细边框
                // （与其他页面 PosterCard/AppBottomSheet 弹层的用法一致），这里直接复用统一规格。
                // 此前在组件外再套一层 chip 会导致双层黑底、两圈不同样式的边框叠加。
                val badgeModifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
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

/**
 * 发现页分区错误条。实现已收敛到共享的 [AppErrorState]（Inline 形态），
 * 这里只保留薄包装，13 处调用点不变。
 */
@Composable
internal fun ErrorRetryRow(error: String, onRetry: () -> Unit) {
    AppErrorState(
        message = error,
        onRetry = onRetry,
        variant = AppErrorVariant.Inline
    )
}

@Composable
internal fun EmptyRow() {
    EmptyStateCard(
        isDark = isAppDarkTheme(),
        title = stringResource(R.string.empty_default),
        icon = Icons.Rounded.Movie,
        modifier = Modifier.padding(vertical = 8.dp)
    )
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
