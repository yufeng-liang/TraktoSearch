package com.tracktosearch.ui.component

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.hapticCombinedClickable
import com.tracktosearch.ui.navigation.DetailSeedStore

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MovieCard(
    title: String,
    year: Int?,
    genres: String,
    posterUrl: String?,
    tmdbId: Int,
    onClick: () -> Unit,
    /**
     * 本卡片所属的列表，见 [SharedOrigin]。
     *
     * 与 tmdbId 一起写入 [DetailSeedStore]，详情页用它做首帧种子。
     * 同一屏里两个列表都有这部片子时，靠这个值区分点的是哪一张，因此每个调用点都要显式给出，
     * 没有默认值。
     */
    origin: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isInWatchlist: Boolean = false,
    isWatched: Boolean = false,
    showStatusText: Boolean = true,
    /**
     * 海报位的 shimmer 状态，仅在 [posterUrl] 还是 null 时生效。
     *
     * 用于「条目已经拿到标题、海报还在路上」的列表：给一格平的灰底不如给一格微光，
     * 用户能看出是在加载而不是这张没有海报。调用方用 [rememberShimmer] 全列表共享一份。
     */
    posterShimmer: ShimmerState? = null
) {
    val context = LocalContext.current
    val colorExtractor = remember(context) { posterColorExtractor(context) }
    // 海报加载成功后延迟提取主色写入缓存，详情页首帧即可取到沉浸色。
    // 卡片海报源图已降级 w342(列表数据 posterUrl),解码 342 与源图 1:1,显示约 318px 清晰;
    // 详情页 header 改用 w780 独立高清图(见 DetailHeaderContent),不复用卡片解码结果
    val onPosterLoaded = rememberPosterColorExtraction(posterUrl, colorExtractor)
    val shouldExtractColor = remember(posterUrl, colorExtractor) {
        posterUrl != null &&
            colorExtractor.peekCachedColor(posterUrl) == null &&
            colorExtractor.peekCachedColorCandidates(posterUrl) == null
    }
    val imageRequest = remember(posterUrl, shouldExtractColor) {
        ImageRequest.Builder(context)
            .data(posterUrl)
            .size(342)
            .crossfade(false)
            .apply {
                if (shouldExtractColor) {
                    listener(
                        onSuccess = { _, result ->
                            // Coil 已经解码出 BitmapDrawable 时直接复用位图，避免图片批量完成时
                            // 在主线程额外复制 Bitmap；非 BitmapDrawable 才走兼容转换。
                            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                                ?: result.drawable.toBitmap()
                            onPosterLoaded(bitmap)
                        }
                    )
                }
            }
            .build()
    }

    // 包装点击回调：把已渲染的海报/年份/来源交给详情页做首帧种子
    // remember 包裹避免每次重组创建新 lambda 实例,减少不必要 recomposition
    val wrappedOnClick = remember(onClick, tmdbId, posterUrl, year, origin) {
        {
            if (tmdbId > 0) {
                // 发现页等栏目的海报来自 TMDB 列表接口，不会写入详情缓存，详情页 peek 落空
                DetailSeedStore.remember(tmdbId, posterUrl, year, origin)
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

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .then(
                // 点击海报卡是「看图/导航」，用户规则不震；长按（多选/预览）仍是操作，保留
                if (onLongClick != null) {
                    Modifier.hapticCombinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        semantic = null,
                        onClick = wrappedOnClick,
                        onLongClick = onLongClick
                    )
                } else {
                    Modifier.hapticClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        semantic = null,
                        onClick = wrappedOnClick
                    )
                }
            ),
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            val posterShape = RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp)
            // 海报未到位时铺一层微光。shimmer 只在绘制阶段读进度，不触发重组；
            // 画在 clip 之后，圆角由 shape 直接参与绘制，不用再加一层图层。
            val placeholderModifier = if (posterUrl == null && posterShimmer != null) {
                Modifier.shimmer(posterShimmer, posterShape)
            } else {
                Modifier
            }
            val imageModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(posterShape)
                .then(placeholderModifier)

            Box {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = title,
                    modifier = imageModifier,
                    contentScale = ContentScale.Crop
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
                                imageVector = Icons.Rounded.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Bookmark,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = Color.White
                            )
                        }
                        if (showStatusText) {
                            Text(
                                text = stringResource(
                                    if (isWatched) R.string.cd_watched_badge else R.string.cd_watchlist_badge
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = Color.White
                            )
                        }
                    }
                }
                // 年份角标（海报右下角）：白色无填充 + 柔影
                if (year != null && year != 0) {
                    YearBadge(
                        year = "$year",
                        fontSize = 10,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }
            Column {
                AdaptiveTwoLineTitle(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxFontSize = 14.sp,
                    minFontSize = 12.sp,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 0.dp)
                )
                if (genres.isNotEmpty()) {
                    Text(
                        text = genres,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                    )
                }
            }
        }
    }
}
