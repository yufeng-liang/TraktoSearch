package com.tracktosearch.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.remote.douban.DelayType
import com.tracktosearch.service.ConsistencyCheckService
import kotlinx.coroutines.delay

internal fun consistencySubPhaseForDisplay(phase: String, subPhase: String): String? {
    val candidate = subPhase.trim()
    return candidate.takeIf { it.isNotEmpty() && !phase.contains(it) }
}

/**
 * 状态一致性检查进度弹窗。
 *
 * 参考 DoubanSyncDialog 简化版：
 * - 阶段标题 + 进度条 + 当前条目标题 + 延时倒计时
 * - 进行中：「转后台」+「取消」
 * - 完成时：结果统计 +「完成」
 */
@Composable
fun ConsistencyCheckDialog(
    onDismiss: () -> Unit,
    onBackground: () -> Unit,
    onLogin: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onViewConflicts: (() -> Unit)? = null,
    onBackgroundUnavailable: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val progress by viewModel.checkProgress.collectAsStateWithLifecycle()
    val p = progress
    val context = LocalContext.current

    // 延时倒计时（参考 DoubanSyncDialog 的实现）
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
            // 检查运行中不允许点击外部关闭（需点「转后台」或「取消」）
            if (!p.isRunning) onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Text(text = stringResource(R.string.consistency_check_title))
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 主进度（阶段 + current/total）
                val displaySubPhase = consistencySubPhaseForDisplay(p.phase, p.subPhase)
                if (p.isRunning) {
                    val phaseText = if (p.total > 0) {
                        stringResource(
                            R.string.consistency_check_notification_progress,
                            p.phase,
                            p.current,
                            p.total
                        )
                    } else {
                        p.phase.ifEmpty { stringResource(R.string.consistency_check_phase_preparing) }
                    }
                    Text(
                        text = phaseText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else if (p.isComplete) {
                    Text(
                        text = p.phase.ifEmpty { stringResource(R.string.consistency_check_phase_done) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // 子阶段
                if (displaySubPhase != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = displaySubPhase,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 当前条目标题
                if (p.isRunning && !p.currentTitle.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = p.currentTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 进度条
                if (p.isRunning) {
                    Spacer(modifier = Modifier.height(8.dp))
                    if (p.total > 0) {
                        LinearProgressIndicator(
                            progress = { (p.current.toFloat() / p.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                // 延时倒计时
                if (p.isRunning && p.delayInfo != null && delayRemainingSeconds > 0) {
                    // CC-L03: 用本地化字符串映射 DelayType,替代直接显示英文 displayKey
                    val info = p.delayInfo
                    val delayTypeText = when (info.type) {
                        DelayType.DOUBAN_DETAIL_CRAWL -> stringResource(R.string.delay_type_douban_detail_crawl)
                        DelayType.DOUBAN_LIST_CRAWL -> stringResource(R.string.delay_type_douban_list_crawl)
                        DelayType.DOUBAN_RETRY -> stringResource(R.string.delay_type_douban_retry)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.douban_sync_delay_format,
                            delayRemainingSeconds,
                            delayTypeText
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                // Cookie 过期提示(登录后过期)或未登录豆瓣提示
                if (p.cookieExpired) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.consistency_check_cookie_expired_prompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (p.neverLoggedInDouban) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.consistency_check_not_logged_in_prompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // 完成时结果统计
                if (p.isComplete && p.totalChecked > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.consistency_check_summary,
                            p.totalChecked,
                            p.conflictsFound,
                            p.traktUpdated,
                            p.doubanUpdated,
                            p.errors
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            when {
                p.isRunning -> {
                    Row {
                        TextButton(
                            // 正在取消时禁用"转后台":避免用户在取消过程中触发前台服务启动导致状态混乱
                            enabled = !p.isCancelling,
                            onClick = {
                                if (ConsistencyCheckService.start(context)) onBackground()
                                else onBackgroundUnavailable?.invoke()
                            }
                        ) {
                            Text(stringResource(R.string.consistency_check_background))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            // 正在取消时禁用取消按钮避免重复调用 + 改文案为"正在取消..."
                            enabled = !p.isCancelling,
                            onClick = {
                                viewModel.cancelConsistencyCheck()
                            }
                        ) {
                            Text(
                                stringResource(
                                    if (p.isCancelling) R.string.consistency_check_cancelling
                                    else R.string.consistency_check_cancel
                                )
                            )
                        }
                    }
                }
                p.isComplete -> {
                    Row {
                        if ((p.cookieExpired || p.neverLoggedInDouban) && onLogin != null) {
                            TextButton(onClick = onLogin) { Text(stringResource(R.string.consistency_check_login)) }
                        }
                        if (p.errors > 0 && onRetry != null) {
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.consistency_check_retry)) }
                        }
                        if (p.conflictsFound > 0 && onViewConflicts != null) {
                            TextButton(onClick = onViewConflicts) { Text(stringResource(R.string.consistency_check_view_conflicts)) }
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.consistency_check_phase_done))
                        }
                    }
                }
                else -> {
                    // 初始状态：显示加载指示器
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp).width(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.consistency_check_cancel))
                        }
                    }
                }
            }
        }
    )
}
