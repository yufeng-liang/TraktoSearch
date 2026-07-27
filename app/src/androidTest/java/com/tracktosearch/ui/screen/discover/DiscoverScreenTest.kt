package com.tracktosearch.ui.screen.discover

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
import com.tracktosearch.data.local.DiscoverSectionConfig
import com.tracktosearch.data.local.DiscoverSectionStorage
import com.tracktosearch.data.remote.douban.dto.DoubanRecommendItem
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.remote.trakt.dto.TraktListInfo
import com.tracktosearch.data.remote.trakt.dto.TraktListIds
import com.tracktosearch.data.remote.trakt.dto.TraktListUser
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingListResponse
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.ui.screen.search.DoubanHotCategory
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
 * DiscoverScreen 页面 Instrumented UI 测试。
 *
 * 测试策略:
 * - 使用 `createAndroidComposeRule<HiltTestActivity>()`:HiltTestActivity 被
 *   `@AndroidEntryPoint` 注解(位于 debug source set),满足 `hiltViewModel()` 对
 *   承载 Activity 实现 `GeneratedComponentManager` 的要求。
 * - DiscoverScreen 默认路径不会调用内部 `hiltViewModel<SettingsViewModel>()`
 *   (仅当 showDiscoverSectionsDialog=true 即用户点击栏目管理按钮时触发),
 *   故大多数测试无需预填充 SettingsViewModel。
 * - DiscoverViewModel 通过参数直接传入 mock,不走 hiltViewModel()。
 * - DiscoverScreen 使用 LazyColumn 惰性渲染,查找节点前需用 `performScrollToNode`
 *   滚动到目标。
 * - 所有 CompositionLocal(LocalSharedTransitionScope, LocalAnimatedVisibilityScope,
 *   LocalActivePoster 系列, LocalScrollToTopProvider 等)均有安全默认值(null, false,
 *   -1, 默认实例),无需在测试中显式提供。
 * - 字符串定位使用 InstrumentationRegistry.targetContext.getString() 获取当前 locale 文案。
 *
 * 测试覆盖:
 * - 默认状态/加载状态/空数据/错误状态渲染不崩溃
 * - 关键回调(onFilterDiscoverClick/onDoubanLoginClick/onListClick)
 * - 有数据时显示栏目标题
 * - 豆瓣推荐 Tab 切换不崩溃
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DiscoverScreenTest {

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
            DiscoverScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                viewModel = createMockDiscoverViewModel()
            )
        }
        composeRule.waitForIdle()
        val title = context.getString(R.string.discover_title)
        composeRule.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun `加载状态不崩溃`() {
        composeRule.setContent {
            DiscoverScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                viewModel = createMockDiscoverViewModel(
                    uiState = DiscoverUiState(
                        isLoadingPopular = true,
                        isLoadingUpcoming = true,
                        isLoadingTrakt = true,
                        isLoadingRecommendations = true,
                        isLoadingTraktLists = true
                    )
                )
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `点击筛选按钮触发_onFilterDiscoverClick`() {
        var filterClicked = false
        composeRule.setContent {
            DiscoverScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onFilterDiscoverClick = { filterClicked = true },
                viewModel = createMockDiscoverViewModel()
            )
        }
        composeRule.waitForIdle()
        // 右上角筛选图标(IconButton with FilterList icon)的 contentDescription
        val filterDesc = context.getString(R.string.discover_filter_title)
        composeRule.onNodeWithContentDescription(filterDesc).performClick()
        composeRule.waitForIdle()
        assertThat(filterClicked).isTrue()
    }

    @Test
    fun `点击豆瓣登录触发_onDoubanLoginClick`() {
        var doubanLoginClicked = false
        composeRule.setContent {
            DiscoverScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onDoubanLoginClick = { doubanLoginClicked = true },
                viewModel = createMockDiscoverViewModel(
                    uiState = DiscoverUiState(
                        doubanRecommendState = DoubanRecommendState.NotLoggedIn
                    )
                )
            )
        }
        composeRule.waitForIdle()
        // 滚动到「猜你喜欢」栏目(NotLoggedIn 状态显示登录按钮)
        val loginButtonText = context.getString(R.string.discover_douban_recommend_login_button)
        scrollToText(loginButtonText)
        composeRule.onNodeWithText(loginButtonText).performClick()
        composeRule.waitForIdle()
        assertThat(doubanLoginClicked).isTrue()
    }

    @Test
    fun `有数据时显示栏目标题`() {
        composeRule.setContent {
            DiscoverScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                viewModel = createMockDiscoverViewModel(
                    uiState = DiscoverUiState(
                        tmdbPopularMovies = listOf(
                            TmdbSearchResult(id = 1, title = "流行电影A")
                        )
                    )
                )
            )
        }
        composeRule.waitForIdle()
        // 趋势电影栏目标题(在 LazyColumn 中渲染)
        val trendingTitle = context.getString(R.string.discover_trending)
        scrollToText(trendingTitle)
        composeRule.onAllNodesWithText(trendingTitle).onFirst().assertIsDisplayed()
    }

    @Test
    fun `空数据状态不崩溃`() {
        composeRule.setContent {
            DiscoverScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                viewModel = createMockDiscoverViewModel(
                    uiState = DiscoverUiState()
                )
            )
        }
        composeRule.waitForIdle()
        // 底部筛选入口卡片始终渲染
        val filterMoreTitle = context.getString(R.string.discover_filter_more_title)
        scrollToText(filterMoreTitle)
        composeRule.onNodeWithText(filterMoreTitle).assertIsDisplayed()
    }

    @Test
    fun `错误状态显示错误信息`() {
        val errorMsg = context.getString(R.string.common_load_failed)
        composeRule.setContent {
            DiscoverScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                viewModel = createMockDiscoverViewModel(
                    uiState = DiscoverUiState(
                        popularError = errorMsg,
                        upcomingError = errorMsg
                    )
                )
            )
        }
        composeRule.waitForIdle()
        // 滚动到趋势电影栏目(ErrorRetryRow 显示错误信息 + 重试按钮)
        scrollToText(errorMsg)
        composeRule.onAllNodesWithText(errorMsg).onFirst().assertIsDisplayed()
    }

    @Test
    fun `鍗冲皢涓婃槧澶辫触_鐐瑰嚮鍔犺浇澶辫触鎵撳紑閿欒璇︽儏`() {
        val rawError = "Fetch cancelled"
        composeRule.setContent {
            DiscoverScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                viewModel = createMockDiscoverViewModel(
                    uiState = DiscoverUiState(upcomingError = rawError),
                    sectionIds = listOf("tmdb-upcoming")
                )
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.common_load_failed)).performClick()
        composeRule.onNodeWithText(rawError).assertIsDisplayed()
    }

    @Test
    fun `璞嗙摚鐑澶辫触_鐐瑰嚮淇℃伅鍥炬爣鎵撳紑閿欒璇︽儏`() {
        val rawError = "Douban request failed"
        composeRule.setContent {
            DiscoverScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                viewModel = createMockDiscoverViewModel(
                    uiState = DiscoverUiState(
                        doubanHotCategories = listOf(
                            DoubanHotCategory(
                                id = "douban-movie",
                                label = "",
                                error = rawError
                            )
                        )
                    ),
                    sectionIds = listOf("douban-movie")
                )
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(context.getString(R.string.error_detail_title)).performClick()
        composeRule.onNodeWithText(rawError).assertIsDisplayed()
    }

    @Test
    fun `onMovieClick_为空实现时不崩溃`() {
        composeRule.setContent {
            DiscoverScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                viewModel = createMockDiscoverViewModel(
                    uiState = DiscoverUiState(
                        tmdbPopularMovies = listOf(
                            TmdbSearchResult(id = 100, title = "测试电影")
                        )
                    )
                )
            )
        }
        composeRule.waitForIdle()
        // 渲染包含数据的栏目不应崩溃
        val trendingTitle = context.getString(R.string.discover_trending)
        scrollToText(trendingTitle)
        composeRule.onAllNodesWithText(trendingTitle).onFirst().assertIsDisplayed()
    }

    @Test
    fun `onListClick_回调可触发`() {
        var listClicked = false
        val listName = "我的测试列表"
        val listId = 12345
        composeRule.setContent {
            DiscoverScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onListClick = { id, name ->
                    assertThat(id).isEqualTo(listId)
                    assertThat(name).isEqualTo(listName)
                    listClicked = true
                },
                viewModel = createMockDiscoverViewModel(
                    uiState = DiscoverUiState(
                        trendingLists = listOf(
                            TraktTrendingListResponse(
                                like_count = 10,
                                comment_count = 2,
                                list = TraktListInfo(
                                    name = listName,
                                    item_count = 5,
                                    ids = TraktListIds(trakt = listId, slug = "test"),
                                    user = TraktListUser(username = "tester")
                                )
                            )
                        )
                    )
                )
            )
        }
        composeRule.waitForIdle()
        // 滚动到社区列表栏目并点击列表卡片
        scrollToText(listName)
        composeRule.onNodeWithText(listName).performClick()
        composeRule.waitForIdle()
        assertThat(listClicked).isTrue()
    }

    @Test
    fun `豆瓣推荐_tab_切换不崩溃`() {
        // 构造 Success 状态(电影/电视剧都有数据),点击 TV Tab 切换不应崩溃
        // 注意:switchRecommendTab 是 mock,不会真正切换状态,故仅验证点击不崩溃
        val movieItem = DoubanRecommendItem(
            id = "m1",
            title = "推荐电影",
            year = "2024"
        )
        val tvItem = DoubanRecommendItem(
            id = "t1",
            title = "推荐剧集",
            year = "2024"
        )
        composeRule.setContent {
            DiscoverScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                viewModel = createMockDiscoverViewModel(
                    uiState = DiscoverUiState(
                        doubanRecommendState = DoubanRecommendState.Success(
                            movieItems = listOf(movieItem),
                            tvItems = listOf(tvItem),
                            currentTab = RecommendTab.MOVIE
                        )
                    )
                )
            )
        }
        composeRule.waitForIdle()
        // 滚动到「猜你喜欢」栏目,点击 TV Tab
        val tvTabLabel = context.getString(R.string.discover_douban_recommend_tv)
        scrollToText(tvTabLabel)
        composeRule.onNodeWithText(tvTabLabel).performClick()
        composeRule.waitForIdle()
        // 点击不崩溃即通过
    }

    // ============ Helper ============

    /**
     * 滚动 LazyColumn 到包含指定文本的节点。
     * DiscoverScreen 使用 LazyColumn 惰性渲染,不可见 item 不会被组合,
     * 需要先滚动到目标节点才能查找和交互。
     *
     * 注意:页面内嵌套的 LazyRow(横向滚动)也会匹配 hasScrollAction(),
     * 用 onAllNodes().onFirst() 取第一个(即最外层 LazyColumn)避免多节点异常。
     */
    private fun scrollToText(text: String) {
        composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    }

    /**
     * 创建 DiscoverViewModel 的 relaxed mock,显式 mock 所有渲染期读取的 StateFlow/SharedFlow。
     *
     * 默认 sectionConfigs 包含所有栏目且可见,确保各栏目路径都会被组合。
     */
    private fun createMockDiscoverViewModel(
        uiState: DiscoverUiState = DiscoverUiState(),
        sectionIds: List<String> = DiscoverSectionStorage.ALL_SECTION_IDS
    ): DiscoverViewModel {
        val mock = mockk<DiscoverViewModel>(relaxed = true)
        every { mock.uiState } returns MutableStateFlow(uiState)
        // 默认所有栏目可见,顺序与 DiscoverSectionStorage.ALL_SECTION_IDS 一致
        every { mock.sectionConfigs } returns MutableStateFlow(
            sectionIds.mapIndexed { index, id ->
                DiscoverSectionConfig(id = id, visible = true, order = index)
            }
        )
        every { mock.watchlistWatchedIds } returns MutableStateFlow<TraktRepository.WatchlistWatchedIds?>(null)
        every { mock.toastEvent } returns MutableSharedFlow<Int>()
        return mock
    }
}
