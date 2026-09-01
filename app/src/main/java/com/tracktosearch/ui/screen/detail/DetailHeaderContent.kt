package com.tracktosearch.ui.screen.detail

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
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
import com.tracktosearch.ui.component.rememberShimmer
import com.tracktosearch.ui.component.zoomSharedSource
import com.tracktosearch.ui.theme.onColorFor
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ==================== 头部内容 ====================

/** 头部海报宽度：固定 118dp（2:3 比例 → 177dp 高）。为什么不能再跟随右列见下方 Row 注释。 */
private val HEADER_POSTER_WIDTH = 118.dp

/** 海报高度，由 2:3 比例算出。右列靠它撑到同高，见右列注释。 */
private val HEADER_POSTER_HEIGHT = HEADER_POSTER_WIDTH * 1.5f

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
    onPosterClick: () -> Unit = {},
    onPersonClick: (personId: Int, personName: String, profileUrl: String?, avatarColor: Color?) -> Unit = { _, _, _, _ -> },
    onToggleSeason: (Int) -> Unit = {},
    onToggleEpisodeWatched: (seasonNumber: Int, episodeNumber: Int, episodeTraktId: Int) -> Unit = { _, _, _ -> },
    // 分区加载失败时的重试入口（评分聚合 / 演职员 / 季信息）
    onRetryRatings: () -> Unit = {},
    onRetryCredits: () -> Unit = {},
    onRetrySeasons: () -> Unit = {},
    // 预告片/截图区加载失败的重试入口
    onRetryVideos: () -> Unit = {},
    onVideoClick: (TmdbVideo) -> Unit = {},
    onBackdropClick: (Int) -> Unit = {},
    onShowAllVideos: () -> Unit = {},
    onCollectionMovieClick: (tmdbId: Int, title: String) -> Unit = { _, _ -> },
    // 海报主色调提取相关:用于在海报加载成功后提取主色,回调通知 ViewModel 更新沉浸式背景
    posterColorExtractor: PosterColorExtractor,
    onPosterColorExtracted: (Color) -> Unit,
    sectionVisible: DetailSectionVisibility = DetailSectionVisibility(),
    // false 时只组合共享海报、标题和操作按钮，避免转场首帧创建不可见的整页内容。
    contentReady: Boolean = true,
    // 头部下方内容(cast/视频/简介/季集)的透明度,用于"沉浸背景先现,内容后显"淡入效果
    // 1f=完全显示,0f=隐藏;海报+标题+按钮始终不透明
    contentAlpha: Float = 1f,
    onHeaderAnchorBoundsChanged: (Rect) -> Unit = {},
    backdrop: LayerBackdrop? = null
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // 沉浸背景下的自适应文字色，判据收在 DetailVisuals（豆瓣详情页共用同一套）
    val posterColor = uiState.posterDominantColor
    val onPosterColor = detailOnPosterColor(posterColor)
    val onPosterVariantColor = detailOnPosterVariantColor(posterColor)
    // 沉浸渐变上的栏目标题（演职员 / 预告片与截图 / 简介 …）走 LocalContentColor。
    // 页面级原先 provide 的是随海报色调制的 tabContentColor，现在吸顶栏不再染沉浸色、
    // 页面级已改回主题色；而头部这一段仍压在渐变上，就地 provide 海报自适应色。
    CompositionLocalProvider(LocalContentColor provides onPosterColor) {
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
                // 原先这一行是 height(IntrinsicSize.Max)，让海报高度跟随右列（当时右列还
                // 装着评分卡和按钮组）。但元信息改成 FlowRow 胶囊后，右列高度开始依赖宽度，
                // 而 Row 做内在测量时喂给右列的宽度并不是它最终拿到的宽度（海报侧
                // fillMaxHeight + aspectRatio 报不出内在宽度，权重分配把整行宽度都算给了右列），
                // 于是估出的行高比真实需要的少一行胶囊；Column 再按这个偏小的高度自上而下派，
                // 排在最后的按钮组分到的高度不够，图标 + 文字被压成一条。
                // 现在海报固定宽高、整行 wrap content：右列一开始拿到的就是真实宽度，
                // 评分卡与按钮组各自整宽独占一行，全程不再有任何内在测量。
                .padding(bottom = 12.dp)
                .onGloballyPositioned { onHeaderAnchorBoundsChanged(it.boundsInRoot()) },
            verticalAlignment = Alignment.Top
        ) {
            // 海报：固定 118dp 宽、2:3 比例（177dp 高）
            Box(
                modifier = Modifier
                    .width(HEADER_POSTER_WIDTH)
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
                                                    scope.launch {
                                                        // toBitmap 需整图解码，挪到 Default 线程避免阻塞主线程
                                                        val bitmap = withContext(Dispatchers.Default) {
                                                            result.drawable.toBitmap()
                                                        }
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
                                        // 仅双指才缩放：detectTransformGestures 对单指拖动超 touch slop
                                        // 也会消费 change，会吞掉父级 LazyColumn 从海报起手的滚动。
                                        // 自写检测保证单指阶段不消费任何事件，父级滚动不受影响。
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            do {
                                                val event = awaitPointerEvent()
                                                val pressedCount = event.changes.count { it.pressed }
                                                if (pressedCount >= 2) {
                                                    val zoom = event.calculateZoom()
                                                    if (zoom != 1f) {
                                                        posterScale = (posterScale * zoom).coerceIn(1f, 4f)
                                                        event.changes.forEach { change ->
                                                            if (change.positionChanged()) change.consume()
                                                        }
                                                    }
                                                }
                                            } while (event.changes.any { it.pressed })
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

            Spacer(modifier = Modifier.width(14.dp))

            // 标题/原名/元信息胶囊 + 操作按钮组。
            //
            // 原先按钮组排在评分卡下方整宽一行，而胶囊底边到海报底边之间约 90dp 是死白。
            // 现在那块让给按钮组：文字块顶对齐，按钮组底边与海报底边平齐，头部整体矮一截，
            // 演职员与预告片能上移一屏。撑高与对齐交给 [HeaderRightColumn]，那里有为什么
            // 不能用 Spacer(weight) / Arrangement.SpaceBetween 的实测记录。
            HeaderRightColumn(
                minHeight = HEADER_POSTER_HEIGHT,
                modifier = Modifier.weight(1f),
                text = {
                    // 标题：放开两行。原先塞在 Box(height(28.dp)) 里被迫 maxLines = 1，
                    // 《银翼杀手 2049 加长版》这类长片名直接被切掉后半段
                    Text(
                        text = uiState.displayTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = onPosterColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 26.sp
                    )

                    // 原名：有值才占位。原先固定 18dp 槽，纯中文片源（无原名）也留一条死白
                    if (uiState.originalTitle.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.detail_original_title, uiState.originalTitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = onPosterVariantColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 类型/国家/日期/时长：原先是四个 18dp 固定槽竖排，同字号同颜色分不出主次，
                    // 空字段照样占位。收成一行胶囊，FlowRow 装不下自然换行
                    val metaChips = buildDetailMetaChips(uiState)
                    if (metaChips.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        DetailMetaChips(chips = metaChips, contentColor = onPosterColor)
                    }
                },
                actions = {
                    // 操作按钮组：想看 / 已看 / 评分。右列三等分，图标 + 文字两行。
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
                            verticalPadding = 7.dp
                        )
                    }
                }
            )
        }

        // 四平台评分：整宽独占一行，紧贴海报块下方。原先挤在海报右侧的半屏列里做 2×2 网格，
        // 每个平台只有约 110dp 宽，分数被压到 15sp 还得靠一层文字阴影凑对比度。
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
        Spacer(modifier = Modifier.height(14.dp))

        // 第二行：演职员（海报下方独立一行，左对齐，始终预留空间避免布局跳动）
        // 头部下方内容(cast/视频/简介/系列/季集)统一淡入,营造"沉浸背景先现,内容后显"效果
        if (contentReady) {
        // 演职员/预告片/简介三处骨架共享一份 shimmer 动画，避免各跑一条无限动画
        val headerShimmer = rememberShimmer()
        Column(modifier = Modifier.alpha(contentAlpha)) {
        // 纯豆瓣条目(tmdbId=0)没有 TMDB 演职员数据(cast/crew 只来自 TMDB),直接隐藏整栏,
        // 否则骨架卡与「全部」按钮永远等不到内容,永久空挂
        if (sectionVisible.cast && tmdbId > 0) {
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
                // 加载中/失败占位：仅栏目标题直接显示，卡片区保留骨架或错误态重试入口；
                // 「全部」此时隐藏，避免数据未就绪时点开空 sheet（仅 hasCredits 的 CrewSection 提供该入口）
                DetailSectionHeader(title = stringResource(R.string.detail_cast_crew))
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
                    CastRowSkeleton(shimmer = headerShimmer)
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
        // videosError 时也要组合：以前失败整块静默不组合，用户分不清「这部片没有预告片」和「没加载上」
        if (sectionVisible.videosImages && (uiState.videos.isNotEmpty() || uiState.backdrops.isNotEmpty() || uiState.isLoadingVideosImages || uiState.videosError)) {
            if (uiState.videos.isNotEmpty() || uiState.backdrops.isNotEmpty()) {
                VideosAndImagesSection(
                    videos = uiState.videos,
                    backdrops = uiState.backdrops,
                    onVideoClick = onVideoClick,
                    onBackdropClick = onBackdropClick,
                    onShowAll = onShowAllVideos,
                    sharedKeyPrefix = "backdrop-zoom-$tmdbId"
                )
            } else if (uiState.videosError) {
                // 加载失败：与评分区一致的 Inline 错误态，带重试入口
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    DetailSectionHeader(title = stringResource(R.string.detail_videos_section))
                    AppErrorState(
                        message = stringResource(R.string.detail_load_error),
                        onRetry = onRetryVideos,
                        variant = AppErrorVariant.Inline,
                        inlineLabel = stringResource(R.string.detail_load_error),
                        showDetail = false
                    )
                }
            } else {
                // 骨架屏占位，防止加载后内容跳变；栏目标题为静态文字直接显示，「全部」随数据到达后出现
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    DetailSectionHeader(title = stringResource(R.string.detail_videos_section))
                    VideosRowSkeleton(shimmer = headerShimmer)
                }
            }
        }

        // 简介标签 + 折叠/展开正文
        if (sectionVisible.overview) {
            if (uiState.overview.isNotEmpty()) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    DetailSectionHeader(title = stringResource(R.string.detail_overview_label))
                    ExpandableText(text = uiState.overview)
                }
            } else if (uiState.isLoading) {
                // 简介骨架占位，防止加载后推下下方内容；「简介」标签为静态文字直接显示，仅正文保留骨架
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    DetailSectionHeader(title = stringResource(R.string.detail_overview_label))
                    TextBlockSkeleton(shimmer = headerShimmer)
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
                DetailSectionHeader(title = stringResource(R.string.detail_seasons))
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
        } // end if (contentReady)
    }
    } // end CompositionLocalProvider(LocalContentColor)
}

