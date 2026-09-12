package com.tracktosearch.data.ai

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.local.db.AiProfileOutboxEntity
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class AiProfileSyncCoordinatorTest {

    private val profileRepository = mockk<AiProfileRepository>(relaxed = true)
    private val aiRepository = mockk<AiRepository>()
    private val authManager = mockk<AuthManager>()
    private val json = Json { encodeDefaults = true }
    private val coordinator = AiProfileSyncCoordinator(profileRepository, aiRepository, json, authManager)

    init {
        every { authManager.friendId } returns kotlinx.coroutines.flow.MutableStateFlow("friend-1")
        every { authManager.authState } returns kotlinx.coroutines.flow.MutableStateFlow(AuthState.AUTHORIZED)
    }

    @Test
    fun successfulSnapshotSyncMarksOutboxSent() = runTest {
        val batch = batch()
        val payload = json.encodeToString(batch)
        coEvery { profileRepository.settings("friend-1") } returns settings()
        coEvery { profileRepository.buildSyncBatch("friend-1", any()) } returns batch
        coEvery { profileRepository.pending("friend-1") } returns listOf(outbox(batch.batchId, payload))
        coEvery { aiRepository.syncProfile("friend-1", batch) } returns Result.success(AiProfileSyncResultDto(accepted = true))

        val result = coordinator.syncNow("friend-1")

        assertThat(result.isSuccess).isTrue()
        coVerify {
            profileRepository.enqueue(
                "friend-1",
                match { it.batchId.startsWith("profile-") },
                any(),
                any()
            )
        }
        coVerify { profileRepository.markOutbox("friend-1", batch.batchId, "SENT", 0, null, any()) }
    }

    @Test
    fun failedSnapshotSyncKeepsOutboxRetryable() = runTest {
        val batch = batch()
        val payload = json.encodeToString(batch)
        val failure = IllegalStateException("offline")
        coEvery { profileRepository.settings("friend-1") } returns settings()
        coEvery { profileRepository.buildSyncBatch("friend-1", any()) } returns batch
        coEvery { profileRepository.pending("friend-1") } returns listOf(outbox(batch.batchId, payload))
        coEvery { aiRepository.syncProfile("friend-1", batch) } returns Result.failure(failure)

        val result = coordinator.syncNow("friend-1")

        assertThat(result.exceptionOrNull()).isSameInstanceAs(failure)
        coVerify { profileRepository.markOutbox("friend-1", batch.batchId, "RETRY", 1, "offline", any()) }
    }

    @Test
    fun behaviorRevocationDiscardsPendingBehaviorSnapshotWithoutUploadingIt() = runTest {
        val currentBatch = AiProfileSyncBatch(batchId = "profile-current")
        val staleBatch = batch().copy(batchId = "profile-stale")
        val stalePayload = json.encodeToString(staleBatch)
        coEvery { profileRepository.settings("friend-1") } returns settings().copy(behaviorConsent = false)
        coEvery { profileRepository.buildSyncBatch("friend-1", any()) } returns currentBatch
        coEvery { profileRepository.pending("friend-1") } returns listOf(outbox(staleBatch.batchId, stalePayload))

        val result = coordinator.syncNow("friend-1")

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { aiRepository.syncProfile(any(), any()) }
        coVerify {
            profileRepository.markOutbox("friend-1", staleBatch.batchId, "DISCARDED", 0, null, any())
        }
    }

    private fun settings() = AiProfileSettings(
        friendId = "friend-1",
        profileConsent = true,
        behaviorConsent = true,
        personalizationEnabled = true,
        syncEnabled = true,
        shouldAutoImport = false,
        updatedAt = 0L,
        clearedAt = null
    )

    private fun batch() = AiProfileSyncBatch(
        batchId = "profile-test",
        media = emptyList(),
        behavior = listOf(
            AiProfileBehaviorPayload(
                mediaKey = "movie:tmdb:101",
                eventDay = "2026-08-13",
                searchClickCount = 1
            )
        )
    )

    private fun outbox(batchId: String, payload: String) = AiProfileOutboxEntity(
        friendId = "friend-1",
        batchId = batchId,
        payloadJson = payload,
        payloadDigest = "digest"
    )
}
