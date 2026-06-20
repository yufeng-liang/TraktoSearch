package com.tracktosearch.ui.screen.detail

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.os.Environment
import com.tracktosearch.ui.util.showToast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tracktosearch.R
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.data.remote.tmdb.dto.TmdbCast
import com.tracktosearch.data.remote.tmdb.dto.TmdbCrew
import com.tracktosearch.data.remote.trakt.dto.TraktComment
import com.tracktosearch.data.remote.trakt.dto.TraktEpisode
import com.tracktosearch.data.remote.trakt.dto.TraktSeason
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.MultiRatings
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.ui.component.ResourceItemCard
import com.tracktosearch.ui.component.ScrollToTopButton
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun DetailScreen(
    traktId: Int,
    tmdbId: Int,
    title: String,
    mediaType: MediaType,
    year: Int? = null,
    imdbId: String = "",
    traktRating: Double = 0.0,
    onBack: (changed: Boolean) -> Unit = {},
    onPersonClick: (personId: Int, personName: String) -> Unit = { _, _ -> },
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit = { _, _, _, _, _ -> },
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit = { _, _, _, _, _ -> },
    onNavigateToLogin: () -> Unit = {},
    viewModel: DetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 拦截系统返回手势/返回键，统一走 onBack 回调以传递 watchlistChanged 状态
    BackHandler(enabled = true) {
        onBack(uiState.watchlistChanged)
    }

    LaunchedEffect(traktId, tmdbId, title) {
        viewModel.loadDetail(traktId, tmdbId, title, mediaType, year, imdbId, traktRating)
    }

    val listState = rememberLazyListState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showPosterFullscreen by remember { mutableStateOf(false) }

    // 评论翻译映射
    val translatedMap = remember(uiState.translatedComments) {
        uiState.translatedComments.associateBy { it.id }
    }

    // 资源分页加载
    val initialCount = 30
    var displayedCount by remember { mutableIntStateOf(initialCount.coerceAtMost(uiState.resources.size)) }
    LaunchedEffect(uiState.resources.size) {
        if (uiState.resources.isNotEmpty() && displayedCount < uiState.resources.size) {
            // 数据增长时，至少显示 initialCount 条（避免先到源只有少量结果导致卡住）
            displayedCount = maxOf(displayedCount, initialCount).coerceAtMost(uiState.resources.size)
        }
    }

    // 滚动到底部自动加载更多（资源/评论共用）
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(shouldLoadMore, selectedTab) {
        if (shouldLoadMore) {
            if (selectedTab == 0 && displayedCount < uiState.resources.size) {
                displayedCount = (displayedCount + 30).coerceAtMost(uiState.resources.size)
            } else if (selectedTab == 1 && uiState.hasMoreComments && !uiState.isLoadingMoreComments) {
                viewModel.loadMoreComments()
            }
        }
    }

    // Haze 毛玻璃状态
    val detailHazeState = remember { HazeState() }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        ) {
            // 单 LazyColumn：头部(item) + TabRow(stickyHeader) + 内容(根据Tab切换)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .hazeSource(state = detailHazeState),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // 头部信息（随内容滚动）
                item(key = "detail_header") {
                    DetailHeaderContent(
                        uiState = uiState,
                        tmdbId = tmdbId,
                        isMarkedWatched = uiState.isMarkedWatched,
                        isMarkingWatched = uiState.isMarkingWatched,
                        onToggleWatched = { viewModel.toggleWatched() },
                        isMarkedWatchlist = uiState.isMarkedWatchlist,
                        isMarkingWatchlist = uiState.isMarkingWatchlist,
                        onToggleWatchlist = { viewModel.toggleWatchlist() },
                        onRatingClick = { viewModel.setRating(it) },
                        onPosterClick = { showPosterFullscreen = true },
                        onPersonClick = onPersonClick
                    )
                }

                // Tab 行（吸顶，共用同一个）
                stickyHeader(key = "tab_row") {
                    PrimaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("${stringResource(R.string.detail_tab_resources)}(${uiState.resources.size})", modifier = Modifier.animateContentSize()) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("${stringResource(R.string.detail_tab_comments)}(${uiState.comments.size})", modifier = Modifier.animateContentSize()) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("${stringResource(R.string.detail_tab_recommendations)}(${uiState.recommendations.size})", modifier = Modifier.animateContentSize()) }
                        )
                    }
                }

                // ===== 资源 Tab 内容 =====
                if (selectedTab == 0) {
                    // 筛选器
                    item(key = "filter_section") {
                        FilterSection(
                            enabledSources = uiState.enabledSources,
                            enabledDiskTypes = uiState.enabledDiskTypes,
                            onToggleSource = { viewModel.toggleSource(it) },
                            onToggleDiskType = { viewModel.toggleDiskType(it) }
                        )
                    }

                    // 季/集信息（仅电视剧）
                    if (uiState.seasons.isNotEmpty()) {
                        item(key = "seasons_section") {
                            SeasonsSection(
                                seasons = uiState.seasons,
                                episodes = uiState.episodes,
                                expandedSeasons = uiState.expandedSeasons,
                                watchedEpisodeNumbers = uiState.watchedEpisodeNumbers,
                                togglingEpisode = uiState.togglingEpisode,
                                onToggleSeason = { viewModel.toggleSeason(it) },
                                onToggleEpisodeWatched = { season, episode, traktId ->
                                    viewModel.toggleEpisodeWatched(season, episode, traktId)
                                }
                            )
                            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                        }
                    }

                    // 资源列表
                    val items = uiState.resources
                    when {
                        uiState.isSearching -> {
                            item(key = "searching") {
                                SearchingState(
                                    completedSources = uiState.completedSources,
                                    totalSources = uiState.totalSources
                                )
                            }
                        }
                        items.isEmpty() && uiState.searchAttempted -> {
                            item(key = "empty") { EmptyState(onRetry = { viewModel.searchResources() }) }
                        }
                        else -> {
                            item(key = "resource_count") {
                                Text(
                                    text = stringResource(R.string.detail_found_resources, items.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                                )
                            }
                            itemsIndexed(items.take(displayedCount), key = { _, item -> item.url }) { index, item ->
                                ResourceItemCard(
                                    item = item,
                                    isViewed = item.url in uiState.viewedUrls,
                                    onClick = {
                                        viewModel.markResourceViewed(item.url)
                                        openResourceLink(context, item)
                                    },
                                    index = index
                                )
                            }
                            if (displayedCount < items.size) {
                                item(key = "load_more") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.detail_load_more_text, displayedCount, items.size),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else if (items.size > initialCount) {
                                item(key = "all_loaded") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.detail_all_loaded, items.size),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ===== 评论 Tab 内容 =====
                if (selectedTab == 1) {
                    val commentsToShow = uiState.comments
                    item(key = "comments_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.detail_comments),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (commentsToShow.isNotEmpty() && uiState.translatedComments.size < commentsToShow.size) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .clickable(enabled = !uiState.isTranslating) { viewModel.translateComments() }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        if (uiState.isTranslating) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(13.dp),
                                                strokeWidth = 1.5.dp
                                            )
                                        }
                                        Text(
                                            text = if (uiState.isTranslating) stringResource(R.string.detail_translating)
                                            else stringResource(R.string.detail_translate_all),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (uiState.commentsError && commentsToShow.isEmpty()) {
                        item(key = "comments_error") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.detail_load_error),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    items(
                        items = commentsToShow,
                        key = { it.id }
                    ) { comment ->
                        CommentItem(
                            comment = comment,
                            translatedText = translatedMap[comment.id]?.comment,
                            onTranslate = { commentId ->
                                if (commentId == -1) viewModel.translateComments()
                                else viewModel.translateSingleComment(commentId)
                            },
                            isTranslating = uiState.isTranslating,
                            isThisTranslating = (uiState.translatingCommentId == comment.id)
                        )
                    }

                    if (uiState.hasMoreComments || uiState.isLoadingMoreComments) {
                        item(key = "load_more_comments") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (uiState.isLoadingMoreComments) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                                } else {
                                    Text(
                                        text = stringResource(R.string.detail_load_more_comments),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else if (commentsToShow.size > 5) {
                        item(key = "all_comments_loaded") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.detail_all_comments_loaded, commentsToShow.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // ===== 推荐 Tab 内容 =====
                if (selectedTab == 2) {
                    val recommendations = uiState.recommendations
                    when {
                        uiState.isLoadingRecommendations -> {
                            item(key = "rec_loading") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                        uiState.recommendationsError -> {
                            item(key = "rec_error") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.detail_load_error),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        recommendations.isEmpty() -> {
                            item(key = "rec_empty") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.detail_no_recommendations),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        else -> {
                            item(key = "rec_header") {
                                Text(
                                    text = stringResource(R.string.detail_recommendations_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                                )
                            }
                            recommendations.chunked(3).forEachIndexed { rowIndex, rowItems ->
                                item(key = "rec_row_$rowIndex") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        rowItems.forEach { item ->
                                            MovieCard(
                                                title = item.displayTitle.ifEmpty { item.title },
                                                year = item.year,
                                                genres = item.genres,
                                                posterUrl = item.posterUrl,
                                                tmdbId = item.tmdbId,
                                                onClick = {
                                                    if (mediaType == MediaType.MOVIE) {
                                                        onMovieClick(item.traktId, item.tmdbId, item.title, item.imdbId, item.traktRating)
                                                    } else {
                                                        onShowClick(item.traktId, item.tmdbId, item.title, item.imdbId, item.traktRating)
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                                isInWatchlist = item.isInWatchlist,
                                                isWatched = item.isWatched
                                            )
                                        }
                                        repeat(3 - rowItems.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 返回按钮（半透明背景 + Haze 模糊增强）
            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 4.dp)
                    .align(Alignment.TopStart)
                    .size(40.dp)
                    .clip(CircleShape)
                    .hazeEffect(
                        state = detailHazeState,
                        style = HazeStyle(
                            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            blurRadius = 20.dp,
                            noiseFactor = 0f,
                            tint = null
                        )
                    )
                    // 半透明背景作为主视觉效果（Haze 在部分设备上效果不明显时兜底）
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        shape = CircleShape
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        shape = CircleShape
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onBack(uiState.watchlistChanged) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.detail_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }

            // 分享按钮（半透明背景 + Haze 模糊增强）
            val context = LocalContext.current
            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(end = 12.dp, top = 4.dp)
                    .align(Alignment.TopEnd)
                    .size(40.dp)
                    .clip(CircleShape)
                    .hazeEffect(
                        state = detailHazeState,
                        style = HazeStyle(
                            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            blurRadius = 20.dp,
                            noiseFactor = 0f,
                            tint = null
                        )
                    )
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        shape = CircleShape
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        shape = CircleShape
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            val shareText = buildString {
                                append(uiState.title)
                                if (uiState.year != null) append(" (${uiState.year})")
                                append("\n")
                                if (uiState.overview.isNotBlank()) {
                                    append(uiState.overview)
                                    append("\n")
                                }
                                // 附带前两个资源搜索结果的网盘链接
                                val topResources = uiState.resources.take(2)
                                if (topResources.isNotEmpty()) {
                                    append("\n资源链接：\n")
                                    topResources.forEachIndexed { index, item ->
                                        append("${index + 1}. ${item.name}\n${item.url}\n")
                                    }
                                }
                            }
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, null))
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = stringResource(R.string.detail_share),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }

            ScrollToTopButton(
                listState = listState,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 16.dp),
                hazeState = detailHazeState
            )

            // 海报大图查看
            val posterUrl = uiState.posterUrl
            if (showPosterFullscreen && posterUrl != null) {
                PosterFullscreenOverlay(
                    posterUrl = posterUrl,
                    title = uiState.displayTitle,
                    onDismiss = { showPosterFullscreen = false }
                )
            }

            // 未登录用户引导登录弹窗
            if (uiState.showLoginPrompt) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissLoginPrompt() },
                    title = { Text(stringResource(R.string.detail_login_required_title)) },
                    text = { Text(stringResource(R.string.detail_login_required_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.dismissLoginPrompt()
                            onNavigateToLogin()
                        }) {
                            Text(stringResource(R.string.detail_login_go))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissLoginPrompt() }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }
                )
            }
        }
    }
}

// ==================== 头部内容 ====================

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun DetailHeaderContent(
    uiState: DetailUiState,
    tmdbId: Int,
    isMarkedWatched: Boolean,
    isMarkingWatched: Boolean,
    onToggleWatched: () -> Unit,
    isMarkedWatchlist: Boolean,
    isMarkingWatchlist: Boolean,
    onToggleWatchlist: () -> Unit,
    onRatingClick: (Int) -> Unit,
    onPosterClick: () -> Unit = {},
    onPersonClick: (personId: Int, personName: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 海报（宽度随高度等比例变化，海报比例 2:3）
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f)
                    .then(if (uiState.posterUrl != null) Modifier.clickable { onPosterClick() } else Modifier)
            ) {
                if (uiState.posterUrl != null) {
                    var posterScale by remember { mutableFloatStateOf(1f) }
                    val sharedTransitionScope = LocalSharedTransitionScope.current
                    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
                    val posterModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
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
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 标题/类型/日期/评分/标记已看
            Column(modifier = Modifier.weight(1f)) {
                // 标题
                Text(
                    text = uiState.displayTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (uiState.title != uiState.displayTitle) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.detail_original_title, uiState.title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (uiState.genres.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = uiState.genres,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // 上映日期（详细日期，放在标题下方）
                if (!uiState.releaseDate.isEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    val dateText = if (uiState.releaseDate.length >= 10) {
                        "${uiState.releaseDate.substring(0, 4)}-${uiState.releaseDate.substring(5, 7)}-${uiState.releaseDate.substring(8, 10)}"
                    } else {
                        uiState.releaseDate
                    }
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (uiState.year != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${uiState.year}年",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 时长
                if (uiState.runtime != null && uiState.runtime!! > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    val hours = uiState.runtime!! / 60
                    val minutes = uiState.runtime!! % 60
                    val runtimeText = if (hours > 0) {
                        stringResource(R.string.detail_runtime_hours, hours, minutes)
                    } else {
                        stringResource(R.string.detail_runtime_minutes, minutes)
                    }
                    Text(
                        text = runtimeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 多平台评分（始终预留固定高度，避免加载前后按钮位置跳动）
                Spacer(modifier = Modifier.height(10.dp))
                if (uiState.ratings != null) {
                    RatingsRow(uiState.ratings)
                } else {
                    // 骨架占位：两行评分高度
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(20.dp)
                            ) {}
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(20.dp)
                            ) {}
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(20.dp)
                            ) {}
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(20.dp)
                            ) {}
                        }
                    }
                }
                // 用户评分控件（评分行下方、按钮上方）
                UserRatingBar(
                    userRating = uiState.userRating,
                    isRating = uiState.isRating,
                    isRatingLoading = uiState.isRatingLoading,
                    onRatingClick = onRatingClick
                )
                // 标记已看按钮 + 想看按钮（已看按钮对齐 RT 评分起点）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 左半区：想看按钮
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Surface(
                            onClick = if (isMarkingWatchlist) ({}) else onToggleWatchlist,
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
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                if (isMarkingWatchlist) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isMarkedWatchlist) Icons.Filled.Check else Icons.Filled.BookmarkBorder,
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
                            onClick = if (isMarkingWatched) ({}) else onToggleWatched,
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
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                if (isMarkingWatched) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isMarkedWatched) Icons.Filled.Check else Icons.Filled.Visibility,
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
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 第二行：演职员（海报下方独立一行，左对齐，始终预留空间避免布局跳动）
        val hasCredits = uiState.cast.isNotEmpty() || uiState.crew.isNotEmpty()
        var showFullCast by remember { mutableStateOf(false) }
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

        // 简介标签 + 折叠/展开正文
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
        }
    }
}

// ==================== 评分行 ====================

@Composable
private fun RatingsRow(ratings: MultiRatings) {
    // 第一行：IMDb, MTC
    val row1 = mutableListOf<Triple<String, Color, String>>()
    if (ratings.imdbRating.isNotEmpty()) {
        row1.add(Triple("IMDb", Color(0xFFF5C518), ratings.imdbRating))
    }
    if (ratings.metacritic.isNotEmpty()) {
        row1.add(Triple("MTC", Color(0xFFFF9500), ratings.metacritic))
    }

    // 第二行：TMDB, RT
    val row2 = mutableListOf<Triple<String, Color, String>>()
    if (ratings.tmdbRating > 0) {
        row2.add(Triple("TMDB", Color(0xFFF5C518), String.format("%.1f", ratings.tmdbRating)))
    }
    if (ratings.rottenTomatoes.isNotEmpty()) {
        row2.add(Triple("RT", Color(0xFFFF4444), ratings.rottenTomatoes))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 第一行：始终渲染，无数据时用透明占位保持高度
        Row(
            modifier = Modifier.fillMaxWidth().height(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (row1.isNotEmpty()) {
                for ((label, color, value) in row1) {
                    RatingBadge(label = label, color = color, value = value, modifier = Modifier.weight(1f))
                }
                if (row1.size == 1) Spacer(modifier = Modifier.weight(1f))
            } else {
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        // 第二行：始终渲染，无数据时用透明占位保持高度
        Row(
            modifier = Modifier.fillMaxWidth().height(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (row2.isNotEmpty()) {
                for ((label, color, value) in row2) {
                    RatingBadge(label = label, color = color, value = value, modifier = Modifier.weight(1f))
                }
                if (row2.size == 1) Spacer(modifier = Modifier.weight(1f))
            } else {
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 单个评分项：无背景填充，各平台专属图标 */
@Composable
private fun RatingBadge(label: String, color: Color, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        when (label) {
            "TMDB" -> Icon(
                Icons.Filled.Star, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = color
            )
            "IMDb" -> Surface(
                shape = RoundedCornerShape(2.dp),
                color = color
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 15.sp, fontWeight = FontWeight.Bold
                    ),
                    color = Color.Black,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            "RT" -> Text(
                text = "🍅",
                fontSize = 18.sp
            )
            "MTC" -> Text(
                text = "🎯",
                fontSize = 18.sp
            )
        }

        if (label != "IMDb") {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ==================== 用户评分控件 ====================

@Composable
private fun UserRatingBar(
    userRating: Int?,
    isRating: Boolean,
    isRatingLoading: Boolean,
    onRatingClick: (Int) -> Unit
) {
    val starColor = Color(0xFFFFC107)
    val emptyColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)

    Row(
        modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 标签：我的评分
        Text(
            text = stringResource(R.string.detail_your_rating),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (userRating != null) {
            Text(
                text = "$userRating/10",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = starColor
            )
        }
        // 星标（紧跟在文字右边）
        if (isRatingLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                for (i in 1..10) {
                    val isFilled = userRating != null && i <= userRating
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = !isRating,
                                onClick = { onRatingClick(i) }
                            ),
                        tint = if (isFilled) starColor else emptyColor
                    )
                }
            }
        }
    }
}

// ==================== 演职员 ====================

@Composable
private fun CrewSection(
    cast: List<TmdbCast>,
    crew: List<TmdbCrew>,
    onShowAll: () -> Unit = {},
    onPersonClick: (personId: Int, personName: String) -> Unit = { _, _ -> }
) {
    val directors = crew.filter { it.job == "Director" }
    val writers = crew.filter { it.job == "Writer" || it.job == "Screenplay" }
    val producers = crew.filter { it.job == "Producer" }

    Column {
        // 标题行
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
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onShowAll)
            )
        }

        // 横向滚动：导演→演员→编剧→制片人
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            // 导演
            items(directors, key = { "director_${it.id}" }) { person ->
                CastCard(
                    name = person.name,
                    role = stringResource(R.string.detail_director_tag),
                    profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w185$it" },
                    onClick = { onPersonClick(person.id, person.name) }
                )
            }
            // 演员
            items(cast, key = { "cast_${it.id}_${it.character}" }) { person ->
                CastCard(
                    name = person.name,
                    role = if (person.character.isNotEmpty()) stringResource(R.string.detail_cast_as, person.character) else stringResource(R.string.detail_actor),
                    profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w185$it" },
                    onClick = { onPersonClick(person.id, person.name) }
                )
            }
            // 编剧
            items(writers, key = { "writer_${it.id}" }) { person ->
                CastCard(
                    name = person.name,
                    role = stringResource(R.string.detail_writer_tag),
                    profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w185$it" },
                    onClick = { onPersonClick(person.id, person.name) }
                )
            }
            // 制片人
            items(producers, key = { "producer_${it.id}" }) { person ->
                CastCard(
                    name = person.name,
                    role = stringResource(R.string.detail_producer_tag),
                    profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w185$it" },
                    onClick = { onPersonClick(person.id, person.name) }
                )
            }
        }
    }
}

@Composable
private fun CastCard(name: String, role: String, profileUrl: String?, onClick: () -> Unit = {}) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .width(68.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .width(68.dp)
                .height(95.dp)
        ) {
            if (profileUrl != null) {
                SubcomposeAsyncImage(
                    model = remember(profileUrl) {
                        ImageRequest.Builder(context)
                            .data(profileUrl)
                            .size(136)
                            .build()
                    },
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    loading = {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    error = {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                )
            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = role,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ==================== 全部演职员弹窗 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullCastCrewSheet(
    cast: List<TmdbCast>,
    crew: List<TmdbCrew>,
    onDismiss: () -> Unit,
    onPersonClick: (personId: Int, personName: String) -> Unit = { _, _ -> }
) {
    val directors = crew.filter { it.job == "Director" }
    val writers = crew.filter { it.job == "Writer" || it.job == "Screenplay" }
    val producers = crew.filter { it.job == "Producer" }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.detail_cast_all_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.detail_cast_close))
                }
            }

            // 分组列表
            LazyColumn(
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 导演
                if (directors.isNotEmpty()) {
                    item { SectionHeader("${stringResource(R.string.detail_director_tag)} (${directors.size})") }
                    items(directors, key = { "director_${it.id}" }) { person ->
                        FullCastItem(
                            name = person.name,
                            originalName = person.original_name,
                            role = person.job,
                            profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w185$it" },
                            onClick = { onPersonClick(person.id, person.name) }
                        )
                    }
                }
                // 演员
                if (cast.isNotEmpty()) {
                    item { SectionHeader("${stringResource(R.string.detail_actor)} (${cast.size})") }
                    items(cast, key = { "cast_${it.id}_${it.character}" }) { person ->
                        FullCastItem(
                            name = person.name,
                            originalName = person.original_name,
                            role = if (person.character.isNotEmpty()) stringResource(R.string.detail_cast_as, person.character) else "",
                            profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w185$it" },
                            onClick = { onPersonClick(person.id, person.name) }
                        )
                    }
                }
                // 编剧
                if (writers.isNotEmpty()) {
                    item { SectionHeader("${stringResource(R.string.detail_writer_tag)} (${writers.size})") }
                    items(writers, key = { "writer_${it.id}" }) { person ->
                        FullCastItem(
                            name = person.name,
                            originalName = person.original_name,
                            role = person.job,
                            profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w185$it" },
                            onClick = { onPersonClick(person.id, person.name) }
                        )
                    }
                }
                // 制片人
                if (producers.isNotEmpty()) {
                    item { SectionHeader("${stringResource(R.string.detail_producer_tag)} (${producers.size})") }
                    items(producers, key = { "producer_${it.id}" }) { person ->
                        FullCastItem(
                            name = person.name,
                            originalName = person.original_name,
                            role = person.job,
                            profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w185$it" },
                            onClick = { onPersonClick(person.id, person.name) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun FullCastItem(name: String, originalName: String, role: String, profileUrl: String?, onClick: () -> Unit = {}) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(width = 72.dp, height = 100.dp)
        ) {
            if (profileUrl != null) {
                SubcomposeAsyncImage(
                    model = remember(profileUrl) {
                        ImageRequest.Builder(context)
                            .data(profileUrl)
                            .size(144)
                            .build()
                    },
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    loading = {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    },
                    error = {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                )
            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (originalName.isNotEmpty() && originalName != name) {
                Text(
                    text = originalName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (role.isNotEmpty()) {
                Text(
                    text = role,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ==================== 折叠展开文本 ====================

@Composable
private fun ExpandableText(text: String, maxLines: Int = 3) {
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

// ==================== 筛选器 ====================

@Composable
private fun FilterSection(
    enabledSources: Set<String>,
    enabledDiskTypes: Set<DiskType>,
    onToggleSource: (String) -> Unit,
    onToggleDiskType: (DiskType) -> Unit
) {
    // 固定左侧标签宽度，保证两个行的 Chip 起点对齐
    val labelWidth = 64.dp

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        // 搜索源（横向滚动）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.detail_filter_sources),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(labelWidth)
            )
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = enabledSources.toList(),
                    key = { it }
                ) { source ->
                    val label = when (source) {
                        "pansou" -> "PanSou"
                        "panhub" -> "PanHub"
                        else -> "Zreso"
                    }
                    FilterChip(
                        selected = source in enabledSources,
                        onClick = { onToggleSource(source) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 网盘类型（横向滚动）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.detail_filter_disk_types),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(labelWidth)
            )
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = ResourceRepository.ALL_DISK_TYPES.toList(),
                    key = { it.name }
                ) { type ->
                    val label = when (type) {
                        DiskType.QUARK -> stringResource(R.string.detail_disk_type_quark)
                        DiskType.BAIDU -> stringResource(R.string.detail_disk_type_baidu)
                        DiskType.ALI -> stringResource(R.string.detail_disk_type_ali)
                        DiskType.XUNLEI -> stringResource(R.string.detail_disk_type_xunlei)
                        DiskType.UC -> stringResource(R.string.detail_disk_type_uc)
                        DiskType.ONEONEFIVE -> stringResource(R.string.detail_disk_type_115)
                        DiskType.MAGNET -> stringResource(R.string.detail_disk_type_magnet)
                        DiskType.OTHER -> stringResource(R.string.detail_disk_type_other)
                    }
                    FilterChip(
                        selected = type in enabledDiskTypes,
                        onClick = { onToggleDiskType(type) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }
    }
}

// ==================== 季/集信息 ====================

@Composable
private fun SeasonsSection(
    seasons: List<TraktSeason>,
    episodes: Map<Int, List<TraktEpisode>>,
    expandedSeasons: Set<Int>,
    watchedEpisodeNumbers: Map<Int, Set<Int>>,
    togglingEpisode: Pair<Int, Int>?,
    onToggleSeason: (Int) -> Unit,
    onToggleEpisodeWatched: (seasonNumber: Int, episodeNumber: Int, episodeTraktId: Int) -> Unit
) {
    // 过滤掉第0季（特别篇），单独展示为"特别篇"
    val regularSeasons = seasons.filter { it.number > 0 }
    val specialSeasons = seasons.filter { it.number == 0 }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.detail_seasons),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // 正常季（第1季、第2季...）
        regularSeasons.forEach { season ->
            SeasonRow(
                season = season,
                episodes = episodes,
                expandedSeasons = expandedSeasons,
                watchedEpisodeNumbers = watchedEpisodeNumbers,
                togglingEpisode = togglingEpisode,
                onToggleSeason = onToggleSeason,
                onToggleEpisodeWatched = onToggleEpisodeWatched
            )
        }

        // 特别篇（第0季）— 合并为一个条目展示
        specialSeasons.forEach { season ->
            val totalEpisodes = season.episode_count
            if (totalEpisodes > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSeason(season.number) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.detail_specials),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.detail_episode_count, totalEpisodes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = if (season.number in expandedSeasons) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(
                    visible = season.number in expandedSeasons,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    val episodeList = episodes[season.number] ?: emptyList()
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        if (episodeList.isEmpty()) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).padding(2.dp),
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

@Composable
private fun SeasonRow(
    season: TraktSeason,
    episodes: Map<Int, List<TraktEpisode>>,
    expandedSeasons: Set<Int>,
    watchedEpisodeNumbers: Map<Int, Set<Int>>,
    togglingEpisode: Pair<Int, Int>?,
    onToggleSeason: (Int) -> Unit,
    onToggleEpisodeWatched: (seasonNumber: Int, episodeNumber: Int, episodeTraktId: Int) -> Unit
) {
    val isExpanded = season.number in expandedSeasons
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleSeason(season.number) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.detail_season, season.number),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.detail_episode_count, season.episode_count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val episodeList = episodes[season.number] ?: emptyList()
            Column(modifier = Modifier.padding(start = 16.dp)) {
                if (episodeList.isEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).padding(2.dp),
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

/** 单集行：集号+标题 + 已看切换图标 */
@Composable
private fun EpisodeRow(
    episode: TraktEpisode,
    seasonNumber: Int,
    isWatched: Boolean,
    isToggling: Boolean,
    onToggleWatched: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.detail_episode, episode.number, episode.title),
            style = MaterialTheme.typography.bodySmall,
            color = if (isWatched) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleWatched
                )
        )
        if (isToggling) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp).padding(2.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = if (isWatched) "已看" else "未看",
                modifier = Modifier
                    .size(18.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleWatched
                    ),
                tint = if (isWatched) Color(0xFF4CAF50)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
        }
    }
}

// ==================== 搜索状态 ====================

@Composable
private fun SearchingState(completedSources: Int, totalSources: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.detail_searching),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.detail_searching_info, completedSources, totalSources),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.detail_no_resources),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.detail_retry))
            }
        }
    }
}

// ==================== 单条评论 ====================

@Composable
private fun CommentItem(
    comment: TraktComment,
    translatedText: String?,
    onTranslate: (Int) -> Unit,
    isTranslating: Boolean,
    isThisTranslating: Boolean
) {
    var showOriginal by remember(comment.id) { mutableStateOf(false) }
    var spoilerRevealed by remember { mutableStateOf(false) }

    val displayText = if (showOriginal) comment.comment else (translatedText ?: comment.comment)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 用户名 + 评分
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = comment.user.username,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "(来源:${comment.source})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (comment.user_rating != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color(0xFFFFC107)
                    )
                    Text(
                        text = String.format("%.0f", comment.user_rating),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // 单条翻译按钮（剧透未揭示时不显示）
                if (!comment.spoiler || spoilerRevealed) {
                    if (translatedText == null && !isThisTranslating) {
                        Text(
                            text = stringResource(R.string.detail_translate),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onTranslate(comment.id) }
                        )
                    } else if (isThisTranslating) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.detail_translating),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 剧透遮罩
            if (comment.spoiler && !spoilerRevealed) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { spoilerRevealed = true },
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = stringResource(R.string.detail_spoiler),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // 评论正文（折叠展开）
                ExpandableText(text = displayText)

                // 原文/译文切换
                if (translatedText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = if (showOriginal) stringResource(R.string.detail_translated) else stringResource(R.string.detail_original),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showOriginal = !showOriginal }
                        )
                    }
                }
            }
        }
    }
}

// ==================== 工具函数 ====================

internal fun openResourceLink(context: android.content.Context, item: ResourceItem) {
    val appScheme = when (item.diskType) {
        DiskType.QUARK -> "quark://"
        DiskType.BAIDU -> "baidunetdisk://"
        DiskType.ALI -> "aliyundrive://"
        DiskType.XUNLEI, DiskType.UC, DiskType.ONEONEFIVE, DiskType.MAGNET, DiskType.OTHER -> null
    }

    // 优先尝试打开网盘 App
    if (appScheme != null) {
        try {
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(appScheme))
            appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (appIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(appIntent)
                return
            }
        } catch (_: Exception) {}
    }

    // 磁力链接：尝试用系统默认 App 打开
    if (item.diskType == DiskType.MAGNET || item.url.startsWith("magnet:", ignoreCase = true)) {
        try {
            val magnetIntent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url))
            magnetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (magnetIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(magnetIntent)
                return
            }
        } catch (_: Exception) {}
        // 无 App 处理磁力链接：复制到剪贴板
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("magnet", item.url)
        clipboard.setPrimaryClip(clip)
        context.showToast("磁力链接已复制到剪贴板")
        return
    }

    // Fallback: 浏览器打开
    try {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url))
        context.startActivity(browserIntent)
    } catch (_: Exception) {
        context.showToast("无法打开此链接")
    }
}

