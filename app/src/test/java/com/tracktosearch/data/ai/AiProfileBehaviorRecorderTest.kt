package com.tracktosearch.data.ai

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 画像行为入口单测。
 *
 * BYOK 后行为数据只写本地：镜像跟随画像授权，行为明细跟随行为授权，
 * 不再触发任何上传调度。
 */
class AiProfileBehaviorRecorderTest {

    private val profileRepository = mockk<AiProfileRepository>()
    private val authManager = mockk<AuthManager>()
    private val recorder = AiProfileBehaviorRecorder(
        profileRepository = profileRepository,
        authManager = authManager
    )

    init {
        every { authManager.friendId } returns MutableStateFlow("friend-1")
        every { authManager.authState } returns MutableStateFlow(AuthState.AUTHORIZED)
    }

    @Test
    fun recordNow_requiresProfileConsentBeforeMirroringOrRecording() = runTest {
        coEvery { profileRepository.settings("friend-1") } returns settings(profileConsent = false)

        val recorded = recorder.recordNow(snapshot(), AiProfileBehavior.SearchClick, "2026-08-14")

        assertThat(recorded).isFalse()
        coVerify(exactly = 0) { profileRepository.mirrorMedia(any(), any(), any()) }
        coVerify(exactly = 0) { profileRepository.recordBehavior(any(), any(), any(), any(), any()) }
    }

    @Test
    fun recordNow_mirrorsMediaAndRecordsBehaviorLocally() = runTest {
        coEvery { profileRepository.settings("friend-1") } returns settings(
            profileConsent = true,
            behaviorConsent = true
        )
        coEvery { profileRepository.mirrorMedia("friend-1", any(), any()) } returns AiMediaNormalizer.normalize(snapshot())
        coEvery {
            profileRepository.recordBehavior(
                friendId = "friend-1",
                mediaKey = "movie:101",
                day = "2026-08-14",
                behavior = AiProfileBehavior.SearchClick,
                now = any()
            )
        } returns true

        val recorded = recorder.recordNow(snapshot(), AiProfileBehavior.SearchClick, "2026-08-14")

        assertThat(recorded).isTrue()
        coVerify { profileRepository.mirrorMedia("friend-1", any(), any()) }
        coVerify {
            profileRepository.recordBehavior(
                friendId = "friend-1",
                mediaKey = "movie:101",
                day = "2026-08-14",
                behavior = AiProfileBehavior.SearchClick,
                now = any()
            )
        }
    }

    @Test
    fun recordNow_canMirrorWithoutRecordingWhenBehaviorConsentIsOff() = runTest {
        coEvery { profileRepository.settings("friend-1") } returns settings(
            profileConsent = true,
            behaviorConsent = false
        )
        coEvery { profileRepository.mirrorMedia("friend-1", any(), any()) } returns AiMediaNormalizer.normalize(snapshot())

        val recorded = recorder.recordNow(snapshot(), AiProfileBehavior.EpisodeCompleted, "2026-08-14")

        // 镜像归画像授权管，行为明细归行为授权管，两者独立
        assertThat(recorded).isTrue()
        coVerify(exactly = 0) { profileRepository.recordBehavior(any(), any(), any(), any(), any()) }
    }

    @Test
    fun recordDetailDwell_recordsDetailDwellLocally() = runTest {
        coEvery { profileRepository.settings("friend-1") } returns settings(
            profileConsent = true,
            behaviorConsent = true
        )
        coEvery { profileRepository.mirrorMedia("friend-1", any(), any()) } returns AiMediaNormalizer.normalize(snapshot())
        coEvery {
            profileRepository.recordBehavior(
                friendId = "friend-1",
                mediaKey = "movie:101",
                day = "2026-08-14",
                behavior = AiProfileBehavior.DetailDwell(durationMs = 42_000L),
                now = any()
            )
        } returns true

        val recorded = recorder.recordDetailDwell(snapshot(), durationMs = 42_000L, day = "2026-08-14")

        assertThat(recorded).isTrue()
        coVerify {
            profileRepository.recordBehavior(
                friendId = "friend-1",
                mediaKey = "movie:101",
                day = "2026-08-14",
                behavior = AiProfileBehavior.DetailDwell(durationMs = 42_000L),
                now = any()
            )
        }
    }

    private fun snapshot() = MediaSourceSnapshot(
        mediaType = "movie",
        tmdbId = 101,
        traktId = 201,
        imdbId = "tt1234567",
        title = "测试电影",
        year = 2026,
        publicRating = 8.5
    )

    private fun settings(
        profileConsent: Boolean,
        behaviorConsent: Boolean = false
    ) = AiProfileSettings(
        friendId = "friend-1",
        profileConsent = profileConsent,
        behaviorConsent = behaviorConsent,
        personalizationEnabled = profileConsent,
        syncEnabled = true,
        shouldAutoImport = false,
        updatedAt = 0L,
        clearedAt = null
    )
}
