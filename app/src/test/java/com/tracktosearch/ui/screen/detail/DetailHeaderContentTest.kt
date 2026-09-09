package com.tracktosearch.ui.screen.detail

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tracktosearch.R
import com.tracktosearch.data.repository.MultiRatings
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DetailHeaderContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val expandLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.detail_text_expand)

    private val collapseLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.detail_text_collapse)

    private val longOverview = (1..5).joinToString("\n") { "Overview line $it" }

    private fun getTextLayouts(marker: String): List<TextLayoutResult> {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(marker, substring = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                action(layouts)
            }
        return layouts
    }

    @Test
    fun expand_action_is_inline_and_toggles_to_collapse() {
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    ExpandableText(text = longOverview)
                }
            }
        }

        // 「展开」内联在正文行末，与正文同属一个节点：没有单独的「展开」文本节点
        composeRule.onAllNodesWithText(expandLabel).assertCountEquals(0)
        // 点正文即切换（动作就在这段文字里）
        composeRule.onNodeWithText(expandLabel, substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText(collapseLabel, substring = true).assertIsDisplayed()
        // 展开后正文 5 行全部布局显示，内联的「收起」不额外占行
        assertThat(getTextLayouts("Overview line 1").single().lineCount).isEqualTo(5)
    }

    // ==================== 头部上半区布局 ====================

    /** 头部 Column 的 top padding，海报顶边就在这里。 */
    private val headerTopPadding = 32.dp

    /** 海报固定 118dp 宽、2:3 比例。 */
    private val posterBottom = headerTopPadding + 118.dp * 1.5f

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private fun setHeader(uiState: State<DetailUiState>) {
        composeRule.setContent {
            MaterialTheme {
                // 固定 411dp 宽（常见手机宽度），保证胶囊换行行为可预期
                Box(modifier = Modifier.width(411.dp)) {
                    DetailHeaderContent(
                        uiState = uiState.value,
                        tmdbId = 0,
                        isMarkedWatched = false,
                        isMarkingWatched = false,
                        onToggleWatched = {},
                        isMarkedWatchlist = false,
                        isMarkingWatchlist = false,
                        onToggleWatchlist = {},
                        posterColorExtractor = mockk(relaxed = true),
                        onPosterColorExtracted = {},
                    )
                }
            }
        }
    }

    /** 元信息未到达时的首帧状态：只有标题和评分卡。 */
    private fun stateWithoutMeta() = DetailUiState(
        displayTitle = "欢迎来龙餐馆",
        ratings = MultiRatings(imdbRating = "7.9"),
        ratingSource = DetailRatingSource.NORMAL
    )

    /** 元信息齐全的状态：类型 / 地区 / 日期 / 时长四项都有，胶囊会占两行。 */
    private fun stateWithMeta() = stateWithoutMeta().copy(
        genres = "剧情 · 战争",
        country = "中国",
        releaseDate = "2026-08-11",
        runtime = 140
    )

    private fun ratingsCardTop(): Float =
        composeRule.onNodeWithText(string(R.string.detail_info_imdb_rating))
            .getUnclippedBoundsInRoot().top.value

    @Test
    fun action_buttons_stay_pinned_to_the_poster_bottom_when_meta_arrives() {
        val label = string(R.string.detail_mark_watchlist)
        val state = mutableStateOf(stateWithoutMeta())
        setHeader(state)
        val before = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()

        // 元信息胶囊到达后按钮组一动不动 —— 它贴的是海报底边，不是文字块底边
        state.value = stateWithMeta()
        composeRule.waitForIdle()
        val after = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()

        assertThat(after.top.value).isEqualTo(before.top.value)
        // 文字（12sp）底边比按钮列底边高 4dp 内边距 + 7dp 外边距，故留 12dp 容差
        assertThat(after.bottom.value).isAtMost(posterBottom.value)
        assertThat(after.bottom.value).isAtLeast(posterBottom.value - 12f)

        // 评分卡仍在按钮组下方
        assertThat(ratingsCardTop()).isAtLeast(after.bottom.value)
    }

    @Test
    fun meta_chips_arriving_late_do_not_push_the_ratings_card_down() {
        val state = mutableStateOf(stateWithoutMeta())
        setHeader(state)
        val ratingsTopWithoutChips = ratingsCardTop()

        // 四项元信息到齐（胶囊两行）后，评分卡不许挪窝——右列高度被海报钉住
        state.value = stateWithMeta()
        composeRule.waitForIdle()

        assertThat(ratingsCardTop()).isEqualTo(ratingsTopWithoutChips)
    }

    @Test
    fun overlong_meta_pushes_the_action_buttons_below_the_poster_instead_of_clipping() {
        // 两行标题 + 原名 + 三行胶囊，文字块本身就高过海报：按钮组要顺势下移，
        // 不能被钉在 177dp 处压住文字或被裁掉
        setHeader(
            mutableStateOf(
                stateWithMeta().copy(
                    displayTitle = "银翼杀手 2049 导演剪辑加长纪念版",
                    originalTitle = "Blade Runner 2049 Final Cut",
                    genres = "剧情 / 战争 / 历史 / 传记",
                    country = "中国大陆 / 美国 / 法国 / 日本"
                )
            )
        )

        val buttonBounds = composeRule.onNodeWithText(string(R.string.detail_mark_watchlist))
            .getUnclippedBoundsInRoot()
        assertThat(buttonBounds.bottom.value).isGreaterThan(posterBottom.value)
        // 评分卡跟着下移，仍在按钮组之后
        assertThat(ratingsCardTop()).isAtLeast(buttonBounds.bottom.value)
    }

}
