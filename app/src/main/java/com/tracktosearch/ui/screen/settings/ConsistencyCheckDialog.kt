package com.tracktosearch.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import com.tracktosearch.ui.component.AppDialogActionRow
import com.tracktosearch.ui.component.AppAlertDialog
import com.tracktosearch.ui.component.DialogAction
import com.tracktosearch.ui.haptic.rememberAppHaptics
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

    // 检查终态那一记触感，边沿检测的理由同 DoubanSyncDialog：进度不会立刻重置，
    // 弹窗重开的第一帧可能就是上一轮的 isComplete=true
    val outcomeHaptics = rememberAppHaptics()
    var observedComplete by remember { mutableStateOf(p.isComplete) }
    LaunchedEffect(p.isComplete) {
        val wasComplete = observedComplete
        observedComplete = p.isComplete
        if (!p.isComplete || wasComplete) return@LaunchedEffect
        if (p.isCancelled || p.isCancelling) return@LaunchedEffect
        // 这里刻意**不**把 conflictsFound > 0 算失败：查出冲突并改掉正是这个功能要干的事，
        // 修复数就记在 doubanUpdated/traktUpdated 上。真正的失败是登录态断了或 errors > 0
        val clean = !p.cookieExpired && !p.neverLoggedInDouban && p.errors == 0
        if (clean) outcomeHaptics.confirm() else outcomeHaptics.reject()
    }

    AppAlertDialog(
        onDismissRequest = {
            // 检查运行中不允许点击外部关闭（需点「转后台」或「取消」）
            if (!p.isRunning) onDismiss()
        },
        title = stringResource(R.string.consistency_check_title),
        // 按钮行随进度状态变化且非单一「确认/取消」语义（多分支多按钮），按迁移配方
        // 整体并入 content 末尾改用 AppDialogActionRow，原 confirmButton 槽废弃
        content = {
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

                // 按钮行（原 confirmButton 槽内容，触感由 AppDialogActionRow 统一负责）
                Spacer(modifier = Modifier.height(8.dp))
                when {
                    p.isRunning -> AppDialogActionRow(
                        primary = null,
                        secondary = listOf(
                            DialogAction(
                                label = stringResource(R.string.consistency_check_background),
                                // 正在取消时禁用"转后台":避免用户在取消过程中触发前台服务启动导致状态混乱
                                enabled = !p.isCancelling,
                                onClick = {
                                    if (ConsistencyCheckService.start(context)) onBackground()
                                    else onBackgroundUnavailable?.invoke()
                                }
                            ),
                            DialogAction(
                                label = stringResource(
                                    if (p.isCancelling) R.string.consistency_check_cancelling
                                    else R.string.consistency_check_cancel
                                ),
                                // 正在取消时禁用取消按钮避免重复调用 + 改文案为"正在取消..."
                                enabled = !p.isCancelling,
                                onClick = { viewModel.cancelConsistencyCheck() }
                            )
                        )
                    )
                    p.isComplete -> AppDialogActionRow(
                        // 「完成」是主推进按钮（填充在右），条件出现的入口均为次级文字按钮
                        primary = DialogAction(
                            label = stringResource(R.string.consistency_check_phase_done),
                            onClick = { onDismiss() }
                        ),
                        secondary = buildList {
                            if ((p.cookieExpired || p.neverLoggedInDouban) && onLogin != null) {
                                add(
                                    DialogAction(
                                        label = stringResource(R.string.consistency_check_login),
                                        onClick = { onLogin() }
                                    )
                                )
                            }
                            if (p.errors > 0 && onRetry != null) {
                                add(
                                    DialogAction(
                                        label = stringResource(R.string.consistency_check_retry),
                                        onClick = { onRetry() }
                                    )
                                )
                            }
                            if (p.conflictsFound > 0 && onViewConflicts != null) {
                                // 「查看冲突」是转去另一个页面的次级入口，不是本对话框的主操作
                                add(
                                    DialogAction(
                                        label = stringResource(R.string.consistency_check_view_conflicts),
                                        onClick = { onViewConflicts() }
                                    )
                                )
                            }
                        }
                    )
                    else -> {
                        // 初始状态：加载指示器与取消按钮同行；spinner 不是 DialogAction，
                        // 无法走 AppDialogActionRow，保守保留原 Row 结构（手写触感已删）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(16.dp).width(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { onDismiss() }) {
                                Text(stringResource(R.string.consistency_check_cancel))
                            }
                        }
                    }
                }
            }
        }
    )
}
