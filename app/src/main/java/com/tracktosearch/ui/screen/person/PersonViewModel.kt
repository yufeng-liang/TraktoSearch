package com.tracktosearch.ui.screen.person

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.remote.tmdb.dto.TmdbPerson
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonMovieCredit
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonTvCredit
import com.tracktosearch.data.remote.trakt.dto.TraktPersonDetail
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.util.PersonAvatarColorStore
import com.tracktosearch.data.util.PosterColorExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class PersonUiState(
    val isLoading: Boolean = false,
    val person: TmdbPerson? = null,
    val movieCredits: List<TmdbPersonMovieCredit> = emptyList(),
    val tvCredits: List<TmdbPersonTvCredit> = emptyList(),
    val hasMoreMovies: Boolean = false,
    val hasMoreTvShows: Boolean = false,
    val isLoadingMoreMovies: Boolean = false,
    val isLoadingMoreTvShows: Boolean = false,
    val isLoadingMovies: Boolean = false, // 电影作品是否正在加载
    val isLoadingTvShows: Boolean = false, // 剧集作品是否正在加载
    val error: String? = null,
    val resolvingTmdbId: Int? = null,
    val traktPerson: TraktPersonDetail? = null,
    val personImages: List<String> = emptyList(), // 人物图片URL列表（headshot + fanart等）
    val isLoadingPersonImages: Boolean = false,
    val totalMovieCredits: Int = 0,
    val totalTvCredits: Int = 0,
    val originalName: String? = null,
    val isLoadingTrakt: Boolean = true, // Trakt 数据是否正在加载（控制社媒/简介骨架占位）
    val avatarDominantColor: Color? = null // 头像主色（用于顶部渐变背景）
)

