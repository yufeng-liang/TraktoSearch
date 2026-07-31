package com.tracktosearch.ui.screen.statistics

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.UserReviewRepository
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.data.remote.trakt.dto.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val wordCloud: List<WordCloudItem> = emptyList(),
    // 各区块就绪标志：数据到达即显示，不等最慢请求
    val overviewReady: Boolean = false,
    val watchTimeReady: Boolean = false,
    val heatmapReady: Boolean = false,
    val ratingsReady: Boolean = false,
    val genreReady: Boolean = false,
    val wordCloudReady: Boolean = false,
    // 首个区块就绪前显示整页骨架
    val initialLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
    private val userReviewRepository: UserReviewRepository,
    private val sessionModeManager: SessionModeManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatisticsUiState(initialLoading = true))
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
        if (!sessionModeManager.traktConnected.value) {
            _uiState.value = StatisticsUiState(initialLoading = false)
            return
        }
        _uiState.value = StatisticsUiState(initialLoading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                // 5 个请求并行发起，各自到达后增量发布对应区块，不等最慢请求
                // 1. getUserStats 替代本地时长/集数求和（服务端已汇总）
                // 2. show history 降级 extended=min（仅需时间戳，episode 级数据量大）
                // 3. ratings 并行
                // 4. 删除 N+1 getShowWatchedProgress（watchedShows 已有 completed 字段）
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
                // 短评词云：本地数据，独立并行，失败不影响主流程
                val wordCloudDeferred = async(Dispatchers.IO) {
                    try {
                        val reviews = userReviewRepository.getAllReviews()
                        val comments = reviews.mapNotNull { it.comment?.takeIf { c -> c.isNotBlank() } }
                        val freq = ReviewTokenizer.tokenize(comments)
                        freq.entries
                            .sortedByDescending { it.value }
                            .map { WordCloudItem(it.key, it.value) }
                    } catch (_: Exception) {
                        emptyList<WordCloudItem>()
                    }
                }

                // 渐进式发布：每拿到一个依赖就计算并刷新已就绪的区块
                val words = wordCloudDeferred.await()
                var movies: List<TraktWatchlistMovieItem>? = null
                var shows: List<TraktWatchlistShowItem>? = null
                var watchedShows: List<TraktWatchedShow>? = null
                var userStats: TraktUserStatsResponse? = null
                var allRatings: List<TraktRatingItem>? = null

                // 词云（本地、最快）先发布
                publish(
                    movies = movies, shows = shows, watchedShows = watchedShows,
                    userStats = userStats, allRatings = allRatings, words = words
                )

                // 电影历史（致命）：到达后观影时长即用本地 runtime 兜底
                movies = movieHistoryDeferred.await().getOrElse {
                    _uiState.value = _uiState.value.copy(initialLoading = false, error = it.message ?: context.getString(R.string.error_load_failed))
                    return@launch
                }
                publish(movies, shows, watchedShows, userStats, allRatings, words)

                // 剧集历史（致命）：+电影后热力图就绪
                shows = showHistoryDeferred.await().getOrElse {
                    _uiState.value = _uiState.value.copy(initialLoading = false, error = it.message ?: context.getString(R.string.error_load_failed))
                    return@launch
                }
                publish(movies, shows, watchedShows, userStats, allRatings, words)

                // 已看剧（致命）：+电影+剧集后概览与类型分布就绪
                watchedShows = watchedShowsDeferred.await().getOrElse {
                    _uiState.value = _uiState.value.copy(initialLoading = false, error = it.message ?: context.getString(R.string.error_load_failed))
                    return@launch
                }
                publish(movies, shows, watchedShows, userStats, allRatings, words)

                // 用户统计（非致命，优先用于时长/集数）
                userStats = userStatsDeferred.await().getOrNull()
                publish(movies, shows, watchedShows, userStats, allRatings, words)

                // 评分（非致命）
                allRatings = ratingsDeferred.await().getOrDefault(emptyList())
                publish(movies, shows, watchedShows, userStats, allRatings, words)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    initialLoading = false,
                    error = e.message ?: context.getString(R.string.error_load_failed)
                )
            }
        }
    }

    /**
     * 增量发布：根据当前已到达的依赖计算各区块，已就绪的区块立即更新，
     * 未就绪的区块保持默认（UI 显示骨架占位）。单写入者（主协程），无并发竞争。
     */
    private fun publish(
        movies: List<TraktWatchlistMovieItem>?,
        shows: List<TraktWatchlistShowItem>?,
        watchedShows: List<TraktWatchedShow>?,
        userStats: TraktUserStatsResponse?,
        allRatings: List<TraktRatingItem>?,
        words: List<WordCloudItem>
    ) {
        val prev = _uiState.value

        // 合并所有观看记录时间戳（history 用 watched_at，watchlist 用 listed_at）
        val allDates = mutableListOf<Date>()
        movies?.forEach { parseDate(it.watched_at.ifBlank { it.listed_at })?.let { allDates.add(it) } }
        shows?.forEach { parseDate(it.watched_at.ifBlank { it.listed_at })?.let { allDates.add(it) } }

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

        // 热力图：电影+剧集
        val heatmapReady = movies != null && shows != null
        val heatmapData = if (heatmapReady) {
            val map = mutableMapOf<String, Int>()
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = TimeZone.getDefault()
            for (date in allDates) {
                val key = fmt.format(date)
                map[key] = (map[key] ?: 0) + 1
            }
            map.toMap()
        } else emptyMap()

        // 概览：电影+剧集+已看剧
        val overviewReady = movies != null && shows != null && watchedShows != null
        val totalMovieCount = movies?.size ?: 0
        val totalShowCount = watchedShows?.count { show ->
            show.seasons.any { season ->
                season.number > 0 && season.episodes.isNotEmpty() && season.episodes.all { it.completed > 0 }
            }
        } ?: 0
        val totalEpisodeCount = userStats?.episodes?.watched?.takeIf { it > 0 }
            ?: watchedShows?.sumOf { show ->
                show.seasons.sumOf { season -> season.episodes.count { it.completed > 0 } }
            } ?: 0

        // 类型分布：电影+已看剧
        val genreReady = movies != null && watchedShows != null
        val genreCount = mutableMapOf<String, Int>()
        movies?.forEach { item -> item.movie.genres.forEach { g -> genreCount[g] = (genreCount[g] ?: 0) + 1 } }
        watchedShows?.forEach { item -> item.show.genres.forEach { g -> genreCount[g] = (genreCount[g] ?: 0) + 1 } }
        val genreDistribution = if (genreReady) {
            genreCount.toList().sortedByDescending { it.second }.toMap()
        } else emptyMap()

        // 观影时长：电影（优先 userStats 升级）
        val watchTimeReady = movies != null
        val totalWatchMinutes = userStats?.let { it.movies.minutes.toLong() + it.episodes.minutes.toLong() }
            ?.takeIf { it > 0 } ?: movies?.sumOf { it.movie.runtime.toLong() } ?: 0

        // 评分
        val ratingsReady = allRatings != null
        val totalRatings = allRatings?.size ?: 0
        val averageRating = if (totalRatings > 0) allRatings!!.map { it.rating }.average() else 0.0
        val ratingDistribution = allRatings?.groupBy { it.rating }?.mapValues { it.value.size } ?: emptyMap()

        val wordCloudReady = words.isNotEmpty()

        // 首个区块就绪即退出整页骨架
        val initialLoading = prev.initialLoading &&
            !overviewReady && !watchTimeReady && !heatmapReady &&
            !ratingsReady && !genreReady && !wordCloudReady

        _uiState.value = prev.copy(
            totalMovieCount = totalMovieCount,
            totalShowCount = totalShowCount,
            totalEpisodeCount = totalEpisodeCount,
            thisMonthWatched = thisMonthWatched,
            thisYearWatched = thisYearWatched,
            genreDistribution = genreDistribution,
            heatmapData = heatmapData,
            totalWatchMinutes = totalWatchMinutes,
            totalRatings = totalRatings,
            averageRating = averageRating,
            ratingDistribution = ratingDistribution,
            wordCloud = words,
            overviewReady = overviewReady,
            watchTimeReady = watchTimeReady,
            heatmapReady = heatmapReady,
            ratingsReady = ratingsReady,
            genreReady = genreReady,
            wordCloudReady = wordCloudReady,
            initialLoading = initialLoading,
            error = null
        )
    }
}
