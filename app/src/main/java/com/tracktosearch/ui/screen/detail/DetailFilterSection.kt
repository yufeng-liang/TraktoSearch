package com.tracktosearch.ui.screen.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.ui.haptic.rememberAppHaptics

/** 网盘类型选项：常量集合，不必每次重组都 toList 一份新的（LazyRow 的 items 会跟着重新读一遍） */
private val DISK_TYPE_OPTIONS = ResourceRepository.ALL_DISK_TYPES.toList()

@Composable
internal fun FilterSection(
    availableSources: List<String>,
    enabledSources: Set<String>,
    customSourceNames: Map<String, String>,
    enabledDiskTypes: Set<DiskType>,
    onToggleSource: (String) -> Unit,
    onToggleDiskType: (DiskType) -> Unit,
    relevanceEnabled: Boolean = false,
    showHighRelevanceOnly: Boolean = false,
    onToggleShowHighRelevanceOnly: () -> Unit = {}
) {
    val haptics = rememberAppHaptics()
    // 固定左侧标签宽度，保证两个行的 Chip 起点对齐
    val labelWidth = 64.dp
    // 标签文字颜色跟随 LocalContentColor(由详情页根据 Tab 沉浸色自动设置)
    val labelColor = LocalContentColor.current

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        // 搜索源（横向滚动）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.detail_filter_sources),
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                modifier = Modifier.width(labelWidth)
            )
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = availableSources,
                    key = { it }
                ) { source ->
                    val label = when (source) {
                        "pansou" -> "PanSou"
                        "panhub" -> "PanHub"
                        "zreso" -> "Zreso"
                        else -> customSourceNames[source] ?: source
                    }
                    FilterChip(
                        selected = source in enabledSources,
                        border = if (source in enabledSources) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        onClick = { haptics.toggle(source !in enabledSources); onToggleSource(source) },
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
                color = labelColor,
                modifier = Modifier.width(labelWidth)
            )
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = DISK_TYPE_OPTIONS,
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
                        border = if (type in enabledDiskTypes) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        onClick = { haptics.toggle(type !in enabledDiskTypes); onToggleDiskType(type) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }

        // 相关度过滤：仅在存在目标影视上下文（relevanceEnabled）时显示
        if (relevanceEnabled) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.detail_filter_relevance),
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    modifier = Modifier.width(labelWidth)
                )
                FilterChip(
                    selected = showHighRelevanceOnly,
                    border = if (showHighRelevanceOnly) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    onClick = { haptics.toggle(!showHighRelevanceOnly); onToggleShowHighRelevanceOnly() },
                    label = { Text(stringResource(R.string.detail_filter_high_relevance), style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                    modifier = Modifier.height(28.dp)
                )
            }
        }
    }
}
