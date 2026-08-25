package com.tracktosearch.ui.component

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
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

/** 新手引导：步骤可前进也可回退，最后一步才是「完成」。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class OnboardingOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private val titles = listOf("步骤一", "步骤二", "步骤三")
    private val descriptions = listOf("说明一", "说明二", "说明三")

    private fun setContent(
        onComplete: () -> Unit = {},
        onSkip: () -> Unit = {},
        onStepChanged: (Int) -> Unit = {}
    ) {
        composeRule.setContent {
            MaterialTheme {
                OnboardingOverlay(
                    targetRects = List(3) { Rect(0f, 0f, 100f, 100f) },
                    titles = titles,
                    descriptions = descriptions,
                    onComplete = onComplete,
                    onSkip = onSkip,
                    onStepChanged = onStepChanged
                )
            }
        }
    }

    @Test
    fun 首步不显示上一步() {
        setContent()

        composeRule.onNodeWithText("步骤一").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onboarding_prev)).assertDoesNotExist()
    }

    @Test
    fun 第二步起可回退到上一步() {
        val steps = mutableListOf<Int>()
        setContent(onStepChanged = { steps += it })

        composeRule.onNodeWithText(string(R.string.onboarding_next)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("步骤二").assertIsDisplayed()

        composeRule.onNodeWithText(string(R.string.onboarding_prev)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("步骤一").assertIsDisplayed()
        assertThat(steps).containsExactly(1, 0).inOrder()
    }

    @Test
    fun 最后一步显示完成并触发回调() {
        var completed = false
        setContent(onComplete = { completed = true })

        composeRule.onNodeWithText(string(R.string.onboarding_next)).performClick()
        composeRule.onNodeWithText(string(R.string.onboarding_next)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("步骤三").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onboarding_done)).performClick()
        composeRule.waitForIdle()

        assertThat(completed).isTrue()
    }

    @Test
    fun 跳过触发跳过回调() {
        var skipped = false
        setContent(onSkip = { skipped = true })

        composeRule.onNodeWithText(string(R.string.onboarding_skip)).performClick()
        composeRule.waitForIdle()

        assertThat(skipped).isTrue()
    }
}
