package com.tracktosearch.data.auth

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 后台定期校验网关授权，确保后台撤销后不会长期沿用本地会话。
 */
@HiltWorker
class AuthCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val authManager: AuthManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        authManager.initialize()
        return Result.success()
    }
}
