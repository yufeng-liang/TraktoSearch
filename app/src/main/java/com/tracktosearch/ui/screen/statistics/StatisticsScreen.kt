package com.tracktosearch.ui.screen.statistics

import android.content.ClipData
import android.content.Intent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.backdropSource
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.rememberShimmer
import com.tracktosearch.ui.component.shimmer
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.performHaptic
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun StatisticsScreen(
    onBack: () -> Unit,
    viewModel: StatisticsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showInfoDialog by remember { mutableStateOf(false) }
    // 共享元素转场 scope(与设置页观看统计卡片配对)
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

    LaunchedEffect(Unit) {
        viewModel.loadStatistics()
    }

    val statsHazeState = remember { HazeState() }
    val statsHazeStyle = HazeMaterials.thin()
    val statisticsContentCount = uiState.genreDistribution.values.sum() +
        uiState.heatmapData.size + uiState.wordCloud.size
    val statisticsGlassScene = glassSceneForContent(
        contentCount = statisticsContentCount,
        readabilityDemand = when {
            uiState.error != null -> 0.96f
            uiState.initialLoading -> 0.84f
            else -> 0.90f
        },
        ambientColor = MaterialTheme.colorScheme.background,
        contentCapacity = 72
    )

    // Hero 卡片与分享长图共用的派生量：连看天数要扫全量热力图（约一年 365 个 key），
    // 提到组合顶层算一次，列表 item 被回收重建时不重复解析日期
    val streakDays = remember(uiState.heatmapData) {
        longestWatchStreak(uiState.heatmapData)
    }
    val highlight = remember(
        streakDays,
        uiState.thisYearWatched,
        uiState.totalWatchMinutes,
        uiState.genreDistribution,
        uiState.totalMovieCount,
        uiState.showsWatchedCount
    ) {
        pickHighlight(
            streakDays = streakDays,
            thisYearWatched = uiState.thisYearWatched,
            totalWatchMinutes = uiState.totalWatchMinutes,
            genreDistribution = uiState.genreDistribution,
            totalWatchedCount = uiState.totalMovieCount + uiState.showsWatchedCount
        )
    }

    // 分享长图：内容与文案在组合里取好，位图渲染与写文件都在后台线程
    val context = LocalContext.current
    val shareScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var sharing by remember { mutableStateOf(false) }
    val shareData = statisticsShareData(
        uiState = uiState,
        locale = statisticsLocale(),
        highlight = highlight
    )
    val shareFailedText = stringResource(R.string.statistics_share_failed)
    val shareSavedText = stringResource(R.string.statistics_share_saved)
    val shareOpenFailedText = stringResource(R.string.statistics_share_open_failed)
    val shareChooserTitle = stringResource(R.string.statistics_share_chooser)
    val shareEnabled = shareData != null && !sharing
    val onShare: () -> Unit = {
        val data = shareData
        if (data != null && !sharing) {
            sharing = true
            shareScope.launch {
                try {
                    // 渲染完成后先写进系统相册，分享使用 MediaStore 的 content URI。
                    val uri = renderStatisticsShareImage(context, data)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("statistics", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val feedbackText = try {
                        context.startActivity(Intent.createChooser(intent, shareChooserTitle))
                        shareSavedText
                    } catch (_: Exception) {
                        // 相册已经保存成功；系统分享面板异常不能误报成“保存失败”。
                        shareOpenFailedText
                    }
                    shareScope.launch { snackbarHostState.showSnackbar(feedbackText) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 渲染或相册写入失败时不拉起分享，也不显示“已保存”。
                    snackbarHostState.showSnackbar(shareFailedText)
                } finally {
                    sharing = false
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.testTag("statistics_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val listState = rememberLazyListState()
            val hasContentUnderTopBar by remember {
                derivedStateOf {
                    hasListScrolled(
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        firstVisibleItemScrollOffsetPx = listState.firstVisibleItemScrollOffset
                    )
                }
            }
            val errorMsg = uiState.error
            if (errorMsg != null && !uiState.watchTimeReady && !uiState.overviewReady) {
                AppErrorState(
                    message = errorMsg,
                    onRetry = { viewModel.loadStatistics() },
                    modifier = Modifier.fillMaxSize(),
                    retryLabel = stringResource(R.string.watchlist_retry)
                )
            } else {
                // Navigation 会恢复 LazyListState；页面实例每次重新进入时强制从 Hero 顶部开始。
                // 只以 Unit 为 key，避免数据稍后就绪时把已经开始浏览的用户再次拉回顶部。
                LaunchedEffect(Unit) {
                    listState.scrollToItem(0)
                }
                val scrollToTopProvider = LocalScrollToTopProvider.current
                val statsCoroutineScope = rememberCoroutineScope()
                DisposableEffect(Unit) {
                    scrollToTopProvider.register {
                        statsCoroutineScope.launch {
                            listState.animateScrollToItem(0)
                        }
                    }
                    onDispose {
                        scrollToTopProvider.unregister()
                    }
                }
                val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                // 热力图翻页位置与网格计算提升到列表外：item 滑出屏幕被回收也不会丢翻页位置，
                // 且 13×7 网格（含 SimpleDateFormat / Calendar 运算）只在数据或翻页变化时重算一次
                var heatmapWeekOffset by rememberSaveable { mutableStateOf(0) }
                val heatmapLocale = statisticsLocale()
                val heatmapGrid = remember(
                    uiState.heatmapData,
                    uiState.heatmapReady,
                    heatmapWeekOffset,
                    heatmapLocale
                ) {
                    if (uiState.heatmapReady) {
                        buildHeatmapGrid(
                            heatmapData = uiState.heatmapData,
                            weekOffset = heatmapWeekOffset,
                            locale = heatmapLocale
                        )
                    } else {
                        null
                    }
                }
                // 词云布局按屏幕缓存：item 被回收再进入时直接复用，不重跑螺旋排布
                val wordCloudLayoutStore = rememberWordCloudLayoutStore()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = statsHazeState)
                        .backdropSource(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 65.dp + statusBarHeight,
                        bottom = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Hero 小结：一句话 + 一个大数字，先把「最突出的一项」讲成人话，
                // 后面的总览再给全量数字。数据未就绪时不占位，避免一进页面就是空卡片。
                item(key = "hero") {
                    if (uiState.overviewReady) {
                        StatisticsHeroCard(
                            highlight = highlight,
                            thisYearWatched = uiState.thisYearWatched,
                            totalHours = (uiState.totalWatchMinutes / 60).toInt(),
                            streakDays = streakDays
                        )
                    }
                }

                // 总览卡片（数字跳动动画）
                item(key = "overview") {
                    val reveal = rememberSectionReveal(listState, "overview")
                    SectionCard(title = stringResource(R.string.statistics_overview)) {
                        if (uiState.overviewReady) {
                            OverviewCards(uiState, reveal)
                        } else {
                            StatisticsSkeletonContent(
                                variant = StatisticsSkeletonVariant.OVERVIEW
                            )
                        }
                    }
                }

                // 观影时长
                item(key = "watch_time") {
                    val reveal = rememberSectionReveal(listState, "watch_time")
                    SectionCard(title = stringResource(R.string.statistics_total_watch_time)) {
                        if (uiState.watchTimeReady) {
                            WatchTimeCard(uiState = uiState, reveal = reveal)
                        } else {
                            StatisticsSkeletonContent(StatisticsSkeletonVariant.WATCH_TIME)
                        }
                    }
                }

                // 热力图
                item(key = "heatmap") {
                    val reveal = rememberSectionReveal(listState, "heatmap")
                    SectionCard(title = stringResource(R.string.statistics_heatmap)) {
                        if (heatmapGrid != null) {
                            HeatmapChart(
                                grid = heatmapGrid,
                                locale = heatmapLocale,
                                weekOffset = heatmapWeekOffset,
                                onWeekOffsetChange = { heatmapWeekOffset = it },
                                reveal = reveal
                            )
                        } else {
                            StatisticsSkeletonContent(StatisticsSkeletonVariant.HEATMAP)
                        }
                    }
                }

                // 评分统计
                item(key = "ratings") {
                    val reveal = rememberSectionReveal(listState, "ratings")
                    SectionCard(title = stringResource(R.string.statistics_ratings)) {
                        if (uiState.ratingsReady) {
                            RatingStatsCard(uiState = uiState, reveal = reveal)
                        } else {
                            StatisticsSkeletonContent(StatisticsSkeletonVariant.RATINGS)
                        }
                    }
                }

                // 短评词云
                item(key = "wordcloud") {
                    SectionCard(title = stringResource(R.string.statistics_wordcloud)) {
                        if (!uiState.wordCloudReady) {
                            StatisticsSkeletonContent(StatisticsSkeletonVariant.WORD_CLOUD)
                        } else if (uiState.wordCloud.isNotEmpty()) {
                            WordCloud(
                                words = uiState.wordCloud,
                                layoutStore = wordCloudLayoutStore,
                                modifier = Modifier.fillMaxWidth().height(220.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.statistics_wordcloud_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // 类型分布（饼图，展开动画）
                item(key = "pie") {
                    val reveal = rememberSectionReveal(listState, "pie")
                    SectionCard(title = stringResource(R.string.statistics_genre_distribution)) {
                        if (uiState.genreReady && uiState.genreDistribution.isNotEmpty()) {
                            GenrePieChart(genreDistribution = uiState.genreDistribution, reveal = reveal)
                        } else if (!uiState.genreReady) {
                            StatisticsSkeletonContent(StatisticsSkeletonVariant.PIE)
                        } else {
                            Text(
                                text = stringResource(R.string.common_no_data),
                                modifier = Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 最常看的类型排行（柱形增长动画 + emoji奖牌）
                item(key = "ranking") {
                    val reveal = rememberSectionReveal(listState, "ranking")
                    SectionCard(title = stringResource(R.string.statistics_genre_ranking)) {
                        if (uiState.genreReady && uiState.genreDistribution.isNotEmpty()) {
                            GenreRanking(genreDistribution = uiState.genreDistribution, reveal = reveal)
                        } else if (!uiState.genreReady) {
                            StatisticsSkeletonContent(StatisticsSkeletonVariant.RANKING)
                        } else {
                            Text(
                                text = stringResource(R.string.common_no_data),
                                modifier = Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            }
            // Haze模糊渐变TopAppBar（含状态栏）
            // 「标题+返回箭头」整体与设置页观看统计入口配对（sharedBounds）
            val headerModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
                with(sharedTransitionScope) {
                    Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "settings-statistics-entry"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                }
            } else { Modifier }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(headerModifier)
                    .hazeTopBar(
                        state = statsHazeState,
                        style = statsHazeStyle,
                        blurRadius = 24.dp,
                        isContentUnderTopBar = hasContentUnderTopBar,
                        scene = statisticsGlassScene
                    )
                    .clickable(enabled = false, onClick = {})
            ) {
                Spacer(modifier = Modifier.statusBarsPadding())
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.detail_back), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = stringResource(R.string.statistics_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { showInfoDialog = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Help,
                        contentDescription = stringResource(R.string.statistics_info),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onShare,
                    enabled = shareEnabled,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = stringResource(R.string.statistics_share),
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
                // 展示上次快照并后台刷新时的细进度条：不遮挡内容，只提示数据可能不是最新
                if (uiState.isRefreshing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                    )
                }
            }
        }
    }

    if (showInfoDialog) {
        StatisticsInfoDialog(onDismiss = { showInfoDialog = false })
    }
}

/** 统计说明弹窗 */
@Composable
private fun StatisticsInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.statistics_info)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.statistics_info_overview),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.statistics_info_watch_time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.statistics_info_heatmap),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.statistics_info_ratings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.statistics_info_wordcloud),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.statistics_info_genre_distribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.statistics_info_genre_ranking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

/**
 * 分区的「首次露出」状态。
 *
 * @param revealed 是否已经进入过可视区域
 * @param playAnimation 本次组合是否需要播放入场动画（只有首次露出才播）
 */
@Immutable
private data class SectionReveal(
    val revealed: Boolean,
    val playAnimation: Boolean
)

/**
 * 记录某个 LazyColumn item 是否露出过，用于入场动画只播一次。
 *
 * [revealed] 存在 rememberSaveable 里，item 滑出屏幕被回收后再回来仍是 true，
 * 所以动画不会重播。可见性用 snapshotFlow 只取「第一次可见」，露出之后不再监听，
 * 避免 derivedStateOf 每帧读 layoutInfo 造成滑动掉帧。
 */
@Composable
private fun rememberSectionReveal(listState: LazyListState, itemKey: Any): SectionReveal {
    var revealed by rememberSaveable(itemKey) { mutableStateOf(false) }
    // 组合时就固定下来：本次是否为首次露出。之后 revealed 变 true 也不影响这个判断
    val playAnimation = remember { !revealed }
    if (!revealed) {
        LaunchedEffect(itemKey) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.any { it.key == itemKey }
            }.first { it }
            revealed = true
        }
    }
    return SectionReveal(revealed = revealed, playAnimation = playAnimation)
}

/**
 * 入场动画：首次露出播一次，之后（含 item 回收重建、数据刷新）直接给最终值。
 *
 * 返回 [Animatable] 而不是 Float，Canvas 这类绘制场景可以把 `value` 的读取留在
 * 绘制阶段，动画期间只失效绘制、不重组。
 */
@Composable
private fun rememberRevealAnimatable(
    targetValue: Float,
    reveal: SectionReveal,
    durationMillis: Int = 1500,
    delayMillis: Int = 0
): Animatable<Float, AnimationVector1D> {
    val animatable = remember { Animatable(if (reveal.playAnimation) 0f else targetValue) }
    // 只在协程里读写，不参与组合，避免动画开始时额外触发一次重组
    var animated by remember { mutableStateOf(!reveal.playAnimation) }

    LaunchedEffect(reveal.revealed, targetValue) {
        if (!reveal.revealed) return@LaunchedEffect
        if (animated) {
            animatable.snapTo(targetValue)
        } else {
            animated = true
            animatable.animateTo(
                targetValue = targetValue,
                animationSpec = tween(
                    durationMillis = durationMillis,
                    delayMillis = delayMillis,
                    easing = LinearOutSlowInEasing
                )
            )
        }
    }
    return animatable
}

/** [rememberRevealAnimatable] 的取值版本，读取发生在组合期（文本、布局用）。 */
@Composable
private fun rememberRevealAnimatedFloat(
    targetValue: Float,
    reveal: SectionReveal,
    durationMillis: Int = 1500,
    delayMillis: Int = 0
): Float = rememberRevealAnimatable(
    targetValue = targetValue,
    reveal = reveal,
    durationMillis = durationMillis,
    delayMillis = delayMillis
).value

/** 统计页日期文案使用的 Locale（跟随应用语言）。 */
@Composable
private fun statisticsLocale(): Locale {
    val language = LocalLocale.current.platformLocale.language
    return remember(language) {
        when (language) {
            "en" -> Locale.ENGLISH
            "ja" -> Locale.JAPANESE
            "ko" -> Locale.KOREAN
            else -> Locale.CHINESE
        }
    }
}

/**
 * 总览：2 列 × 3 行图标格子。
 *
 * 原先 3 列时「剧集」格要塞主数字 + 「N 部看完」副行，窄屏会折成两行折字；
 * 且 5 个格子第二行只放 2 个，右侧留一大块空。改 2 列后每格更宽，
 * 「图标 + 标签」和「大数字 + 单位」两层都放得下，补上「已评分」正好铺满 6 格。
 */
@Composable
private fun OverviewCards(uiState: StatisticsUiState, reveal: SectionReveal) {
    val movies = rememberOneShotAnimatedInt(uiState.totalMovieCount, reveal)
    val shows = rememberOneShotAnimatedInt(uiState.showsWatchedCount, reveal)
    val completed = rememberOneShotAnimatedInt(uiState.showsCompletedCount, reveal)
    val episodes = rememberOneShotAnimatedInt(uiState.totalEpisodeCount, reveal)
    val thisMonth = rememberOneShotAnimatedInt(uiState.thisMonthWatched, reveal)
    val thisYear = rememberOneShotAnimatedInt(uiState.thisYearWatched, reveal)
    val ratings = rememberOneShotAnimatedInt(uiState.totalRatings, reveal)
    // 用目标值而非动画中间值判断，避免数字跳动过程中副行闪现
    val showCompletedLine = uiState.showsCompletedReady &&
        uiState.showsCompletedCount != uiState.showsWatchedCount
    val unitTitles = stringResource(R.string.statistics_unit_titles)
    val unitEpisodes = stringResource(R.string.statistics_unit_episodes)
    val unitTimes = stringResource(R.string.statistics_unit_times)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatOverviewTile(
                icon = Icons.Rounded.Movie,
                value = movies.toString(),
                unit = unitTitles,
                label = stringResource(R.string.statistics_movies),
                modifier = Modifier.weight(1f)
            )
            // 剧集有两个口径：有观看记录的剧数（含未看完）与整部看完的剧数。
            // Trakt 的「已看」只要看过一集就计入，和「看完」差别很大，分开显示避免歧义。
            StatOverviewTile(
                icon = Icons.Rounded.Tv,
                value = shows.toString(),
                unit = unitTitles,
                label = stringResource(R.string.statistics_shows),
                modifier = Modifier.weight(1f),
                secondary = if (showCompletedLine) {
                    stringResource(R.string.statistics_shows_completed, completed)
                } else {
                    null
                }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatOverviewTile(
                icon = Icons.Rounded.LiveTv,
                value = episodes.toString(),
                unit = unitEpisodes,
                label = stringResource(R.string.statistics_episodes),
                modifier = Modifier.weight(1f)
            )
            StatOverviewTile(
                icon = Icons.Rounded.Star,
                value = ratings.toString(),
                unit = unitTimes,
                label = stringResource(R.string.statistics_overview_rated),
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatOverviewTile(
                icon = Icons.Rounded.Schedule,
                value = thisMonth.toString(),
                unit = unitTitles,
                label = stringResource(R.string.statistics_this_month),
                modifier = Modifier.weight(1f)
            )
            StatOverviewTile(
                icon = Icons.Rounded.Insights,
                value = thisYear.toString(),
                unit = unitTitles,
                label = stringResource(R.string.statistics_this_year),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 数字只在首次进入可视区域时播放一次，后续数据更新直接同步最终值。 */
@Composable
private fun rememberOneShotAnimatedInt(targetValue: Int, reveal: SectionReveal): Int =
    rememberRevealAnimatedFloat(
        targetValue = targetValue.toFloat(),
        reveal = reveal,
        durationMillis = 1500
    ).roundToInt()

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    StatsSectionCard(title = title, content = content)
}

@Composable
internal fun localizedGenreName(genre: String): String = when (genre) {
    "action" -> stringResource(R.string.genre_action)
    "adventure" -> stringResource(R.string.genre_adventure)
    "animation" -> stringResource(R.string.genre_animation)
    "anime" -> stringResource(R.string.genre_anime)
    "comedy" -> stringResource(R.string.genre_comedy)
    "crime" -> stringResource(R.string.genre_crime)
    "documentary" -> stringResource(R.string.genre_documentary)
    "drama" -> stringResource(R.string.genre_drama)
    "fantasy" -> stringResource(R.string.genre_fantasy)
    "history" -> stringResource(R.string.genre_history)
    "horror" -> stringResource(R.string.genre_horror)
    "music" -> stringResource(R.string.genre_music)
    "musical" -> stringResource(R.string.genre_musical)
    "mystery" -> stringResource(R.string.genre_mystery)
    "romance" -> stringResource(R.string.genre_romance)
    "science-fiction", "science_fiction", "sci-fi", "scifi" -> stringResource(R.string.genre_scifi)
    "sport" -> stringResource(R.string.genre_sport)
    "thriller" -> stringResource(R.string.genre_thriller)
    "war" -> stringResource(R.string.genre_war)
    "western" -> stringResource(R.string.genre_western)
    "family" -> stringResource(R.string.genre_family)
    "children", "kids" -> stringResource(R.string.genre_kids)
    "news" -> stringResource(R.string.genre_news)
    "reality" -> stringResource(R.string.genre_reality)
    "soap" -> stringResource(R.string.genre_soap)
    "talk", "talk_show" -> stringResource(R.string.genre_talk)
    "espionage", "spy" -> stringResource(R.string.genre_spy)
    "superhero" -> stringResource(R.string.genre_superhero)
    "biography" -> stringResource(R.string.genre_biography)
    "film-noir", "film_noir", "noir" -> stringResource(R.string.genre_noir)
    "game-show", "game_show" -> stringResource(R.string.genre_game_show)
    "other" -> stringResource(R.string.genre_other)
    else -> genre.replaceFirstChar { it.uppercase() }
}

/** 饼图/图例配色：统一走 8 色暖调分析色板，与词云、类型排行同一套 */
private const val PIE_MAX_SLICES = ANALYTICS_PALETTE_SIZE

/** 饼图扇区：类型名 + 计数 */
@Immutable
private data class PieSlice(val genre: String, val count: Int)

/** 取前 7 大类型，其余合并为「其他」，最多 8 个扇区，与分析色板容量一致。 */
private fun pieSlices(genreDistribution: Map<String, Int>): List<PieSlice> {
    val sorted = genreDistribution.entries.sortedByDescending { it.value }
    return if (sorted.size > PIE_MAX_SLICES) {
        sorted.take(PIE_MAX_SLICES - 1).map { PieSlice(it.key, it.value) } +
            PieSlice("other", sorted.drop(PIE_MAX_SLICES - 1).sumOf { it.value })
    } else {
        sorted.map { PieSlice(it.key, it.value) }
    }
}

/** 类型分布饼图（展开动画 + 点击交互） */
@Composable
private fun GenrePieChart(genreDistribution: Map<String, Int>, reveal: SectionReveal) {
    val view = LocalView.current
    val totalCount = genreDistribution.values.sum()
    if (totalCount == 0) return

    val entries = remember(genreDistribution) { pieSlices(genreDistribution) }
    val colors = rememberAnalyticsPalette()

    // 饼图展开动画进度（只在首次露出时播一次，进度在绘制阶段读取）
    val sweepProgress = rememberRevealAnimatable(
        targetValue = 1f,
        reveal = reveal,
        durationMillis = 1500
    )

    // 点击选中的扇区索引
    var selectedIndex by remember { mutableStateOf(-1) }

    val density = LocalDensity.current
    val chartSizeDp = 200.dp
    val chartSizePx = with(density) { chartSizeDp.toPx() }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(
                modifier = Modifier
                    .size(chartSizeDp)
                    .pointerInput(entries) {
                        detectTapGestures { tapOffset ->
                            // 计算点击的扇区
                            val center = Offset(chartSizePx / 2f, chartSizePx / 2f)
                            val dx = tapOffset.x - center.x
                            val dy = tapOffset.y - center.y
                            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                            val radius = chartSizePx / 2f
                            if (distance <= radius) {
                                // atan2 返回 0°=右, 90°=下, 180°=左, 270°=上
                                // 饼图从 -90°（顶部）开始顺时针，需将点击角度偏移 +90° 对齐
                                var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                if (angle < 0) angle += 360f
                                angle = (angle + 90f) % 360f
                                var accumulated = 0f
                                for ((index, entry) in entries.withIndex()) {
                                    val sweep = 360f * entry.count.toFloat() / totalCount.toFloat()
                                    if (angle >= accumulated && angle < accumulated + sweep) {
                                        view.performHaptic(HapticType.CLICK)
                                        selectedIndex = if (selectedIndex == index) -1 else index
                                        break
                                    }
                                    accumulated += sweep
                                }
                            } else {
                                selectedIndex = -1
                            }
                        }
                    }
            ) {
                val canvasRadius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                var startAngle = -90f // 从顶部开始

                entries.forEachIndexed { index, entry ->
                    val fullSweep = 360f * entry.count.toFloat() / totalCount.toFloat()
                    val animatedSweep = fullSweep * sweepProgress.value
                    val color = colors[index % colors.size]
                    val isSelected = selectedIndex == index

                    // 选中的扇区向外偏移
                    val midAngle = startAngle + animatedSweep / 2f
                    val offset = if (isSelected) 8f else 0f
                    val offsetX = (cos(Math.toRadians(midAngle.toDouble())).toFloat()) * offset
                    val offsetY = (sin(Math.toRadians(midAngle.toDouble())).toFloat()) * offset

                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = animatedSweep,
                        useCenter = true,
                        topLeft = Offset(center.x - canvasRadius + offsetX, center.y - canvasRadius + offsetY),
                        size = Size(canvasRadius * 2, canvasRadius * 2)
                    )
                    startAngle += animatedSweep
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 显示选中扇区的详细信息（固定高度防跳变）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (selectedIndex >= 0 && selectedIndex < entries.size) {
                    val entry = entries[selectedIndex]
                    val percentage = String.format("%.1f%%", entry.count.toFloat() / totalCount.toFloat() * 100)
                    Text(
                        text = stringResource(R.string.statistics_item_count, localizedGenreName(entry.genre), entry.count, percentage),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors[selectedIndex % colors.size]
                    )
                } else {
                    Text(
                        text = stringResource(R.string.statistics_pie_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 图例
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                entries.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { view.performHaptic(HapticType.CLICK); selectedIndex = if (selectedIndex == index) -1 else index },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(colors[index % colors.size])
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = localizedGenreName(entry.genre),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = entry.count.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 排行前三名的奖牌 */
private val MEDAL_EMOJIS = listOf("🥇", "🥈", "🥉")

/** 最常看的类型排行（柱形增长动画 + emoji奖牌） */
@Composable
private fun GenreRanking(genreDistribution: Map<String, Int>, reveal: SectionReveal) {
    val maxCount = genreDistribution.values.maxOrNull() ?: 1
    // 与饼图取同样的排序与条数：同一个类型在饼图和排行里拿到同一个颜色，两块图才能互相对读
    val colors = rememberAnalyticsPalette()
    val topGenres = remember(genreDistribution) {
        genreDistribution.entries
            .sortedByDescending { it.value }
            .take(PIE_MAX_SLICES)
            .map { it.key to it.value }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        topGenres.forEachIndexed { index, (genre, count) ->
            key(genre) {
                // 柱形增长动画：首次露出播一次
                val animatedFraction = rememberRevealAnimatedFloat(
                    targetValue = count.toFloat() / maxCount.toFloat(),
                    reveal = reveal,
                    durationMillis = 1200,
                    delayMillis = index * 120
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 排名标志：前三名用emoji奖牌，其余用与饼图一致的类型色点
                    Box(
                        modifier = Modifier.size(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (index < 3) {
                            Text(
                                text = MEDAL_EMOJIS[index],
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(colors[index % colors.size])
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = localizedGenreName(genre),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 进度条
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedFraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

/** 热力图周数：一页固定 13 周 */
private const val HEATMAP_WEEKS = 13

/**
 * 热力图单格。
 *
 * [dateKey] 为 `yyyy-MM-dd`；未来日期不展示，[dateKey] 置空串。
 */
internal data class HeatmapCell(
    val dateKey: String,
    val count: Int,
    val isFuture: Boolean,
    val date: Date
)

/** 热力图一页的网格数据：13 周 × 7 天（每列自周一起排）+ 月份标签 + 日期范围文案 */
internal data class HeatmapGrid(
    val weeks: List<List<HeatmapCell>>,
    val monthLabels: List<Pair<Int, String>>,
    val rangeStart: String,
    val rangeEnd: String
)

/**
 * 构建热力图网格。
 *
 * 纯计算函数，放在组合之外用 remember 缓存：这段有 4 个 SimpleDateFormat 与 91 次
 * 日期格式化，放在组合里每次重组都要重跑，滑动时会掉帧。
 *
 * @param weekOffset 翻页偏移，0 为最近 13 周，每页 ±[HEATMAP_WEEKS] 周
 * @param today 基准「今天」，测试可注入
 */
internal fun buildHeatmapGrid(
    heatmapData: Map<String, Int>,
    weekOffset: Int,
    locale: Locale,
    today: Date = Date()
): HeatmapGrid {
    val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val monthFormat = if (locale == Locale.CHINESE) {
        SimpleDateFormat("M月", Locale.CHINESE)
    } else {
        SimpleDateFormat("MMM", locale)
    }
    val rangeFormat = SimpleDateFormat("yyyy/M/d", Locale.US)

    // Calendar.DAY_OF_WEEK 以周日为 1，换算成「距本周一过了几天」，
    // 让每列从周一开始，与左侧一/月/월/Mon 起排的星期标签对齐
    val dayOfWeek = Calendar.getInstance().apply { time = today }.get(Calendar.DAY_OF_WEEK)
    val daysSinceMonday = (dayOfWeek + 5) % 7
    val startCal = Calendar.getInstance().apply {
        time = today
        add(Calendar.DAY_OF_YEAR, -((HEATMAP_WEEKS - 1) * 7 + daysSinceMonday) + weekOffset * 7)
    }
    val endCal = (startCal.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, HEATMAP_WEEKS * 7 - 1)
    }

    val weeks = ArrayList<List<HeatmapCell>>(HEATMAP_WEEKS)
    val monthLabels = mutableListOf<Pair<Int, String>>()
    val cursor = startCal.clone() as Calendar
    var lastMonth = -1

    for (weekIndex in 0 until HEATMAP_WEEKS) {
        val week = ArrayList<HeatmapCell>(7)
        for (dayIndex in 0 until 7) {
            val date = cursor.time
            val dateKey = dateKeyFormat.format(date)
            val isFuture = date.after(today)
            week.add(
                HeatmapCell(
                    dateKey = if (isFuture) "" else dateKey,
                    count = heatmapData[dateKey] ?: 0,
                    isFuture = isFuture,
                    date = date
                )
            )

            if (dayIndex == 0) {
                val month = cursor.get(Calendar.MONTH)
                if (month != lastMonth) {
                    monthLabels.add(weekIndex to monthFormat.format(date))
                    lastMonth = month
                }
            }
            cursor.add(Calendar.DAY_OF_YEAR, 1)
        }
        weeks.add(week)
    }

    return HeatmapGrid(
        weeks = weeks,
        monthLabels = monthLabels,
        rangeStart = rangeFormat.format(startCal.time),
        rangeEnd = rangeFormat.format(endCal.time)
    )
}

/** 观看热力图（Canvas 统一绘制，点击格子查看详情，支持翻页查看历史） */
@Composable
private fun HeatmapChart(
    grid: HeatmapGrid,
    locale: Locale,
    weekOffset: Int,
    onWeekOffsetChange: (Int) -> Unit,
    reveal: SectionReveal
) {
    val view = LocalView.current
    val weeks = grid.weeks
    val monthLabels = grid.monthLabels
    val canGoForward = weekOffset < 0
    val fullDateFormat = remember(locale) {
        if (locale == Locale.CHINESE) {
            SimpleDateFormat("yyyy年M月d日", Locale.CHINESE)
        } else {
            SimpleDateFormat("yyyy/M/d", locale)
        }
    }

    // 尺寸常量
    val cellSize = 20.dp
    val cellGap = 3.dp
    val labelWidth = 28.dp
    val monthLabelHeight = 18.dp
    val cornerRadius = 4.dp

    val density = LocalDensity.current
    val cellSizePx = with(density) { cellSize.toPx() }
    val cellGapPx = with(density) { cellGap.toPx() }
    val slotPx = cellSizePx + cellGapPx
    val labelWidthPx = with(density) { labelWidth.toPx() }
    val monthLabelHeightPx = with(density) { monthLabelHeight.toPx() }
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }

    val gridWidthDp = (weeks.size * (cellSize.value + cellGap.value) - cellGap.value).dp
    val totalWidthDp = labelWidth + gridWidthDp
    val gridHeightDp = (7 * (cellSize.value + cellGap.value) - cellGap.value).dp
    val totalHeightDp = monthLabelHeight + gridHeightDp

    val primary = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val selectedBorderColor = MaterialTheme.colorScheme.primary

    val textMeasurer = rememberTextMeasurer()
    val weekdayStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val monthStyle = TextStyle(fontSize = 11.sp, color = labelColor, fontWeight = FontWeight.Medium)

    // 预测量星期文字高度，用于垂直居中
    val weekdayMeasure = remember { textMeasurer.measure("一", weekdayStyle) }
    val weekdayYOffset = (cellSizePx - weekdayMeasure.size.height) / 2f

    var selectedCell by remember { mutableStateOf<HeatmapCell?>(null) }

    // 星期标签跟随语言，提前算好：Canvas 每帧都要用，别放在绘制回调里构造
    val weekdayLabels = remember(locale) {
        when (locale) {
            Locale.CHINESE -> listOf("一", "二", "三", "四", "五", "六", "日")
            Locale.JAPANESE -> listOf("月", "火", "水", "木", "金", "土", "日")
            Locale.KOREAN -> listOf("월", "화", "수", "목", "금", "토", "일")
            else -> listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        }
    }

    // 淡入动画：只在首次露出时播一次，alpha 在绘制阶段读取
    val alphaAnim = rememberRevealAnimatable(
        targetValue = 1f,
        reveal = reveal,
        durationMillis = 900
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // 翻页导航
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onWeekOffsetChange(weekOffset - HEATMAP_WEEKS) },
                modifier = Modifier.size(32.dp)
            ) {
                Text("←", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = "${grid.rangeStart} ~ ${grid.rangeEnd}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(
                onClick = { onWeekOffsetChange(weekOffset + HEATMAP_WEEKS) },
                modifier = Modifier.size(32.dp),
                enabled = canGoForward
            ) {
                Text(
                    "→",
                    fontSize = 18.sp,
                    color = if (canGoForward) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            }
        }

        Canvas(
            modifier = Modifier
                .height(totalHeightDp)
                .horizontalScroll(rememberScrollState())
                .width(totalWidthDp)
                .pointerInput(grid) {
                    detectTapGestures { offset ->
                        val x = offset.x - labelWidthPx
                        val y = offset.y - monthLabelHeightPx
                        if (x >= 0 && y >= 0) {
                            val weekIdx = (x / slotPx).toInt()
                            val dayIdx = (y / slotPx).toInt()
                            if (weekIdx in weeks.indices && dayIdx in 0 until 7) {
                                val cell = weeks[weekIdx][dayIdx]
                                if (!cell.isFuture) {
                                    view.performHaptic(HapticType.CLICK)
                                    selectedCell = cell
                                }
                            }
                        }
                    }
                }
                .pointerInput(weekOffset) {
                    val dragThresholdPx = with(density) { 50.dp.toPx() }
                    var totalDrag = 0f
                    var triggered = false
                    detectHorizontalDragGestures(
                        onDragStart = {
                            totalDrag = 0f
                            triggered = false
                        },
                        onDragEnd = {
                            totalDrag = 0f
                            triggered = false
                        },
                        onDragCancel = {
                            totalDrag = 0f
                            triggered = false
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            if (triggered) return@detectHorizontalDragGestures
                            totalDrag += dragAmount
                            if (totalDrag < -dragThresholdPx) {
                                // 向左滑：看更近日期
                                if (canGoForward) {
                                    onWeekOffsetChange(weekOffset + HEATMAP_WEEKS)
                                    view.performHaptic(HapticType.CLICK)
                                }
                                triggered = true
                            } else if (totalDrag > dragThresholdPx) {
                                // 向右滑：看更早日期
                                onWeekOffsetChange(weekOffset - HEATMAP_WEEKS)
                                view.performHaptic(HapticType.CLICK)
                                triggered = true
                            }
                        }
                    )
                }
        ) {
            // 1. 绘制月份标签
            monthLabels.forEach { (startWeekIndex, label) ->
                val nextStart = monthLabels.indexOfFirst { it.first > startWeekIndex }
                    .let { if (it >= 0 && it < monthLabels.size) monthLabels[it].first else weeks.size }
                val x = labelWidthPx + startWeekIndex * slotPx
                val width = (nextStart - startWeekIndex) * slotPx
                drawText(
                    textMeasurer = textMeasurer,
                    text = label,
                    topLeft = Offset(x, 0f),
                    style = monthStyle,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                    size = Size(width, monthLabelHeightPx)
                )
            }

            // 2. 绘制星期标签
            weekdayLabels.forEachIndexed { dayIdx, label ->
                val y = monthLabelHeightPx + dayIdx * slotPx + weekdayYOffset
                drawText(
                    textMeasurer = textMeasurer,
                    text = label,
                    topLeft = Offset(0f, y),
                    style = weekdayStyle,
                    size = Size(labelWidthPx, cellSizePx)
                )
            }

            // 3. 绘制热力图格子（alpha 相乘保留深浅层次）
            val cellAlpha = alphaAnim.value
            val emptyBorderColor = labelColor.copy(alpha = 0.15f * cellAlpha)
            weeks.forEachIndexed { weekIdx, week ->
                week.forEachIndexed { dayIdx, cell ->
                    val x = labelWidthPx + weekIdx * slotPx
                    val y = monthLabelHeightPx + dayIdx * slotPx

                    if (cell.isFuture) {
                        // 未来日期不绘制
                    } else {
                        val base = heatmapColor(cell.count, primary, emptyColor)
                        val fillColor = base.copy(alpha = base.alpha * cellAlpha)
                        drawRoundRect(
                            color = fillColor,
                            topLeft = Offset(x, y),
                            size = Size(cellSizePx, cellSizePx),
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                        )
                        // 空白格子添加细边框使其可见
                        if (cell.count == 0) {
                            drawRoundRect(
                                color = emptyBorderColor,
                                topLeft = Offset(x, y),
                                size = Size(cellSizePx, cellSizePx),
                                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
                    }
                }
            }

            // 4. 绘制选中格子高亮边框
            selectedCell?.let { selected ->
                weeks.forEachIndexed { weekIdx, week ->
                    week.forEachIndexed { dayIdx, cell ->
                        if (cell.dateKey == selected.dateKey && !cell.isFuture) {
                            val x = labelWidthPx + weekIdx * slotPx
                            val y = monthLabelHeightPx + dayIdx * slotPx
                            drawRoundRect(
                                color = selectedBorderColor,
                                topLeft = Offset(x, y),
                                size = Size(cellSizePx, cellSizePx),
                                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 选中格子详情（固定高度防跳变）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            contentAlignment = Alignment.Center
        ) {
            selectedCell?.let { cell ->
                val dateDisplay = fullDateFormat.format(cell.date)
                Text(
                    text = if (cell.count > 0) {
                        stringResource(R.string.statistics_date_count_watched, dateDisplay, cell.count)
                    } else {
                        stringResource(R.string.statistics_date_no_data, dateDisplay)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cell.count > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } ?: Text(
                text = stringResource(R.string.statistics_heatmap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 图例
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.statistics_less),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            (0..4).forEach { level ->
                val color = heatmapColor(
                    count = when (level) {
                        0 -> 0
                        1 -> 1
                        2 -> 3
                        3 -> 6
                        else -> 10
                    },
                    primary = primary,
                    emptyColor = emptyColor
                )
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                        .then(
                            if (level == 0) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(3.dp)
                                )
                            } else Modifier
                        )
                )
                Spacer(modifier = Modifier.width(2.dp))
            }
            Text(
                text = stringResource(R.string.statistics_more),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 根据观看次数返回热力图颜色 */
private fun heatmapColor(count: Int, primary: Color, emptyColor: Color): Color {
    return when {
        count == 0 -> emptyColor
        count <= 2 -> primary.copy(alpha = 0.25f)
        count <= 5 -> primary.copy(alpha = 0.5f)
        count <= 9 -> primary.copy(alpha = 0.75f)
        else -> primary
    }
}

private enum class StatisticsSkeletonVariant {
    OVERVIEW,
    WATCH_TIME,
    HEATMAP,
    RATINGS,
    WORD_CLOUD,
    PIE,
    RANKING
}

/**
 * 分块加载时复用初始整页骨架的结构，避免从 A 样式切换成简单矩形 B 样式。
 *
 * 用 [rememberShimmer] + [Modifier.shimmer]：闪烁进度只在绘制阶段读取，
 * 骨架方块再多也不会跟着动画逐帧重组（热力图骨架有 91 个方块）。
 */
@Composable
private fun StatisticsSkeletonContent(
    variant: StatisticsSkeletonVariant,
    modifier: Modifier = Modifier
) {
    val shimmer = rememberShimmer()
    when (variant) {
        StatisticsSkeletonVariant.OVERVIEW -> {
            // 骨架跟随真实布局的 2 列 × 3 行，避免加载完成时格子数量与位置整体跳一下
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(3) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        repeat(2) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(104.dp)
                                    .shimmer(shimmer, RoundedCornerShape(16.dp))
                            )
                        }
                    }
                }
            }
        }

        StatisticsSkeletonVariant.WATCH_TIME -> {
            Column(modifier = modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(32.dp)
                        .shimmer(shimmer, RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(14.dp)
                        .shimmer(shimmer, RoundedCornerShape(4.dp))
                )
            }
        }

        StatisticsSkeletonVariant.HEATMAP -> {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                repeat(7) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(13) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .shimmer(shimmer, RoundedCornerShape(4.dp))
                            )
                        }
                    }
                }
            }
        }

        StatisticsSkeletonVariant.RATINGS -> {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(16.dp)
                        .shimmer(shimmer, RoundedCornerShape(4.dp))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.3f)
                        .height(14.dp)
                        .shimmer(shimmer, RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.height(4.dp))
                repeat(5) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(28.dp)
                                .height(10.dp)
                                .shimmer(shimmer, RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(10.dp)
                                .shimmer(shimmer, RoundedCornerShape(5.dp))
                        )
                    }
                }
            }
        }

        StatisticsSkeletonVariant.WORD_CLOUD -> {
            Column(
                modifier = modifier.fillMaxWidth().height(220.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(76.dp, 120.dp, 58.dp).forEach { width ->
                        Box(
                            modifier = Modifier
                                .width(width)
                                .height(24.dp)
                                .shimmer(shimmer, RoundedCornerShape(12.dp))
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(52.dp, 148.dp).forEach { width ->
                        Box(
                            modifier = Modifier
                                .width(width)
                                .height(34.dp)
                                .shimmer(shimmer, RoundedCornerShape(17.dp))
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(108.dp, 68.dp, 92.dp).forEach { width ->
                        Box(
                            modifier = Modifier
                                .width(width)
                                .height(18.dp)
                                .shimmer(shimmer, RoundedCornerShape(9.dp))
                        )
                    }
                }
            }
        }

        StatisticsSkeletonVariant.PIE -> {
            Column(
                modifier = modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .shimmer(shimmer, RoundedCornerShape(100.dp))
                )
                Spacer(modifier = Modifier.height(12.dp))
                repeat(5) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .shimmer(shimmer, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(14.dp)
                                .shimmer(shimmer, RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }

        StatisticsSkeletonVariant.RANKING -> {
            val widths = listOf(1f, 0.92f, 0.84f, 0.76f, 0.68f, 0.6f, 0.52f, 0.44f, 0.36f, 0.28f)
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                widths.forEach { fraction ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .shimmer(shimmer, RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(14.dp)
                                .shimmer(shimmer, RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }
    }
}

/** 观影时长卡片 */
@Composable
private fun WatchTimeCard(uiState: StatisticsUiState, reveal: SectionReveal) {
    val totalHours = uiState.totalWatchMinutes / 60
    val totalDays = totalHours / 24
    val animatedHours = rememberOneShotAnimatedInt(totalHours.toInt(), reveal)

    Column {
        Text(
            text = stringResource(R.string.statistics_watch_hours, animatedHours),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (totalDays > 0) {
            Text(
                text = stringResource(R.string.statistics_watch_days, totalDays.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 评分统计卡片 */
@Composable
private fun RatingStatsCard(uiState: StatisticsUiState, reveal: SectionReveal) {
    val maxCount = uiState.ratingDistribution.values.maxOrNull() ?: 1

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.statistics_ratings_count, uiState.totalRatings),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.statistics_avg_rating, uiState.averageRating),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 水平条形分布图（10分到1分）
        (10 downTo 1).forEach { rating ->
            key(rating) {
                val count = uiState.ratingDistribution[rating] ?: 0
                // 柱形增长动画：首次露出播一次
                val animatedFraction = rememberRevealAnimatedFloat(
                    targetValue = count.toFloat() / maxCount.toFloat(),
                    reveal = reveal,
                    durationMillis = 1200,
                    delayMillis = (10 - rating) * 80
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${rating}★",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(28.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedFraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(5.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(32.dp),
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
