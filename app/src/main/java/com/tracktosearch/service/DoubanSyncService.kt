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
            context.startForegroundService(intent)
            return true
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
        // START_STICKY:系统杀进程后可重启 Service 恢复通知(同步仍在 Application scope 继续运行)
        return START_STICKY
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
    private fun buildNotification(progress: DoubanSyncProgress): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra("navigate_to", "douban_sync"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DoubanSyncService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stageLabel = getString(progress.stage.labelRes())
        val compactLabel = getString(progress.stage.compactLabelRes())
        val subStageLabel = progress.subStage.secondaryLabelRes()?.let(::getString)
        val targetLabel = when (progress.subStage) {
            DoubanSyncSubStage.FETCHING_WISH_LIST -> getString(R.string.douban_sync_preview_status_wish)
            DoubanSyncSubStage.FETCHING_COLLECT_LIST -> getString(R.string.douban_sync_preview_status_collect)
            else -> null
        }
        val contentText = when {
            progress.cookieExpired -> getString(R.string.douban_sync_cookie_expired_banner)
            progress.isComplete && progress.stage == DoubanSyncStage.LOGIN_REQUIRED -> when (progress.loginTarget) {
                DoubanSyncLoginTarget.DOUBAN -> getString(R.string.douban_sync_douban_login_required_banner)
                DoubanSyncLoginTarget.TRAKT -> getString(R.string.douban_sync_trakt_login_required_banner)
                null -> stageLabel
            }
            progress.isComplete && progress.stage == DoubanSyncStage.COMPLETED ->
                getString(R.string.douban_sync_result_format, progress.successCount, progress.failedCount)
            progress.isComplete && progress.stage == DoubanSyncStage.FAILED ->
                getString(R.string.douban_sync_failed_banner, progress.failedCount)
            progress.isComplete && progress.stage == DoubanSyncStage.CANCELLING ->
                getString(R.string.douban_sync_cancelled_banner)
            progress.total > 0 && targetLabel != null -> getString(
                R.string.douban_sync_notification_progress_format,
                stageLabel,
                targetLabel,
                progress.current,
                progress.total
            )
            progress.total > 0 && subStageLabel != null -> getString(
                R.string.douban_sync_notification_progress_format,
                stageLabel,
                subStageLabel,
                progress.current,
                progress.total
            )
            progress.total > 0 -> getString(
                R.string.douban_sync_progress_format,
                stageLabel,
                progress.current,
                progress.total
            )
            subStageLabel != null -> getString(
                R.string.douban_sync_notification_stage_format,
                stageLabel,
                subStageLabel
            )
            else -> stageLabel
        }
        val expandedParts = buildList {
            progress.currentTitle?.takeIf { it.isNotBlank() }?.let {
                add(getString(R.string.douban_sync_notification_current, it))
            } ?: progress.recentItems.firstOrNull()?.title?.takeIf { it.isNotBlank() }?.let {
                add(getString(R.string.douban_sync_notification_latest, it))
            }
            if (progress.isRunning && progress.etaSeconds >= 0) {
                add(getString(R.string.douban_sync_eta_format, formatEta(progress.etaSeconds)))
            }
            progress.errorMessage?.takeIf { it.isNotBlank() }?.let {
                add(getString(R.string.douban_sync_error_detail, it))
            }
        }
        val expandedText = expandedParts.joinToString(" · ").ifBlank { contentText }

        return LiveUpdateNotificationBuilder.build(
            context = this,
            channelId = CHANNEL_ID,
            title = getString(R.string.douban_sync_title),
            phase = compactLabel,
            current = progress.current,
            total = progress.total,
            contentIntent = contentIntent,
            cancelIntent = cancelIntent,
            cancelText = getString(R.string.douban_sync_cancel),
            contentText = contentText,
            expandedText = expandedText,
            publicText = getString(R.string.douban_sync_notification_public, stageLabel),
            isTerminal = progress.isComplete
        )
    }

    private fun formatEta(seconds: Long): String = when {
        seconds < 60 -> getString(R.string.douban_sync_eta_seconds, seconds)
        seconds < 3600 -> getString(R.string.douban_sync_eta_minutes, seconds / 60)
        else -> getString(R.string.douban_sync_eta_hours, seconds / 3600, (seconds % 3600) / 60)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.douban_sync_title), NotificationManager.IMPORTANCE_LOW
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
