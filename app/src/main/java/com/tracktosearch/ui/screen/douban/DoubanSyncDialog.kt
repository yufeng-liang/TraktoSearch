package com.tracktosearch.ui.screen.douban

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.remote.douban.DelayType
import com.tracktosearch.data.repository.DoubanFailureExporter
import com.tracktosearch.data.repository.DoubanSyncFailure
import com.tracktosearch.data.repository.DoubanSyncLoginTarget
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.DoubanSyncStage
import com.tracktosearch.data.repository.labelRes
import com.tracktosearch.data.repository.FailureReason
import com.tracktosearch.service.DoubanSyncService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 豆瓣同步进度对话框 ViewModel:
 * - 暴露 DoubanSyncManager.progress 给 UI
 * - 提供启动同步、取消同步能力
 * - 提供导出失败项能力
 */
@HiltViewModel
class DoubanSyncViewModel @Inject constructor(
    val doubanSyncManager: DoubanSyncManager,
    val doubanFailureExporter: DoubanFailureExporter
) : ViewModel() {

    val progress = doubanSyncManager.progress

    fun cancel() {
        doubanSyncManager.cancel()
    }
}

/** 获取失败原因的本地化字符串资源 ID */
private fun FailureReason.localizedStringRes(): Int = when (this) {
    FailureReason.NO_IMDB_ID -> R.string.douban_failure_reason_no_imdb_id
    FailureReason.DETAIL_FETCH_FAILED -> R.string.douban_failure_reason_detail_fetch_failed
    FailureReason.TRAKT_NOT_FOUND -> R.string.douban_failure_reason_trakt_not_found
    FailureReason.TRAKT_WRITE_TIMEOUT -> R.string.douban_failure_reason_trakt_write_timeout
    FailureReason.TRAKT_WRITE_FAILED -> R.string.douban_failure_reason_trakt_write_failed
}

