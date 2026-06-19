package com.tracktosearch.data.repository

import com.tracktosearch.data.remote.trakt.TraktApiService
import com.tracktosearch.data.remote.trakt.dto.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktRepository @Inject constructor(
    private val traktApiService: TraktApiService
) {
    suspend fun searchByTmdb(tmdbId: Int, type: MediaType): Result<List<TraktSearchResult>> {
        return try {
            val typeStr = when (type) {
                MediaType.MOVIE -> "movie"
                MediaType.SHOW -> "show"
            }
            val response = traktApiService.searchByTmdb(tmdbId, typeStr)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
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

    suspend fun getComments(traktId: Int, type: MediaType, limit: Int = 5, page: Int = 1): Result<List<TraktComment>> {
        return try {
            val response = when (type) {
                MediaType.MOVIE -> traktApiService.getMovieComments(traktId.toString(), limit, page)
                MediaType.SHOW -> traktApiService.getShowComments(traktId.toString(), limit, page)
            }
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
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
                Result.failure(Exception("Failed to mark episode watched: ${response.code()}"))
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
        return try {
            val response = traktApiService.getRelatedMovies(traktId, limit = limit)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("Failed to fetch related movies: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRelatedShows(traktId: Int, limit: Int = 10): Result<List<TraktShow>> {
        return try {
            val response = traktApiService.getRelatedShows(traktId, limit = limit)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
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
}

enum class MediaType {
    MOVIE, SHOW
}
