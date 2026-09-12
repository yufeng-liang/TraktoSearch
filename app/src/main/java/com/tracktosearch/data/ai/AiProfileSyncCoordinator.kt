package com.tracktosearch.data.ai

import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiProfileSyncCoordinator @Inject constructor(
    private val profileRepository: AiProfileRepository,
    private val aiRepository: AiRepository,
    private val json: Json,
    private val authManager: AuthManager
) {
    suspend fun syncNow(friendIdValue: String): Result<Unit> {
        val friendId = friendIdValue.trim()
        if (friendId.isBlank()) return Result.failure(IllegalArgumentException("friendId must not be blank"))
        if (authManager.friendId.value?.trim() != friendId ||
            authManager.authState.value !in setOf(AuthState.AUTHORIZED, AuthState.OFFLINE)
        ) {
            return Result.success(Unit)
        }

        val settings = profileRepository.settings(friendId) ?: return Result.success(Unit)
        if (!settings.profileConsent || !settings.syncEnabled) return Result.success(Unit)

        return try {
            enqueueCurrentSnapshot(friendId)
            flushPending(friendId, behaviorConsent = settings.behaviorConsent)
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun enqueueCurrentSnapshot(friendId: String) {
        val draft = profileRepository.buildSyncBatch(friendId, "profile-pending")
        val batchId = "profile-${sha256(json.encodeToString(draft.copy(batchId = ""))).take(48)}"
        val batch = draft.copy(batchId = batchId)
        val payload = json.encodeToString(batch)
        val payloadDigest = sha256(payload)
        val existing = profileRepository.outbox(friendId, batchId)
        if (existing == null || existing.payloadDigest != payloadDigest) {
            profileRepository.enqueue(friendId, batch, payload)
        }
    }

    private suspend fun flushPending(friendId: String, behaviorConsent: Boolean) {
        for (row in profileRepository.pending(friendId)) {
            val batch = json.decodeFromString<AiProfileSyncBatch>(row.payloadJson)
            if (!behaviorConsent && batch.behavior.isNotEmpty()) {
                profileRepository.markOutbox(
                    friendId = friendId,
                    batchId = row.batchId,
                    status = "DISCARDED",
                    attemptCount = row.attemptCount,
                    error = null
                )
                continue
            }
            aiRepository.syncProfile(friendId, batch).fold(
                onSuccess = {
                    profileRepository.markOutbox(
                        friendId = friendId,
                        batchId = row.batchId,
                        status = "SENT",
                        attemptCount = row.attemptCount,
                        error = null
                    )
                },
                onFailure = { error ->
                    profileRepository.markOutbox(
                        friendId = friendId,
                        batchId = row.batchId,
                        status = "RETRY",
                        attemptCount = row.attemptCount + 1,
                        error = error.message ?: error.javaClass.simpleName
                    )
                    throw error
                }
            )
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
