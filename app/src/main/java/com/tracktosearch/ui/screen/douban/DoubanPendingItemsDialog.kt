package com.tracktosearch.ui.screen.douban

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracktosearch.R

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
 * 优先级:pending items 优先于 failures 重试。
 * - 有 pending items → 弹此对话框
 * - 无 pending items 但有 failures → 弹失败重试对话框
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
                    onClick = {
                        onDismiss()
                        onContinue()
                    },
                    isRecommended = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 选项 2:完整同步
                ResumeOptionItem(
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(R.string.douban_resume_full),
                    subtitle = stringResource(R.string.douban_resume_full_desc),
                    onClick = {
                        onDismiss()
                        onFullSync()
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
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

    Surface(
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