// ==================== 海报大图查看 ====================

@Composable
private fun PosterFullscreenOverlay(
    posterUrl: String,
    title: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSaved by remember { mutableStateOf<Boolean?>(null) } // null=未检查, true=已保存, false=未保存

    // 进入时检查是否已保存
    LaunchedEffect(posterUrl, title) {
        val safeName = title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")
        val filename = "TrackToSearch_${safeName}.jpg"
        val relativePath = Environment.DIRECTORY_PICTURES + "/TrackToSearch"
        isSaved = queryExistingFile(context, filename, relativePath) != null
    }

    BackHandler(onBack = onDismiss)

    // 海报大图 overlay 的 Haze 状态
    val posterHazeState = remember { HazeState() }
    var posterScale by remember { mutableFloatStateOf(1f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .hazeSource(state = posterHazeState)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        // 海报图片（放大显示，拦截点击事件不触发外层dismiss）
        AsyncImage(
            model = remember(posterUrl) {
                ImageRequest.Builder(context)
                    .data(posterUrl)
                    .crossfade(true)
                    .size(1080) // 加载高清大图
                    .build()
            },
            contentDescription = title,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .aspectRatio(2f / 3f)
                .graphicsLayer(scaleX = posterScale, scaleY = posterScale)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        posterScale = (posterScale * zoom).coerceIn(1f, 4f)
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // 拦截点击，不触发外层 dismiss
                )
        )

        // 顶部操作栏（在图片之上，也需要拦截点击）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 关闭按钮（毛玻璃效果）
            IconButton(onClick = onDismiss) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .hazeEffect(
                            state = posterHazeState,
                            style = HazeMaterials.thin()
                        ) {
                            blurRadius = 18.dp
                            noiseFactor = 0f
                        }
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.detail_back),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 保存按钮（已保存时显示勾选图标）
            IconButton(onClick = {
                if (isSaved == true) {
                    context.showToast("已保存到相册")
                } else {
                    savePosterToGallery(context, scope, posterUrl, title) {
                        isSaved = true
                    }
                }
            }) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .hazeEffect(
                            state = posterHazeState,
                            style = HazeMaterials.thin()
                        ) {
                            blurRadius = 18.dp
                            noiseFactor = 0f
                        }
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSaved == true) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "已保存",
                            tint = Color(0xFF4CAF50), // 绿色
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "保存",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun savePosterToGallery(context: android.content.Context, scope: CoroutineScope, posterUrl: String, title: String, onSaved: () -> Unit = {}) {
    val imageLoader = context.imageLoader
    scope.launch(Dispatchers.IO) {
        try {
            val result = imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(posterUrl)
                    .allowHardware(false)
                    .build()
            )
            val bitmap = (result as? SuccessResult)?.drawable?.toBitmap()
            if (bitmap != null) {
                saveBitmapToGallery(context, scope, bitmap, title)
                onSaved()
            }
        } catch (_: Exception) {
            withContext(Dispatchers.Main) {
                context.showToast("保存失败")
            }
        }
    }
}

