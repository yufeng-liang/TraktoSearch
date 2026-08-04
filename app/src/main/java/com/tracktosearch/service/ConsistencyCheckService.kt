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
import com.tracktosearch.data.repository.ConsistencyCheckResult
import com.tracktosearch.data.repository.DoubanTraktStatusConsistencyChecker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 只负责把一致性检查的实时状态映射到系统通知，检查本身由 Checker 的应用级协程执行。 */
@AndroidEntryPoint
class ConsistencyCheckService : Service() {

    @Inject lateinit var consistencyChecker: DoubanTraktStatusConsistencyChecker

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var progressJob: kotlinx.coroutines.Job? = null

    companion object {
        const val CHANNEL_ID = "consistency_check"
        const val NOTIF_ID = 9002
        const val ACTION_START = "com.tracktosearch.START_CONSISTENCY_CHECK"
        const val ACTION_CANCEL = "com.tracktosearch.CANCEL_CONSISTENCY_CHECK"

        /** 返回 false 表示通知权限不可用或前台服务未能启动，调用方应保留前台对话框。 */
        fun start(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) return false
            }
            val intent = Intent(context, ConsistencyCheckService::class.java).setAction(ACTION_START)
            return runCatching { context.startForegroundService(intent) }.isSuccess
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ConsistencyCheckService::class.java).setAction(ACTION_CANCEL)
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
                // 让 Checker 发布最终的 cancelled 状态后再停止，避免通知停在“正在取消”。
                consistencyChecker.cancel()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                try {
                    val initial = consistencyChecker.checkProgress.value.let { current ->
                        if (current.isRunning || current.isCancelling || current.isComplete) {
                            current
                        } else {
                            current.copy(
                                isRunning = true,
                                phase = getString(R.string.consistency_check_phase_preparing),
                                startTimeMs = System.currentTimeMillis()
                            )
                        }
                    }
                    val notification = buildNotification(initial)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIF_ID, notification)
                    }
                } catch (_: Exception) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startProgressCollection()
            }
        }
        return START_NOT_STICKY
    }

    private fun startProgressCollection() {
        progressJob?.cancel()
        progressJob = scope.launch {
            consistencyChecker.checkProgress.collectLatest { progress ->
                when {
                    progress.isComplete -> {
                        publishNotification(progress)
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                        return@collectLatest
                    }

                    progress.isRunning || progress.isCancelling -> publishNotification(progress)
                }
            }
        }
    }

    private fun publishNotification(progress: ConsistencyCheckResult) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(progress))
    }

    private fun buildNotification(progress: ConsistencyCheckResult): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIF_ID,
            Intent(this, MainActivity::class.java).putExtra("navigate_to", "consistency_check"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this,
            NOTIF_ID + 1,
            Intent(this, ConsistencyCheckService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val phase = progress.phase.ifBlank { getString(R.string.consistency_check_phase_preparing) }
        val subPhase = progress.subPhase
            .takeIf { it.isNotBlank() && !phase.contains(it) }
        val contentText = when {
            progress.isComplete && progress.isCancelled ->
                getString(R.string.consistency_check_phase_cancelled)
            progress.isComplete -> getString(
                R.string.consistency_check_summary,
                progress.totalChecked,
                progress.conflictsFound,
                progress.traktUpdated,
                progress.doubanUpdated,
                progress.errors
            )
            progress.total > 0 && subPhase != null -> getString(
                R.string.consistency_check_notification_progress_with_subphase,
                phase,
                subPhase,
                progress.current,
                progress.total
            )
            progress.total > 0 -> getString(
                R.string.consistency_check_notification_progress,
                phase,
                progress.current,
                progress.total
            )
            subPhase != null -> getString(
                R.string.consistency_check_notification_stage_with_subphase,
                phase,
                subPhase
            )
            else -> phase
        }
        val expandedParts = buildList {
            progress.currentTitle?.takeIf { it.isNotBlank() }?.let {
                add(getString(R.string.consistency_check_notification_current, it))
            }
            if (progress.isRunning && progress.current > 0 && progress.total > progress.current && progress.startTimeMs > 0) {
                val elapsed = ((System.currentTimeMillis() - progress.startTimeMs) / 1000).coerceAtLeast(1)
                val eta = elapsed * (progress.total - progress.current) / progress.current
                add(getString(R.string.consistency_check_notification_eta, formatEta(eta)))
            }
        }

        return LiveUpdateNotificationBuilder.build(
            context = this,
            channelId = CHANNEL_ID,
            title = getString(R.string.consistency_check_title),
            phase = phase,
            current = progress.current,
            total = progress.total,
            contentIntent = contentIntent,
            cancelIntent = cancelIntent,
            cancelText = getString(R.string.consistency_check_cancel),
            contentText = contentText,
            expandedText = expandedParts.joinToString(" | ").ifBlank { contentText },
            publicText = getString(R.string.consistency_check_notification_public, phase),
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
            CHANNEL_ID,
            getString(R.string.consistency_check_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.consistency_check_channel_desc)
            setLockscreenVisibility(Notification.VISIBILITY_PRIVATE)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        progressJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
