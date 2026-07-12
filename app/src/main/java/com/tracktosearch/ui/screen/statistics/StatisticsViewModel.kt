package com.tracktosearch.ui.screen.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem
import com.tracktosearch.data.repository.TraktRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.tracktosearch.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

@Immutable
data class StatisticsUiState(
    val totalMovieCount: Int = 0,
    val totalShowCount: Int = 0,
    val totalEpisodeCount: Int = 0,
    val thisMonthWatched: Int = 0,
    val thisYearWatched: Int = 0,
    val genreDistribution: Map<String, Int> = emptyMap(),
    val heatmapData: Map<String, Int> = emptyMap(),
    val totalWatchMinutes: Long = 0,
    val totalRatings: Int = 0,
    val averageRating: Double = 0.0,
    val ratingDistribution: Map<Int, Int> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatisticsUiState(isLoading = true))
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    // 解析 ISO 8601 时间戳，返回 Date 或 null
    private fun parseDate(dateStr: String): Date? {
        if (dateStr.isBlank()) return null
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd"
        )
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(dateStr)
            } catch (_: Exception) {
                // 尝试下一个格式
            }
        }
        return null
    }

    fun loadStatistics() {
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                // 5 个 API 并行请求，耗时取决于最慢的那个
                // 优化点：
                // 1. getUserStats 替代本地时长/集数求和（服务端已汇总）
                // 2. show history 降级 extended=min（仅需时间戳，episode 级数据量大）
                // 3. ratings 并行而非串行在最后
                // 4. 删除 N+1 getShowWatchedProgress（watchedShows 已有 completed 字段）
                // 每个 async 包 try-catch 返回 Result.failure,确保即使未被 await(因前置 await
                // 提前 return@launch)也不会产生被静默吞掉的异常
                val movieHistoryDeferred = async {
                    try { traktRepository.getAllMovieHistory(extended = "full") }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Result.failure(e) }
                }
                val showHistoryDeferred = async {
                    try { traktRepository.getAllShowHistory(extended = "min") }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Result.failure(e) }
                }
                val watchedShowsDeferred = async {
                    try { traktRepository.getWatchedShowsWithEpisodes() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Result.failure(e) }
                }
                val userStatsDeferred = async {
                    try { traktRepository.getUserStats() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Result.failure(e) }
                }
                val ratingsDeferred = async {
                    try { traktRepository.getAllUserRatings() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Result.failure(e) }
                }

                val movies = movieHistoryDeferred.await().getOrElse {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = it.message ?: context.getString(R.string.error_load_failed))
                    return@launch
                }
                val shows = showHistoryDeferred.await().getOrElse {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = it.message ?: context.getString(R.string.error_load_failed))
                    return@launch
                }
                val watchedShows = watchedShowsDeferred.await().getOrElse {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = it.message ?: context.getString(R.string.error_load_failed))
                    return@launch
                }
                // userStats 失败时降级为本地计算
                val userStats = userStatsDeferred.await().getOrNull()
                // ratings 失败时空列表降级
                val allRatings = ratingsDeferred.await().getOrDefault(emptyList())

                // 合并所有观看记录的时间戳（history 端点用 watched_at，watchlist 端点用 listed_at）
                val allDates = mutableListOf<Date>()
                movies.forEach { item ->
                    val dateStr = item.watched_at.ifBlank { item.listed_at }
                    parseDate(dateStr)?.let { allDates.add(it) }
                }
                shows.forEach { item ->
                    val dateStr = item.watched_at.ifBlank { item.listed_at }
                    parseDate(dateStr)?.let { allDates.add(it) }
                }

                val totalMovieCount = movies.size

                // 总集数：优先用 userStats 服务端数据，降级为本地 watchedShows 求和
                val totalEpisodeCount = userStats?.episodes?.watched?.takeIf { it > 0 }
                    ?: watchedShows.sumOf { show ->
                        show.seasons.sumOf { season ->
                            season.episodes.count { it.completed > 0 }
                        }
                    }

                // 整季看完的剧集数：直接用 watchedShows 的 completed 字段判断，不再调 N 次 progress API
                val totalShowCount = watchedShows.count { show ->
                    show.seasons.any { season ->
                        season.number > 0 &&
                            season.episodes.isNotEmpty() &&
                            season.episodes.all { it.completed > 0 }
                    }
                }

                // 本月/本年统计
                val calendar = Calendar.getInstance()
                val currentYear = calendar.get(Calendar.YEAR)
                val currentMonth = calendar.get(Calendar.MONTH)
                var thisMonthWatched = 0
                var thisYearWatched = 0
                for (date in allDates) {
                    val cal = Calendar.getInstance()
                    cal.time = date
                    if (cal.get(Calendar.YEAR) == currentYear) {
                        thisYearWatched++
                        if (cal.get(Calendar.MONTH) == currentMonth) {
                            thisMonthWatched++
                        }
                    }
                }

                // 类型分布：电影从 history 的 movie.genres，剧集从 watchedShows 的 show.genres
                // （show history 降级为 min 后无 genres，改用 watchedShows 提供剧集类型，按剧集计数更合理）
                val genreCount = mutableMapOf<String, Int>()
                movies.forEach { item ->
                    item.movie.genres.forEach { genre ->
                        genreCount[genre] = (genreCount[genre] ?: 0) + 1
                    }
                }
                watchedShows.forEach { item ->
                    item.show.genres.forEach { genre ->
                        genreCount[genre] = (genreCount[genre] ?: 0) + 1
                    }
                }

                // 总观影时长：优先用 userStats 服务端数据（分钟），降级为本地 movie runtime 求和
                val totalWatchMinutes = userStats?.let {
                    it.movies.minutes.toLong() + it.episodes.minutes.toLong()
                }?.takeIf { it > 0 } ?: movies.sumOf { it.movie.runtime.toLong() }

                // 用户评分统计
                val totalRatings = allRatings.size
                val averageRating = if (totalRatings > 0) allRatings.map { it.rating }.average() else 0.0
                val ratingDistribution = allRatings.groupBy { it.rating }.mapValues { it.value.size }

                // 热力图数据：过去 365 天每天的观看次数
                val heatmapData = mutableMapOf<String, Int>()
                val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                dateKeyFormat.timeZone = TimeZone.getDefault()
                for (date in allDates) {
                    val key = dateKeyFormat.format(date)
                    heatmapData[key] = (heatmapData[key] ?: 0) + 1
                }

                _uiState.value = StatisticsUiState(
                    totalMovieCount = totalMovieCount,
                    totalShowCount = totalShowCount,
                    totalEpisodeCount = totalEpisodeCount,
                    thisMonthWatched = thisMonthWatched,
                    thisYearWatched = thisYearWatched,
                    genreDistribution = genreCount.toMap().toList()
                        .sortedByDescending { it.second }
                        .toMap(),
                    heatmapData = heatmapData.toMap(),
                    totalWatchMinutes = totalWatchMinutes,
                    totalRatings = totalRatings,
                    averageRating = averageRating,
                    ratingDistribution = ratingDistribution,
                    isLoading = false,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: context.getString(R.string.error_load_failed)
                )
            }
        }
    }
}
