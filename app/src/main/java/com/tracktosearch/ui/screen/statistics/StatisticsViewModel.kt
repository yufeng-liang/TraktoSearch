package com.tracktosearch.ui.screen.statistics

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.local.db.UserReviewEntity
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private data class RatingRecord(
    val key: String,
    val score: Int
)

private data class LocalDoubanRecord(
    val item: DoubanSyncedItem,
    val detail: DoubanDetailCacheEntry?
)

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
    private val userReviewRepository: UserReviewRepository,
    private val doubanSyncedItemDao: DoubanSyncedItemDao,
    private val doubanRepository: DoubanRepository,
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
            "yyyy-MM-dd HH:mm:ss",
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
        _uiState.value = StatisticsUiState(initialLoading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val isDoubanMode = sessionModeManager.isDoubanMode.first()
                if (!sessionModeManager.traktConnected.value && !isDoubanMode) {
                    _uiState.value = StatisticsUiState(initialLoading = false)
                    return@launch
                }

                val doubanItemsDeferred = async(Dispatchers.IO) {
                    runCatching { doubanSyncedItemDao.getAllSyncedItems() }.getOrDefault(emptyList())
                }
                // 详情缓存只读本地 DataStore，不触发豆瓣网络请求；用于补齐集数、片长和类型。
                val doubanDetailsDeferred = async(Dispatchers.IO) {
                    runCatching { doubanRepository.getDetailSnapshot() }.getOrDefault(emptyMap())
                }

                if (isDoubanMode && !sessionModeManager.traktConnected.value) {
                    val doubanItems = doubanItemsDeferred.await()
                    val doubanDetails = doubanDetailsDeferred.await()
                    val reviews = runCatching {
                        withContext(Dispatchers.IO) { userReviewRepository.getAllReviews() }
                    }.getOrDefault(emptyList())
                    val words = buildWordCloud(reviews, doubanItems)
                    publish(
                        movies = null,
                        shows = null,
                        watchedShows = null,
                        userStats = null,
                        allRatings = null,
                        words = words,
                        wordCloudReady = true,
                        doubanItems = doubanItems,
                        isDoubanMode = true,
                        doubanDetails = doubanDetails
                    )
                    return@launch
                }

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
                        val doubanItems = doubanItemsDeferred.await()
                        val words = buildWordCloud(reviews, doubanItems)
                        Result.success(
                            words
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        Result.failure(Exception("WORD_CLOUD_LOAD_FAILED"))
                    }
                }

                // 渐进式发布：每拿到一个依赖就计算并刷新已就绪的区块
                val wordCloudResult = wordCloudDeferred.await()
                val words = wordCloudResult.getOrDefault(emptyList())
                val wordCloudReady = wordCloudResult.isSuccess
                val doubanItems = doubanItemsDeferred.await()
                val doubanDetails = doubanDetailsDeferred.await()
                var movies: List<TraktWatchlistMovieItem>? = null
                var shows: List<TraktWatchlistShowItem>? = null
                var watchedShows: List<TraktWatchedShow>? = null
                var userStats: TraktUserStatsResponse? = null
                var allRatings: List<TraktRatingItem>? = null

                // 词云（本地、最快）先发布
                publish(
                    movies = movies, shows = shows, watchedShows = watchedShows,
                    userStats = userStats, allRatings = allRatings, words = words,
                    wordCloudReady = wordCloudReady,
                    doubanItems = doubanItems,
                    isDoubanMode = isDoubanMode,
                    doubanDetails = doubanDetails
                )

                // 电影历史（致命）：到达后观影时长即用本地 runtime 兜底
                movies = movieHistoryDeferred.await().getOrElse {
                    _uiState.value = _uiState.value.copy(initialLoading = false, error = it.message ?: context.getString(R.string.error_load_failed))
                    return@launch
                }
                publish(movies, shows, watchedShows, userStats, allRatings, words, wordCloudReady, doubanItems, isDoubanMode, doubanDetails)

                // 剧集历史（致命）：+电影后热力图就绪
                shows = showHistoryDeferred.await().getOrElse {
                    _uiState.value = _uiState.value.copy(initialLoading = false, error = it.message ?: context.getString(R.string.error_load_failed))
                    return@launch
                }
                publish(movies, shows, watchedShows, userStats, allRatings, words, wordCloudReady, doubanItems, isDoubanMode, doubanDetails)

                // 已看剧（致命）：+电影+剧集后概览与类型分布就绪
                watchedShows = watchedShowsDeferred.await().getOrElse {
                    _uiState.value = _uiState.value.copy(initialLoading = false, error = it.message ?: context.getString(R.string.error_load_failed))
                    return@launch
                }
                publish(movies, shows, watchedShows, userStats, allRatings, words, wordCloudReady, doubanItems, isDoubanMode, doubanDetails)

                // 用户统计（非致命，优先用于时长/集数）
                userStats = userStatsDeferred.await().getOrNull()
                publish(movies, shows, watchedShows, userStats, allRatings, words, wordCloudReady, doubanItems, isDoubanMode, doubanDetails)

                // 评分（非致命）
                allRatings = ratingsDeferred.await().getOrDefault(emptyList())
                publish(movies, shows, watchedShows, userStats, allRatings, words, wordCloudReady, doubanItems, isDoubanMode, doubanDetails)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    initialLoading = false,
                    error = e.message ?: context.getString(R.string.error_load_failed)
                )
            }
        }
    }

    private fun buildWordCloud(
        reviews: List<UserReviewEntity>,
        doubanItems: List<DoubanSyncedItem>
    ): List<WordCloudItem> {
        val comments = (
            reviews.mapNotNull { it.comment?.takeIf(String::isNotBlank) } +
                doubanItems
                    .asSequence()
                    .filter { it.status.equals("collect", ignoreCase = true) }
                    .mapNotNull { it.comment?.takeIf(String::isNotBlank) }
                    .toList()
            ).distinct()
        return ReviewTokenizer.tokenize(comments)
            .entries
            .sortedByDescending { it.value }
            .map { WordCloudItem(it.key, it.value) }
    }

    private fun mediaKey(
        imdbId: String?,
        traktId: Int,
        title: String,
        year: Int,
        fallbackId: String
    ): String {
        imdbId?.trim()?.lowercase()?.takeIf(String::isNotBlank)?.let { return "imdb:$it" }
        if (traktId > 0) return "trakt:$traktId"
        val normalizedTitle = title.trim().lowercase()
        if (normalizedTitle.isNotBlank()) return "title:$normalizedTitle:$year"
        return "fallback:$fallbackId"
    }

    private fun genres(value: String?): List<String> = value.orEmpty()
        .split(Regex("\\s*(?:/|·|,|，|、)\\s*"))
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun mediaType(item: DoubanSyncedItem, detail: DoubanDetailCacheEntry?): String? = when {
        item.mediaType.equals("movie", ignoreCase = true) -> "movie"
        item.mediaType.equals("show", ignoreCase = true) -> "show"
        item.mediaType.equals("variety", ignoreCase = true) -> "show"
        item.mediaType.equals("documentary", ignoreCase = true) -> "show"
        detail?.mediaType.equals("movie", ignoreCase = true) -> "movie"
        detail?.isTvShow == true -> "show"
        detail != null -> "movie"
        else -> null
    }

    private fun parseDurationMinutes(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val hours = Regex("(\\d+)\\s*(?:小时|小時|h)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val minutes = Regex("(\\d+)\\s*(?:分钟|分鐘|min|m)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        if (hours > 0L || minutes > 0L) return hours * 60L + minutes
        return Regex("\\d+").find(value)?.value?.toLongOrNull() ?: 0L
    }

    private fun localEpisodeCount(record: LocalDoubanRecord): Int =
        record.detail?.episodeCount?.takeIf { it > 0 } ?: 0

    private fun localWatchMinutes(record: LocalDoubanRecord): Long {
        val detail = record.detail ?: return 0L
        return if (mediaType(record.item, detail) == "show") {
            localEpisodeCount(record).toLong() * parseDurationMinutes(detail.episodeDuration)
        } else {
            parseDurationMinutes(detail.runtime)
        }
    }

    private fun localGenres(record: LocalDoubanRecord): List<String> =
        (genres(record.item.genres) + record.detail?.genres.orEmpty()).distinct()

    private fun ratingKey(item: TraktRatingItem, index: Int): String = when {
        item.movie != null -> mediaKey(
            imdbId = item.movie.ids.imdb,
            traktId = item.movie.ids.trakt,
            title = item.movie.title,
            year = item.movie.year,
            fallbackId = "trakt-rating-movie-$index"
        )
        item.show != null -> mediaKey(
            imdbId = item.show.ids.imdb,
            traktId = item.show.ids.trakt,
            title = item.show.title,
            year = item.show.year,
            fallbackId = "trakt-rating-show-$index"
        )
        else -> "trakt-rating-$index"
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
        words: List<WordCloudItem>,
        wordCloudReady: Boolean,
        doubanItems: List<DoubanSyncedItem>? = null,
        isDoubanMode: Boolean = false,
        doubanDetails: Map<String, DoubanDetailCacheEntry> = emptyMap()
    ) {
        val prev = _uiState.value

        val collectItems = doubanItems.orEmpty()
            .filter { it.status.equals("collect", ignoreCase = true) }
        val localRecords = collectItems.map { item ->
            LocalDoubanRecord(item, doubanDetails[item.doubanId])
        }
        val localOnlyReady = isDoubanMode && doubanItems != null
        val localMovieOnly = if (localOnlyReady || movies != null) {
            val networkKeys = movies.orEmpty().map { item ->
                mediaKey(
                    imdbId = item.movie.ids.imdb,
                    traktId = item.movie.ids.trakt,
                    title = item.movie.title,
                    year = item.movie.year,
                    fallbackId = "trakt-movie"
                )
            }.toSet()
            localRecords.filter { mediaType(it.item, it.detail) == "movie" }
                .filter { record ->
                    val item = record.item
                    mediaKey(item.imdbId, item.traktId ?: 0, item.displayTitle ?: item.title, item.year ?: 0, item.doubanId) !in networkKeys
                }
        } else emptyList()
        val localShowOnly = if (localOnlyReady || shows != null || watchedShows != null) {
            val networkKeys = (
                shows.orEmpty().map { item ->
                    mediaKey(
                        imdbId = item.show.ids.imdb,
                        traktId = item.show.ids.trakt,
                        title = item.show.title,
                        year = item.show.year,
                        fallbackId = "trakt-show"
                    )
                } + watchedShows.orEmpty().map { item ->
                    mediaKey(
                        imdbId = item.show.ids.imdb,
                        traktId = item.show.ids.trakt,
                        title = item.show.title,
                        year = item.show.year,
                        fallbackId = "trakt-watched-show"
                    )
                }
                ).toSet()
            localRecords.filter { mediaType(it.item, it.detail) == "show" }
                .filter { record ->
                    val item = record.item
                    mediaKey(item.imdbId, item.traktId ?: 0, item.displayTitle ?: item.title, item.year ?: 0, item.doubanId) !in networkKeys
                }
        } else emptyList()

        // 合并所有观看记录时间戳（history 用 watched_at，watchlist 用 listed_at）
        val allDates = mutableListOf<Date>()
        movies?.forEach { parseDate(it.watched_at.ifBlank { it.listed_at })?.let { allDates.add(it) } }
        shows?.forEach { parseDate(it.watched_at.ifBlank { it.listed_at })?.let { allDates.add(it) } }
        if (localOnlyReady || (movies != null && shows != null)) {
            (localMovieOnly + localShowOnly).forEach { item ->
                parseDate(item.item.markedAt ?: item.item.listedAt.orEmpty())?.let { allDates.add(it) }
            }
        }

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
        val heatmapReady = localOnlyReady || (movies != null && shows != null)
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
        val overviewReady = localOnlyReady || (movies != null && shows != null && watchedShows != null)
        val totalMovieCount = when {
            movies != null -> movies.size + localMovieOnly.size
            localOnlyReady -> localMovieOnly.size
            else -> 0
        }
        val totalShowCount = when {
            watchedShows != null -> watchedShows.count { show ->
                show.seasons.any { season ->
                    season.number > 0 && season.episodes.isNotEmpty() && season.episodes.all { it.completed > 0 }
                }
            } + localShowOnly.size
            localOnlyReady -> localShowOnly.size
            else -> 0
        }
        val networkEpisodeCount = userStats?.episodes?.watched?.takeIf { it > 0 }
            ?: watchedShows?.sumOf { show ->
                show.seasons.sumOf { season -> season.episodes.count { it.completed > 0 } }
            } ?: 0
        val totalEpisodeCount = networkEpisodeCount + localShowOnly.sumOf(::localEpisodeCount)

        // 类型分布：电影+已看剧
        val genreReady = localOnlyReady || (movies != null && watchedShows != null)
        val genreCount = mutableMapOf<String, Int>()
        movies?.forEach { item -> item.movie.genres.forEach { g -> genreCount[g] = (genreCount[g] ?: 0) + 1 } }
        watchedShows?.forEach { item -> item.show.genres.forEach { g -> genreCount[g] = (genreCount[g] ?: 0) + 1 } }
        localMovieOnly.forEach { item -> localGenres(item).forEach { g -> genreCount[g] = (genreCount[g] ?: 0) + 1 } }
        localShowOnly.forEach { item -> localGenres(item).forEach { g -> genreCount[g] = (genreCount[g] ?: 0) + 1 } }
        val genreDistribution = if (genreReady) {
            genreCount.toList().sortedByDescending { it.second }.toMap()
        } else emptyMap()

        // 观影时长：优先使用服务端汇总，豆瓣本地条目补充缓存中的片长。
        val watchTimeReady = localOnlyReady || movies != null
        val networkWatchMinutes = userStats?.let { it.movies.minutes.toLong() + it.episodes.minutes.toLong() }
            ?.takeIf { it > 0 } ?: movies?.sumOf { it.movie.runtime.toLong() } ?: 0
        val totalWatchMinutes = networkWatchMinutes + (localMovieOnly + localShowOnly).sumOf(::localWatchMinutes)

        // 评分：同一 IMDb 优先保留豆瓣个人评分，豆瓣 1-5 星转换为现有 10 分制。
        val localRatings = collectItems.mapNotNull { item ->
            item.rating?.takeIf { it in 1..5 }?.let { rating ->
                RatingRecord(
                    key = mediaKey(item.imdbId, item.traktId ?: 0, item.displayTitle ?: item.title, item.year ?: 0, item.doubanId),
                    score = rating * 2
                )
            }
        }
        val localRatingKeys = localRatings.map { it.key }.toSet()
        val networkRatings = allRatings.orEmpty().mapIndexedNotNull { index, item ->
            item.rating.takeIf { it > 0 }?.let { rating ->
                RatingRecord(ratingKey(item, index), rating)
            }
        }.filter { it.key !in localRatingKeys }
        val effectiveRatings = localRatings + networkRatings
        val ratingsReady = allRatings != null || localOnlyReady
        val totalRatings = if (ratingsReady) effectiveRatings.size else 0
        val averageRating = if (totalRatings > 0) effectiveRatings.map { it.score }.average() else 0.0
        val ratingDistribution = if (ratingsReady) {
            effectiveRatings.groupBy { it.score }.mapValues { it.value.size }
        } else emptyMap()

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