// ==================== 头部右列 ====================

/**
 * 头部海报右侧那一列：[text] 顶对齐，[actions] 底边对齐到 [minHeight]（传海报高度）。
 *
 * 两者加起来超过 [minHeight] 时按内容实高排版，[actions] 顺势下移 —— 不重叠也不裁切。
 *
 * 为什么不用 `Column` + `Spacer(weight)` 或 `Arrangement.SpaceBetween`：这一列的高度上限
 * 取决于宿主。详情页把头部放在 LazyColumn 的 item 里，maxHeight 是无穷：
 * - `SpaceBetween` 只按内容高度分配剩余空间，`heightIn(min=)` 撑出来的那截它看不见，
 *   按钮组照旧紧跟在胶囊下方（实测顶边 127dp，而海报底边在 209dp）。
 * - `Spacer(Modifier.weight(1f))` 反过来太贪：主轴有界时它吃满 maxHeight，
 *   把按钮组顶到屏幕底部（实测底边 458dp）。
 * 一个只认自己 [minHeight] 的 Layout 与宿主约束无关，两种宿主下结果一致。
 */
@Composable
private fun HeaderRightColumn(
    minHeight: Dp,
    modifier: Modifier = Modifier,
    text: @Composable ColumnScope.() -> Unit,
    actions: @Composable () -> Unit
) {
    Layout(
        contents = listOf({ Column(content = text) }, actions),
        modifier = modifier
    ) { (textMeasurables, actionMeasurables), constraints ->
        // 宽度沿用父级给的精确值（Row 的 weight 已定死），高度放开由内容自报
        val childConstraints = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val textPlaceable = textMeasurables.first().measure(childConstraints)
        val actionsPlaceable = actionMeasurables.first().measure(childConstraints)
        val height = constraints.constrainHeight(
            maxOf(minHeight.roundToPx(), textPlaceable.height + actionsPlaceable.height)
        )
        layout(constraints.maxWidth, height) {
            textPlaceable.place(0, 0)
            actionsPlaceable.place(0, height - actionsPlaceable.height)
        }
    }
}

