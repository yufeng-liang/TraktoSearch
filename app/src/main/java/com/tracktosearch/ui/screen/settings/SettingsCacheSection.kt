package com.tracktosearch.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * 缓存管理项：初始只显示总概览，点击展开三个类目的分项大小与清除按钮。
 *
 * - 概览行：图标 + 「缓存管理」标题 + 总大小 + 右侧展开箭头（整行一个热区）
 * - 展开后：3 个 CacheCategoryRow（图片 / 影视数据 / 网络请求），每个独立清除
 * - 底部：「全部清除」按钮
 */
@Composable
fun CacheManagementItem(
    breakdown: SettingsViewModel.CacheBreakdown,
    onClearCategory: (SettingsViewModel.CacheCategory) -> Unit,
    onClearAll: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberAppHaptics()

    // 卡片样式：独占一行的圆角卡片
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 概览行（整卡可点击展开/收起）
            val toggleDescription = stringResource(
                if (expanded) R.string.detail_collapse else R.string.detail_expand
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 展开 / 收起：语义按点完之后的新状态定，和通知分组那两行同一写法
                    .hapticClickable(
                        semantic = if (expanded) HapticSemantic.TOGGLE_OFF
                                   else HapticSemantic.TOGGLE_ON
                    ) { expanded = !expanded }
                    .semantics { contentDescription = toggleDescription }
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isDark = isAppDarkTheme()
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = settingsIconContainerColor(isDark),
                            shape = RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_cache_management),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_cache_total, formatFileSize(breakdown.totalBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 展开箭头只作指示：整行已经是同一个展开热区，再套一个 IconButton 等于同一目标
                // 有两个可点区域，读屏也会读出两个按钮
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            // 展开后：保留 图片/影视数据/HTTP 三个类目（去掉 ID 映射、离线数据库）
            AnimatedVisibility(visible = expanded) {
                Column {
                    CacheCategoryRow(
                        icon = Icons.Rounded.Image,
                        labelRes = R.string.settings_cache_category_image,
                        descRes = R.string.settings_cache_category_image_desc,
                        sizeBytes = breakdown.imageBytes,
                        onClear = { onClearCategory(SettingsViewModel.CacheCategory.IMAGE) }
                    )
                    CacheCategoryRow(
                        icon = Icons.Rounded.Movie,
                        labelRes = R.string.settings_cache_category_media_data,
                        descRes = R.string.settings_cache_category_media_data_desc,
                        sizeBytes = breakdown.mediaDataBytes,
                        onClear = { onClearCategory(SettingsViewModel.CacheCategory.MEDIA_DATA) }
                    )
                    CacheCategoryRow(
                        icon = Icons.Rounded.CloudDownload,
                        labelRes = R.string.settings_cache_category_http,
                        descRes = R.string.settings_cache_category_http_desc,
                        sizeBytes = breakdown.httpBytes,
                        onClear = { onClearCategory(SettingsViewModel.CacheCategory.HTTP) }
                    )
                    // 全部清除按钮（红色警示样式）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = { haptics.tap(); onClearAll() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text(stringResource(R.string.settings_cache_clear_all))
                        }
                    }
                }
            }
        }
    }
}

/** 缓存类目行：图标 + 名称 + 描述 + 大小 + 清除按钮 */
@Composable
private fun CacheCategoryRow(
    icon: ImageVector,
    labelRes: Int,
    descRes: Int,
    sizeBytes: Long,
    onClear: () -> Unit
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = formatFileSize(sizeBytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        OutlinedButton(
            onClick = { haptics.tap(); onClear() },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
        ) {
            Text(stringResource(R.string.settings_cache_clear))
        }
    }
}

/** 文件大小格式化（B/KB/MB/GB） */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${size.toInt()} ${units[unitIndex]}"
    else String.format("%.1f %s", size, units[unitIndex])
}
