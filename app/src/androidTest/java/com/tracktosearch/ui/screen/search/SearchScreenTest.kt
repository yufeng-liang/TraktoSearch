package com.tracktosearch.ui.screen.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiAudio
import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.data.local.SearchHistoryItem
import com.tracktosearch.ui.screen.ai.AiSpriteUiState
import com.tracktosearch.ui.screen.ai.AiSpriteViewModel
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
 * SearchScreen 页面 Instrumented UI 测试。
 *
 * 测试策略：使用 HiltAndroidTest + createComposeRule（空壳 ComponentActivity）。
 * SearchScreen 内部通过 EntryPointAccessors 从 Application（HiltTestApplication）获取
 * ViewedStorageProvider 和 CloudThemeProvider，不需要 MainActivity 作为载体。
 * MainActivity.onCreate 自带 setContent，会与 composeRule.setContent 冲突，故不使用。
 *
 * 字符串定位使用 InstrumentationRegistry.targetContext.getString() 获取当前 locale 文案。
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SearchScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun `搜索框默认渲染_不崩溃`() {
        composeRule.setContent {
            SearchScreen(
                onBack = {},
                onSearchClick = {},
                viewModel = createMockViewModel()
            )
        }
        composeRule.waitForIdle()
        val title = context.getString(R.string.search_title)
        composeRule.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun `输入关键词不崩溃`() {
        composeRule.setContent {
            SearchScreen(
                onBack = {},
                onSearchClick = {},
                viewModel = createMockViewModel()
            )
        }
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performTextInput("测试电影")
        composeRule.waitForIdle()
    }

    @Test
    fun `initialKeyword_非空时显示初始关键词`() {
        composeRule.setContent {
            SearchScreen(
                initialKeyword = "初始关键词",
                onBack = {},
                onSearchClick = {},
                viewModel = createMockViewModel()
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("初始关键词").assertIsDisplayed()
    }

    @Test
    fun `返回按钮触发_onBack`() {
        var backClicked = false
        composeRule.setContent {
            SearchScreen(
                // 传入非空 initialKeyword 使 searchQuery 非空 → isActive=true → 返回按钮显示
                initialKeyword = "test",
                onBack = { backClicked = true },
                onSearchClick = {},
                viewModel = createMockViewModel()
            )
        }
        composeRule.waitForIdle()
        val backDesc = context.getString(R.string.search_back)
        composeRule.onNodeWithContentDescription(backDesc).performClick()
        composeRule.waitForIdle()
        assertThat(backClicked).isTrue()
    }

    @Test
    fun `onBack_为null时不崩溃`() {
        composeRule.setContent {
            SearchScreen(
                onBack = null,
                onSearchClick = {},
                viewModel = createMockViewModel()
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `onSearchClick_为null时不崩溃`() {
        composeRule.setContent {
            SearchScreen(
                onBack = {},
                onSearchClick = null,
                viewModel = createMockViewModel()
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `搜索类型选择器渲染不崩溃`() {
        composeRule.setContent {
            SearchScreen(
                onBack = {},
                onSearchClick = {},
                viewModel = createMockViewModel(),
                searchSourceType = SearchSourceType.MOVIE,
                onSearchSourceTypeChange = {}
            )
        }
        composeRule.waitForIdle()
        val typeLabel = context.getString(R.string.search_type_movie)
        composeRule.onNodeWithText(typeLabel).assertIsDisplayed()
    }

    @Test
    fun `加载状态不崩溃`() {
        composeRule.setContent {
            SearchScreen(
                onBack = {},
                onSearchClick = {},
                viewModel = createMockViewModel(isLoading = true)
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `热门搜索词显示`() {
        composeRule.setContent {
            SearchScreen(
                onBack = {},
                onSearchClick = {},
                viewModel = createMockViewModel(hotSearches = listOf("盗梦空间", "星际穿越"))
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("盗梦空间").assertIsDisplayed()
    }

    @Test
    fun `搜索历史显示`() {
        composeRule.setContent {
            SearchScreen(
                onBack = {},
                onSearchClick = {},
                viewModel = createMockViewModel(
                    searchHistory = listOf(SearchHistoryItem("测试电影", "disk"))
                )
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("测试电影").assertIsDisplayed()
    }

    @Test
    fun `已激活精灵在搜索页空闲时显示互动浮层`() {
        val spriteViewModel = mockk<AiSpriteViewModel>(relaxed = true)
        every { spriteViewModel.uiState } returns MutableStateFlow(
            AiSpriteUiState(
                characters = listOf(AiCharacter("usagi", "乌萨奇", "乌萨奇", isAvailable = true)),
                selectedCharacterId = "usagi",
                activatedCharacterId = "usagi"
            )
        )
        every { spriteViewModel.audioEvents } returns MutableSharedFlow<AiAudio>()

        composeRule.setContent {
            SearchScreen(
                onBack = {},
                onSearchClick = {},
                viewModel = createMockViewModel(),
                spriteViewModel = spriteViewModel
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("乌萨奇").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.ai_feature_quiz)).assertIsDisplayed()
    }

    private fun createMockViewModel(
        isLoading: Boolean = false,
        error: String? = null,
        hotSearches: List<String> = emptyList(),
        searchHistory: List<SearchHistoryItem> = emptyList()
    ): SearchViewModel {
        val mock = mockk<SearchViewModel>(relaxed = true)
        val uiState = SearchUiState(
            isLoading = isLoading,
            error = error,
            searchHistory = searchHistory
        )
        every { mock.uiState } returns MutableStateFlow(uiState)
        every { mock.hotSearches } returns MutableStateFlow(hotSearches)
        return mock
    }
}
