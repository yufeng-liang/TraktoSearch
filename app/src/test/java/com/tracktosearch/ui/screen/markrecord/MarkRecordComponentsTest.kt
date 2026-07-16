package com.tracktosearch.ui.screen.markrecord

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 标记记录模块 UI 组件纯函数测试。
 *
 * 覆盖：
 * - [isRecordChanged]：public 纯函数，9 个状态矩阵分支
 * - [formatRelativeTime]：private 函数，通过反射测试 6 个时间分支
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MarkRecordComponentsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun item(
        actionType: String,
        currentStatus: CurrentMarkStatus?
    ) = MarkRecordItem(
        traktId = 1, tmdbId = 1, imdbId = "tt1", mediaType = "movie",
        title = "T", displayTitle = "T", posterUrl = null, year = 2024,
        actionType = actionType, actedAt = 1000L, episodeInfo = null,
        currentStatus = currentStatus
    )

    // ==================== isRecordChanged ====================

    @Test
    fun `isRecordChanged_currentStatus为null_始终返回false`() {
        assertThat(isRecordChanged(item("ADD_WATCHLIST", null))).isFalse()
        assertThat(isRecordChanged(item("REMOVE_WATCHLIST", null))).isFalse()
        assertThat(isRecordChanged(item("UNMARK_WATCHED", null))).isFalse()
        assertThat(isRecordChanged(item("WATCHED", null))).isFalse()
    }

    @Test
    fun `isRecordChanged_ADD_WATCHLIST_状态一致_IN_WATCHLIST_返回false`() {
        assertThat(isRecordChanged(item("ADD_WATCHLIST", CurrentMarkStatus.IN_WATCHLIST))).isFalse()
    }

    @Test
    fun `isRecordChanged_ADD_WATCHLIST_状态不一致_WATCHED_返回true`() {
        assertThat(isRecordChanged(item("ADD_WATCHLIST", CurrentMarkStatus.WATCHED))).isTrue()
    }

    @Test
    fun `isRecordChanged_ADD_WATCHLIST_状态不一致_NONE_返回true`() {
        assertThat(isRecordChanged(item("ADD_WATCHLIST", CurrentMarkStatus.NONE))).isTrue()
    }

    @Test
    fun `isRecordChanged_REMOVE_WATCHLIST_状态不一致_IN_WATCHLIST_返回true`() {
        assertThat(isRecordChanged(item("REMOVE_WATCHLIST", CurrentMarkStatus.IN_WATCHLIST))).isTrue()
    }

    @Test
    fun `isRecordChanged_REMOVE_WATCHLIST_状态一致_NONE_返回false`() {
        assertThat(isRecordChanged(item("REMOVE_WATCHLIST", CurrentMarkStatus.NONE))).isFalse()
    }

    @Test
    fun `isRecordChanged_UNMARK_WATCHED_状态不一致_WATCHED_返回true`() {
        assertThat(isRecordChanged(item("UNMARK_WATCHED", CurrentMarkStatus.WATCHED))).isTrue()
    }

    @Test
    fun `isRecordChanged_UNMARK_WATCHED_状态一致_NONE_返回false`() {
        assertThat(isRecordChanged(item("UNMARK_WATCHED", CurrentMarkStatus.NONE))).isFalse()
    }

    @Test
    fun `isRecordChanged_WATCHED_状态一致_WATCHED_返回false`() {
        assertThat(isRecordChanged(item("WATCHED", CurrentMarkStatus.WATCHED))).isFalse()
    }

    @Test
    fun `isRecordChanged_WATCHED_状态不一致_NONE_返回true`() {
        assertThat(isRecordChanged(item("WATCHED", CurrentMarkStatus.NONE))).isTrue()
    }

    @Test
    fun `isRecordChanged_未知actionType_返回false`() {
        assertThat(isRecordChanged(item("UNKNOWN", CurrentMarkStatus.WATCHED))).isFalse()
        assertThat(isRecordChanged(item("UNKNOWN", CurrentMarkStatus.IN_WATCHLIST))).isFalse()
    }

    // ==================== formatRelativeTime（反射）====================

    private fun formatRelativeTime(timestampMs: Long): String {
        val method = Class.forName("com.tracktosearch.ui.screen.markrecord.MarkRecordComponentsKt")
            .getDeclaredMethod("formatRelativeTime", Long::class.javaPrimitiveType, Context::class.java)
        method.isAccessible = true
        return method.invoke(null, timestampMs, context) as String
    }

    @Test
    fun `formatRelativeTime_时间戳小于等于0_返回空字符串`() {
        assertThat(formatRelativeTime(0L)).isEmpty()
        assertThat(formatRelativeTime(-1L)).isEmpty()
    }

    @Test
    fun `formatRelativeTime_30秒前_返回刚刚`() {
        val ts = System.currentTimeMillis() - 30 * 1000L
        val result = formatRelativeTime(ts)
        // Robolectric 默认加载 values/(英文)，mark_records_time_just_now = "Just now"
        assertThat(result).isEqualTo("Just now")
    }

    @Test
    fun `formatRelativeTime_2分钟前_返回X分钟前`() {
        val ts = System.currentTimeMillis() - 2 * 60 * 1000L
        val result = formatRelativeTime(ts)
        // mark_records_time_minutes_ago = "%1\$d minutes ago"
        assertThat(result).isEqualTo("2 minutes ago")
    }

    @Test
    fun `formatRelativeTime_2小时前_返回X小时前`() {
        val ts = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        val result = formatRelativeTime(ts)
        // mark_records_time_hours_ago = "%1\$d hours ago"
        assertThat(result).isEqualTo("2 hours ago")
    }

    @Test
    fun `formatRelativeTime_2天前_返回X天前`() {
        val ts = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
        val result = formatRelativeTime(ts)
        // mark_records_time_days_ago = "%1\$d days ago"
        assertThat(result).isEqualTo("2 days ago")
    }

    @Test
    fun `formatRelativeTime_100天前_返回yyyy-MM-dd格式`() {
        val ts = System.currentTimeMillis() - 100L * 24 * 60 * 60 * 1000
        val result = formatRelativeTime(ts)
        val expected = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
        assertThat(result).isEqualTo(expected)
    }
}
