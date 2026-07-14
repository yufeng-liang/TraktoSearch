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
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.repository.CloudFailureSyncManager
import com.tracktosearch.data.repository.DoubanFailureExporter
import com.tracktosearch.data.repository.DoubanRetryManager
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.DownloadResult
import com.tracktosearch.data.repository.FailureReason
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 重试选项对话框 ViewModel。
 *
 * 暴露 [DoubanRetryManager.retryState] 给 UI,并提供启动重试、清空失败记录的能力。
 * 同时暴露 [doubanFailureExporter] 与 [doubanSyncManager] 供设置页「重新同步豆瓣」「导出失败记录」入口调用。
 *
 * 云端同步:[cloudFailureSyncManager] 提供手动上传/下载失败数据到 Gitee 云端的能力,
 * 供设置页「上传到云端」「从云端拉取」入口调用(要求登录豆瓣获取 userId 做文件路径隔离)。
 */
@HiltViewModel
class DoubanRetryViewModel @Inject constructor(
    val doubanRetryManager: DoubanRetryManager,
    val doubanFailureExporter: DoubanFailureExporter,
    val doubanSyncManager: DoubanSyncManager,
    val cloudFailureSyncManager: CloudFailureSyncManager
) : ViewModel() {
    val retryState = doubanRetryManager.retryState

    /** 云端同步事件(一次性,UI 显示后用 [clearCloudSyncEvent] 清空) */
    private val _cloudSyncEvent = MutableStateFlow<CloudSyncEvent?>(null)
    val cloudSyncEvent: StateFlow<CloudSyncEvent?> = _cloudSyncEvent.asStateFlow()

    /** 云端同步进行中(用于 UI 显示 loading) */
    private val _cloudSyncLoading = MutableStateFlow(false)
    val cloudSyncLoading: StateFlow<Boolean> = _cloudSyncLoading.asStateFlow()

    /** 重新加载失败项统计(用于入口检测) */
    suspend fun refreshRetryState() {
        doubanRetryManager.refreshRetryState()
    }

    /** 启动本地重试 */
    suspend fun startRetryFromLocal(selectedReasons: Set<FailureReason>): Boolean {
        return doubanRetryManager.startRetryFromLocal(selectedReasons)
    }

    /** 清空失败记录 */
    suspend fun clearAllFailures() {
        doubanRetryManager.clearAllFailures()
    }

    /** 手动上传本地失败项到云端(要求已登录豆瓣获取 userId 做文件路径隔离) */
    fun uploadToCloud() {
        viewModelScope.launch {
            _cloudSyncLoading.value = true
            if (!cloudFailureSyncManager.isLoggedIn()) {
                _cloudSyncEvent.value = CloudSyncEvent.NotLoggedIn
                _cloudSyncLoading.value = false
                return@launch
            }
            if (!retryState.value.hasFailures) {
                _cloudSyncEvent.value = CloudSyncEvent.NoLocalFailures
                _cloudSyncLoading.value = false
                return@launch
            }
            val success = cloudFailureSyncManager.uploadIfHasFailures()
            _cloudSyncEvent.value = if (success) CloudSyncEvent.UploadSuccess else CloudSyncEvent.UploadFailed
            _cloudSyncLoading.value = false
        }
    }

    /** 从云端拉取该豆瓣账号的失败数据并合并到本地 Room */
    fun downloadFromCloud() {
        viewModelScope.launch {
            _cloudSyncLoading.value = true
            if (!cloudFailureSyncManager.isLoggedIn()) {
                _cloudSyncEvent.value = CloudSyncEvent.NotLoggedIn
                _cloudSyncLoading.value = false
                return@launch
            }
            val result = cloudFailureSyncManager.downloadAndMerge()
            _cloudSyncEvent.value = when (result) {
                is DownloadResult.Success -> {
                    // 云端更新,本地已替换 → 刷新失败项统计
                    refreshRetryState()
                    CloudSyncEvent.DownloadSuccess(result.count)
                }
                DownloadResult.CloudEmpty -> CloudSyncEvent.CloudEmpty
                DownloadResult.LocalNewer -> CloudSyncEvent.LocalNewer
                DownloadResult.Failed -> CloudSyncEvent.DownloadFailed
            }
            _cloudSyncLoading.value = false
        }
    }

    fun clearCloudSyncEvent() {
        _cloudSyncEvent.value = null
    }
}

/** 云端同步事件(用于 UI 显示 Snackbar 反馈) */
sealed class CloudSyncEvent {
    /** 未登录豆瓣账号,无法定位云端文件路径 */
    object NotLoggedIn : CloudSyncEvent()

    /** 本地无失败项,跳过上传 */
    object NoLocalFailures : CloudSyncEvent()

    /** 上传成功 */
    object UploadSuccess : CloudSyncEvent()

    /** 上传失败(网络/服务端错误) */
    object UploadFailed : CloudSyncEvent()

    /** 云端无失败数据 */
    object CloudEmpty : CloudSyncEvent()

    /** 本地数据更新,跳过云端(无需刷新 UI) */
    object LocalNewer : CloudSyncEvent()

    /** 下载并合并 N 条失败数据 */
    data class DownloadSuccess(val count: Int) : CloudSyncEvent()

    /** 下载失败(网络/服务端错误) */
    object DownloadFailed : CloudSyncEvent()
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
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
                    icon = Icons.Rounded.Replay,
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
                    icon = Icons.Rounded.FileUpload,
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
                    icon = Icons.Rounded.FileDownload,
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp
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
                Icons.Rounded.ChevronRight,
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
