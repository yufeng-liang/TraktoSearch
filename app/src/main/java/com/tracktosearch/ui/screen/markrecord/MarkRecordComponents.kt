package com.tracktosearch.ui.screen.markrecord

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
            .background(backgroundBrush)
            .then(if (isRemoved) Modifier.alpha(0.55f) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 海报 64×96dp
            val fullPosterUrl = buildFullPosterUrl(item.posterUrl)
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
                    contentDescription = item.displayTitle.ifBlank { item.title },
                    modifier = Modifier
                        .size(width = 64.dp, height = 96.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 64.dp, height = 96.dp)
                        .clip(RoundedCornerShape(8.dp))
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

            Spacer(Modifier.width(8.dp))

            // 信息列
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 标题（可多行）
                Text(
                    text = item.displayTitle.ifBlank { item.title }.ifBlank { stringResource(R.string.mark_records_empty_all) },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.Medium,
                    color = onColor,
                    lineHeight = 18.sp
                )
                // 年份（标题下一行）
                item.year?.let {
                    Text(
                        text = it.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = onColor.copy(alpha = 0.8f)
                    )
                }
                // 操作胶囊 + 相对时间（胶囊右侧）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionTypeChip(item.actionType)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = formatRelativeTime(item.actedAt, context),
                        style = MaterialTheme.typography.bodySmall,
                        color = onColor.copy(alpha = 0.85f)
                    )
                }
                // 当前状态（胶囊下一行）
                CurrentStatusBadge(item)
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
            .clip(RoundedCornerShape(999.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
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
    val (textRes, color) = when (status) {
        CurrentMarkStatus.IN_WATCHLIST -> R.string.mark_records_current_in_watchlist to Color(0xFF90CAF9)
        CurrentMarkStatus.WATCHED -> R.string.mark_records_current_watched to Color(0xFFA5D6A7)
        CurrentMarkStatus.NONE -> R.string.mark_records_current_none to Color(0xFFEF9A9A)
    }
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium
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

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        // 媒体类型
        Text(
            stringResource(R.string.mark_records_filter_media_type),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = "movie" in selectedMediaTypes,
                onClick = {
                    selectedMediaTypes = if ("movie" in selectedMediaTypes) selectedMediaTypes - "movie" else selectedMediaTypes + "movie"
                },
                label = { Text(stringResource(R.string.mark_records_media_movie)) }
            )
            FilterChip(
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
                FilterChip(
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
            Text(
                stringResource(R.string.mark_records_date_preset_custom),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        // 排序
        Text(
            stringResource(R.string.mark_records_filter_sort),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !selectedAscending,
                onClick = { selectedAscending = false },
                label = { Text(stringResource(R.string.mark_records_sort_desc)) }
            )
            FilterChip(
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
                onConfirm(selectedMediaTypes, selectedPreset, selectedRange, selectedAscending)
            }) {
                Text(stringResource(R.string.mark_records_confirm))
            }
        }
    }
}
