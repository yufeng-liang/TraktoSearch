package com.tracktosearch.ui.screen.douban

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.repository.DoubanFailureExporter
import com.tracktosearch.data.repository.DoubanRetryManager
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.FailureReason
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 重试选项对话框 ViewModel。
 *
 * 暴露 [DoubanRetryManager.retryState] 给 UI,并提供启动重试、清空失败记录的能力。
 * 同时暴露 [doubanFailureExporter] 与 [doubanSyncManager] 供设置页「重新同步豆瓣」「导出失败记录」入口调用。
 */
@HiltViewModel
class DoubanRetryViewModel @Inject constructor(
    val doubanRetryManager: DoubanRetryManager,
    val doubanFailureExporter: DoubanFailureExporter,
    val doubanSyncManager: DoubanSyncManager
) : ViewModel() {
    val retryState = doubanRetryManager.retryState

    /** 重新加载失败项统计(用于入口检测) */
    suspend fun refreshRetryState() {
        doubanRetryManager.refreshRetryState()
    }

    /** 启动本地重试 */
    suspend fun startRetryFromLocal(selectedReasons: Set<FailureReason>): Boolean {
        return doubanRetryManager.startRetryFromLocal(selectedReasons)
    }

    /** 启动 JSON 导入重试 */
    fun startRetryFromJson(
        failures: List<com.tracktosearch.data.repository.DoubanSyncFailure>,
        selectedReasons: Set<FailureReason>
    ): Boolean {
        return doubanRetryManager.startRetryFromJson(failures, selectedReasons)
    }

    /** 清空失败记录 */
    suspend fun clearAllFailures() {
        doubanRetryManager.clearAllFailures()
    }
}

/**
 * 重新导入豆瓣失败项重试对话框。
 *
 * 触发条件:用户点击设置页「重试上次失败项」按钮时,检测到 douban_sync_failures 表非空。
 *
 * 三个选项:
 * 1. 重试上次失败的 N 项(从本地 Room 读,默认仅重试可恢复类型)
 * 2. 导入 JSON 文件重试(从导出的失败数据文件加载)
 * 3. 导出失败记录(从本地 Room 读取,生成 JSON 触发分享)
 *
 * 选择「重试上次失败」后会弹子对话框,让用户勾选要重试的失败类型。
 */
@Composable
fun DoubanRetryDialog(
    onDismiss: () -> Unit,
    onRetryLocal: (Set<FailureReason>) -> Unit,
    onRetryFromJson: () -> Unit,
    onExportFailures: () -> Unit,
    viewModel: DoubanRetryViewModel = hiltViewModel()
) {
    val state by viewModel.retryState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 子对话框状态:选中「重试上次失败」后弹出,让用户勾选要重试的失败类型
    var showReasonPicker by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.douban_retry_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.douban_retry_subtitle, state.totalFailures),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 选项 1:重试上次失败
                RetryOptionItem(
                    icon = Icons.Default.Replay,
                    title = stringResource(R.string.douban_retry_option_local, state.totalFailures),
                    subtitle = stringResource(
                        R.string.douban_retry_option_local_desc,
                        state.recoverableCount,
                        state.nonRecoverableCount
                    ),
                    onClick = { showReasonPicker = true }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 选项 2:导入 JSON 重试
                RetryOptionItem(
                    icon = Icons.Default.FileUpload,
                    title = stringResource(R.string.douban_retry_option_json),
                    subtitle = stringResource(R.string.douban_retry_option_json_desc),
                    onClick = {
                        onDismiss()
                        onRetryFromJson()
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 选项 3:导出失败记录(从本地 Room 读取,生成 JSON 触发分享)
                RetryOptionItem(
                    icon = Icons.Default.FileDownload,
                    title = stringResource(R.string.douban_retry_option_export),
                    subtitle = stringResource(R.string.douban_retry_option_export_desc),
                    onClick = {
                        onDismiss()
                        onExportFailures()
                    }
                )

                // 重试次数 >= 3 时不再自动建议
                if (state.maxAttemptCount >= 3) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.douban_retry_max_attempt, state.maxAttemptCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { showClearConfirm = true }) {
                    Text(stringResource(R.string.douban_retry_clear))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.douban_retry_cancel))
                }
            }
        }
    )

    // 子对话框:选择要重试的失败类型
    if (showReasonPicker) {
        FailureReasonPickerDialog(
            state = state,
            onDismiss = { showReasonPicker = false },
            onStart = { selectedReasons ->
                showReasonPicker = false
                onDismiss()
                onRetryLocal(selectedReasons)
            }
        )
    }

    // 清空失败记录确认
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.douban_retry_clear)) },
            text = { Text(stringResource(R.string.douban_retry_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch {
                        viewModel.clearAllFailures()
                        onDismiss()
                    }
                }) { Text(stringResource(R.string.douban_retry_clear_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.douban_retry_clear_confirm_no))
                }
            }
        )
    }
}

/**
 * 重试选项子对话框:让用户勾选要重试的失败类型。
 *
 * 默认勾选可恢复类型(详情页访问失败、Trakt 写入超时/失败),
 * 不可恢复类型(无 IMDb ID、Trakt 未找到)默认不勾选,但用户可手动勾选强制重试。
 */
@Composable
private fun FailureReasonPickerDialog(
    state: com.tracktosearch.data.repository.RetryState,
    onDismiss: () -> Unit,
    onStart: (Set<FailureReason>) -> Unit
) {
    // 默认勾选可恢复类型
    val initialReasons = FailureReason.entries.filter { it.recoverable }.toMutableSet()
    val selectedReasons = remember { mutableStateOf<Set<FailureReason>>(initialReasons) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.douban_retry_select_reasons)) },
        text = {
            Column {
                FailureReason.entries.forEach { reason ->
                    val count = state.byReason[reason] ?: 0
                    if (count == 0) return@forEach

                    val label = stringResource(reason.localizedStringResCompat())
                    val subtitle = if (reason.recoverable) {
                        stringResource(R.string.douban_retry_reason_recoverable, count)
                    } else {
                        stringResource(R.string.douban_retry_reason_non_recoverable, count)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val current = selectedReasons.value
                                selectedReasons.value = if (reason in current) {
                                    current - reason
                                } else {
                                    current + reason
                                }
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = reason in selectedReasons.value,
                            onCheckedChange = { checked ->
                                val current = selectedReasons.value
                                selectedReasons.value = if (checked) {
                                    current + reason
                                } else {
                                    current - reason
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (reason.recoverable)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onStart(selectedReasons.value) },
                enabled = selectedReasons.value.isNotEmpty()
            ) {
                Text(stringResource(R.string.douban_retry_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.douban_retry_cancel))
            }
        }
    )
}

/** 重试选项行 */
@Composable
private fun RetryOptionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
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
            verticalAlignment = Alignment.CenterVertically,
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
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** FailureReason 本地化字符串资源 ID */
private fun FailureReason.localizedStringResCompat(): Int = when (this) {
    FailureReason.NO_IMDB_ID -> R.string.douban_failure_reason_no_imdb_id
    FailureReason.DETAIL_FETCH_FAILED -> R.string.douban_failure_reason_detail_fetch_failed
    FailureReason.TRAKT_NOT_FOUND -> R.string.douban_failure_reason_trakt_not_found
    FailureReason.TRAKT_WRITE_TIMEOUT -> R.string.douban_failure_reason_trakt_write_timeout
    FailureReason.TRAKT_WRITE_FAILED -> R.string.douban_failure_reason_trakt_write_failed
}
