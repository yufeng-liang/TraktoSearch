package com.tracktosearch.data.ai

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AiProfileBehaviorRecorderTest {

    private val profileRepository = mockk<AiProfileRepository>()
    private val syncScheduler = mockk<AiProfileSyncScheduler>(relaxed = true)
    private val authManager = mockk<AuthManager>()
    private val recorder = AiProfileBehaviorRecorder(
        profileRepository = profileRepository,
        syncScheduler = syncScheduler,
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
        verify(exactly = 0) { syncScheduler.enqueueNow() }
    }

    @Test
    fun recordNow_mirrorsMediaAndRecordsBehaviorThenSchedulesSync() = runTest {
        coEvery { profileRepository.settings("friend-1") } returns settings(
            profileConsent = true,
            behaviorConsent = true,
            syncEnabled = true
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
        verify { syncScheduler.enqueueNow() }
    }

    @Test
    fun recordNow_canMirrorWithoutUploadingBehaviorWhenBehaviorConsentIsOff() = runTest {
        coEvery { profileRepository.settings("friend-1") } returns settings(
            profileConsent = true,
            behaviorConsent = false,
            syncEnabled = true
        )
        coEvery { profileRepository.mirrorMedia("friend-1", any(), any()) } returns AiMediaNormalizer.normalize(snapshot())

        val recorded = recorder.recordNow(snapshot(), AiProfileBehavior.EpisodeCompleted, "2026-08-14")

        assertThat(recorded).isTrue()
        coVerify(exactly = 0) { profileRepository.recordBehavior(any(), any(), any(), any(), any()) }
        verify { syncScheduler.enqueueNow() }
    }

    @Test
    fun recordDetailDwell_recordsDetailDwellAndSchedulesSync() = runTest {
        coEvery { profileRepository.settings("friend-1") } returns settings(
            profileConsent = true,
            behaviorConsent = true,
            syncEnabled = true
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
        verify { syncScheduler.enqueueNow() }
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
        behaviorConsent: Boolean = false,
        syncEnabled: Boolean = true
    ) = AiProfileSettings(
        friendId = "friend-1",
        profileConsent = profileConsent,
        behaviorConsent = behaviorConsent,
        personalizationEnabled = profileConsent,
        syncEnabled = syncEnabled,
        shouldAutoImport = false,
        updatedAt = 0L,
        clearedAt = null
    )
}
