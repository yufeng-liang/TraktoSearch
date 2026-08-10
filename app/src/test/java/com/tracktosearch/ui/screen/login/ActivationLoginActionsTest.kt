package com.tracktosearch.ui.screen.login

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import dev.chrisbanes.haze.HazeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivationLoginActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `豆瓣按钮位于访客按钮上方并显示豆瓣logo`() {
        composeRule.setContent {
            MaterialTheme {
                ActivationSecondaryActions(
                    guestEnabled = true,
                    doubanEnabled = true,
                    hazeState = remember { HazeState() },
                    guestContentColor = MaterialTheme.colorScheme.onSurface,
                    onDoubanLogin = {},
                    onGuestMode = {}
                )
            }
        }
        val doubanText = composeRule
            .onNodeWithText(context.getString(R.string.login_douban))
            .assertIsDisplayed()
        val guestText = composeRule
            .onNodeWithText(context.getString(R.string.login_guest))
            .assertIsDisplayed()
        val doubanTop = doubanText.fetchSemanticsNode().boundsInRoot.top
        val guestTop = guestText.fetchSemanticsNode().boundsInRoot.top

        assertThat(doubanTop).isLessThan(guestTop)
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.settings_account_douban))
            .assertIsDisplayed()
    }
}