// ==================== 头部元信息胶囊 ====================

/**
 * 把类型 / 国家 / 上映日期 / 时长拼成胶囊列表。
 *
 * 类型与国家在 ViewModel 里已经是 `" / "` 拼好的字符串（少数富化路径可能用逗号或顿号），
 * 这里按几种分隔符统一拆开各成一枚胶囊——拼成一长串再 ellipsis 的话，三个类型只能看到
 * 第一个半。日期和时长各一枚，与原先「日期 (时长)」合并成一行的写法相比更好扫读。
 */
@Composable
private fun buildDetailMetaChips(uiState: DetailUiState): List<DetailMetaChip> {
    val genresDesc = stringResource(R.string.detail_meta_genres_desc)
    val countryDesc = stringResource(R.string.detail_meta_country_desc)
    val dateDesc = stringResource(R.string.detail_meta_release_date_desc)
    val runtimeDesc = stringResource(R.string.detail_meta_runtime_desc)
    val chips = mutableListOf<DetailMetaChip>()
    splitMetaValues(uiState.genres).forEach { chips += DetailMetaChip(it, genresDesc.format(it)) }
    splitMetaValues(uiState.country).forEach { chips += DetailMetaChip(it, countryDesc.format(it)) }

    val dateText = when {
        uiState.releaseDate.length >= 10 ->
            "${uiState.releaseDate.substring(0, 4)}-${uiState.releaseDate.substring(5, 7)}-${uiState.releaseDate.substring(8, 10)}"
        uiState.releaseDate.isNotEmpty() -> uiState.releaseDate
        uiState.year != null -> stringResource(R.string.detail_year_suffix, uiState.year)
        else -> ""
    }
    if (dateText.isNotEmpty()) chips += DetailMetaChip(dateText, dateDesc.format(dateText))

    val runtime = uiState.runtime
    if (runtime != null && runtime > 0) {
        val hours = runtime / 60
        val minutes = runtime % 60
        val runtimeText = if (hours > 0) {
            stringResource(R.string.detail_runtime_hours, hours, minutes)
        } else {
            stringResource(R.string.detail_runtime_minutes, minutes)
        }
        chips += DetailMetaChip(runtimeText, runtimeDesc.format(runtimeText))
    }
    return chips
}

