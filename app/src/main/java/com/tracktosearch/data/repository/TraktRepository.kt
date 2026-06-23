package com.tracktosearch.data.repository

import com.tracktosearch.data.remote.trakt.TraktApiService
import com.tracktosearch.data.remote.trakt.dto.*
import com.tracktosearch.data.util.TtlCache
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktRepository @Inject constructor(
    private val traktApiService: TraktApiService
) {
    companion object {
        private const val TTL_ID_MAPPING = 60 * 60 * 1000L   // ID 转换 1 小时
        private const val TTL_COMMENTS = 10 * 60 * 1000L     // 评论 10 分钟
        private const val TTL_RELATED = 30 * 60 * 1000L      // 相关推荐 30 分钟
        private const val TTL_RECOMMENDATIONS = 60 * 60 * 1000L // 个性化推荐 1 小时
    }

    // 带 TTL 的缓存
    private val searchByTmdbCache = TtlCache<List<TraktSearchResult>>(TTL_ID_MAPPING)
    private val commentsCache = TtlCache<List<TraktComment>>(TTL_COMMENTS)
    private val relatedMoviesCache = TtlCache<List<TraktMovie>>(TTL_RELATED)
    private val relatedShowsCache = TtlCache<List<TraktShow>>(TTL_RELATED)
    private val recommendationsCache = TtlCache<List<TraktMovie>>(TTL_RECOMMENDATIONS)

    suspend fun searchByTmdb(tmdbId: Int, type: MediaType): Result<List<TraktSearchResult>> {
        val key = "${tmdbId}_${type.name}"
        searchByTmdbCache.get(key)?.let { return Result.success(it) }
        return try {
            val typeStr = when (type) {
                MediaType.MOVIE -> "movie"
                MediaType.SHOW -> "show"
            }
            val response = traktApiService.searchByTmdb(tmdbId, typeStr)
            if (response.isSuccessful) {
                val body = response.body() ?: emptyList()
                searchByTmdbCache.put(key, body)
                Result.success(body)
            } else {
                Result.failure(Exception("Failed to search by tmdb: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    suspend fun getMovieWatchlist(page: Int = 1, limit: Int = 50): Result<Pair<List<TraktWatchlistMovieItem>, Int>> {
        return try {
            val response = traktApiService.getWatchlist(
                type = "movies",
                extended = "full",
                page = page,
                limit = limit
            )
            if (response.isSuccessful) {
                val items = response.body() ?: emptyList()
                val totalPages = response.headers()["X-Pagination-Page-Count"]?.toIntOrNull() ?: 1
                Result.success(Pair(items, totalPages))
            } else {
                Result.failure(Exception("Failed to fetch movie watchlist: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getShowWatchlist(page: Int = 1, limit: Int = 50): Result<Pair<List<TraktWatchlistShowItem>, Int>> {
        return try {
            val response = traktApiService.getShowWatchlist(
                type = "shows",
                extended = "full",
                page = page,
                limit = limit
            )
            if (response.isSuccessful) {
                val items = response.body() ?: emptyList()
                val totalPages = response.headers()["X-Pagination-Page-Count"]?.toIntOrNull() ?: 1
                Result.success(Pair(items, totalPages))
            } else {
                Result.failure(Exception("Failed to fetch show watchlist: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMovieHistory(page: Int = 1, limit: Int = 200): Result<Pair<List<TraktWatchlistMovieItem>, Int>> {
        return try {
            val response = traktApiService.getMovieHistory(page = page, limit = limit)
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

    suspend fun getShowHistory(page: Int = 1, limit: Int = 200): Result<Pair<List<TraktWatchlistShowItem>, Int>> {
        return try {
            val response = traktApiService.getShowHistory(page = page, limit = limit)
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

    /** 获取全部电影观看历史（跨页拉取），用于统计 */
    suspend fun getAllMovieHistory(): Result<List<TraktWatchlistMovieItem>> {
        val allItems = mutableListOf<TraktWatchlistMovieItem>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            val result = getMovieHistory(page = page, limit = 200)
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

    /** 获取全部电视剧观看历史（跨页拉取），用于统计 */
    suspend fun getAllShowHistory(): Result<List<TraktWatchlistShowItem>> {
        val allItems = mutableListOf<TraktWatchlistShowItem>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            val result = getShowHistory(page = page, limit = 200)
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

    /** 获取已看电视剧列表（含每部剧的已看集数），用于统计 */
    suspend fun getWatchedShowsWithEpisodes(): Result<List<TraktWatchedShow>> {
        return try {
            val response = traktApiService.getWatchedShows()
            if (response.isSuccessful) {
                val shows = response.body() ?: emptyList()
                android.util.Log.d("TraktRepo", "getWatchedShows: ${shows.size} shows")
                shows.forEach { show ->
                    val epCount = show.seasons.sumOf { season ->
                        season.episodes.count { it.completed > 0 }
                    }
                    android.util.Log.d("TraktRepo", "  ${show.show.title}: ${show.seasons.size} seasons, $epCount completed episodes")
                    show.seasons.forEach { season ->
                        android.util.Log.d("TraktRepo", "    S${season.number}: ${season.episodes.size} eps, completed: ${season.episodes.map { it.completed }}")
                    }
                }
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

    suspend fun markAsWatched(traktId: Int, type: MediaType): Result<TraktSyncResponse> {
        return try {
            val ids = TraktIds(trakt = traktId)
            val request = when (type) {
                MediaType.MOVIE -> TraktSyncRequest(movies = listOf(TraktSyncItem(ids)))
                MediaType.SHOW -> TraktSyncRequest(shows = listOf(TraktSyncItem(ids)))
            }
            val response = traktApiService.addToHistory(request)
            if (response.isSuccessful) {
                // 标记已看后自动从想看列表移除
                traktApiService.removeFromWatchlist(request)
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to mark as watched: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeWatched(traktId: Int, type: MediaType): Result<TraktSyncResponse> {
        return try {
            val ids = TraktIds(trakt = traktId)
            val request = when (type) {
                MediaType.MOVIE -> TraktSyncRequest(movies = listOf(TraktSyncItem(ids)))
                MediaType.SHOW -> TraktSyncRequest(shows = listOf(TraktSyncItem(ids)))
            }
            val response = traktApiService.removeFromHistory(request)
            if (response.isSuccessful) {
                // 取消已看后重新加入想看列表
                traktApiService.addToWatchlist(request)
                Result.success(response.body()!!)
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
            }

            traktIds.associateWith { id ->
                Pair(watchlistIds.contains(id), watchedIds.contains(id))
            }
        } catch (e: Exception) {
            traktIds.associateWith { Pair(false, false) }
        }
    }

    /** 添加到想看列表 */
    suspend fun addToWatchlist(traktId: Int, type: MediaType): Result<TraktSyncResponse> {
        return try {
            val ids = TraktIds(trakt = traktId)
            val request = when (type) {
                MediaType.MOVIE -> TraktSyncRequest(movies = listOf(TraktSyncItem(ids)))
                MediaType.SHOW -> TraktSyncRequest(shows = listOf(TraktSyncItem(ids)))
            }
            val response = traktApiService.addToWatchlist(request)
            if (response.isSuccessful) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to add to watchlist: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 从想看列表移除 */
    suspend fun removeFromWatchlist(traktId: Int, type: MediaType): Result<TraktSyncResponse> {
        return try {
            val ids = TraktIds(trakt = traktId)
            val request = when (type) {
                MediaType.MOVIE -> TraktSyncRequest(movies = listOf(TraktSyncItem(ids)))
                MediaType.SHOW -> TraktSyncRequest(shows = listOf(TraktSyncItem(ids)))
            }
            val response = traktApiService.removeFromWatchlist(request)
            if (response.isSuccessful) {
                Result.success(response.body()!!)
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

    /** 移除评分 */
    suspend fun removeRating(traktId: Int, type: MediaType): Result<Unit> {
        return try {
            val ids = TraktIds(trakt = traktId)
            val item = RatingItem(ids = ids)
            val request = when (type) {
                MediaType.MOVIE -> RatingRequest(movies = listOf(item))
                MediaType.SHOW -> RatingRequest(shows = listOf(item))
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
            }
            val response = traktApiService.getRatings(typeStr)
            if (response.isSuccessful) {
                val ratings = response.body() ?: emptyList()
                ratings.find { item ->
                    when (type) {
                        MediaType.MOVIE -> item.movie?.ids?.trakt == traktId
                        MediaType.SHOW -> item.show?.ids?.trakt == traktId
                    }
                }?.rating
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /** Trakt 文本搜索（电影） */
    suspend fun searchMovies(query: String, page: Int = 1, limit: Int = 20): Result<Pair<List<TraktSearchResult>, Int>> {
        return try {
            val response = traktApiService.searchMovies(query, limit, page)
            if (response.isSuccessful) {
                val items = response.body() ?: emptyList()
                val totalCount = response.headers()["X-Pagination-Item-Count"]?.toIntOrNull() ?: items.size
                Result.success(Pair(items, totalCount))
            } else {
                Result.failure(Exception("Failed to search movies: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Trakt 文本搜索（电视剧） */
    suspend fun searchShows(query: String, page: Int = 1, limit: Int = 20): Result<Pair<List<TraktSearchResult>, Int>> {
        return try {
            val response = traktApiService.searchShows(query, limit, page)
            if (response.isSuccessful) {
                val items = response.body() ?: emptyList()
                val totalCount = response.headers()["X-Pagination-Item-Count"]?.toIntOrNull() ?: items.size
                Result.success(Pair(items, totalCount))
            } else {
                Result.failure(Exception("Failed to search shows: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
}

enum class MediaType {
    MOVIE, SHOW
}
