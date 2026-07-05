package com.tracktosearch.ui.screen.listdetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.repository.TraktRepository.WatchlistWatchedIds
import com.tracktosearch.data.remote.trakt.dto.TraktShow
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

data class ListDetailUiState(
    val listId: Int = 0,
    val listName: String = "",
    val items: List<ListDetailItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 1,
    val error: String? = null
)

data class ListDetailItem(
    val rank: Int,
    val type: MediaType,
    val traktId: Int,
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val imdbId: String = "",
    val traktRating: Double = 0.0,
    val isEnhanced: Boolean = false
)

/** 磁盘缓存用的可序列化数据类 */
@Serializable
data class CachedListDetail(
    val listId: Int,
    val items: List<CachedListItem>
)

@Serializable
data class CachedListItem(
    val rank: Int,
    val type: String,
    val traktId: Int,
    val tmdbId: Int,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val imdbId: String = "",
    val traktRating: Double = 0.0
)

@HiltViewModel
class TraktListDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val listId: Int = savedStateHandle.get<Int>("listId") ?: 0
    private val listName: String = savedStateHandle.get<String>("listName") ?: ""

    private val _uiState = MutableStateFlow(ListDetailUiState(listId = listId, listName = listName))
    val uiState: StateFlow<ListDetailUiState> = _uiState

    // 全局想看/已看缓存
    private val _watchlistWatchedIds = MutableStateFlow<WatchlistWatchedIds?>(null)
    val watchlistWatchedIds: StateFlow<WatchlistWatchedIds?> = _watchlistWatchedIds

    // 增强缓存：traktId → 已增强的 ListDetailItem
    private val enhanceCache = mutableMapOf<Int, ListDetailItem>()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        // 加载全局想看/已看缓存
        viewModelScope.launch {
            traktRepository.getWatchlistWatchedIds()?.let { _watchlistWatchedIds.value = it }
            traktRepository.loadWatchlistWatchedIds().let { _watchlistWatchedIds.value = it }
        }
        if (listId > 0) {
            // 先尝试从磁盘缓存加载
            val cached = loadDiskCache()
            if (cached != null) {
                _uiState.value = ListDetailUiState(
                    listId = listId,
                    listName = listName,
                    items = cached,
                    isLoading = false,
                    hasMore = true
                )
                // 填充内存缓存
                cached.filter { it.isEnhanced }.forEach { enhanceCache[it.traktId] = it }
            } else {
                loadItems(page = 1)
            }
        }
    }

    fun retry() {
        loadItems(page = 1)
    }

    fun loadMore() {
        val current = _uiState.value
        if (current.isLoadingMore || !current.hasMore) return
        loadItems(page = current.currentPage + 1)
    }

    private fun loadItems(page: Int) {
        viewModelScope.launch {
            if (page == 1) {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            } else {
                _uiState.value = _uiState.value.copy(isLoadingMore = true)
            }

            val result = traktRepository.getListItems(listId, limit = 20, page = page)
            result.onSuccess { rawItems ->
                // 先用 Trakt 原始数据构建基础 item，缓存命中的直接用增强数据
                val baseItems = rawItems.mapNotNull { item ->
                    when (item.type) {
                        "movie" -> item.movie?.let { buildBaseMovieItem(item.rank, it) }
                        "show" -> item.show?.let { buildBaseShowItem(item.rank, it) }
                        else -> null
                    }
                }
                // 立即显示
                val current = _uiState.value
                val combinedItems = if (page == 1) baseItems else current.items + baseItems
                _uiState.value = current.copy(
                    items = combinedItems,
                    isLoading = false,
                    isLoadingMore = false,
                    currentPage = page,
                    hasMore = rawItems.size >= 20,
                    error = null
                )
                // 并发增强未增强的 item，逐个更新
                val itemsToEnhance = combinedItems.filter { !it.isEnhanced }
                enhanceItemsConcurrently(itemsToEnhance, page)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = e.message
                )
            }
        }
    }

    private fun enhanceItemsConcurrently(items: List<ListDetailItem>, currentPage: Int) {
        viewModelScope.launch {
            items.map { item ->
                async {
                    val enhanced = when (item.type) {
                        MediaType.MOVIE -> enhanceMovieItem(item)
                        MediaType.SHOW -> enhanceShowItem(item)
                        else -> item
                    }
                    // 写入缓存
                    enhanceCache[enhanced.traktId] = enhanced
                    // 立即更新 UI 中对应 item
                    val current = _uiState.value
                    val updatedItems = current.items.map {
                        if (it.traktId == enhanced.traktId && !it.isEnhanced) enhanced else it
                    }
                    _uiState.value = current.copy(items = updatedItems)
                    enhanced
                }
            }.awaitAll()
            // 所有增强完成后，写入磁盘缓存
            saveDiskCache(_uiState.value.items)
        }
    }

    private fun buildBaseMovieItem(rank: Int, movie: TraktMovie): ListDetailItem {
        val traktId = movie.ids.trakt
        enhanceCache[traktId]?.let { return it }
        return ListDetailItem(
            rank = rank,
            type = MediaType.MOVIE,
            traktId = traktId,
            tmdbId = movie.ids.tmdb,
            title = movie.title,
            year = movie.year,
            posterUrl = null,
            imdbId = movie.ids.imdb
        )
    }

    private fun buildBaseShowItem(rank: Int, show: TraktShow): ListDetailItem {
        val traktId = show.ids.trakt
        enhanceCache[traktId]?.let { return it }
        return ListDetailItem(
            rank = rank,
            type = MediaType.SHOW,
            traktId = traktId,
            tmdbId = show.ids.tmdb,
            title = show.title,
            year = show.year,
            posterUrl = null,
            imdbId = show.ids.imdb
        )
    }

    private suspend fun enhanceMovieItem(base: ListDetailItem): ListDetailItem {
        if (base.tmdbId <= 0) return base.copy(isEnhanced = true)
        try {
            val detail = tmdbRepository.getMovieDetail(base.tmdbId)
            if (detail != null) {
                return base.copy(
                    title = detail.title.ifBlank { base.title },
                    year = detail.release_date.take(4).toIntOrNull() ?: base.year,
                    posterUrl = detail.poster_path,
                    isEnhanced = true
                )
            }
        } catch (_: Exception) {}
        return base.copy(isEnhanced = true)
    }

    private suspend fun enhanceShowItem(base: ListDetailItem): ListDetailItem {
        if (base.tmdbId <= 0) return base.copy(isEnhanced = true)
        try {
            val enrichment = tmdbRepository.enrichTv(base.tmdbId, base.title, base.year)
            return base.copy(
                title = enrichment.chineseTitle.ifBlank { base.title },
                year = enrichment.year ?: base.year,
                posterUrl = enrichment.posterUrl,
                isEnhanced = true
            )
        } catch (_: Exception) {}
        return base.copy(isEnhanced = true)
    }

    // ========== 磁盘缓存 ==========

    private fun cacheFile(): File = File(context.cacheDir, "list_detail_$listId.json")

    private fun loadDiskCache(): List<ListDetailItem>? {
        val file = cacheFile()
        if (!file.exists()) return null
        return try {
            val cached = json.decodeFromString<CachedListDetail>(file.readText())
            cached.items.map { it.toListDetailItem() }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveDiskCache(items: List<ListDetailItem>) {
        try {
            val cached = CachedListDetail(
                listId = listId,
                items = items.map { it.toCachedListItem() }
            )
            cacheFile().writeText(json.encodeToString(cached))
        } catch (_: Exception) {}
    }

    private fun ListDetailItem.toCachedListItem() = CachedListItem(
        rank = rank,
        type = type.name,
        traktId = traktId,
        tmdbId = tmdbId,
        title = title,
        year = year,
        posterUrl = posterUrl,
        imdbId = imdbId,
        traktRating = traktRating
    )

    private fun CachedListItem.toListDetailItem() = ListDetailItem(
        rank = rank,
        type = try { MediaType.valueOf(type) } catch (_: Exception) { MediaType.MOVIE },
        traktId = traktId,
        tmdbId = tmdbId,
        title = title,
        year = year,
        posterUrl = posterUrl,
        imdbId = imdbId,
        traktRating = traktRating,
        isEnhanced = true // 磁盘缓存的都是增强完成的
    )
}
