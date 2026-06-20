package com.tracktosearch.ui.screen.statistics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tracktosearch.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onBack: () -> Unit,
    viewModel: StatisticsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showInfoDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadStatistics()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.statistics_title))
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = { showInfoDialog = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Help,
                                contentDescription = stringResource(R.string.statistics_info),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.detail_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadStatistics() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.watchlist_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        if (uiState.error != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = uiState.error!!,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { viewModel.loadStatistics() }) {
                    Text(stringResource(R.string.watchlist_retry))
                }
            }
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 总览卡片（数字跳动动画）
                item(key = "overview") {
                    val isVisible by remember {
                        derivedStateOf {
                            listState.layoutInfo.visibleItemsInfo.any { it.key == "overview" }
                        }
                    }
                    OverviewCards(uiState, isVisible)
                }

                // 热力图
                item(key = "heatmap") {
                    val isVisible by remember {
                        derivedStateOf {
                            listState.layoutInfo.visibleItemsInfo.any { it.key == "heatmap" }
                        }
                    }
                    SectionCard(title = stringResource(R.string.statistics_heatmap)) {
                        HeatmapChart(heatmapData = uiState.heatmapData, isVisible = isVisible)
                    }
                }

                // 类型分布（饼图，展开动画）
                if (uiState.genreDistribution.isNotEmpty()) {
                    item(key = "pie") {
                        val isVisible by remember {
                            derivedStateOf {
                                listState.layoutInfo.visibleItemsInfo.any { it.key == "pie" }
                            }
                        }
                        SectionCard(title = stringResource(R.string.statistics_genre_distribution)) {
                            GenrePieChart(genreDistribution = uiState.genreDistribution, isVisible = isVisible)
                        }
                    }
                }

                // 最常看的类型排行（柱形增长动画 + emoji奖牌）
                if (uiState.genreDistribution.isNotEmpty()) {
                    item(key = "ranking") {
                        val isVisible by remember {
                            derivedStateOf {
                                listState.layoutInfo.visibleItemsInfo.any { it.key == "ranking" }
                            }
                        }
                        SectionCard(title = stringResource(R.string.statistics_genre_ranking)) {
                            GenreRanking(genreDistribution = uiState.genreDistribution, isVisible = isVisible)
                        }
                    }
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
                    text = stringResource(R.string.statistics_info_heatmap),
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

/** 总览卡片：数字跳动动画 */
@Composable
private fun OverviewCards(uiState: StatisticsUiState, isVisible: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AnimatedStatCard(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.statistics_total),
            targetValue = uiState.totalWatched,
            isVisible = isVisible
        )
        AnimatedStatCard(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.statistics_this_month),
            targetValue = uiState.thisMonthWatched,
            isVisible = isVisible
        )
        AnimatedStatCard(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.statistics_this_year),
            targetValue = uiState.thisYearWatched,
            isVisible = isVisible
        )
    }
}

