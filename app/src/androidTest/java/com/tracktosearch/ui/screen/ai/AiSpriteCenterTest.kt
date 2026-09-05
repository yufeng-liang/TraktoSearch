package com.tracktosearch.ui.screen.ai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiAudio
import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.data.auth.AuthState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    fun failedVoiceActivationKeepsHoldToTalkRetryAlongsideTextEntry() {
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
        // 主按钮仍是语音重试（按住说话），文字入口是并列的第二入口
        composeRule.onAllNodesWithText(context.getString(R.string.ai_sprite_hold_to_talk, "乌萨奇"))
            .assertCountEquals(1)
        // 面板标题仍用「喊名字来激活」，按钮文案已换成按住提示，所以这句只剩标题一处
        composeRule.onAllNodesWithText(context.getString(R.string.ai_sprite_activate, "乌萨奇"))
            .assertCountEquals(1)
        composeRule.onAllNodesWithText(context.getString(R.string.ai_sprite_text_fallback))
            .assertCountEquals(1)
    }

    @Test
    fun textFallbackButtonOpensNameInputInsteadOfSubmittingImmediately() {
        val viewModel = mockk<AiSpriteViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(
            AiSpriteUiState(
                characters = listOf(AiCharacter("usagi", "乌萨奇", "乌萨奇", isAvailable = true)),
                selectedCharacterId = "usagi",
                authState = AuthState.AUTHORIZED,
                activationAttempt = 1,
                activationState = AiActivationState.FAILED
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
        composeRule.onNodeWithText(context.getString(R.string.ai_sprite_text_fallback)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.ai_sprite_text_fallback_label))
            .assertIsDisplayed()
        // 没输入内容前不能提交
        composeRule.onNodeWithText(context.getString(R.string.ai_sprite_text_fallback_confirm))
            .assertIsNotEnabled()
        verify(exactly = 0) { viewModel.activateByText(any()) }

        composeRule.onNodeWithText(context.getString(R.string.ai_sprite_text_fallback_label))
            .performTextInput("乌萨奇")
        composeRule.onNodeWithText(context.getString(R.string.ai_sprite_text_fallback_confirm))
            .performClick()
        composeRule.waitForIdle()

        verify { viewModel.activateByText("乌萨奇") }
    }

    @Test
    fun inactiveCharacterDoesNotShowActivationSuccessBadge() {
        val viewModel = mockk<AiSpriteViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(
            AiSpriteUiState(
                characters = listOf(AiCharacter("usagi", "乌萨奇", "乌萨奇", isAvailable = true)),
                selectedCharacterId = "usagi",
                activatedCharacterId = null,
                authState = AuthState.AUTHORIZED
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

        composeRule.onAllNodesWithText(context.getString(R.string.ai_sprite_activation_success))
            .assertCountEquals(0)
    }

    @Test
    fun activatedCharacterShowsStatusPanelInsteadOfDeadActivateButton() {
        val viewModel = mockk<AiSpriteViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(
            AiSpriteUiState(
                characters = listOf(AiCharacter("usagi", "乌萨奇", "乌萨奇", isAvailable = true)),
                selectedCharacterId = "usagi",
                activatedCharacterId = "usagi",
                activationState = AiActivationState.SUCCESS,
                authState = AuthState.AUTHORIZED
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

        composeRule.onNodeWithText(context.getString(R.string.ai_sprite_activated_status, "乌萨奇"))
            .assertIsDisplayed()
        // 激活成功后不该再留一个灰掉的激活按钮当摆设：标题和按住提示都应该随面板一起消失
        composeRule.onAllNodesWithText(context.getString(R.string.ai_sprite_hold_to_talk, "乌萨奇"))
            .assertCountEquals(0)
        composeRule.onAllNodesWithText(context.getString(R.string.ai_sprite_activate, "乌萨奇"))
            .assertCountEquals(0)
    }

    @Test
    fun characterCatalogFailureExplainsItselfAndOffersReload() {
        val viewModel = mockk<AiSpriteViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(
            AiSpriteUiState(
                characters = listOf(AiCharacter("usagi", "乌萨奇", "乌萨奇", isAvailable = false)),
                selectedCharacterId = "usagi",
                authState = AuthState.AUTHORIZED,
                charactersLoadFailed = true
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

        composeRule.onNodeWithText(context.getString(R.string.ai_error_characters_failed)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.ai_sprite_characters_retry)).performClick()
        verify { viewModel.reloadCharacters() }
        // 角色还没上线时激活按钮旁要说明原因，不能只是灰着
        composeRule.onNodeWithText(context.getString(R.string.ai_sprite_activate_disabled_preparing))
            .assertIsDisplayed()
    }
}