/** 按 `/`、`,`、`、` 拆分富化字段，去空去重，最多留 4 项避免胶囊行挤掉评分卡。 */
private fun splitMetaValues(raw: String): List<String> =
    if (raw.isBlank()) emptyList() else raw.split('/', ',', '，', '、')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(4)

// ==================== 折叠展开文本 ====================

/**
 * 折叠/展开文本：折叠时正文截到最后一行放得下的位置，行末紧接一个主题色的「… 展开」；
 * 展开后正文末尾同样内联一个主题色的「收起」。省略号与动作词同色 —— 省略号就是
 * 「后面还有」的提示，属于动作的一部分。
 *
 * 动作嵌在正文行里而不另起一行：原先「展开」独占一行右对齐，一段 3 行简介要占掉 4 行高，
 * 评论卡里一条两行短评更明显。点击整段正文即切换（动作本身就在这段文字里，点它就是点正文），
 * 高度变化带动画。
 *
 * [bottomAction] 落在正文下方一行的左端，给调用方放自己的动作（评论卡片的「翻译」
 * 「原文/译文」）。为 null 时不产生这一行。
 */
@Composable
internal fun ExpandableText(
    text: String,
    maxLines: Int = 3,
    bottomAction: (@Composable () -> Unit)? = null
) {
    val effectiveMaxLines = maxLines.coerceAtLeast(1)
    val expandLabel = stringResource(R.string.detail_text_expand)
    val collapseLabel = stringResource(R.string.detail_text_collapse)
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val actionSpan = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold
    )
    val expandSuffix = "… $expandLabel"
    val collapseSuffix = "  $collapseLabel"

    var expanded by rememberSaveable(text, effectiveMaxLines) { mutableStateOf(false) }
    // 折叠态的切字位置：null = 还没量过 / 正文本来就放得下，此时不显示动作也不响应点击。
    // 由 onTextLayout 回填一次；回填后正文已经短到不再溢出，靠这个非空值记住「曾经溢出」。
    var collapsedCut by remember(text, effectiveMaxLines) { mutableStateOf<Int?>(null) }
    val canToggle = collapsedCut != null

    // 「… 展开」占多宽，用来在最后一行右端反推该从哪个字切开。
    // 按 SemiBold 量（动作词就是 SemiBold）并多留一个空格的余量，免得切点偏大导致
    // 内置 Ellipsis 反过来把「展开」自己吃掉半截。
    val textMeasurer = rememberTextMeasurer()
    val suffixWidth = remember(expandSuffix, bodyStyle, textMeasurer) {
        textMeasurer.measure(
            text = AnnotatedString("$expandSuffix "),
            style = bodyStyle.copy(fontWeight = FontWeight.SemiBold)
        ).size.width.toFloat()
    }

    val displayText = remember(text, expanded, collapsedCut, expandSuffix, collapseSuffix, actionSpan) {
        buildAnnotatedString {
            val cut = collapsedCut
            if (expanded || cut == null) {
                append(text)
                if (expanded && cut != null) {
                    withStyle(actionSpan) { append(collapseSuffix) }
                }
            } else {
                append(text.take(cut).trimEnd())
                withStyle(actionSpan) { append(expandSuffix) }
            }
        }
    }
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
            text = displayText,
            style = bodyStyle,
            color = bodyColor,
            maxLines = if (expanded) Int.MAX_VALUE else effectiveMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layoutResult ->
                // 只在折叠、还没量过、且确实溢出时算一次切点。
                // x 取「整宽 - 后缀宽」而不是「本行右端 - 后缀宽」：溢出也可能只是因为后面还有
                // 硬换行，此时最后一行本身并没排满，按本行右端算会把一行放得下的字白白切掉。
                if (!expanded && collapsedCut == null && layoutResult.hasVisualOverflow) {
                    val lastLine = layoutResult.lineCount - 1
                    val x = (layoutResult.size.width - suffixWidth).coerceAtLeast(0f)
                    val y = (layoutResult.getLineTop(lastLine) + layoutResult.getLineBottom(lastLine)) / 2f
                    val lineEnd = layoutResult.getLineEnd(lastLine, visibleEnd = true)
                    collapsedCut = layoutResult.getOffsetForPosition(Offset(x, y))
                        .coerceAtMost(lineEnd)
                        .coerceAtLeast(0)
                }
            },
            modifier = toggleModifier.fillMaxWidth()
        )
        if (bottomAction != null) {
            Box(modifier = Modifier.padding(top = 4.dp)) { bottomAction() }
        }
    }
}

// ==================== 状态绑带 ====================

/** 状态标签：右下角圆角矩形 */
@Composable
private fun StatusRibbon(status: String, modifier: Modifier = Modifier) {
    val backgroundColor = detailStatusColor(status)
    val displayText = getStatusDisplayText(status)
    Surface(
        modifier = modifier.padding(4.dp),
        shape = RoundedCornerShape(4.dp),
        color = backgroundColor.copy(alpha = 0.9f)
    ) {
        Text(
            text = displayText,
            // 按状态色亮度取黑白。原先整排写死白字，7 个状态色里 5 个不到 AA ——
            // 制作中的橙 #FF9800 上白字只有 2.16:1，黑字有 9.74:1。
            color = onColorFor(backgroundColor),
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
