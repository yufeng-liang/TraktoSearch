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

/**
 * 资源筛选条：搜索源与网盘类型两组互不影响的多选 chip，外加一个高相关度开关。
 *
 * @param onToggleSource 切换搜索源。**返回是否真的切换了** —— 两个持有方（`DetailViewModel`
 *   与 `DoubanItemDetailScreen` 的内联 VM）都有「至少保留 1 个」的守卫，被守卫挡下时
 *   什么都没变。返回值让触感能分辨这两种结果，同时把那条业务规则留在 VM 里 ——
 *   界面在这里预判 `enabledSources.size == 1` 等于把规则抄一份过来。
 * @param onToggleDiskType 切换网盘类型，同一条守卫、同一个约定。
 */
@Composable
internal fun FilterSection(
    availableSources: List<String>,
    enabledSources: Set<String>,
    customSourceNames: Map<String, String>,
    enabledDiskTypes: Set<DiskType>,
    onToggleSource: (String) -> Boolean,
    onToggleDiskType: (DiskType) -> Boolean,
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
                        onClick = {
                            // 触感看 VM 的回执，不看按下时的意图：被「至少保留 1 个」挡下时
                            // 屏幕上什么都没变，发 TOGGLE_OFF 就是在说一件没发生的事
                            val willSelect = source !in enabledSources
                            if (onToggleSource(source)) haptics.toggle(willSelect) else haptics.reject()
                        },
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
                        onClick = {
                            // 与上面那组搜索源同一条守卫、同一个判据
                            val willSelect = type !in enabledDiskTypes
                            if (onToggleDiskType(type)) haptics.toggle(willSelect) else haptics.reject()
                        },
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
