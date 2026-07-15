package com.tracktosearch.ui.screen.markrecord

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tracktosearch.R
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
    onClick: () -> Unit
) {
    val isChanged = isRecordChanged(item)
    val rowAlpha = if (isChanged) 0.6f else 1f
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(rowAlpha)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 小海报 48×72dp
        AsyncImage(
            model = item.posterUrl,
            contentDescription = item.title,
            modifier = Modifier
                .size(width = 48.dp, height = 72.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(12.dp))
        // 信息区
        Column(modifier = Modifier.weight(1f)) {
            // 标题 + 年份
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.displayTitle.ifBlank { item.title }.ifBlank { stringResource(R.string.mark_records_empty_all) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                item.year?.let {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "($it)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // episodeInfo
            item.episodeInfo?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.size(4.dp))
            // 操作类型 chip + 相对时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionTypeChip(item.actionType)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatRelativeTime(item.actedAt, context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.size(4.dp))
            // 当前状态徽标
            CurrentStatusBadge(item)
        }
    }
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
    val (textRes, color) = when (actionType) {
        "ADD_WATCHLIST" -> R.string.mark_records_action_add_watchlist to Color(0xFF2196F3)
        "REMOVE_WATCHLIST" -> R.string.mark_records_action_remove_watchlist to Color(0xFFF44336)
        "UNMARK_WATCHED" -> R.string.mark_records_action_unmark_watched to Color(0xFFFF9800)
        "WATCHED" -> R.string.mark_records_action_watched to Color(0xFF4CAF50)
        else -> R.string.mark_records_action_watched to Color.Gray
    }
    AssistChip(
        onClick = {},
        label = { Text(stringResource(textRes), fontSize = 11.sp) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = color.copy(alpha = 0.15f),
            labelColor = color
        )
    )
}

@Composable
private fun CurrentStatusBadge(item: MarkRecordItem) {
    val status = item.currentStatus ?: return
    val (textRes, color) = when (status) {
        CurrentMarkStatus.IN_WATCHLIST -> R.string.mark_records_current_in_watchlist to Color(0xFF4CAF50)
        CurrentMarkStatus.WATCHED -> R.string.mark_records_current_watched to Color(0xFF4CAF50)
        CurrentMarkStatus.NONE -> {
            if (isRecordChanged(item)) R.string.mark_records_current_changed to Color.Gray
            else R.string.mark_records_current_none to Color.Gray
        }
    }
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontSize = 11.sp
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
