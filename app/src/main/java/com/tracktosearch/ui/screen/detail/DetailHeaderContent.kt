package com.tracktosearch.ui.screen.detail

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbVideo
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.data.util.PosterColorExtractor
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch

// ==================== 头部内容 ====================

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun DetailHeaderContent(
    uiState: DetailUiState,
    tmdbId: Int,
    isMarkedWatched: Boolean,
    isMarkingWatched: Boolean,
    onToggleWatched: () -> Unit,
    isMarkedWatchlist: Boolean,
    isMarkingWatchlist: Boolean,
    onToggleWatchlist: () -> Unit,
    onShowRatingDialog: () -> Unit = {},
    onDismissRatingDialog: () -> Unit = {},
    onRatingSelected: (Int?) -> Unit,
    onPosterClick: () -> Unit = {},
    onPersonClick: (personId: Int, personName: String, profileUrl: String?) -> Unit = { _, _, _ -> },
    onToggleSeason: (Int) -> Unit = {},
    onToggleEpisodeWatched: (seasonNumber: Int, episodeNumber: Int, episodeTraktId: Int) -> Unit = { _, _, _ -> },
    onVideoClick: (TmdbVideo) -> Unit = {},
    onBackdropClick: (Int) -> Unit = {},
    onShowAllVideos: () -> Unit = {},
    onCollectionMovieClick: (tmdbId: Int, title: String) -> Unit = { _, _ -> },
    // 海报主色调提取相关:用于在海报加载成功后提取主色,回调通知 ViewModel 更新沉浸式背景
    posterColorExtractor: PosterColorExtractor,
    onPosterColorExtracted: (Color) -> Unit,
    sectionVisible: DetailSectionVisibility = DetailSectionVisibility(),
    // 头部下方内容(cast/视频/简介/季集)的透明度,用于"沉浸背景先现,内容后显"淡入效果
    // 1f=完全显示,0f=隐藏;海报+标题+按钮始终不透明
    contentAlpha: Float = 1f
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // 根据海报主色调亮度自适应文字颜色,增强沉浸背景下的可读性
    // 亮色海报 → 深色文字;暗色海报 → 浅色文字;无海报色 → 回退主题色
    val posterColor = uiState.posterDominantColor
    val onPosterColor = posterColor?.let { c ->
        if (c.luminance() > 0.5f) Color.Black.copy(alpha = 0.92f) else Color.White
    } ?: MaterialTheme.colorScheme.onSurface
    val onPosterVariantColor = posterColor?.let { c ->
        if (c.luminance() > 0.5f) Color.Black.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.72f)
    } ?: MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 海报（2:3 比例，高度跟随右侧固定信息区域，宽度按比例计算不跳变）
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (uiState.posterUrl != null) Modifier.clickable { onPosterClick() } else Modifier)
                ) {
                    if (uiState.posterUrl != null) {
                        var posterScale by remember { mutableFloatStateOf(1f) }
                        val sharedTransitionScope = LocalSharedTransitionScope.current
                        val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
                        val posterModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
                            with(sharedTransitionScope) {
                                Modifier
                                    .sharedElement(
                                        rememberSharedContentState(key = "poster-$tmdbId"),
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                                    .fillMaxSize()
                            }
                        } else {
                            Modifier.fillMaxSize()
                        }
                        AsyncImage(
                            model = remember(uiState.posterUrl) {
                                ImageRequest.Builder(context)
                                    .data(uiState.posterUrl)
                                    .size(264)
                                    // 与 MovieCard 保持一致:转场时不要图片淡入叠加在
                                    // sharedElement 容器动画上,避免双重动画看起来卡顿
                                    .crossfade(false)
                                    .listener(
                                        onSuccess = { _, result ->
                                            // 图片加载成功后提取主色调,用于沉浸式背景渐变
                                            uiState.posterUrl?.let { url ->
                                                val bitmap = result.drawable.toBitmap()
                                                scope.launch {
                                                    val argb = posterColorExtractor.extractDominantColor(url, bitmap)
                                                    if (argb != 0L) {
                                                        onPosterColorExtracted(Color(argb))
                                                    }
                                                }
                                            }
                                        }
                                    )
                                    .build()
                            },
                            contentDescription = uiState.displayTitle,
                            contentScale = ContentScale.Crop,
                            modifier = posterModifier
                                .graphicsLayer(scaleX = posterScale, scaleY = posterScale)
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, _, zoom, _ ->
                                        posterScale = (posterScale * zoom).coerceIn(1f, 4f)
                                    }
                                }
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // 状态标签：右下角圆角矩形
                if (uiState.status.isNotEmpty()) {
                    StatusRibbon(
                        status = uiState.status,
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 标题/类型/日期/评分/标记已看
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                // 标题
                Box(modifier = Modifier.height(28.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        text = uiState.displayTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = onPosterColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 原名
                Box(modifier = Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
                    if (uiState.originalTitle.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.detail_original_title, uiState.originalTitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = onPosterVariantColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 类型
                Box(modifier = Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
                    if (uiState.genres.isNotEmpty()) {
                        Text(
                            text = uiState.genres,
                            style = MaterialTheme.typography.bodySmall,
                            color = onPosterVariantColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 国家
                Box(modifier = Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
                    if (uiState.country.isNotEmpty()) {
                        Text(
                            text = uiState.country,
                            style = MaterialTheme.typography.bodySmall,
                            color = onPosterVariantColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 上映日期 + 时长
                Box(modifier = Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
                    val dateText = if (!uiState.releaseDate.isEmpty()) {
                        if (uiState.releaseDate.length >= 10) {
                            "${uiState.releaseDate.substring(0, 4)}-${uiState.releaseDate.substring(5, 7)}-${uiState.releaseDate.substring(8, 10)}"
                        } else {
                            uiState.releaseDate
                        }
                    } else if (uiState.year != null) {
                        stringResource(R.string.detail_year_suffix, uiState.year)
                    } else ""
                    val runtimeText = if (uiState.runtime != null && uiState.runtime > 0) {
                        val hours = uiState.runtime / 60
                        val minutes = uiState.runtime % 60
                        if (hours > 0) {
                            " (${stringResource(R.string.detail_runtime_hours, hours, minutes)})"
                        } else {
                            " (${stringResource(R.string.detail_runtime_minutes, minutes)})"
                        }
                    } else ""
                    if (dateText.isNotEmpty() || runtimeText.isNotEmpty()) {
                        Text(
                            text = dateText + runtimeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = onPosterVariantColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 多平台评分（固定高度区域）
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.height(46.dp)) {
                    if (uiState.ratings != null) {
                        RatingsRow(uiState.ratings)
                    } else {
                        // 骨架占位
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.weight(1f).height(20.dp)
                                ) {}
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.weight(1f).height(20.dp)
                                ) {}
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.weight(1f).height(20.dp)
                                ) {}
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.weight(1f).height(20.dp)
                                ) {}
                            }
                        }
                    }
                }
                // 用户评分控件（始终预留固定高度避免布局跳动）
                Box(modifier = Modifier.height(56.dp)) {
                    if (uiState.userRating != null) {
                        UserRatingBar(
                            userRating = uiState.userRating,
                            isRating = uiState.isRating,
                            isRatingLoading = uiState.isRatingLoading,
                            onClick = onShowRatingDialog
                        )
                    }
                }
                // 标记已看按钮 + 想看按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // 左半区：想看按钮
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Surface(
                            onClick = if (isMarkingWatchlist) ({}) else ({
                                view.performHaptic(HapticType.TICK)
                                onToggleWatchlist()
                            }),
                            enabled = !isMarkingWatchlist,
                            shape = RoundedCornerShape(16.dp),
                            border = if (!isMarkedWatchlist && !isMarkingWatchlist) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)) else null,
                            color = when {
                                isMarkingWatchlist -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                isMarkedWatchlist -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                            },
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                            modifier = Modifier.height(32.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (isMarkingWatchlist) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isMarkedWatchlist) Icons.Rounded.Check else Icons.Rounded.BookmarkBorder,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint = if (isMarkedWatchlist) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = when {
                                        isMarkingWatchlist -> stringResource(R.string.detail_mark_processing)
                                        isMarkedWatchlist -> stringResource(R.string.detail_marked_watchlist)
                                        else -> stringResource(R.string.detail_mark_watchlist)
                                    },
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                                    color = when {
                                        isMarkingWatchlist -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        isMarkedWatchlist -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                    // 右半区：已看按钮（起点对齐 RT 评分）
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Surface(
                            onClick = if (isMarkingWatched) ({}) else ({
                                view.performHaptic(HapticType.TICK)
                                onToggleWatched()
                            }),
                            enabled = !isMarkingWatched,
                            shape = RoundedCornerShape(16.dp),
                            border = if (!isMarkedWatched && !isMarkingWatched) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)) else null,
                            color = when {
                                isMarkingWatched -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                isMarkedWatched -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                            },
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                            modifier = Modifier.height(32.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (isMarkingWatched) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isMarkedWatched) Icons.Rounded.Check else Icons.Rounded.Visibility,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint = if (isMarkedWatched) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = when {
                                        isMarkingWatched -> stringResource(R.string.detail_mark_processing)
                                        isMarkedWatched -> stringResource(R.string.detail_marked_watched)
                                        else -> stringResource(R.string.detail_mark_watched)
                                    },
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                                    color = when {
                                        isMarkingWatched -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        isMarkedWatched -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // 第二行：演职员（海报下方独立一行，左对齐，始终预留空间避免布局跳动）
        // 头部下方内容(cast/视频/简介/系列/季集)统一淡入,营造"沉浸背景先现,内容后显"效果
        Column(modifier = Modifier.alpha(contentAlpha)) {
        if (sectionVisible.cast) {
        val hasCredits = uiState.cast.isNotEmpty() || uiState.crew.isNotEmpty()
        var showFullCast by rememberSaveable { mutableStateOf(false) }
        Column(
            modifier = Modifier.padding(bottom = 14.dp)
        ) {
            if (hasCredits) {
                CrewSection(
                    cast = uiState.cast.take(10),
                    crew = uiState.crew,
                    onShowAll = { showFullCast = true },
                    onPersonClick = onPersonClick
                )
            } else {
                // 加载中占位：固定高度骨架屏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.width(56.dp).height(16.dp)
                    ) {}
                    Spacer(modifier = Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.width(48.dp).height(14.dp)
                    ) {}
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    repeat(5) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(width = 68.dp, height = 95.dp)
                            ) {}
                            Spacer(modifier = Modifier.height(5.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.width(56.dp).height(10.dp)
                            ) {}
                            Spacer(modifier = Modifier.height(2.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.width(40.dp).height(8.dp)
                            ) {}
                        }
                    }
                }
            }
            if (showFullCast) {
                FullCastCrewSheet(
                    cast = uiState.cast,
                    crew = uiState.crew,
                    onDismiss = { showFullCast = false },
                    onPersonClick = onPersonClick
                )
            }
        }
        } // end if (sectionVisible.cast)

        // 预告片与截图横向滑动栏
        if (sectionVisible.videosImages && (uiState.videos.isNotEmpty() || uiState.backdrops.isNotEmpty() || uiState.isLoadingVideosImages)) {
            if (uiState.videos.isNotEmpty() || uiState.backdrops.isNotEmpty()) {
                VideosAndImagesSection(
                    videos = uiState.videos,
                    backdrops = uiState.backdrops,
                    onVideoClick = onVideoClick,
                    onBackdropClick = onBackdropClick,
                    onShowAll = onShowAllVideos
                )
            } else {
                // 骨架屏占位，防止加载后内容跳变
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(100.dp)
                                .height(18.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        )
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        )
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp)
                    ) {
                        items(3) {
                            Box(
                                modifier = Modifier
                                    .width(240.dp)
                                    .height(135.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            )
                        }
                    }
                }
            }
        }

        // 简介标签 + 折叠/展开正文
        if (sectionVisible.overview) {
            if (uiState.overview.isNotEmpty()) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        text = "${stringResource(R.string.detail_overview_label)}：",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    ExpandableText(text = uiState.overview)
                }
            } else if (uiState.isLoading) {
                // 简介骨架占位，防止加载后推下下方内容
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    repeat(3) { index ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(
                                    when (index) {
                                        0 -> 1f
                                        1 -> 0.95f
                                        else -> 0.7f
                                    }
                                )
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        )
                        if (index < 2) Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }

        // 系列卡片（放在简介下方、季/集之前）
        val collection = uiState.collectionInfo
        if (collection != null && collection.parts.size > 1) {
            CollectionSection(
                collection = collection,
                currentTmdbId = tmdbId,
                onMovieClick = onCollectionMovieClick
            )
        }

        // 季/集信息（仅电视剧，放在简介下方）
        if (uiState.seasons.isNotEmpty()) {
            SeasonsSection(
                seasons = uiState.seasons,
                episodes = uiState.episodes,
                expandedSeasons = uiState.expandedSeasons,
                watchedEpisodeNumbers = uiState.watchedEpisodeNumbers,
                togglingEpisode = uiState.togglingEpisode,
                onToggleSeason = onToggleSeason,
                onToggleEpisodeWatched = onToggleEpisodeWatched
            )
        }
        } // end Column(alpha = contentAlpha)
    }
}

