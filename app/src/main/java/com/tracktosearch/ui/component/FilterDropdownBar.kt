package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.theme.StatusReleased
import com.tracktosearch.ui.theme.readableOn

/** 当前打开的筛选弹层；null 表示没有弹层。 */
private enum class FilterSheetType { SOURCES, DISK_TYPES }

/**
 * 资源筛选吸顶行：搜索源 / 网盘类型 / 高相关 三个胶囊。
 *
 * 胶囊只负责打开 [AppBottomSheet] 多选面板，真正的筛选状态仍由调用方持有：
 * - 搜索源与网盘类型通过 [onToggleSource] / [onToggleDiskType] 逐项提交；
 * - 高相关通过 [onToggleShowHighRelevanceOnly] 直接切换。
 *
 * 全不选会被规范化为「全选」，避免出现空筛选把结果清空的死路；
 * PanHub 等不在 [availableSources] 里的源会在面板中灰显、不可勾选。
 */
@Composable
fun FilterDropdownBar(
    availableSources: List<String>,
    enabledSources: Set<String>,
    customSourceNames: Map<String, String>,
    enabledDiskTypes: Set<DiskType>,
    onToggleSource: (String) -> Unit,
    onToggleDiskType: (DiskType) -> Unit,
    onEnableSource: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    relevanceEnabled: Boolean = false,
    showHighRelevanceOnly: Boolean = false,
    onToggleShowHighRelevanceOnly: () -> Unit = {},
    sourceCounts: Map<String, Int> = emptyMap(),
    diskTypeCounts: Map<DiskType, Int> = emptyMap(),
    searching: Boolean = false,
    completedSources: Int = 0,
    totalSources: Int = 0,
) {
    val haptics = rememberAppHaptics()

    // 选项全集：内置源全量列出（不可用的灰显），再加自定义源；顺序以 ALL_SOURCES 为准。
    val allSourceOptions = remember(availableSources, customSourceNames) {
        buildList {
            addAll(ResourceRepository.ALL_SOURCES)
            addAll(customSourceNames.keys)
            addAll(availableSources)
        }.distinct()
    }
    val availableSourceSet = remember(availableSources) { availableSources.toSet() }
    val allDiskTypeOptions = remember { ResourceRepository.ALL_DISK_TYPES.toList() }

    var activeSheet by remember { mutableStateOf<FilterSheetType?>(null) }
    var draftSources by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draftDiskTypes by remember { mutableStateOf<Set<DiskType>>(emptySet()) }

    // 每次打开面板都用当前筛选状态初始化草稿，避免上次取消留下的中间态影响本次。
    fun openSourcesSheet() {
        draftSources = enabledSources.filter { it in availableSourceSet }.toSet()
        if (draftSources.isEmpty()) draftSources = availableSourceSet
        activeSheet = FilterSheetType.SOURCES
    }

    fun openDiskTypesSheet() {
        draftDiskTypes = enabledDiskTypes
        if (draftDiskTypes.isEmpty()) draftDiskTypes = allDiskTypeOptions.toSet()
        activeSheet = FilterSheetType.DISK_TYPES
    }

    fun commitSources() {
        // 面板里允许临时清空，真正提交时兜底恢复全部可用源。
        val target = if (draftSources.isEmpty()) availableSourceSet else draftSources
        val current = enabledSources.filter { it in availableSourceSet }.toSet()
        // 先选后取消：详情页 VM 有「至少保留 1 个」守卫，先取消可能在只剩 1 个时被拦下。
        (target - current).forEach(onToggleSource)
        (current - target).forEach(onToggleSource)
    }

    fun commitDiskTypes() {
        val target = if (draftDiskTypes.isEmpty()) allDiskTypeOptions.toSet() else draftDiskTypes
        val current = enabledDiskTypes
        (target - current).forEach(onToggleDiskType)
        (current - target).forEach(onToggleDiskType)
    }

    val selectedSourceCount = enabledSources.count { it in availableSourceSet }
    val allDiskTypesSelected = enabledDiskTypes.containsAll(allDiskTypeOptions)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterPill(
            text = stringResource(R.string.search_filter_sources) + " · " + selectedSourceCount,
            selected = selectedSourceCount < availableSourceSet.size,
            onClick = {
                haptics.popupShow()
                openSourcesSheet()
            }
        )
        FilterPill(
            text = stringResource(R.string.search_filter_disk) + " · " +
                if (allDiskTypesSelected) {
                    stringResource(R.string.search_filter_all)
                } else {
                    enabledDiskTypes.size.toString()
                },
            selected = !allDiskTypesSelected,
            onClick = {
                haptics.popupShow()
                openDiskTypesSheet()
            }
        )
        if (relevanceEnabled) {
            FilterPill(
                text = stringResource(R.string.search_filter_high_relevance),
                selected = showHighRelevanceOnly,
                accentColor = StatusReleased,
                onClick = {
                    haptics.toggle(!showHighRelevanceOnly)
                    onToggleShowHighRelevanceOnly()
                }
            )
        }
        if (searching) {
            // 进度放在胶囊右侧剩余区域的水平中心，而不是紧贴最后一个胶囊
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(
                            R.string.search_loading_disk_progress,
                            completedSources,
                            totalSources,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    when (activeSheet) {
        FilterSheetType.SOURCES -> {
            AppBottomSheet(
                onDismissRequest = {
                    // 下滑/点遮罩/点关闭只关闭面板，不提交草稿；只有「完成」才提交。
                    activeSheet = null
                },
                title = stringResource(R.string.search_filter_sources),
                // 全展开，避免半屏高度把面板内容截断
                skipPartiallyExpanded = true,
            ) {
                FilterMultiSelectContent(
                    selectedCount = draftSources.size,
                    totalCount = availableSourceSet.size,
                    doneEnabled = draftSources.isNotEmpty(),
                    onSelectAll = {
                        haptics.tap()
                        draftSources = availableSourceSet
                    },
                    onClear = {
                        haptics.lightTap()
                        draftSources = emptySet()
                    },
                    onDone = {
                        commitSources()
                        activeSheet = null
                    },
                ) {
                    FilterCheckboxGrid(items = allSourceOptions) { source ->
                        val available = source in availableSourceSet
                        FilterCheckboxRow(
                            label = sourceLabel(source, customSourceNames),
                            count = if (!available || sourceCounts.isEmpty()) null else sourceCounts[source] ?: 0,
                            checked = source in draftSources,
                            enabled = available,
                            onCheckedChange = { checked ->
                                haptics.toggle(checked)
                                draftSources = if (checked) {
                                    draftSources + source
                                } else {
                                    draftSources - source
                                }
                            },
                            onEnable = if (!available && !searching) {
                                {
                                    haptics.lightTap()
                                    onEnableSource(source)
                                    activeSheet = null
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        FilterSheetType.DISK_TYPES -> {
            AppBottomSheet(
                onDismissRequest = {
                    // 下滑/点遮罩/点关闭只关闭面板，不提交草稿；只有「完成」才提交。
                    activeSheet = null
                },
                title = stringResource(R.string.search_filter_disk_types),
                skipPartiallyExpanded = true,
            ) {
                FilterMultiSelectContent(
                    selectedCount = draftDiskTypes.size,
                    totalCount = allDiskTypeOptions.size,
                    doneEnabled = draftDiskTypes.isNotEmpty(),
                    onSelectAll = {
                        haptics.tap()
                        draftDiskTypes = allDiskTypeOptions.toSet()
                    },
                    onClear = {
                        haptics.lightTap()
                        draftDiskTypes = emptySet()
                    },
                    onDone = {
                        commitDiskTypes()
                        activeSheet = null
                    },
                ) {
                    FilterCheckboxGrid(items = allDiskTypeOptions) { type ->
                        FilterCheckboxRow(
                            label = diskTypeLabel(type),
                            count = if (diskTypeCounts.isEmpty()) null else diskTypeCounts[type] ?: 0,
                            checked = type in draftDiskTypes,
                            enabled = true,
                            onCheckedChange = { checked ->
                                haptics.toggle(checked)
                                draftDiskTypes = if (checked) {
                                    draftDiskTypes + type
                                } else {
                                    draftDiskTypes - type
                                }
                            },
                        )
                    }
                }
            }
        }

        null -> Unit
    }
}

/** 胶囊：视觉高 32dp，外层 48dp 最小触控区；开启态可传 accent 色。 */
@Composable
private fun FilterPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    accentColor: Color? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val containerColor = when {
        !selected -> MaterialTheme.colorScheme.surfaceVariant
        accentColor != null -> accentColor.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    }
    val borderColor = when {
        !selected -> MaterialTheme.colorScheme.outlineVariant
        accentColor != null -> accentColor.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    }
    val textColor = when {
        !selected -> MaterialTheme.colorScheme.onSurfaceVariant
        accentColor != null -> readableOn(accentColor, MaterialTheme.colorScheme.surfaceVariant)
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .semantics { this.selected = selected }
            .clip(shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .clip(shape)
                .background(containerColor)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 弹层通用头部：已选计数 + 全选/清空，下面由调用方提供列表。 */
@Composable
private fun FilterMultiSelectContent(
    selectedCount: Int,
    totalCount: Int,
    doneEnabled: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    R.string.search_filter_selected_count,
                    selectedCount,
                    totalCount,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.search_filter_clear))
            }
            TextButton(onClick = onSelectAll) {
                Text(stringResource(R.string.search_filter_select_all))
            }
        }
        content()
        Button(
            onClick = onDone,
            enabled = doneEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
        ) {
            Text(stringResource(R.string.search_filter_done))
        }
    }
}

/** 两列等宽复选网格：每格 weight(1f)，保证左右两列复选框起点一致。 */
@Composable
private fun <T> FilterCheckboxGrid(
    items: List<T>,
    itemContent: @Composable (T) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        items.chunked(2).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { item ->
                    Box(modifier = Modifier.weight(1f)) {
                        itemContent(item)
                    }
                }
                // 奇数项补一个空单元格，避免最后一行被拉伸成整行
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 复选框行：左侧标签，右侧结果计数；不可用的源灰显且不可点击。 */
@Composable
private fun FilterCheckboxRow(
    label: String,
    count: Int?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onEnable: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 固定 48dp 前导区：禁用态 Checkbox 不带最小触控尺寸，不包裹会导致灰显行的复选框整体左移
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = if (enabled) onCheckedChange else null,
                enabled = enabled,
                modifier = Modifier
                    .size(20.dp)
                    .alpha(if (enabled) 1f else 0.38f),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 普通行 fill=false，让计数紧跟文字；带「启用」按钮的禁用行 fill=true，把按钮推到最右侧
            modifier = Modifier
                .weight(1f, fill = onEnable == null)
                .alpha(if (enabled) 1f else 0.38f),
        )
        if (count != null) {
            Text(
                text = "($count)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .alpha(if (enabled) 1f else 0.38f),
            )
        }
        if (!enabled && onEnable != null) {
            // 固定宽度 + End 对齐，保证「启用」文字右缘与同列其他行的计数右缘一致
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(48.dp)
                    .clickable(onClick = onEnable),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = stringResource(R.string.search_filter_enable),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun sourceLabel(source: String, customSourceNames: Map<String, String>): String {
    return when (source) {
        ResourceRepository.SOURCE_PANSOU -> stringResource(R.string.search_sources_pansou)
        ResourceRepository.SOURCE_PANHUB -> stringResource(R.string.search_sources_panhub)
        ResourceRepository.SOURCE_ZRESO -> stringResource(R.string.search_sources_zreso)
        ResourceRepository.SOURCE_USEAI -> stringResource(R.string.search_sources_useai)
        ResourceRepository.SOURCE_YUNSO -> stringResource(R.string.search_sources_yunso)
        ResourceRepository.SOURCE_ATAW -> stringResource(R.string.search_sources_ataw)
        ResourceRepository.SOURCE_MELOST -> stringResource(R.string.search_sources_melost)
        ResourceRepository.SOURCE_PANXIAOZI -> stringResource(R.string.search_sources_panxiaozi)
        ResourceRepository.SOURCE_BAOYS -> stringResource(R.string.search_sources_baoys)
        else -> customSourceNames[source] ?: source
    }
}

@Composable
private fun diskTypeLabel(type: DiskType): String {
    return when (type) {
        DiskType.QUARK -> stringResource(R.string.disk_quark)
        DiskType.BAIDU -> stringResource(R.string.disk_baidu)
        DiskType.ALI -> stringResource(R.string.disk_ali)
        DiskType.XUNLEI -> stringResource(R.string.disk_xunlei)
        DiskType.UC -> stringResource(R.string.disk_uc)
        DiskType.ONEONEFIVE -> stringResource(R.string.disk_115)
        DiskType.MAGNET -> stringResource(R.string.disk_magnet)
        DiskType.OTHER -> stringResource(R.string.disk_other)
    }
}
