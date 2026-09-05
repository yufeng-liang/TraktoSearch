package com.tracktosearch.ui.screen.statistics

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.local.StatisticsSnapshot
import com.tracktosearch.data.local.StatisticsSnapshotStore
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.local.db.UserReviewEntity
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.UserReviewRepository
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.data.util.MediaIdentity
import com.tracktosearch.data.remote.trakt.dto.*
import com.tracktosearch.ui.haptic.HapticOutcome
import com.tracktosearch.ui.haptic.HapticOutcomeEmitter
import com.tracktosearch.ui.util.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@Immutable
data class StatisticsUiState(
    val totalMovieCount: Int = 0,
    /** 有观看记录的剧数（含未看完） */
    val showsWatchedCount: Int = 0,
    /** 已整部看完的剧数 */
    val showsCompletedCount: Int = 0,
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
    /** 「整部看完」的剧数是否可算（需要 watchedShows 的季进度）；未就绪时隐藏该行而非显示 0 */
    val showsCompletedReady: Boolean = false,
    val watchTimeReady: Boolean = false,
    val heatmapReady: Boolean = false,
    val ratingsReady: Boolean = false,
    val genreReady: Boolean = false,
    val wordCloudReady: Boolean = false,
    // 首个区块就绪前显示整页骨架
    val initialLoading: Boolean = false,
    /** 正在展示上次快照并后台刷新中（顶部细进度条，不遮挡内容） */
    val isRefreshing: Boolean = false,
    val error: String? = null
)

private data class RatingRecord(
    val key: String,
    val score: Int
)

