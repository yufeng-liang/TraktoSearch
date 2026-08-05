package com.tracktosearch.ui.screen.douban

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DoubanLoginScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var viewModel: DoubanLoginViewModel

    @Before
    fun setup() {
        viewModel = mockk(relaxed = true)
        every { viewModel.loginSuccess } returns MutableStateFlow(false)
    }

    @Test
    fun `登录后不显示云端失败数据入口`() {
        composeRule.setContent {
            MaterialTheme {
                DoubanLoginScreen(onBack = {}, viewModel = viewModel)
            }
        }

        assertThat(composeRule.onAllNodesWithText("Cloud failures detected").fetchSemanticsNodes()).isEmpty()
        assertThat(composeRule.onAllNodesWithText("Download").fetchSemanticsNodes()).isEmpty()
    }
}