/** 带数字跳动动画的统计卡片 */
@Composable
private fun AnimatedStatCard(
    title: String,
    targetValue: Int,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true
) {
    val animatedValue by animateIntAsState(
        targetValue = if (isVisible) targetValue else 0,
        animationSpec = tween(durationMillis = 1500, easing = LinearOutSlowInEasing),
        label = "statValue"
    )

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = animatedValue.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun localizedGenreName(genre: String): String = when (genre) {
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
    else -> genre.replaceFirstChar { it.uppercase() }
}

/** 类型分布饼图（展开动画 + 点击交互） */
@Composable
private fun GenrePieChart(genreDistribution: Map<String, Int>, isVisible: Boolean = true) {
    val totalCount = genreDistribution.values.sum()
    if (totalCount == 0) return

    val entries = genreDistribution.entries.sortedByDescending { it.value }.take(10)
    val colors = listOf(
        Color(0xFF6750A4), Color(0xFF625B71), Color(0xFF7D5260), Color(0xFF2196F3),
        Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFF9C27B0), Color(0xFF00BCD4),
        Color(0xFFFF5722), Color(0xFF607D8B)
    )

    // 饼图展开动画进度
    val sweepProgress = remember { Animatable(0f) }
    LaunchedEffect(entries, isVisible) {
        if (isVisible) {
            sweepProgress.snapTo(0f)
            sweepProgress.animateTo(1f, animationSpec = tween(durationMillis = 1500, easing = LinearOutSlowInEasing))
        }
    }

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
                                    val sweep = 360f * entry.value.toFloat() / totalCount.toFloat()
                                    if (angle >= accumulated && angle < accumulated + sweep) {
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
                    val fullSweep = 360f * entry.value.toFloat() / totalCount.toFloat()
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
                    startAngle += fullSweep
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
                    val percentage = String.format("%.1f%%", entry.value.toFloat() / totalCount.toFloat() * 100)
                    Text(
                        text = stringResource(R.string.statistics_item_count, localizedGenreName(entry.key), entry.value, percentage),
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
                            .clickable { selectedIndex = if (selectedIndex == index) -1 else index },
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
                            text = localizedGenreName(entry.key),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = entry.value.toString(),
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

/** 最常看的类型排行（柱形增长动画 + emoji奖牌） */
@Composable
private fun GenreRanking(genreDistribution: Map<String, Int>, isVisible: Boolean = true) {
    val maxCount = genreDistribution.values.maxOrNull() ?: 1
    val sortedGenres = genreDistribution.entries.sortedByDescending { it.value }
    val medalEmojis = listOf("🥇", "🥈", "🥉")

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sortedGenres.take(10).forEachIndexed { index, (genre, count) ->
            // 柱形增长动画
            val animatedFraction by animateFloatAsState(
                targetValue = if (isVisible) count.toFloat() / maxCount.toFloat() else 0f,
                animationSpec = tween(
                    durationMillis = 1200,
                    delayMillis = index * 120,
                    easing = LinearOutSlowInEasing
                ),
                label = "barGrow$index"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 排名标志：前三名用emoji奖牌，其余用数字
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (index < 3) medalEmojis[index] else "${index + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = if (index < 3) 16.sp else 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

/** 观看热力图（Canvas 统一绘制，点击格子查看详情，支持翻页查看历史） */
@Composable
private fun HeatmapChart(heatmapData: Map<String, Int>, isVisible: Boolean = true) {
    // 翻页偏移：0 = 最近13周，每次 -13 往前翻一页
    var weekOffset by remember { mutableStateOf(0) }

    val calendar = Calendar.getInstance()
    val today = calendar.time

    val todayCal = Calendar.getInstance()
    todayCal.time = today
    val dayOfWeek = todayCal.get(Calendar.DAY_OF_WEEK) // 1=Sunday
    val startCal = Calendar.getInstance()
    startCal.time = today
    startCal.add(Calendar.DAY_OF_YEAR, -((13 - 1) * 7 + (dayOfWeek - 1)) + weekOffset * 7)

    val endCal = (startCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 13 * 7 - 1) }

    val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val monthFormat = SimpleDateFormat("M月", Locale.CHINESE)
    val fullDateFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINESE)
    val rangeFormat = SimpleDateFormat("yyyy/M/d", Locale.US)
    val rangeStart = rangeFormat.format(startCal.time)
    val rangeEnd = rangeFormat.format(endCal.time)
    val canGoForward = weekOffset < 0

    // 构建 13 周 × 7 天网格
    data class HeatmapCell(val dateKey: String, val count: Int, val isFuture: Boolean, val date: java.util.Date)
    val weeks = mutableListOf<List<HeatmapCell>>()
    var currentCal = startCal.clone() as Calendar
    var lastMonth = -1
    val monthLabels = mutableListOf<Pair<Int, String>>()

    for (weekIndex in 0 until 13) {
        val week = mutableListOf<HeatmapCell>()
        for (dayIndex in 0 until 7) {
            val dateKey = dateKeyFormat.format(currentCal.time)
            val count = heatmapData[dateKey] ?: 0
            val isFuture = currentCal.time.after(today)
            week.add(HeatmapCell(if (isFuture) "" else dateKey, count, isFuture, currentCal.time))

            if (dayIndex == 0) {
                val month = currentCal.get(Calendar.MONTH)
                if (month != lastMonth) {
                    monthLabels.add(weekIndex to monthFormat.format(currentCal.time))
                    lastMonth = month
                }
            }
            currentCal.add(Calendar.DAY_OF_YEAR, 1)
        }
        weeks.add(week)
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

    val textMeasurer = rememberTextMeasurer()
    val weekdayStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val monthStyle = TextStyle(fontSize = 11.sp, color = labelColor, fontWeight = FontWeight.Medium)

    // 预测量星期文字高度，用于垂直居中
    val weekdayMeasure = remember { textMeasurer.measure("一", weekdayStyle) }
    val weekdayYOffset = (cellSizePx - weekdayMeasure.size.height) / 2f

    var selectedCell by remember { mutableStateOf<HeatmapCell?>(null) }

    // 淡入动画：初始可见，进入视野时播放一次
    val alphaAnim = remember { Animatable(1f) }
    var hasAnimated by remember { mutableStateOf(false) }
    LaunchedEffect(isVisible) {
        if (isVisible && !hasAnimated) {
            hasAnimated = true
            alphaAnim.snapTo(0f)
            alphaAnim.animateTo(1f, animationSpec = tween(900, easing = LinearOutSlowInEasing))
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 翻页导航
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { weekOffset -= 13 }, modifier = Modifier.size(32.dp)) {
                Text("←", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = "$rangeStart ~ $rangeEnd",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(
                onClick = { weekOffset += 13 },
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
                .pointerInput(weeks) {
                    detectTapGestures { offset ->
                        val x = offset.x - labelWidthPx
                        val y = offset.y - monthLabelHeightPx
                        if (x >= 0 && y >= 0) {
                            val weekIdx = (x / slotPx).toInt()
                            val dayIdx = (y / slotPx).toInt()
                            if (weekIdx in weeks.indices && dayIdx in 0 until 7) {
                                val cell = weeks[weekIdx][dayIdx]
                                if (!cell.isFuture) {
                                    selectedCell = cell
                                }
                            }
                        }
                    }
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

            // 2. 绘制星期标签（一二三四五六日）
            val weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
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
                                color = Color.Black,
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