private data class LocalDoubanRecord(
    val item: DoubanSyncedItem,
    val detail: DoubanDetailCacheEntry?
) {
    /** 豆瓣条目的全部可用标识，用于和 Trakt 侧跨源去重 */
    val identityKeys: Set<String> by lazy {
        MediaIdentity.keysOf(
            imdbId = item.imdbId,
            traktId = item.traktId,
            tmdbId = item.tmdbId,
            titles = listOf(item.displayTitle, item.title, detail?.title),
            year = item.year ?: detail?.year?.toIntOrNull()
        )
    }
}

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
    private val userReviewRepository: UserReviewRepository,
    private val doubanSyncedItemDao: DoubanSyncedItemDao,
    private val doubanRepository: DoubanRepository,
    private val sessionModeManager: SessionModeManager,
    private val snapshotStore: StatisticsSnapshotStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatisticsUiState(initialLoading = true))
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    /**
     * 「部分数据没加载上」的一次性提示。
     *
     * 本类有六处非致命降级各自吞掉异常后照样出页面，而页面上是**错的数字**（少算的豆瓣
     * 条目、缺失的评分、永远转着的词云骨架），[StatisticsUiState.error] 却是 null。
     * 一屏数字全是错的而一声不响，比整页报错更糟。这里只补一句提示，不挡着看已有内容。
     */
    private val _toastEvent = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val toastEvent: SharedFlow<Int> = _toastEvent.asSharedFlow()

    /** 结果类触感的出口，界面侧一行 `HapticOutcomeEffect(viewModel.hapticOutcomes)` 收集。 */
    private val hapticOutcomeEmitter = HapticOutcomeEmitter()
    val hapticOutcomes: SharedFlow<HapticOutcome> = hapticOutcomeEmitter.outcomes

    private var loadJob: kotlinx.coroutines.Job? = null

    /** 渐进发布用的已到达数据；字段为 null 表示对应请求尚未返回。 */
    private data class LoadedData(
        val movies: List<TraktWatchlistMovieItem>? = null,
        val shows: List<TraktWatchlistShowItem>? = null,
        val watchedShows: List<TraktWatchedShow>? = null,
        val userStats: TraktUserStatsResponse? = null,
        val allRatings: List<TraktRatingItem>? = null,
        val words: List<WordCloudItem> = emptyList(),
        val wordCloudReady: Boolean = false,
        val doubanItems: List<DoubanSyncedItem>? = null,
        val isDoubanMode: Boolean = false,
        val doubanDetails: Map<String, DoubanDetailCacheEntry> = emptyMap()
    )

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
            // 非致命降级的账本：下面六处 runCatching 各自兜底成空集合后页面照样出，
            // 但那时屏幕上的数字是错的。用 AtomicBoolean 是因为几处落账发生在
            // Dispatchers.IO 的并行 async 里，普通 var 会有数据竞争
            val degraded = AtomicBoolean(false)
            try {
                val isDoubanMode = sessionModeManager.isDoubanMode.first()
                if (!sessionModeManager.traktConnected.value && !isDoubanMode) {
                    _uiState.value = StatisticsUiState(initialLoading = false)
                    return@launch
                }

                // 上次结果先秒出，再后台刷新（stale-while-revalidate）：
                // 网络到达前页面就有内容，不必看整页骨架。
                restoreSnapshot()

                val doubanItemsDeferred = async(Dispatchers.IO) {
                    runCatching { doubanSyncedItemDao.getAllSyncedItems() }
                        .onFailure { degraded.set(true) }
                        .getOrDefault(emptyList())
                }
                // 详情缓存只读本地 DataStore，不触发豆瓣网络请求；用于补齐集数、片长和类型。
                // getDetailSnapshot() 会全量读盘并反序列化所有豆瓣详情，没有「看过」条目时完全不必付这个代价。
                val doubanDetailsDeferred = async(Dispatchers.IO) {
                    val items = doubanItemsDeferred.await()
                    if (items.none { it.status.equals("collect", ignoreCase = true) }) {
                        emptyMap()
                    } else {
                        runCatching { doubanRepository.getDetailSnapshot() }
                            .onFailure { degraded.set(true) }
                            .getOrDefault(emptyMap())
                    }
                }

                if (isDoubanMode && !sessionModeManager.traktConnected.value) {
                    val doubanItems = doubanItemsDeferred.await()
                    val doubanDetails = doubanDetailsDeferred.await()
                    val reviews = runCatching {
                        withContext(Dispatchers.IO) { userReviewRepository.getAllReviews() }
                    }.onFailure { degraded.set(true) }.getOrDefault(emptyList())
                    val words = buildWordCloud(reviews, doubanItems)
                    publish(
                        LoadedData(
                            words = words,
                            wordCloudReady = true,
                            doubanItems = doubanItems,
                            isDoubanMode = true,
                            doubanDetails = doubanDetails
                        )
                    )
                    saveSnapshot()
                    notifyIfDegraded(degraded.get())
                    return@launch
                }

                // 5 个请求并行发起，各自到达后增量发布对应区块，不等最慢请求
                // 1. getUserStats 单个小请求即含部数/集数/时长，最快到达，总览与观影时长先出
                // 2. show history 降级 extended=min（仅需时间戳，episode 级数据量大）
                // 3. ratings 并行
                // 4. 不用 N+1 getShowWatchedProgress：watchedShows 的 aired_episodes 已够判断整部看完
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
                        Result.success(buildWordCloud(reviews, doubanItems))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        Result.failure(Exception("WORD_CLOUD_LOAD_FAILED"))
                    }
                }

                // 渐进式发布：每拿到一个依赖就计算并刷新已就绪的区块
                var data = LoadedData(isDoubanMode = isDoubanMode)

                // 用户统计（非致命）：单个小请求，通常最先到达，总览与观影时长立即可见
                data = data.copy(
                    userStats = userStatsDeferred.await().onFailure { degraded.set(true) }.getOrNull()
                )
                publish(data)

                val wordCloudResult = wordCloudDeferred.await()
                // 词云失败时 wordCloudReady 留在 false，界面画的是骨架 —— 看起来像还在加载
                if (wordCloudResult.isFailure) degraded.set(true)
                data = data.copy(
                    words = wordCloudResult.getOrDefault(emptyList()),
                    wordCloudReady = wordCloudResult.isSuccess,
                    doubanItems = doubanItemsDeferred.await(),
                    doubanDetails = doubanDetailsDeferred.await()
                )
                publish(data)

                // 电影历史（致命）：到达后电影部数按跨源去重修正，观影时长可用本地 runtime 兜底
                data = data.copy(
                    movies = movieHistoryDeferred.await().getOrElse {
                        publishError(it)
                        return@launch
                    }
                )
                publish(data)

                // 剧集历史（致命）：+电影后热力图就绪
                data = data.copy(
                    shows = showHistoryDeferred.await().getOrElse {
                        publishError(it)
                        return@launch
                    }
                )
                publish(data)

                // 已看剧（致命）：+电影后类型分布就绪，并给出「整部看完」的剧数
                data = data.copy(
                    watchedShows = watchedShowsDeferred.await().getOrElse {
                        publishError(it)
                        return@launch
                    }
                )
                publish(data)

                // 评分（非致命）
                data = data.copy(
                    allRatings = ratingsDeferred.await()
                        .onFailure { degraded.set(true) }
                        .getOrDefault(emptyList())
                )
                publish(data)

                // 全部到位后落盘，供下次进页秒出
                saveSnapshot()
                notifyIfDegraded(degraded.get())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 与三处致命请求失败落在同一个出口：错误文案与触感只有一处要维护
                publishError(e)
            }
        }
    }

    /**
     * 用磁盘快照填充 UI。
     *
     * 各区块直接标记为就绪并进入 [StatisticsUiState.isRefreshing]：用户看到的是上次的真实数字，
     * 顶部细进度条表示正在刷新，网络到达后逐块替换。无快照时保持整页骨架。
     */
    private suspend fun restoreSnapshot() {
        val snapshot = snapshotStore.load() ?: return
        _uiState.value = StatisticsUiState(
            totalMovieCount = snapshot.totalMovieCount,
            showsWatchedCount = snapshot.showsWatchedCount,
            showsCompletedCount = snapshot.showsCompletedCount,
            totalEpisodeCount = snapshot.totalEpisodeCount,
            thisMonthWatched = snapshot.thisMonthWatched,
            thisYearWatched = snapshot.thisYearWatched,
            genreDistribution = snapshot.genreDistribution,
            heatmapData = snapshot.heatmapData,
            totalWatchMinutes = snapshot.totalWatchMinutes,
            totalRatings = snapshot.totalRatings,
            averageRating = snapshot.averageRating,
            ratingDistribution = snapshot.ratingDistribution,
            wordCloud = snapshot.wordCloud.map { WordCloudItem(it.word, it.weight) },
            overviewReady = true,
            showsCompletedReady = true,
            watchTimeReady = true,
            heatmapReady = snapshot.heatmapData.isNotEmpty(),
            ratingsReady = snapshot.totalRatings > 0,
            genreReady = snapshot.genreDistribution.isNotEmpty(),
            wordCloudReady = snapshot.wordCloud.isNotEmpty(),
            initialLoading = false,
            isRefreshing = true
        )
    }

    /** 把当前完整结果写回磁盘快照。部分失败时不写，避免用不完整数据覆盖上次的好快照。 */
    private suspend fun saveSnapshot() {
        val state = _uiState.value
        if (state.error != null) return
        snapshotStore.save(
            StatisticsSnapshot(
                totalMovieCount = state.totalMovieCount,
                showsWatchedCount = state.showsWatchedCount,
                showsCompletedCount = state.showsCompletedCount,
                totalEpisodeCount = state.totalEpisodeCount,
                thisMonthWatched = state.thisMonthWatched,
                thisYearWatched = state.thisYearWatched,
                genreDistribution = state.genreDistribution,
                heatmapData = state.heatmapData,
                totalWatchMinutes = state.totalWatchMinutes,
                totalRatings = state.totalRatings,
                averageRating = state.averageRating,
                ratingDistribution = state.ratingDistribution,
                wordCloud = state.wordCloud.map { StatisticsSnapshot.Word(it.word, it.weight) },
                savedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 致命请求失败：保留已展示的内容（快照或已到达的区块），只挂错误提示并结束刷新态。
     * 有内容在屏时页面不应退回整页错误，否则刷新失败会把用户已看到的数据抹掉。
     */
    private fun publishError(error: Throwable) {
        _uiState.value = _uiState.value.copy(
            initialLoading = false,
            isRefreshing = false,
            error = error.toUserMessage(context, R.string.error_load_failed)
        )
        hapticOutcomeEmitter.emit(HapticOutcome.FAILURE)
    }

    /**
     * 有非致命降级时提示一句「数字可能不全」，并发一记 reject。
     *
     * 六处降级合成一条提示：一处一条会在网络差的时候连弹六个 toast、连震六记。
     * 成功路径不发触感 —— 一屏数字铺出来本身就是反馈。
     */
    private fun notifyIfDegraded(degraded: Boolean) {
        if (!degraded) return
        _toastEvent.tryEmit(R.string.statistics_partial_load_failed)
        hapticOutcomeEmitter.emit(HapticOutcome.FAILURE)
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

    /** Trakt 电影条目的全部可用标识 */
    private fun TraktMovie.identityKeys(): Set<String> = MediaIdentity.keysOf(
        imdbId = ids.imdb, traktId = ids.trakt, tmdbId = ids.tmdb,
        titles = listOf(title), year = year
    )

    /** Trakt 剧集条目的全部可用标识 */
    private fun TraktShow.identityKeys(): Set<String> = MediaIdentity.keysOf(
        imdbId = ids.imdb, traktId = ids.trakt, tmdbId = ids.tmdb,
        titles = listOf(title), year = year
    )

    /**
     * 按跨源身份去重：与 [known] 或已保留条目共享任一标识的条目被丢弃。
     *
     * 无任何标识的条目一律保留——无法证明它和别的条目是同一部，宁可多算也不能凭空合并。
     */
    private fun <T> dedupeByIdentity(
        items: List<T>,
        known: Set<String>,
        keysOf: (T) -> Set<String>
    ): List<T> {
        val seen = known.toMutableSet()
        val result = mutableListOf<T>()
        for (item in items) {
            val keys = keysOf(item)
            if (MediaIdentity.isKnown(keys, seen)) continue
            seen += keys
            result += item
        }
        return result
    }

    /** 已看不同集数，排除特别篇（season 0），与 Trakt `aired_episodes` 的口径一致 */
    private fun watchedRegularEpisodeCount(show: TraktWatchedShow): Int =
        show.seasons.filter { it.number > 0 }.sumOf { season -> season.episodes.count { it.completed > 0 } }

    /**
     * 增量发布：根据当前已到达的依赖计算各区块，已就绪的区块立即更新，
     * 未就绪的区块保持上一次的值（快照或更早批次）。单写入者（主协程），无并发竞争。
     */
    private fun publish(data: LoadedData) {
        val prev = _uiState.value

        val collectItems = data.doubanItems.orEmpty()
            .filter { it.status.equals("collect", ignoreCase = true) }
        val localRecords = collectItems.map { item ->
            LocalDoubanRecord(item, data.doubanDetails[item.doubanId])
        }
        // 豆瓣独立模式（无 Trakt）：没有 Trakt 侧数据可比对，豆瓣条目全部计入
        val isDoubanOnly = data.isDoubanMode && data.doubanItems != null

        // —— 跨源身份索引 ——
        // 只按单一优选 key 去重会漏判：豆瓣条目可能只有 traktId，而 Trakt 侧优选 imdb 作为 key，
        // 两边永远对不上，同一部影视被算两次。因此两边都收集全部可用标识，任一相交即视为同一部。
        val traktMovieKeys = mutableSetOf<String>()
        data.movies?.forEach { traktMovieKeys += it.movie.identityKeys() }
        val traktShowKeys = mutableSetOf<String>()
        data.shows?.forEach { traktShowKeys += it.show.identityKeys() }
        data.watchedShows?.forEach { traktShowKeys += it.show.identityKeys() }

        val localMovies = localRecords.filter { mediaType(it.item, it.detail) == "movie" }
        val localShows = localRecords.filter { mediaType(it.item, it.detail) == "show" }

        // Trakt 身份集合还没到齐时先不加豆瓣条目：否则会双算。
        // 数字随后只会往上补，读起来像「还在加载」，比先多后少更不容易被误认为出错。
        val localMovieOnly = when {
            isDoubanOnly -> dedupeByIdentity(localMovies, emptySet()) { it.identityKeys }
            data.movies != null -> dedupeByIdentity(localMovies, traktMovieKeys) { it.identityKeys }
            else -> emptyList()
        }
        val localShowOnly = when {
            isDoubanOnly -> dedupeByIdentity(localShows, emptySet()) { it.identityKeys }
            data.shows != null || data.watchedShows != null ->
                dedupeByIdentity(localShows, traktShowKeys) { it.identityKeys }
            else -> emptyList()
        }

        // 合并所有观看记录时间戳（history 用 watched_at，watchlist 用 listed_at）
        val allDates = mutableListOf<Date>()
        data.movies?.forEach { parseDate(it.watched_at.ifBlank { it.listed_at })?.let { d -> allDates.add(d) } }
        data.shows?.forEach { parseDate(it.watched_at.ifBlank { it.listed_at })?.let { d -> allDates.add(d) } }
        if (isDoubanOnly || (data.movies != null && data.shows != null)) {
            (localMovieOnly + localShowOnly).forEach { record ->
                parseDate(record.item.markedAt ?: record.item.listedAt.orEmpty())?.let { allDates.add(it) }
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
        val heatmapReady = isDoubanOnly || (data.movies != null && data.shows != null)
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

        // 电影去重后的 Trakt 条目：/sync/history 是「每次观看一条」，同一部看多次会重复，
        // 用于类型分布与 userStats 缺失时的部数兜底。
        val distinctTraktMovies = data.movies?.let {
            dedupeByIdentity(it, emptySet()) { item -> item.movie.identityKeys() }
        }

        // userStats 请求成功但内容全零时不能算「就绪」：那可能只是空响应体，
        // 先显示 0 再被 history 修正会出现数字跳动，读起来像出错。
        val usableUserStats = data.userStats?.takeIf {
            it.movies.watched > 0 || it.shows.watched > 0 || it.episodes.watched > 0 ||
                it.movies.minutes > 0 || it.episodes.minutes > 0
        }

        // 总览：userStats 单请求即可给出部数/集数，先出；history 到达后按跨源去重修正。
        // 各数字的「可算就绪」条件互相独立：电影部数只要有 movies 就能算，不该等 watchedShows。
        // overviewReady 只决定卡片整体是显示数字还是骨架。
        val overviewReady = isDoubanOnly || usableUserStats != null ||
            (data.movies != null && data.watchedShows != null)
        val movieCountReady = isDoubanOnly || usableUserStats != null || data.movies != null
        val showCountReady = isDoubanOnly || usableUserStats != null || data.watchedShows != null
        // 「整部看完」必须有 watchedShows 的季进度，userStats 给不了；未就绪时隐藏该行而不是显示 0
        val showsCompletedReady = isDoubanOnly || data.watchedShows != null

        val traktMovieCount = usableUserStats?.movies?.watched?.takeIf { it > 0 }
            ?: distinctTraktMovies?.size ?: 0
        val totalMovieCount = traktMovieCount + localMovieOnly.size

        // 有观看记录的剧数：watchedShows 是权威列表，未到达时用 userStats 兜底
        val traktShowsWatched = data.watchedShows?.size
            ?: usableUserStats?.shows?.watched?.takeIf { it > 0 } ?: 0
        // 整部看完：已看不同集数 ≥ 已播出总集数。
        // aired_episodes 随 watched 端点的完整剧信息一起返回，不必为每部剧再发 progress 请求。
        val traktShowsCompleted = data.watchedShows?.count { show ->
            val aired = show.show.airedEpisodes
            aired > 0 && watchedRegularEpisodeCount(show) >= aired
        } ?: 0
        // 豆瓣「看过」的剧集不细分集数，标记看过即代表整部看完，两个口径都计入
        val showsWatchedCount = traktShowsWatched + localShowOnly.size
        val showsCompletedCount = traktShowsCompleted + localShowOnly.size

        val networkEpisodeCount = usableUserStats?.episodes?.watched?.takeIf { it > 0 }
            ?: data.watchedShows?.sumOf { show ->
                show.seasons.sumOf { season -> season.episodes.count { it.completed > 0 } }
            } ?: 0
        val totalEpisodeCount = networkEpisodeCount + localShowOnly.sumOf(::localEpisodeCount)

        // 类型分布：电影（已去重，一部只算一次）+ 已看剧
        val genreReady = isDoubanOnly || (data.movies != null && data.watchedShows != null)
        val genreCount = mutableMapOf<String, Int>()
        distinctTraktMovies?.forEach { item -> item.movie.genres.forEach { g -> genreCount[g] = (genreCount[g] ?: 0) + 1 } }
        data.watchedShows?.forEach { item -> item.show.genres.forEach { g -> genreCount[g] = (genreCount[g] ?: 0) + 1 } }
        localMovieOnly.forEach { item -> localGenres(item).forEach { g -> genreCount[g] = (genreCount[g] ?: 0) + 1 } }
        localShowOnly.forEach { item -> localGenres(item).forEach { g -> genreCount[g] = (genreCount[g] ?: 0) + 1 } }
        val genreDistribution = if (genreReady) {
            genreCount.toList().sortedByDescending { it.second }.toMap()
        } else emptyMap()

        // 观影时长：优先使用服务端汇总，豆瓣本地条目补充缓存中的片长。
        val watchTimeReady = isDoubanOnly || usableUserStats != null || data.movies != null
        val networkWatchMinutes = usableUserStats?.let { it.movies.minutes.toLong() + it.episodes.minutes.toLong() }
            ?.takeIf { it > 0 }
            ?: distinctTraktMovies?.sumOf { it.movie.runtime.toLong() } ?: 0
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
        val networkRatings = data.allRatings.orEmpty().mapIndexedNotNull { index, item ->
            item.rating.takeIf { it > 0 }?.let { rating ->
                RatingRecord(ratingKey(item, index), rating)
            }
        }.filter { it.key !in localRatingKeys }
        val effectiveRatings = localRatings + networkRatings
        val ratingsReady = data.allRatings != null || isDoubanOnly
        val totalRatings = if (ratingsReady) effectiveRatings.size else 0
        val averageRating = if (totalRatings > 0) effectiveRatings.map { it.score }.average() else 0.0
        val ratingDistribution = if (ratingsReady) {
            effectiveRatings.groupBy { it.score }.mapValues { it.value.size }
        } else emptyMap()

        // 首个区块就绪即退出整页骨架
        val initialLoading = prev.initialLoading &&
            !overviewReady && !watchTimeReady && !heatmapReady &&
            !ratingsReady && !genreReady && !data.wordCloudReady

        // 未就绪的区块沿用上一次的值（快照或更早批次），避免刷新过程中已显示的内容被清空
        _uiState.value = prev.copy(
            totalMovieCount = if (movieCountReady) totalMovieCount else prev.totalMovieCount,
            showsWatchedCount = if (showCountReady) showsWatchedCount else prev.showsWatchedCount,
            showsCompletedCount = if (showsCompletedReady) showsCompletedCount else prev.showsCompletedCount,
            totalEpisodeCount = if (showCountReady) totalEpisodeCount else prev.totalEpisodeCount,
            thisMonthWatched = if (heatmapReady) thisMonthWatched else prev.thisMonthWatched,
            thisYearWatched = if (heatmapReady) thisYearWatched else prev.thisYearWatched,
            genreDistribution = if (genreReady) genreDistribution else prev.genreDistribution,
            heatmapData = if (heatmapReady) heatmapData else prev.heatmapData,
            totalWatchMinutes = if (watchTimeReady) totalWatchMinutes else prev.totalWatchMinutes,
            totalRatings = if (ratingsReady) totalRatings else prev.totalRatings,
            averageRating = if (ratingsReady) averageRating else prev.averageRating,
            ratingDistribution = if (ratingsReady) ratingDistribution else prev.ratingDistribution,
            wordCloud = if (data.wordCloudReady) data.words else prev.wordCloud,
            overviewReady = prev.overviewReady || overviewReady,
            showsCompletedReady = prev.showsCompletedReady || showsCompletedReady,
            watchTimeReady = prev.watchTimeReady || watchTimeReady,
            heatmapReady = prev.heatmapReady || heatmapReady,
            ratingsReady = prev.ratingsReady || ratingsReady,
            genreReady = prev.genreReady || genreReady,
            wordCloudReady = prev.wordCloudReady || data.wordCloudReady,
            initialLoading = initialLoading,
            // 评分是最后一步；它到位说明这一轮刷新结束
            isRefreshing = prev.isRefreshing && !ratingsReady,
            error = null
        )
    }
}
