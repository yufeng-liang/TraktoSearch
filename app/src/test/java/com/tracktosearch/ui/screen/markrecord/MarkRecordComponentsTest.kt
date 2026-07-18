package com.tracktosearch.ui.screen.markrecord

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.util.PosterColorExtractor
import io.mockk.mockk
import org.junit.Rule
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
 * - [formatRelativeTime]：private 函数，通过反射测试 6 个时间分支 + 4 个边界值
 * - [ActionTypeChip]：private @Composable，通过 Compose UI 渲染测试文案映射
 * - [CurrentStatusBadge]：private @Composable，通过 Compose UI 渲染测试状态判断
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MarkRecordComponentsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @get:Rule
    val composeRule = createComposeRule()

    // 测试不校验海报取色行为，用宽松 mock 满足构造参数即可
    private val posterColorExtractor: PosterColorExtractor = mockk(relaxed = true)

    private fun item(
        actionType: String,
        currentStatus: CurrentMarkStatus?
    ) = MarkRecordItem(
        traktId = 1, tmdbId = 1, imdbId = "tt1", mediaType = "movie",
        title = "T", displayTitle = "T", posterUrl = null, year = 2024,
        actionType = actionType, actedAt = 1000L, episodeInfo = null,
        currentStatus = currentStatus
    )

    // ==================== buildFullPosterUrl（测试点 D）====================
    // 验证 posterUrl 兜底拼接逻辑：null/空 → null；完整 URL → 原样；相对路径 → 拼接 TMDB 基础 URL。
    // 这是「海报不显示」bug 的 UI 层防护——如果 Repository 写入了相对路径，UI 层必须拼接为完整 URL。

    @Test
    fun `buildFullPosterUrl_null返回null`() {
        assertThat(buildFullPosterUrl(null)).isNull()
    }

    @Test
    fun `buildFullPosterUrl_空字符串返回null`() {
        assertThat(buildFullPosterUrl("")).isNull()
    }

    @Test
    fun `buildFullPosterUrl_空白字符串返回null`() {
        assertThat(buildFullPosterUrl("   ")).isNull()
    }

    @Test
    fun `buildFullPosterUrl_http完整URL原样返回`() {
        val url = "https://image.tmdb.org/t/p/w500/inception.jpg"
        assertThat(buildFullPosterUrl(url)).isEqualTo(url)
    }

    @Test
    fun `buildFullPosterUrl_https完整URL原样返回`() {
        val url = "https://image.tmdb.org/t/p/w500/bb.jpg"
        assertThat(buildFullPosterUrl(url)).isEqualTo(url)
    }

    @Test
    fun `buildFullPosterUrl_相对路径拼接TMDB基础URL`() {
        val result = buildFullPosterUrl("/inception.jpg")
        assertThat(result).isEqualTo("https://image.tmdb.org/t/p/w500/inception.jpg")
    }

    @Test
    fun `buildFullPosterUrl_无斜杠相对路径也拼接`() {
        val result = buildFullPosterUrl("inception.jpg")
        assertThat(result).isEqualTo("https://image.tmdb.org/t/p/w500inception.jpg")
    }

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

    // ==================== formatRelativeTime 边界值（反射）====================

    @Test
    fun `formatRelativeTime_刚好60秒_返回1分钟前`() {
        // minutes = 1，不满足 < 1，满足 < 60 → "1 minutes ago"
        val ts = System.currentTimeMillis() - 60 * 1000L
        val result = formatRelativeTime(ts)
        assertThat(result).isEqualTo("1 minutes ago")
    }

    @Test
    fun `formatRelativeTime_刚好60分钟_返回1小时前`() {
        // minutes = 60，不满足 < 60；hours = 1，满足 < 24 → "1 hours ago"
        val ts = System.currentTimeMillis() - 60 * 60 * 1000L
        val result = formatRelativeTime(ts)
        assertThat(result).isEqualTo("1 hours ago")
    }

    @Test
    fun `formatRelativeTime_刚好24小时_返回1天前`() {
        // hours = 24，不满足 < 24；days = 1，满足 < 30 → "1 days ago"
        val ts = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val result = formatRelativeTime(ts)
        assertThat(result).isEqualTo("1 days ago")
    }

    @Test
    fun `formatRelativeTime_刚好30天_返回日期格式`() {
        // days = 30，不满足 < 30 → 走 else 分支返回 yyyy-MM-dd
        val ts = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val result = formatRelativeTime(ts)
        val expected = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
        assertThat(result).isEqualTo(expected)
    }

    // ==================== ActionTypeChip 文案映射（Compose UI 渲染）====================
    // ActionTypeChip 是 @Composable private，颜色映射内联在 when 表达式中，
    // 没有独立的 getActionTypeChipColor 函数可供反射，改用 Compose UI 渲染验证文案映射。

    @Test
    fun `ActionTypeChip_ADD_WATCHLIST渲染Watchlist`() {
        composeRule.setContent {
            MaterialTheme {
                MarkRecordItemRow(item = item("ADD_WATCHLIST", null), posterColorExtractor = posterColorExtractor, onClick = {})
            }
        }
        composeRule.onNodeWithText("Watchlist").assertIsDisplayed()
    }

    @Test
    fun `ActionTypeChip_REMOVE_WATCHLIST渲染Removed`() {
        composeRule.setContent {
            MaterialTheme {
                MarkRecordItemRow(item = item("REMOVE_WATCHLIST", null), posterColorExtractor = posterColorExtractor, onClick = {})
            }
        }
        composeRule.onNodeWithText("Removed").assertIsDisplayed()
    }

    @Test
    fun `ActionTypeChip_UNMARK_WATCHED渲染Removed`() {
        composeRule.setContent {
            MaterialTheme {
                MarkRecordItemRow(item = item("UNMARK_WATCHED", null), posterColorExtractor = posterColorExtractor, onClick = {})
            }
        }
        composeRule.onNodeWithText("Removed").assertIsDisplayed()
    }

    @Test
    fun `ActionTypeChip_未知类型回退渲染默认Watched`() {
        composeRule.setContent {
            MaterialTheme {
                MarkRecordItemRow(item = item("UNKNOWN", null), posterColorExtractor = posterColorExtractor, onClick = {})
            }
        }
        // else 分支使用 mark_records_action_watched ("Watched") + Color.Gray
        composeRule.onNodeWithText("Watched").assertIsDisplayed()
    }

    // ==================== CurrentStatusBadge 状态判断（Compose UI 渲染）====================
    // CurrentStatusBadge 是 @Composable private，状态判断内联在 when 表达式中，
    // 改用 Compose UI 渲染验证不同 currentStatus 下的文案映射。

    @Test
    fun `CurrentStatusBadge_IN_WATCHLIST渲染Current_Watchlist`() {
        composeRule.setContent {
            MaterialTheme {
                MarkRecordItemRow(
                    item = item("ADD_WATCHLIST", CurrentMarkStatus.IN_WATCHLIST),
                    posterColorExtractor = posterColorExtractor,
                    onClick = {}
                )
            }
        }
        composeRule.onNodeWithText("Current: Watchlist").assertIsDisplayed()
    }

    @Test
    fun `CurrentStatusBadge_WATCHED渲染Current_Watched`() {
        composeRule.setContent {
            MaterialTheme {
                MarkRecordItemRow(
                    item = item("WATCHED", CurrentMarkStatus.WATCHED),
                    posterColorExtractor = posterColorExtractor,
                    onClick = {}
                )
            }
        }
        composeRule.onNodeWithText("Current: Watched").assertIsDisplayed()
    }

    @Test
    fun `CurrentStatusBadge_NONE渲染Current_Removed`() {
        // REMOVE_WATCHLIST + NONE → currentStatus=NONE → "Current: Removed"
        composeRule.setContent {
            MaterialTheme {
                MarkRecordItemRow(
                    item = item("REMOVE_WATCHLIST", CurrentMarkStatus.NONE),
                    posterColorExtractor = posterColorExtractor,
                    onClick = {}
                )
            }
        }
        composeRule.onNodeWithText("Current: Removed").assertIsDisplayed()
    }

    @Test
    fun `CurrentStatusBadge_ADD_WATCHLIST_NONE渲染Current_Removed`() {
        // ADD_WATCHLIST + NONE → currentStatus=NONE → "Current: Removed"
        composeRule.setContent {
            MaterialTheme {
                MarkRecordItemRow(
                    item = item("ADD_WATCHLIST", CurrentMarkStatus.NONE),
                    posterColorExtractor = posterColorExtractor,
                    onClick = {}
                )
            }
        }
        composeRule.onNodeWithText("Current: Removed").assertIsDisplayed()
    }
}
