package com.tracktosearch.ui.screen.watchlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.HiltTestActivity
import com.tracktosearch.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WatchlistScreen 页面 Instrumented UI 测试。
 *
 * 测试策略:
 * - 使用 `createAndroidComposeRule<HiltTestActivity>()`:HiltTestActivity 被
 *   `@AndroidEntryPoint` 注解(位于 debug source set),满足 `hiltViewModel()` 对
 *   承载 Activity 实现 `GeneratedComponentManager` 的要求。
 * - WatchlistViewModel 通过参数直接传入 mock,不走 hiltViewModel()。
 * - WatchlistScreen 内部无其他 hiltViewModel() 调用,但 WatchlistPosterCard
 *   使用 EntryPointAccessors.fromApplication 获取 PosterColorExtractorProvider,
 *   HiltTestApplication 已安装 Hilt 组件,可正常解析。
 * - WatchlistScreen 使用 LazyVerticalGrid 惰性渲染,查找节点前需用
 *   performScrollToNode 滚动到目标。
 * - 所有 CompositionLocal 均有安全默认值,无需在测试中显式提供。
 * - 字符串定位使用 InstrumentationRegistry.targetContext.getString() 获取当前 locale 文案。
 *
 * 测试覆盖:
 * - 默认状态/加载状态/空数据/错误状态渲染不崩溃
 * - 有数据时显示海报标题
 * - 点击筛选按钮不崩溃
 * - 已看模式切换不崩溃
 * - 搜索框默认渲染不崩溃
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WatchlistScreenTest {

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
    fun `默认状态渲染不崩溃`() {
        composeRule.setContent {
            WatchlistScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onSearchClick = {},
                onTraktSearch = { _, _ -> },
                viewModel = createMockWatchlistViewModel()
            )
        }
        composeRule.waitForIdle()
        // 想看模式标签始终可见
        val watchlistLabel = context.getString(R.string.watchlist_mode_watchlist)
        composeRule.onNodeWithText(watchlistLabel).assertIsDisplayed()
    }

    @Test
    fun `加载状态不崩溃`() {
        composeRule.setContent {
            WatchlistScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onSearchClick = {},
                onTraktSearch = { _, _ -> },
                viewModel = createMockWatchlistViewModel(
                    uiState = WatchlistUiState(
                        isLoadingMovies = true,
                        moviesLoaded = false,
                        movies = emptyList()
                    )
                )
            )
        }
        composeRule.waitForIdle()
        // 骨架屏渲染不崩溃即通过
    }

    @Test
    fun `空数据状态显示空提示`() {
        composeRule.setContent {
            WatchlistScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onSearchClick = {},
                onTraktSearch = { _, _ -> },
                viewModel = createMockWatchlistViewModel()
            )
        }
        composeRule.waitForIdle()
        // 空状态显示 watchlist_empty_title
        val emptyTitle = context.getString(R.string.watchlist_empty_title)
        composeRule.onNodeWithText(emptyTitle).assertIsDisplayed()
    }

    @Test
    fun `有数据时显示海报标题`() {
        val movieTitle = "测试电影标题"
        composeRule.setContent {
            WatchlistScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onSearchClick = {},
                onTraktSearch = { _, _ -> },
                viewModel = createMockWatchlistViewModel(
                    uiState = WatchlistUiState(
                        movies = listOf(
                            MediaUiItem(
                                traktId = 1,
                                tmdbId = 100,
                                title = movieTitle,
                                displayTitle = movieTitle,
                                year = 2024,
                                genres = "动作",
                                posterUrl = null
                            )
                        ),
                        moviesLoaded = true
                    )
                )
            )
        }
        composeRule.waitForIdle()
        // 海报卡片标题在 LazyVerticalGrid 中渲染
        scrollToText(movieTitle)
        composeRule.onAllNodesWithText(movieTitle).onFirst().assertIsDisplayed()
    }

    @Test
    fun `点击筛选按钮不崩溃`() {
        composeRule.setContent {
            WatchlistScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onSearchClick = {},
                onTraktSearch = { _, _ -> },
                viewModel = createMockWatchlistViewModel()
            )
        }
        composeRule.waitForIdle()
        // 筛选按钮(Icons.Rounded.Tune)的 contentDescription
        val filterDesc = context.getString(R.string.filter_title)
        composeRule.onNodeWithContentDescription(filterDesc).performClick()
        composeRule.waitForIdle()
        // 点击不崩溃即通过(筛选面板会打开)
    }

    @Test
    fun `onMovieClick_为空实现时不崩溃`() {
        composeRule.setContent {
            WatchlistScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onSearchClick = {},
                onTraktSearch = { _, _ -> },
                viewModel = createMockWatchlistViewModel(
                    uiState = WatchlistUiState(
                        movies = listOf(
                            MediaUiItem(
                                traktId = 2,
                                tmdbId = 200,
                                title = "空回调电影",
                                displayTitle = "空回调电影",
                                year = 2023,
                                genres = "喜剧",
                                posterUrl = null
                            )
                        ),
                        moviesLoaded = true
                    )
                )
            )
        }
        composeRule.waitForIdle()
        // 渲染包含数据的栏目不应崩溃
        scrollToText("空回调电影")
        composeRule.onAllNodesWithText("空回调电影").onFirst().assertIsDisplayed()
    }

    @Test
    fun `错误状态不崩溃`() {
        composeRule.setContent {
            WatchlistScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onSearchClick = {},
                onTraktSearch = { _, _ -> },
                viewModel = createMockWatchlistViewModel(
                    uiState = WatchlistUiState(
                        moviesError = "加载失败",
                        moviesLoaded = true
                    )
                )
            )
        }
        composeRule.waitForIdle()
        // 错误状态不崩溃即通过
    }

    @Test
    fun `多数据项渲染不崩溃`() {
        composeRule.setContent {
            WatchlistScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onSearchClick = {},
                onTraktSearch = { _, _ -> },
                viewModel = createMockWatchlistViewModel(
                    uiState = WatchlistUiState(
                        movies = listOf(
                            MediaUiItem(
                                traktId = 1, tmdbId = 101,
                                title = "多数据项一", displayTitle = "多数据项一",
                                year = 2024, genres = "动作", posterUrl = null
                            ),
                            MediaUiItem(
                                traktId = 2, tmdbId = 102,
                                title = "多数据项二", displayTitle = "多数据项二",
                                year = 2023, genres = "喜剧", posterUrl = null
                            ),
                            MediaUiItem(
                                traktId = 3, tmdbId = 103,
                                title = "多数据项三", displayTitle = "多数据项三",
                                year = 2022, genres = "科幻", posterUrl = null
                            )
                        ),
                        moviesLoaded = true
                    )
                )
            )
        }
        composeRule.waitForIdle()
        // 多数据项渲染不崩溃,滚动到最后一项验证
        scrollToText("多数据项三")
        composeRule.onAllNodesWithText("多数据项三").onFirst().assertIsDisplayed()
    }

    @Test
    fun `已看模式切换不崩溃`() {
        composeRule.setContent {
            WatchlistScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onSearchClick = {},
                onTraktSearch = { _, _ -> },
                viewModel = createMockWatchlistViewModel()
            )
        }
        composeRule.waitForIdle()
        // 点击"已看"模式标签切换模式
        val watchedLabel = context.getString(R.string.watchlist_mode_watched)
        composeRule.onNodeWithText(watchedLabel).performClick()
        composeRule.waitForIdle()
        // 点击不崩溃即通过(mode 切换后显示 watched_empty_title)
    }

    @Test
    fun `搜索框默认渲染不崩溃`() {
        composeRule.setContent {
            WatchlistScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onSearchClick = {},
                onTraktSearch = { _, _ -> },
                viewModel = createMockWatchlistViewModel()
            )
        }
        composeRule.waitForIdle()
        // 搜索框 placeholder 文字
        val searchPlaceholder = context.getString(R.string.watchlist_search_watchlist)
        composeRule.onNodeWithText(searchPlaceholder).assertIsDisplayed()
    }

    // ============ Helper ============

    /**
     * 滚动 LazyVerticalGrid 到包含指定文本的节点。
     * 用 onAllNodes(hasScrollAction()).onFirst() 取第一个滚动容器,
     * 避免嵌套滚动容器导致多节点异常。
     */
    private fun scrollToText(text: String) {
        composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    }

    /**
     * 创建 WatchlistViewModel 的 relaxed mock,显式 mock 所有渲染期读取的 StateFlow/SharedFlow。
     *
     * 默认 uiState 为空列表状态,模拟已加载但无数据的场景。
     * needFirstSyncGuide 默认 false,避免弹出首次同步引导弹窗。
     */
    private fun createMockWatchlistViewModel(
        uiState: WatchlistUiState = WatchlistUiState()
    ): WatchlistViewModel {
        val mock = mockk<WatchlistViewModel>(relaxed = true)
        every { mock.uiState } returns MutableStateFlow(uiState)
        every { mock.filterState } returns MutableStateFlow(FilterState())
        every { mock.hasActiveFilters } returns MutableStateFlow(false)
        every { mock.availableGenres } returns MutableStateFlow(emptyList())
        every { mock.decadeOptions } returns MutableStateFlow(emptyList())
        every { mock.needFirstSyncGuide } returns MutableStateFlow(false)
        every { mock.syncCompleteEvent } returns MutableSharedFlow()
        every { mock.consistencyCheckCompleteEvent } returns MutableSharedFlow()
        every { mock.isDoubanLoggedIn() } returns false
        every { mock.isBatchRemovalRunning() } returns false
        return mock
    }
}
