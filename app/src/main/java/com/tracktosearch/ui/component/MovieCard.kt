package com.tracktosearch.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.util.PosterColorExtractor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tracktosearch.ui.navigation.DetailSeedStore
import java.util.concurrent.atomic.AtomicReference

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PosterColorExtractorProvider {
    fun posterColorExtractor(): PosterColorExtractor
}

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
    isWatched: Boolean = false,
    showStatusText: Boolean = true
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    // 当前活跃海报 tmdbId(-1=都不启用 / 具体值=只有匹配的启用)
    val activePosterTmdbId = LocalActivePosterTmdbId.current
    val setActivePosterTmdbId = LocalActivePosterClickSetter.current
    // 当前活跃点击 token,每次点击递增;只有 token 匹配的卡片实例才启用 sharedElement
    val activeClickToken = LocalActivePosterClickToken.current
    // 记录"我自己被点击时"获得的 token
    // 改用 remember(无 Saveable)：横竖屏配置变化后全部重新生成保持一致，避免旧 token 与 activeClickToken 不匹配导致共享元素转场飘错
    var myClickToken by remember { mutableStateOf(0) }
    // 只有"当前可见 tab"且"被用户点击激活"的海报才启用 sharedElement
    // isCurrentTab 避免 HorizontalPager 常驻的非当前 tab 同 tmdbId 海报参与匹配
    // clickToken 匹配避免同页面不同栏目下同 tmdbId 海报参与匹配(转场飘错根因)
    val isCurrentTab = LocalIsCurrentTab.current
    val enableShared = tmdbId == activePosterTmdbId
        && isCurrentTab
        && myClickToken != 0
        && myClickToken == activeClickToken
    val context = LocalContext.current
    // 通过 EntryPoint 获取 PosterColorExtractor 单例,用于提前提取海报主色写入缓存
    val posterColorExtractor = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PosterColorExtractorProvider::class.java
        ).posterColorExtractor()
    }
    // 卡片可见性状态:海报加载成功 + 卡片仍在屏幕上 1.5s 后才提取主色,
    // 避免快速滑动时大量卡片同时触发 Palette CPU 密集型计算影响帧率
    var colorExtracted by remember { mutableStateOf(false) }
    // 仅保存延迟主色提取任务，不把 Bitmap 放入 Compose 状态。
    val colorExtractionScope = rememberCoroutineScope()
    val colorExtractionJob = remember { AtomicReference<Job?>(null) }

    // posterUrl 变化时重置提取状态
    LaunchedEffect(posterUrl) {
        colorExtracted = false
        colorExtractionJob.getAndSet(null)?.cancel()
    }

    // 海报加载成功 + 卡片仍在组合树中,延迟 500ms 后提取主色
    // 快速滑过的卡片会在 DisposableEffect 中取消协程,不会浪费 CPU
    // 500ms 确保用户点击卡片进入详情页前 PosterColorCache 大概率已写入
    // 卡片海报源图已降级 w342(列表数据 posterUrl),解码 342 与源图 1:1,显示约 318px 清晰;
    // 详情页 header 改用 w780 独立高清图(见 DetailHeaderContent),不再与卡片共享解码图,
    // 转场期间由 header 的 w342 占位图兜底避免"闪空"
    val imageRequest = remember(posterUrl) {
        ImageRequest.Builder(context)
            .data(posterUrl)
            .size(342)
            .crossfade(false)
            .listener(
                onSuccess = { _, result ->
                    // 仅标记海报已加载,不立即提取主色
                    // 由 LaunchedEffect + delay 控制提取时机
                    colorExtractionJob.getAndSet(null)?.cancel()
                    val bitmap = result.drawable.toBitmap()
                    colorExtractionJob.set(colorExtractionScope.launch {
                        delay(500L)
                        if (!colorExtracted && posterUrl != null) {
                            withContext(Dispatchers.Default) {
                                posterColorExtractor.extractDominantColor(posterUrl, bitmap)
                            }
                            colorExtracted = true
                        }
                    }
                    )
                }
            )
            .build()
    }

    // 卡片离开屏幕时清理 bitmap 引用,帮助 GC
    DisposableEffect(posterUrl) {
        onDispose {
            colorExtractionJob.getAndSet(null)?.cancel()
        }
    }

    // 包装点击回调:点击时记录当前海报为活跃状态,并获取新的 token
    // remember 包裹避免每次重组创建新 lambda 实例,减少不必要 recomposition
    val wrappedOnClick = remember(onClick, tmdbId, posterUrl, year) {
        {
            if (tmdbId > 0) {
                myClickToken = setActivePosterTmdbId(tmdbId)
                // 把卡片已渲染的海报/年份交给详情页做首帧种子：
                // 发现页等栏目的海报来自 TMDB 列表接口，不会写入详情缓存，详情页 peek 落空
                DetailSeedStore.remember(tmdbId, posterUrl, year)
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

    // 整卡合并成一个无障碍节点。
    //
    // 原先一张卡片会向语义树贡献 6-7 个节点（海报、状态角标文字、评分、年份、标题、类型），
    // 3 列网格同屏 9-12 张就是 60-80 个节点，滚动时还在不断进出。设备上只要有订阅
    // TYPE_WINDOW_CONTENT_CHANGED 的无障碍服务（本机是 GKD + 小米 AI 引擎），每帧都要重建、
    // 比对并下发整棵树：Simpleperf 实测 getCurrentSemanticsNodes + setTraversalValues 合计占
    // me_scroll 应用采样 25.8%。
    //
    // mergeDescendants 把子树折叠成单个节点，节点数降一个量级；对读屏用户也更好——网格项本来
    // 就该是「一格一个可聚焦项 + 一句完整描述」，而不是 6 个碎片。clickable 的点击动作仍在本
    // 节点上，无障碍激活不受影响。
    val cardSemanticsLabel = remember(title, year, genres, isWatched, isInWatchlist) {
        buildString {
            append(title)
            if (year != null) append(" ($year)")
            if (genres.isNotBlank()) append(" · $genres")
        }
    }
    val watchedBadgeText = stringResource(R.string.cd_watched_badge)
    val watchlistBadgeText = stringResource(R.string.cd_watchlist_badge)
    val cardStateSuffix = when {
        isWatched -> watchedBadgeText
        isInWatchlist -> watchlistBadgeText
        else -> null
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = wrappedOnClick,
                        onLongClick = onLongClick
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = wrappedOnClick
                    )
                }
            )
            .semantics(mergeDescendants = true) {
                contentDescription = if (cardStateSuffix != null) {
                    "$cardSemanticsLabel，$cardStateSuffix"
                } else {
                    cardSemanticsLabel
                }
            },
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            val imageModifier = if (enableShared && sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
                with(sharedTransitionScope) {
                    Modifier
                        .sharedElement(
                            rememberSharedContentState(key = "poster-$tmdbId"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp))
                }
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp))
            }

            Box {
                SubcomposeAsyncImage(
                    model = imageRequest,
                    // 描述由卡片根节点的合并语义统一承担，这里不再单独出节点。
                    contentDescription = null,
                    modifier = imageModifier,
                    contentScale = ContentScale.Crop,
                    // 低分辨率 w92 缩略图占位:先显示轮廓再替换为 w342 清晰图,改善加载白屏感知;
                    // 仅 TMDB URL 生效(swapSize 对豆瓣图原样返回,避免重复加载同一原图)
                    loading = {
                        val thumbUrl = posterUrl?.let {
                            if (it.contains("/t/p/")) TmdbImageUrls.swapSize(it, "w92") else null
                        }
                        if (thumbUrl != null) {
                            AsyncImage(
                                model = remember(thumbUrl) {
                                    ImageRequest.Builder(context)
                                        .data(thumbUrl)
                                        .size(92)
                                        .crossfade(false)
                                        .build()
                                },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    },
                    error = null
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
