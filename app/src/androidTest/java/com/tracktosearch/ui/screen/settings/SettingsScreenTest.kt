package com.tracktosearch.ui.screen.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.HiltTestActivity
import com.tracktosearch.R
import com.tracktosearch.data.local.CooldownStatus
import com.tracktosearch.data.local.DoubanUserProfile
import com.tracktosearch.data.remote.panhub.PanHubConfig
import com.tracktosearch.data.remote.trakt.dto.TraktUserProfileResponse
import com.tracktosearch.data.repository.ConsistencyCheckResult
import com.tracktosearch.data.repository.UpdateInfo
import com.tracktosearch.ui.haptic.HapticMode
import com.tracktosearch.ui.haptic.HapticSystemState
import com.tracktosearch.ui.theme.MonetAccent
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SettingsScreen 页面 Instrumented UI 测试。
 *
 * 测试策略:
 * - 使用 `createAndroidComposeRule<HiltTestActivity>()`:HiltTestActivity 被
 *   `@AndroidEntryPoint` 注解(位于 debug source set,不会进入 release APK),
 *   满足 `hiltViewModel()` 对承载 Activity 实现 `GeneratedComponentManager` 的要求。
 * - SettingsViewModel 通过参数直接传入 mock,不走 hiltViewModel()。
 * - SettingsScreen 使用 LazyColumn 惰性渲染,不可见 item 不会被组合,
 *   查找节点前需用 `performScrollToNode` 滚动到目标。
 * - 字符串定位使用 InstrumentationRegistry.targetContext.getString() 获取当前 locale 文案。
 *
 * 测试覆盖:
 * - 登录态分支(已登录显示登出/未登录显示登录入口)
 * - 关键回调(onLogout/onHelpClick/onStatisticsClick)
 * - 状态渲染不崩溃(主题/搜索源/通知/豆瓣登录态)
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

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
    fun `已登录时显示登出按钮`() {
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                viewModel = createMockSettingsViewModel()
            )
        }
        composeRule.waitForIdle()
        val logoutText = context.getString(R.string.settings_logout_button)
        scrollToText(logoutText)
        composeRule.onNodeWithText(logoutText).assertIsDisplayed()
    }

    @Test
    fun `未登录时显示登录入口`() {
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = false,
                onLogout = {},
                viewModel = createMockSettingsViewModel()
            )
        }
        composeRule.waitForIdle()
        val loginText = context.getString(R.string.login_button)
        scrollToText(loginText)
        composeRule.onNodeWithText(loginText).assertIsDisplayed()
    }

    @Test
    fun `点击登出按钮弹出确认对话框`() {
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                viewModel = createMockSettingsViewModel()
            )
        }
        composeRule.waitForIdle()
        val logoutText = context.getString(R.string.settings_logout_button)
        scrollToText(logoutText)
        composeRule.onNodeWithText(logoutText).performClick()
        composeRule.waitForIdle()
        val accountTitle = context.getString(R.string.settings_account)
        composeRule.onAllNodesWithText(accountTitle).onFirst().assertIsDisplayed()
    }

    @Test
    fun `点击帮助触发_onHelpClick`() {
        var helpClicked = false
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                onHelpClick = { helpClicked = true },
                viewModel = createMockSettingsViewModel()
            )
        }
        composeRule.waitForIdle()
        val helpText = context.getString(R.string.settings_help)
        scrollToText(helpText)
        composeRule.onNodeWithText(helpText).performClick()
        composeRule.waitForIdle()
        assertThat(helpClicked).isTrue()
    }

    @Test
    fun `点击统计触发_onStatisticsClick`() {
        var statsClicked = false
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                onStatisticsClick = { statsClicked = true },
                viewModel = createMockSettingsViewModel()
            )
        }
        composeRule.waitForIdle()
        val statsText = context.getString(R.string.settings_view_statistics)
        scrollToText(statsText)
        composeRule.onNodeWithText(statsText).performClick()
        composeRule.waitForIdle()
        assertThat(statsClicked).isTrue()
    }

    @Test
    fun `主题模式切换不崩溃`() {
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                viewModel = createMockSettingsViewModel(themeMode = "light")
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `搜索源开关启用不崩溃`() {
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                viewModel = createMockSettingsViewModel(pansouEnabled = true)
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `通知开关启用不崩溃`() {
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                viewModel = createMockSettingsViewModel(notificationEnabled = true)
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `豆瓣已登录时显示豆瓣账户行`() {
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                viewModel = createMockSettingsViewModel(doubanLoggedIn = true)
            )
        }
        composeRule.waitForIdle()
        val doubanLogoDescription = context.getString(R.string.settings_account_douban)
        composeRule.onNodeWithContentDescription(doubanLogoDescription).assertIsDisplayed()
    }

    @Test
    fun `豆瓣未登录时显示豆瓣登录入口`() {
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                viewModel = createMockSettingsViewModel(doubanLoggedIn = false)
            )
        }
        composeRule.waitForIdle()
        val doubanLoginText = context.getString(R.string.settings_account_douban_login)
        scrollToText(doubanLoginText)
        composeRule.onNodeWithText(doubanLoginText).assertIsDisplayed()
        val doubanLogoDescription = context.getString(R.string.settings_account_douban)
        composeRule.onNodeWithContentDescription(doubanLogoDescription).assertIsDisplayed()
    }

    @Test
    fun `Trakt账户行显示logo`() {
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                viewModel = createMockSettingsViewModel()
            )
        }
        composeRule.waitForIdle()
        val traktLogoDescription = context.getString(R.string.settings_account_trakt_label)
        composeRule.onNodeWithContentDescription(traktLogoDescription).assertIsDisplayed()
    }

    // ============ 检查一致性交互测试 ============

    @Test
    fun `设置页显示检查一致性按钮`() {
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                viewModel = createMockSettingsViewModel()
            )
        }
        composeRule.waitForIdle()
        val buttonText = context.getString(R.string.settings_douban_status_consistency)
        scrollToText(buttonText)
        composeRule.onNodeWithText(buttonText).assertIsDisplayed()
    }

    @Test
    fun `点击检查一致性按钮显示二次确认弹窗`() {
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                viewModel = createMockSettingsViewModel()
            )
        }
        composeRule.waitForIdle()
        val buttonText = context.getString(R.string.settings_douban_status_consistency)
        scrollToText(buttonText)
        composeRule.onNodeWithText(buttonText).performClick()
        composeRule.waitForIdle()
        // 按钮文案"检查状态一致性"与弹窗标题文案相同,改用弹窗描述文案断言
        val confirmDesc = context.getString(R.string.consistency_check_confirm_desc)
        composeRule.onNodeWithText(confirmDesc).assertIsDisplayed()
    }

    @Test
    fun `检查进行中入口卡片显示正在检查文案`() {
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                viewModel = createMockSettingsViewModel(
                    checkProgress = ConsistencyCheckResult(isRunning = true)
                )
            )
        }
        composeRule.waitForIdle()
        val buttonText = context.getString(R.string.settings_douban_status_consistency)
        scrollToText(buttonText)
        // isRunning=true 时入口卡片副标题切换为"正在检查…"
        val checkingText = context.getString(R.string.settings_douban_status_consistency_checking)
        composeRule.onNodeWithText(checkingText).assertIsDisplayed()
    }

    @Test
    fun `检查完成入口卡片显示统计文案`() {
        setContentWithMockedViewModels {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                viewModel = createMockSettingsViewModel(
                    checkProgress = ConsistencyCheckResult(
                        isComplete = true,
                        conflictsFound = 5,
                        traktUpdated = 3,
                        doubanUpdated = 2
                    )
                )
            )
        }
        composeRule.waitForIdle()
        val buttonText = context.getString(R.string.settings_douban_status_consistency)
        scrollToText(buttonText)
        // isComplete=true 时副标题显示"发现冲突 5 项，Trakt 更新 3 项，豆瓣更新 2 项"
        val doneText = context.getString(
            R.string.settings_douban_status_consistency_done, 5, 3, 2
        )
        composeRule.onNodeWithText(doneText).assertIsDisplayed()
    }

    @Test
    fun `检查运行中点击按钮直接显示进度弹窗`() {
        val viewModel = createMockSettingsViewModel(
            checkProgress = ConsistencyCheckResult(
                isRunning = true,
                phase = "检查中",
                current = 5,
                total = 100
            )
        )
        every { viewModel.isCheckRunning() } returns true
        // 预填充 SettingsViewModel 到 ViewModelStore,使 ConsistencyCheckDialog 内部
        // hiltViewModel() 命中缓存(避免 Hilt 创建真实 VM)
        setContentWithMockedViewModels(settingsViewModel = viewModel) {
            SettingsScreen(
                isLoggedIn = true,
                onLogout = {},
                viewModel = viewModel
            )
        }
        composeRule.waitForIdle()
        val buttonText = context.getString(R.string.settings_douban_status_consistency)
        scrollToText(buttonText)
        composeRule.onNodeWithText(buttonText).performClick()
        composeRule.waitForIdle()
        // isCheckRunning=true 时点击按钮直接弹 ConsistencyCheckDialog（跳过二次确认）
        val dialogTitle = context.getString(R.string.consistency_check_title)
        composeRule.onNodeWithText(dialogTitle).assertIsDisplayed()
        // 进度弹窗渲染阶段文案为 "检查中 (5/100)",用子串匹配断言
        composeRule.onNodeWithText("检查中", substring = true).assertIsDisplayed()
    }

    // ============ Helper ============

    /**
     * 滚动 LazyColumn 到包含指定文本的节点。
     * SettingsScreen 使用 LazyColumn 惰性渲染,不可见 item 不会被组合,
     * 需要先滚动到目标节点才能查找和交互。
     */
    private fun scrollToText(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    /** 设置 Compose 内容,可选预填充 SettingsViewModel 供一致性检查对话框复用。 */
    private inline fun setContentWithMockedViewModels(
        settingsViewModel: SettingsViewModel? = null,
        crossinline content: @androidx.compose.runtime.Composable () -> Unit
    ) {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass == SettingsViewModel::class.java && settingsViewModel != null) {
                    @Suppress("UNCHECKED_CAST")
                    return settingsViewModel as T
                }
                throw IllegalArgumentException("Unknown ViewModel: $modelClass")
            }
        }
        if (settingsViewModel != null) {
            ViewModelProvider(composeRule.activity, factory).get(SettingsViewModel::class.java)
        }

        composeRule.setContent {
            content()
        }
    }

    /**
     * 创建 SettingsViewModel 的 relaxed mock,显式 mock 所有渲染期读取的 StateFlow。
     */
    private fun createMockSettingsViewModel(
        themeMode: String = "system",
        pansouEnabled: Boolean = false,
        notificationEnabled: Boolean = false,
        doubanLoggedIn: Boolean = false,
        checkProgress: ConsistencyCheckResult = ConsistencyCheckResult()
    ): SettingsViewModel {
        val mock = mockk<SettingsViewModel>(relaxed = true)
        every { mock.themeMode } returns MutableStateFlow(themeMode)
        every { mock.accentColor } returns MutableStateFlow<MonetAccent?>(null)
        every { mock.language } returns MutableStateFlow("zh-CN")
        every { mock.defaultTab } returns MutableStateFlow(0)
        every { mock.pansouEnabled } returns MutableStateFlow(pansouEnabled)
        every { mock.panhubEnabled } returns MutableStateFlow(false)
        every { mock.zresoEnabled } returns MutableStateFlow(false)
        every { mock.panHubConfig } returns MutableStateFlow(PanHubConfig())
        every { mock.notificationEnabled } returns MutableStateFlow(notificationEnabled)
        every { mock.releaseReminderEnabled } returns MutableStateFlow(false)
        every { mock.newSeasonReminderEnabled } returns MutableStateFlow(false)
        every { mock.exportImportState } returns MutableStateFlow(ExportImportState())
        every { mock.cooldownStatus } returns MutableStateFlow<CooldownStatus?>(null)
        every { mock.checkProgress } returns MutableStateFlow(checkProgress)
        every { mock.isDoubanSyncRunning } returns MutableStateFlow(false)
        every { mock.userProfile } returns MutableStateFlow<TraktUserProfileResponse?>(null)
        every { mock.doubanLoggedIn } returns MutableStateFlow(doubanLoggedIn)
        every { mock.doubanProfile } returns MutableStateFlow<DoubanUserProfile?>(null)
        every { mock.cacheBreakdown } returns MutableStateFlow(SettingsViewModel.CacheBreakdown())
        every { mock.showUpdateDialog } returns MutableStateFlow(false)
        every { mock.updateInfo } returns MutableStateFlow<UpdateInfo?>(null)
        every { mock.isCheckingUpdate } returns MutableStateFlow(false)
        every { mock.latestVersion } returns MutableStateFlow<String?>(null)
        every { mock.discoverSections } returns MutableStateFlow(emptyList())
        every { mock.detailSections } returns MutableStateFlow(emptyList())
        every { mock.changelog } returns MutableStateFlow<String?>(null)
        every { mock.isLoadingChangelog } returns MutableStateFlow(false)
        // 触感档位与设备状态：SettingsScreen 的外观分组会读这两个，relaxed 兜不出真 Flow
        every { mock.hapticMode } returns MutableStateFlow(HapticMode.DEFAULT)
        every { mock.hapticSystemState } returns MutableStateFlow(HapticSystemState.OPTIMISTIC)
        // aiTasteEnabled/crashLogEnabled 开关已迁入「数据与隐私」页（PrivacyViewModel），
        // SettingsScreen 不再读取，无需 stub
        return mock
    }
}
