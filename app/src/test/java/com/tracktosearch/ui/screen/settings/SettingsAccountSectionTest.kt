package com.tracktosearch.ui.screen.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 账号卡第三态：登录态还在但凭据失效时，必须与正常已登录可区分并给出重新登录入口。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SettingsAccountSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    @Test
    fun 失效态显示原因与身份信息() {
        composeRule.setContent {
            MaterialTheme {
                AccountExpiredRow(
                    brandLogo = {},
                    primaryName = "someone",
                    onReconnect = {},
                    onLogout = {}
                )
            }
        }

        composeRule.onNodeWithText("someone").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_account_connection_expired))
            .assertIsDisplayed()
    }

    @Test
    fun 点击重新登录触发回调() {
        var reconnected = false
        composeRule.setContent {
            MaterialTheme {
                AccountExpiredRow(
                    brandLogo = {},
                    primaryName = "someone",
                    onReconnect = { reconnected = true },
                    onLogout = {}
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.settings_account_reconnect)).performClick()
        composeRule.waitForIdle()

        assertThat(reconnected).isTrue()
    }

    @Test
    fun 失效态仍保留退出登录入口() {
        var loggedOut = false
        composeRule.setContent {
            MaterialTheme {
                AccountExpiredRow(
                    brandLogo = {},
                    primaryName = null,
                    onReconnect = {},
                    onLogout = { loggedOut = true }
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.settings_logout_button)).performClick()
        composeRule.waitForIdle()

        assertThat(loggedOut).isTrue()
    }
}
