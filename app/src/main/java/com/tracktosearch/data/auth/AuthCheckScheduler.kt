package com.tracktosearch.data.auth

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthCheckScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val WORK_NAME = "auth_check_work"
        const val PREFLIGHT_WORK_NAME = "auth_check_preflight_work"
        const val FORCE_CHECK_INPUT = "force_check"
        private const val CHECK_INTERVAL_MINUTES = 15L
        private const val PREFLIGHT_LEAD_TIME_MINUTES = 5L
    }

    fun schedulePeriodicCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<AuthCheckWorker>(
            CHECK_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** 在下次校验到期前约五分钟预校验，避免用户启动时才承担网络等待。 */
    fun schedulePreflight(nextCheckAt: Long) {
        val now = System.currentTimeMillis()
        if (nextCheckAt <= 0L || nextCheckAt * 1_000L <= now) {
            cancelPreflight()
            return
        }

        val delayMillis = (nextCheckAt * 1_000L - now -
            PREFLIGHT_LEAD_TIME_MINUTES * 60_000L).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<AuthCheckWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(Data.Builder().putBoolean(FORCE_CHECK_INPUT, true).build())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            PREFLIGHT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelPreflight() {
        WorkManager.getInstance(context).cancelUniqueWork(PREFLIGHT_WORK_NAME)
    }
}
