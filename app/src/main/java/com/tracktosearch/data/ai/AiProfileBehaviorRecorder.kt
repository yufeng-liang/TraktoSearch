package com.tracktosearch.data.ai

import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用级画像行为入口。
 *
 * 统一处理授权边界、媒体快照镜像和后台同步调度；记录失败不应影响原有用户操作。
 */
@Singleton
class AiProfileBehaviorRecorder @Inject constructor(
    private val profileRepository: AiProfileRepository,
    private val syncScheduler: AiProfileSyncScheduler,
    private val authManager: AuthManager
) {

    suspend fun recordNow(
        snapshot: MediaSourceSnapshot,
        behavior: AiProfileBehavior? = null,
        day: String = LocalDate.now().toString(),
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val friendId = authManager.friendId.value?.trim().orEmpty()
        if (friendId.isBlank() || authManager.authState.value !in AUTHORIZED_STATES) return false

        val settings = runCatching { profileRepository.settings(friendId) }.getOrNull()
            ?: return false
        if (!settings.profileConsent) return false

        val normalized = runCatching {
            profileRepository.mirrorMedia(friendId, snapshot, now)
        }.getOrNull() ?: return false

        var behaviorRecorded = false
        if (behavior != null && settings.behaviorConsent) {
            behaviorRecorded = runCatching {
                profileRepository.recordBehavior(
                    friendId = friendId,
                    mediaKey = normalized.mediaKey,
                    day = day,
                    behavior = behavior,
                    now = now
                )
            }.getOrDefault(false)
        }

        if (settings.syncEnabled) syncScheduler.enqueueNow()
        return true
    }

    suspend fun recordDetailDwell(
        snapshot: MediaSourceSnapshot,
        durationMs: Long,
        day: String = LocalDate.now().toString(),
        now: Long = System.currentTimeMillis()
    ): Boolean = recordNow(
        snapshot = snapshot,
        behavior = AiProfileBehavior.DetailDwell(durationMs),
        day = day,
        now = now
    )

    private companion object {
        val AUTHORIZED_STATES = setOf(AuthState.AUTHORIZED, AuthState.OFFLINE)
    }
}
