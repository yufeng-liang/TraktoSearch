package com.tracktosearch.ui.screen.detail

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.eygraber.seymour.SeymourText
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbVideo
import com.tracktosearch.data.util.PosterColorExtractor
import com.tracktosearch.ui.component.ActionButtonRow
import com.tracktosearch.ui.component.ActionItem
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import dev.chrisbanes.haze.HazeState
import kotlin.math.max
import kotlin.math.roundToInt
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
    onPersonClick: (personId: Int, personName: String, profileUrl: String?, avatarColor: Color?) -> Unit = { _, _, _, _ -> },
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
    contentAlpha: Float = 1f,
    hazeState: HazeState
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
            // 海报：fillMaxHeight 让海报高度跟随右侧信息列（含按钮组），
            // 实现海报底部与想看/已看/评分按钮底部对齐。
            // IntrinsicSize.Max 会多一次测量 pass，但进入卡顿已由 contentReady
            // 延迟组合优化抵消，视觉对齐优先。
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
                        // 用 Box 承载全屏查看转场的 sharedBounds；AsyncImage 上保留导航用 sharedElement
                        // （双 key 嵌套是文档支持的 sharedBounds+sharedElement 组合模式）
                        val boundsModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
                            with(sharedTransitionScope) {
                                Modifier.sharedBounds(
                                    rememberSharedContentState(key = "poster-zoom-bounds-$tmdbId"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(8.dp))
                                )
                            }
                        } else Modifier
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(boundsModifier)
                        ) {
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
                                                uiState.posterUrl.let { url ->
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
                        }
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
                Box(modifier = Modifier.height(64.dp)) {
                    if (
                        uiState.ratings != null &&
                        uiState.ratingSource != DetailRatingSource.UNKNOWN
                    ) {
                        RatingsRow(
                            ratings = uiState.ratings,
                            ratingSource = uiState.ratingSource,
                            immersionColor = posterColor
                        )
                    } else {
                        RatingsLoadingPlaceholder(immersionColor = posterColor)
                    }
                }
                // 操作按钮组：想看 / 已看 / 评分
                ActionButtonRow(
                    actions = listOf(
                        ActionItem(
                            icon = if (isMarkedWatchlist) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            label = stringResource(if (isMarkedWatchlist) R.string.detail_marked_watchlist else R.string.detail_mark_watchlist),
                            selected = isMarkedWatchlist,
                            enabled = !isMarkingWatchlist,
                            isLoading = isMarkingWatchlist,
                            onClick = {
                                view.performHaptic(HapticType.TICK)
                                onToggleWatchlist()
                            }
                        ),
                        ActionItem(
                            icon = if (isMarkedWatched) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            label = stringResource(if (isMarkedWatched) R.string.detail_marked_watched else R.string.detail_mark_watched),
                            selected = isMarkedWatched,
                            enabled = !isMarkingWatched,
                            isLoading = isMarkingWatched,
                            onClick = {
                                view.performHaptic(HapticType.TICK)
                                onToggleWatched()
                            }
                        ),
                        ActionItem(
                            icon = if (uiState.userRating != null && uiState.userRating > 0) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            label = stringResource(if (uiState.userRating != null && uiState.userRating > 0) R.string.detail_rated else R.string.detail_rate),
                            selected = uiState.userRating != null && uiState.userRating > 0,
                            enabled = !uiState.isRating,
                            isLoading = uiState.isRating,
                            onClick = {
                                view.performHaptic(HapticType.TICK)
                                onShowRatingDialog()
                            }
                        )
                    ),
                    hazeState = hazeState,
                    modifier = Modifier.padding(top = 8.dp),
                    verticalPadding = 5.dp
                )
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
                    onShowAll = onShowAllVideos,
                    sharedKeyPrefix = "backdrop-zoom-$tmdbId"
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
                        text = stringResource(R.string.detail_overview_label),
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

/**
 * 折叠/展开文本：折叠时在最后一行行尾显示「展开/收起」链接（流式内联，紧贴截断文字），
 * 链接前的文字用横向渐变淡出融入背景；点击链接展开/收起，高度变化带动画。
 * 截断与溢出处理由 seymour-text（SeymourText）完成。
 */
@Composable
internal fun ExpandableText(
    text: String,
    maxLines: Int = 3,
    fadeColor: Color? = null
) {
    val effectiveMaxLines = maxLines.coerceAtLeast(1)
    val expandLabel = stringResource(R.string.detail_text_expand)
    val collapseLabel = stringResource(R.string.detail_text_collapse)
    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = fadeColor ?: MaterialTheme.colorScheme.background
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current

    var expanded by rememberSaveable(text, effectiveMaxLines) { mutableStateOf(false) }
    // 折叠且溢出时显示渐隐遮罩（由 onTextLayout 驱动，展开时淡出）
    var showFade by remember(text, effectiveMaxLines) { mutableStateOf(false) }
    // 文本布局尺寸（px）：容器宽度、最后一行顶部与行高，用于渐变遮罩定位
    var textWidthPx by remember(text, effectiveMaxLines) { mutableFloatStateOf(0f) }
    var lineTopPx by remember(text, effectiveMaxLines) { mutableFloatStateOf(0f) }
    var lineHeightPx by remember(text, effectiveMaxLines) { mutableFloatStateOf(0f) }

    // 展开/收起链接宽度：链接紧贴最后一行行尾，渐变区右端 = 容器宽 - 链接宽
    val textMeasurer = rememberTextMeasurer()
    val linkWidthPx = remember(expandLabel, collapseLabel, bodyStyle) {
        maxOf(
            textMeasurer.measure(AnnotatedString(expandLabel), bodyStyle).size.width,
            textMeasurer.measure(AnnotatedString(collapseLabel), bodyStyle).size.width
        )
    }

    val fadeWidth = 48.dp
    val fadeWidthPx = with(density) { fadeWidth.toPx() }
    val fadeAlpha by animateFloatAsState(
        targetValue = if (showFade) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "expandableTextFade"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        SeymourText(
            onSeeMoreChange = { expanded = it },
            isSeeMoreExpanded = expanded,
            text = text,
            seeMoreText = expandLabel,
            seeLessText = collapseLabel,
            seeMoreMaxLines = effectiveMaxLines,
            seeLessMaxLines = Int.MAX_VALUE,
            style = bodyStyle,
            color = bodyColor,
            seeMoreStyle = SpanStyle(color = primaryColor),
            seeLessStyle = SpanStyle(color = primaryColor),
            onTextLayout = { layoutResult ->
                // 溢出检测（展开后无溢出，遮罩淡出）；行高取自任意一帧，首帧次帧一致
                textWidthPx = layoutResult.size.width.toFloat()
                showFade = !expanded && layoutResult.hasVisualOverflow
                if (layoutResult.lineCount > 0) {
                    val lastLine = (effectiveMaxLines - 1)
                        .coerceAtMost(layoutResult.lineCount - 1)
                    val lineTop = layoutResult.getLineTop(lastLine)
                    val lineBottom = layoutResult.getLineBottom(lastLine)
                    lineTopPx = lineTop
                    lineHeightPx = lineBottom - lineTop
                }
            }
        )
        // 渐隐遮罩：只覆盖最后一行行尾、链接左侧一小段，文字渐变融入背景
        if (showFade && textWidthPx > 0f && lineHeightPx > 0f) {
            val fadeRight = textWidthPx - linkWidthPx
            val fadeLeft = max(0f, fadeRight - fadeWidthPx)
            Box(
                modifier = Modifier
                    .offset { IntOffset(fadeLeft.roundToInt(), lineTopPx.roundToInt()) }
                    .width(with(density) { (fadeRight - fadeLeft).toDp() })
                    .height(with(density) { lineHeightPx.toDp() })
                    .graphicsLayer { alpha = fadeAlpha }
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, backgroundColor)
                        )
                    )
            )
        }
    }
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
