package com.tracktosearch.ui.screen.markrecord

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.drawable.toBitmap
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.util.PosterColorExtractor
import com.tracktosearch.ui.component.AppDialogActionRow
import com.tracktosearch.ui.component.DialogAction
import com.tracktosearch.ui.component.SharedOrigin
import com.tracktosearch.ui.component.rememberShimmer
import com.tracktosearch.ui.component.shimmer
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.theme.WcagBlackWhiteCrossover
import com.tracktosearch.ui.theme.floatingDialogColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 标记记录页的 origin 基名。
 *
 * [SharedOrigin] 只收跨页面配对用到的公共值，某个屏幕私有的细分值就近声明在该屏幕文件里。
 */
private const val MARK_RECORD_ORIGIN_BASE = "mark-record"

/**
 * 某一条标记记录的 origin。
 *
 * 同一部片子可以被反复标记（标了看过又取消，之后再标一次），列表里因此允许出现同 tmdbId 的
 * 多行，只靠 tmdbId 分不出点的是哪一行。槽位取与网格 item key 相同的三段组合，
 * [MarkRecordScreen] 的点击回调也用本函数写入 [com.tracktosearch.ui.navigation.DetailSeedStore]，
 * 保持来源记录一致。
 */
internal fun markRecordOrigin(item: MarkRecordItem): String =
    SharedOrigin.of(MARK_RECORD_ORIGIN_BASE, "${item.traktId}_${item.actedAt}_${item.actionType}")

/**
 * 标记记录列表项骨架屏。
 *
 * 尺寸与 [MarkRecordItemRow] 一致（卡片圆角 16dp、海报 80×120dp、右侧信息列），
 * 让真实数据到达时布局不跳动；替代原来居中的单个转圈指示器。
 */
@Composable
fun MarkRecordItemSkeleton(modifier: Modifier = Modifier) {
    // 绘制期失效的 shimmer：动画每帧只重画骨架块，不逐帧重组
    val shimmerState = rememberShimmer()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(120.dp)
                    .shimmer(shimmerState, RoundedCornerShape(8.dp))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(120.dp)
                    .padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(15.dp)
                        .shimmer(shimmerState, RoundedCornerShape(4.dp))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(11.dp)
                        .shimmer(shimmerState, RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(11.dp)
                        .shimmer(shimmerState, RoundedCornerShape(4.dp))
                )
            }
        }
    }
}

/**
 * 标记记录列表项。
 * @param item 记录数据
 * @param onClick 点击跳转详情页
 */
