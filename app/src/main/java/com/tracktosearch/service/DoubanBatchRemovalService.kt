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
import com.tracktosearch.data.repository.BatchRemovalPhase
import com.tracktosearch.data.repository.BatchRemovalProgress
import com.tracktosearch.data.repository.DoubanBatchRemovalManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 豆瓣标记批量移除通知服务：
 * - 只负责显示通知栏进度，不启动移除（移除在 [DoubanBatchRemovalManager] 的 Application scope 跑）
 * - 移除完成或取消后自动 stopSelf
 * - 通知带「取消」Action，用户可中断移除
 *
 * 仅在用户触发多选移除时（App 在前台）启动。
 */
@AndroidEntryPoint
class DoubanBatchRemovalService : Service() {

    @Inject lateinit var batchRemovalManager: DoubanBatchRemovalManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var progressJob: kotlinx.coroutines.Job? = null

    companion object {
        const val CHANNEL_ID = "douban_batch_removal"
        const val NOTIF_ID = 9003
        const val ACTION_START = "com.tracktosearch.START_DOUBAN_BATCH_REMOVAL"
        const val ACTION_CANCEL = "com.tracktosearch.CANCEL_DOUBAN_BATCH_REMOVAL"

        fun start(context: Context) {
            // 通知权限未授予时不启动 Service（移除仍在 Application scope 跑，只是没通知）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            val intent = Intent(context, DoubanBatchRemovalService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                batchRemovalManager.cancel()
                try {
                    val cancellingNotif = buildNotification(
                        batchRemovalManager.progress.value.current,
                        batchRemovalManager.progress.value.total,
                        BatchRemovalPhase.CANCELLING
                    )
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, cancellingNotif)
                } catch (_: Exception) {}
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                try {
                    // 初始通知用"准备中"文案,后续 collectLatest 会用 Manager 的 phase 替换
                    val notif = buildNotification(0, 0, BatchRemovalPhase.REMOVING)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIF_ID, notif)
                    }
                    // WakeLock 由 DoubanBatchRemovalManager 统一管理
                } catch (e: Exception) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                progressJob?.cancel()
                progressJob = scope.launch {
                    batchRemovalManager.progress.collectLatest { p: BatchRemovalProgress ->
                        if (p.isComplete) {
                            stopSelf()
                            return@collectLatest
                        }
                        if (p.isRunning || p.isCancelling) {
                            val notif = buildNotification(p.current, p.total, p.phase)
                            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** 构建进度通知（委托 LiveUpdateNotificationBuilder，Android 16+ 自动升级 Live Update） */
    private fun buildNotification(current: Int, total: Int, phase: BatchRemovalPhase): Notification {
        val phaseText = when (phase) {
            BatchRemovalPhase.REMOVING -> getString(com.tracktosearch.R.string.batch_removal_phase_removing)
            BatchRemovalPhase.CANCELLING -> getString(com.tracktosearch.R.string.batch_removal_phase_cancelling)
            BatchRemovalPhase.DONE -> getString(com.tracktosearch.R.string.batch_removal_phase_done)
            BatchRemovalPhase.CANCELLED -> getString(com.tracktosearch.R.string.batch_removal_phase_cancelled)
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DoubanBatchRemovalService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        return LiveUpdateNotificationBuilder.build(
            context = this,
            channelId = CHANNEL_ID,
            title = getString(com.tracktosearch.R.string.douban_batch_removal_title),
            phase = phaseText,
            current = current,
            total = total,
            contentIntent = contentIntent,
            cancelIntent = cancelIntent,
            cancelText = getString(com.tracktosearch.R.string.douban_batch_removal_cancel),
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(com.tracktosearch.R.string.douban_batch_removal_title), NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Douban batch mark removal progress" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
