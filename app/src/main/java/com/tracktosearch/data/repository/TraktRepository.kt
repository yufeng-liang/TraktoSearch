package com.tracktosearch.data.repository

import com.tracktosearch.data.remote.trakt.TraktApiService
import com.tracktosearch.data.remote.trakt.dto.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktRepository @Inject constructor(
    private val traktApiService: TraktApiService
) {
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
}

enum class MediaType {
    MOVIE, SHOW
}