@HiltViewModel
class PersonViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val posterColorExtractor: PosterColorExtractor
) : ViewModel() {

    companion object {
        // 人物图片 URL 列表内存缓存（personId -> urls），LRU 限制 30 条
        private val personImagesCache = android.util.LruCache<Int, List<String>>(30)
        // Trakt人物详情缓存（personId -> TraktPersonDetail），包含社媒、维基百科等，LRU 限制 30 条
        private val traktPersonCache = android.util.LruCache<Int, com.tracktosearch.data.remote.trakt.dto.TraktPersonDetail>(30)
        // 原名缓存（personId -> originalName），LRU 限制 30 条
        private val originalNameCache = android.util.LruCache<Int, String>(30)
        // Trakt 数据最近一次拉取时间戳（personId -> System.currentTimeMillis），用于 TTL 跳过刷新
        private val traktPersonFetchTime = android.util.LruCache<Int, Long>(30)
        // Trakt 数据 TTL：1 小时内不重复请求
        private const val TRAKT_PERSON_TTL = 60 * 60 * 1000L
    }

    private val _uiState = MutableStateFlow(PersonUiState())
    val uiState: StateFlow<PersonUiState> = _uiState.asStateFlow()

    private val _toastEvent = MutableSharedFlow<Int>()
    val toastEvent = _toastEvent.asSharedFlow()

    private var currentPersonId: Int = 0
    private var loaded: Boolean = false
    private var movieCreditsPage: Int = 1
    private var tvCreditsPage: Int = 1

    fun loadPerson(personId: Int, profilePath: String? = null, avatarColor: Color? = null) {
        if (currentPersonId == personId && loaded) return
        currentPersonId = personId
        loaded = false
        movieCreditsPage = 1
        tvCreditsPage = 1

        // 首帧直接带入前一屏已算好的主色；路由未传时查进程内缓存，避免首次进入白色闪烁
        val initialColor = avatarColor
            ?: PersonAvatarColorStore.get(personId)?.let { Color(it) }

        _uiState.value = PersonUiState(
            isLoading = true,
            isLoadingMovies = true,
            isLoadingTvShows = true,
            isLoadingPersonImages = true,
            avatarDominantColor = initialColor
        )

        // 路由已带 profilePath（前一屏算好的头像 URL），不等 TMDB API 直接调色
        if (!profilePath.isNullOrBlank()) {
            prefetchAvatarColor(profilePath)
        }

        viewModelScope.launch {
            try {
                val personDeferred = async { tmdbRepository.getPersonDetail(personId) }
                val movieCreditsDeferred = async { tmdbRepository.getPersonMovieCredits(personId, page = 1) }
                val tvCreditsDeferred = async { tmdbRepository.getPersonTvCredits(personId, page = 1) }

                // 渐进式渲染：人物基本信息先显示
                val person = personDeferred.await()
                if (person == null) {
                    _uiState.value = PersonUiState(error = "Failed to load person")
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        person = person
                    )
                    // 异步加载 Trakt 人物数据（静默失败）
                    loadTraktPerson(personId, person.name)
                    // 从 TMDB also_known_as 获取原名（英文名）
                    val originalName = person.also_known_as.firstOrNull { name ->
                        name.all { c -> c.isLetter() || c == ' ' || c == '.' || c == '-' || c == '\'' }
                    }
                    if (originalName != null) {
                        _uiState.value = _uiState.value.copy(originalName = originalName)
                    }
                    // 预取头像主色（用于顶部沉浸式渐变背景）
                    prefetchAvatarColor(person.profile_path)
                }

                // 电影作品加载完成，独立更新
                movieCreditsDeferred.await().let { result ->
                    _uiState.value = _uiState.value.copy(
                        isLoadingMovies = false,
                        movieCredits = result.items,
                        hasMoreMovies = result.hasMore
                    )
                }

                // 剧集作品加载完成，独立更新
                tvCreditsDeferred.await().let { result ->
                    _uiState.value = _uiState.value.copy(
                        isLoadingTvShows = false,
                        tvCredits = result.items,
                        hasMoreTvShows = result.hasMore
                    )
                }

                loaded = true
            } catch (_: Exception) {
                _uiState.value = PersonUiState(error = "Failed to load person data")
            }
        }
    }

    /**
     * 预取头像主色：先查持久化缓存命中则瞬间生效，未命中则用 Coil 加载头像 bitmap
     * 并延迟 1.5s 后提取主色，避免快速滑过时浪费 CPU。
     */
    private fun prefetchAvatarColor(profilePath: String?) {
        if (profilePath.isNullOrBlank()) return
        val avatarUrl = TmdbImageUrls.build(profilePath)
        // 已有主色则跳过（首帧带入场景）
        if (_uiState.value.avatarDominantColor != null) return
        viewModelScope.launch {
            posterColorExtractor.getCachedColor(avatarUrl)?.let { argb ->
                if (argb != 0L) {
                    _uiState.value = _uiState.value.copy(avatarDominantColor = Color(argb))
                }
            }
        }
        // 使用 Coil 加载头像 bitmap，加载成功后延迟提取主色
        val request = ImageRequest.Builder(context)
            .data(avatarUrl)
            .size(200)
            .crossfade(false)
            .listener(
                onSuccess = { _, result ->
                    val bitmap = result.drawable.toBitmap()
                    viewModelScope.launch {
                        val argb = posterColorExtractor.extractDominantColor(avatarUrl, bitmap)
                        if (argb != 0L) {
                            _uiState.value = _uiState.value.copy(avatarDominantColor = Color(argb))
                        }
                    }
                }
            )
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun loadTraktPerson(tmdbId: Int, personName: String) {
        // 检查缓存：若有缓存则先用缓存显示
        val cachedImages = personImagesCache[tmdbId]
        val cachedTraktPerson = traktPersonCache[tmdbId]
        val cachedOriginalName = originalNameCache[tmdbId]
        val cachedFetchTime = traktPersonFetchTime[tmdbId]
        val now = System.currentTimeMillis()
        // TTL 内（1 小时）有缓存则跳过 Trakt 后台刷新，避免二次进入重复发请求
        val cacheFresh = cachedFetchTime != null && (now - cachedFetchTime) < TRAKT_PERSON_TTL

        if (cachedTraktPerson != null) {
            _uiState.value = _uiState.value.copy(traktPerson = cachedTraktPerson)
        }
        if (cachedOriginalName != null) {
            _uiState.value = _uiState.value.copy(originalName = cachedOriginalName)
        }
        if (cachedImages != null && cachedImages.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(personImages = cachedImages)
        }

        // 有缓存时不显示加载状态，直接显示缓存数据
        val hasAnyCache = cachedTraktPerson != null || cachedImages?.isNotEmpty() == true
        _uiState.value = _uiState.value.copy(
            isLoadingPersonImages = cachedImages.isNullOrEmpty(),
            isLoadingTrakt = !hasAnyCache
        )

        // 缓存新鲜则跳过 Trakt 后台刷新（图片仍走 TmdbRepository 的 TtlCache，命中即不重复请求）
        if (cacheFresh) {
            viewModelScope.launch {
                // 仍然走 loadTmdbPersonImages 让图片列表合并状态正常化（若缓存图片已存在则状态保持）
                if (cachedImages.isNullOrEmpty()) {
                    loadTmdbPersonImages()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoadingPersonImages = false,
                        isLoadingTrakt = false
                    )
                }
            }
            return
        }

        viewModelScope.launch {
            try {
                // 使用 TMDB ID 搜索 Trakt 人物
                val searchResult = traktRepository.searchByTmdb(tmdbId, MediaType.PERSON)
                searchResult.onSuccess { results ->
                    android.util.Log.d("PersonVM", "Trakt search TMDB $tmdbId: ${results.size} results")
                    val personResult = results.firstOrNull { it.person != null }
                    val slug = personResult?.person?.ids?.slug
                    android.util.Log.d("PersonVM", "Person slug: $slug")
                    if (!slug.isNullOrEmpty()) {
                        // 5 个请求并行执行，总耗时取最慢的一个
                        val detailDeferred = async { traktRepository.getPersonSummary(slug) }
                        val movieCreditsDeferred = async { traktRepository.getPersonMovieCredits(slug) }
                        val showCreditsDeferred = async { traktRepository.getPersonShowCredits(slug) }
                        val aliasesDeferred = async { traktRepository.getPersonAliases(slug) }
                        val imagesDeferred = async { traktRepository.getPersonImages(slug) }

                        val detailResult = detailDeferred.await()
                        detailResult.onSuccess { detail ->
                            // 更新缓存
                            traktPersonCache.put(tmdbId, detail)
                            _uiState.value = _uiState.value.copy(traktPerson = detail)
                        }
                        movieCreditsDeferred.await().onSuccess { credits ->
                            _uiState.value = _uiState.value.copy(totalMovieCredits = credits.cast.size)
                        }
                        showCreditsDeferred.await().onSuccess { credits ->
                            _uiState.value = _uiState.value.copy(totalTvCredits = credits.cast.size)
                        }
                        aliasesDeferred.await().onSuccess { aliases ->
                            val originalName = aliases.firstOrNull { it.country == null }?.name
                            if (originalName != null && originalName != detailResult.getOrNull()?.name) {
                                // 更新缓存
                                originalNameCache.put(tmdbId, originalName)
                                _uiState.value = _uiState.value.copy(originalName = originalName)
                            }
                        }
                        imagesDeferred.await().onSuccess { images ->
                            val imageUrls = mutableListOf<String>()
                            images.headshot.forEach { url ->
                                val fullUrl = if (url.startsWith("http")) url else "https://$url"
                                imageUrls.add(fullUrl)
                            }
                            images.fanart.forEach { url ->
                                val fullUrl = if (url.startsWith("http")) url else "https://$url"
                                imageUrls.add(fullUrl)
                            }
                            images.poster.forEach { url ->
                                val fullUrl = if (url.startsWith("http")) url else "https://$url"
                                imageUrls.add(fullUrl)
                            }
                            if (imageUrls.isNotEmpty()) {
                                personImagesCache.put(tmdbId, imageUrls)
                                _uiState.value = _uiState.value.copy(personImages = imageUrls)
                            }
                        }
                        // 标记本次拉取完成,启动 TTL 跳过窗口
                        traktPersonFetchTime.put(tmdbId, System.currentTimeMillis())
                    }
                }
            } catch (_: Exception) {
                // 静默失败，不影响页面正常显示
                _uiState.value = _uiState.value.copy(isLoadingTrakt = false)
            }
            // 额外获取 TMDB 人物图片并合并
            loadTmdbPersonImages()
        }
    }

    private fun loadTmdbPersonImages() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingPersonImages = true)
            try {
                val tmdbImages = tmdbRepository.getPersonImages(currentPersonId)
                if (tmdbImages.isNotEmpty()) {
                    val existing = _uiState.value.personImages.toMutableList()
                    val existingSet = existing.toSet()
                    tmdbImages.forEach { url ->
                        if (url !in existingSet) {
                            existing.add(url)
                        }
                    }
                    _uiState.value = _uiState.value.copy(personImages = existing)
                }
                // 获取人物被标注的图片（影视海报/剧照中含该人物的照片）
                val taggedImages = tmdbRepository.getPersonTaggedImages(currentPersonId)
                if (taggedImages.isNotEmpty()) {
                    val existing = _uiState.value.personImages.toMutableList()
                    val existingSet = existing.toSet()
                    taggedImages.forEach { url ->
                        if (url !in existingSet) {
                            existing.add(url)
                        }
                    }
                    _uiState.value = _uiState.value.copy(personImages = existing)
                }
            } catch (_: Exception) {
            } finally {
                // 更新缓存为最终合并结果
                val finalImages = _uiState.value.personImages
                if (finalImages.isNotEmpty()) {
                    personImagesCache.put(currentPersonId, finalImages)
                }
                _uiState.value = _uiState.value.copy(isLoadingPersonImages = false, isLoadingTrakt = false)
            }
        }
    }

    fun loadMoreMovies() {
        val state = _uiState.value
        if (state.isLoadingMoreMovies || !state.hasMoreMovies) return
        val nextPage = movieCreditsPage + 1

        _uiState.value = state.copy(isLoadingMoreMovies = true)
        viewModelScope.launch {
            try {
                val result = tmdbRepository.getPersonMovieCredits(currentPersonId, page = nextPage)
                movieCreditsPage = nextPage
                _uiState.value = _uiState.value.copy(
                    movieCredits = _uiState.value.movieCredits + result.items,
                    hasMoreMovies = result.hasMore,
                    isLoadingMoreMovies = false
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMoreMovies = false)
            }
        }
    }

    fun loadMoreTvShows() {
        val state = _uiState.value
        if (state.isLoadingMoreTvShows || !state.hasMoreTvShows) return
        val nextPage = tvCreditsPage + 1

        _uiState.value = state.copy(isLoadingMoreTvShows = true)
        viewModelScope.launch {
            try {
                val result = tmdbRepository.getPersonTvCredits(currentPersonId, page = nextPage)
                tvCreditsPage = nextPage
                _uiState.value = _uiState.value.copy(
                    tvCredits = _uiState.value.tvCredits + result.items,
                    hasMoreTvShows = result.hasMore,
                    isLoadingMoreTvShows = false
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMoreTvShows = false)
            }
        }
    }

    fun resolveAndNavigate(
        tmdbId: Int,
        title: String,
        isMovie: Boolean,
        onNavigate: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit
    ) {
        _uiState.value = _uiState.value.copy(resolvingTmdbId = tmdbId)
        viewModelScope.launch {
            try {
                val type = if (isMovie) MediaType.MOVIE else MediaType.SHOW
                val result = traktRepository.searchByTmdb(tmdbId, type)
                result.onSuccess { searchResults ->
                    val first = searchResults.firstOrNull()
                    val traktId = if (isMovie) first?.movie?.ids?.trakt else first?.show?.ids?.trakt
                    val imdbId = if (isMovie) first?.movie?.ids?.imdb else first?.show?.ids?.imdb ?: ""
                    if (traktId != null && traktId > 0) {
                        onNavigate(traktId, tmdbId, title, imdbId ?: "", 0.0)
                    } else {
                        _toastEvent.emit(R.string.card_resolve_not_found)
                    }
                }
            } catch (_: Exception) {
                // 忽略异常，确保 resolvingTmdbId 被清空
            } finally {
                _uiState.value = _uiState.value.copy(resolvingTmdbId = null)
            }
        }
    }
}
