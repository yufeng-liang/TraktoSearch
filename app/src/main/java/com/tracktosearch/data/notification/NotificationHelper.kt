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

    // 类型/条目/季联合哈希取正 Int 作为通知 id：原来按前缀分段（新季 = traktId*100+season），
    // 会与其它剧的 release id 结构性相撞，且 traktId 较大时 *100 直接溢出 Int
    private fun notificationIdFor(type: String, traktId: Int, season: Int = 0): Int {
        var result = type.hashCode()
        result = 31 * result + traktId
        result = 31 * result + season
        return result and Int.MAX_VALUE
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
        val notificationId = notificationIdFor(mediaType, traktId)
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
        val notificationId = notificationIdFor("show_season", traktId, seasonNumber)
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