/**
 * 豆瓣同步进度对话框：
 * - 显示当前阶段、子阶段、进度条、预计剩余时间
 * - 「转后台」按钮启动前台服务并关闭对话框
 * - 「取消」按钮中断同步
 * - 完成后展示成功/跳过/缓存命中/失败计数
 * - 失败项 LazyColumn 滑动列表,按"可恢复/不可恢复"分组,支持折叠展开
 * - 顶部"导出失败项"按钮,生成 JSON 并触发 ShareSheet
 * - 实时显示当前正在处理的条目标题
 * - 失败时滚动展示最近 5 条失败
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DoubanSyncDialog(
    onDismiss: () -> Unit,
    onRelogin: (() -> Unit)? = null,
    /**
     * 「转后台」按钮回调:调用方应无条件隐藏弹窗(同步在 Application scope 继续运行)。
     * 与 onDismiss 分离是因为 onDismiss 在同步运行中会被调用方拦截(防止点击外部关闭),
     * 而「转后台」是用户主动要求隐藏弹窗,必须立即生效。
     */
    onBackground: () -> Unit = onDismiss,
    /** Trakt 未登录时的引导回调:跳转到 LoginScreen */
    onTraktLogin: (() -> Unit)? = null,
    viewModel: DoubanSyncViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val p = progress
    // 分享失败项 JSON 时 createChooser 的标题(预解析,避免在 lambda 内调用 stringResource)
    val failedExportChooserTitle = stringResource(R.string.douban_sync_failed_export_chooser_title)

    // 延时倒计时(豆瓣反爬/列表页/重试等待):基于 delayInfo.startMs + totalSeconds 每秒刷新剩余秒数
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

    // 失败项分组折叠状态(默认可恢复展开,不可恢复展开)
    var recoverableExpanded by remember { mutableStateOf(true) }
    var nonRecoverableExpanded by remember { mutableStateOf(true) }
    // 二级分组(按失败原因)折叠状态
    // 不可恢复原因(NO_IMDB_ID/TRAKT_NOT_FOUND)默认折叠
    val defaultSubExpanded = remember {
        FailureReason.entries.associateWith { reason ->
            reason !in setOf(FailureReason.NO_IMDB_ID, FailureReason.TRAKT_NOT_FOUND)
        }
    }
    var subExpandedMap by remember { mutableStateOf(defaultSubExpanded) }
    // null=未导出,true=成功,false=失败
    var exportResult by remember { mutableStateOf<Boolean?>(null) }
    var exportedCount by remember { mutableStateOf(0) }

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
                // 主进度:阶段 (current/total) - 完成时只显示 phase 避免出现 0/0
                if (p.isComplete) {
                    Text(stageLabel, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.douban_sync_progress_format, stageLabel, p.current, p.total))
                }
                // 子阶段(详情页 / Trakt 查询 / 批量同步 / 断点续传跳过)
                if (subStageLabel != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        subStageLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 当前正在处理的条目标题(进度详情)
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
                // 延时倒计时(豆瓣反爬/列表页/重试等待)
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

                // 最近失败(同步进行中,实时滚动展示)
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

                if (p.isRunning && p.recentFailures.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.douban_sync_recent_failures),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    p.recentFailures.forEach { failure ->
                        Text(
                            "✗ ${failure.title}：${stringResource(failure.failureReason.localizedStringRes())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                    // 完成总结:成功·跳过·缓存命中·失败
                    Text(
                        stringResource(
                            R.string.douban_sync_summary_format,
                            p.successCount, p.skippedCount, p.cacheHitCount, p.failedCount
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    // 失败项分组展示(可恢复 + 不可恢复)
                    if (p.failedItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))

                        // 导出按钮 + 失败项标题
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.douban_sync_failed_list_label),
                                style = MaterialTheme.typography.labelMedium
                            )
                            AssistChip(
                                onClick = {
                                    scope.launch {
                                        val uri = viewModel.doubanFailureExporter
                                            .exportFromList(context, p.failedItems)
                                        if (uri != null) {
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/json"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, failedExportChooserTitle))
                                            exportResult = true
                                            exportedCount = p.failedItems.size
                                        } else {
                                            exportResult = false
                                        }
                                    }
                                },
                                label = {
                                    Text(
                                        stringResource(R.string.douban_sync_failed_export),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.FileDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            )
                        }

                        // 导出状态提示
                        exportResult?.let { success ->
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                if (success) stringResource(R.string.douban_sync_failed_export_success, exportedCount)
                                else stringResource(R.string.douban_sync_failed_export_failed),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (success) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                        }

                        // 失败项列表(双层吸顶分组:一级可恢复/不可恢复,二级按失败原因)
                        val recoverable = p.failedItems.filter { it.failureReason.recoverable }
                        val nonRecoverable = p.failedItems.filter { !it.failureReason.recoverable }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                        ) {
                            // 一级分组:可恢复
                            if (recoverable.isNotEmpty()) {
                                stickyHeader(key = "recoverable_header") {
                                    FailureGroupHeader(
                                        title = stringResource(
                                            R.string.douban_sync_failed_group_recoverable,
                                            recoverable.size
                                        ),
                                        expanded = recoverableExpanded,
                                        onClick = { recoverableExpanded = !recoverableExpanded },
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                                if (recoverableExpanded) {
                                    val byReason = recoverable.groupBy { it.failureReason }
                                    for ((reason, rItems) in byReason) {
                                        stickyHeader(key = "recoverable_${reason.name}") {
                                            FailureSubGroupHeader(
                                                title = stringResource(reason.localizedStringRes()),
                                                count = rItems.size,
                                                expanded = subExpandedMap[reason] ?: true,
                                                onClick = {
                                                    subExpandedMap = subExpandedMap.toMutableMap().apply {
                                                        this[reason] = !(this[reason] ?: true)
                                                    }
                                                }
                                            )
                                        }
                                        if (subExpandedMap[reason] ?: true) {
                                            items(rItems, key = { "rec_${it.doubanId}" }) { failure ->
                                                FailureItemRow(failure)
                                            }
                                        }
                                    }
                                }
                            }
                            // 一级分组:不可恢复
                            if (nonRecoverable.isNotEmpty()) {
                                stickyHeader(key = "non_recoverable_header") {
                                    if (recoverable.isNotEmpty()) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                                    }
                                    FailureGroupHeader(
                                        title = stringResource(
                                            R.string.douban_sync_failed_group_non_recoverable,
                                            nonRecoverable.size
                                        ),
                                        expanded = nonRecoverableExpanded,
                                        onClick = { nonRecoverableExpanded = !nonRecoverableExpanded },
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                }
                                if (nonRecoverableExpanded) {
                                    val byReason = nonRecoverable.groupBy { it.failureReason }
                                    for ((reason, rItems) in byReason) {
                                        stickyHeader(key = "non_rec_${reason.name}") {
                                            FailureSubGroupHeader(
                                                title = stringResource(reason.localizedStringRes()),
                                                count = rItems.size,
                                                expanded = subExpandedMap[reason] ?: true,
                                                onClick = {
                                                    subExpandedMap = subExpandedMap.toMutableMap().apply {
                                                        this[reason] = !(this[reason] ?: true)
                                                    }
                                                }
                                            )
                                        }
                                        if (subExpandedMap[reason] ?: true) {
                                            items(rItems, key = { "nonrec_${it.doubanId}" }) { failure ->
                                                FailureItemRow(failure)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                // Trakt 未登录(DoubanSyncManager 预检设置 phase="未登录 Trakt,请先登录")
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
                // 豆瓣未登录(DoubanSyncManager 预检设置 phase="未登录豆瓣")
                // 与 cookieExpired 分开:cookieExpired 是登录后过期,这里是从未登录
                // 两者都跳转豆瓣登录页,但按钮文案不同(登录 vs 重新登录)
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
                            // 正在取消时禁用"转后台":避免用户在取消过程中触发前台服务启动导致状态混乱
                            enabled = !p.isCancelling,
                            onClick = {
                                if (DoubanSyncService.start(context)) {
                                    onBackground()
                                }
                            }
                        ) { Text(stringResource(R.string.douban_sync_background)) }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            // 正在取消时禁用取消按钮避免重复调用 + 改文案为"正在取消..."
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

/** 失败项分组标题(可折叠) */
@Composable
private fun FailureGroupHeader(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = tint
        )
    }
}

/** 失败项二级分组标题(按失败原因细分,可折叠,吸顶) */
@Composable
private fun FailureSubGroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 24.dp, top = 4.dp, bottom = 4.dp, end = 8.dp)
        ) {
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "$title ($count)",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 单条失败项展示 */
@Composable
private fun FailureItemRow(failure: DoubanSyncFailure) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Text(
            "•",
            style = MaterialTheme.typography.bodySmall,
            color = if (failure.failureReason.recoverable)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                failure.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(failure.failureReason.localizedStringRes()),
                style = MaterialTheme.typography.labelSmall,
                color = if (failure.failureReason.recoverable)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.outline
            )
            // 重试次数 >= 3 时标记
            if (failure.attemptCount >= 3) {
                Text(
                    stringResource(R.string.douban_retry_max_attempt, failure.attemptCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}
