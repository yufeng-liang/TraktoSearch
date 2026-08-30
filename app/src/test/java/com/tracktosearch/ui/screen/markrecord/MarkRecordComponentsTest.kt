package com.tracktosearch.ui.screen.markrecord

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 标记记录模块纯函数测试。
 *
 * 只保留用户可见的关键契约，并用表驱动覆盖输入边界：海报 URL 兜底、记录状态变更判定、相对时间格式化。
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

    @Test
    fun `buildFullPosterUrl_覆盖空值完整地址和相对路径`() {
        val cases: List<Pair<String?, String?>> = listOf(
            null to null,
            "https://image.tmdb.org/t/p/w500/bb.jpg" to
                "https://image.tmdb.org/t/p/w500/bb.jpg",
            "/inception.jpg" to "${TmdbImageUrls.W342}/inception.jpg"
        )

        cases.forEach { (input, expected) ->
            assertThat(buildFullPosterUrl(input)).isEqualTo(expected)
        }
    }

    @Test
    fun `isRecordChanged_覆盖各动作和状态组合`() {
        data class Case(
            val actionType: String,
            val status: CurrentMarkStatus?,
            val expected: Boolean
        )

        val cases = listOf(
            Case("ADD_WATCHLIST", null, false),
            Case("ADD_WATCHLIST", CurrentMarkStatus.IN_WATCHLIST, false),
            Case("ADD_WATCHLIST", CurrentMarkStatus.WATCHED, true),
            Case("ADD_WATCHLIST", CurrentMarkStatus.NONE, true),
            Case("REMOVE_WATCHLIST", CurrentMarkStatus.IN_WATCHLIST, true),
            Case("REMOVE_WATCHLIST", CurrentMarkStatus.NONE, false),
            Case("UNMARK_WATCHED", CurrentMarkStatus.WATCHED, true),
            Case("UNMARK_WATCHED", CurrentMarkStatus.NONE, false),
            Case("WATCHED", CurrentMarkStatus.WATCHED, false),
            Case("WATCHED", CurrentMarkStatus.NONE, true),
            Case("UNKNOWN", CurrentMarkStatus.WATCHED, false),
            Case("UNKNOWN", CurrentMarkStatus.IN_WATCHLIST, false)
        )

        cases.forEach { case ->
            assertThat(isRecordChanged(item(case.actionType, case.status)))
                .isEqualTo(case.expected)
        }
    }

    private fun formatRelativeTime(timestampMs: Long): String {
        val method = Class.forName("com.tracktosearch.ui.screen.markrecord.MarkRecordComponentsKt")
            .getDeclaredMethod(
                "formatRelativeTime",
                Long::class.javaPrimitiveType,
                Context::class.java
            )
        method.isAccessible = true
        return method.invoke(null, timestampMs, context) as String
    }

    @Test
    fun `formatRelativeTime_覆盖非法输入和主要时间分段`() {
        val now = System.currentTimeMillis()
        val cases = listOf(
            0L to "",
            -1L to "",
            now - 30 * 1000L to "Just now",
            now - 2 * 60 * 1000L to "2 minutes ago",
            now - 2 * 60 * 60 * 1000L to "2 hours ago",
            now - 2 * 24 * 60 * 60 * 1000L to "2 days ago"
        )

        cases.forEach { (timestamp, expected) ->
            assertThat(formatRelativeTime(timestamp)).isEqualTo(expected)
        }

        val oldTimestamp = now - 100L * 24 * 60 * 60 * 1000
        val expectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(oldTimestamp))
        assertThat(formatRelativeTime(oldTimestamp)).isEqualTo(expectedDate)
    }
}
