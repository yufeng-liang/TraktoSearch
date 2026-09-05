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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.remote.douban.DelayType
import com.tracktosearch.data.repository.DoubanSyncLoginTarget
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.DoubanSyncStage
import com.tracktosearch.data.repository.DoubanSyncSubStage
import com.tracktosearch.data.repository.labelRes
import com.tracktosearch.service.DoubanSyncService
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.screen.watchlist.hasLiveCountProgress
import com.tracktosearch.ui.util.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

/** 豆瓣同步进度对话框的 ViewModel。 */
@HiltViewModel
class DoubanSyncViewModel @Inject constructor(
    val doubanSyncManager: DoubanSyncManager
) : ViewModel() {

    val progress = doubanSyncManager.progress

    fun cancel() {
        doubanSyncManager.cancel()
    }
}

/**
 * 豆瓣同步进度对话框。
 *
 * 完成态只展示成功、跳过和缓存命中统计；失败数据仍由底层重试兼容链路保存。
 */
@Composable
fun DoubanSyncDialog(
    onDismiss: () -> Unit,
    onRelogin: (() -> Unit)? = null,
    onBackground: () -> Unit = onDismiss,
    onBackgroundUnavailable: (() -> Unit)? = null,
    onTraktLogin: (() -> Unit)? = null,
    onViewFailures: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onViewConflicts: (() -> Unit)? = null,
    viewModel: DoubanSyncViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val p = progress
    var showActivityDetails by remember { mutableStateOf(false) }
    var showFailureItems by remember { mutableStateOf(false) }

    var delayRemainingSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(p.delayInfo) {
        val info = p.delayInfo
        if (info == null) {
            delayRemainingSeconds = 0
        } else {
            while (true) {
                val elapsed = ((System.currentTimeMillis() - info.startMs) / 1000).toInt()
                delayRemainingSeconds = (info.totalSeconds - elapsed).coerceAtLeast(0)
                if (delayRemainingSeconds <= 0) break
                delay(1000)
            }
        }
    }

    // 同步终态那一记触感。用「上一次看到的 isComplete」做边沿检测，而不是直接
    // LaunchedEffect(p.isComplete)：DoubanSyncManager 完成后不会立刻重置 progress，
    // 弹窗重开时第一帧就可能拿到 isComplete=true —— 那是上一轮的旧结果，不该震。
    // remember 而非 rememberSaveable：旋屏后重新以当前值为基线，同样不会补震一记
    val outcomeHaptics = rememberAppHaptics()
    var observedComplete by remember { mutableStateOf(p.isComplete) }
    LaunchedEffect(p.isComplete) {
        val wasComplete = observedComplete
        observedComplete = p.isComplete
        if (!p.isComplete || wasComplete) return@LaunchedEffect
        // 取消不是结果：既没成也没败，用户自己按的，不用震回去告诉他
        if (p.isCancelling) return@LaunchedEffect
        // 判据就是下面结果区渲染出来的那几行：报错文案、失败数、未修复的冲突、
        // 云端上传红字，任意一条成立就说明还有事要用户再来一趟
        val clean = p.stage != DoubanSyncStage.FAILED &&
            p.stage != DoubanSyncStage.LOGIN_REQUIRED &&
            !p.cookieExpired &&
            p.errorMessage.isNullOrBlank() &&
            p.failedCount == 0 &&
            p.conflictsFound == 0 &&
            !(p.cloudUploadAttempted && !p.cloudUploadSucceeded)
        if (clean) outcomeHaptics.confirm() else outcomeHaptics.reject()
    }

    AlertDialog(
        onDismissRequest = {
            if (!p.isRunning) onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.douban_sync_title)) },
        text = {
            // AlertDialog 的 text 与 confirmButton 是各自独立的 subcomposition，各取一份
            val textHaptics = rememberAppHaptics()
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val stageLabel = stringResource(p.stage.labelRes())
                val subStageLabel = p.subStage.labelRes()?.let { stringResource(it) }
                val showRecentItems = p.stage == DoubanSyncStage.FETCHING_LIST
                val showProcessingQueue = when {
                    p.stage == DoubanSyncStage.PARSING_DATA -> when (p.subStage) {
                        DoubanSyncSubStage.FETCHING_DETAIL,
                        DoubanSyncSubStage.LOOKING_UP_TRAKT,
                        DoubanSyncSubStage.RETRYING_FAILURES -> true
                        else -> false
                    }
                    p.stage == DoubanSyncStage.UPDATING_LIST &&
                        p.subStage == DoubanSyncSubStage.STATUS_CHANGES -> true
                    else -> false
                }
                val showProgressCount = p.hasLiveCountProgress()
                val etaLabel = when {
                    p.etaSeconds < 0 -> null
                    p.etaSeconds < 60 -> stringResource(R.string.douban_sync_eta_seconds, p.etaSeconds)
                    p.etaSeconds < 3600 -> stringResource(
                        R.string.douban_sync_eta_minutes,
                        p.etaSeconds / 60
                    )
                    else -> stringResource(
                        R.string.douban_sync_eta_hours,
                        p.etaSeconds / 3600,
                        (p.etaSeconds % 3600) / 60
                    )
                }

                if (!showProgressCount) {
                    Text(stageLabel, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.douban_sync_progress_format, stageLabel, p.current, p.total))
                }
                if (subStageLabel != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        subStageLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (p.isRunning && !p.currentTitle.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.douban_sync_current_processing, p.currentTitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (p.isRunning && etaLabel != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.douban_sync_eta_format, etaLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (p.hasLiveCountProgress()) {
                    LinearProgressIndicator(
                        progress = { if (p.total > 0) (p.current.toFloat() / p.total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (p.isRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (p.isRunning && p.delayInfo != null && delayRemainingSeconds > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val delayLabel = when (p.delayInfo.type) {
                        DelayType.DOUBAN_DETAIL_CRAWL -> stringResource(R.string.douban_sync_delay_detail_crawl)
                        DelayType.DOUBAN_LIST_CRAWL -> stringResource(R.string.douban_sync_delay_list_crawl)
                        DelayType.DOUBAN_RETRY -> stringResource(R.string.douban_sync_delay_retry)
                    }
                    Text(
                        stringResource(R.string.douban_sync_delay_format, delayRemainingSeconds, delayLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                if (showRecentItems && p.recentItems.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.douban_sync_recent_items),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    p.recentItems.forEach { item ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        stringResource(item.status.labelRes()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Text(
                                        item.rating?.let {
                                            stringResource(R.string.douban_sync_preview_rating_format, it)
                                        } ?: stringResource(R.string.douban_sync_preview_no_rating),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        item.markedAt,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                if (showProcessingQueue) {
                    val processingItems = p.processingItems.take(3)
                    val pendingItems = p.pendingItems.take(5)
                    val remainingPendingCount = (p.pendingItemCount - pendingItems.size).coerceAtLeast(0)

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.douban_sync_processing_items),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (processingItems.isEmpty() && pendingItems.isEmpty() && p.pendingItemCount == 0) {
                        Text(
                            stringResource(R.string.douban_sync_empty_batch),
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        processingItems.forEach { item ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 1.dp,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    item.title,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (pendingItems.isNotEmpty() || remainingPendingCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.douban_sync_pending_items),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        pendingItems.forEach { item ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 1.dp,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    item.title,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (remainingPendingCount > 0) {
                            Text(
                                stringResource(R.string.douban_sync_pending_count, remainingPendingCount),
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (p.isComplete) {
                    Spacer(modifier = Modifier.height(12.dp))
                    p.errorMessage?.takeIf { it.isNotBlank() }?.let { errorMessage ->
                        // errorMessage 为原始异常消息，先映射为友好本地化文案再展示
                        val localizedError = Exception(errorMessage).toUserMessage(LocalContext.current, R.string.error_operation_failed)
                        Text(
                            stringResource(R.string.douban_sync_error_detail, localizedError),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        stringResource(R.string.douban_sync_summary_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SyncSummaryRow(
                        label = stringResource(R.string.douban_sync_summary_success),
                        value = p.successCount
                    )
                    SyncSummaryRow(
                        label = stringResource(R.string.douban_sync_summary_reused),
                        value = p.skippedCount + p.cacheHitCount
                    )
                    SyncSummaryRow(
                        label = stringResource(R.string.douban_sync_summary_failed),
                        value = p.failedCount
                    )
                    SyncSummaryRow(
                        label = stringResource(R.string.douban_sync_summary_conflicts),
                        value = p.conflictFixedCount,
                        suffix = if (p.conflictsFound > 0) {
                            stringResource(R.string.douban_sync_summary_conflicts_found, p.conflictsFound)
                        } else null
                    )
                    SyncSummaryRow(
                        label = stringResource(R.string.douban_sync_summary_cloud),
                        value = null,
                        suffix = when {
                            !p.cloudUploadAttempted -> stringResource(R.string.douban_sync_cloud_not_attempted)
                            p.cloudUploadSucceeded -> stringResource(R.string.douban_sync_cloud_succeeded)
                            else -> stringResource(R.string.douban_sync_cloud_failed)
                        }
                    )
                    // 保留紧凑摘要，便于快速扫读及兼容旧的无障碍/自动化查找。
                    Text(
                        stringResource(
                            R.string.douban_sync_summary_format,
                            p.successCount,
                            p.skippedCount,
                            p.cacheHitCount
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        when {
                            !p.cloudUploadAttempted -> stringResource(R.string.douban_sync_cloud_not_attempted)
                            p.cloudUploadSucceeded -> stringResource(R.string.douban_sync_cloud_succeeded)
                            else -> stringResource(R.string.douban_sync_cloud_failed)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (p.cloudUploadAttempted && !p.cloudUploadSucceeded) {
                            MaterialTheme.colorScheme.error
                        } else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (p.pendingItemCount > 0) {
                        SyncSummaryRow(
                            label = stringResource(R.string.douban_sync_summary_pending),
                            value = p.pendingItemCount
                        )
                    }
                    TextButton(onClick = {
                        textHaptics.toggle(!showActivityDetails)
                        showActivityDetails = !showActivityDetails
                    }) {
                        Text(
                            stringResource(
                                if (showActivityDetails) R.string.douban_sync_hide_details
                                else R.string.douban_sync_view_details
                            )
                        )
                    }
                    if (showActivityDetails) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (p.failedItems.isNotEmpty()) {
                                TextButton(onClick = {
                                    textHaptics.lightTap()
                                    showActivityDetails = true
                                    showFailureItems = !showFailureItems
                                    onViewFailures?.invoke()
                                }) {
                                    Text(stringResource(R.string.douban_sync_view_failures))
                                }
                            }
                            if (p.failedCount > 0 && onRetry != null) {
                                TextButton(onClick = { textHaptics.tap(); onRetry() }) {
                                    Text(stringResource(R.string.douban_sync_retry_failures))
                                }
                            }
                            if (p.conflictsFound > 0 && onViewConflicts != null) {
                                TextButton(onClick = {
                                    textHaptics.lightTap()
                                    showActivityDetails = true
                                    onViewConflicts()
                                }) {
                                    Text(stringResource(R.string.douban_sync_view_conflicts))
                                }
                            }
                        }
                        if (showFailureItems && p.failedItems.isNotEmpty()) {
                            p.failedItems.take(5).forEach { failure ->
                                Text(
                                    failure.title,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            val remaining = p.failedItems.size - 5
                            if (remaining > 0) {
                                Text(
                                    stringResource(R.string.douban_sync_failure_remaining, remaining),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val buttonHaptics = rememberAppHaptics()
            // 「完成」虽然只是关掉弹窗（同步早已跑完、结果已落库），但它是这个弹窗的主按钮 → tap()。
            // lightTap() 只留给下面标着「取消」的那一侧
            when {
                p.isComplete &&
                    p.stage == DoubanSyncStage.LOGIN_REQUIRED &&
                    p.loginTarget == DoubanSyncLoginTarget.TRAKT -> {
                    Row {
                        if (onTraktLogin != null) {
                            TextButton(onClick = {
                                buttonHaptics.tap()
                                onDismiss()
                                onTraktLogin()
                            }) { Text(stringResource(R.string.douban_sync_login_trakt)) }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        TextButton(onClick = { buttonHaptics.tap(); onDismiss() }) { Text(stringResource(R.string.douban_sync_complete)) }
                    }
                }
                p.isComplete &&
                    p.stage == DoubanSyncStage.LOGIN_REQUIRED &&
                    p.loginTarget == DoubanSyncLoginTarget.DOUBAN &&
                    !p.cookieExpired -> {
                    Row {
                        if (onRelogin != null) {
                            TextButton(onClick = {
                                buttonHaptics.tap()
                                onDismiss()
                                onRelogin()
                            }) { Text(stringResource(R.string.douban_sync_login_douban)) }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        TextButton(onClick = { buttonHaptics.tap(); onDismiss() }) { Text(stringResource(R.string.douban_sync_complete)) }
                    }
                }
                p.isComplete &&
                    p.stage == DoubanSyncStage.LOGIN_REQUIRED &&
                    p.loginTarget == DoubanSyncLoginTarget.DOUBAN &&
                    p.cookieExpired -> {
                    Row {
                        if (onRelogin != null) {
                            TextButton(onClick = {
                                buttonHaptics.tap()
                                onDismiss()
                                onRelogin()
                            }) { Text(stringResource(R.string.douban_sync_relogin)) }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        TextButton(onClick = { buttonHaptics.tap(); onDismiss() }) { Text(stringResource(R.string.douban_sync_complete)) }
                    }
                }
                p.isComplete -> {
                    TextButton(onClick = { buttonHaptics.tap(); onDismiss() }) { Text(stringResource(R.string.douban_sync_complete)) }
                }
                p.isRunning -> {
                    Row {
                        TextButton(
                            enabled = !p.isCancelling,
                            onClick = {
                                buttonHaptics.tap()
                                if (DoubanSyncService.start(context)) {
                                    onBackground()
                                } else {
                                    onBackgroundUnavailable?.invoke()
                                }
                            }
                        ) { Text(stringResource(R.string.douban_sync_background)) }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            enabled = !p.isCancelling,
                            onClick = { buttonHaptics.lightTap(); viewModel.cancel() }
                        ) {
                            Text(
                                stringResource(
                                    if (p.isCancelling) R.string.douban_sync_cancelling
                                    else R.string.douban_sync_cancel
                                )
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun SyncSummaryRow(
    label: String,
    value: Int?,
    suffix: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            text = value?.toString() ?: suffix.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (value != null && suffix != null) {
        Text(
            suffix,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
