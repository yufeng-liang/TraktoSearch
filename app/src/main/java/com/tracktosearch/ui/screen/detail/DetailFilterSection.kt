package com.tracktosearch.ui.screen.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.ui.component.FilterDropdownBar

/**
 * 资源筛选条：搜索源 / 网盘类型 / 高相关 三个胶囊。
 *
 * @param onToggleSource 切换搜索源。两个持有方（`DetailViewModel` 与
 *   `DoubanItemDetailScreen` 的内联 VM）都有「至少保留 1 个」的守卫，被守卫挡下时
 *   状态不会变化。
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
    onToggleShowHighRelevanceOnly: () -> Unit = {},
    sourceCounts: Map<String, Int> = emptyMap(),
    diskTypeCounts: Map<DiskType, Int> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    FilterDropdownBar(
        availableSources = availableSources,
        enabledSources = enabledSources,
        customSourceNames = customSourceNames,
        enabledDiskTypes = enabledDiskTypes,
        onToggleSource = { onToggleSource(it) },
        onToggleDiskType = { onToggleDiskType(it) },
        modifier = modifier,
        relevanceEnabled = relevanceEnabled,
        showHighRelevanceOnly = showHighRelevanceOnly,
        onToggleShowHighRelevanceOnly = onToggleShowHighRelevanceOnly,
        sourceCounts = sourceCounts,
        diskTypeCounts = diskTypeCounts,
    )
}
