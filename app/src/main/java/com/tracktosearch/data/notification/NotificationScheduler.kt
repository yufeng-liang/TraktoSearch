package com.tracktosearch.data.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val WORK_NAME = "release_check_work"
        private const val CHECK_INTERVAL_HOURS = 12L
    }

    fun schedulePeriodicCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ReleaseCheckWorker>(
            CHECK_INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelPeriodicCheck() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    suspend fun isScheduled(): Boolean = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WORK_NAME)
            .get()
            .any { it.state == androidx.work.WorkInfo.State.ENQUEUED ||
                    it.state == androidx.work.WorkInfo.State.RUNNING }
    }
}