@Composable
fun MarkRecordItemRow(
    item: MarkRecordItem,
    posterColorExtractor: PosterColorExtractor,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 移除分类（已无标记）的卡片：通过外层透明度让整张卡片均匀变暗
    val isRemoved = item.currentStatus == CurrentMarkStatus.NONE
    var dominantColor by remember { mutableStateOf<Color?>(null) }

    // 压在海报主色上的文字颜色。阈值用 WCAG 的黑白等对比点（约 0.179）而不是 0.5 ——
    // 0.5 会让中等明度的暖色拿到白字，对比度掉到 3:1 以下。
    val onColor = dominantColor?.let { c ->
        if (c.luminance() > WcagBlackWhiteCrossover) Color.Black.copy(alpha = 0.92f) else Color.White
    } ?: Color.White

    // 卡片沉浸渐变：主色 1.0 → 主色 0.7 alpha
    val backgroundBrush: Brush = dominantColor?.let { color ->
        Brush.horizontalGradient(colors = listOf(color, color.copy(alpha = 0.7f)))
    } ?: Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(if (isRemoved) Modifier.alpha(0.55f) else Modifier)
            .background(backgroundBrush)
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) {
                onClick()
            }
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.Top
        ) {
            // 海报固定 80×120dp（2:3 比例），确保信息列底部行能对齐海报底部
            val posterWidth = 80.dp
            val posterHeight = 120.dp
            val fullPosterUrl = buildFullPosterUrl(item.posterUrl)
            Box(
                modifier = Modifier
                    .width(posterWidth)
                    .height(posterHeight)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                if (fullPosterUrl != null) {
                    AsyncImage(
                        model = remember(fullPosterUrl) {
                            ImageRequest.Builder(context)
                                .data(fullPosterUrl)
                                .size(150)
                                .crossfade(false)
                                .listener(
                                    onSuccess = { _, result ->
                                        scope.launch {
                                            val bitmap = result.drawable.toBitmap()
                                            val argb = posterColorExtractor.extractDominantColor(fullPosterUrl, bitmap)
                                            if (argb != 0L) dominantColor = Color(argb)
                                        }
                                    }
                                )
                                .build()
                        },
                        contentDescription = item.displayTitle.ifBlank { item.title }
                            .ifBlank { stringResource(R.string.mark_records_unknown_title) },
                        modifier = Modifier.fillMaxSize(),
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
                            "?",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.width(6.dp))

            // 信息列：fillMaxHeight 拉满 Row 高度（= 海报 120dp）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // 顶部内容区
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                ) {
                    // 标题
                    Text(
                        text = item.displayTitle.ifBlank { item.title }
                            .ifBlank { stringResource(R.string.mark_records_unknown_title) },
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        fontWeight = FontWeight.Medium,
                        color = onColor,
                        lineHeight = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    // 年份 + 当前状态徽标（仅状态变更时显示），徽标右对齐
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item.year?.let { year ->
                            Text(
                                text = year.toString(),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                color = onColor.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                        }
                        if (isRecordChanged(item)) {
                            Spacer(Modifier.weight(1f))
                            CurrentStatusBadge(item)
                        }
                    }
                }

                // 底部内容区：相对时间 + 操作胶囊，整体下对齐卡片底部
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                ) {
                    // 相对时间
                    Text(
                        text = formatRelativeTime(item.actedAt, context),
                        style = MaterialTheme.typography.bodySmall,
                        color = onColor.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    // 操作胶囊（左对齐）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        ActionTypeChip(item.actionType)
                    }
                }
            }
        }

    }
}

/**
 * 将海报路径拼接为完整 URL。
 * - null 或空字符串 → 返回 null（不加载图片）
 * - 已是完整 http(s) URL → 原样返回
 * - 相对路径（如 "/abc.jpg"）→ 拼接 TMDB 基础 URL
 *
 * 提取为纯函数便于单元测试覆盖兜底拼接逻辑。
 */
fun buildFullPosterUrl(path: String?): String? {
    if (path.isNullOrBlank()) return null
    return if (path.startsWith("http")) path else TmdbImageUrls.build(path)
}

/** 判断记录的操作类型与当前状态是否矛盾 */
fun isRecordChanged(item: MarkRecordItem): Boolean {
    val status = item.currentStatus ?: return false
    return when (item.actionType) {
        "ADD_WATCHLIST" -> status != CurrentMarkStatus.IN_WATCHLIST
        "REMOVE_WATCHLIST" -> status == CurrentMarkStatus.IN_WATCHLIST
        "UNMARK_WATCHED" -> status == CurrentMarkStatus.WATCHED
        "WATCHED" -> status != CurrentMarkStatus.WATCHED
        else -> false
    }
}

@Composable
private fun ActionTypeChip(actionType: String) {
    val (textRes, bgColor) = when (actionType) {
        "ADD_WATCHLIST" -> R.string.mark_records_pill_watchlist to Color(0xFF2196F3)
        "REMOVE_WATCHLIST", "UNMARK_WATCHED" -> R.string.mark_records_pill_remove to Color(0xFFF44336)
        "WATCHED" -> R.string.mark_records_pill_watched to Color(0xFF4CAF50)
        else -> R.string.mark_records_pill_watched to Color(0xFF4CAF50)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 5.dp, vertical = 3.dp)
    ) {
        Text(
            text = stringResource(textRes),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1
        )
    }
}

@Composable
private fun CurrentStatusBadge(item: MarkRecordItem) {
    val status = item.currentStatus ?: return
    val (textRes, accentColor) = when (status) {
        CurrentMarkStatus.IN_WATCHLIST -> R.string.mark_records_current_in_watchlist_short to Color(0xFF42A5F5)
        CurrentMarkStatus.WATCHED -> R.string.mark_records_current_watched_short to Color(0xFF66BB6A)
        CurrentMarkStatus.NONE -> R.string.mark_records_current_none_short to Color(0xFFEF5350)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(accentColor, CircleShape)
        )
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp)
        )
    }
}

