package com.tracktosearch.ui.screen.markrecord

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    // 移除分类（已无标记）的卡片：保留海报沉浸色,叠加暗化遮罩并降透明度
    val isRemoved = item.currentStatus == CurrentMarkStatus.NONE
    var dominantColor by remember { mutableStateOf<Color?>(null) }

    // 根据主色亮度自适应文字颜色（深色主色用白字）
    val onColor = dominantColor?.let { c ->
        if (c.luminance() > 0.5f) Color.Black.copy(alpha = 0.92f) else Color.White
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
            .clickable(onClick = onClick)
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

        // 移除分类：叠暗色遮罩
        if (isRemoved) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )
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
 */
@Composable
private fun MarkRecordFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
    var selectedRange by remember { mutableStateOf(dateRange) }
    var selectedAscending by remember { mutableStateOf(ascending) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
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
                label = { Text(stringResource(R.string.mark_records_media_movie)) }
            )
            MarkRecordFilterChip(
                selected = "show" in selectedMediaTypes,
                onClick = {
                    selectedMediaTypes = if ("show" in selectedMediaTypes) selectedMediaTypes - "show" else selectedMediaTypes + "show"
                },
                label = { Text(stringResource(R.string.mark_records_media_show)) }
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
                val start = selectedRange?.first
                OutlinedButton(
                    onClick = { showStartDatePicker = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = start?.let { dateFormatter.format(Date(it)) }
                            ?: stringResource(R.string.mark_records_filter_start_date)
                    )
                }
                val end = selectedRange?.second
                OutlinedButton(
                    onClick = { showEndDatePicker = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = end?.let { dateFormatter.format(Date(it)) }
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
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.mark_records_reset))
            }
            Button(onClick = {
                val range = if (selectedPreset == DatePreset.CUSTOM) selectedRange else null
                onConfirm(selectedMediaTypes, selectedPreset, range, selectedAscending)
            }) {
                Text(stringResource(R.string.mark_records_confirm))
            }
        }
    }

    if (showStartDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedRange?.first
        )
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            selectedRange = selectedRange?.copy(first = millis)
                                ?: (millis to (selectedRange?.second ?: millis))
                        }
                        showStartDatePicker = false
                    }
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        ) { DatePicker(state = pickerState) }
    }

    if (showEndDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedRange?.second
        )
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            selectedRange = selectedRange?.copy(second = millis)
                                ?: ((selectedRange?.first ?: millis) to millis)
                        }
                        showEndDatePicker = false
                    }
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        ) { DatePicker(state = pickerState) }
    }
}
