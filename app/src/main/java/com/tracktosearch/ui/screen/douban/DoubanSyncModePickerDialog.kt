package com.tracktosearch.ui.screen.douban

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.repository.SyncMode

/**
 * 豆瓣重新导入模式选择对话框(设置页「重新同步豆瓣」按钮触发)。
 *
 * 三种模式:
 * - A: 仅同步新增条目(跳过已同步的,不处理状态变化)
 * - B: 同步新增 + 检测状态变化(撤销旧操作应用新操作)
 * - C: 完全重写(清空已同步标记后重新应用,需二次确认)
 *
 * 每个选项附说明 + 示例,帮助用户理解后选择。
 */
@Composable
fun DoubanSyncModePickerDialog(
    syncedCount: Int,
    onDismiss: () -> Unit,
    onModeSelected: (SyncMode) -> Unit
) {
    // 模式 C 二次确认状态
    var showFullRewriteConfirm by remember { mutableStateOf(false) }

    if (showFullRewriteConfirm) {
        AlertDialog(
            onDismissRequest = { showFullRewriteConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.douban_sync_mode_c_title)) },
            text = { Text(stringResource(R.string.douban_sync_mode_warning_c)) },
            confirmButton = {
                TextButton(onClick = {
                    showFullRewriteConfirm = false
                    onDismiss()
                    onModeSelected(SyncMode.FULL_REWRITE)
                }) {
                    Text(stringResource(R.string.douban_sync_mode_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullRewriteConfirm = false }) {
                    Text(stringResource(R.string.douban_retry_cancel))
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.douban_sync_mode_picker_title)) },
        text = {
            Column {
                ModeOptionItem(
                    icon = Icons.Default.AddCircle,
                    title = stringResource(R.string.douban_sync_mode_a_title),
                    desc = stringResource(R.string.douban_sync_mode_a_desc),
                    example = stringResource(R.string.douban_sync_mode_a_example),
                    onClick = {
                        onDismiss()
                        onModeSelected(SyncMode.INCREMENTAL_ONLY)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ModeOptionItem(
                    icon = Icons.Default.Refresh,
                    title = stringResource(R.string.douban_sync_mode_b_title),
                    desc = stringResource(R.string.douban_sync_mode_b_desc),
                    example = stringResource(R.string.douban_sync_mode_b_example),
                    onClick = {
                        onDismiss()
                        onModeSelected(SyncMode.INCREMENTAL_WITH_CHANGES)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ModeOptionItem(
                    icon = Icons.Default.Replay,
                    title = stringResource(R.string.douban_sync_mode_c_title),
                    desc = stringResource(R.string.douban_sync_mode_c_desc),
                    example = stringResource(R.string.douban_sync_mode_c_example, syncedCount),
                    onClick = {
                        // 模式 C 需二次确认
                        showFullRewriteConfirm = true
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.douban_retry_cancel))
            }
        }
    )
}

@Composable
private fun ModeOptionItem(
    icon: ImageVector,
    title: String,
    desc: String,
    example: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    example,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontStyle = FontStyle.Italic
                )
            }
        }
    }
}