// ==================== 折叠展开文本 ====================

@Composable
internal fun ExpandableText(text: String, maxLines: Int = 3) {
    var expanded by remember { mutableStateOf(false) }
    var isOverflowing by remember { mutableStateOf(false) }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (!expanded) {
                isOverflowing = result.hasVisualOverflow || result.lineCount > maxLines
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (isOverflowing || expanded) expanded = !expanded
            }
    )
}

// ==================== 状态绑带 ====================

/** 状态绑带颜色 */
private fun getStatusColor(status: String): Color {
    return when (status) {
        "Released" -> Color(0xFF4CAF50)
        "Returning Series" -> Color(0xFF4CAF50)
        "In Production" -> Color(0xFFFF9800)
        "Post Production" -> Color(0xFFFF9800)
        "Pilot" -> Color(0xFFFF9800)
        "Planned" -> Color(0xFF2196F3)
        "Rumored" -> Color(0xFF9C27B0)
        "Canceled" -> Color(0xFFF44336)
        "Ended" -> Color(0xFF9E9E9E)
        else -> Color(0xFF757575)
    }
}

/** 状态标签：右下角圆角矩形 */
@Composable
private fun StatusRibbon(status: String, modifier: Modifier = Modifier) {
    val backgroundColor = getStatusColor(status)
    val displayText = getStatusDisplayText(status)
    Surface(
        modifier = modifier.padding(4.dp),
        shape = RoundedCornerShape(4.dp),
        color = backgroundColor.copy(alpha = 0.9f)
    ) {
        Text(
            text = displayText,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** 将 TMDB 英文状态映射为本地化显示文本 */
@Composable
private fun getStatusDisplayText(status: String): String {
    val resId = when (status) {
        "Released" -> R.string.status_released
        "Returning Series" -> R.string.status_returning_series
        "In Production" -> R.string.status_in_production
        "Post Production" -> R.string.status_post_production
        "Pilot" -> R.string.status_pilot
        "Planned" -> R.string.status_planned
        "Rumored" -> R.string.status_rumored
        "Canceled" -> R.string.status_canceled
        "Ended" -> R.string.status_ended
        else -> return status
    }
    return stringResource(resId)
}
