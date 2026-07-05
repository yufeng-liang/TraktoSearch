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
import androidx.core.app.NotificationCompat
import com.tracktosearch.MainActivity
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.DoubanSyncProgress
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

    companion object {
        const val CHANNEL_ID = "douban_sync"
        const val NOTIF_ID = 9001
        const val ACTION_START = "com.tracktosearch.START_DOUBAN_SYNC"
        const val ACTION_CANCEL = "com.tracktosearch.CANCEL_DOUBAN_SYNC"

        fun start(context: Context) {
            // 通知权限未授予时不启动 Service（同步仍在 Application scope 跑，只是没通知）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            val intent = Intent(context, DoubanSyncService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // 启动前台通知（Android 14+ 需要指定 foregroundServiceType）
                // 用 try-catch 兜底：即使 FGS 启动失败（权限缺失或后台限制），也不崩溃，
                // 同步仍在 DoubanSyncManager 的 Application scope 中继续运行。
                try {
                    val notif = buildNotification(0, 0, "准备同步...")
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
                // 监听同步进度并更新通知，同步结束后 stopSelf
                scope.launch {
                    doubanSyncManager.progress.collectLatest { p: DoubanSyncProgress ->
                        if (p.isComplete) {
                            stopSelf()
                            return@collectLatest
                        }
                        if (p.isRunning) {
                            val notif = buildNotification(p.current, p.total, p.phase)
                            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** 构建进度通知 */
    private fun buildNotification(current: Int, total: Int, phase: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DoubanSyncService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )

        // 通知文案用字符串资源，phase 本身是中文阶段名（来自 DoubanSyncProgress.phase）
        val title = getString(com.tracktosearch.R.string.douban_sync_title)
        val cancelText = getString(com.tracktosearch.R.string.douban_sync_cancel)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.tracktosearch.R.drawable.ic_sync)
            .setContentTitle(title)
            .setContentText("$phase ($current/$total)")
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, cancelText, cancelIntent)
            .setOngoing(true)

        if (total > 0) {
            builder.setProgress(total, current, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(com.tracktosearch.R.string.douban_sync_title), NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Douban → Trakt sync progress" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
