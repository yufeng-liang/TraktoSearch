package com.tracktosearch.data.ai

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class AiProfileSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val authManager: AuthManager,
    private val coordinator: AiProfileSyncCoordinator
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val friendId = authManager.friendId.value?.trim().orEmpty()
        if (friendId.isBlank()) return Result.success()
        if (authManager.authState.value !in setOf(AuthState.AUTHORIZED, AuthState.OFFLINE)) {
            return Result.success()
        }
        return coordinator.syncNow(friendId).fold(
            onSuccess = { Result.success() },
            onFailure = { if (runAttemptCount >= 3) Result.failure() else Result.retry() }
        )
    }
}
