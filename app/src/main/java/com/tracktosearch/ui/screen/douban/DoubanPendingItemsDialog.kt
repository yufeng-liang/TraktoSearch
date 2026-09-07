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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.haptic.rememberAppHaptics

/**
 * 豆瓣同步续传对话框。
 *
 * 触发条件:App 启动时检测到 douban_sync_pending_items 表有数据
 * (上次同步取消时有已爬到的列表数据未处理完)。
 *
 * 两个选项:
 * 1. 继续同步:跳过列表爬取,直接处理已爬到的数据(走 startResume)
 * 2. 完整同步:清空未处理数据,从头开始(走 startSync)
 *
 * - 有 pending items → 弹此对话框
 * - 都没有 → 正常流程
 */
@Composable
fun DoubanPendingItemsDialog(
    pendingCount: Int,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,    // 继续同步(走 startResume)
    onFullSync: () -> Unit     // 完整同步(清空 pending items + 走 startSync)
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.douban_resume_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.douban_resume_subtitle, pendingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 选项 1:继续同步(推荐)
                ResumeOptionItem(
                    icon = Icons.Rounded.PlayArrow,
                    title = stringResource(R.string.douban_resume_continue),
                    subtitle = stringResource(R.string.douban_resume_continue_desc),
                    onClick = onContinue,
                    isRecommended = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 选项 2:完整同步
                ResumeOptionItem(
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(R.string.douban_resume_full),
                    subtitle = stringResource(R.string.douban_resume_full_desc),
                    onClick = onFullSync
                )
            }
        },
        confirmButton = {
            // AlertDialog 的槽是独立 subcomposition，单独取一份
            val haptics = rememberAppHaptics()
            TextButton(onClick = { haptics.lightTap(); onDismiss() }) {
                Text(stringResource(R.string.douban_resume_cancel))
            }
        }
    )
}

/**
 * 豆瓣同步回滚恢复对话框。
 *
 * 触发条件:App 启动时检测到 douban_sync_rollback 表有数据
 * (上次完整重写同步失败/取消,标记已从 Trakt 删除但未恢复)。
 *
 * 两个选项:
 * 1. 恢复标记:将被删除的标记重新添加到 Trakt(推荐)
 * 2. 不恢复:放弃恢复,标记将永久丢失
 *
 */
@Composable
fun DoubanRollbackDialog(
    rollbackCount: Int,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,    // 恢复标记
    onDiscard: () -> Unit     // 丢弃,不恢复
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.douban_rollback_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.douban_rollback_subtitle, rollbackCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 选项 1:恢复标记(推荐)
                ResumeOptionItem(
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(R.string.douban_rollback_restore),
                    subtitle = stringResource(R.string.douban_rollback_restore_desc),
                    onClick = {
                        onDismiss()
                        onRestore()
                    },
                    isRecommended = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 选项 2:不恢复
                ResumeOptionItem(
                    icon = Icons.Rounded.ChevronRight,
                    title = stringResource(R.string.douban_rollback_discard),
                    subtitle = stringResource(R.string.douban_rollback_discard_desc),
                    onClick = {
                        onDismiss()
                        onDiscard()
                    }
                )
            }
        },
        confirmButton = {
            val haptics = rememberAppHaptics()
            TextButton(onClick = { haptics.lightTap(); onDismiss() }) {
                Text(stringResource(R.string.douban_resume_cancel))
            }
        }
    )
}

/** 续传选项行 */
@Composable
private fun ResumeOptionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isRecommended: Boolean = false
) {
    val containerColor = if (isRecommended)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    val titleColor = if (isRecommended)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurface
    // 选项行是这几个弹窗真正的「确定」，本函数自己就在弹窗的 text 槽里，取一份即可
    val haptics = rememberAppHaptics()

    Surface(
        onClick = { haptics.tap(); onClick() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        color = containerColor,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = titleColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = titleColor
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = titleColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun DoubanPendingItemsDialogWithDiscard(
    pendingCount: Int,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onFullSync: () -> Unit,
    onDiscardPending: () -> Unit
) {
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.douban_resume_discard_title)) },
            text = { Text(stringResource(R.string.douban_resume_discard_message, pendingCount)) },
            confirmButton = {
                val haptics = rememberAppHaptics()
                TextButton(onClick = {
                    haptics.tap()
                    showDiscardConfirmation = false
                    onDiscardPending()
                    onDismiss()
                }) { Text(stringResource(R.string.douban_resume_discard_confirm)) }
            },
            dismissButton = {
                val haptics = rememberAppHaptics()
                TextButton(onClick = { haptics.lightTap(); showDiscardConfirmation = false }) {
                    Text(stringResource(R.string.douban_resume_discard_cancel))
                }
            }
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.douban_resume_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.douban_resume_subtitle, pendingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                ResumeOptionItem(
                    icon = Icons.Rounded.PlayArrow,
                    title = stringResource(R.string.douban_resume_continue),
                    subtitle = stringResource(R.string.douban_resume_continue_desc),
                    onClick = onContinue,
                    isRecommended = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                ResumeOptionItem(
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(R.string.douban_resume_full),
                    subtitle = stringResource(R.string.douban_resume_full_desc),
                    onClick = onFullSync
                )
            }
        },
        confirmButton = {
            val haptics = rememberAppHaptics()
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { haptics.tap(); showDiscardConfirmation = true }) {
                    Text(stringResource(R.string.douban_resume_discard))
                }
                TextButton(onClick = { haptics.lightTap(); onDismiss() }) {
                    Text(stringResource(R.string.douban_resume_cancel))
                }
            }
        }
    )
}