/**
 * 与 Watchlist 筛选弹窗保持一致的 FilterChip。
 *
 * 触感在这里统一发：调用点一个个加会漏（原来整个弹窗都没有，而列表页其他 chip 都有）。
 * 发哪一记看 [multiSelect]，判据是互斥性而不是叫不叫「筛选」。
 *
 * @param multiSelect 这个 chip 是否有自己独立的二值选中态（一组里能同时亮好几个）。
 *   true 时本质是穿了 chip 外衣的 Checkbox，「加上」与「去掉」的方向感有意义，按新状态发 toggle；
 *   false（默认）是一组里只能选一个的单选 / 分段控件，没有「关掉」这回事，只是把选中位挪一格，发 segmentTick。
 */
@Composable
private fun MarkRecordFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    multiSelect: Boolean = false,
) {
    val haptics = rememberAppHaptics()
    FilterChip(
        selected = selected,
        onClick = {
            if (multiSelect) haptics.toggle(!selected) else haptics.segmentTick()
            onClick()
        },
        label = label,
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = FilterChipDefaults.filterChipBorder(
            selected = selected,
            enabled = true,
            borderColor = MaterialTheme.colorScheme.outline,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/** 格式化时间戳为相对时间字符串 */
private fun formatRelativeTime(timestampMs: Long, context: Context): String {
    if (timestampMs <= 0L) return ""
    val diff = System.currentTimeMillis() - timestampMs
    val minutes = diff / (60 * 1000L)
    val hours = diff / (60 * 60 * 1000L)
    val days = diff / (24 * 60 * 60 * 1000L)
    return when {
        minutes < 1 -> context.getString(R.string.mark_records_time_just_now)
        minutes < 60 -> context.getString(R.string.mark_records_time_minutes_ago, minutes.toInt())
        hours < 24 -> context.getString(R.string.mark_records_time_hours_ago, hours.toInt())
        days < 30 -> context.getString(R.string.mark_records_time_days_ago, days.toInt())
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestampMs))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheetContent(
    mediaTypes: Set<String>,
    datePreset: DatePreset,
    dateRange: Pair<Long, Long>?,
    ascending: Boolean,
    onConfirm: (Set<String>, DatePreset, Pair<Long, Long>?, Boolean) -> Unit,
    onReset: () -> Unit
) {
    var selectedMediaTypes by remember { mutableStateOf(mediaTypes) }
    var selectedPreset by remember { mutableStateOf(datePreset) }
    // 起止分开存：Pair 逼着两端同时有值，只挑了开始日期时会被当成「就这一天」，
    // 而用户的意思通常是「这天以后」。0 表示这一端不限，DAO 的查询按 0 放行。
    var selectedStart by remember { mutableStateOf(dateRange?.first?.takeIf { it > 0L }) }
    var selectedEnd by remember { mutableStateOf(dateRange?.second?.takeIf { it > 0L }) }
    var selectedAscending by remember { mutableStateOf(ascending) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    // 本内容整块在 AppBottomSheet 里，sheet 有自己的宿主 View，在这一层取
    val haptics = rememberAppHaptics()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
            // 字体放大 / 小屏时内容会超过 80% 屏高，不能滚的话「确定」按钮点不到
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 媒体类型
        Text(
            stringResource(R.string.mark_records_filter_media_type),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkRecordFilterChip(
                selected = "movie" in selectedMediaTypes,
                onClick = {
                    selectedMediaTypes = if ("movie" in selectedMediaTypes) selectedMediaTypes - "movie" else selectedMediaTypes + "movie"
                },
                label = { Text(stringResource(R.string.mark_records_media_movie)) },
                // 媒体类型可以同时选上电影和剧集，两个 chip 各有独立选中态
                multiSelect = true
            )
            MarkRecordFilterChip(
                selected = "show" in selectedMediaTypes,
                onClick = {
                    selectedMediaTypes = if ("show" in selectedMediaTypes) selectedMediaTypes - "show" else selectedMediaTypes + "show"
                },
                label = { Text(stringResource(R.string.mark_records_media_show)) },
                multiSelect = true
            )
        }
        Spacer(Modifier.height(16.dp))
        // 日期范围
        Text(
            stringResource(R.string.mark_records_filter_date_range),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DatePreset.entries.forEach { preset ->
                MarkRecordFilterChip(
                    selected = selectedPreset == preset,
                    onClick = { selectedPreset = preset },
                    label = { Text(stringResource(when (preset) {
                        DatePreset.SEVEN_DAYS -> R.string.mark_records_date_preset_7d
                        DatePreset.THIRTY_DAYS -> R.string.mark_records_date_preset_30d
                        DatePreset.CUSTOM -> R.string.mark_records_date_preset_custom
                        DatePreset.ALL -> R.string.mark_records_date_preset_all
                    })) }
                )
            }
        }
        if (selectedPreset == DatePreset.CUSTOM) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        showStartDatePicker = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = selectedStart?.let { dateFormatter.format(Date(it)) }
                            ?: stringResource(R.string.mark_records_filter_start_date)
                    )
                }
                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        showEndDatePicker = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = selectedEnd?.let { dateFormatter.format(Date(it)) }
                            ?: stringResource(R.string.mark_records_filter_end_date)
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        // 排序
        Text(
            stringResource(R.string.mark_records_filter_sort),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkRecordFilterChip(
                selected = !selectedAscending,
                onClick = { selectedAscending = false },
                label = { Text(stringResource(R.string.mark_records_sort_desc)) }
            )
            MarkRecordFilterChip(
                selected = selectedAscending,
                onClick = { selectedAscending = true },
                label = { Text(stringResource(R.string.mark_records_sort_asc)) }
            )
        }
        Spacer(Modifier.height(24.dp))
        // 底部按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = {
                haptics.tap()
                onReset()
            }) {
                Text(stringResource(R.string.mark_records_reset))
            }
            Button(onClick = {
                haptics.tap()
                val custom = selectedPreset == DatePreset.CUSTOM
                // 两端都挑了却挑反了就换回来，省得筛出空列表让用户以为没数据
                val start = selectedStart
                val end = selectedEnd
                val ordered = if (start != null && end != null && start > end) end to start else start to end
                val range = (ordered.first ?: 0L) to (ordered.second ?: 0L)
                // 选了自定义又没挑日期 = 没有时间条件：落回「全部」，
                // 否则顶栏筛选按钮会亮着但其实什么都没筛
                val preset = if (custom && range.first == 0L && range.second == 0L) {
                    DatePreset.ALL
                } else {
                    selectedPreset
                }
                onConfirm(
                    selectedMediaTypes,
                    preset,
                    if (preset == DatePreset.CUSTOM) range else null,
                    selectedAscending
                )
            }) {
                Text(stringResource(R.string.mark_records_confirm))
            }
        }
    }

    if (showStartDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedStart
        )
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            colors = DatePickerDefaults.colors(containerColor = floatingDialogColor()),
            confirmButton = {
                // 按钮行收口到统一规格，主按钮 tap / 次按钮 lightTap 的触感由 AppDialogActionRow 负责
                AppDialogActionRow(
                    primary = DialogAction(
                        label = stringResource(android.R.string.ok),
                        onClick = {
                            pickerState.selectedDateMillis?.let { millis -> selectedStart = millis }
                            showStartDatePicker = false
                        },
                    ),
                    secondary = listOf(
                        DialogAction(
                            label = stringResource(android.R.string.cancel),
                            onClick = { showStartDatePicker = false },
                        ),
                    ),
                )
            },
        ) { DatePicker(state = pickerState) }
    }

    if (showEndDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedEnd
        )
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            colors = DatePickerDefaults.colors(containerColor = floatingDialogColor()),
            confirmButton = {
                AppDialogActionRow(
                    primary = DialogAction(
                        label = stringResource(android.R.string.ok),
                        onClick = {
                            pickerState.selectedDateMillis?.let { millis -> selectedEnd = millis }
                            showEndDatePicker = false
                        },
                    ),
                    secondary = listOf(
                        DialogAction(
                            label = stringResource(android.R.string.cancel),
                            onClick = { showEndDatePicker = false },
                        ),
                    ),
                )
            },
        ) { DatePicker(state = pickerState) }
    }
}
