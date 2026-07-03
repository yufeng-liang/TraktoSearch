package com.tracktosearch.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.tracktosearch.MainActivity
import com.tracktosearch.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID_RELEASE = "release_reminder"
        const val CHANNEL_ID_NEW_SEASON = "new_season_reminder"
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val releaseChannel = NotificationChannel(
                CHANNEL_ID_RELEASE,
                context.getString(R.string.notification_channel_release),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_release_desc)
                enableVibration(true)
            }
            val newSeasonChannel = NotificationChannel(
                CHANNEL_ID_NEW_SEASON,
                context.getString(R.string.notification_channel_new_season),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_new_season_desc)
                enableVibration(true)
            }
            manager.createNotificationChannels(listOf(releaseChannel, newSeasonChannel))
        }
    }

    fun showReleaseNotification(
        title: String,
        releaseDate: String,
        traktId: Int,
        tmdbId: Int,
        mediaType: String
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "detail")
            putExtra("type", mediaType)
            putExtra("traktId", traktId)
            putExtra("tmdbId", tmdbId)
            putExtra("title", title)
        }
        // movie/show 的 traktId 命名空间独立，用不同前缀避免 notification id 冲突
        val baseId = if (mediaType == "show") 200000 else 100000
        val notificationId = baseId + traktId
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_RELEASE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_release_title, title))
            .setContentText(context.getString(R.string.notification_release_text, releaseDate))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notification_release_text, releaseDate)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(notificationId, notification)
    }

    fun showNewSeasonNotification(
        title: String,
        seasonNumber: Int,
        airDate: String,
        traktId: Int,
        tmdbId: Int
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "detail")
            putExtra("type", "show")
            putExtra("traktId", traktId)
            putExtra("tmdbId", tmdbId)
            putExtra("title", title)
        }
        // 新季通知属于 show，用 200000 前缀 + traktId*100 + seasonNumber 避免与 movie 冲突
        val notificationId = 200000 + traktId * 100 + seasonNumber
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_NEW_SEASON)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_new_season_title, title))
            .setContentText(context.getString(R.string.notification_new_season_text, seasonNumber, airDate))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notification_new_season_text, seasonNumber, airDate)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(notificationId, notification)
    }
}
