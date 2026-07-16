package com.tracktosearch.ui.screen.markrecord

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.HiltTestActivity
import com.tracktosearch.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MarkRecordScreen 页面 Instrumented UI 测试。
 *
 * 测试策略:
 * - 使用 `createAndroidComposeRule<HiltTestActivity>()`:HiltTestActivity 被
 *   `@AndroidEntryPoint` 注解(位于 debug source set),满足 `hiltViewModel()` 对
 *   承载 Activity 实现 `GeneratedComponentManager` 的要求。
 * - MarkRecordViewModel 通过参数直接传入 mock,不走 hiltViewModel()。
 * - MarkRecordScreen 内部无其他 hiltViewModel() 调用。
 * - MarkRecordItemRow 使用 `Modifier.clickable` 合并后代语义,
 *   `onNodeWithText(title)` 可定位到整行并触发点击。
 * - 字符串定位使用 InstrumentationRegistry.targetContext.getString() 获取当前 locale 文案。
 *
 * 测试覆盖:
 * - 默认显示 ALL Tab
 * - 列表项标题渲染
 * - 空状态提示
 * - 错误状态重试按钮
 * - 加载状态进度指示器
 * - 点击卡片触发 onMovieClick / onShowClick
 * - 返回按钮触发 onBack
 * - 搜索输入触发 updateSearchQuery
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MarkRecordScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun `默认显示ALLTab`() {
        composeRule.setContent {
            MarkRecordScreen(
                onBack = {},
                onMovieClick = { _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _ -> },
                viewModel = createMockViewModel()
            )
        }
        composeRule.waitForIdle()
        val allTabLabel = context.getString(R.string.mark_records_tab_all)
        composeRule.onNodeWithText(allTabLabel).assertIsDisplayed()
    }

    @Test
    fun `显示列表项标题`() {
        val title = "测试电影标题"
        composeRule.setContent {
            MarkRecordScreen(
                onBack = {},
                onMovieClick = { _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _ -> },
                viewModel = createMockViewModel(
                    uiState = MarkRecordUiState(
                        items = listOf(createMovieItem(title = title))
                    )
                )
            )
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(title).onFirst().assertIsDisplayed()
    }

    @Test
    fun `空状态显示提示`() {
        composeRule.setContent {
            MarkRecordScreen(
                onBack = {},
                onMovieClick = { _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _ -> },
                viewModel = createMockViewModel()
            )
        }
        composeRule.waitForIdle()
        val emptyText = context.getString(R.string.mark_records_empty_all)
        composeRule.onNodeWithText(emptyText).assertIsDisplayed()
    }

    @Test
    fun `错误状态显示重试按钮`() {
        composeRule.setContent {
            MarkRecordScreen(
                onBack = {},
                onMovieClick = { _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _ -> },
                viewModel = createMockViewModel(
                    uiState = MarkRecordUiState(error = "加载失败")
                )
            )
        }
        composeRule.waitForIdle()
        val retryText = context.getString(R.string.mark_records_retry)
        composeRule.onNodeWithText(retryText).assertIsDisplayed()
    }

    @Test
    fun `加载状态显示进度指示器`() {
        composeRule.setContent {
            MarkRecordScreen(
                onBack = {},
                onMovieClick = { _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _ -> },
                viewModel = createMockViewModel(
                    uiState = MarkRecordUiState(isLoading = true)
                )
            )
        }
        composeRule.waitForIdle()
        // 空状态提示不应显示
        val emptyText = context.getString(R.string.mark_records_empty_all)
        composeRule.onAllNodesWithText(emptyText).fetchSemanticsNodes().also {
            assertThat(it).isEmpty()
        }
        // 进度指示器显示
        composeRule.onNode(hasProgressBar()).assertIsDisplayed()
    }

    @Test
    fun `点击卡片触发OnMovieClick`() {
        val title = "点击电影测试"
        var clickedTraktId = -1
        composeRule.setContent {
            MarkRecordScreen(
                onBack = {},
                onMovieClick = { traktId, _, _, _, _ -> clickedTraktId = traktId },
                onShowClick = { _, _, _, _, _ -> },
                viewModel = createMockViewModel(
                    uiState = MarkRecordUiState(
                        items = listOf(createMovieItem(traktId = 42, title = title))
                    )
                )
            )
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(title).onFirst().performClick()
        composeRule.waitForIdle()
        assertThat(clickedTraktId).isEqualTo(42)
    }

    @Test
    fun `点击卡片触发OnShowClick`() {
        val title = "点击剧集测试"
        var clickedTraktId = -1
        composeRule.setContent {
            MarkRecordScreen(
                onBack = {},
                onMovieClick = { _, _, _, _, _ -> },
                onShowClick = { traktId, _, _, _, _ -> clickedTraktId = traktId },
                viewModel = createMockViewModel(
                    uiState = MarkRecordUiState(
                        items = listOf(createShowItem(traktId = 99, title = title))
                    )
                )
            )
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(title).onFirst().performClick()
        composeRule.waitForIdle()
        assertThat(clickedTraktId).isEqualTo(99)
    }

    @Test
    fun `返回按钮触发OnBack`() {
        var backClicked = false
        composeRule.setContent {
            MarkRecordScreen(
                onBack = { backClicked = true },
                onMovieClick = { _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _ -> },
                viewModel = createMockViewModel()
            )
        }
        composeRule.waitForIdle()
        val backDesc = context.getString(R.string.content_desc_back)
        composeRule.onNodeWithContentDescription(backDesc).performClick()
        composeRule.waitForIdle()
        assertThat(backClicked).isTrue()
    }

    @Test
    fun `搜索输入触发updateSearchQuery`() {
        val viewModel = createMockViewModel()
        composeRule.setContent {
            MarkRecordScreen(
                onBack = {},
                onMovieClick = { _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _ -> },
                viewModel = viewModel
            )
        }
        composeRule.waitForIdle()
        val searchHint = context.getString(R.string.mark_records_search_hint)
        composeRule.onNodeWithText(searchHint).performTextInput("test")
        composeRule.waitForIdle()
        verify { viewModel.updateSearchQuery("test") }
        // 点击 Tab 移除文本框焦点，关闭软键盘，避免 Activity teardown 卡在 PAUSED 状态
        val allTabLabel = context.getString(R.string.mark_records_tab_all)
        composeRule.onNodeWithText(allTabLabel).performClick()
        composeRule.waitForIdle()
    }

    // ============ Helper ============

    /**
     * 创建 MarkRecordViewModel 的 relaxed mock,显式 mock uiState StateFlow。
     *
     * 默认 uiState 为空列表状态(ALL Tab,无加载,无错误),模拟已加载但无数据的场景。
     */
    private fun createMockViewModel(
        uiState: MarkRecordUiState = MarkRecordUiState()
    ): MarkRecordViewModel {
        val mock = mockk<MarkRecordViewModel>(relaxed = true)
        every { mock.uiState } returns MutableStateFlow(uiState)
        return mock
    }

    /** 创建 movie 类型的 MarkRecordItem */
    private fun createMovieItem(
        traktId: Int = 1,
        title: String = "电影"
    ) = MarkRecordItem(
        traktId = traktId,
        tmdbId = 100,
        imdbId = "tt123",
        mediaType = "movie",
        title = title,
        displayTitle = title,
        posterUrl = null,
        year = 2024,
        actionType = "ADD_WATCHLIST",
        actedAt = System.currentTimeMillis(),
        episodeInfo = null,
        currentStatus = null
    )

    /** 创建 show 类型的 MarkRecordItem */
    private fun createShowItem(
        traktId: Int = 1,
        title: String = "剧集"
    ) = MarkRecordItem(
        traktId = traktId,
        tmdbId = 200,
        imdbId = "tt456",
        mediaType = "show",
        title = title,
        displayTitle = title,
        posterUrl = null,
        year = 2023,
        actionType = "WATCHED",
        actedAt = System.currentTimeMillis(),
        episodeInfo = null,
        currentStatus = null
    )

    /** 匹配具有 ProgressBarRangeInfo 语义的节点(CircularProgressIndicator) */
    private fun hasProgressBar() = SemanticsMatcher("has ProgressBarRangeInfo") { node ->
        node.config.contains(SemanticsProperties.ProgressBarRangeInfo)
    }
}
