package com.tracktosearch.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tracktosearch.R

/**
 * 统一构建进度通知的无状态工具。
 *
 * Android 16 (API 36)+：升级为 Live Update 样式
 *  - setRequestPromotedOngoing(true) 请求系统置顶 + 锁屏突出 + 状态栏芯片
 *  - setShortCriticalText(phase.take(7)) 状态栏芯片显示当前阶段名（空间小，截断到 ≤7 字）
 *  - ProgressStyle 两段色进度条：已完成段用系统动态强调色（跟随壁纸，与 App 默认主题和谐），
 *    剩余段用系统中性色
 * <36：自动回退为现有普通样式（setProgress）。
 *
 * 说明：Live Update 实际需 API 36，动态色资源需 API 31，因此启用 Live Update 的设备动态色必然可用；
 * <31 的固定色兜底仅为理论完备（该分支永远走不到 Live Update）。
 */
object LiveUpdateNotificationBuilder {

    /** Trakt 品牌红兜底（仅 <API 31 且无动态色时使用） */
    private const val FALLBACK_DONE_COLOR = 0xFFED1C24.toInt()
    private const val FALLBACK_REMAIN_COLOR = 0xFF3A2A30.toInt()

    /** 状态栏芯片文本最大字符数 */
    private const val CHIP_MAX_CHARS = 7

    fun build(
        context: Context,
        channelId: String,
        title: String,
        phase: String,
        current: Int,
        total: Int,
        contentIntent: PendingIntent,
        cancelIntent: PendingIntent,
        cancelText: String,
        contentText: String? = null,
        expandedText: String? = null,
        publicText: String? = null,
        isTerminal: Boolean = false,
    ): Notification {
        val resolvedContentText = contentText ?: if (total > 0) "$phase ($current/$total)" else phase
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(title)
            .setContentText(resolvedContentText)
            .setContentIntent(contentIntent)
            .setOngoing(!isTerminal)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        if (!isTerminal) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, cancelText, cancelIntent)
        } else {
            builder.setAutoCancel(true)
        }
        if (!expandedText.isNullOrBlank()) {
            builder.setSubText(expandedText)
        }

        if (Build.VERSION.SDK_INT >= 36) {
            if (!isTerminal) builder.setRequestPromotedOngoing(true)
            if (phase.isNotEmpty()) {
                builder.setShortCriticalText(phase.take(CHIP_MAX_CHARS))
            }
            builder.setStyle(buildProgressStyle(context, current, total))
        } else {
            if (total > 0) builder.setProgress(total, current, false)
            else builder.setProgress(0, 0, true)
            if (!expandedText.isNullOrBlank()) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            }
        }

        // 锁屏只显示通用状态，避免把用户的豆瓣条目标题暴露到公开通知预览。
        builder.setPublicVersion(
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_sync)
                .setContentTitle(title)
                .setContentText(publicText ?: title)
                .setOngoing(!isTerminal)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        )
        return builder.build()
    }

    private fun buildProgressStyle(
        context: Context,
        current: Int,
        total: Int,
    ): NotificationCompat.ProgressStyle {
        val style = NotificationCompat.ProgressStyle()
        if (total > 0) {
            val done = current.coerceIn(0, total)
            val remain = (total - done).coerceAtLeast(0)
            style.addProgressSegment(
                NotificationCompat.ProgressStyle.Segment(done).setColor(resolveDoneColor(context))
            )
            if (remain > 0) {
                style.addProgressSegment(
                    NotificationCompat.ProgressStyle.Segment(remain).setColor(resolveRemainColor(context))
                )
            }
            style.setProgress(done)
        } else {
            style.setProgressIndeterminate(true)
        }
        return style
    }

    private fun resolveDoneColor(context: Context): Int =
        if (Build.VERSION.SDK_INT >= 31)
            ContextCompat.getColor(context, android.R.color.system_accent1_500)
        else FALLBACK_DONE_COLOR

    private fun resolveRemainColor(context: Context): Int =
        if (Build.VERSION.SDK_INT >= 31)
            ContextCompat.getColor(context, android.R.color.system_neutral1_700)
        else FALLBACK_REMAIN_COLOR
}
