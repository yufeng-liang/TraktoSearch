package com.tracktosearch.ui.screen.privacy

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiProfileRepository
import com.tracktosearch.data.ai.AiProfileSettings
import com.tracktosearch.data.ai.AiProfileSettingsDto
import com.tracktosearch.data.ai.AiProfileSettingsRequest
import com.tracktosearch.data.ai.AiRepository
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
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 隐私页 ViewModel 的 AI 画像开关组单测。
 *
 * 重点不在「能不能调通」，而在三条要么静默错、要么把用户状态改反的规则：
 * 1. 未登录（没有 friendId）时不该发任何请求，开关组只读
 * 2. 云端写入失败要整体回滚，不能留下「服务端没改、本地显示改了」的错位
 * 3. 撤回画像授权要连带清本地镜像，否则残留的画像会在重新授权时被当成旧数据带上云
 */
class PrivacyViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val aiTasteStorage = mockk<AiTasteStorage>(relaxed = true)
    private val crashLogStorage = mockk<CrashLogStorage>(relaxed = true)
    private val crashLogUploader = mockk<CrashLogUploader>(relaxed = true)
    private val aiProfileRepository = mockk<AiProfileRepository>(relaxed = true)
    private val aiRepository = mockk<AiRepository>(relaxed = true)
    private val authManager = mockk<AuthManager>(relaxed = true)

    private val friendId = MutableStateFlow<String?>(null)

    @Before
    fun setup() {
        every { authManager.friendId } returns friendId
        every { aiTasteStorage.tasteUploadEnabled } returns MutableStateFlow(true)
        every { crashLogStorage.enabled } returns MutableStateFlow(false)
    }

    private fun createViewModel(): PrivacyViewModel = PrivacyViewModel(
        aiTasteStorage = aiTasteStorage,
        crashLogStorage = crashLogStorage,
        crashLogUploader = crashLogUploader,
        aiProfileRepository = aiProfileRepository,
        aiRepository = aiRepository,
        authManager = authManager
    )

    private fun remoteSettings(
        profileConsent: Boolean = false,
        behaviorConsent: Boolean = false,
        personalizationEnabled: Boolean = false,
        syncEnabled: Boolean = true
    ) = AiProfileSettingsDto(
        profileConsent = profileConsent,
        behaviorConsent = behaviorConsent,
        personalizationEnabled = personalizationEnabled,
        syncEnabled = syncEnabled
    )

    @Test
    fun `没有 friendId 时开关组只读且不请求云端`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.aiProfileSettings.value
        assertThat(state.isAvailable).isFalse()
        assertThat(state.isLoading).isFalse()

        viewModel.setAiProfileConsent(true)
        advanceUntilIdle()

        coVerify(exactly = 0) { aiRepository.updateProfileSettings(any(), any()) }
        assertThat(viewModel.message.value?.resId).isEqualTo(R.string.settings_ai_profile_unavailable)
    }

    @Test
    fun `有 friendId 时先落本地镜像再被云端结果覆盖`() = runTest {
        coEvery { aiProfileRepository.settings("friend-1") } returns AiProfileSettings(
            friendId = "friend-1",
            profileConsent = true,
            behaviorConsent = false,
            personalizationEnabled = true,
            syncEnabled = false,
            shouldAutoImport = false,
            updatedAt = 0L,
            clearedAt = null
        )
        coEvery { aiRepository.getProfileSettings("friend-1") } returns Result.success(
            remoteSettings(profileConsent = true, personalizationEnabled = true, syncEnabled = true)
        )

        friendId.value = "friend-1"
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.aiProfileSettings.value
        assertThat(state.isAvailable).isTrue()
        assertThat(state.profileConsent).isTrue()
        assertThat(state.personalizationEnabled).isTrue()
        // 云端说开着同步，本地镜像说关着：以云端为准
        assertThat(state.syncEnabled).isTrue()
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `云端拉取失败时停在本地镜像上`() = runTest {
        coEvery { aiProfileRepository.settings("friend-1") } returns AiProfileSettings(
            friendId = "friend-1",
            profileConsent = false,
            behaviorConsent = true,
            personalizationEnabled = false,
            syncEnabled = true,
            shouldAutoImport = false,
            updatedAt = 0L,
            clearedAt = null
        )
        coEvery { aiRepository.getProfileSettings("friend-1") } returns Result.failure(IllegalStateException("offline"))

        friendId.value = "friend-1"
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.aiProfileSettings.value.behaviorConsent).isTrue()
        assertThat(viewModel.aiProfileSettings.value.isLoading).isFalse()
    }

    @Test
    fun `开启画像授权会同时打开个性化和关闭行为采集`() = runTest {
        coEvery { aiProfileRepository.settings("friend-1") } returns null
        coEvery { aiRepository.getProfileSettings("friend-1") } returns Result.success(remoteSettings())
        coEvery { aiRepository.updateProfileSettings(any(), any()) } returns Result.success(
            remoteSettings(profileConsent = true, personalizationEnabled = true)
        )

        friendId.value = "friend-1"
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setAiProfileConsent(true)
        advanceUntilIdle()

        val request = slot<AiProfileSettingsRequest>()
        coVerify { aiRepository.updateProfileSettings("friend-1", capture(request)) }
        assertThat(request.captured.profileConsent).isTrue()
        assertThat(request.captured.personalizationEnabled).isTrue()
        assertThat(request.captured.behaviorConsent).isFalse()
        coVerify { aiProfileRepository.grantProfileConsent("friend-1", false, true, true, false, any()) }
        assertThat(viewModel.message.value?.outcome).isEqualTo(HapticOutcome.SUCCESS)
    }

    @Test
    fun `撤回画像授权会清掉本地镜像`() = runTest {
        coEvery { aiProfileRepository.settings("friend-1") } returns null
        coEvery { aiRepository.getProfileSettings("friend-1") } returns Result.success(
            remoteSettings(profileConsent = true, personalizationEnabled = true)
        )
        coEvery { aiRepository.updateProfileSettings(any(), any()) } returns Result.success(remoteSettings())

        friendId.value = "friend-1"
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setAiProfileConsent(false)
        advanceUntilIdle()

        coVerify { aiProfileRepository.clear("friend-1", any()) }
        assertThat(viewModel.aiProfileSettings.value.profileConsent).isFalse()
    }

    @Test
    fun `云端写入失败时回滚且给出失败提示`() = runTest {
        coEvery { aiProfileRepository.settings("friend-1") } returns null
        coEvery { aiRepository.getProfileSettings("friend-1") } returns Result.success(remoteSettings())
        coEvery { aiRepository.updateProfileSettings(any(), any()) } returns Result.failure(IllegalStateException("offline"))

        friendId.value = "friend-1"
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setAiBehaviorConsent(true)
        advanceUntilIdle()

        val state = viewModel.aiProfileSettings.value
        // 服务端没改，界面也不许显示改过
        assertThat(state.behaviorConsent).isFalse()
        assertThat(state.isUpdating).isFalse()
        assertThat(viewModel.message.value?.resId).isEqualTo(R.string.settings_ai_profile_update_failed)
        assertThat(viewModel.message.value?.outcome).isEqualTo(HapticOutcome.FAILURE)
    }

    @Test
    fun `总开关关闭时个性化分析不发请求`() = runTest {
        coEvery { aiProfileRepository.settings("friend-1") } returns null
        coEvery { aiRepository.getProfileSettings("friend-1") } returns Result.success(remoteSettings())

        friendId.value = "friend-1"
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setAiPersonalizationEnabled(true)
        advanceUntilIdle()

        coVerify(exactly = 0) { aiRepository.updateProfileSettings(any(), any()) }
    }

    @Test
    fun `清空消息后不再重放`() = runTest {
        coEvery { aiProfileRepository.settings("friend-1") } returns null
        coEvery { aiRepository.getProfileSettings("friend-1") } returns Result.success(remoteSettings())
        coEvery { aiRepository.updateProfileSettings(any(), any()) } returns Result.success(remoteSettings())

        friendId.value = "friend-1"
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setAiSyncEnabled(false)
        advanceUntilIdle()
        assertThat(viewModel.message.value).isNotNull()

        viewModel.clearMessage()
        assertThat(viewModel.message.value).isNull()
    }
}
