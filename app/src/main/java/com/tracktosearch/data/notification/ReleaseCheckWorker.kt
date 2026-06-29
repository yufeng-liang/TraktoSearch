package com.tracktosearch.data.notification

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tracktosearch.data.local.NotificationStorage
import com.tracktosearch.data.local.db.NotificationRecordDao
import com.tracktosearch.data.remote.tmdb.TmdbApiService
import com.tracktosearch.data.repository.TraktRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 定期检查想看列表中影视的上映/上线状态，并发送通知
 */
@HiltWorker
class ReleaseCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val traktRepository: TraktRepository,
    private val tmdbApiService: TmdbApiService,
    private val notificationStorage: NotificationStorage,
    private val notificationRecordDao: NotificationRecordDao,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_NAME = "release_check_work"
    }

    private fun createDateParser(): SimpleDateFormat {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    override suspend fun doWork(): Result {
        // 检查通知总开关
        val enabled = notificationStorage.enabled.first()
        if (!enabled) return Result.success()

        // 确保通知渠道已创建
        notificationHelper.createChannels()

        val releaseEnabled = notificationStorage.releaseReminderEnabled.first()
        val newSeasonEnabled = notificationStorage.newSeasonReminderEnabled.first()

        // 清理 90 天前的通知记录
        val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
        notificationRecordDao.deleteOlderThan(cutoff)

        // 获取想看列表
        val movieWatchlist = traktRepository.getAllMovieWatchlist().getOrElse { return Result.retry() }
        val showWatchlist = traktRepository.getAllShowWatchlist().getOrElse { return Result.retry() }

        val today = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        // 允许检查过去 30 天内上映的影视（避免错过刚上映的）
        val startDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            add(Calendar.DAY_OF_YEAR, -30)
        }

        if (releaseEnabled) {
            checkMovieReleases(movieWatchlist, today, startDate)
            checkShowReleases(showWatchlist, today, startDate)
        }

        if (newSeasonEnabled) {
            checkShowNewSeasons(showWatchlist, today, startDate)
        }

        return Result.success()
    }

    private suspend fun checkMovieReleases(
        watchlist: List<com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem>,
        today: Calendar,
        startDate: Calendar
    ) {
        coroutineScope {
            watchlist.mapNotNull { item ->
                val tmdbId = item.movie.ids.tmdb.takeIf { it > 0 } ?: return@mapNotNull null
                val traktId = item.movie.ids.trakt
                async {
                    try {
                        val response = tmdbApiService.getMovieDetail(tmdbId)
                        if (!response.isSuccessful) return@async
                        val detail = response.body() ?: return@async
                        val releaseDateStr = detail.release_date.takeIf { it.isNotBlank() } ?: return@async

                        val releaseDate = try {
                            createDateParser().parse(releaseDateStr) ?: return@async
                        } catch (_: Exception) {
                            return@async
                        }

                        val releaseCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            time = releaseDate
                        }

                        if (releaseCal.after(startDate) && !releaseCal.after(today)) {
                            val payload = releaseDateStr
                            val existing = notificationRecordDao.find(traktId, "release", payload)
                            if (existing == null) {
                                notificationHelper.showReleaseNotification(
                                    title = item.movie.title,
                                    releaseDate = releaseDateStr,
                                    traktId = traktId,
                                    tmdbId = tmdbId,
                                    mediaType = "movie"
                                )
                                notificationRecordDao.insert(
                                    com.tracktosearch.data.local.db.NotificationRecordEntity(
                                        traktId = traktId,
                                        tmdbId = tmdbId,
                                        mediaType = "movie",
                                        title = item.movie.title,
                                        type = "release",
                                        payload = payload
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {
                        // 单个失败不影响其他
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun checkShowReleases(
        watchlist: List<com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem>,
        today: Calendar,
        startDate: Calendar
    ) {
        coroutineScope {
            watchlist.mapNotNull { item ->
                val tmdbId = item.show.ids.tmdb.takeIf { it > 0 } ?: return@mapNotNull null
                val traktId = item.show.ids.trakt
                async {
                    try {
                        val response = tmdbApiService.getTvDetail(tmdbId)
                        if (!response.isSuccessful) return@async
                        val detail = response.body() ?: return@async
                        val airDateStr = detail.first_air_date.takeIf { it.isNotBlank() } ?: return@async

                        val airDate = try {
                            createDateParser().parse(airDateStr) ?: return@async
                        } catch (_: Exception) {
                            return@async
                        }

                        val airCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            time = airDate
                        }

                        if (airCal.after(startDate) && !airCal.after(today)) {
                            val payload = airDateStr
                            val existing = notificationRecordDao.find(traktId, "release", payload)
                            if (existing == null) {
                                notificationHelper.showReleaseNotification(
                                    title = item.show.title,
                                    releaseDate = airDateStr,
                                    traktId = traktId,
                                    tmdbId = tmdbId,
                                    mediaType = "show"
                                )
                                notificationRecordDao.insert(
                                    com.tracktosearch.data.local.db.NotificationRecordEntity(
                                        traktId = traktId,
                                        tmdbId = tmdbId,
                                        mediaType = "show",
                                        title = item.show.title,
                                        type = "release",
                                        payload = payload
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {
                        // 单个失败不影响其他
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun checkShowNewSeasons(
        watchlist: List<com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem>,
        today: Calendar,
        startDate: Calendar
    ) {
        // TMDB TV 详情不直接返回季列表，需要额外请求 /tv/{id}/season/{season_number}
        // 这里简化处理：仅检查首播日期作为新季开播的近似（适用于想看列表中的剧集）
        // 完整的新季检测需要调用 Trakt 的 seasons API，此处先实现基础版本
        coroutineScope {
            watchlist.mapNotNull { item ->
                val tmdbId = item.show.ids.tmdb.takeIf { it > 0 } ?: return@mapNotNull null
                val traktId = item.show.ids.trakt
                async {
                    try {
                        val response = tmdbApiService.getTvDetail(tmdbId)
                        if (!response.isSuccessful) return@async
                        val detail = response.body() ?: return@async
                        val airDateStr = detail.first_air_date.takeIf { it.isNotBlank() } ?: return@async

                        val airDate = try {
                            createDateParser().parse(airDateStr) ?: return@async
                        } catch (_: Exception) {
                            return@async
                        }

                        val airCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            time = airDate
                        }

                        // 如果首播日期在过去 30 天内，视为"新季开播"（简化逻辑）
                        if (airCal.after(startDate) && !airCal.after(today)) {
                            val payload = "season_1_$airDateStr"
                            val existing = notificationRecordDao.find(traktId, "new_season", payload)
                            if (existing == null) {
                                notificationHelper.showNewSeasonNotification(
                                    title = item.show.title,
                                    seasonNumber = 1,
                                    airDate = airDateStr,
                                    traktId = traktId,
                                    tmdbId = tmdbId
                                )
                                notificationRecordDao.insert(
                                    com.tracktosearch.data.local.db.NotificationRecordEntity(
                                        traktId = traktId,
                                        tmdbId = tmdbId,
                                        mediaType = "show",
                                        title = item.show.title,
                                        type = "new_season",
                                        payload = payload
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {
                        // 单个失败不影响其他
                    }
                }
            }.awaitAll()
        }
    }
}
