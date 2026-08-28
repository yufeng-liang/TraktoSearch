package com.tracktosearch.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.tracktosearch.MainActivity
import com.tracktosearch.R
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.repository.DoubanSyncLoginTarget
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.DoubanSyncProgress
import com.tracktosearch.data.repository.DoubanSyncStage
import com.tracktosearch.data.repository.DoubanSyncSubStage
import com.tracktosearch.data.repository.compactLabelRes
import com.tracktosearch.data.repository.labelRes
import com.tracktosearch.data.repository.secondaryLabelRes
import com.tracktosearch.ui.util.toUserMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 豆瓣标记同步通知服务：
 * - 只负责显示通知栏进度，不启动同步（同步在 DoubanSyncManager 内部的 Application scope 跑）
 * - 同步完成或取消后自动 stopSelf
 * - 通知带「取消」Action，用户可中断同步
 *
 * 注意：Android 14+ 要求 dataSync 类型前台服务声明 FOREGROUND_SERVICE_DATA_SYNC 权限，
 * 且后台启动前台服务受限。本 Service 仅在用户主动点击「转后台」时（App 在前台）启动。
 */
@AndroidEntryPoint
class DoubanSyncService : Service() {

    @Inject lateinit var doubanSyncManager: DoubanSyncManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 进度收集协程 Job，防止多次 start() 启动多个收集协程 */
    private var progressJob: kotlinx.coroutines.Job? = null

    companion object {
        const val CHANNEL_ID = "douban_sync"
        const val NOTIF_ID = 9001
        const val ACTION_START = "com.tracktosearch.START_DOUBAN_SYNC"
        const val ACTION_CANCEL = "com.tracktosearch.CANCEL_DOUBAN_SYNC"

        fun start(context: Context): Boolean {
            // 通知权限未授予时不启动 Service（同步仍在 Application scope 跑，只是没通知）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) return false
            }
            val intent = Intent(context, DoubanSyncService::class.java).setAction(ACTION_START)
            return runCatching {
                context.startForegroundService(intent)
            }.isSuccess
        }

        fun cancel(context: Context) {
            val intent = Intent(context, DoubanSyncService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                doubanSyncManager.cancel()
                // 取消是异步过程，保留 Service 直到 Manager 发布最终状态。
                runCatching { publishNotification(doubanSyncManager.progress.value) }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // 启动前台通知（Android 14+ 需要指定 foregroundServiceType）
                // 用 try-catch 兜底：即使 FGS 启动失败（权限缺失或后台限制），也不崩溃，
                // 同步仍在 DoubanSyncManager 的 Application scope 中继续运行。
                try {
                    val currentProgress = doubanSyncManager.progress.value
                    val initialProgress = if (
                        currentProgress.stage == DoubanSyncStage.IDLE &&
                        !currentProgress.isRunning &&
                        !currentProgress.isComplete
                    ) {
                        currentProgress.copy(
                            isRunning = true,
                            stage = DoubanSyncStage.PREPARING,
                            subStage = DoubanSyncSubStage.CONNECTING
                        )
                    } else {
                        currentProgress
                    }
                    val notif = buildNotification(initialProgress)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIF_ID, notif)
                    }
                } catch (e: Exception) {
                    // FGS 启动失败，直接停止 Service，同步不受影响
                    stopSelf()
                    return START_NOT_STICKY
                }
                startProgressCollection()
            }
        }
        // 与 ConsistencyCheckService/DoubanBatchRemovalService 一致用 START_NOT_STICKY：
        // START_STICKY 下系统粘性重启时 intent 为 null 不会进前台分支，由
        // startForegroundService 启动的服务重启 5 秒内未调 startForeground 会抛
        // ForegroundServiceDidNotStartInTimeException 崩溃；同步本体在 Application
        // scope 运行，Service 仅是通知载体，系统杀进程后不重启通知无碍。
        return START_NOT_STICKY
    }

    private fun startProgressCollection() {
        // 先取消旧协程，防止多次 start() 启动多个收集协程导致通知重复更新。
        progressJob?.cancel()
        progressJob = scope.launch {
            doubanSyncManager.progress.collectLatest { progress: DoubanSyncProgress ->
                if (progress.isComplete) {
                    // 先发布完成/失败通知，再解除前台服务，确保终态不会丢失。
                    publishNotification(progress)
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                    return@collectLatest
                }
                if (progress.isRunning || progress.isCancelling) {
                    publishNotification(progress)
                }
            }
        }
    }

    private fun publishNotification(progress: DoubanSyncProgress) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(progress))
    }

    /** 构建通知：状态栏使用短阶段名，通知内容补充子阶段、数量、ETA 和当前条目。 */
    private fun buildNotification(progress: DoubanSyncProgress): Notification =
        buildDoubanSyncNotification(this, progress)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.douban_sync_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.douban_sync_notification_channel_description)
            setLockscreenVisibility(Notification.VISIBILITY_PRIVATE)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

