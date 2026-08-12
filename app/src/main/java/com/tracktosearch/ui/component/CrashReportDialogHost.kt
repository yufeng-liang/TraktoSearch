package com.tracktosearch.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.CrashHandler
import com.tracktosearch.R
import com.tracktosearch.data.local.CrashLogStorage
import com.tracktosearch.data.util.CrashLogUploader
import com.tracktosearch.data.util.CrashPromptDecision
import com.tracktosearch.data.util.UploadState
import com.tracktosearch.data.util.UploadToastPolicy
import com.tracktosearch.ui.util.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 崩溃上报对话框主机（Compose 顶层状态驱动）。
 *
 * 状态组合：
 * - 启动读崩溃计数 + 授权状态 → 弹授权询问 / 清日志 / 等待自动上传
 * - 观察 [CrashLogUploader.uploadState]：Uploading 显示转圈；Success 弹成功 toast；
 *   Failed 弹失败 toast + 重试对话框（不关闭，可取消保留日志）
 * - toast 触发规则（避免误报）：状态转移（Uploading → 终态）或首次挂载即终态且本次会话有崩溃
 *
 * 已知取舍：旋转/进程重建后授权/失败/上传中对话框不恢复（崩溃计数已消费，重建后不重复打扰；
 * 上传进行中由 [CrashLogUploader.uploadState] 状态驱动继续反馈）。
 */
@Composable
fun CrashReportDialogHost(
    crashLogStorage: CrashLogStorage,
    crashLogUploader: CrashLogUploader,
) {
    val context = LocalContext.current
    var crashCount by remember { mutableStateOf(0) }
    var authLoaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var dialogVisible by remember { mutableStateOf(false) }
    var dialogKind by remember { mutableStateOf<DialogKind>(DialogKind.Authorize) }
    var lastUploadState by remember { mutableStateOf<UploadState?>(null) }
    val uploadState by crashLogUploader.uploadState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 启动决策：读崩溃计数与授权状态（一次性）
    LaunchedEffect(Unit) {
        crashCount = withContext(Dispatchers.IO) { CrashHandler.getAndResetCrashCount(context) }
        // 挂起等待 DataStore 初始值加载完成，避免对已授权用户误读默认值而弹授权框
        crashLogStorage.loaded.first { it }
        val prompted = crashLogStorage.prompted.first()
        enabled = crashLogStorage.enabled.first()
        when (CrashPromptDecision.decide(crashCount, enabled, prompted)) {
            CrashPromptDecision.Action.None -> dialogVisible = false
            CrashPromptDecision.Action.Authorize -> {
                dialogVisible = true
                dialogKind = DialogKind.Authorize
            }
            CrashPromptDecision.Action.ClearLogs ->
                withContext(Dispatchers.IO) { CrashHandler.clearCrashLogs(context) }
            CrashPromptDecision.Action.AutoUpload -> {
                // 上传已由 TraktSearchApp 启动触发，由下方状态监听驱动
            }
        }
        authLoaded = true
    }

    // 上传状态监听：处理 toast 与失败重试对话框
    LaunchedEffect(uploadState, authLoaded) {
        if (!authLoaded) return@LaunchedEffect
        val state = uploadState
        val prev = lastUploadState
        lastUploadState = state
        when (state) {
            UploadState.Uploading -> {
                dialogVisible = true
                dialogKind = DialogKind.Uploading
            }
            UploadState.Success -> {
                // 手动触发（从 Uploading 转移）或启动自动上传完成（prev==null 且有崩溃）
                if (UploadToastPolicy.shouldNotify(prev, state, crashCount)) {
                    context.showToast(context.getString(R.string.crash_upload_success))
                }
                dialogVisible = false
            }
            is UploadState.Failed -> {
                if (UploadToastPolicy.shouldNotify(prev, state, crashCount)) {
                    context.showToast(context.getString(R.string.crash_upload_failed))
                }
                dialogVisible = true
                dialogKind = DialogKind.Retry(state.error)
            }
            UploadState.Idle -> Unit
        }
    }

    if (!dialogVisible) return

    when (val kind = dialogKind) {
        is DialogKind.Authorize -> AlertDialog(
            onDismissRequest = { /* 不可取消 */ },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.crash_auth_dialog_title)) },
            text = { Text(stringResource(R.string.crash_auth_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    // 乐观切换上传中，避免关闭后再弹的闪烁空窗
                    dialogKind = DialogKind.Uploading
                    scope.launch {
                        crashLogStorage.setEnabled(true)
                        crashLogUploader.uploadPendingLogs()
                    }
                }) { Text(stringResource(R.string.crash_auth_dialog_agree)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    dialogVisible = false
                    scope.launch {
                        crashLogStorage.setPrompted(true)
                        withContext(Dispatchers.IO) { CrashHandler.clearCrashLogs(context) }
                    }
                }) { Text(stringResource(R.string.crash_auth_dialog_decline)) }
            },
        )
        DialogKind.Uploading -> AlertDialog(
            onDismissRequest = { /* 上传中不可取消 */ },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.crash_uploading)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
        is DialogKind.Retry -> AlertDialog(
            onDismissRequest = { /* 需用户明确取消 */ },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null) },
            title = { Text(stringResource(R.string.crash_upload_failed)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.crash_upload_fail_hint))
                    if (kind.error.isNotBlank()) {
                        Text(
                            kind.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { crashLogUploader.uploadPendingLogs() }
                }) { Text(stringResource(R.string.crash_upload_retry)) }
            },
            dismissButton = {
                TextButton(onClick = { dialogVisible = false }) {
                    Text(stringResource(R.string.crash_dialog_cancel))
                }
            },
        )
    }
}

private sealed interface DialogKind {
    data object Authorize : DialogKind
    data object Uploading : DialogKind
    data class Retry(val error: String) : DialogKind
}
