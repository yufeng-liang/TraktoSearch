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
import com.tracktosearch.data.repository.labelRes
import com.tracktosearch.service.DoubanSyncService
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
    onTraktLogin: (() -> Unit)? = null,
    viewModel: DoubanSyncViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val p = progress

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

    AlertDialog(
        onDismissRequest = {
            if (!p.isRunning) onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.douban_sync_title)) },
        text = {
            Column {
                val stageLabel = stringResource(p.stage.labelRes())
                val subStageLabel = p.subStage.labelRes()?.let { stringResource(it) }
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

                if (p.isComplete) {
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
                if (p.total > 0) {
                    LinearProgressIndicator(
                        progress = { (p.current.toFloat() / p.total).coerceIn(0f, 1f) },
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

                if (p.recentItems.isNotEmpty()) {
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

                if (p.isComplete) {
                    Spacer(modifier = Modifier.height(12.dp))
                    p.errorMessage?.takeIf { it.isNotBlank() }?.let { errorMessage ->
                        Text(
                            stringResource(R.string.douban_sync_error_detail, errorMessage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        stringResource(
                            R.string.douban_sync_summary_format,
                            p.successCount,
                            p.skippedCount,
                            p.cacheHitCount
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            when {
                p.isComplete &&
                    p.stage == DoubanSyncStage.LOGIN_REQUIRED &&
                    p.loginTarget == DoubanSyncLoginTarget.TRAKT -> {
                    Row {
                        if (onTraktLogin != null) {
                            TextButton(onClick = {
                                onDismiss()
                                onTraktLogin()
                            }) { Text(stringResource(R.string.douban_sync_login_trakt)) }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.douban_sync_complete)) }
                    }
                }
                p.isComplete &&
                    p.stage == DoubanSyncStage.LOGIN_REQUIRED &&
                    p.loginTarget == DoubanSyncLoginTarget.DOUBAN &&
                    !p.cookieExpired -> {
                    Row {
                        if (onRelogin != null) {
                            TextButton(onClick = {
                                onDismiss()
                                onRelogin()
                            }) { Text(stringResource(R.string.douban_sync_login_douban)) }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.douban_sync_complete)) }
                    }
                }
                p.isComplete &&
                    p.stage == DoubanSyncStage.LOGIN_REQUIRED &&
                    p.loginTarget == DoubanSyncLoginTarget.DOUBAN &&
                    p.cookieExpired -> {
                    Row {
                        if (onRelogin != null) {
                            TextButton(onClick = {
                                onDismiss()
                                onRelogin()
                            }) { Text(stringResource(R.string.douban_sync_relogin)) }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.douban_sync_complete)) }
                    }
                }
                p.isComplete -> {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.douban_sync_complete)) }
                }
                p.isRunning -> {
                    Row {
                        TextButton(
                            enabled = !p.isCancelling,
                            onClick = {
                                if (DoubanSyncService.start(context)) {
                                    onBackground()
                                }
                            }
                        ) { Text(stringResource(R.string.douban_sync_background)) }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            enabled = !p.isCancelling,
                            onClick = { viewModel.cancel() }
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
