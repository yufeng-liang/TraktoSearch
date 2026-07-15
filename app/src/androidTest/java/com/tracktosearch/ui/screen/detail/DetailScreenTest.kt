package com.tracktosearch.ui.screen.detail

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
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.MultiRatings
import com.tracktosearch.data.util.PosterColorExtractor
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
 * DetailScreen 页面 Instrumented UI 测试。
 *
 * 测试策略:
 * - 使用 `createAndroidComposeRule<HiltTestActivity>()`:HiltTestActivity 被
 *   `@AndroidEntryPoint` 注解,满足 DetailScreen 内部 CompositionLocal 访问需求。
 * - DetailViewModel 通过参数直接传入 mock,不走 hiltViewModel()。
 *   DetailViewModel 有14个依赖和静态缓存,直接 mock 整个 ViewModel 绕过 Hilt 工厂。
 * - 显式 mock 渲染期读取的 StateFlow/SharedFlow:
 *   uiState、toastEvent、posterColorExtractor(val)。
 * - DetailScreen 使用10个 CompositionLocal,均有安全默认值(null/false/默认实例),
 *   无需额外提供。
 * - LazyColumn 惰性渲染,查找节点前用 performScrollToNode 滚动到目标。
 * - 字符串定位使用 InstrumentationRegistry.targetContext.getString() 获取当前 locale 文案。
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DetailScreenTest {

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
        setContent()
    }

    @Test
    fun `title_正常显示`() {
        setContent(uiState = DetailUiState(title = "测试电影", displayTitle = "测试电影"))
        composeRule.onNodeWithText("测试电影").assertIsDisplayed()
    }

    @Test
    fun `加载状态不崩溃`() {
        setContent(uiState = DetailUiState(isLoading = true))
    }

    @Test
    fun `已登录时显示操作按钮`() {
        setContent(uiState = DetailUiState(isLoggedIn = true))
        val watchlistLabel = context.getString(R.string.detail_mark_watchlist)
        scrollToText(watchlistLabel)
        composeRule.onNodeWithText(watchlistLabel).assertIsDisplayed()
    }

    @Test
    fun `未登录时点击操作触发_onNavigateToLogin`() {
        var loginNavigated = false
        setContent(
            uiState = DetailUiState(showLoginPrompt = true),
            onNavigateToLogin = { loginNavigated = true }
        )
        val loginBtn = context.getString(R.string.detail_login_go)
        composeRule.onNodeWithText(loginBtn).performClick()
        composeRule.waitForIdle()
        assertThat(loginNavigated).isTrue()
    }

    @Test
    fun `initialInWatchlist_true_时想看按钮选中态`() {
        setContent(uiState = DetailUiState(isMarkedWatchlist = true))
        val markedLabel = context.getString(R.string.detail_marked_watchlist)
        scrollToText(markedLabel)
        composeRule.onNodeWithText(markedLabel).assertIsDisplayed()
    }

    @Test
    fun `initialIsWatched_true_时已看按钮选中态`() {
        setContent(uiState = DetailUiState(isMarkedWatched = true))
        val watchedLabel = context.getString(R.string.detail_marked_watched)
        scrollToText(watchedLabel)
        composeRule.onNodeWithText(watchedLabel).assertIsDisplayed()
    }

    @Test
    fun `电视剧类型渲染不崩溃`() {
        setContent(mediaType = MediaType.SHOW)
    }

    @Test
    fun `返回按钮触发_onBack`() {
        var backCalled = false
        setContent(onBack = { _, _ -> backCalled = true })
        val backDesc = context.getString(R.string.detail_back)
        composeRule.onNodeWithContentDescription(backDesc).performClick()
        composeRule.waitForIdle()
        assertThat(backCalled).isTrue()
    }

    @Test
    fun `错误状态显示错误信息`() {
        setContent(
            uiState = DetailUiState(
                commentsError = true,
                comments = emptyList(),
                sectionVisible = DetailSectionVisibility(comments = true)
            )
        )
        val commentsTab = context.getString(R.string.detail_tab_comments) + "(0)"
        scrollToText(commentsTab)
        composeRule.onAllNodesWithText(commentsTab).onFirst().performClick()
        composeRule.waitForIdle()
        val errorText = context.getString(R.string.detail_load_error)
        composeRule.onAllNodesWithText(errorText).onFirst().assertIsDisplayed()
    }

    @Test
    fun `资源搜索状态不崩溃`() {
        setContent(uiState = DetailUiState(isSearching = true))
        val searchingText = context.getString(R.string.detail_searching)
        scrollToText(searchingText)
        composeRule.onNodeWithText(searchingText).assertIsDisplayed()
    }

    @Test
    fun `traktRating_显示`() {
        setContent(
            uiState = DetailUiState(
                ratings = MultiRatings(traktRating = 8.5, tmdbRating = 7.5)
            )
        )
        // 评分行渲染后 TMDB 评分值应可见
        val tmdbRatingText = String.format("%.1f", 7.5)
        scrollToText(tmdbRatingText)
        composeRule.onAllNodesWithText(tmdbRatingText).onFirst().assertIsDisplayed()
    }

    // ============ Helper ============

    private fun setContent(
        uiState: DetailUiState = DetailUiState(),
        mediaType: MediaType = MediaType.MOVIE,
        onBack: (Boolean, Boolean) -> Unit = { _, _ -> },
        onNavigateToLogin: () -> Unit = {}
    ) {
        composeRule.setContent {
            DetailScreen(
                traktId = 1,
                tmdbId = 1,
                title = "测试电影",
                mediaType = mediaType,
                onBack = onBack,
                onNavigateToLogin = onNavigateToLogin,
                viewModel = createMockViewModel(uiState)
            )
        }
        composeRule.waitForIdle()
    }

    /**
     * 创建 DetailViewModel 的 relaxed mock,显式 mock 渲染期读取的 StateFlow/SharedFlow/属性。
     * - uiState: 提供 MutableStateFlow 持有自定义 DetailUiState
     * - toastEvent: 空 MutableSharedFlow,不发射任何 Toast 事件
     * - posterColorExtractor: relaxed mock(海报主色提取器,测试中海报 URL 为 null 不会触发提取)
     */
    private fun createMockViewModel(uiState: DetailUiState): DetailViewModel {
        val mock = mockk<DetailViewModel>(relaxed = true)
        every { mock.uiState } returns MutableStateFlow(uiState)
        every { mock.toastEvent } returns MutableSharedFlow()
        every { mock.posterColorExtractor } returns mockk<PosterColorExtractor>(relaxed = true)
        return mock
    }

    /**
     * 滚动 LazyColumn 到包含指定文本的节点。
     * DetailScreen 使用 LazyColumn 惰性渲染,不可见 item 不会被组合,
     * 需要先滚动到目标节点才能查找和交互。
     *
     * 注意: DetailScreen 存在多个可滚动节点(主 LazyColumn + FilterSection 的两个 LazyRow),
     * 不能用 onNode(hasScrollAction()) 期望恰好 1 个,改用 onAllNodes().onFirst()
     * 定位主 LazyColumn(它在组合树中第一个被创建)。
     */
    private fun scrollToText(text: String) {
        composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    }
}
