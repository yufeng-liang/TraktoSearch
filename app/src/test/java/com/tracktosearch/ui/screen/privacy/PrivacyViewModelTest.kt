package com.tracktosearch.ui.screen.privacy

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiProfileRepository
import com.tracktosearch.data.ai.AiProfileSettings
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.local.AiTasteStorage
import com.tracktosearch.data.local.CrashLogStorage
import com.tracktosearch.data.util.CrashLogUploader
import com.tracktosearch.test.MainDispatcherRule
import com.tracktosearch.ui.haptic.HapticOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 隐私页 ViewModel 的 AI 画像开关组单测。
 *
 * BYOK 后画像只存本地：云端读写端点已全部拆除，这里验证三条规则：
 * 1. 未登录（没有 friendId）时不写任何状态，开关组只读
 * 2. 本地写入失败要回滚界面状态，不能让 UI 与实际授权状态错位
 * 3. 撤回画像授权要连带清本地镜像，否则残留的画像会污染之后的个性化输出
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val aiTasteStorage = mockk<AiTasteStorage>(relaxed = true)
    private val crashLogStorage = mockk<CrashLogStorage>(relaxed = true)
    private val crashLogUploader = mockk<CrashLogUploader>(relaxed = true)
    private val aiProfileRepository = mockk<AiProfileRepository>(relaxed = true)
    private val authManager = mockk<AuthManager>()

    private val friendId = MutableStateFlow<String?>(null)
    private val aiTasteEnabledFlow = MutableStateFlow(true)
    private val crashLogEnabledFlow = MutableStateFlow(false)

    @Before
    fun setup() {
        every { authManager.friendId } returns friendId
        every { aiTasteStorage.tasteUploadEnabled } returns aiTasteEnabledFlow
        every { crashLogStorage.enabled } returns crashLogEnabledFlow
    }

    private fun createViewModel(): PrivacyViewModel = PrivacyViewModel(
        aiTasteStorage = aiTasteStorage,
        crashLogStorage = crashLogStorage,
        crashLogUploader = crashLogUploader,
        aiProfileRepository = aiProfileRepository,
        authManager = authManager
    )

    private fun localSettings(
        profileConsent: Boolean = false,
        behaviorConsent: Boolean = false,
        personalizationEnabled: Boolean = false
    ) = AiProfileSettings(
        friendId = "friend-1",
        profileConsent = profileConsent,
        behaviorConsent = behaviorConsent,
        personalizationEnabled = personalizationEnabled,
        syncEnabled = true,
        shouldAutoImport = false,
        updatedAt = 0L,
        clearedAt = null
    )

    @Test
    fun `本地开关订阅后直接读内存镜像`() = runTest {
        val viewModel = createViewModel()

        // 存储层是内存镜像：进入页面直接读当前值，不依赖冷 Flow 首值
        assertThat(viewModel.crashLogEnabled.value).isFalse()
        aiTasteEnabledFlow.value = false
        runCurrent()
        assertThat(viewModel.aiTasteEnabled.value).isFalse()
        crashLogEnabledFlow.value = true
        runCurrent()
        assertThat(viewModel.crashLogEnabled.value).isTrue()
        assertThat(viewModel.aiTasteEnabled.value).isFalse()
    }

    @Test
    fun `没有 friendId 时开关组只读且不写本地`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.aiProfileSettings.value
        assertThat(state.isAvailable).isFalse()

        viewModel.setAiProfileConsent(true)
        advanceUntilIdle()

        coVerify(exactly = 0) { aiProfileRepository.grantProfileConsent(any(), any(), any(), any(), any(), any()) }
        assertThat(viewModel.message.value?.resId).isEqualTo(R.string.settings_ai_profile_unavailable)
    }

    @Test
    fun `有 friendId 时直接读本地镜像`() = runTest {
        coEvery { aiProfileRepository.settings("friend-1") } returns localSettings(
            profileConsent = true,
            personalizationEnabled = true
        )

        friendId.value = "friend-1"
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.aiProfileSettings.value
        assertThat(state.isAvailable).isTrue()
        assertThat(state.profileConsent).isTrue()
        assertThat(state.personalizationEnabled).isTrue()
        assertThat(state.isLoaded).isTrue()
    }

    @Test
    fun `本地无镜像时落在默认只读态`() = runTest {
        coEvery { aiProfileRepository.settings("friend-1") } returns null

        friendId.value = "friend-1"
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.aiProfileSettings.value
        assertThat(state.isAvailable).isTrue()
        assertThat(state.isLoaded).isTrue()
        assertThat(state.profileConsent).isFalse()
    }

    @Test
    fun `开启画像授权会同时打开个性化并写本地`() = runTest {
        coEvery { aiProfileRepository.settings("friend-1") } returns null
        coEvery { aiProfileRepository.grantProfileConsent("friend-1", any(), any(), any(), any(), any()) } returns
            localSettings(profileConsent = true, personalizationEnabled = true)
        coEvery { aiProfileRepository.settings("friend-1") } returns null andThen
            localSettings(profileConsent = true, personalizationEnabled = true)

        friendId.value = "friend-1"
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setAiProfileConsent(true)
        advanceUntilIdle()

        coVerify {
            aiProfileRepository.grantProfileConsent(
                friendId = "friend-1",
                behaviorConsent = any(),
                personalizationEnabled = any(),
                shouldAutoImport = false
            )
        }
        assertThat(viewModel.aiProfileSettings.value.profileConsent).isTrue()
        assertThat(viewModel.message.value?.outcome).isEqualTo(HapticOutcome.SUCCESS)
    }

    @Test
    fun `撤回画像授权会清掉本地镜像`() = runTest {
        coEvery { aiProfileRepository.settings("friend-1") } returns localSettings(
            profileConsent = true,
            personalizationEnabled = true
        )

        friendId.value = "friend-1"
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setAiProfileConsent(false)
        advanceUntilIdle()

        coVerify { aiProfileRepository.clear("friend-1", any()) }
        assertThat(viewModel.aiProfileSettings.value.profileConsent).isFalse()
    }

    @Test
    fun `本地写入失败时回滚且给出失败提示`() = runTest {
        coEvery { aiProfileRepository.settings("friend-1") } returns localSettings()
        coEvery { aiProfileRepository.setBehaviorConsent(any(), any()) } throws IllegalStateException("disk full")

        friendId.value = "friend-1"
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setAiBehaviorConsent(true)
        advanceUntilIdle()

        val state = viewModel.aiProfileSettings.value
        // 本地没写进去，界面也不许显示改过
        assertThat(state.behaviorConsent).isFalse()
        assertThat(state.isUpdating).isFalse()
        assertThat(viewModel.message.value?.resId).isEqualTo(R.string.settings_ai_profile_update_failed)
        assertThat(viewModel.message.value?.outcome).isEqualTo(HapticOutcome.FAILURE)
    }

    @Test
    fun `总开关关闭时个性化分析不写本地`() = runTest {
        coEvery { aiProfileRepository.settings("friend-1") } returns localSettings()

        friendId.value = "friend-1"
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setAiPersonalizationEnabled(true)
        advanceUntilIdle()

        coVerify(exactly = 0) { aiProfileRepository.grantProfileConsent(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `清空消息后不再重放`() = runTest {
        coEvery { aiProfileRepository.settings("friend-1") } returns localSettings()

        friendId.value = "friend-1"
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setAiBehaviorConsent(true)
        advanceUntilIdle()
        assertThat(viewModel.message.value).isNotNull()

        viewModel.clearMessage()
        assertThat(viewModel.message.value).isNull()
    }
}
