package com.tracktosearch.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.tracktosearch.data.local.UserProfileStorage
import com.tracktosearch.data.remote.trakt.TraktApiService
import com.tracktosearch.data.remote.trakt.dto.*
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.data.util.TtlCache
import com.tracktosearch.data.util.persistentTtlCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/** Trakt 持久化缓存专用 DataStore */
private val Context.traktPersistentCacheStore: DataStore<Preferences> by preferencesDataStore(name = "trakt_persistent_cache")

@Singleton
class TraktRepository @Inject constructor(
    private val traktApiService: TraktApiService,
    private val userProfileStorage: UserProfileStorage,
    private val json: Json,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TTL_ID_MAPPING = Long.MAX_VALUE    // ID 转换永不过期（tmdb↔trkt 映射不会变）
        private const val TTL_COMMENTS = 10 * 60 * 1000L     // 评论 10 分钟
        private const val TTL_RELATED = 30 * 60 * 1000L      // 相关推荐 30 分钟
        private const val TTL_RECOMMENDATIONS = 6 * 60 * 60 * 1000L // 个性化推荐 6 小时
        private const val TTL_STATS = 5 * 60 * 1000L         // 统计数据 5 分钟
        private const val TTL_TRENDING = 6 * 60 * 60 * 1000L  // 趋势/最受期待/社区列表 6 小时（榜单更新不频繁）
        private const val TTL_WATCHLIST_IDS = 6 * 60 * 60 * 1000L // 想看/已看 ID 集合 6 小时（避免每次启动全量拉取）
    }

    /** 全局想看/已看 ID 缓存，登录后加载一次，退出登录时清除 */
    @Serializable
    data class WatchlistWatchedIds(
        val movieWatchlistTraktIds: Set<Int> = emptySet(),
        val movieWatchlistTmdbIds: Set<Int> = emptySet(),
        val showWatchlistTraktIds: Set<Int> = emptySet(),
        val showWatchlistTmdbIds: Set<Int> = emptySet(),
        val movieWatchedTraktIds: Set<Int> = emptySet(),
        val movieWatchedTmdbIds: Set<Int> = emptySet(),
        val showWatchedTraktIds: Set<Int> = emptySet(),
        val showWatchedTmdbIds: Set<Int> = emptySet(),
        /** TMDB ID → Trakt ID 映射（来自想看+已看数据） */
        val movieTmdbToTrakt: Map<Int, Int> = emptyMap(),
        val showTmdbToTrakt: Map<Int, Int> = emptyMap()
    ) {
        fun isInWatchlist(traktId: Int?, tmdbId: Int?, type: MediaType): Boolean = when (type) {
            MediaType.MOVIE -> movieWatchlistTraktIds.contains(traktId) || movieWatchlistTmdbIds.contains(tmdbId)
            MediaType.SHOW -> showWatchlistTraktIds.contains(traktId) || showWatchlistTmdbIds.contains(tmdbId)
            else -> false
        }

        fun isWatched(traktId: Int?, tmdbId: Int?, type: MediaType): Boolean = when (type) {
            MediaType.MOVIE -> movieWatchedTraktIds.contains(traktId) || movieWatchedTmdbIds.contains(tmdbId)
            MediaType.SHOW -> showWatchedTraktIds.contains(traktId) || showWatchedTmdbIds.contains(tmdbId)
            else -> false
        }

        /** 通过 TMDB ID 查找 Trakt ID（想看/已看命中时可直接跳转） */
        fun traktIdByTmdb(tmdbId: Int, type: MediaType): Int? = when (type) {
            MediaType.MOVIE -> movieTmdbToTrakt[tmdbId]
            MediaType.SHOW -> showTmdbToTrakt[tmdbId]
            else -> null
        }
    }

    @Volatile
    private var watchlistWatchedIds: WatchlistWatchedIds? = null

    /** 加载全局想看/已看 ID 缓存（登录后调用，仅加载一次）。
     *  优先读持久化缓存（6h TTL，跨 App 重启复用），未命中或过期时走网络全量拉取并写回持久化缓存 */
    suspend fun loadWatchlistWatchedIds(): WatchlistWatchedIds {
        watchlistWatchedIds?.let { return it }
        // 先尝试持久化缓存（避免每次启动都发 4 个 /sync/* 请求）
        val cacheKey = "watchlist_watched_ids"
        watchlistWatchedIdsCache.awaitLoaded()
        watchlistWatchedIdsCache.get(cacheKey)?.let { cached ->
            watchlistWatchedIds = cached
            return cached
        }
        return try {
            coroutineScope {
                val movieWatchlistDef = async { getAllMovieWatchlist() }
                val showWatchlistDef = async { getAllShowWatchlist() }
                val movieHistoryDef = async { getAllMovieHistory(extended = "min") }
                val showHistoryDef = async { getAllShowHistory(extended = "min") }
                val movieWatchlist = movieWatchlistDef.await().getOrDefault(emptyList())
                val showWatchlist = showWatchlistDef.await().getOrDefault(emptyList())
                val movieHistory = movieHistoryDef.await().getOrDefault(emptyList())
                val showHistory = showHistoryDef.await().getOrDefault(emptyList())
                val ids = WatchlistWatchedIds(
                    movieWatchlistTraktIds = movieWatchlist.map { it.movie.ids.trakt }.toSet(),
                    movieWatchlistTmdbIds = movieWatchlist.map { it.movie.ids.tmdb }.filter { it > 0 }.toSet(),
                    showWatchlistTraktIds = showWatchlist.map { it.show.ids.trakt }.toSet(),
                    showWatchlistTmdbIds = showWatchlist.map { it.show.ids.tmdb }.filter { it > 0 }.toSet(),
                    movieWatchedTraktIds = movieHistory.map { it.movie.ids.trakt }.toSet(),
                    movieWatchedTmdbIds = movieHistory.map { it.movie.ids.tmdb }.filter { it > 0 }.toSet(),
                    showWatchedTraktIds = showHistory.map { it.show.ids.trakt }.toSet(),
                    showWatchedTmdbIds = showHistory.map { it.show.ids.tmdb }.filter { it > 0 }.toSet(),
                    movieTmdbToTrakt = (movieWatchlist + movieHistory).associate { it.movie.ids.tmdb to it.movie.ids.trakt }.filterKeys { it > 0 },
                    showTmdbToTrakt = (showWatchlist + showHistory).associate { it.show.ids.tmdb to it.show.ids.trakt }.filterKeys { it > 0 }
                )
                watchlistWatchedIds = ids
                // 写回持久化缓存（异步落盘，6h TTL）
                watchlistWatchedIdsCache.put(cacheKey, ids)
                ids
            }
        } catch (e: Exception) {
            Log.w("TraktRepo", "loadWatchlistWatchedIds failed: ${e.message}")
            WatchlistWatchedIds()
        }
    }

    /** 获取当前缓存（可能为 null，需先调用 loadWatchlistWatchedIds） */
    fun getWatchlistWatchedIds(): WatchlistWatchedIds? = watchlistWatchedIds

    /** 清除全局想看/已看缓存及所有用户私有内存缓存（退出登录时调用） */
    fun clearWatchlistWatchedCache() {
        watchlistWatchedIds = null
        // 清除所有用户私有内存缓存，避免下一用户看到上一用户的历史/评分/想看列表
        commentsCache.clear()
        relatedMoviesCache.clear()
        relatedShowsCache.clear()
        movieHistoryCache.clear()
        showHistoryCache.clear()
        watchedShowsCache.clear()
        userRatingsCache.clear()
        userStatsCache.clear()
        movieWatchlistCache.clear()
        showWatchlistCache.clear()
        // 清除负缓存，避免下一用户继承上一用户的"未找到"标记
        notFoundTmdbIds.clear()
        notFoundImdbIds.clear()
        // 同步清除持久化缓存，避免下次登录仍读到旧账号数据
        persistentScope.launch {
            try { watchlistWatchedIdsCache.clearAll() } catch (_: Exception) {}
        }
    }

    /** 将当前内存中的 watchlistWatchedIds 异步写回持久化缓存（增删后调用以保持一致） */
    private fun persistWatchlistWatchedIds() {
        val current = watchlistWatchedIds ?: return
        persistentScope.launch {
            try { watchlistWatchedIdsCache.put("watchlist_watched_ids", current) } catch (_: Exception) {}
        }
    }

    /** 缓存已加载时，添加想看 ID 到缓存 */
    private fun addToWatchlistCache(traktId: Int, tmdbId: Int, type: MediaType) {
        watchlistWatchedIds?.let { current ->
            watchlistWatchedIds = when (type) {
                MediaType.MOVIE -> current.copy(
                    movieWatchlistTraktIds = current.movieWatchlistTraktIds + traktId,
                    movieWatchlistTmdbIds = if (tmdbId > 0) current.movieWatchlistTmdbIds + tmdbId else current.movieWatchlistTmdbIds
                )
                MediaType.SHOW -> current.copy(
                    showWatchlistTraktIds = current.showWatchlistTraktIds + traktId,
                    showWatchlistTmdbIds = if (tmdbId > 0) current.showWatchlistTmdbIds + tmdbId else current.showWatchlistTmdbIds
                )
                else -> current
            }
            persistWatchlistWatchedIds()
        }
    }

    /** 缓存已加载时，从想看缓存移除 ID */
    private fun removeFromWatchlistCache(traktId: Int, tmdbId: Int, type: MediaType) {
        watchlistWatchedIds?.let { current ->
            watchlistWatchedIds = when (type) {
                MediaType.MOVIE -> current.copy(
                    movieWatchlistTraktIds = current.movieWatchlistTraktIds - traktId,
                    movieWatchlistTmdbIds = if (tmdbId > 0) current.movieWatchlistTmdbIds - tmdbId else current.movieWatchlistTmdbIds
                )
                MediaType.SHOW -> current.copy(
                    showWatchlistTraktIds = current.showWatchlistTraktIds - traktId,
                    showWatchlistTmdbIds = if (tmdbId > 0) current.showWatchlistTmdbIds - tmdbId else current.showWatchlistTmdbIds
                )
                else -> current
            }
            persistWatchlistWatchedIds()
        }
    }

    /** 缓存已加载时，添加已看 ID 到缓存 */
    private fun addToWatchedCache(traktId: Int, tmdbId: Int, type: MediaType) {
        watchlistWatchedIds?.let { current ->
            watchlistWatchedIds = when (type) {
                MediaType.MOVIE -> current.copy(
                    movieWatchedTraktIds = current.movieWatchedTraktIds + traktId,
                    movieWatchedTmdbIds = if (tmdbId > 0) current.movieWatchedTmdbIds + tmdbId else current.movieWatchedTmdbIds,
                    // 标记已看后从想看缓存移除
                    movieWatchlistTraktIds = current.movieWatchlistTraktIds - traktId,
                    movieWatchlistTmdbIds = if (tmdbId > 0) current.movieWatchlistTmdbIds - tmdbId else current.movieWatchlistTmdbIds
                )
                MediaType.SHOW -> current.copy(
                    showWatchedTraktIds = current.showWatchedTraktIds + traktId,
                    showWatchedTmdbIds = if (tmdbId > 0) current.showWatchedTmdbIds + tmdbId else current.showWatchedTmdbIds,
                    showWatchlistTraktIds = current.showWatchlistTraktIds - traktId,
                    showWatchlistTmdbIds = if (tmdbId > 0) current.showWatchlistTmdbIds - tmdbId else current.showWatchlistTmdbIds
                )
                else -> current
            }
            persistWatchlistWatchedIds()
        }
    }

    /** 缓存已加载时，从已看缓存移除 ID */
    private fun removeFromWatchedCache(traktId: Int, tmdbId: Int, type: MediaType) {
        watchlistWatchedIds?.let { current ->
            watchlistWatchedIds = when (type) {
                MediaType.MOVIE -> current.copy(
                    movieWatchedTraktIds = current.movieWatchedTraktIds - traktId,
                    movieWatchedTmdbIds = if (tmdbId > 0) current.movieWatchedTmdbIds - tmdbId else current.movieWatchedTmdbIds,
                    // 取消已看后加回想看缓存
                    movieWatchlistTraktIds = current.movieWatchlistTraktIds + traktId,
                    movieWatchlistTmdbIds = if (tmdbId > 0) current.movieWatchlistTmdbIds + tmdbId else current.movieWatchlistTmdbIds
                )
                MediaType.SHOW -> current.copy(
                    showWatchedTraktIds = current.showWatchedTraktIds - traktId,
                    showWatchedTmdbIds = if (tmdbId > 0) current.showWatchedTmdbIds - tmdbId else current.showWatchedTmdbIds,
                    showWatchlistTraktIds = current.showWatchlistTraktIds + traktId,
                    showWatchlistTmdbIds = if (tmdbId > 0) current.showWatchlistTmdbIds + tmdbId else current.showWatchlistTmdbIds
                )
                else -> current
            }
            persistWatchlistWatchedIds()
        }
    }

    // 持久化缓存作用域
    private val persistentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistentDataStore get() = context.traktPersistentCacheStore

    // 持久化缓存（永久）：tmdb↔trakt ID 映射，跨 App 重启保留
    private val searchByTmdbCache = persistentTtlCache<List<TraktSearchResult>>(
        TTL_ID_MAPPING, 100, persistentDataStore, json, "tmdb_search", persistentScope
    )
    // imdbId → Trakt 反查缓存（永不过期，imdb↔trakt 映射不会变）
    private val searchByImdbCache = persistentTtlCache<List<TraktSearchResult>>(
        TTL_ID_MAPPING, 100, persistentDataStore, json, "imdb_search", persistentScope
    )
    // 想看/已看 ID 集合持久化缓存（6 小时）：跨 App 重启复用，避免每次启动都发 4 个 /sync/* 请求
    // 增删想看/已看时同步更新，退出登录时清除
    private val watchlistWatchedIdsCache = persistentTtlCache<WatchlistWatchedIds>(
        TTL_WATCHLIST_IDS, 1, persistentDataStore, json, "watchlist_watched_ids", persistentScope
    )
    // 持久化缓存（6 小时）：发现页栏目数据，跨 App 重启保留，避免重启后重新请求
    private val recommendationsCache = persistentTtlCache<List<TraktMovie>>(
        TTL_RECOMMENDATIONS, 30, persistentDataStore, json, "recommendations", persistentScope
    )
    private val trendingMoviesCache = persistentTtlCache<Pair<List<TraktTrendingMovieResponse>, Int>>(
        TTL_TRENDING, 5, persistentDataStore, json, "trending_movies", persistentScope
    )
    private val trendingShowsCache = persistentTtlCache<Pair<List<TraktTrendingShowResponse>, Int>>(
        TTL_TRENDING, 5, persistentDataStore, json, "trending_shows", persistentScope
    )
    private val anticipatedMoviesCache = persistentTtlCache<Pair<List<TraktAnticipatedMovieResponse>, Int>>(
        TTL_TRENDING, 5, persistentDataStore, json, "anticipated_movies", persistentScope
    )
    private val anticipatedShowsCache = persistentTtlCache<Pair<List<TraktAnticipatedShowResponse>, Int>>(
        TTL_TRENDING, 5, persistentDataStore, json, "anticipated_shows", persistentScope
    )
    private val showRecommendationsCache = persistentTtlCache<List<TraktRecommendationShowResponse>>(
        TTL_TRENDING, 5, persistentDataStore, json, "show_recommendations", persistentScope
    )
    private val trendingListsCache = persistentTtlCache<List<TraktTrendingListResponse>>(
        TTL_TRENDING, 5, persistentDataStore, json, "trending_lists", persistentScope
    )

    /** 持久化缓存列表，供 Application 启动时批量加载 */
    val persistentCaches: List<PersistentTtlCache<*>> get() = listOf(
        searchByTmdbCache, searchByImdbCache, watchlistWatchedIdsCache, recommendationsCache,
        trendingMoviesCache, trendingShowsCache, anticipatedMoviesCache,
        anticipatedShowsCache, showRecommendationsCache, trendingListsCache
    )

    /** ID 映射持久化缓存（tmdb↔trakt、imdb↔trakt），用于设置页按类目清除 */
    val idMappingCaches: List<PersistentTtlCache<*>> get() = listOf(searchByTmdbCache, searchByImdbCache)

    /** 影视数据持久化缓存（想看/已看 ID + 趋势/推荐/列表等 6 小时缓存），用于设置页按类目清除 */
    val mediaDataCaches: List<PersistentTtlCache<*>> get() = listOf(
        watchlistWatchedIdsCache, recommendationsCache, trendingMoviesCache, trendingShowsCache,
        anticipatedMoviesCache, anticipatedShowsCache, showRecommendationsCache, trendingListsCache
    )

    /**
     * 导出 IMDb→Trakt 映射的全部条目（用于跨设备云端同步上传）。
     * key 格式: "{imdbId}_{MOVIE|SHOW}"，value: 对应的 TraktSearchResult 列表。
     */
    suspend fun snapshotImdbMappings(): Map<String, List<TraktSearchResult>> =
        searchByImdbCache.snapshotFromDisk()

    /**
     * 批量合并 IMDb→Trakt 映射到本地缓存（不覆盖本地已有，本地新数据优先）。
     * 用于从云端拉取后写入本地。
     * @return 实际写入的条目数
     */
    suspend fun mergeImdbMappings(mappings: Map<String, List<TraktSearchResult>>): Int =
        searchByImdbCache.putAll(mappings, overwrite = false)

    // 内存缓存（短期，App 进程内有效）
    private val commentsCache = TtlCache<List<TraktComment>>(TTL_COMMENTS, maxSize = 50)
    private val relatedMoviesCache = TtlCache<List<TraktMovie>>(TTL_RELATED, maxSize = 50)
    private val relatedShowsCache = TtlCache<List<TraktShow>>(TTL_RELATED, maxSize = 50)
    // 统计页专用缓存：频繁进出页面时避免重复全量拉取
    private val movieHistoryCache = TtlCache<List<TraktWatchlistMovieItem>>(TTL_STATS, maxSize = 5)
    private val showHistoryCache = TtlCache<List<TraktWatchlistShowItem>>(TTL_STATS, maxSize = 5)
    private val watchedShowsCache = TtlCache<List<TraktWatchedShow>>(TTL_STATS, maxSize = 5)
    private val userRatingsCache = TtlCache<List<TraktRatingItem>>(TTL_STATS, maxSize = 5)
    private val userStatsCache = TtlCache<TraktUserStatsResponse>(TTL_STATS, maxSize = 5)
    // Watchlist 首页缓存：splash 预取的结果供 MainScreen 复用，避免重复请求
    private val movieWatchlistCache = TtlCache<Pair<List<TraktWatchlistMovieItem>, Int>>(TTL_STATS, maxSize = 5)
    private val showWatchlistCache = TtlCache<Pair<List<TraktWatchlistShowItem>, Int>>(TTL_STATS, maxSize = 5)

    /** 已搜索但未找到有效 Trakt ID 的 tmdbId 集合（负缓存，避免重复请求和转圈） */
    private val notFoundTmdbIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 已搜索但未找到有效 Trakt ID 的 imdbId 集合（负缓存，避免重复请求和转圈） */
    private val notFoundImdbIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 同步查询 ID 转换缓存（不触发网络请求），用于秒进判断。扫描所有结果找有效 ID */
    fun getCachedTraktId(tmdbId: Int, type: MediaType): Int? {
        val key = "${tmdbId}_${type.name}"
        // 负缓存命中：之前搜过没找到，直接返回 0（表示已搜过但无有效 ID，调用方据此跳过转圈）
        if (notFoundTmdbIds.contains(key)) return 0
        val cached = searchByTmdbCache.get(key) ?: return null
        // 扫描所有结果找有效 trakt ID（第一个结果可能 trakt <= 0）
        for (result in cached) {
            val traktId = when (type) {
                MediaType.MOVIE -> result.movie?.ids?.trakt
                MediaType.SHOW -> result.show?.ids?.trakt
                else -> null
            }
            if (traktId != null && traktId > 0) return traktId
        }
        // 缓存有数据但无有效 ID → 记入负缓存
        notFoundTmdbIds.add(key)
        return 0
    }

    /** 同步查询 imdbId → traktId 缓存（不触发网络请求）。返回值约定同 getCachedTraktId：null=未查过，0=已查无有效ID，正数=有效traktId */
    fun getCachedTraktIdByImdb(imdbId: String, type: MediaType): Int? {
        val key = "${imdbId}_${type.name}"
        if (notFoundImdbIds.contains(key)) return 0
        val cached = searchByImdbCache.get(key) ?: return null
        for (result in cached) {
            val traktId = when (type) {
                MediaType.MOVIE -> result.movie?.ids?.trakt
                MediaType.SHOW -> result.show?.ids?.trakt
                else -> null
            }
            if (traktId != null && traktId > 0) return traktId
        }
        notFoundImdbIds.add(key)
        return 0
    }

    suspend fun searchByTmdb(tmdbId: Int, type: MediaType): Result<List<TraktSearchResult>> {
        val key = "${tmdbId}_${type.name}"
        searchByTmdbCache.get(key)?.let { return Result.success(it) }
        return try {
            val typeStr = when (type) {
                MediaType.MOVIE -> "movie"
                MediaType.SHOW -> "show"
                MediaType.PERSON -> "person"
                MediaType.DISK -> return Result.failure(Exception("DISK not supported"))
            }
            val response = traktApiService.searchByTmdb(tmdbId, typeStr)
            if (response.isSuccessful) {
                val body = response.body() ?: emptyList()
                searchByTmdbCache.put(key, body)
                // 如果无有效结果，记入负缓存
                val hasValid = body.any { r ->
                    val id = when (type) {
                        MediaType.MOVIE -> r.movie?.ids?.trakt
                        MediaType.SHOW -> r.show?.ids?.trakt
                        else -> null
                    }
                    id != null && id > 0
                }
                if (!hasValid) notFoundTmdbIds.add(key)
                Result.success(body)
            } else {
                Result.failure(Exception("Failed to search by tmdb: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 通过 imdbId 反查 Trakt 条目（用于豆瓣→Trakt 同步）。仅支持 movie/show */
    suspend fun searchByImdb(imdbId: String, type: MediaType): Result<List<TraktSearchResult>> {
        val key = "${imdbId}_${type.name}"
        searchByImdbCache.get(key)?.let { return Result.success(it) }
        return try {
            val typeStr = when (type) {
                MediaType.MOVIE -> "movie"
                MediaType.SHOW -> "show"
                else -> return Result.failure(IllegalArgumentException("Unsupported type for imdb search: $type"))
            }
            val response = traktApiService.searchByImdb(imdbId, typeStr)
            if (response.isSuccessful) {
                val body = response.body() ?: emptyList()
                searchByImdbCache.put(key, body)
                // 如果无有效结果，记入负缓存
                val hasValid = body.any { r ->
                    val id = when (type) {
                        MediaType.MOVIE -> r.movie?.ids?.trakt
                        MediaType.SHOW -> r.show?.ids?.trakt
                        else -> null
                    }
                    id != null && id > 0
                }
                if (!hasValid) notFoundImdbIds.add(key)
                Result.success(body)
            } else {
                Result.failure(Exception("Failed to search by imdb: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMovieWatchlist(page: Int = 1, limit: Int = 50, forceRefresh: Boolean = false): Result<Pair<List<TraktWatchlistMovieItem>, Int>> {
        val cacheKey = "p${page}_$limit"
        return runCatching {
            movieWatchlistCache.getOrAwait(cacheKey, skipCache = forceRefresh) {
                val response = traktApiService.getWatchlist(
                    type = "movies",
                    extended = "full",
                    page = page,
                    limit = limit
                )
                if (response.isSuccessful) {
                    val items = response.body() ?: emptyList()
                    val totalPages = response.headers()["X-Pagination-Page-Count"]?.toIntOrNull() ?: 1
                    Pair(items, totalPages)
                } else {
                    throw Exception("Failed to fetch movie watchlist: ${response.code()}")
                }
            }
        }
    }

    suspend fun getShowWatchlist(page: Int = 1, limit: Int = 50, forceRefresh: Boolean = false): Result<Pair<List<TraktWatchlistShowItem>, Int>> {
        val cacheKey = "p${page}_$limit"
        return runCatching {
            showWatchlistCache.getOrAwait(cacheKey, skipCache = forceRefresh) {
                val response = traktApiService.getShowWatchlist(
                    type = "shows",
                    extended = "full",
                    page = page,
                    limit = limit
                )
                if (response.isSuccessful) {
                    val items = response.body() ?: emptyList()
                    val totalPages = response.headers()["X-Pagination-Page-Count"]?.toIntOrNull() ?: 1
                    Pair(items, totalPages)
                } else {
                    throw Exception("Failed to fetch show watchlist: ${response.code()}")
                }
            }
        }
    }

    suspend fun getMovieHistory(page: Int = 1, limit: Int = 200, extended: String = "full"): Result<Pair<List<TraktWatchlistMovieItem>, Int>> {
        return try {
            val response = traktApiService.getMovieHistory(page = page, limit = limit, extended = extended)
            if (response.isSuccessful) {
                val items = response.body() ?: emptyList()
                val totalPages = response.headers()["X-Pagination-Page-Count"]?.toIntOrNull() ?: 1
                Result.success(Pair(items, totalPages))
            } else {
                Result.failure(Exception("Failed to fetch movie history: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getShowHistory(page: Int = 1, limit: Int = 200, extended: String = "full"): Result<Pair<List<TraktWatchlistShowItem>, Int>> {
        return try {
            val response = traktApiService.getShowHistory(page = page, limit = limit, extended = extended)
            if (response.isSuccessful) {
                val items = response.body() ?: emptyList()
                val totalPages = response.headers()["X-Pagination-Page-Count"]?.toIntOrNull() ?: 1
                Result.success(Pair(items, totalPages))
            } else {
                Result.failure(Exception("Failed to fetch show history: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 获取全部电影观看历史（跨页拉取），用于统计。带 5 分钟 TTL 缓存 */
    suspend fun getAllMovieHistory(extended: String = "full"): Result<List<TraktWatchlistMovieItem>> {
        val cacheKey = "all_$extended"
        movieHistoryCache.get(cacheKey)?.let { return Result.success(it) }
        val allItems = mutableListOf<TraktWatchlistMovieItem>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            val result = getMovieHistory(page = page, limit = 200, extended = extended)
            result.onSuccess { (items, tp) ->
                allItems.addAll(items)
                totalPages = tp
            }.onFailure { e ->
                return Result.failure(e)
            }
            page++
        }
        movieHistoryCache.put(cacheKey, allItems)
        return Result.success(allItems)
    }

    /** 获取全部电视剧观看历史（跨页拉取），用于统计。带 5 分钟 TTL 缓存 */
    suspend fun getAllShowHistory(extended: String = "min"): Result<List<TraktWatchlistShowItem>> {
        val cacheKey = "all_$extended"
        showHistoryCache.get(cacheKey)?.let { return Result.success(it) }
        val allItems = mutableListOf<TraktWatchlistShowItem>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            val result = getShowHistory(page = page, limit = 200, extended = extended)
            result.onSuccess { (items, tp) ->
                allItems.addAll(items)
                totalPages = tp
            }.onFailure { e ->
                return Result.failure(e)
            }
            page++
        }
        showHistoryCache.put(cacheKey, allItems)
        return Result.success(allItems)
    }

    /** 获取已看电视剧列表（含每部剧的已看集数），用于统计。带 5 分钟 TTL 缓存 */
    suspend fun getWatchedShowsWithEpisodes(): Result<List<TraktWatchedShow>> {
        watchedShowsCache.get("all")?.let { return Result.success(it) }
        return try {
            val response = traktApiService.getWatchedShows()
            if (response.isSuccessful) {
                val shows = response.body() ?: emptyList()
                watchedShowsCache.put("all", shows)
                Result.success(shows)
            } else {
                Result.failure(Exception("Failed to get watched shows: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 获取全部电影想看列表（跨页拉取），用于通知检查 */
    suspend fun getAllMovieWatchlist(): Result<List<TraktWatchlistMovieItem>> {
        val allItems = mutableListOf<TraktWatchlistMovieItem>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            val result = getMovieWatchlist(page = page, limit = 200)
            result.onSuccess { (items, tp) ->
                allItems.addAll(items)
                totalPages = tp
            }.onFailure { e ->
                return Result.failure(e)
            }
            page++
        }
        return Result.success(allItems)
    }

    /** 获取全部电视剧想看列表（跨页拉取），用于通知检查 */
    suspend fun getAllShowWatchlist(): Result<List<TraktWatchlistShowItem>> {
        val allItems = mutableListOf<TraktWatchlistShowItem>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            val result = getShowWatchlist(page = page, limit = 200)
            result.onSuccess { (items, tp) ->
                allItems.addAll(items)
                totalPages = tp
            }.onFailure { e ->
                return Result.failure(e)
            }
            page++
        }
        return Result.success(allItems)
    }

    suspend fun getComments(traktId: Int, type: MediaType, limit: Int = 5, page: Int = 1): Result<List<TraktComment>> {
        val key = "${traktId}_${type.name}_${limit}_$page"
        commentsCache.get(key)?.let { return Result.success(it) }
        return try {
            val response = when (type) {
                MediaType.MOVIE -> traktApiService.getMovieComments(traktId.toString(), limit, page)
                MediaType.SHOW -> traktApiService.getShowComments(traktId.toString(), limit, page)
                MediaType.PERSON -> traktApiService.getMovieComments(traktId.toString(), limit, page) // PERSON fallback
                MediaType.DISK -> return Result.failure(Exception("DISK not supported"))
            }
            if (response.isSuccessful) {
                val body = response.body() ?: emptyList()
                commentsCache.put(key, body)
                Result.success(body)
            } else {
                Result.failure(Exception("Failed to fetch comments: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** POST /comments 添加短评，成功返回 201 */
    suspend fun postComment(traktId: Int, type: MediaType, comment: String, spoiler: Boolean = false): Result<TraktComment> {
        return try {
            val typeStr = when (type) {
                MediaType.MOVIE -> "movie"
                MediaType.SHOW -> "show"
                else -> return Result.failure(Exception("Unsupported type for comment: $type"))
            }
            val request = TraktCommentRequest(
                item = TraktCommentItem(
                    type = typeStr,
                    ids = TraktCommentItemId(trakt = traktId)
                ),
                comment = comment,
                spoiler = spoiler
            )
            val response = traktApiService.postComment(request)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) Result.success(body) else Result.failure(Exception("Empty response body"))
            } else {
                Result.failure(Exception("Failed to post comment: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getShowSeasons(traktId: Int): Result<List<TraktSeason>> {
        return try {
            val response = traktApiService.getShowSeasons(traktId.toString())
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("Failed to fetch seasons: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSeasonEpisodes(showTraktId: Int, seasonNumber: Int): Result<List<TraktEpisode>> {
        return try {
            val response = traktApiService.getSeasonEpisodes(showTraktId, seasonNumber)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("Failed to fetch episodes: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 获取电视剧的观看进度（已看剧集列表） */
    suspend fun getShowWatchedProgress(showTraktId: Int): Result<TraktShowProgress> {
        return try {
            val response = traktApiService.getShowWatchedProgress(showTraktId)
            if (response.isSuccessful) {
                Result.success(response.body() ?: TraktShowProgress())
            } else {
                Result.failure(Exception("Failed to fetch watched progress: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 标记某集为已看 */
    suspend fun markEpisodeWatched(episodeTraktId: Int): Result<Unit> {
        return try {
            val request = TraktSyncRequest(
                episodes = listOf(TraktSyncItem(TraktIds(trakt = episodeTraktId)))
            )
            val response = traktApiService.addToHistory(request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to mark episode: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 批量标记多集为已看 */
    suspend fun markEpisodesWatched(episodeTraktIds: List<Int>): Result<Unit> {
        if (episodeTraktIds.isEmpty()) return Result.success(Unit)
        return try {
            val request = TraktSyncRequest(
                episodes = episodeTraktIds.map { TraktSyncItem(TraktIds(trakt = it)) }
            )
            val response = traktApiService.addToHistory(request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to mark episodes: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 取消某集已看标记 */
    suspend fun unmarkEpisodeWatched(episodeTraktId: Int): Result<Unit> {
        return try {
            val request = TraktSyncRequest(
                episodes = listOf(TraktSyncItem(TraktIds(trakt = episodeTraktId)))
            )
            val response = traktApiService.removeFromHistory(request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to unmark episode watched: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markAsWatched(traktId: Int, type: MediaType, tmdbId: Int = 0): Result<TraktSyncResponse> {
        return try {
            val ids = TraktIds(trakt = traktId)
            val request = when (type) {
                MediaType.MOVIE -> TraktSyncRequest(movies = listOf(TraktSyncItem(ids)))
                MediaType.SHOW -> TraktSyncRequest(shows = listOf(TraktSyncItem(ids)))
                MediaType.PERSON -> TraktSyncRequest(movies = listOf(TraktSyncItem(ids))) // fallback
                MediaType.DISK -> return Result.failure(Exception("DISK not supported"))
            }
            val response = traktApiService.addToHistory(request)
            if (response.isSuccessful) {
                addToWatchedCache(traktId, tmdbId, type)
                // 副操作：从想看列表移除。失败时仅记录日志，不影响主操作的成功状态（已看标记已生效）
                try {
                    val removeResp = traktApiService.removeFromWatchlist(request)
                    if (!removeResp.isSuccessful) {
                        Log.w("TraktRepository", "markAsWatched 副操作 removeFromWatchlist 失败: ${removeResp.code()}, traktId=$traktId")
                    }
                } catch (e: Exception) {
                    Log.w("TraktRepository", "markAsWatched 副操作 removeFromWatchlist 异常: ${e.message}, traktId=$traktId")
                }
                Result.success(response.body() ?: TraktSyncResponse())
            } else {
                Result.failure(Exception("Failed to mark as watched: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeWatched(traktId: Int, type: MediaType, tmdbId: Int = 0): Result<TraktSyncResponse> {
        return try {
            val ids = TraktIds(trakt = traktId)
            val request = when (type) {
                MediaType.MOVIE -> TraktSyncRequest(movies = listOf(TraktSyncItem(ids)))
                MediaType.SHOW -> TraktSyncRequest(shows = listOf(TraktSyncItem(ids)))
                MediaType.PERSON -> TraktSyncRequest(movies = listOf(TraktSyncItem(ids))) // fallback
                MediaType.DISK -> return Result.failure(Exception("DISK not supported"))
            }
            val response = traktApiService.removeFromHistory(request)
            if (response.isSuccessful) {
                removeFromWatchedCache(traktId, tmdbId, type)
                // 副操作：加回想看列表。失败时仅记录日志，不影响主操作的成功状态（已看取消已生效）
                try {
                    val addResp = traktApiService.addToWatchlist(request)
                    if (!addResp.isSuccessful) {
                        Log.w("TraktRepository", "removeWatched 副操作 addToWatchlist 失败: ${addResp.code()}, traktId=$traktId")
                    }
                } catch (e: Exception) {
                    Log.w("TraktRepository", "removeWatched 副操作 addToWatchlist 异常: ${e.message}, traktId=$traktId")
                }
                Result.success(response.body() ?: TraktSyncResponse())
            } else {
                Result.failure(Exception("Failed to remove watched: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRelatedMovies(traktId: Int, limit: Int = 10): Result<List<TraktMovie>> {
        val key = "${traktId}_${limit}"
        relatedMoviesCache.get(key)?.let { return Result.success(it) }
        return try {
            val response = traktApiService.getRelatedMovies(traktId, limit = limit)
            if (response.isSuccessful) {
                val body = response.body() ?: emptyList()
                relatedMoviesCache.put(key, body)
                Result.success(body)
            } else {
                Result.failure(Exception("Failed to fetch related movies: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRelatedShows(traktId: Int, limit: Int = 10): Result<List<TraktShow>> {
        val key = "${traktId}_${limit}"
        relatedShowsCache.get(key)?.let { return Result.success(it) }
        return try {
            val response = traktApiService.getRelatedShows(traktId, limit = limit)
            if (response.isSuccessful) {
                val body = response.body() ?: emptyList()
                relatedShowsCache.put(key, body)
                Result.success(body)
            } else {
                Result.failure(Exception("Failed to fetch related shows: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 检查某个影视是否在想看列表中 */
    suspend fun checkInWatchlist(traktId: Int, type: MediaType): Boolean {
        return try {
            when (type) {
                MediaType.MOVIE -> {
                    val response = traktApiService.getWatchlist(
                        type = "movies", extended = "full", page = 1, limit = 200
                    )
                    if (response.isSuccessful) {
                        response.body()?.any { it.movie.ids.trakt == traktId } ?: false
                    } else false
                }
                MediaType.SHOW -> {
                    val response = traktApiService.getShowWatchlist(
                        type = "shows", extended = "full", page = 1, limit = 200
                    )
                    if (response.isSuccessful) {
                        response.body()?.any { it.show.ids.trakt == traktId } ?: false
                    } else false
                }
                MediaType.PERSON -> false
                MediaType.DISK -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 检查某个影视是否已在已看历史中 */
    suspend fun checkWatched(traktId: Int, type: MediaType): Boolean {
        return try {
            when (type) {
                MediaType.MOVIE -> {
                    val response = traktApiService.getMovieHistory()
                    if (response.isSuccessful) {
                        response.body()?.any { it.movie.ids.trakt == traktId } ?: false
                    } else false
                }
                MediaType.SHOW -> {
                    val response = traktApiService.getShowHistory()
                    if (response.isSuccessful) {
                        response.body()?.any { it.show.ids.trakt == traktId } ?: false
                    } else false
                }
                MediaType.PERSON -> false
                MediaType.DISK -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 批量获取推荐项的想看/已看状态，返回 traktId -> (inWatchlist, watched) 映射 */
    suspend fun batchCheckStatus(traktIds: List<Int>, type: MediaType): Map<Int, Pair<Boolean, Boolean>> {
        if (traktIds.isEmpty()) return emptyMap()
        return try {
            val watchlistIds = mutableSetOf<Int>()
            val watchedIds = mutableSetOf<Int>()

            when (type) {
                MediaType.MOVIE -> {
                    val watchlistResp = traktApiService.getWatchlist(
                        type = "movies", extended = "full", page = 1, limit = 200
                    )
                    if (watchlistResp.isSuccessful) {
                        watchlistResp.body()?.forEach { watchlistIds.add(it.movie.ids.trakt) }
                    }
                    val historyResp = traktApiService.getMovieHistory()
                    if (historyResp.isSuccessful) {
                        historyResp.body()?.forEach { watchedIds.add(it.movie.ids.trakt) }
                    }
                }
                MediaType.SHOW -> {
                    val watchlistResp = traktApiService.getShowWatchlist(
                        type = "shows", extended = "full", page = 1, limit = 200
                    )
                    if (watchlistResp.isSuccessful) {
                        watchlistResp.body()?.forEach { watchlistIds.add(it.show.ids.trakt) }
                    }
                    val historyResp = traktApiService.getShowHistory()
                    if (historyResp.isSuccessful) {
                        historyResp.body()?.forEach { watchedIds.add(it.show.ids.trakt) }
                    }
                }
                MediaType.PERSON -> { /* PERSON not applicable */ }
                MediaType.DISK -> {} 
            }

            traktIds.associateWith { id ->
                Pair(watchlistIds.contains(id), watchedIds.contains(id))
            }
        } catch (e: Exception) {
            traktIds.associateWith { Pair(false, false) }
        }
    }

    /** 添加到想看列表 */
    suspend fun addToWatchlist(traktId: Int, type: MediaType, tmdbId: Int = 0): Result<TraktSyncResponse> {
        return try {
            val ids = TraktIds(trakt = traktId)
            val request = when (type) {
                MediaType.MOVIE -> TraktSyncRequest(movies = listOf(TraktSyncItem(ids)))
                MediaType.SHOW -> TraktSyncRequest(shows = listOf(TraktSyncItem(ids)))
                MediaType.PERSON -> TraktSyncRequest(movies = listOf(TraktSyncItem(ids))) // fallback
                MediaType.DISK -> return Result.failure(Exception("DISK not supported"))
            }
            val response = traktApiService.addToWatchlist(request)
            if (response.isSuccessful) {
                addToWatchlistCache(traktId, tmdbId, type)
                Result.success(response.body() ?: TraktSyncResponse())
            } else {
                Result.failure(Exception("Failed to add to watchlist: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 从想看列表移除 */
    suspend fun removeFromWatchlist(traktId: Int, type: MediaType, tmdbId: Int = 0): Result<TraktSyncResponse> {
        return try {
            val ids = TraktIds(trakt = traktId)
            val request = when (type) {
                MediaType.MOVIE -> TraktSyncRequest(movies = listOf(TraktSyncItem(ids)))
                MediaType.SHOW -> TraktSyncRequest(shows = listOf(TraktSyncItem(ids)))
                MediaType.PERSON -> TraktSyncRequest(movies = listOf(TraktSyncItem(ids))) // fallback
                MediaType.DISK -> return Result.failure(Exception("DISK not supported"))
            }
            val response = traktApiService.removeFromWatchlist(request)
            if (response.isSuccessful) {
                removeFromWatchlistCache(traktId, tmdbId, type)
                Result.success(response.body() ?: TraktSyncResponse())
            } else {
                Result.failure(Exception("Failed to remove from watchlist: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 添加评分（1-10 分） */
    suspend fun addRating(traktId: Int, rating: Int, type: MediaType): Result<Unit> {
        return try {
            val ids = TraktIds(trakt = traktId)
            val item = RatingItem(ids = ids, rating = rating)
            val request = when (type) {
                MediaType.MOVIE -> RatingRequest(movies = listOf(item))
                MediaType.SHOW -> RatingRequest(shows = listOf(item))
                MediaType.PERSON -> RatingRequest(movies = listOf(item)) // fallback
                MediaType.DISK -> return Result.failure(Exception("DISK not supported"))
            }
            val response = traktApiService.addRating(request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to add rating: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 批量同步操作通用模板（想看/已看的增删）。
     * @param movieTraktIds 电影 traktId 列表
     * @param showTraktIds 剧集 traktId 列表
     * @param apiCall Trakt 同步 API 调用
     * @param cacheUpdate 成功后缓存更新函数（traktId, tmdbId, type）
     * @param errorLabel 错误消息中的方法名
     */
    private suspend fun batchSync(
        movieTraktIds: List<Int>,
        showTraktIds: List<Int>,
        apiCall: suspend (TraktSyncRequest) -> Response<TraktSyncResponse>,
        cacheUpdate: (Int, Int, MediaType) -> Unit,
        errorLabel: String,
        // 仅 addToHistory 时传递,Map<traktId, watchedAtIso>;空 Map 或 null 表示不传 watched_at
        watchedAtByTraktId: Map<Int, String>? = null
    ): Result<TraktSyncResponse> {
        if (movieTraktIds.isEmpty() && showTraktIds.isEmpty()) return Result.success(TraktSyncResponse())
        return try {
            val request = TraktSyncRequest(
                movies = movieTraktIds.takeIf { it.isNotEmpty() }?.map {
                    TraktSyncItem(TraktIds(trakt = it), watched_at = watchedAtByTraktId?.get(it))
                },
                shows = showTraktIds.takeIf { it.isNotEmpty() }?.map {
                    TraktSyncItem(TraktIds(trakt = it), watched_at = watchedAtByTraktId?.get(it))
                }
            )
            val response = apiCall(request)
            if (response.isSuccessful) {
                movieTraktIds.forEach { cacheUpdate(it, 0, MediaType.MOVIE) }
                showTraktIds.forEach { cacheUpdate(it, 0, MediaType.SHOW) }
                Result.success(response.body() ?: TraktSyncResponse())
            } else {
                Result.failure(Exception("$errorLabel failed: ${response.code()}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 批量添加到想看列表（Trakt API 原生支持批量，一次 POST 传多个 ids）。 */
    suspend fun batchAddToWatchlist(movieTraktIds: List<Int>, showTraktIds: List<Int>) =
        batchSync(movieTraktIds, showTraktIds, traktApiService::addToWatchlist, ::addToWatchlistCache, "batchAddToWatchlist")

    /** 批量从想看列表移除。 */
    suspend fun batchRemoveFromWatchlist(movieTraktIds: List<Int>, showTraktIds: List<Int>) =
        batchSync(movieTraktIds, showTraktIds, traktApiService::removeFromWatchlist, ::removeFromWatchlistCache, "batchRemoveFromWatchlist")

    /** 批量标记已看。注意：Trakt 的 addToHistory 不会自动从 watchlist 移除，需调用方显式调用 batchRemoveFromWatchlist。 */
    suspend fun batchMarkAsWatched(movieTraktIds: List<Int>, showTraktIds: List<Int>) =
        batchSync(movieTraktIds, showTraktIds, traktApiService::addToHistory, ::addToWatchedCache, "batchMarkAsWatched")

    /**
     * 批量标记已看(带观看时间)。
     * @param movieItems 电影 (traktId, watchedAtIso) 列表,watchedAtIso 为 ISO 8601 UTC 字符串
     * @param showItems 剧集 (traktId, watchedAtIso) 列表
     */
    suspend fun batchMarkAsWatchedAt(
        movieItems: List<Pair<Int, String?>>,
        showItems: List<Pair<Int, String?>>
    ): Result<TraktSyncResponse> {
        val watchedAtMap = buildMap<Int, String> {
            movieItems.forEach { (id, ts) -> ts?.let { put(id, it) } }
            showItems.forEach { (id, ts) -> ts?.let { put(id, it) } }
        }
        return batchSync(
            movieTraktIds = movieItems.map { it.first },
            showTraktIds = showItems.map { it.first },
            apiCall = traktApiService::addToHistory,
            cacheUpdate = ::addToWatchedCache,
            errorLabel = "batchMarkAsWatchedAt",
            watchedAtByTraktId = watchedAtMap.takeIf { it.isNotEmpty() }
        )
    }

    /** 批量移除已看记录。注意:Trakt 的 removeFromHistory 不会自动加回 watchlist,如需加回需调用方显式调用 batchAddToWatchlist。 */
    suspend fun batchRemoveFromWatched(movieTraktIds: List<Int>, showTraktIds: List<Int>) =
        batchSync(movieTraktIds, showTraktIds, traktApiService::removeFromHistory, ::removeFromWatchedCache, "batchRemoveFromWatched")

    /**
     * 批量添加评分（1-10 分）。
     * @param movieRatings 电影 (traktId, rating) 列表
     * @param showRatings 剧集 (traktId, rating) 列表
     */
    suspend fun batchAddRatings(
        movieRatings: List<Pair<Int, Int>>,
        showRatings: List<Pair<Int, Int>>
    ): Result<Unit> {
        if (movieRatings.isEmpty() && showRatings.isEmpty()) return Result.success(Unit)
        return try {
            val request = RatingRequest(
                movies = movieRatings.takeIf { it.isNotEmpty() }?.map { (id, r) -> RatingItem(TraktIds(trakt = id), r) },
                shows = showRatings.takeIf { it.isNotEmpty() }?.map { (id, r) -> RatingItem(TraktIds(trakt = id), r) }
            )
            val response = traktApiService.addRating(request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("batchAddRatings failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 批量添加评分(带评分时间)。
     * @param movieRatings 电影 (traktId, rating, ratedAtIso) 列表
     * @param showRatings 剧集 (traktId, rating, ratedAtIso) 列表
     */
    suspend fun batchAddRatingsAt(
        movieRatings: List<Triple<Int, Int, String?>>,
        showRatings: List<Triple<Int, Int, String?>>
    ): Result<Unit> {
        if (movieRatings.isEmpty() && showRatings.isEmpty()) return Result.success(Unit)
        return try {
            val request = RatingRequest(
                movies = movieRatings.takeIf { it.isNotEmpty() }?.map { (id, r, ts) ->
                    RatingItem(TraktIds(trakt = id), r, ts)
                },
                shows = showRatings.takeIf { it.isNotEmpty() }?.map { (id, r, ts) ->
                    RatingItem(TraktIds(trakt = id), r, ts)
                }
            )
            val response = traktApiService.addRating(request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("batchAddRatingsAt failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 移除评分 */
    suspend fun removeRating(traktId: Int, type: MediaType): Result<Unit> {
        return try {
            val ids = TraktIds(trakt = traktId)
            val item = RatingItem(ids = ids)
            val request = when (type) {
                MediaType.MOVIE -> RatingRequest(movies = listOf(item))
                MediaType.SHOW -> RatingRequest(shows = listOf(item))
                MediaType.PERSON -> RatingRequest(movies = listOf(item)) // fallback
                MediaType.DISK -> return Result.failure(Exception("DISK not supported"))
            }
            val response = traktApiService.removeRating(request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to remove rating: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 获取用户对某影视的评分，未评分返回 null */
    suspend fun getUserRating(traktId: Int, type: MediaType): Int? {
        return try {
            val typeStr = when (type) {
                MediaType.MOVIE -> "movies"
                MediaType.SHOW -> "shows"
                MediaType.PERSON -> "movies" // fallback
                MediaType.DISK -> return null
            }
            val response = traktApiService.getRatings(typeStr)
            if (response.isSuccessful) {
                val ratings = response.body() ?: emptyList()
                ratings.find { item ->
                    when (type) {
                        MediaType.MOVIE -> item.movie?.ids?.trakt == traktId
                        MediaType.SHOW -> item.show?.ids?.trakt == traktId
                        MediaType.PERSON -> item.movie?.ids?.trakt == traktId // fallback
                        MediaType.DISK -> false
                    }
                }?.rating
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /** 分页搜索通用模板（电影/电视剧/人物搜索结构一致，仅 API 调用不同） */
    private suspend fun <T> searchPaginated(
        apiCall: suspend (String, Int, Int) -> Response<List<T>>,
        query: String,
        page: Int,
        limit: Int,
        errorLabel: String
    ): Result<Pair<List<T>, Int>> {
        return try {
            val response = apiCall(query, limit, page)
            if (response.isSuccessful) {
                val items = response.body() ?: emptyList()
                val totalCount = response.headers()["X-Pagination-Item-Count"]?.toIntOrNull() ?: items.size
                Result.success(Pair(items, totalCount))
            } else {
                Result.failure(Exception("Failed to $errorLabel: ${response.code()}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Trakt 文本搜索（电影） */
    suspend fun searchMovies(query: String, page: Int = 1, limit: Int = 20) =
        searchPaginated(traktApiService::searchMovies, query, page, limit, "search movies")

    /** Trakt 文本搜索（电视剧） */
    suspend fun searchShows(query: String, page: Int = 1, limit: Int = 20) =
        searchPaginated(traktApiService::searchShows, query, page, limit, "search shows")

    /** 个性化推荐（已登录用户） */
    suspend fun getRecommendations(limit: Int = 10): Result<List<TraktMovie>> {
        val key = "recommendations_${limit}"
        recommendationsCache.get(key)?.let { return Result.success(it) }
        return try {
            val response = traktApiService.getMovieRecommendations(limit = limit)
            if (response.isSuccessful) {
                val body = response.body() ?: emptyList()
                recommendationsCache.put(key, body)
                Result.success(body)
            } else {
                Result.failure(Exception("Failed to fetch recommendations: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 搜索人物（通过名字），返回搜索结果列表 */
    suspend fun searchPeople(query: String, page: Int = 1, limit: Int = 20) =
        searchPaginated(traktApiService::searchPeople, query, page, limit, "search people")

    /** 获取人物详情（通过 slug 或 id） */
    suspend fun getPersonSummary(personSlug: String): Result<TraktPersonDetail> {
        return try {
            val response = traktApiService.getPersonSummary(personSlug)
            if (response.isSuccessful) {
                Result.success(response.body() ?: TraktPersonDetail())
            } else {
                Result.failure(Exception("HTTP ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTrendingMovies(page: Int = 1, limit: Int = 10): Result<Pair<List<TraktTrendingMovieResponse>, Int>> {
        val key = "${page}_${limit}"
        trendingMoviesCache.get(key)?.let { return Result.success(it) }
        return try {
            val response = traktApiService.getTrendingMovies(page = page, limit = limit)
            if (response.isSuccessful) {
                val totalCount = response.headers()["X-Pagination-Item-Count"]?.toIntOrNull() ?: 0
                val result = Pair(response.body() ?: emptyList(), totalCount)
                trendingMoviesCache.put(key, result)
                Result.success(result)
            } else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getTrendingShows(page: Int = 1, limit: Int = 10): Result<Pair<List<TraktTrendingShowResponse>, Int>> {
        val key = "${page}_${limit}"
        trendingShowsCache.get(key)?.let { return Result.success(it) }
        return try {
            val response = traktApiService.getTrendingShows(page = page, limit = limit)
            if (response.isSuccessful) {
                val totalCount = response.headers()["X-Pagination-Item-Count"]?.toIntOrNull() ?: 0
                val result = Pair(response.body() ?: emptyList(), totalCount)
                trendingShowsCache.put(key, result)
                Result.success(result)
            } else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getAnticipatedMovies(page: Int = 1, limit: Int = 10): Result<Pair<List<TraktAnticipatedMovieResponse>, Int>> {
        val key = "${page}_${limit}"
        anticipatedMoviesCache.get(key)?.let { return Result.success(it) }
        return try {
            val response = traktApiService.getAnticipatedMovies(page = page, limit = limit)
            if (response.isSuccessful) {
                val totalCount = response.headers()["X-Pagination-Item-Count"]?.toIntOrNull() ?: 0
                val result = Pair(response.body() ?: emptyList(), totalCount)
                anticipatedMoviesCache.put(key, result)
                Result.success(result)
            } else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getAnticipatedShows(page: Int = 1, limit: Int = 10): Result<Pair<List<TraktAnticipatedShowResponse>, Int>> {
        val key = "${page}_${limit}"
        anticipatedShowsCache.get(key)?.let { return Result.success(it) }
        return try {
            val response = traktApiService.getAnticipatedShows(page = page, limit = limit)
            if (response.isSuccessful) {
                val totalCount = response.headers()["X-Pagination-Item-Count"]?.toIntOrNull() ?: 0
                val result = Pair(response.body() ?: emptyList(), totalCount)
                anticipatedShowsCache.put(key, result)
                Result.success(result)
            } else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getShowRecommendations(limit: Int = 10): Result<List<TraktRecommendationShowResponse>> {
        val key = "${limit}"
        showRecommendationsCache.get(key)?.let { return Result.success(it) }
        return try {
            val response = traktApiService.getShowRecommendations(limit = limit)
            if (response.isSuccessful) {
                val result = response.body() ?: emptyList()
                showRecommendationsCache.put(key, result)
                Result.success(result)
            } else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 获取影视的 Trakt 视频列表 */
    suspend fun getVideos(traktId: String, mediaType: MediaType): Result<List<TraktVideo>> {
        return try {
            val response = when (mediaType) {
                MediaType.MOVIE -> traktApiService.getMovieVideos(traktId)
                MediaType.SHOW -> traktApiService.getShowVideos(traktId)
                MediaType.PERSON -> return Result.success(emptyList())
                MediaType.DISK -> return Result.success(emptyList())
            }
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 获取影视的 Trakt 图片（fanart等） */
    suspend fun getImages(traktId: String, mediaType: MediaType): Result<TraktImages> {
        return try {
            when (mediaType) {
                MediaType.MOVIE -> {
                    val response = traktApiService.getMovieWithImages(traktId)
                    if (response.isSuccessful) {
                        Result.success(response.body()?.images ?: TraktImages())
                    } else Result.failure(Exception("HTTP ${response.code()}"))
                }
                MediaType.SHOW -> {
                    val response = traktApiService.getShowWithImages(traktId)
                    if (response.isSuccessful) {
                        Result.success(response.body()?.images ?: TraktImages())
                    } else Result.failure(Exception("HTTP ${response.code()}"))
                }
                MediaType.PERSON -> Result.success(TraktImages())
                MediaType.DISK -> Result.success(TraktImages())
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 获取人物的 Trakt 图片 */
    suspend fun getPersonImages(personSlug: String): Result<TraktImages> {
        return try {
            val response = traktApiService.getPersonWithImages(personSlug)
            if (response.isSuccessful) {
                Result.success(response.body()?.images ?: TraktImages())
            } else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 获取人物电影参演 */
    suspend fun getPersonMovieCredits(personSlug: String): Result<TraktPersonCreditsResponse> {
        return try {
            val response = traktApiService.getPersonMovieCredits(personSlug)
            if (response.isSuccessful) {
                Result.success(response.body() ?: TraktPersonCreditsResponse())
            } else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 获取人物电视剧参演 */
    suspend fun getPersonShowCredits(personSlug: String): Result<TraktPersonCreditsResponse> {
        return try {
            val response = traktApiService.getPersonShowCredits(personSlug)
            if (response.isSuccessful) {
                Result.success(response.body() ?: TraktPersonCreditsResponse())
            } else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 获取人物别名 */
    suspend fun getPersonAliases(personSlug: String): Result<List<TraktPersonAlias>> {
        return try {
            val response = traktApiService.getPersonAliases(personSlug)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // 社区热门列表
    suspend fun getTrendingLists(limit: Int = 10, page: Int = 1): Result<List<TraktTrendingListResponse>> {
        val key = "${limit}_${page}"
        trendingListsCache.get(key)?.let { return Result.success(it) }
        return try {
            val response = traktApiService.getTrendingLists(limit, page)
            if (response.isSuccessful) {
                val result = response.body() ?: emptyList()
                trendingListsCache.put(key, result)
                Result.success(result)
            }
            else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // 列表详情条目
    suspend fun getListItems(
        listId: Int,
        limit: Int = 20,
        page: Int = 1
    ): Result<List<TraktListItemResponse>> {
        return try {
            val response = traktApiService.getListItems(listId, limit, page)
            if (response.isSuccessful) Result.success(response.body() ?: emptyList())
            else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // 用户统计。带 5 分钟 TTL 缓存
    // 用户资料缓存（获取一次后永久缓存，登出才清除）
    @Volatile
    private var userProfileCache: TraktUserProfileResponse? = null

    suspend fun getUserProfile(): Result<TraktUserProfileResponse> {
        // 1. 内存缓存
        userProfileCache?.let { return Result.success(it) }
        // 2. DataStore 持久化缓存（重启 app 后仍可用）
        val persisted = userProfileStorage.getProfile()
        if (persisted != null) {
            userProfileCache = persisted
            return Result.success(persisted)
        }
        // 3. 网络请求
        return try {
            val response = traktApiService.getUserProfile()
            if (response.isSuccessful) {
                val profile = response.body() ?: TraktUserProfileResponse()
                userProfileCache = profile
                userProfileStorage.saveProfile(profile)
                Result.success(profile)
            } else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun clearUserProfileCache() {
        userProfileCache = null
        userProfileStorage.clear()
    }

    suspend fun getUserStats(): Result<TraktUserStatsResponse> {
        userStatsCache.get("me")?.let { return Result.success(it) }
        return try {
            val response = traktApiService.getUserStats()
            if (response.isSuccessful) {
                val stats = response.body() ?: TraktUserStatsResponse()
                userStatsCache.put("me", stats)
                Result.success(stats)
            }
            else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // 全量电影评分
    suspend fun getAllMovieRatings(): Result<List<TraktRatingItem>> {
        return try {
            val response = traktApiService.getAllMovieRatings()
            if (response.isSuccessful) Result.success(response.body() ?: emptyList())
            else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // 全量剧集评分
    suspend fun getAllShowRatings(): Result<List<TraktRatingItem>> {
        return try {
            val response = traktApiService.getAllShowRatings()
            if (response.isSuccessful) Result.success(response.body() ?: emptyList())
            else Result.failure(Exception("HTTP ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // 合并全量评分（并行请求）。带 5 分钟 TTL 缓存
    suspend fun getAllUserRatings(): Result<List<TraktRatingItem>> {
        userRatingsCache.get("all")?.let { return Result.success(it) }
        return try {
            coroutineScope {
                val movieDeferred = async { getAllMovieRatings() }
                val showDeferred = async { getAllShowRatings() }
                val movieRatings = movieDeferred.await().getOrDefault(emptyList())
                val showRatings = showDeferred.await().getOrDefault(emptyList())
                val combined = movieRatings + showRatings
                userRatingsCache.put("all", combined)
                Result.success(combined)
            }
        } catch (e: Exception) { Result.failure(e) }
    }
}

enum class MediaType {
    MOVIE, SHOW, PERSON, DISK
}
