package com.tracktosearch.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.remote.tmdb.TmdbApiService
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.remote.tmdb.dto.*
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.data.util.TtlCache
import com.tracktosearch.data.util.persistentTtlCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 持久化缓存专用 DataStore（共享一个文件，通过 key 前缀区分不同缓存类型） */
private val Context.persistentCacheDataStore: DataStore<Preferences> by preferencesDataStore(name = "persistent_cache")

@Singleton
class TmdbRepository @Inject constructor(
    private val tmdbApiService: TmdbApiService,
    private val languageStorage: LanguageStorage,
    private val json: Json,
    @ApplicationContext private val context: Context
) {
    companion object {
        private val IMAGE_BASE_URL = TmdbImageUrls.W500
        private const val TTL_DETAIL = Long.MAX_VALUE       // 详情（海报路径、tmdbId、imdbId 等不变字段）永久缓存
        private const val TTL_CREDITS = Long.MAX_VALUE      // 演职员信息永久缓存（头像、姓名、角色不变）
        private const val TTL_SEARCH = 60 * 60 * 1000L       // 搜索/ID转换 1 小时
        private const val TTL_PERSON = Long.MAX_VALUE       // 人物信息永久缓存（姓名、头像路径、生日等不变）
        private const val TTL_REVIEWS = 10 * 60 * 1000L      // 评论 10 分钟
        private const val TTL_LISTS = 6 * 60 * 60 * 1000L   // 列表类 6 小时（榜单数据更新不频繁）
        private const val PERSON_CREDITS_PAGE_SIZE = 20      // 人物作品每页数量
    }

    /** 根据当前语言设置返回 TMDB API 的 language 参数 */
    private suspend fun getTmdbLanguage(): String {
        val lang = languageStorage.language.first()
        return when (lang) {
            LanguageStorage.LANGUAGE_CHINESE -> "zh-CN"
            LanguageStorage.LANGUAGE_ENGLISH -> "en-US"
            LanguageStorage.LANGUAGE_JAPANESE -> "ja-JP"
            LanguageStorage.LANGUAGE_KOREAN -> "ko-KR"
            else -> "zh-CN" // 默认中文
        }
    }

    /** 构造带语言后缀的缓存 key，避免切换语言后命中旧语言缓存 */
    private suspend fun langKey(id: Any): String = "${id}_${getTmdbLanguage()}"

    /** 根据当前语言设置返回 TMDB alternative_titles 的 country 参数 */
    private suspend fun getTmdbCountry(): String {
        val lang = languageStorage.language.first()
        return when (lang) {
            LanguageStorage.LANGUAGE_CHINESE -> "CN"
            LanguageStorage.LANGUAGE_ENGLISH -> "US"
            LanguageStorage.LANGUAGE_JAPANESE -> "JP"
            LanguageStorage.LANGUAGE_KOREAN -> "KR"
            else -> "CN"
        }
    }

    /** 人物作品分页结果 */
    data class PersonCreditsPage<T>(
        val items: List<T>,
        val hasMore: Boolean
    )

    // 持久化缓存作用域：IO 线程 + SupervisorJob（子协程异常不影响父级）
    private val persistentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistentDataStore get() = context.persistentCacheDataStore

    // 持久化缓存（永久，跨 App 重启保留）：海报路径、tmdbId、imdbId、演职员头像等不变字段
    // 缓存 key 已含语言后缀（如 "12345_zh-CN"），不同语言的海报/标题分别存储
    // maxSize 放大到 1000:覆盖超大 watchlist(>1000 条)+ 已看历史,
    // loadFromDisk 调 super.put 触发 LRU 淘汰,若 maxSize 小于磁盘条目数会导致第二次启动仍走网络
    private val movieDetailCache = persistentTtlCache<TmdbMovieDetail?>(
        TTL_DETAIL, 1000, persistentDataStore, json, "movie_detail_v1", persistentScope
    )
    private val tvDetailCache = persistentTtlCache<TmdbTvDetail?>(
        TTL_DETAIL, 1000, persistentDataStore, json, "tv_detail_v1", persistentScope
    )
    private val creditsCache = persistentTtlCache<TmdbCreditsResponse?>(
        TTL_CREDITS, 500, persistentDataStore, json, "credits_v1", persistentScope
    )
    private val personDetailCache = persistentTtlCache<TmdbPerson?>(
        TTL_PERSON, 500, persistentDataStore, json, "person_detail_v1", persistentScope
    )
    // 别名（alternative_titles）持久化缓存：中文译名等不变字段，跨 App 重启复用，
    // 避免每个 watchlist 条目每次启动都重新请求 alternative_titles 接口
    private val movieAltTitlesCache = persistentTtlCache<TmdbAlternativeTitlesResponse>(
        TTL_DETAIL, 1000, persistentDataStore, json, "movie_alt_titles_v1", persistentScope
    )
    private val tvAltTitlesCache = persistentTtlCache<TmdbAlternativeTitlesResponse>(
        TTL_DETAIL, 1000, persistentDataStore, json, "tv_alt_titles_v1", persistentScope
    )

    // 持久化缓存（6 小时）：发现页 TMDB 列表类栏目，跨 App 重启保留
    private val popularMoviesCache = persistentTtlCache<List<TmdbSearchResult>>(
        TTL_LISTS, 10, persistentDataStore, json, "popular_movies_v1", persistentScope
    )
    private val upcomingMoviesCache = persistentTtlCache<List<TmdbSearchResult>>(
        TTL_LISTS, 10, persistentDataStore, json, "upcoming_movies_v1", persistentScope
    )
    private val topRatedMoviesCache = persistentTtlCache<List<TmdbSearchResult>>(
        TTL_LISTS, 10, persistentDataStore, json, "top_rated_movies_v1", persistentScope
    )
    private val trendingMoviesCache = persistentTtlCache<List<TmdbSearchResult>>(
        TTL_LISTS, 10, persistentDataStore, json, "trending_movies_v1", persistentScope
    )

    // 内存缓存（短期，App 进程内有效）
    private val movieTitleCache = TtlCache<String>(TTL_DETAIL, maxSize = 100)
    private val tvTitleCache = TtlCache<String>(TTL_DETAIL, maxSize = 100)
    private val searchMovieCache = TtlCache<TmdbSearchResult?>(TTL_SEARCH, maxSize = 100)
    // 人物作品缓存：TMDB 一次性返回全部作品，这里缓存按 vote_average 降序排列后的完整列表，按 personId 分页切片
    private val personMovieCreditsCache = TtlCache<List<TmdbPersonMovieCredit>>(TTL_PERSON, maxSize = 30)
    private val personTvCreditsCache = TtlCache<List<TmdbPersonTvCredit>>(TTL_PERSON, maxSize = 30)
    // 人物图片缓存：避免二次进入人物详情页时重复请求图片 URL 列表
    private val personImagesCache = TtlCache<List<String>>(TTL_PERSON, maxSize = 30)
    private val personTaggedImagesCache = TtlCache<List<String>>(TTL_PERSON, maxSize = 30)
    private val reviewsCache = TtlCache<TmdbReviewsResponse?>(TTL_REVIEWS, maxSize = 50)
    // 搜索人物/多类型搜索结果缓存（10 分钟），避免重复搜索同关键词时重复请求
    private val searchPersonCache = TtlCache<List<TmdbPersonSearchResult>>(TTL_SEARCH, maxSize = 50)
    private val searchMultiCache = TtlCache<TmdbMultiSearchResponse?>(TTL_SEARCH, maxSize = 50)

    /** 持久化缓存列表，供 Application 启动时批量加载 */
    val persistentCaches: List<PersistentTtlCache<*>> get() = listOf(
        movieDetailCache, tvDetailCache, creditsCache, personDetailCache,
        movieAltTitlesCache, tvAltTitlesCache,
        popularMoviesCache, upcomingMoviesCache, topRatedMoviesCache, trendingMoviesCache
    )

    /** 影视数据持久化缓存（详情/演职员/人物/别名 + 列表 6h），用于设置页按类目清除 */
    val mediaDataCaches: List<PersistentTtlCache<*>> get() = listOf(
        movieDetailCache, tvDetailCache, creditsCache, personDetailCache,
        movieAltTitlesCache, tvAltTitlesCache,
        popularMoviesCache, upcomingMoviesCache, topRatedMoviesCache, trendingMoviesCache
    )

    data class MovieEnrichment(
        val posterUrl: String?,
        val chineseTitle: String,
        val originalTitle: String = "",
        val overview: String,
        val genres: String,
        val year: Int?,
        val rating: Double,
        val runtime: Int? = null,
        val releaseDate: String = "",
        val country: String = "",
        val status: String = "",
        val collectionId: Int? = null,
        val imdbId: String? = null
    )

    data class TvEnrichment(
        val posterUrl: String?,
        val chineseTitle: String,
        val originalTitle: String = "",
        val overview: String,
        val genres: String,
        val year: Int?,
        val rating: Double,
        val episodeRunTime: Int? = null,
        val releaseDate: String = "",
        val country: String = "",
        val status: String = "",
        val imdbId: String? = null
    )

    suspend fun enrichMovie(tmdbId: Int, originalTitle: String, year: Int?): MovieEnrichment {
        val key = langKey(tmdbId)
        val tmdbLang = getTmdbLanguage()
        val cached = movieDetailCache.get(key)
        if (cached != null) {
            val chineseTitle = movieTitleCache.getOrPut(key) {
                resolveMovieChineseTitle(tmdbId, originalTitle, cached)
            }
            return MovieEnrichment(
                posterUrl = cached.poster_path?.let { "$IMAGE_BASE_URL$it" },
                chineseTitle = chineseTitle,
                originalTitle = cached.original_title,
                overview = cached.overview ?: "",
                genres = cached.genres?.joinToString(" · ") { it.name } ?: "",
                year = cached.release_date?.take(4)?.toIntOrNull() ?: year,
                rating = cached.vote_average,
                runtime = cached.runtime,
                releaseDate = cached.release_date ?: "",
                country = cached.production_countries.map { codeToCountryName(it.iso_3166_1, tmdbLang) }.joinToString(" · "),
                status = cached.status,
                collectionId = cached.belongs_to_collection?.id,
                imdbId = cached.imdb_id
            )
        }
        // 内存未命中：等待磁盘加载完成后再查一次，避免 loadFromDisk 未完成时误判为缓存未命中
        movieDetailCache.awaitLoaded()
        val cachedAfterLoad = movieDetailCache.get(key)
        if (cachedAfterLoad != null) {
            val chineseTitle = movieTitleCache.getOrPut(key) {
                resolveMovieChineseTitle(tmdbId, originalTitle, cachedAfterLoad)
            }
            return MovieEnrichment(
                posterUrl = cachedAfterLoad.poster_path?.let { "$IMAGE_BASE_URL$it" },
                chineseTitle = chineseTitle,
                originalTitle = cachedAfterLoad.original_title,
                overview = cachedAfterLoad.overview ?: "",
                genres = cachedAfterLoad.genres?.joinToString(" · ") { it.name } ?: "",
                year = cachedAfterLoad.release_date?.take(4)?.toIntOrNull() ?: year,
                rating = cachedAfterLoad.vote_average,
                runtime = cachedAfterLoad.runtime,
                releaseDate = cachedAfterLoad.release_date ?: "",
                country = cachedAfterLoad.production_countries.map { codeToCountryName(it.iso_3166_1, tmdbLang) }.joinToString(" · "),
                status = cachedAfterLoad.status,
                collectionId = cachedAfterLoad.belongs_to_collection?.id,
                imdbId = cachedAfterLoad.imdb_id
            )
        }

        return try {
            val response = tmdbApiService.getMovieDetail(tmdbId, language = tmdbLang)
            if (response.isSuccessful) {
                val detail = response.body() ?: return fallbackMovie(originalTitle, year)
                movieDetailCache.put(key, detail)
                val chineseTitle = resolveMovieChineseTitle(tmdbId, originalTitle, detail)
                movieTitleCache.put(key, chineseTitle)
                MovieEnrichment(
                    posterUrl = detail.poster_path?.let { "$IMAGE_BASE_URL$it" },
                    chineseTitle = chineseTitle,
                    originalTitle = detail.original_title,
                    overview = detail.overview ?: "",
                    genres = detail.genres?.joinToString(" · ") { it.name } ?: "",
                    year = detail.release_date?.take(4)?.toIntOrNull() ?: year,
                    rating = detail.vote_average,
                    runtime = detail.runtime,
                    releaseDate = detail.release_date ?: "",
                    country = detail.production_countries.map { codeToCountryName(it.iso_3166_1, tmdbLang) }.joinToString(" · "),
                    status = detail.status,
                    collectionId = detail.belongs_to_collection?.id,
                    imdbId = detail.imdb_id
                )
            } else {
                fallbackMovie(originalTitle, year)
            }
        } catch (e: Exception) {
            android.util.Log.w("TmdbRepo", "enrichMovie($tmdbId) failed: ${e.message}")
            fallbackMovie(originalTitle, year)
        }
    }

    suspend fun enrichTv(tmdbId: Int, originalName: String, year: Int?): TvEnrichment {
        val key = langKey(tmdbId)
        val tmdbLang = getTmdbLanguage()
        val cached = tvDetailCache.get(key)
        if (cached != null) {
            val chineseTitle = tvTitleCache.getOrPut(key) {
                resolveTvChineseTitle(tmdbId, originalName, cached)
            }
            return TvEnrichment(
                posterUrl = cached.poster_path?.let { "$IMAGE_BASE_URL$it" },
                chineseTitle = chineseTitle,
                originalTitle = cached.original_name,
                overview = cached.overview ?: "",
                genres = cached.genres?.joinToString(" · ") { it.name } ?: "",
                year = cached.first_air_date?.take(4)?.toIntOrNull() ?: year,
                rating = cached.vote_average,
                episodeRunTime = cached.episode_run_time?.firstOrNull(),
                releaseDate = cached.first_air_date ?: "",
                country = cached.origin_country.map { codeToCountryName(it, tmdbLang) }.joinToString(" · "),
                status = cached.status,
                imdbId = cached.imdb_id
            )
        }
        // 内存未命中：等待磁盘加载完成后再查一次，避免 loadFromDisk 未完成时误判为缓存未命中
        tvDetailCache.awaitLoaded()
        val cachedAfterLoad = tvDetailCache.get(key)
        if (cachedAfterLoad != null) {
            val chineseTitle = tvTitleCache.getOrPut(key) {
                resolveTvChineseTitle(tmdbId, originalName, cachedAfterLoad)
            }
            return TvEnrichment(
                posterUrl = cachedAfterLoad.poster_path?.let { "$IMAGE_BASE_URL$it" },
                chineseTitle = chineseTitle,
                originalTitle = cachedAfterLoad.original_name,
                overview = cachedAfterLoad.overview ?: "",
                genres = cachedAfterLoad.genres?.joinToString(" · ") { it.name } ?: "",
                year = cachedAfterLoad.first_air_date?.take(4)?.toIntOrNull() ?: year,
                rating = cachedAfterLoad.vote_average,
                episodeRunTime = cachedAfterLoad.episode_run_time?.firstOrNull(),
                releaseDate = cachedAfterLoad.first_air_date ?: "",
                country = cachedAfterLoad.origin_country.map { codeToCountryName(it, tmdbLang) }.joinToString(" · "),
                status = cachedAfterLoad.status,
                imdbId = cachedAfterLoad.imdb_id
            )
        }

        return try {
            val response = tmdbApiService.getTvDetail(tmdbId, language = tmdbLang)
            if (response.isSuccessful) {
                val detail = response.body() ?: return fallbackTv(originalName, year)
                tvDetailCache.put(key, detail)
                val chineseTitle = resolveTvChineseTitle(tmdbId, originalName, detail)
                tvTitleCache.put(key, chineseTitle)
                TvEnrichment(
                    posterUrl = detail.poster_path?.let { "$IMAGE_BASE_URL$it" },
                    chineseTitle = chineseTitle,
                    originalTitle = detail.original_name,
                    overview = detail.overview ?: "",
                    genres = detail.genres?.joinToString(" · ") { it.name } ?: "",
                    year = detail.first_air_date?.take(4)?.toIntOrNull() ?: year,
                    rating = detail.vote_average,
                    episodeRunTime = detail.episode_run_time?.firstOrNull(),
                    releaseDate = detail.first_air_date ?: "",
                    country = detail.origin_country.map { codeToCountryName(it, tmdbLang) }.joinToString(" · "),
                    status = detail.status,
                    imdbId = detail.imdb_id
                )
            } else {
                fallbackTv(originalName, year)
            }
        } catch (e: Exception) {
            android.util.Log.w("TmdbRepo", "enrichTv($tmdbId) failed: ${e.message}")
            fallbackTv(originalName, year)
        }
    }

    private suspend fun resolveMovieChineseTitle(tmdbId: Int, originalTitle: String, detail: TmdbMovieDetail): String {
        // TMDB 已按当前 language(zh-CN)本地化 title 字段,非空就直接用
        // (原 != original_title 判断对中国本土影视失效:title 和 original_title 都是中文,相等被误判为未本地化)
        if (detail.title.isNotEmpty()) {
            return detail.title
        }
        // 别名持久化缓存:中文译名等不变字段,跨 App 重启复用
        val altKey = langKey(tmdbId)
        movieAltTitlesCache.get(altKey)?.let { cached ->
            val cnTitle = cached.titles.firstOrNull { it.iso_3166_1 == "CN" && it.title.isNotEmpty() }?.title
            return cnTitle ?: originalTitle
        }
        // 缓存未命中时等待磁盘加载完成,避免 loadFromDisk 未完成时误判
        movieAltTitlesCache.awaitLoaded()
        movieAltTitlesCache.get(altKey)?.let { cached ->
            val cnTitle = cached.titles.firstOrNull { it.iso_3166_1 == "CN" && it.title.isNotEmpty() }?.title
            return cnTitle ?: originalTitle
        }
        return try {
            val altResponse = tmdbApiService.getMovieAlternativeTitles(tmdbId, country = getTmdbCountry())
            if (altResponse.isSuccessful) {
                val body = altResponse.body()
                if (body != null) movieAltTitlesCache.put(altKey, body)
                val cnTitle = body?.titles?.firstOrNull {
                    it.iso_3166_1 == "CN" && it.title.isNotEmpty()
                }?.title
                cnTitle ?: originalTitle
            } else originalTitle
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            originalTitle
        }
    }

    private suspend fun resolveTvChineseTitle(tmdbId: Int, originalName: String, detail: TmdbTvDetail): String {
        // TMDB 已按当前 language(zh-CN)本地化 name 字段,非空就直接用(原因同上)
        if (detail.name.isNotEmpty()) {
            return detail.name
        }
        // 别名持久化缓存:中文译名等不变字段,跨 App 重启复用
        val altKey = langKey(tmdbId)
        tvAltTitlesCache.get(altKey)?.let { cached ->
            val cnTitle = cached.titles.firstOrNull { it.iso_3166_1 == "CN" && it.title.isNotEmpty() }?.title
            return cnTitle ?: originalName
        }
        // 缓存未命中时等待磁盘加载完成,避免 loadFromDisk 未完成时误判
        tvAltTitlesCache.awaitLoaded()
        tvAltTitlesCache.get(altKey)?.let { cached ->
            val cnTitle = cached.titles.firstOrNull { it.iso_3166_1 == "CN" && it.title.isNotEmpty() }?.title
            return cnTitle ?: originalName
        }
        return try {
            val altResponse = tmdbApiService.getTvAlternativeTitles(tmdbId, country = getTmdbCountry())
            if (altResponse.isSuccessful) {
                val body = altResponse.body()
                if (body != null) tvAltTitlesCache.put(altKey, body)
                val cnTitle = body?.titles?.firstOrNull {
                    it.iso_3166_1 == "CN" && it.title.isNotEmpty()
                }?.title
                cnTitle ?: originalName
            } else originalName
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            originalName
        }
    }

    private fun fallbackMovie(originalTitle: String, year: Int?) = MovieEnrichment(
        posterUrl = null,
        chineseTitle = originalTitle,
        overview = "",
        genres = "",
        year = year,
        rating = 0.0
    )

    private fun fallbackTv(originalName: String, year: Int?) = TvEnrichment(
        posterUrl = null,
        chineseTitle = originalName,
        overview = "",
        genres = "",
        year = year,
        rating = 0.0
    )

    suspend fun getCredits(tmdbId: Int, mediaType: MediaType): TmdbCreditsResponse? {
        val key = langKey(tmdbId)
        return creditsCache.getOrAwait(key) {
            try {
                val response = when (mediaType) {
                    MediaType.MOVIE -> tmdbApiService.getMovieCredits(tmdbId, language = getTmdbLanguage())
                    MediaType.SHOW -> tmdbApiService.getCredits(tmdbId, language = getTmdbLanguage())
                    MediaType.PERSON -> tmdbApiService.getMovieCredits(tmdbId, language = getTmdbLanguage()) // fallback
                    MediaType.DISK -> return@getOrAwait null
                }
                if (response.isSuccessful) {
                    response.body()?.also { creditsCache.put(key, it) }
                } else null
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun getReviews(tmdbId: Int, mediaType: MediaType, page: Int = 1): TmdbReviewsResponse? {
        val key = langKey("${tmdbId}_${mediaType.name}_$page")
        return reviewsCache.getOrAwait(key) {
            try {
                val response = when (mediaType) {
                    MediaType.MOVIE -> tmdbApiService.getMovieReviews(tmdbId, page)
                    MediaType.SHOW -> tmdbApiService.getTvReviews(tmdbId, page)
                    MediaType.PERSON -> tmdbApiService.getMovieReviews(tmdbId, page) // fallback
                    MediaType.DISK -> return@getOrAwait null
                }
                if (response.isSuccessful) {
                    response.body()?.also { reviewsCache.put(key, it) }
                } else null
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun getPersonDetail(personId: Int): TmdbPerson? {
        val key = langKey(personId)
        return personDetailCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.getPersonDetail(personId, language = getTmdbLanguage())
                if (response.isSuccessful) {
                    response.body()?.also { personDetailCache.put(key, it) }
                } else null
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun getPersonMovieCredits(personId: Int, page: Int = 1): PersonCreditsPage<TmdbPersonMovieCredit> {
        val key = langKey(personId)
        val full = try {
            personMovieCreditsCache.get(key) ?: run {
                val response = tmdbApiService.getPersonMovieCredits(personId, language = getTmdbLanguage(), page = 1)
                val result = response.body()?.cast?.sortedByDescending { it.vote_average } ?: emptyList()
                personMovieCreditsCache.put(key, result)
                result
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
        val start = (page - 1) * PERSON_CREDITS_PAGE_SIZE
        val slice = full.drop(start).take(PERSON_CREDITS_PAGE_SIZE)
        val hasMore = start + PERSON_CREDITS_PAGE_SIZE < full.size
        return PersonCreditsPage(slice, hasMore)
    }

    suspend fun getPersonTvCredits(personId: Int, page: Int = 1): PersonCreditsPage<TmdbPersonTvCredit> {
        val key = langKey(personId)
        val full = try {
            personTvCreditsCache.get(key) ?: run {
                val response = tmdbApiService.getPersonTvCredits(personId, language = getTmdbLanguage(), page = 1)
                val result = response.body()?.cast?.sortedByDescending { it.vote_average } ?: emptyList()
                personTvCreditsCache.put(key, result)
                result
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
        val start = (page - 1) * PERSON_CREDITS_PAGE_SIZE
        val slice = full.drop(start).take(PERSON_CREDITS_PAGE_SIZE)
        val hasMore = start + PERSON_CREDITS_PAGE_SIZE < full.size
        return PersonCreditsPage(slice, hasMore)
    }

    fun buildProfileUrl(profilePath: String?): String? {
        return profilePath?.let { "$IMAGE_BASE_URL$it" }
    }

    /** 将 ISO 3166-1 alpha-2 国家代码转换为当前应用语言的国家名称 */
    @Suppress("DEPRECATION")
    private fun codeToCountryName(code: String, tmdbLang: String): String {
        if (code.length != 2) return code
        return try {
            val lang = when {
                tmdbLang.startsWith("zh") -> Locale.SIMPLIFIED_CHINESE
                tmdbLang.startsWith("en") -> Locale.ENGLISH
                tmdbLang.startsWith("ja") -> Locale.JAPANESE
                tmdbLang.startsWith("ko") -> Locale.KOREAN
                else -> Locale.SIMPLIFIED_CHINESE
            }
            Locale(lang.language, code).displayCountry.ifEmpty { code }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            code
        }
    }

    // 通过标题搜索电影，返回第一个匹配结果
    suspend fun searchMovie(query: String): TmdbSearchResult? {
        val key = langKey(query.trim())
        return searchMovieCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.searchMovie(query = query, language = getTmdbLanguage())
                if (response.isSuccessful) {
                    response.body()?.results?.firstOrNull()?.also { searchMovieCache.put(key, it) }
                } else null
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                null
            }
        }
    }

    /** 搜索人物（TMDB，支持中文名搜索） */
    suspend fun searchPerson(query: String): List<TmdbPersonSearchResult> {
        val key = langKey(query.trim())
        return searchPersonCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.searchPerson(query = query, language = getTmdbLanguage())
                if (response.isSuccessful) {
                    response.body()?.results ?: emptyList()
                } else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** 多类型搜索（TMDB，同时搜索电影和剧集，支持中文名） */
    suspend fun searchMulti(query: String): TmdbMultiSearchResponse? {
        val key = langKey(query.trim())
        return searchMultiCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.searchMulti(query = query, language = getTmdbLanguage())
                if (response.isSuccessful) response.body() else null
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                null
            }
        }
    }

    /** 趋势电影（今日/本周） */
    suspend fun getTrendingMovies(timeWindow: String = "day"): List<TmdbSearchResult> {
        val key = langKey(timeWindow)
        return trendingMoviesCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.getTrendingMovies(
                    timeWindow = timeWindow,
                    language = getTmdbLanguage()
                )
                if (response.isSuccessful) {
                    val results = response.body()?.results ?: emptyList()
                    trendingMoviesCache.put(key, results)
                    results
                } else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** 趋势电影（今日/本周，分页）—— 用于"查看全部"Sheet，绕过缓存 */
    suspend fun getTrendingMovies(timeWindow: String = "day", page: Int): List<TmdbSearchResult> {
        return try {
            val response = tmdbApiService.getTrendingMovies(
                timeWindow = timeWindow,
                language = getTmdbLanguage(),
                page = page
            )
            if (response.isSuccessful) {
                response.body()?.results ?: emptyList()
            } else emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
    }

    /** 热门电影 */
    suspend fun getPopularMovies(): List<TmdbSearchResult> {
        val key = langKey("default")
        return popularMoviesCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.getPopularMovies(language = getTmdbLanguage())
                if (response.isSuccessful) {
                    val results = response.body()?.results ?: emptyList()
                    popularMoviesCache.put(key, results)
                    results
                } else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** 热门电影（分页） */
    suspend fun getPopularMovies(page: Int): List<TmdbSearchResult> {
        return try {
            val response = tmdbApiService.getPopularMovies(language = getTmdbLanguage(), page = page)
            if (response.isSuccessful) {
                response.body()?.results ?: emptyList()
            } else emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
    }

    /** 即将上映 */
    suspend fun getUpcomingMovies(): List<TmdbSearchResult> {
        val key = langKey("default")
        return upcomingMoviesCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.getUpcomingMovies(language = getTmdbLanguage())
                if (response.isSuccessful) {
                    val results = response.body()?.results ?: emptyList()
                    upcomingMoviesCache.put(key, results)
                    results
                } else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** 即将上映（分页） */
    suspend fun getUpcomingMovies(page: Int): List<TmdbSearchResult> {
        return try {
            val response = tmdbApiService.getUpcomingMovies(language = getTmdbLanguage(), page = page)
            if (response.isSuccessful) {
                response.body()?.results ?: emptyList()
            } else emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
    }

    /** 高分电影（未登录时的推荐降级） */
    suspend fun getTopRatedMovies(): List<TmdbSearchResult> {
        val key = langKey("default")
        return topRatedMoviesCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.getTopRatedMovies(language = getTmdbLanguage())
                if (response.isSuccessful) {
                    val results = response.body()?.results ?: emptyList()
                    topRatedMoviesCache.put(key, results)
                    results
                } else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** 高分电影（分页） */
    suspend fun getTopRatedMovies(page: Int): List<TmdbSearchResult> {
        return try {
            val response = tmdbApiService.getTopRatedMovies(language = getTmdbLanguage(), page = page)
            if (response.isSuccessful) {
                response.body()?.results ?: emptyList()
            } else emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
    }

    /** 获取电影详情（含海报路径），优先读缓存 */
    suspend fun getMovieDetail(movieId: Int): TmdbMovieDetail? {
        val key = langKey(movieId)
        return movieDetailCache.getOrAwait(key) {
            // 飞行中未命中：等待磁盘加载完成后再查一次，避免 loadFromDisk 未完成时误判为缓存未命中
            movieDetailCache.awaitLoaded()
            movieDetailCache.get(key)?.let { return@getOrAwait it }
            try {
                val response = tmdbApiService.getMovieDetail(movieId, language = getTmdbLanguage())
                if (response.isSuccessful) {
                    val detail = response.body()
                    detail?.let { movieDetailCache.put(key, it) }
                    detail
                } else null
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                null
            }
        }
    }

    /** 获取电视剧详情（用于补全 imdb_id 等），优先读缓存 */
    suspend fun getTvDetail(tvId: Int): TmdbTvDetail? {
        val key = langKey(tvId)
        return tvDetailCache.getOrAwait(key) {
            // 飞行中未命中：等待磁盘加载完成后再查一次，避免 loadFromDisk 未完成时误判为缓存未命中
            tvDetailCache.awaitLoaded()
            tvDetailCache.get(key)?.let { return@getOrAwait it }
            try {
                val response = tmdbApiService.getTvDetail(tvId, language = getTmdbLanguage())
                if (response.isSuccessful) {
                    val detail = response.body()
                    detail?.let { tvDetailCache.put(key, it) }
                    detail
                } else null
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun getMovieVideos(id: Int): List<TmdbVideo> {
        return try {
            val lang = getTmdbLanguage()
            val response = tmdbApiService.getMovieVideos(id, lang)
            if (response.isSuccessful) response.body()?.results ?: emptyList()
            else emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    suspend fun getTvVideos(id: Int): List<TmdbVideo> {
        return try {
            val lang = getTmdbLanguage()
            val response = tmdbApiService.getTvVideos(id, lang)
            if (response.isSuccessful) response.body()?.results ?: emptyList()
            else emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    suspend fun getMovieImages(id: Int): List<TmdbImage> {
        return try {
            val response = tmdbApiService.getMovieImages(id, "zh,null")
            if (response.isSuccessful) response.body()?.backdrops ?: emptyList()
            else emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    suspend fun getTvImages(id: Int): List<TmdbImage> {
        return try {
            val response = tmdbApiService.getTvImages(id, "zh,null")
            if (response.isSuccessful) response.body()?.backdrops ?: emptyList()
            else emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    /** 获取人物图片（TMDB profiles） */
    suspend fun getPersonImages(personId: Int): List<String> {
        val key = langKey(personId)
        personImagesCache.get(key)?.let { return it }
        return try {
            val response = tmdbApiService.getPersonImages(personId)
            if (response.isSuccessful) {
                val urls = response.body()?.profiles?.map { TmdbImageUrls.H632 + it.file_path } ?: emptyList()
                personImagesCache.put(key, urls)
                urls
            } else emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    /** 获取人物被标注的图片（TMDB tagged images） */
    suspend fun getPersonTaggedImages(personId: Int): List<String> {
        val key = langKey(personId)
        personTaggedImagesCache.get(key)?.let { return it }
        return try {
            val response = tmdbApiService.getPersonTaggedImages(personId, page = 1)
            if (response.isSuccessful) {
                val urls = response.body()?.results?.map { TmdbImageUrls.W500 + it.file_path } ?: emptyList()
                personTaggedImagesCache.put(key, urls)
                urls
            } else emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    /** 获取相似电影（TMDB） */
    suspend fun getSimilarMovies(tmdbId: Int): List<TmdbSearchResult> {
        return try {
            val response = tmdbApiService.getSimilarMovies(tmdbId, language = getTmdbLanguage())
            if (response.isSuccessful) response.body()?.results ?: emptyList()
            else emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    /** 获取相似剧集（TMDB） */
    suspend fun getSimilarShows(tmdbId: Int): List<TmdbSearchResult> {
        return try {
            val response = tmdbApiService.getSimilarShows(tmdbId, language = getTmdbLanguage())
            if (response.isSuccessful) response.body()?.results ?: emptyList()
            else emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    /** 获取系列信息（TMDB） */
    suspend fun getCollection(collectionId: Int): TmdbCollectionResponse? {
        return try {
            val response = tmdbApiService.getCollection(collectionId, language = getTmdbLanguage())
            if (response.isSuccessful) response.body() else null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    /** 获取电视剧季详情（用于本地化集标题） */
    suspend fun getTvSeasonDetail(tvId: Int, seasonNumber: Int): TmdbTvSeasonDetail? {
        return try {
            val response = tmdbApiService.getTvSeasonDetail(tvId, seasonNumber, language = getTmdbLanguage())
            if (response.isSuccessful) response.body() else null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    // ========== Discover API：按维度筛选影视 ==========

    /** Discover 筛选条件 */
    data class DiscoverFilter(
        val type: DiscoverType,             // 电影/电视剧
        val genreIds: List<Int>,            // 类型（多选，TMDB OR 逻辑）
        val originCountries: List<String>,  // 地区（多选，TMDB OR 逻辑）
        val keywordIds: List<Int>,          // 标签/关键词（多选，TMDB OR 逻辑）
        val voteAverageMin: Float,          // 评分下限 0-10
        val voteAverageMax: Float,          // 评分上限 0-10
        val releaseDateStart: String?,      // 年代起始日期 yyyy-MM-dd
        val releaseDateEnd: String?,        // 年代结束日期 yyyy-MM-dd
        val sortBy: DiscoverSort,           // 排序方式
        val hideWatched: Boolean            // 仅展示未标看过
    )

    enum class DiscoverType { MOVIE, SHOW }

    enum class DiscoverSort(val value: String) {
        POPULARITY_DESC("popularity.desc"),
        RELEASE_DATE_DESC("primary_release_date.desc"),
        VOTE_AVERAGE_DESC("vote_average.desc")
    }

    /** Discover 分页结果 */
    data class DiscoverPage(
        val items: List<TmdbSearchResult>,
        val totalPages: Int,
        val totalResults: Int
    )

    /** 按筛选条件发现影视（分页） */
    suspend fun discover(filter: DiscoverFilter, page: Int): DiscoverPage {
        return try {
            val genres = filter.genreIds.joinToString(",").ifEmpty { null }
            val countries = filter.originCountries.joinToString(",").ifEmpty { null }
            val keywords = filter.keywordIds.joinToString(",").ifEmpty { null }
            // 评分下限/上限：边界值不传，避免过滤掉恰好 0 分或 10 分的条目
            val voteMin = if (filter.voteAverageMin <= 0f) null else filter.voteAverageMin
            val voteMax = if (filter.voteAverageMax >= 10f) null else filter.voteAverageMax
            // 评分筛选配合最低投票数，避免低投票数的高分片污染结果
            val voteCountGte = if (filter.voteAverageMin > 0f || filter.voteAverageMax < 10f) 50 else null

            val response = when (filter.type) {
                DiscoverType.MOVIE -> tmdbApiService.discoverMovie(
                    language = getTmdbLanguage(),
                    page = page,
                    withGenres = genres,
                    withOriginCountry = countries,
                    withKeywords = keywords,
                    voteAverageGte = voteMin,
                    voteAverageLte = voteMax,
                    voteCountGte = voteCountGte,
                    releaseDateGte = filter.releaseDateStart,
                    releaseDateLte = filter.releaseDateEnd,
                    sortBy = filter.sortBy.value,
                    includeAdult = true
                )
                DiscoverType.SHOW -> tmdbApiService.discoverTv(
                    language = getTmdbLanguage(),
                    page = page,
                    withGenres = genres,
                    withOriginCountry = countries,
                    withKeywords = keywords,
                    voteAverageGte = voteMin,
                    voteAverageLte = voteMax,
                    voteCountGte = voteCountGte,
                    airDateGte = filter.releaseDateStart,
                    airDateLte = filter.releaseDateEnd,
                    sortBy = filter.sortBy.value,
                    includeAdult = true
                )
            }
            if (response.isSuccessful) {
                val body = response.body() ?: TmdbSearchResponse()
                DiscoverPage(body.results, body.total_pages, body.total_results)
            } else DiscoverPage(emptyList(), 0, 0)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            DiscoverPage(emptyList(), 0, 0)
        }
    }
}
