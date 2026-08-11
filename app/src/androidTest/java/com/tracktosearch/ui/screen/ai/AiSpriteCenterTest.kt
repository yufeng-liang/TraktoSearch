package com.tracktosearch.ui.screen.ai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiAudio
import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.data.auth.AuthState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiSpriteCenterTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun guestCanBrowseAndUseLoginActionWithoutActivation() {
        val viewModel = mockk<AiSpriteViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(
            AiSpriteUiState(
                characters = listOf(AiCharacter("usagi", "乌萨奇", "乌萨奇", isAvailable = false)),
                authState = AuthState.UNAUTHORIZED
            )
        )
        every { viewModel.audioEvents } returns MutableSharedFlow<AiAudio>()
        var loginClicked = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            AiSpriteCenter(
                visible = true,
                onDismiss = {},
                onNavigateToLogin = { loginClicked = true },
                viewModel = viewModel
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("乌萨奇").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.ai_sprite_guest_hint)).performClick()
        assertThat(loginClicked).isTrue()
    }

    @Test
    fun failedVoiceActivationOffersOneTextFallbackAction() {
        val viewModel = mockk<AiSpriteViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(
            AiSpriteUiState(
                characters = listOf(AiCharacter("usagi", "乌萨奇", "乌萨奇", isAvailable = true)),
                selectedCharacterId = "usagi",
                authState = AuthState.AUTHORIZED,
                activationAttempt = 1,
                activationState = AiActivationState.FAILED,
                errorCode = "ACTIVATION_NOT_MATCHED"
            )
        )
        every { viewModel.audioEvents } returns MutableSharedFlow<AiAudio>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            AiSpriteCenter(
                visible = true,
                onDismiss = {},
                viewModel = viewModel
            )
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(context.getString(R.string.ai_sprite_text_fallback))
            .assertCountEquals(1)
    }
}