internal fun buildDoubanSyncNotification(context: Context, progress: DoubanSyncProgress): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra("navigate_to", "douban_sync"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            context, 1,
            Intent(context, DoubanSyncService::class.java).setAction(DoubanSyncService.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stageLabel = context.getString(progress.stage.labelRes())
        val compactLabel = context.getString(progress.stage.compactLabelRes())
        val subStageLabel = progress.subStage.secondaryLabelRes()?.let(context::getString)
        val targetLabel = when (progress.subStage) {
            DoubanSyncSubStage.FETCHING_WISH_LIST -> context.getString(R.string.douban_sync_preview_status_wish)
            DoubanSyncSubStage.FETCHING_COLLECT_LIST -> context.getString(R.string.douban_sync_preview_status_collect)
            else -> null
        }
        val isUploading = progress.stage == DoubanSyncStage.UPLOADING
        val isBatchWrite = progress.subStage == DoubanSyncSubStage.WRITING_TARGET ||
            progress.subStage == DoubanSyncSubStage.WRITING_LOCAL
        val hasTerminalFailure = progress.failedCount > 0 || !progress.errorMessage.isNullOrBlank()
        val contentText = when {
            progress.cookieExpired -> context.getString(R.string.douban_sync_cookie_expired_banner)
            progress.isComplete && progress.stage == DoubanSyncStage.LOGIN_REQUIRED -> when (progress.loginTarget) {
                DoubanSyncLoginTarget.DOUBAN -> context.getString(R.string.douban_sync_douban_login_required_banner)
                DoubanSyncLoginTarget.TRAKT -> context.getString(R.string.douban_sync_trakt_login_required_banner)
                null -> stageLabel
            }
            progress.isComplete && progress.stage == DoubanSyncStage.CANCELLING ->
                if (progress.pendingItemCount > 0) {
                    context.getString(R.string.douban_sync_cancelled_with_pending, progress.pendingItemCount)
                } else {
                    context.getString(R.string.douban_sync_cancelled_banner)
                }
            progress.isComplete && hasTerminalFailure -> context.getString(
                R.string.douban_sync_summary_with_failures,
                progress.successCount,
                progress.skippedCount,
                progress.cacheHitCount,
                progress.failedCount
            )
            progress.isComplete && progress.stage == DoubanSyncStage.COMPLETED ->
                context.getString(
                    R.string.douban_sync_summary_format,
                    progress.successCount,
                    progress.skippedCount,
                    progress.cacheHitCount
                )
            progress.isComplete && progress.stage == DoubanSyncStage.FAILED ->
                context.getString(R.string.douban_sync_stage_failed)
            isUploading && subStageLabel != null -> context.getString(
                R.string.douban_sync_notification_stage_format,
                stageLabel,
                subStageLabel
            )
            isUploading -> stageLabel
            isBatchWrite && subStageLabel != null -> context.getString(
                R.string.douban_sync_notification_stage_format,
                stageLabel,
                subStageLabel
            )
            progress.total > 0 && targetLabel != null -> context.getString(
                R.string.douban_sync_notification_progress_format,
                stageLabel,
                targetLabel,
                progress.current,
                progress.total
            )
            progress.total > 0 && subStageLabel != null -> context.getString(
                R.string.douban_sync_notification_progress_format,
                stageLabel,
                subStageLabel,
                progress.current,
                progress.total
            )
            progress.total > 0 -> context.getString(
                R.string.douban_sync_progress_format,
                stageLabel,
                progress.current,
                progress.total
            )
            subStageLabel != null -> context.getString(
                R.string.douban_sync_notification_stage_format,
                stageLabel,
                subStageLabel
            )
            else -> stageLabel
        }
        val liveTitle = if (progress.isRunning || progress.isCancelling) {
            progress.currentTitle?.takeIf { it.isNotBlank() }
                ?: progress.processingItems.firstOrNull()?.title?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val historicalTitle = if (!progress.isRunning && !progress.isCancelling) {
            progress.recentItems.firstOrNull()?.title?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val expandedParts = buildList {
            liveTitle?.let {
                add(context.getString(R.string.douban_sync_notification_current, it))
            }
            historicalTitle?.let {
                add(context.getString(R.string.douban_sync_notification_latest, it))
            }
            if (progress.isRunning && progress.etaSeconds >= 0) {
                add(context.getString(R.string.douban_sync_eta_format, formatDoubanSyncEta(context, progress.etaSeconds)))
            }
            progress.errorMessage?.takeIf { it.isNotBlank() }?.let {
                // errorMessage 为原始异常消息，先映射为友好本地化文案再展示
                add(context.getString(R.string.douban_sync_error_detail, Exception(it).toUserMessage(context, R.string.error_operation_failed)))
            }
        }
        val expandedText = expandedParts.joinToString(" · ").ifBlank { contentText }

        return LiveUpdateNotificationBuilder.build(
            context = context,
            channelId = DoubanSyncService.CHANNEL_ID,
            title = context.getString(R.string.douban_sync_title),
            phase = compactLabel,
            current = if (isUploading || isBatchWrite) 0 else progress.current,
            total = if (isUploading || isBatchWrite) 0 else progress.total,
            contentIntent = contentIntent,
            cancelIntent = cancelIntent,
            cancelText = context.getString(R.string.douban_sync_cancel),
            contentText = contentText,
            expandedText = expandedText,
            publicText = context.getString(R.string.douban_sync_notification_public, stageLabel),
            terminalActionText = if (progress.isComplete) context.getString(R.string.douban_sync_complete) else null,
            isTerminal = progress.isComplete
        )
}

private fun formatDoubanSyncEta(context: Context, seconds: Long): String = when {
    seconds < 60 -> context.getString(R.string.douban_sync_eta_seconds, seconds)
    seconds < 3600 -> context.getString(R.string.douban_sync_eta_minutes, seconds / 60)
    else -> context.getString(R.string.douban_sync_eta_hours, seconds / 3600, (seconds % 3600) / 60)
}
