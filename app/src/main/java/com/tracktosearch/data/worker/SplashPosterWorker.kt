package com.tracktosearch.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tracktosearch.data.repository.SplashQuoteRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把开屏台词的海报整池补齐。
 *
 * 启动时的预取只覆盖未来几天，够用但不够稳：用户可能连着几周都在弱网下启动。
 * 这个 Worker 在「不计费网络 + 非低电量」时把整池一次性补完，
 * 之后无论哪天开屏都能直接拿到海报，不会退化成占位图。
 *
 * 整池 w342 只有 ~2MB，跑一次就长期不用再跑，所以池子齐了直接 success 返回。
 */
@HiltWorker
class SplashPosterWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val splashQuoteRepository: SplashQuoteRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (splashQuoteRepository.isPoolComplete()) return Result.success()
        return try {
            splashQuoteRepository.prefetchAll()
            Result.success()
        } catch (e: Exception) {
            // 下载失败大概率是网络问题，交给 WorkManager 退避重试
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "splash_poster_prefetch"
    }
}

@Singleton
class SplashPosterScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * 排定周期补齐任务。
     *
     * 周期给到 24 小时而不是更短：池子一旦齐了后续每次执行都是空转，
     * 频繁唤醒只是白耗电。KEEP 策略保证重复调用不会重排已有任务。
     */
    fun schedulePeriodicPrefetch() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<SplashPosterWorker>(
            PREFETCH_INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SplashPosterWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelPeriodicPrefetch() {
        WorkManager.getInstance(context).cancelUniqueWork(SplashPosterWorker.WORK_NAME)
    }

    companion object {
        private const val PREFETCH_INTERVAL_HOURS = 24L
    }
}
