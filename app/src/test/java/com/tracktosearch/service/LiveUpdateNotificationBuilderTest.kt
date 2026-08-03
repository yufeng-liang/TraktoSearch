package com.tracktosearch.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.MainActivity
import com.tracktosearch.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveUpdateNotificationBuilderTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `进行中通知正文显示阶段计数和当前条目并隐藏锁屏标题`() {
        val notification = buildNotification(
            contentText = "获取豆瓣列表 · 想看 (15/60)",
            expandedText = "当前：流浪地球 2 · 预计剩余 2 分钟",
            publicText = "豆瓣同步：获取列表",
            isTerminal = false
        )

        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo("获取豆瓣列表 · 想看 (15/60)")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
            .contains("当前：流浪地球 2")
        assertThat(notification.extras.getInt(Notification.EXTRA_PROGRESS)).isEqualTo(15)
        assertThat(notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX)).isEqualTo(60)
        assertThat(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)).isFalse()
        assertThat(notification.visibility).isEqualTo(NotificationCompat.VISIBILITY_PRIVATE)
        assertThat(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo("豆瓣同步：获取列表")
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT)
            .isNotEqualTo(0)
    }

    @Test
    fun `终态通知可留在通知栏且不再提供取消动作`() {
        val notification = buildNotification(
            contentText = "成功 15 条，失败 0 条",
            expandedText = "同步完成",
            publicText = "豆瓣同步：同步完成",
            isTerminal = true
        )

        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT)
            .isEqualTo(0)
        assertThat(notification.actions?.size ?: 0).isEqualTo(0)
        assertThat(notification.flags and Notification.FLAG_AUTO_CANCEL)
            .isNotEqualTo(0)
    }

    private fun buildNotification(
        contentText: String,
        expandedText: String,
        publicText: String,
        isTerminal: Boolean
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, DoubanSyncService::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return LiveUpdateNotificationBuilder.build(
            context = context,
            channelId = "test_douban_sync",
            title = context.getString(R.string.douban_sync_title),
            phase = "获取豆瓣列表",
            current = 15,
            total = 60,
            contentIntent = contentIntent,
            cancelIntent = cancelIntent,
            cancelText = context.getString(R.string.douban_sync_cancel),
            contentText = contentText,
            expandedText = expandedText,
            publicText = publicText,
            isTerminal = isTerminal
        )
    }
}