private fun saveBitmapToGallery(context: android.content.Context, scope: CoroutineScope, bitmap: Bitmap, title: String) {
    val safeName = title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")
    val filename = "TrackToSearch_${safeName}.jpg"
    val relativePath = Environment.DIRECTORY_PICTURES + "/TrackToSearch"

    // 检查是否已存在同名文件（防重复保存）
    val existingUri = queryExistingFile(context, filename, relativePath)
    if (existingUri != null) {
        scope.launch(Dispatchers.Main) {
            context.showToast("已存在: $filename")
        }
        return
    }

    val contentValues = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, relativePath)
    }
    val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    if (uri != null) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }
        scope.launch(Dispatchers.Main) {
            context.showToast("已保存: $relativePath/$filename")
        }
    }
}

/** 查询 MediaStore 中是否已存在同名文件 */
private fun queryExistingFile(context: android.content.Context, filename: String, relativePath: String): android.net.Uri? {
    val selection = "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.Images.Media.RELATIVE_PATH} = ?"
    val selectionArgs = arrayOf(filename, relativePath)
    val cursor = context.contentResolver.query(
        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(android.provider.MediaStore.Images.Media._ID),
        selection,
        selectionArgs,
        null
    )
    cursor?.use {
        if (it.moveToFirst()) {
            val id = it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID))
            return android.net.Uri.withAppendedPath(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id.toString()
            )
        }
    }
    return null
}
