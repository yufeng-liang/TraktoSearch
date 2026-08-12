package com.tracktosearch.ui.screen.watchlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.HiltTestActivity
import com.tracktosearch.R
import com.tracktosearch.data.repository.DoubanSyncProgress
import com.tracktosearch.data.repository.WatchlistMediaType
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
    fun `豆瓣only条目使用独立动画状态`() {
        val firstTitle = "豆瓣only条目一"
        val secondTitle = "豆瓣only条目二"
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
                                traktId = 0,
                                tmdbId = 0,
                                title = firstTitle,
                                displayTitle = firstTitle,
                                year = 2024,
                                genres = "剧情",
                                posterUrl = null,
                                doubanId = "db-animation-1",
                                mediaType = WatchlistMediaType.MOVIE
                            ),
                            MediaUiItem(
                                traktId = 0,
                                tmdbId = 0,
                                title = secondTitle,
                                displayTitle = secondTitle,
                                year = 2023,
                                genres = "喜剧",
                                posterUrl = null,
                                doubanId = "db-animation-2",
                                mediaType = WatchlistMediaType.MOVIE
                            )
                        ),
                        moviesLoaded = true
                    )
                )
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(firstTitle).assertIsDisplayed()
        composeRule.onNodeWithText(secondTitle).assertIsDisplayed()
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
        composeRule.onNodeWithTag("watchlist_mode_tab_0").assertIsSelected()
        composeRule.onNodeWithTag("watchlist_mode_tab_1").assertIsNotSelected()

        // 点击"已看"模式标签切换模式
        composeRule.onNodeWithTag("watchlist_mode_tab_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("watchlist_mode_tab_0").assertIsNotSelected()
        composeRule.onNodeWithTag("watchlist_mode_tab_1").assertIsSelected()
    }

    @Test
    fun `搜索入口默认收起为图标`() {
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
        val searchDescription = context.getString(R.string.watchlist_search)
        composeRule.onNodeWithContentDescription(searchDescription).assertIsDisplayed()
        val searchPlaceholder = context.getString(R.string.watchlist_search_watchlist)
        composeRule.onAllNodesWithText(searchPlaceholder).fetchSemanticsNodes().also {
            assertThat(it.size).isEqualTo(0)
        }
    }

    @Test
    fun `点击搜索图标展开并保留查询`() {
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

        val searchDescription = context.getString(R.string.watchlist_search)
        val searchPlaceholder = context.getString(R.string.watchlist_search_watchlist)
        composeRule.onNodeWithContentDescription(searchDescription).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(searchPlaceholder).assertIsDisplayed()

        composeRule.onNodeWithTag("watchlist_search_input").performTextInput("星际")
        composeRule.onNodeWithText("星际").assertIsDisplayed()
    }

    @Test
    fun `展开搜索时隐藏标题收起时恢复`() {
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

        val titleText = context.getString(R.string.tab_me)
        // 初始:标题可见
        composeRule.onNodeWithText(titleText).assertIsDisplayed()

        // 点击搜索图标展开:标题淡出后从组合树移除
        val searchDescription = context.getString(R.string.watchlist_search)
        composeRule.onNodeWithContentDescription(searchDescription).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(titleText).assertDoesNotExist()

        // 点击分类 Tab 收起搜索:标题恢复显示
        composeRule.onNodeWithTag("watchlist_category_tab_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(titleText).assertIsDisplayed()
    }

    @Test
    fun `展开搜索时点击分类会收起并切换分类`() {
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

        val searchDescription = context.getString(R.string.watchlist_search)
        val searchPlaceholder = context.getString(R.string.watchlist_search_watchlist)
        composeRule.onNodeWithContentDescription(searchDescription).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("watchlist_category_tab_1").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(searchPlaceholder).fetchSemanticsNodes().also {
            assertThat(it.size).isEqualTo(0)
        }
        composeRule.onNodeWithTag("watchlist_category_tab_1").assertIsDisplayed()
    }

    @Test
    fun `分类Tab显示独立数量徽标和等宽触控区域`() {
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

        composeRule.onNodeWithText(context.getString(R.string.watchlist_tab_movies)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.watchlist_tab_shows)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.detail_disk_type_other)).assertIsDisplayed()
        assertThat(composeRule.onAllNodesWithText("0").fetchSemanticsNodes().size).isEqualTo(3)
        composeRule.onNodeWithTag("watchlist_category_tab_0").assertIsDisplayed()
        composeRule.onNodeWithTag("watchlist_category_tab_1").assertIsDisplayed()
        composeRule.onNodeWithTag("watchlist_category_tab_2").assertIsDisplayed()
    }

    // ============ 豆瓣同步交互 ============

    @Test
    fun `同步进行中显示同步横幅`() {
        composeRule.setContent {
            WatchlistScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onSearchClick = {},
                onTraktSearch = { _, _ -> },
                viewModel = createMockWatchlistViewModel(
                    uiState = WatchlistUiState(
                        doubanSyncProgress = DoubanSyncProgress(
                            isRunning = true,
                            current = 5,
                            total = 100,
                            phase = "同步中"
                        )
                    )
                )
            )
        }
        composeRule.waitForIdle()
        // 横幅文案格式: "阶段 (current/total)"
        composeRule.onNodeWithText("同步中 (5/100)").assertIsDisplayed()
    }

    @Test
    fun `同步未进行时不显示横幅`() {
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
        // doubanSyncProgress = null (默认) 时横幅不显示
        composeRule.onAllNodesWithText("同步中").fetchSemanticsNodes().also {
            assertThat(it).isEmpty()
        }
    }

    @Test
    fun `点击同步横幅打开DoubanSyncDialog`() {
        composeRule.setContent {
            WatchlistScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onSearchClick = {},
                onTraktSearch = { _, _ -> },
                viewModel = createMockWatchlistViewModel(
                    uiState = WatchlistUiState(
                        doubanSyncProgress = DoubanSyncProgress(
                            isRunning = true,
                            current = 5,
                            total = 100,
                            phase = "同步中"
                        )
                    )
                )
            )
        }
        composeRule.waitForIdle()
        // 点击同步横幅
        composeRule.onNodeWithText("同步中 (5/100)").performClick()
        composeRule.waitForIdle()
        // DoubanSyncDialog 打开,显示标题
        val dialogTitle = context.getString(R.string.douban_sync_title)
        composeRule.onNodeWithText(dialogTitle).assertIsDisplayed()
    }

    @Test
    fun `cookie过期时横幅显示重新登录提示`() {
        composeRule.setContent {
            WatchlistScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onSearchClick = {},
                onTraktSearch = { _, _ -> },
                viewModel = createMockWatchlistViewModel(
                    uiState = WatchlistUiState(
                        doubanSyncProgress = DoubanSyncProgress(
                            isComplete = true,
                            cookieExpired = true
                        )
                    )
                )
            )
        }
        composeRule.waitForIdle()
        // cookie 过期时横幅显示过期提示和重新登录按钮
        val expiredBanner = context.getString(R.string.douban_sync_cookie_expired_banner)
        val reloginText = context.getString(R.string.douban_sync_relogin)
        composeRule.onNodeWithText(expiredBanner).assertIsDisplayed()
        composeRule.onNodeWithText(reloginText).assertIsDisplayed()
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
    @Test
    fun tmdbUnavailable_rendersPosterErrorOnCard() {
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
                                title = "测试电影",
                                displayTitle = "测试电影",
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

        composeRule
            .onNodeWithText(context.getString(R.string.watchlist_poster_error))
            .assertIsDisplayed()
    }

    private fun createMockWatchlistViewModel(
        uiState: WatchlistUiState = WatchlistUiState()
    ): WatchlistViewModel {
        val mock = mockk<WatchlistViewModel>(relaxed = true)
        every { mock.uiState } returns MutableStateFlow(uiState)
        every { mock.filterState } returns MutableStateFlow(FilterState())
        every { mock.hasActiveFilters } returns MutableStateFlow(false)
        every { mock.availableGenres } returns MutableStateFlow(emptyList())
        every { mock.decadeOptions } returns MutableStateFlow(emptyList())
        every { mock.isTraktConnected } returns MutableStateFlow(false)
        every { mock.isDoubanLoggedInFlow } returns MutableStateFlow(false)
        every { mock.needFirstSyncGuide } returns MutableStateFlow(false)
        every { mock.syncCompleteEvent } returns MutableSharedFlow()
        every { mock.consistencyCheckCompleteEvent } returns MutableSharedFlow()
        every { mock.isDoubanLoggedIn() } returns false
        every { mock.isBatchRemovalRunning() } returns false
        return mock
    }
}
