package com.tracktosearch.ui.screen.detail

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.remote.tmdb.dto.TmdbVideo
import com.tracktosearch.data.util.PosterColorExtractor
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.tracktosearch.ui.component.ActionButtonRow
import com.tracktosearch.ui.component.ActionItem
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.ui.component.AppErrorVariant
import com.tracktosearch.ui.component.LocalBackdrop
import com.tracktosearch.ui.component.backdropContentSource
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.zoomSharedSource
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import dev.chrisbanes.haze.HazeState
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
    // 分区加载失败时的重试入口（评分聚合 / 演职员 / 季信息）
    onRetryRatings: () -> Unit = {},
    onRetryCredits: () -> Unit = {},
    onRetrySeasons: () -> Unit = {},
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
    hazeState: HazeState,
    onHeaderAnchorBoundsChanged: (Rect) -> Unit = {},
    backdrop: LayerBackdrop? = null
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
    Column(
        modifier = Modifier
            // GLASS 模式将 tab 栏之上的全部头部内容注册为 Backdrop 采样源，
            // 使悬浮的顶栏按钮能采到真实内容（海报/标题/评分/演职员等）而非透明背景
            .backdropContentSource(backdrop)
            .padding(start = 16.dp, end = 16.dp, top = 32.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
                .padding(bottom = 12.dp)
                .onGloballyPositioned { onHeaderAnchorBoundsChanged(it.boundsInRoot()) },
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
                        // 用 Box 承载全屏查看转场的共享元素；AsyncImage 上保留导航用 sharedElement
                        // （双 key 嵌套是文档支持的组合模式）。
                        // 全屏查看这一侧用 caller-managed visibility：全屏 overlay 打开该 key 时
                        // 本侧置不可见，避免与 overlay 侧同时是 target 导致转场方向反转。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zoomSharedSource(
                                    key = "poster-zoom-bounds-$tmdbId",
                                    clipShape = RoundedCornerShape(8.dp)
                                )
                        ) {
                            SubcomposeAsyncImage(
                                model = remember(uiState.posterUrl) {
                                    // 详情页海报独立使用 w780 高清图:header 实际渲染约 525-700px,
                                    // w780 源图 + 780 解码 1:1 保证清晰(原 264 解码明显模糊)
                                    ImageRequest.Builder(context)
                                        .data(uiState.posterUrl?.let { TmdbImageUrls.swapSize(it, "w780") })
                                        .size(780)
                                        // 转场时不要图片淡入叠加在 sharedElement 容器动画上,避免双重动画看起来卡顿
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
                                    },
                                // 转场兜底:首次进入详情页 w780 可能需网络下载,
                                // loading 期间显示与列表卡片同 URL+size 的 w342 缩略图(内存缓存大概率命中),避免"闪空"
                                loading = {
                                    val thumbUrl = uiState.posterUrl?.let { TmdbImageUrls.swapSize(it, "w342") }
                                    if (thumbUrl != null) {
                                        AsyncImage(
                                            model = remember(thumbUrl) {
                                                ImageRequest.Builder(context)
                                                    .data(thumbUrl)
                                                    .size(342)
                                                    .crossfade(false)
                                                    .build()
                                            },
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
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
                    } else if (uiState.ratingsError) {
                        // 评分聚合失败：以前这里一直转圈，用户看不出是「这部片没有评分」还是「没加载上」
                        AppErrorState(
                            message = stringResource(R.string.detail_load_error),
                            onRetry = onRetryRatings,
                            variant = AppErrorVariant.Inline,
                            inlineLabel = stringResource(R.string.detail_load_error),
                            showDetail = false
                        )
                    } else {
                        RatingsLoadingPlaceholder(immersionColor = posterColor)
                    }
                }
                // 操作按钮组：想看 / 已看 / 评分
                // 按钮组属于 Glass overlay：置于采样源之外（LocalBackdrop=null），
                // 避免把自身 drawBackdrop 录回头部采样源造成 RenderThread 递归。
                CompositionLocalProvider(LocalBackdrop provides null) {
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
                    modifier = Modifier.padding(top = 8.dp),
                    verticalPadding = 5.dp
                )
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
                // 加载中占位：栏目标题与「全部」为静态文字直接显示，仅卡片区保留骨架
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.detail_cast_crew),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.detail_cast_all),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = { showFullCast = true })
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (uiState.creditsError) {
                        // 加载失败：以前失败也照样显示骨架，用户永远等不到内容也不知道该重试
                        AppErrorState(
                            message = stringResource(R.string.detail_load_error),
                            onRetry = onRetryCredits,
                            variant = AppErrorVariant.Inline,
                            inlineLabel = stringResource(R.string.detail_load_error),
                            showDetail = false
                        )
                    } else {
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
                // 骨架屏占位，防止加载后内容跳变；栏目标题为静态文字直接显示，「全部」随数据到达后出现
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.detail_videos_section),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
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
                // 简介骨架占位，防止加载后推下下方内容；「简介」标签为静态文字直接显示，仅正文保留骨架
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        text = stringResource(R.string.detail_overview_label),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
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
        } else if (uiState.seasonsError) {
            // 季信息加载失败：以前整个区块直接消失，用户分不清「这部剧没有季信息」和「没加载上」
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    text = stringResource(R.string.detail_seasons),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                AppErrorState(
                    message = stringResource(R.string.detail_load_error),
                    onRetry = onRetrySeasons,
                    variant = AppErrorVariant.Inline,
                    inlineLabel = stringResource(R.string.detail_load_error),
                    showDetail = false
                )
            }
        }
        } // end Column(alpha = contentAlpha)
    }
}

// ==================== 折叠展开文本 ====================

/**
 * 折叠/展开文本：折叠时正文用省略号截断，「展开」「收起」按钮都独占一行、右对齐放在正文下方，
 * 两种状态下按钮位置一致。按钮不再叠放在正文最后一行上，避免遮挡文字。
 * 点击整段正文或按钮均可切换，高度变化带动画。
 */
@Composable
internal fun ExpandableText(
    text: String,
    maxLines: Int = 3
) {
    val effectiveMaxLines = maxLines.coerceAtLeast(1)
    val expandLabel = stringResource(R.string.detail_text_expand)
    val collapseLabel = stringResource(R.string.detail_text_collapse)
    val primaryColor = MaterialTheme.colorScheme.primary
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bodyStyle = MaterialTheme.typography.bodyMedium

    var expanded by rememberSaveable(text, effectiveMaxLines) { mutableStateOf(false) }
    // 溢出检测由 onTextLayout 驱动：仅折叠且实际超出 maxLines 时置 true
    var hasOverflow by remember(text, effectiveMaxLines) { mutableStateOf(false) }
    // 仅溢出（或已展开）时才可点击；未溢出文本不响应点击、不显示按钮
    val canToggle = hasOverflow || expanded
    val toggleModifier = if (canToggle) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { expanded = !expanded }
    } else Modifier

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Text(
            text = text,
            style = bodyStyle,
            color = bodyColor,
            maxLines = if (expanded) Int.MAX_VALUE else effectiveMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layoutResult ->
                hasOverflow = !expanded && layoutResult.hasVisualOverflow
            },
            modifier = toggleModifier.fillMaxWidth()
        )
        // 「展开」「收起」共用同一个位置：独占一行、右对齐。
        // 旧实现把「展开」叠在正文最后一行右侧，会压住文字，故改为独立一行。
        if (canToggle) {
            Text(
                text = if (expanded) collapseLabel else expandLabel,
                style = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
                color = primaryColor,
                modifier = toggleModifier
                    .align(Alignment.End)
                    .padding(top = 4.dp)
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
