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
import retrofit2.HttpException

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
        // 列表/卡片海报统一 w342：卡片显示约 318px，w342 足够且下载量比 w500 省 30-40%；
        // 详情页 header 与全屏大图在 UI 层用 swapSize 升级到 w780/original 保证清晰度
        private val IMAGE_BASE_URL = TmdbImageUrls.W342
        private const val TTL_DETAIL = Long.MAX_VALUE       // 详情（海报路径、tmdbId、imdbId 等不变字段）永久缓存
        private const val TTL_CREDITS = Long.MAX_VALUE      // 演职员信息永久缓存（头像、姓名、角色不变）
        private const val TTL_SEARCH = 60 * 60 * 1000L       // 搜索/ID转换 1 小时
        private const val TTL_PERSON = Long.MAX_VALUE       // 人物信息永久缓存（姓名、头像路径、生日等不变）
        private const val TTL_REVIEWS = 10 * 60 * 1000L      // 评论 10 分钟
        private const val TTL_LISTS = 6 * 60 * 60 * 1000L   // 列表类 6 小时（榜单数据更新不频繁）
        private const val PERSON_CREDITS_PAGE_SIZE = 20      // 人物作品每页数量
        // Discover 评分筛选的最低投票数：只有几票的条目评分没有代表性，会挤掉真正的高分片
        private const val DISCOVER_VOTE_COUNT_MIN_FILTER = 50
        // Discover 按评分排序的最低投票数：门槛比筛选高，否则榜首全是 1 票的满分冷门片
        private const val DISCOVER_VOTE_COUNT_MIN_SORT = 200
    }

    private data class TmdbLocale(
        val language: String,
        val country: String
    )

    /**
     * 根据当前应用/系统语言统一生成 TMDB 的 language 与 alternative_titles country。
     *
     * 两个参数和缓存 key 都从同一个映射得到，避免详情请求与备用标题筛选使用不同地区。
     * 读取 StateFlow.value 保持同步，供 [langKey] 和同步 peek 使用；system 模式则读取当前
     * 系统 Locale，不在这里挂起或发起网络请求。
     */
    private fun getTmdbLocale(): TmdbLocale {
        return when (languageStorage.language.value.trim().lowercase(Locale.ROOT)) {
            LanguageStorage.LANGUAGE_CHINESE -> TmdbLocale("zh-CN", "CN")
            LanguageStorage.LANGUAGE_ENGLISH -> TmdbLocale("en-US", "US")
            LanguageStorage.LANGUAGE_JAPANESE -> TmdbLocale("ja-JP", "JP")
            LanguageStorage.LANGUAGE_KOREAN -> TmdbLocale("ko-KR", "KR")
            LanguageStorage.LANGUAGE_SYSTEM -> getSystemTmdbLocale()
            else -> TmdbLocale("zh-CN", "CN") // 保留未知配置的历史中文回退
        }
    }

    /** 将系统 Locale 转为 TMDB 使用的 language-country 与 alternative_titles country。 */
    private fun getSystemTmdbLocale(): TmdbLocale {
        val systemLocale = Locale.getDefault()
        val language = systemLocale.language.trim().lowercase(Locale.ROOT)
        if (language.isEmpty()) return TmdbLocale("zh-CN", "CN")

        val country = systemLocale.country.trim().uppercase(Locale.ROOT)
            .ifEmpty { defaultCountryForLanguage(language) }
        val tmdbLanguage = if (country.isEmpty()) language else "$language-$country"
        return TmdbLocale(tmdbLanguage, country)
    }

    /** 无国家码的系统 Locale 使用常见语言的默认地区；有国家码时始终优先使用系统值。 */
    private fun defaultCountryForLanguage(language: String): String = when (language) {
        "zh" -> "CN"
        "en" -> "US"
        "ja" -> "JP"
        "ko" -> "KR"
        "fr" -> "FR"
        "de" -> "DE"
        "es" -> "ES"
        "it" -> "IT"
        "pt" -> "BR"
        "ru" -> "RU"
        "ar" -> "SA"
        "th" -> "TH"
        "vi" -> "VN"
        else -> ""
    }

    private fun getTmdbLanguage(): String = getTmdbLocale().language

    /** 构造带语言后缀的缓存 key，避免切换语言后命中旧语言缓存；格式保持兼容。 */
    private fun langKey(id: Any): String = "${id}_${getTmdbLanguage()}"

    /**
     * TMDB 图片接口的 include_image_language 参数。
     *
     * 图片接口的 language 参数与详情接口不同：它接受 "zh,null" 这种"主语言,null"格式，
     * 表示"返回主语言图片 + 原始语言图片"。若硬编码 "zh,null" 会导致非中文用户剧照请求失效，
     * 且因缓存 key 含语言后缀，同一张剧照会在不同语言下各存一份。
     *
     * 返回值示例：zh-CN,null / en-US,null / ja-JP,null / ko-KR,null
     */
    private fun getTmdbImageLanguage(): String = "${getTmdbLanguage()},null"

    /** 返回与当前 TMDB Locale 对应的 alternative_titles country。 */
    private fun getTmdbCountry(): String = getTmdbLocale().country

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
    // 别名（alternative_titles）持久化缓存：各语言本地化标题等不变字段，跨 App 重启复用，
    // 避免每个 watchlist 条目每次启动都重新请求 alternative_titles 接口
    private val movieAltTitlesCache = persistentTtlCache<TmdbAlternativeTitlesResponse>(
        TTL_DETAIL, 1000, persistentDataStore, json, "movie_alt_titles_v1", persistentScope
    )
    private val tvAltTitlesCache = persistentTtlCache<TmdbAlternativeTitlesResponse>(
        TTL_DETAIL, 1000, persistentDataStore, json, "tv_alt_titles_v1", persistentScope
    )
    // 预告片（YouTube key 等不变字段）永久持久化缓存，避免二次进入详情页重新请求
    private val movieVideosCache = persistentTtlCache<List<TmdbVideo>>(
        TTL_DETAIL, 500, persistentDataStore, json, "movie_videos_v1", persistentScope
    )
    private val tvVideosCache = persistentTtlCache<List<TmdbVideo>>(
        TTL_DETAIL, 500, persistentDataStore, json, "tv_videos_v1", persistentScope
    )
    // 剧照（backdrop file_path 不变）永久持久化缓存
    private val movieImagesCache = persistentTtlCache<List<TmdbImage>>(
        TTL_DETAIL, 500, persistentDataStore, json, "movie_images_v1", persistentScope
    )
    private val tvImagesCache = persistentTtlCache<List<TmdbImage>>(
        TTL_DETAIL, 500, persistentDataStore, json, "tv_images_v1", persistentScope
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

    // 分页列表内存缓存（6 小时，不落盘避免 DataStore 膨胀）：
    // "查看全部"Sheet 翻页时复用已加载页，避免每次翻页重复请求同一页
    private val trendingPagesCache = TtlCache<List<TmdbSearchResult>>(TTL_LISTS, maxSize = 50)
    private val popularPagesCache = TtlCache<List<TmdbSearchResult>>(TTL_LISTS, maxSize = 50)
    private val upcomingPagesCache = TtlCache<List<TmdbSearchResult>>(TTL_LISTS, maxSize = 50)
    private val topRatedPagesCache = TtlCache<List<TmdbSearchResult>>(TTL_LISTS, maxSize = 50)

    // 内存缓存（短期，App 进程内有效）
    private val movieTitleCache = TtlCache<String>(TTL_DETAIL, maxSize = 100)
    private val tvTitleCache = TtlCache<String>(TTL_DETAIL, maxSize = 100)
    private val searchMovieCache = TtlCache<TmdbSearchResult?>(TTL_SEARCH, maxSize = 100)
    private val searchTvCache = TtlCache<TmdbSearchResult?>(TTL_SEARCH, maxSize = 100)
    // 人物作品缓存：TMDB 一次性返回全部作品，这里缓存按 vote_average 降序排列后的完整列表，按 personId 分页切片
    private val personMovieCreditsCache = TtlCache<List<TmdbPersonMovieCredit>>(TTL_PERSON, maxSize = 30)
    private val personTvCreditsCache = TtlCache<List<TmdbPersonTvCredit>>(TTL_PERSON, maxSize = 30)
    // 人物图片缓存：避免二次进入人物详情页时重复请求图片 URL 列表
    private val personImagesCache = TtlCache<List<String>>(TTL_PERSON, maxSize = 30)
    private val personTaggedImagesCache = TtlCache<List<String>>(TTL_PERSON, maxSize = 30)
    private val similarMoviesCache = TtlCache<List<TmdbSearchResult>>(TTL_LISTS, maxSize = 100)
    private val similarShowsCache = TtlCache<List<TmdbSearchResult>>(TTL_LISTS, maxSize = 100)
    private val collectionCache = TtlCache<Result<TmdbCollectionResponse?>>(TTL_LISTS, maxSize = 50)
    private val reviewsCache = TtlCache<TmdbReviewsResponse?>(TTL_REVIEWS, maxSize = 50)
    // 搜索人物/多类型搜索结果缓存（10 分钟），避免重复搜索同关键词时重复请求
    private val searchPersonCache = TtlCache<List<TmdbPersonSearchResult>>(TTL_SEARCH, maxSize = 50)
    private val searchMultiCache = TtlCache<TmdbMultiSearchResponse?>(TTL_SEARCH, maxSize = 50)

    /** 持久化缓存列表，供 Application 启动时批量加载 */
    val persistentCaches: List<PersistentTtlCache<*>> get() = listOf(
        movieDetailCache, tvDetailCache, creditsCache, personDetailCache,
        movieAltTitlesCache, tvAltTitlesCache,
        movieVideosCache, tvVideosCache, movieImagesCache, tvImagesCache,
        popularMoviesCache, upcomingMoviesCache, topRatedMoviesCache, trendingMoviesCache
    )

    /** 影视数据持久化缓存（详情/演职员/人物/别名/预告片/剧照 + 列表 6h），用于设置页按类目清除 */
    val mediaDataCaches: List<PersistentTtlCache<*>> get() = listOf(
        movieDetailCache, tvDetailCache, creditsCache, personDetailCache,
        movieAltTitlesCache, tvAltTitlesCache,
        movieVideosCache, tvVideosCache, movieImagesCache, tvImagesCache,
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

    /** 把已缓存的 TMDB 电影详情映射为富化结果（三处调用点共用：内存命中/磁盘命中/同步 peek）。 */
    private fun buildMovieEnrichment(
        detail: TmdbMovieDetail,
        chineseTitle: String,
        year: Int?
    ): MovieEnrichment {
        val tmdbLang = getTmdbLanguage()
        return MovieEnrichment(
            posterUrl = detail.poster_path?.let { "$IMAGE_BASE_URL$it" },
            chineseTitle = chineseTitle,
            originalTitle = detail.original_title,
            overview = detail.overview,
            genres = detail.genres.joinToString(" · ") { it.name },
            year = detail.release_date.take(4).toIntOrNull() ?: year,
            rating = detail.vote_average,
            runtime = detail.runtime,
            releaseDate = detail.release_date,
            country = detail.production_countries.map { codeToCountryName(it.iso_3166_1, tmdbLang) }.joinToString(" · "),
            status = detail.status,
            collectionId = detail.belongs_to_collection?.id,
            imdbId = detail.imdb_id
        )
    }

    /**
     * 同步读取内存缓存里的电影富化结果，未命中返回 null。
     *
     * 不挂起、不读盘、不发网络：详情页用它在首帧就填好海报与标题，
     * 避免「先空白再弹入」以及共享元素转场找不到落点。
     * 本地化标题优先用详情字段，其次读取已加载的备用标题缓存；没有可靠标题时返回 null，
     * 让调用方继续走挂起的 enrich 补全路径，而不是把原始标题误当成本地化结果。
     */
    fun peekMovieEnrichment(tmdbId: Int, originalTitle: String, year: Int? = null): MovieEnrichment? {
        if (tmdbId <= 0) return null
        val key = langKey(tmdbId)
        val detail = movieDetailCache.get(key) ?: return null
        val country = getTmdbCountry()
        val localizedTitle = detail.title.trim().takeIf { it.isNotEmpty() }
            ?: movieAltTitlesCache.get(key)?.let { findLocalizedAlternativeTitle(it, country) }
            ?: movieTitleCache.get(key)?.trim()?.takeIf { cachedTitle ->
                cachedTitle.isNotEmpty() &&
                    !cachedTitle.equals(originalTitle.trim(), ignoreCase = true) &&
                    !cachedTitle.equals(detail.original_title.trim(), ignoreCase = true)
            }
            ?: return null
        return buildMovieEnrichment(detail, localizedTitle, year)
    }

    suspend fun enrichMovie(tmdbId: Int, originalTitle: String, year: Int?): MovieEnrichment {
        val key = langKey(tmdbId)
        val cached = movieDetailCache.get(key)
        if (cached != null) {
            val chineseTitle = movieTitleCache.getOrPut(key) {
                resolveMovieChineseTitle(tmdbId, originalTitle, cached)
            }
            return buildMovieEnrichment(cached, chineseTitle, year)
        }
        // 内存未命中：等待磁盘加载完成后再查一次，避免 loadFromDisk 未完成时误判为缓存未命中
        movieDetailCache.awaitLoaded()
        val cachedAfterLoad = movieDetailCache.get(key)
        if (cachedAfterLoad != null) {
            val chineseTitle = movieTitleCache.getOrPut(key) {
                resolveMovieChineseTitle(tmdbId, originalTitle, cachedAfterLoad)
            }
            return buildMovieEnrichment(cachedAfterLoad, chineseTitle, year)
        }

        return try {
            val response = tmdbApiService.getMovieDetail(tmdbId, language = getTmdbLanguage())
            if (response.isSuccessful) {
                val detail = response.body() ?: return fallbackMovie(originalTitle, year)
                movieDetailCache.put(key, detail)
                val chineseTitle = resolveMovieChineseTitle(tmdbId, originalTitle, detail)
                movieTitleCache.put(key, chineseTitle)
                buildMovieEnrichment(detail, chineseTitle, year)
            } else {
                fallbackMovie(originalTitle, year)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("TmdbRepo", "enrichMovie($tmdbId) failed: ${e.message}")
            fallbackMovie(originalTitle, year)
        }
    }

    /** 把已缓存的 TMDB 剧集详情映射为富化结果（三处调用点共用：内存命中/磁盘命中/同步 peek）。 */
    private fun buildTvEnrichment(
        detail: TmdbTvDetail,
        chineseTitle: String,
        year: Int?
    ): TvEnrichment {
        val tmdbLang = getTmdbLanguage()
        return TvEnrichment(
            posterUrl = detail.poster_path?.let { "$IMAGE_BASE_URL$it" },
            chineseTitle = chineseTitle,
            originalTitle = detail.original_name,
            overview = detail.overview,
            genres = detail.genres.joinToString(" · ") { it.name },
            year = detail.first_air_date.take(4).toIntOrNull() ?: year,
            rating = detail.vote_average,
            episodeRunTime = detail.episode_run_time?.firstOrNull(),
            releaseDate = detail.first_air_date,
            country = detail.origin_country.map { codeToCountryName(it, tmdbLang) }.joinToString(" · "),
            status = detail.status,
            imdbId = detail.imdb_id
        )
    }

    /** 同步读取内存缓存里的剧集富化结果，未命中返回 null。语义同 [peekMovieEnrichment]。 */
    fun peekTvEnrichment(tmdbId: Int, originalName: String, year: Int? = null): TvEnrichment? {
        if (tmdbId <= 0) return null
        val key = langKey(tmdbId)
        val detail = tvDetailCache.get(key) ?: return null
        val country = getTmdbCountry()
        val localizedTitle = detail.name.trim().takeIf { it.isNotEmpty() }
            ?: tvAltTitlesCache.get(key)?.let { findLocalizedAlternativeTitle(it, country) }
            ?: tvTitleCache.get(key)?.trim()?.takeIf { cachedTitle ->
                cachedTitle.isNotEmpty() &&
                    !cachedTitle.equals(originalName.trim(), ignoreCase = true) &&
                    !cachedTitle.equals(detail.original_name.trim(), ignoreCase = true)
            }
            ?: return null
        return buildTvEnrichment(detail, localizedTitle, year)
    }

    suspend fun enrichTv(tmdbId: Int, originalName: String, year: Int?): TvEnrichment {
        val key = langKey(tmdbId)
        val cached = tvDetailCache.get(key)
        if (cached != null) {
            val chineseTitle = tvTitleCache.getOrPut(key) {
                resolveTvChineseTitle(tmdbId, originalName, cached)
            }
            return buildTvEnrichment(cached, chineseTitle, year)
        }
        // 内存未命中：等待磁盘加载完成后再查一次，避免 loadFromDisk 未完成时误判为缓存未命中
        tvDetailCache.awaitLoaded()
        val cachedAfterLoad = tvDetailCache.get(key)
        if (cachedAfterLoad != null) {
            val chineseTitle = tvTitleCache.getOrPut(key) {
                resolveTvChineseTitle(tmdbId, originalName, cachedAfterLoad)
            }
            return buildTvEnrichment(cachedAfterLoad, chineseTitle, year)
        }

        return try {
            val response = tmdbApiService.getTvDetail(tmdbId, language = getTmdbLanguage())
            if (response.isSuccessful) {
                val detail = response.body() ?: return fallbackTv(originalName, year)
                tvDetailCache.put(key, detail)
                val chineseTitle = resolveTvChineseTitle(tmdbId, originalName, detail)
                tvTitleCache.put(key, chineseTitle)
                buildTvEnrichment(detail, chineseTitle, year)
            } else {
                fallbackTv(originalName, year)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("TmdbRepo", "enrichTv($tmdbId) failed: ${e.message}")
            fallbackTv(originalName, year)
        }
    }

    /** 从 alternative_titles 响应中取当前地区的非空本地化标题。 */
    private fun findLocalizedAlternativeTitle(
        response: TmdbAlternativeTitlesResponse,
        country: String
    ): String? {
        val normalizedCountry = country.trim().uppercase(Locale.ROOT)
        if (normalizedCountry.isEmpty()) return null
        return response.titles.firstOrNull { alternativeTitle ->
            alternativeTitle.iso_3166_1.trim().equals(normalizedCountry, ignoreCase = true) &&
                alternativeTitle.title.isNotBlank()
        }?.title?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun resolveMovieChineseTitle(tmdbId: Int, originalTitle: String, detail: TmdbMovieDetail): String {
        // TMDB 已按当前 language 本地化 title 字段，非空就直接用。
        if (detail.title.isNotBlank()) {
            return detail.title.trim()
        }
        // 别名持久化缓存：按当前 country 取对应地区标题，跨 App 重启复用。
        val altKey = langKey(tmdbId)
        val country = getTmdbCountry()
        movieAltTitlesCache.get(altKey)?.let { cached ->
            return findLocalizedAlternativeTitle(cached, country) ?: originalTitle
        }
        // 缓存未命中时等待磁盘加载完成，避免 loadFromDisk 未完成时误判。
        movieAltTitlesCache.awaitLoaded()
        movieAltTitlesCache.get(altKey)?.let { cached ->
            return findLocalizedAlternativeTitle(cached, country) ?: originalTitle
        }
        return try {
            val altResponse = tmdbApiService.getMovieAlternativeTitles(tmdbId, country = country)
            if (altResponse.isSuccessful) {
                val body = altResponse.body()
                if (body != null) movieAltTitlesCache.put(altKey, body)
                findLocalizedAlternativeTitle(body ?: TmdbAlternativeTitlesResponse(), country) ?: originalTitle
            } else originalTitle
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            originalTitle
        }
    }

    private suspend fun resolveTvChineseTitle(tmdbId: Int, originalName: String, detail: TmdbTvDetail): String {
        // TMDB 已按当前 language 本地化 name 字段，非空就直接用。
        if (detail.name.isNotBlank()) {
            return detail.name.trim()
        }
        // 别名持久化缓存：按当前 country 取对应地区标题，跨 App 重启复用。
        val altKey = langKey(tmdbId)
        val country = getTmdbCountry()
        tvAltTitlesCache.get(altKey)?.let { cached ->
            return findLocalizedAlternativeTitle(cached, country) ?: originalName
        }
        // 缓存未命中时等待磁盘加载完成，避免 loadFromDisk 未完成时误判。
        tvAltTitlesCache.awaitLoaded()
        tvAltTitlesCache.get(altKey)?.let { cached ->
            return findLocalizedAlternativeTitle(cached, country) ?: originalName
        }
        return try {
            val altResponse = tmdbApiService.getTvAlternativeTitles(tmdbId, country = country)
            if (altResponse.isSuccessful) {
                val body = altResponse.body()
                if (body != null) tvAltTitlesCache.put(altKey, body)
                findLocalizedAlternativeTitle(body ?: TmdbAlternativeTitlesResponse(), country) ?: originalName
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
                    // getOrAwait 内部已 put,无需 .also { reviewsCache.put(key, it) }
                    response.body()
                } else null
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 同步读取内存缓存里的人物详情，未命中返回 null。
     *
     * 不挂起、不读盘、不发网络：人物页用它在首帧就把姓名、简介、生日、出生地填好，
     * 二次进入同一人物时不再先显示骨架屏再整块弹入。语义同 [peekMovieEnrichment]。
     */
    fun peekPersonDetail(personId: Int): TmdbPerson? {
        if (personId <= 0) return null
        return personDetailCache.get(langKey(personId))
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
        // getOrAwait 而非 get + 手动 fetch：同一人物的并发请求（例如快速返回再进入）合并成一次
        val full = try {
            personMovieCreditsCache.getOrAwait(key) {
                val response = tmdbApiService.getPersonMovieCredits(personId, language = getTmdbLanguage(), page = 1)
                response.body()?.cast?.sortedByDescending { it.vote_average } ?: emptyList()
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
            personTvCreditsCache.getOrAwait(key) {
                val response = tmdbApiService.getPersonTvCredits(personId, language = getTmdbLanguage(), page = 1)
                response.body()?.cast?.sortedByDescending { it.vote_average } ?: emptyList()
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

    // 通过标题搜索剧集，返回第一个匹配结果（与 searchMovie 同链路，仅端点不同）
    suspend fun searchTv(query: String): TmdbSearchResult? {
        val key = langKey(query.trim())
        return searchTvCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.searchTv(query = query, language = getTmdbLanguage())
                if (response.isSuccessful) {
                    response.body()?.results?.firstOrNull()?.also { searchTvCache.put(key, it) }
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

    /** 趋势电影（今日/本周，分页）—— 用于"查看全部"Sheet，每页内存缓存 6 小时 */
    suspend fun getTrendingMovies(timeWindow: String = "day", page: Int): List<TmdbSearchResult> {
        val key = langKey("${timeWindow}_p$page")
        return trendingPagesCache.getOrAwait(key) {
            try {
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

    /** 热门电影（分页，每页内存缓存 6 小时） */
    suspend fun getPopularMovies(page: Int): List<TmdbSearchResult> {
        val key = langKey("p$page")
        return popularPagesCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.getPopularMovies(language = getTmdbLanguage(), page = page)
                if (response.isSuccessful) {
                    response.body()?.results ?: emptyList()
                } else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                emptyList()
            }
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

    /** 即将上映（分页，每页内存缓存 6 小时） */
    suspend fun getUpcomingMovies(page: Int): List<TmdbSearchResult> {
        val key = langKey("p$page")
        return upcomingPagesCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.getUpcomingMovies(language = getTmdbLanguage(), page = page)
                if (response.isSuccessful) {
                    response.body()?.results ?: emptyList()
                } else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                emptyList()
            }
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

    /** 高分电影（分页，每页内存缓存 6 小时） */
    suspend fun getTopRatedMovies(page: Int): List<TmdbSearchResult> {
        val key = langKey("p$page")
        return topRatedPagesCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.getTopRatedMovies(language = getTmdbLanguage(), page = page)
                if (response.isSuccessful) {
                    response.body()?.results ?: emptyList()
                } else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                emptyList()
            }
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

    /**
     * 取当前语言下的海报路径，电影和剧集两个命名空间都覆盖。
     *
     * [mediaType] 不能省：TMDB 的 /movie/{id} 与 /tv/{id} 各自编号，同一个数字在两边是两部
     * 不同的作品。只查电影接口的话，剧集条目要么 404、要么拿到另一部片的海报——
     * 开屏台词库里有电视剧条目，这个错会直接显示成配错图的开屏。
     *
     * 走的是详情接口那两个永久缓存（key 已带语言），命中之后不再发请求；
     * 拿不到返回 null，由调用方决定退回哪张图。
     */
    suspend fun posterPath(tmdbId: Int, mediaType: MediaType): String? {
        if (tmdbId <= 0) return null
        return when (mediaType) {
            MediaType.MOVIE -> getMovieDetail(tmdbId)?.poster_path
            MediaType.SHOW -> getTvDetail(tmdbId)?.poster_path
            // 人物有头像没海报，碟片不是 TMDB 的概念
            MediaType.PERSON, MediaType.DISK -> null
        }
    }

    suspend fun getMovieVideos(id: Int): List<TmdbVideo> {
        val key = langKey(id)
        return movieVideosCache.getOrAwait(key) {
            try {
                val lang = getTmdbLanguage()
                val response = tmdbApiService.getMovieVideos(id, lang)
                if (response.isSuccessful) response.body()?.results ?: emptyList()
                else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        }
    }

    suspend fun getTvVideos(id: Int): List<TmdbVideo> {
        val key = langKey(id)
        return tvVideosCache.getOrAwait(key) {
            try {
                val lang = getTmdbLanguage()
                val response = tmdbApiService.getTvVideos(id, lang)
                if (response.isSuccessful) response.body()?.results ?: emptyList()
                else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        }
    }

    suspend fun getMovieImages(id: Int): List<TmdbImage> {
        val key = langKey(id)
        return movieImagesCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.getMovieImages(id, getTmdbImageLanguage())
                if (response.isSuccessful) response.body()?.backdrops ?: emptyList()
                else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        }
    }

    suspend fun getTvImages(id: Int): List<TmdbImage> {
        val key = langKey(id)
        return tvImagesCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.getTvImages(id, "zh,null")
                if (response.isSuccessful) response.body()?.backdrops ?: emptyList()
                else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        }
    }

    /** 获取人物图片（TMDB profiles） */
    suspend fun getPersonImages(personId: Int): List<String> {
        val key = langKey(personId)
        return personImagesCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.getPersonImages(personId)
                if (response.isSuccessful) {
                    response.body()?.profiles?.map { TmdbImageUrls.H632 + it.file_path } ?: emptyList()
                } else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        }
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
        val key = langKey(tmdbId)
        return similarMoviesCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.getSimilarMovies(tmdbId, language = getTmdbLanguage())
                if (response.isSuccessful) response.body()?.results ?: emptyList()
                else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        }
    }

    /** 获取相似剧集（TMDB） */
    suspend fun getSimilarShows(tmdbId: Int): List<TmdbSearchResult> {
        val key = langKey(tmdbId)
        return similarShowsCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.getSimilarShows(tmdbId, language = getTmdbLanguage())
                if (response.isSuccessful) response.body()?.results ?: emptyList()
                else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        }
    }

    /** 获取系列信息（TMDB） */
    suspend fun getCollection(collectionId: Int): TmdbCollectionResponse? {
        val key = langKey(collectionId)
        return collectionCache.getOrAwait(key) {
            try {
                val response = tmdbApiService.getCollection(collectionId, language = getTmdbLanguage())
                if (response.isSuccessful) Result.success(response.body())
                else Result.failure(Exception("HTTP ${response.code()}"))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        }.getOrNull()
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
        val genreIds: List<Int>,            // 类型（多选，OR）
        val originCountries: List<String>,  // 地区（多选，OR）
        val keywordIds: List<Int>,          // 标签/关键词（多选，OR）
        val voteAverageMin: Float,          // 评分下限 0-10
        val voteAverageMax: Float,          // 评分上限 0-10
        val releaseDateStart: String?,      // 年代起始日期 yyyy-MM-dd
        val releaseDateEnd: String?,        // 年代结束日期 yyyy-MM-dd
        val sortBy: DiscoverSort,           // 排序方式
        val hideWatched: Boolean            // 仅展示未标看过
    )

    enum class DiscoverType { MOVIE, SHOW }

    enum class DiscoverSort {
        POPULARITY_DESC,
        RELEASE_DATE_DESC,
        VOTE_AVERAGE_DESC;

        /**
         * TMDB 的 sort_by 取值按类型区分。
         *
         * /discover/tv 不认电影专用的 primary_release_date.desc：TMDB 不会报错，
         * 而是静默忽略并回落到默认的 popularity.desc，用户看到的是「点了按上映日期排序但顺序没变」。
         * 剧集的对应字段是 first_air_date。
         */
        fun apiValue(type: DiscoverType): String = when (this) {
            POPULARITY_DESC -> "popularity.desc"
            RELEASE_DATE_DESC ->
                if (type == DiscoverType.MOVIE) "primary_release_date.desc" else "first_air_date.desc"
            VOTE_AVERAGE_DESC -> "vote_average.desc"
        }
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
            // 多选一律用竖线分隔：TMDB 里逗号是 AND、竖线才是 OR。
            // 用逗号时「动作 + 喜剧」只剩同时属于两个类型的片（实测 9157 条 vs 竖线 215064 条），
            // 「中国 + 日本」只剩合拍片（53 条 vs 76100 条），两个关键词更是直接 0 条 ——
            // UI 上是多选，用户预期的是「任选其一」。
            val genres = filter.genreIds.joinToString("|").ifEmpty { null }
            val countries = filter.originCountries.joinToString("|").ifEmpty { null }
            val keywords = filter.keywordIds.joinToString("|").ifEmpty { null }
            // 评分下限/上限：边界值不传，避免过滤掉恰好 0 分或 10 分的条目
            val voteMin = if (filter.voteAverageMin <= 0f) null else filter.voteAverageMin
            val voteMax = if (filter.voteAverageMax >= 10f) null else filter.voteAverageMax
            // 最低投票数：评分筛选和评分排序都需要，否则结果被只有几票的条目占满
            // （sort_by=vote_average.desc 不加下限时榜首实测全是 1 票的 10.0 分片）
            val ratingFiltered = filter.voteAverageMin > 0f || filter.voteAverageMax < 10f
            val voteCountGte = when {
                filter.sortBy == DiscoverSort.VOTE_AVERAGE_DESC -> DISCOVER_VOTE_COUNT_MIN_SORT
                ratingFiltered -> DISCOVER_VOTE_COUNT_MIN_FILTER
                else -> null
            }
            val sortBy = filter.sortBy.apiValue(filter.type)

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
                    sortBy = sortBy,
                    // 影视筛选是通用浏览入口，成人内容不该混在里面（TMDB 默认也是 false）
                    includeAdult = false
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
                    sortBy = sortBy,
                    includeAdult = false
                )
            }
            if (response.isSuccessful) {
                val body = response.body() ?: TmdbSearchResponse()
                DiscoverPage(body.results, body.total_pages, body.total_results)
            } else {
                // 失败必须抛给调用方：以前这里和 catch 一起把失败降级成空页，筛选页于是显示
                // 「没有符合条件的结果」，用户以为条件太严去改条件，真实原因是这次请求没成功
                throw HttpException(response)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}
