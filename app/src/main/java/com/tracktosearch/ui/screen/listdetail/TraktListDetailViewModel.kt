package com.tracktosearch.ui.screen.listdetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.repository.TraktRepository.WatchlistWatchedIds
import com.tracktosearch.data.remote.trakt.dto.TraktShow
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.di.DispatcherModule
import com.tracktosearch.ui.util.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Named

data class ListDetailUiState(
    val listId: Int = 0,
    val listName: String = "",
    val items: List<ListDetailItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    /**
     * 第一页正在向远端刷新，但列表已经有内容（磁盘快照或上一次结果）。
     *
     * 顶栏下方显示细进度条提示数据可能不是最新；列表还空着的骨架屏阶段由 [isLoading] 负责。
     * 翻页有底部加载指示器，不重复提示。
     */
    val isRefreshing: Boolean = false,
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
    /** 完整海报 URL（已含 [com.tracktosearch.data.remote.tmdb.TmdbImageUrls] 尺寸前缀），可直接交给 Coil。 */
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
    private val sessionModeManager: SessionModeManager,
    @Named(DispatcherModule.IO_DISPATCHER) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val listId: Int = savedStateHandle.get<Int>("listId") ?: 0

    /**
     * 榜单名。
     *
     * 导航时经 [java.net.URLEncoder] 编码进路由（空格编成 `+`、非 ASCII 编成 `%XX`），这里必须解回来，
     * 否则顶栏标题会显示成 `MARVEL+Cinematic+Universe`。Nav 组件对路径参数只做 `Uri.decode`，
     * 它不把 `+` 当空格，所以这一步不能省。
     * 解码失败（榜单名里有未转义的 `%`）时退回原串，标题难看好过整页崩掉。
     */
    private val listName: String = (savedStateHandle.get<String>("listName") ?: "").let { raw ->
        runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    private val _uiState = MutableStateFlow(ListDetailUiState(listId = listId, listName = listName))
    val uiState: StateFlow<ListDetailUiState> = _uiState

    // 全局想看/已看缓存
    private val _watchlistWatchedIds = MutableStateFlow<WatchlistWatchedIds?>(null)
    val watchlistWatchedIds: StateFlow<WatchlistWatchedIds?> = _watchlistWatchedIds

    // 增强缓存：traktId → 已增强的 ListDetailItem
    private val enhanceCache = mutableMapOf<Int, ListDetailItem>()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** Trakt 列表条目分页大小。 */
        private const val PAGE_SIZE = 20

        /**
         * TMDB 富化的分批大小，取一屏的行数（3 列 × 3 行）。
         *
         * 分批有两个作用：每批只写一次 uiState（逐条写会让顶栏毛玻璃取色、海报预取在一次
         * 加载里重启二十次），以及让首屏的九张海报先发请求先落地，不必和屏幕外的抢带宽。
         */
        private const val ENHANCE_CHUNK = 9
    }

    init {
        // 加载全局想看/已看缓存
        viewModelScope.launch {
            if (!sessionModeManager.traktConnected.value) {
                _watchlistWatchedIds.value = WatchlistWatchedIds()
                return@launch
            }
            traktRepository.getWatchlistWatchedIds()?.let { _watchlistWatchedIds.value = it }
            // 仓库层网络失败会向上抛（约定由调用方兜底）；失败仅保留上面的缓存值，
            // 未捕获会把协程异常抛到全局 handler 直接杀死进程（断网时必现）
            try {
                traktRepository.loadWatchlistWatchedIds().let { _watchlistWatchedIds.value = it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ListDetailVM", "loadWatchlistWatchedIds failed: ${e.message}")
            }
        }
        if (listId > 0) {
            // 磁盘快照与远端请求并行发出：读盘不拖慢网络，网络也不必等读盘
            restoreDiskSnapshot()
            loadItems(page = 1)
        }
    }

    /**
     * 恢复磁盘快照，让列表在网络回来之前就有内容。
     *
     * 读盘与 JSON 解码放到 IO：进入本页时共享元素转场正在跑，主线程解码几十条会掉帧。
     * 网络已经先回来（items 非空）时丢弃快照，避免旧数据盖掉新数据。
     * 快照只存第一页（见 [saveDiskSnapshot]），所以 currentPage 保持 1，翻页不会与快照重复。
     */
    private fun restoreDiskSnapshot() {
        viewModelScope.launch {
            val snapshot = withContext(ioDispatcher) { loadDiskSnapshot() }
            if (snapshot.isNullOrEmpty()) return@launch
            val current = _uiState.value
            if (current.items.isNotEmpty()) return@launch
            snapshot.forEach { enhanceCache[it.traktId] = it }
            _uiState.value = current.copy(
                items = snapshot,
                hasMore = true,
                // 远端请求还在飞就亮细进度条，提示当前内容可能不是最新
                isRefreshing = current.isLoading
            )
        }
    }

    fun retry() {
        loadItems(page = 1)
    }

    fun loadMore() {
        val current = _uiState.value
        // isLoading 也要挡：第一页还在刷新时翻页会把第二页追加到即将被替换的列表上
        if (current.isLoading || current.isLoadingMore || !current.hasMore) return
        loadItems(page = current.currentPage + 1)
    }

    private fun loadItems(page: Int) {
        viewModelScope.launch {
            val before = _uiState.value
            if (page == 1) {
                _uiState.value = before.copy(
                    isLoading = true,
                    // 已有旧内容时不遮挡内容，只用顶栏细进度条提示正在刷新
                    isRefreshing = before.items.isNotEmpty(),
                    error = null
                )
            } else {
                _uiState.value = before.copy(isLoadingMore = true)
            }

            val result = traktRepository.getListItems(listId, limit = PAGE_SIZE, page = page)
            result.onSuccess { rawItems ->
                // 先用 Trakt 原始数据构建基础 item，缓存命中的直接用增强数据
                val baseItems = rawItems.mapNotNull { item ->
                    when (item.type) {
                        "movie" -> item.movie?.let { buildBaseMovieItem(item.rank, it) }
                        "show" -> item.show?.let { buildBaseShowItem(item.rank, it) }
                        else -> null
                    }
                }
                // 立即显示。distinctBy 兜底：列表在翻页间隙变动会返回重复条目，
                // 撞上 LazyGrid 的 key 会直接抛异常
                val current = _uiState.value
                val combinedItems = if (page == 1) baseItems else (current.items + baseItems)
                    .distinctBy { it.gridKey() }
                _uiState.value = current.copy(
                    items = combinedItems,
                    isLoading = false,
                    isLoadingMore = false,
                    isRefreshing = false,
                    currentPage = page,
                    hasMore = rawItems.size >= PAGE_SIZE,
                    error = null
                )
                // 分批富化未增强的 item，每批一次性刷新 UI
                enhanceItemsInBatches(combinedItems.filter { !it.isEnhanced })
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    // 刷新失败：保留旧内容，只收起进度条
                    isRefreshing = false,
                    error = e.toUserMessage(context, R.string.error_load_failed)
                )
            }
        }
    }

    /**
     * 按 [ENHANCE_CHUNK] 分批并发富化，每批结束写一次 uiState。
     *
     * 逐条写状态会把顶栏毛玻璃取色、海报预取在一次加载里重启二十次，是进入本页卡顿的主因；
     * 分批同时把 TMDB 并发压到一屏的量，视口内的海报不必和屏幕外的抢带宽。
     */
    private fun enhanceItemsInBatches(items: List<ListDetailItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            for (batch in items.chunked(ENHANCE_CHUNK)) {
                val enhanced = batch.map { item ->
                    async {
                        when (item.type) {
                            MediaType.MOVIE -> enhanceMovieItem(item)
                            MediaType.SHOW -> enhanceShowItem(item)
                            else -> item.copy(isEnhanced = true)
                        }
                    }
                }.awaitAll()
                enhanced.forEach { enhanceCache[it.traktId] = it }
                val enhancedById = enhanced.associateBy { it.traktId }
                _uiState.update { state ->
                    state.copy(
                        items = state.items.map { existing ->
                            if (existing.isEnhanced) existing else enhancedById[existing.traktId] ?: existing
                        }
                    )
                }
            }
            saveDiskSnapshot(_uiState.value.items)
        }
    }

    /** 与列表的 LazyGrid key 同构，用于去重后保证不会撞 key。 */
    private fun ListDetailItem.gridKey(): String = "$type-$traktId"

    /**
     * 用 Trakt 原始数据建基础 item，并尽力从本地缓存直接补齐海报与中文标题。
     *
     * 命中顺序：本页的 [enhanceCache]（翻页/刷新复用），再到 TMDB 内存缓存的同步 peek。
     * peek 不挂起、不读盘、不发网络，所以能在首帧就把海报填上——从发现页反复进出同一榜单时，
     * 第一屏不再是二十个灰块，也不会再为已经在内存里的详情重发请求。
     */
    private fun buildBaseMovieItem(rank: Int, movie: TraktMovie): ListDetailItem {
        val traktId = movie.ids.trakt
        enhanceCache[traktId]?.let { return it.copy(rank = rank) }
        val base = ListDetailItem(
            rank = rank,
            type = MediaType.MOVIE,
            traktId = traktId,
            tmdbId = movie.ids.tmdb,
            title = movie.title,
            year = movie.year,
            posterUrl = null,
            imdbId = movie.ids.imdb
        )
        val peeked = tmdbRepository.peekMovieEnrichment(movie.ids.tmdb, movie.title, movie.year)
            ?: return base
        return base.copy(
            title = peeked.chineseTitle.ifBlank { base.title },
            year = peeked.year ?: base.year,
            posterUrl = peeked.posterUrl,
            isEnhanced = true
        )
    }

    /** 语义同 [buildBaseMovieItem]，走剧集的 peek。 */
    private fun buildBaseShowItem(rank: Int, show: TraktShow): ListDetailItem {
        val traktId = show.ids.trakt
        enhanceCache[traktId]?.let { return it.copy(rank = rank) }
        val base = ListDetailItem(
            rank = rank,
            type = MediaType.SHOW,
            traktId = traktId,
            tmdbId = show.ids.tmdb,
            title = show.title,
            year = show.year,
            posterUrl = null,
            imdbId = show.ids.imdb
        )
        val peeked = tmdbRepository.peekTvEnrichment(show.ids.tmdb, show.title, show.year)
            ?: return base
        return base.copy(
            title = peeked.chineseTitle.ifBlank { base.title },
            year = peeked.year ?: base.year,
            posterUrl = peeked.posterUrl,
            isEnhanced = true
        )
    }

    /**
     * 补齐电影的中文标题、年份与海报。
     *
     * 走 enrichMovie 而不是 getMovieDetail：与 [buildBaseMovieItem] 的 peek 同一套映射，
     * 同一部电影无论从缓存还是从网络补齐，标题和海报尺寸都一致；enrichMovie 内部已兜住网络异常。
     */
    private suspend fun enhanceMovieItem(base: ListDetailItem): ListDetailItem {
        if (base.tmdbId <= 0) return base.copy(isEnhanced = true)
        try {
            val enrichment = tmdbRepository.enrichMovie(base.tmdbId, base.title, base.year)
            return base.copy(
                title = enrichment.chineseTitle.ifBlank { base.title },
                year = enrichment.year ?: base.year,
                posterUrl = enrichment.posterUrl ?: base.posterUrl,
                isEnhanced = true
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {}
        return base.copy(isEnhanced = true)
    }

    /** 语义同 [enhanceMovieItem]，走剧集的 enrichTv。 */
    private suspend fun enhanceShowItem(base: ListDetailItem): ListDetailItem {
        if (base.tmdbId <= 0) return base.copy(isEnhanced = true)
        try {
            val enrichment = tmdbRepository.enrichTv(base.tmdbId, base.title, base.year)
            return base.copy(
                title = enrichment.chineseTitle.ifBlank { base.title },
                year = enrichment.year ?: base.year,
                posterUrl = enrichment.posterUrl ?: base.posterUrl,
                isEnhanced = true
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {}
        return base.copy(isEnhanced = true)
    }

    // ========== 磁盘快照 ==========

    private fun cacheFile(): File = File(context.cacheDir, "list_detail_$listId.json")

    /** 读快照。调用方负责切到 IO（见 [restoreDiskSnapshot]）。 */
    private fun loadDiskSnapshot(): List<ListDetailItem>? {
        val file = cacheFile()
        if (!file.exists()) return null
        return try {
            val cached = json.decodeFromString<CachedListDetail>(file.readText())
            cached.items.map { it.toListDetailItem() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 只存第一页。
     *
     * 存全部翻页结果会让下次进页时 items 已有几十条而 currentPage 仍是 1，
     * [loadMore] 会把第二页重新追加一遍，撞上 LazyGrid 的 key 直接崩。
     * 快照的作用是让首屏立刻有内容，一页够用。
     */
    private suspend fun saveDiskSnapshot(items: List<ListDetailItem>) {
        val page = items.take(PAGE_SIZE)
        if (page.isEmpty()) return
        withContext(ioDispatcher) {
            try {
                val cached = CachedListDetail(
                    listId = listId,
                    items = page.map { it.toCachedListItem() }
                )
                cacheFile().writeText(json.encodeToString(cached))
            } catch (_: Exception) {}
        }
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
