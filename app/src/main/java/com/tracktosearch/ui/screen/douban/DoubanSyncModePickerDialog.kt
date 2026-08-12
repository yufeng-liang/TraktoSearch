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
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
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
import com.tracktosearch.data.local.CooldownStatus
import com.tracktosearch.data.repository.SyncMode

/**
 * 豆瓣重新导入模式选择对话框(设置页「重新同步豆瓣」按钮触发)。
 *
 * 两种模式:
 * - A: 增量同步 + 状态变化检测,同步完成后自动进行状态一致性检查
 * - B: 完全重写(清空已同步标记后重新应用,需二次确认)
 *
 * 每个选项附说明 + 示例,帮助用户理解后选择。
 *
 * @param cooldownStatus 冷却期状态,非 null 且 isCoolingDown 时在增量同步选项标题右侧显示剩余天数
 * @param neverSynced 从未同步过时标题使用「选择同步模式」,否则「选择重新同步模式」
 */
@Composable
fun DoubanSyncModePickerDialog(
    syncedCount: Int,
    cooldownStatus: CooldownStatus? = null,
    neverSynced: Boolean = false,
    onDismiss: () -> Unit,
    onModeSelected: (SyncMode) -> Unit
) {
    // 模式 B 二次确认状态
    var showFullRewriteConfirm by remember { mutableStateOf(false) }

    if (showFullRewriteConfirm) {
        AlertDialog(
            onDismissRequest = { showFullRewriteConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(if (neverSynced) R.string.douban_sync_mode_picker_title_first else R.string.douban_sync_mode_picker_title)) },
        text = {
            Column {
                ModeOptionItem(
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(R.string.douban_sync_mode_b_title),
                    desc = stringResource(R.string.douban_sync_mode_b_desc),
                    example = stringResource(R.string.douban_sync_mode_b_example),
                    onClick = {
                        onDismiss()
                        onModeSelected(SyncMode.INCREMENTAL_WITH_CHANGES)
                    },
                    trailing = { CooldownBadge(cooldownStatus) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ModeOptionItem(
                    icon = Icons.Rounded.Replay,
                    title = stringResource(R.string.douban_sync_mode_c_title),
                    desc = stringResource(R.string.douban_sync_mode_c_desc),
                    example = stringResource(R.string.douban_sync_mode_c_example, syncedCount),
                    onClick = {
                        // 模式 B 需二次确认
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
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    if (trailing != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        trailing()
                    }
                }
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

/**
 * 冷却期状态徽章:冷却中显示剩余天数,可同步显示可同步,从未同步或 null 不显示。
 */
@Composable
private fun CooldownBadge(cooldownStatus: CooldownStatus?) {
    cooldownStatus?.let { status ->
        if (!status.neverSynced) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (status.isCoolingDown)
                    MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = if (status.isCoolingDown)
                        stringResource(R.string.cooldown_remaining_days, status.remainingDays)
                    else stringResource(R.string.cooldown_available),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.isCoolingDown)
                        MaterialTheme.colorScheme.onTertiaryContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * 首次同步引导弹窗。
 *
 * 检测到用户已登录豆瓣但从未同步过时自动弹出。
 * 用户点「开始导入」后弹出模式选择弹窗（DoubanSyncModePickerDialog）。
 */
@Composable
fun DoubanFirstSyncGuideDialog(
    onDismiss: () -> Unit,
    onStartImport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        icon = { Icon(Icons.Rounded.AddCircle, contentDescription = null) },
        title = { Text(stringResource(R.string.douban_first_sync_title)) },
        text = { Text(stringResource(R.string.douban_first_sync_message)) },
        confirmButton = {
            TextButton(onClick = onStartImport) {
                Text(stringResource(R.string.douban_first_sync_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.douban_first_sync_later))
            }
        }
    )
}
