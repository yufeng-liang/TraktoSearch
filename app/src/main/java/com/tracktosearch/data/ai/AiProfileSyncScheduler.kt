package com.tracktosearch.data.ai

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiProfileSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val WORK_NAME = "ai_profile_sync_work"
        private const val PERIODIC_INTERVAL_HOURS = 6L
    }

    fun schedulePeriodic() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AiProfileSyncWorker>(
                PERIODIC_INTERVAL_HOURS,
                TimeUnit.HOURS
            ).setConstraints(networkConstraints()).build()
        )
    }

    fun enqueueNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${WORK_NAME}_once",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AiProfileSyncWorker>()
                .setConstraints(networkConstraints())
                .build()
        )
    }

    fun cancel() {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(WORK_NAME)
        manager.cancelUniqueWork("${WORK_NAME}_once")
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
