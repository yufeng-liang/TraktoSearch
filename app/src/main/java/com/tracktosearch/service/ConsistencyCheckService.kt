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

/**
 * 状态一致性检查通知服务：
 * - 只负责显示通知栏进度，不启动检查（检查在 DoubanTraktStatusConsistencyChecker 的 Application scope 跑）
 * - 检查完成或取消后自动 stopSelf
 * - 通知带「取消」Action，用户可中断检查
 *
 * 仅在用户主动点击「转后台」时（App 在前台）启动。
 */
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

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            val intent = Intent(context, ConsistencyCheckService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                consistencyChecker.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                try {
                    val notif = buildNotification(0, 0, "准备检查...")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIF_ID, notif)
                    }
                } catch (e: Exception) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                progressJob?.cancel()
                progressJob = scope.launch {
                    consistencyChecker.checkProgress.collectLatest { p: ConsistencyCheckResult ->
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

    private fun buildNotification(current: Int, total: Int, phase: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ConsistencyCheckService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )

        val title = getString(com.tracktosearch.R.string.consistency_check_title)
        val cancelText = getString(com.tracktosearch.R.string.consistency_check_cancel)
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
                CHANNEL_ID, getString(com.tracktosearch.R.string.consistency_check_title), NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Status consistency check progress" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
