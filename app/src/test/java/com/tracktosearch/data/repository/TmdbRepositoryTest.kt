package com.tracktosearch.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.remote.tmdb.TmdbApiService
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.remote.tmdb.dto.*
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException
import java.util.Locale
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * TmdbRepository 单元测试。
 *
 * 验证三层缓存链路（内存 → 磁盘 → 网络）与 enrichMovie/enrichTv 的数据转换逻辑。
 *
 * PersistentTtlCache 的 awaitLoaded 在未调用 loadFromDisk 时会永久挂起，
 * 测试 setup 中通过反射调用每个缓存的 loadFromDisk() 来 complete loadedDeferred。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class TmdbRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tmdbApiService = mockk<TmdbApiService>(relaxed = true)
    private val languageStorage = mockk<LanguageStorage>(relaxed = true)
    private lateinit var repository: TmdbRepository

    @Before
    fun setup() = runTest {
        // 默认中文
        coEvery { languageStorage.language } returns kotlinx.coroutines.flow.MutableStateFlow(LanguageStorage.LANGUAGE_CHINESE)
        clearMocks(tmdbApiService)
        val context: Context = RuntimeEnvironment.getApplication()
        repository = TmdbRepository(tmdbApiService, languageStorage, kotlinx.serialization.json.Json { ignoreUnknownKeys = true }, context)
        // 通过反射调用所有 PersistentTtlCache 的 loadFromDisk，complete loadedDeferred
        loadAllPersistentCachesFromDisk()
    }

    @After
    fun teardown() = runTest {
        // 清除所有持久化缓存，避免跨测试污染
        clearAllPersistentCaches()
    }

    // ==================== 反射辅助 ====================

    /** 获取 TmdbRepository 中所有 PersistentTtlCache 类型的字段 */
    private fun getPersistentCacheFields(): List<Pair<String, PersistentTtlCache<*>>> {
        return TmdbRepository::class.java.declaredFields
            .filter { PersistentTtlCache::class.java.isAssignableFrom(it.type) }
            .map { field ->
                field.isAccessible = true
                field.name to (field.get(repository) as PersistentTtlCache<*>)
            }
    }

    /** 调用所有 PersistentTtlCache 的 loadFromDisk（complete loadedDeferred，避免 awaitLoaded 挂起） */
    private suspend fun loadAllPersistentCachesFromDisk() {
        getPersistentCacheFields().forEach { (_, cache) ->
            cache.loadFromDisk()
        }
    }

    /** 清除所有 PersistentTtlCache（内存 + 磁盘） */
    private suspend fun clearAllPersistentCaches() {
        getPersistentCacheFields().forEach { (_, cache) ->
            try { cache.clearAll() } catch (_: Exception) {}
        }
    }

    /** 通过名称获取 PersistentTtlCache 字段 */
    @Suppress("UNCHECKED_CAST")
    private fun <T> getCache(fieldName: String): PersistentTtlCache<T> {
        val field = TmdbRepository::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(repository) as PersistentTtlCache<T>
    }

    /** 通过名称获取普通 TtlCache 字段 */
    @Suppress("UNCHECKED_CAST")
    private fun <T> getTtlCache(fieldName: String): com.tracktosearch.data.util.TtlCache<T> {
        val field = TmdbRepository::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(repository) as com.tracktosearch.data.util.TtlCache<T>
    }

    // ==================== 测试数据 ====================

    private val testMovieDetail = TmdbMovieDetail(
        id = 100,
        title = "电影A",
        original_title = "Movie A",
        poster_path = "/poster.jpg",
        overview = "剧情简介",
        genres = listOf(TmdbGenre(1, "动作"), TmdbGenre(2, "科幻")),
        release_date = "2024-06-15",
        vote_average = 8.5,
        runtime = 120,
        production_countries = listOf(TmdbProductionCountry("CN", "中国")),
        belongs_to_collection = TmdbBelongsToCollection(id = 10, name = "合集"),
        status = "Released",
        imdb_id = "tt0000001"
    )

    private val testTvDetail = TmdbTvDetail(
        id = 200,
        name = "剧集A",
        original_name = "Show A",
        poster_path = "/tv_poster.jpg",
        overview = "剧集简介",
        genres = listOf(TmdbGenre(3, "剧情"), TmdbGenre(4, "惊悚")),
        first_air_date = "2024-01-10",
        vote_average = 9.0,
        episode_run_time = listOf(45),
        origin_country = listOf("US"),
        status = "Ended",
        imdb_id = "tt0000002"
    )

    private val testCredits = TmdbCreditsResponse(
        cast = listOf(TmdbCast(id = 1, name = "演员A", character = "角色A")),
        crew = listOf(TmdbCrew(id = 2, name = "导演A", job = "Director"))
    )

    private val testVideos = listOf(
        TmdbVideo(id = "v1", key = "abc123", name = "预告片", site = "YouTube", type = "Trailer")
    )

    private val testImages = listOf(
        TmdbImage(file_path = "/backdrop1.jpg", width = 1920, height = 1080)
    )

    // ==================== enrichMovie 三层缓存链路 ====================

    @Test
    fun enrichMovie_内存缓存命中_不调用API() = runTest {
        val cache = getCache<TmdbMovieDetail?>("movieDetailCache")
        cache.put("100_zh-CN", testMovieDetail)

        val result = repository.enrichMovie(tmdbId = 100, originalTitle = "Original", year = 2024)

        assertThat(result.chineseTitle).isEqualTo("电影A")
        assertThat(result.imdbId).isEqualTo("tt0000001")
        coVerify(exactly = 0) { tmdbApiService.getMovieDetail(any(), any()) }
    }

    @Test
    fun enrichMovie_缓存未命中_调用API并返回数据() = runTest {
        coEvery { tmdbApiService.getMovieDetail(100, any()) } returns Response.success(testMovieDetail)

        val result = repository.enrichMovie(tmdbId = 100, originalTitle = "Original", year = 2024)

        assertThat(result.chineseTitle).isEqualTo("电影A")
        assertThat(result.originalTitle).isEqualTo("Movie A")
        assertThat(result.overview).isEqualTo("剧情简介")
        assertThat(result.genres).isEqualTo("动作 · 科幻")
        assertThat(result.year).isEqualTo(2024)
        assertThat(result.rating).isEqualTo(8.5)
        assertThat(result.runtime).isEqualTo(120)
        assertThat(result.releaseDate).isEqualTo("2024-06-15")
        assertThat(result.status).isEqualTo("Released")
        assertThat(result.imdbId).isEqualTo("tt0000001")
        assertThat(result.collectionId).isEqualTo(10)
        coVerify(exactly = 1) { tmdbApiService.getMovieDetail(100, any()) }
    }

    @Test
    fun enrichMovie_网络成功_缓存写入供下次命中() = runTest {
        coEvery { tmdbApiService.getMovieDetail(100, any()) } returns Response.success(testMovieDetail)

        // 第一次调用：走网络
        repository.enrichMovie(tmdbId = 100, originalTitle = "Original", year = 2024)
        // 第二次调用：应命中缓存
        repository.enrichMovie(tmdbId = 100, originalTitle = "Original", year = 2024)

        coVerify(exactly = 1) { tmdbApiService.getMovieDetail(100, any()) }
    }

    @Test
    fun enrichMovie_网络404_返回fallback() = runTest {
        coEvery { tmdbApiService.getMovieDetail(100, any()) } returns Response.error(404, okhttp3.ResponseBody.create(null, ""))

        val result = repository.enrichMovie(tmdbId = 100, originalTitle = "原始标题", year = 2023)

        assertThat(result.chineseTitle).isEqualTo("原始标题")
        assertThat(result.year).isEqualTo(2023)
        assertThat(result.rating).isEqualTo(0.0)
        assertThat(result.posterUrl).isNull()
        assertThat(result.overview).isEmpty()
    }

    @Test
    fun enrichMovie_网络异常_返回fallback() = runTest {
        coEvery { tmdbApiService.getMovieDetail(100, any()) } throws IOException("网络错误")

        val result = repository.enrichMovie(tmdbId = 100, originalTitle = "原始标题", year = 2023)

        assertThat(result.chineseTitle).isEqualTo("原始标题")
        assertThat(result.year).isEqualTo(2023)
    }

    @Test
    fun enrichMovie_海报路径正确拼接() = runTest {
        coEvery { tmdbApiService.getMovieDetail(100, any()) } returns Response.success(testMovieDetail)

        val result = repository.enrichMovie(tmdbId = 100, originalTitle = "Original", year = 2024)

        assertThat(result.posterUrl).isEqualTo("${TmdbImageUrls.W342}/poster.jpg")
    }


    @Test
    fun enrichMovie_年份从release_date提取() = runTest {
        val detail = testMovieDetail.copy(release_date = "2023-12-25")
        coEvery { tmdbApiService.getMovieDetail(100, any()) } returns Response.success(detail)

        val result = repository.enrichMovie(tmdbId = 100, originalTitle = "Original", year = 9999)

        assertThat(result.year).isEqualTo(2023)
    }

    @Test
    fun enrichMovie_release_date为空时用传入year() = runTest {
        val detail = testMovieDetail.copy(release_date = "")
        coEvery { tmdbApiService.getMovieDetail(100, any()) } returns Response.success(detail)

        val result = repository.enrichMovie(tmdbId = 100, originalTitle = "Original", year = 2022)

        assertThat(result.year).isEqualTo(2022)
    }


    @Test
    fun enrichMovie_title非空_直接用作中文标题() = runTest {
        val detail = testMovieDetail.copy(title = "中文标题")
        coEvery { tmdbApiService.getMovieDetail(100, any()) } returns Response.success(detail)

        val result = repository.enrichMovie(tmdbId = 100, originalTitle = "Original", year = 2024)

        assertThat(result.chineseTitle).isEqualTo("中文标题")
        // 不应调用 alternative_titles
        coVerify(exactly = 0) { tmdbApiService.getMovieAlternativeTitles(any(), any()) }
    }

    @Test
    fun enrichMovie_title为空_走alternative_titles() = runTest {
        val detail = testMovieDetail.copy(title = "")
        val altTitles = TmdbAlternativeTitlesResponse(
            titles = listOf(
                TmdbAlternativeTitle(iso_3166_1 = "US", title = "English Title", type = ""),
                TmdbAlternativeTitle(iso_3166_1 = "CN", title = "中文译名", type = "")
            )
        )
        coEvery { tmdbApiService.getMovieDetail(100, "zh-CN") } returns Response.success(detail)
        coEvery { tmdbApiService.getMovieAlternativeTitles(100, "CN") } returns Response.success(altTitles)

        val result = repository.enrichMovie(tmdbId = 100, originalTitle = "Original", year = 2024)

        assertThat(result.chineseTitle).isEqualTo("中文译名")
        coVerify(exactly = 1) { tmdbApiService.getMovieAlternativeTitles(100, "CN") }
    }

    @Test
    fun enrichMovie_title为空_altTitles无CN_用originalTitle() = runTest {
        val detail = testMovieDetail.copy(title = "")
        val altTitles = TmdbAlternativeTitlesResponse(
            titles = listOf(TmdbAlternativeTitle(iso_3166_1 = "US", title = "English Title", type = ""))
        )
        coEvery { tmdbApiService.getMovieDetail(100, any()) } returns Response.success(detail)
        coEvery { tmdbApiService.getMovieAlternativeTitles(100, any()) } returns Response.success(altTitles)

        val result = repository.enrichMovie(tmdbId = 100, originalTitle = "Fallback Title", year = 2024)

        assertThat(result.chineseTitle).isEqualTo("Fallback Title")
    }

    @Test
    fun enrichMovie_title为空_altTitles缓存命中_不调用API() = runTest {
        val detail = testMovieDetail.copy(title = "")
        val altTitles = TmdbAlternativeTitlesResponse(
            titles = listOf(TmdbAlternativeTitle(iso_3166_1 = "CN", title = "缓存中文标题", type = ""))
        )
        // 预填充 altTitles 缓存
        val altCache = getCache<TmdbAlternativeTitlesResponse>("movieAltTitlesCache")
        altCache.put("100_zh-CN", altTitles)

        coEvery { tmdbApiService.getMovieDetail(100, any()) } returns Response.success(detail)

        val result = repository.enrichMovie(tmdbId = 100, originalTitle = "Original", year = 2024)

        assertThat(result.chineseTitle).isEqualTo("缓存中文标题")
        coVerify(exactly = 0) { tmdbApiService.getMovieAlternativeTitles(any(), any()) }
    }




    @Test
    fun peekMovieEnrichment_详情标题为空_同步读取已缓存本地化备用标题() = runTest {
        val detailCache = getCache<TmdbMovieDetail?>("movieDetailCache")
        val altCache = getCache<TmdbAlternativeTitlesResponse>("movieAltTitlesCache")
        detailCache.put("100_zh-CN", testMovieDetail.copy(title = ""))
        altCache.put(
            "100_zh-CN",
            TmdbAlternativeTitlesResponse(
                titles = listOf(
                    TmdbAlternativeTitle(iso_3166_1 = "US", title = "English Title"),
                    TmdbAlternativeTitle(iso_3166_1 = "CN", title = "缓存中文标题")
                )
            )
        )

        val result = repository.peekMovieEnrichment(100, "Original", 2024)

        assertThat(result?.chineseTitle).isEqualTo("缓存中文标题")
        coVerify(exactly = 0) { tmdbApiService.getMovieDetail(any(), any()) }
        coVerify(exactly = 0) { tmdbApiService.getMovieAlternativeTitles(any(), any()) }
    }

    @Test
    fun peekMovieEnrichment_详情标题为空且没有同步本地化标题_返回null() = runTest {
        val detailCache = getCache<TmdbMovieDetail?>("movieDetailCache")
        detailCache.put("100_zh-CN", testMovieDetail.copy(title = ""))

        val result = repository.peekMovieEnrichment(100, "Original", 2024)

        assertThat(result).isNull()
        coVerify(exactly = 0) { tmdbApiService.getMovieDetail(any(), any()) }
        coVerify(exactly = 0) { tmdbApiService.getMovieAlternativeTitles(any(), any()) }
    }

    // ==================== enrichTv 三层缓存链路 ====================

    @Test
    fun enrichTv_内存缓存命中_不调用API() = runTest {
        val cache = getCache<TmdbTvDetail?>("tvDetailCache")
        cache.put("200_zh-CN", testTvDetail)

        val result = repository.enrichTv(tmdbId = 200, originalName = "Original", year = 2024)

        assertThat(result.chineseTitle).isEqualTo("剧集A")
        assertThat(result.imdbId).isEqualTo("tt0000002")
        coVerify(exactly = 0) { tmdbApiService.getTvDetail(any(), any()) }
    }

    @Test
    fun enrichTv_缓存未命中_调用API并返回数据() = runTest {
        coEvery { tmdbApiService.getTvDetail(200, any()) } returns Response.success(testTvDetail)

        val result = repository.enrichTv(tmdbId = 200, originalName = "Original", year = 2024)

        assertThat(result.chineseTitle).isEqualTo("剧集A")
        assertThat(result.originalTitle).isEqualTo("Show A")
        assertThat(result.overview).isEqualTo("剧集简介")
        assertThat(result.genres).isEqualTo("剧情 · 惊悚")
        assertThat(result.year).isEqualTo(2024)
        assertThat(result.rating).isEqualTo(9.0)
        assertThat(result.episodeRunTime).isEqualTo(45)
        assertThat(result.releaseDate).isEqualTo("2024-01-10")
        assertThat(result.status).isEqualTo("Ended")
        assertThat(result.imdbId).isEqualTo("tt0000002")
        coVerify(exactly = 1) { tmdbApiService.getTvDetail(200, any()) }
    }

    @Test
    fun enrichTv_网络成功_缓存写入供下次命中() = runTest {
        coEvery { tmdbApiService.getTvDetail(200, any()) } returns Response.success(testTvDetail)

        repository.enrichTv(tmdbId = 200, originalName = "Original", year = 2024)
        repository.enrichTv(tmdbId = 200, originalName = "Original", year = 2024)

        coVerify(exactly = 1) { tmdbApiService.getTvDetail(200, any()) }
    }

    @Test
    fun enrichTv_网络404_返回fallback() = runTest {
        coEvery { tmdbApiService.getTvDetail(200, any()) } returns Response.error(404, okhttp3.ResponseBody.create(null, ""))

        val result = repository.enrichTv(tmdbId = 200, originalName = "原始剧名", year = 2023)

        assertThat(result.chineseTitle).isEqualTo("原始剧名")
        assertThat(result.year).isEqualTo(2023)
        assertThat(result.rating).isEqualTo(0.0)
    }

    @Test
    fun enrichTv_网络异常_返回fallback() = runTest {
        coEvery { tmdbApiService.getTvDetail(200, any()) } throws IOException("网络错误")

        val result = repository.enrichTv(tmdbId = 200, originalName = "原始剧名", year = 2023)

        assertThat(result.chineseTitle).isEqualTo("原始剧名")
    }

    @Test
    fun enrichTv_episodeRunTime取第一个() = runTest {
        val detail = testTvDetail.copy(episode_run_time = listOf(60, 45, 30))
        coEvery { tmdbApiService.getTvDetail(200, any()) } returns Response.success(detail)

        val result = repository.enrichTv(tmdbId = 200, originalName = "Original", year = 2024)

        assertThat(result.episodeRunTime).isEqualTo(60)
    }



    @Test
    fun enrichTv_name非空_直接用作中文标题() = runTest {
        val detail = testTvDetail.copy(name = "中文剧名")
        coEvery { tmdbApiService.getTvDetail(200, any()) } returns Response.success(detail)

        val result = repository.enrichTv(tmdbId = 200, originalName = "Original", year = 2024)

        assertThat(result.chineseTitle).isEqualTo("中文剧名")
        coVerify(exactly = 0) { tmdbApiService.getTvAlternativeTitles(any(), any()) }
    }

    @Test
    fun enrichTv_name为空_走alternative_titles() = runTest {
        val detail = testTvDetail.copy(name = "")
        val altTitles = TmdbAlternativeTitlesResponse(
            titles = listOf(
                TmdbAlternativeTitle(iso_3166_1 = "US", title = "English Show", type = ""),
                TmdbAlternativeTitle(iso_3166_1 = "CN", title = "中文剧名", type = "")
            )
        )
        coEvery { tmdbApiService.getTvDetail(200, "zh-CN") } returns Response.success(detail)
        coEvery { tmdbApiService.getTvAlternativeTitles(200, "CN") } returns Response.success(altTitles)

        val result = repository.enrichTv(tmdbId = 200, originalName = "Original", year = 2024)

        assertThat(result.chineseTitle).isEqualTo("中文剧名")
        coVerify(exactly = 1) { tmdbApiService.getTvAlternativeTitles(200, "CN") }
    }



    @Test
    fun peekTvEnrichment_英文详情名称为空_同步读取US备用标题() = runTest {
        val language = kotlinx.coroutines.flow.MutableStateFlow(LanguageStorage.LANGUAGE_ENGLISH)
        coEvery { languageStorage.language } returns language
        val detailCache = getCache<TmdbTvDetail?>("tvDetailCache")
        val altCache = getCache<TmdbAlternativeTitlesResponse>("tvAltTitlesCache")
        detailCache.put("200_en-US", testTvDetail.copy(name = ""))
        altCache.put(
            "200_en-US",
            TmdbAlternativeTitlesResponse(
                titles = listOf(
                    TmdbAlternativeTitle(iso_3166_1 = "CN", title = "中文剧名"),
                    TmdbAlternativeTitle(iso_3166_1 = "US", title = "Cached English Show")
                )
            )
        )

        val result = repository.peekTvEnrichment(200, "Original", 2024)

        assertThat(result?.chineseTitle).isEqualTo("Cached English Show")
        coVerify(exactly = 0) { tmdbApiService.getTvDetail(any(), any()) }
        coVerify(exactly = 0) { tmdbApiService.getTvAlternativeTitles(any(), any()) }
    }

    @Test
    fun enrichTv_name为空_英文按US筛选备用标题并传入请求参数() = runTest {
        val language = kotlinx.coroutines.flow.MutableStateFlow(LanguageStorage.LANGUAGE_ENGLISH)
        coEvery { languageStorage.language } returns language
        val detail = testTvDetail.copy(name = "")
        val altTitles = TmdbAlternativeTitlesResponse(
            titles = listOf(
                TmdbAlternativeTitle(iso_3166_1 = "CN", title = "中文剧名"),
                TmdbAlternativeTitle(iso_3166_1 = "US", title = "English Show")
            )
        )
        coEvery { tmdbApiService.getTvDetail(200, "en-US") } returns Response.success(detail)
        coEvery { tmdbApiService.getTvAlternativeTitles(200, "US") } returns Response.success(altTitles)

        val result = repository.enrichTv(200, "Original", 2024)

        assertThat(result.chineseTitle).isEqualTo("English Show")
        coVerify(exactly = 1) { tmdbApiService.getTvDetail(200, "en-US") }
        coVerify(exactly = 1) { tmdbApiService.getTvAlternativeTitles(200, "US") }
    }

    // ==================== 语言切换 ====================

    @Test
    fun enrichMovie_语言映射与请求参数() = runTest {
        data class LanguageCase(val configured: String, val tmdbLanguage: String)
        val cases = listOf(
            LanguageCase(LanguageStorage.LANGUAGE_CHINESE, "zh-CN"),
            LanguageCase(LanguageStorage.LANGUAGE_ENGLISH, "en-US"),
            LanguageCase(LanguageStorage.LANGUAGE_JAPANESE, "ja-JP"),
            LanguageCase(LanguageStorage.LANGUAGE_KOREAN, "ko-KR")
        )
        val language = kotlinx.coroutines.flow.MutableStateFlow(LanguageStorage.LANGUAGE_CHINESE)
        coEvery { languageStorage.language } returns language
        coEvery { tmdbApiService.getMovieDetail(100, any()) } returns Response.success(testMovieDetail)

        cases.forEach { case ->
            language.value = case.configured
            repository.enrichMovie(tmdbId = 100, originalTitle = "Original", year = 2024)
            coVerify(atLeast = 1) { tmdbApiService.getMovieDetail(100, case.tmdbLanguage) }
        }
    }





    @Test
    fun enrichMovie_不同语言生成不同缓存key() = runTest {
        // 先用中文加载并缓存
        coEvery { languageStorage.language } returns kotlinx.coroutines.flow.MutableStateFlow(LanguageStorage.LANGUAGE_CHINESE)
        coEvery { tmdbApiService.getMovieDetail(100, "zh-CN") } returns Response.success(testMovieDetail)
        repository.enrichMovie(tmdbId = 100, originalTitle = "Original", year = 2024)

        // 切换到英文，应重新请求（key 不同）
        coEvery { languageStorage.language } returns kotlinx.coroutines.flow.MutableStateFlow(LanguageStorage.LANGUAGE_ENGLISH)
        val englishDetail = testMovieDetail.copy(title = "English Title")
        coEvery { tmdbApiService.getMovieDetail(100, "en-US") } returns Response.success(englishDetail)

        val result = repository.enrichMovie(tmdbId = 100, originalTitle = "Original", year = 2024)

        assertThat(result.chineseTitle).isEqualTo("English Title")
        coVerify(exactly = 1) { tmdbApiService.getMovieDetail(100, "zh-CN") }
        coVerify(exactly = 1) { tmdbApiService.getMovieDetail(100, "en-US") }
    }

    @Test
    fun enrichMovie_system语言跟随系统Locale请求语言国家并筛选备用标题() = runTest {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("en", "GB"))
            val language = kotlinx.coroutines.flow.MutableStateFlow(LanguageStorage.LANGUAGE_SYSTEM)
            coEvery { languageStorage.language } returns language
            val detail = testMovieDetail.copy(title = "")
            val altTitles = TmdbAlternativeTitlesResponse(
                titles = listOf(
                    TmdbAlternativeTitle(iso_3166_1 = "US", title = "US Title"),
                    TmdbAlternativeTitle(iso_3166_1 = "GB", title = "British Title")
                )
            )
            coEvery { tmdbApiService.getMovieDetail(100, "en-GB") } returns Response.success(detail)
            coEvery { tmdbApiService.getMovieAlternativeTitles(100, "GB") } returns Response.success(altTitles)

            val result = repository.enrichMovie(100, "Original", 2024)

            assertThat(result.chineseTitle).isEqualTo("British Title")
            coVerify(exactly = 1) { tmdbApiService.getMovieDetail(100, "en-GB") }
            coVerify(exactly = 1) { tmdbApiService.getMovieAlternativeTitles(100, "GB") }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    // ==================== getMovieDetail / getTvDetail ====================

    @Test
    fun getMovieDetail_缓存命中_不调用API() = runTest {
        val cache = getCache<TmdbMovieDetail?>("movieDetailCache")
        cache.put("100_zh-CN", testMovieDetail)

        val result = repository.getMovieDetail(100)

        assertThat(result).isEqualTo(testMovieDetail)
        coVerify(exactly = 0) { tmdbApiService.getMovieDetail(any(), any()) }
    }

    @Test
    fun getMovieDetail_缓存未命中_调用API() = runTest {
        coEvery { tmdbApiService.getMovieDetail(100, any()) } returns Response.success(testMovieDetail)

        val result = repository.getMovieDetail(100)

        assertThat(result).isEqualTo(testMovieDetail)
        coVerify(exactly = 1) { tmdbApiService.getMovieDetail(100, any()) }
    }

    @Test
    fun getMovieDetail_API失败_返回null() = runTest {
        coEvery { tmdbApiService.getMovieDetail(100, any()) } throws IOException("网络错误")

        val result = repository.getMovieDetail(100)

        assertThat(result).isNull()
    }


    @Test
    fun getTvDetail_缓存命中_不调用API() = runTest {
        val cache = getCache<TmdbTvDetail?>("tvDetailCache")
        cache.put("200_zh-CN", testTvDetail)

        val result = repository.getTvDetail(200)

        assertThat(result).isEqualTo(testTvDetail)
        coVerify(exactly = 0) { tmdbApiService.getTvDetail(any(), any()) }
    }

    @Test
    fun getTvDetail_缓存未命中_调用API() = runTest {
        coEvery { tmdbApiService.getTvDetail(200, any()) } returns Response.success(testTvDetail)

        val result = repository.getTvDetail(200)

        assertThat(result).isEqualTo(testTvDetail)
        coVerify(exactly = 1) { tmdbApiService.getTvDetail(200, any()) }
    }

    // ==================== getCredits ====================

    @Test
    fun getCredits_缓存命中_不调用API() = runTest {
        val cache = getCache<TmdbCreditsResponse?>("creditsCache")
        cache.put("100_zh-CN", testCredits)

        val result = repository.getCredits(tmdbId = 100, mediaType = MediaType.MOVIE)

        assertThat(result).isEqualTo(testCredits)
        coVerify(exactly = 0) { tmdbApiService.getMovieCredits(any(), any()) }
    }

    @Test
    fun getCredits_MOVIE类型_调用getMovieCredits() = runTest {
        coEvery { tmdbApiService.getMovieCredits(100, any()) } returns Response.success(testCredits)

        val result = repository.getCredits(tmdbId = 100, mediaType = MediaType.MOVIE)

        assertThat(result).isEqualTo(testCredits)
        coVerify(exactly = 1) { tmdbApiService.getMovieCredits(100, any()) }
    }

    @Test
    fun getCredits_SHOW类型_调用getCredits() = runTest {
        coEvery { tmdbApiService.getCredits(200, any()) } returns Response.success(testCredits)

        val result = repository.getCredits(tmdbId = 200, mediaType = MediaType.SHOW)

        assertThat(result).isEqualTo(testCredits)
        coVerify(exactly = 1) { tmdbApiService.getCredits(200, any()) }
    }

    @Test
    fun getCredits_DISK类型_返回null() = runTest {
        val result = repository.getCredits(tmdbId = 100, mediaType = MediaType.DISK)

        assertThat(result).isNull()
        coVerify(exactly = 0) { tmdbApiService.getMovieCredits(any(), any()) }
    }

    @Test
    fun getCredits_网络失败_返回null() = runTest {
        coEvery { tmdbApiService.getMovieCredits(100, any()) } throws IOException("网络错误")

        val result = repository.getCredits(tmdbId = 100, mediaType = MediaType.MOVIE)

        assertThat(result).isNull()
    }


    // ==================== getReviews ====================

    @Test
    fun getReviews_缓存命中_不调用API() = runTest {
        val reviews = TmdbReviewsResponse(results = listOf(TmdbReview(id = "r1", author = "用户A")))
        val cache = getTtlCache<TmdbReviewsResponse?>("reviewsCache")
        cache.put("100_MOVIE_1_zh-CN", reviews)

        val result = repository.getReviews(tmdbId = 100, mediaType = MediaType.MOVIE, page = 1)

        assertThat(result).isEqualTo(reviews)
        coVerify(exactly = 0) { tmdbApiService.getMovieReviews(any(), any()) }
    }

    @Test
    fun getReviews_MOVIE类型_调用getMovieReviews() = runTest {
        val reviews = TmdbReviewsResponse(results = listOf(TmdbReview(id = "r1", author = "用户A")))
        coEvery { tmdbApiService.getMovieReviews(100, 1) } returns Response.success(reviews)

        val result = repository.getReviews(tmdbId = 100, mediaType = MediaType.MOVIE, page = 1)

        assertThat(result).isEqualTo(reviews)
        coVerify(exactly = 1) { tmdbApiService.getMovieReviews(100, 1) }
    }

    @Test
    fun getReviews_SHOW类型_调用getTvReviews() = runTest {
        val reviews = TmdbReviewsResponse(results = listOf(TmdbReview(id = "r1", author = "用户A")))
        coEvery { tmdbApiService.getTvReviews(200, 1) } returns Response.success(reviews)

        val result = repository.getReviews(tmdbId = 200, mediaType = MediaType.SHOW, page = 1)

        assertThat(result).isEqualTo(reviews)
        coVerify(exactly = 1) { tmdbApiService.getTvReviews(200, 1) }
    }

    @Test
    fun getReviews_DISK类型_返回null() = runTest {
        val result = repository.getReviews(tmdbId = 100, mediaType = MediaType.DISK, page = 1)

        assertThat(result).isNull()
    }


    // ==================== getPersonDetail ====================

    @Test
    fun getPersonDetail_缓存命中_不调用API() = runTest {
        val person = TmdbPerson(id = 1, name = "演员A", biography = "简介")
        val cache = getCache<TmdbPerson?>("personDetailCache")
        cache.put("1_zh-CN", person)

        val result = repository.getPersonDetail(1)

        assertThat(result).isEqualTo(person)
        coVerify(exactly = 0) { tmdbApiService.getPersonDetail(any(), any()) }
    }

    @Test
    fun getPersonDetail_缓存未命中_调用API() = runTest {
        val person = TmdbPerson(id = 1, name = "演员A", biography = "简介")
        coEvery { tmdbApiService.getPersonDetail(1, any()) } returns Response.success(person)

        val result = repository.getPersonDetail(1)

        assertThat(result).isEqualTo(person)
        coVerify(exactly = 1) { tmdbApiService.getPersonDetail(1, any()) }
    }


    // ==================== getMovieVideos / getTvVideos ====================

    @Test
    fun getMovieVideos_缓存命中_不调用API() = runTest {
        val cache = getCache<List<TmdbVideo>>("movieVideosCache")
        cache.put("100_zh-CN", testVideos)

        val result = repository.getMovieVideos(100)

        assertThat(result).isEqualTo(testVideos)
        coVerify(exactly = 0) { tmdbApiService.getMovieVideos(any(), any()) }
    }

    @Test
    fun getMovieVideos_缓存未命中_调用API() = runTest {
        coEvery { tmdbApiService.getMovieVideos(100, any()) } returns Response.success(TmdbVideosResponse(results = testVideos))

        val result = repository.getMovieVideos(100)

        assertThat(result).isEqualTo(testVideos)
        coVerify(exactly = 1) { tmdbApiService.getMovieVideos(100, any()) }
    }

    @Test
    fun getMovieVideos_网络失败_返回空列表() = runTest {
        coEvery { tmdbApiService.getMovieVideos(100, any()) } throws IOException("网络错误")

        val result = repository.getMovieVideos(100)

        assertThat(result).isEmpty()
    }


    @Test
    fun getTvVideos_缓存命中_不调用API() = runTest {
        val cache = getCache<List<TmdbVideo>>("tvVideosCache")
        cache.put("200_zh-CN", testVideos)

        val result = repository.getTvVideos(200)

        assertThat(result).isEqualTo(testVideos)
        coVerify(exactly = 0) { tmdbApiService.getTvVideos(any(), any()) }
    }

    @Test
    fun getTvVideos_缓存未命中_调用API() = runTest {
        coEvery { tmdbApiService.getTvVideos(200, any()) } returns Response.success(TmdbVideosResponse(results = testVideos))

        val result = repository.getTvVideos(200)

        assertThat(result).isEqualTo(testVideos)
        coVerify(exactly = 1) { tmdbApiService.getTvVideos(200, any()) }
    }

    // ==================== getMovieImages / getTvImages ====================

    @Test
    fun getMovieImages_缓存命中_不调用API() = runTest {
        val cache = getCache<List<TmdbImage>>("movieImagesCache")
        cache.put("100_zh-CN", testImages)

        val result = repository.getMovieImages(100)

        assertThat(result).isEqualTo(testImages)
        coVerify(exactly = 0) { tmdbApiService.getMovieImages(any(), any()) }
    }

    @Test
    fun getMovieImages_缓存未命中_调用API() = runTest {
        coEvery { tmdbApiService.getMovieImages(100, any()) } returns Response.success(TmdbImagesResponse(backdrops = testImages))

        val result = repository.getMovieImages(100)

        assertThat(result).isEqualTo(testImages)
        coVerify(exactly = 1) { tmdbApiService.getMovieImages(100, any()) }
    }

    @Test
    fun getMovieImages_网络失败_返回空列表() = runTest {
        coEvery { tmdbApiService.getMovieImages(100, any()) } throws IOException("网络错误")

        val result = repository.getMovieImages(100)

        assertThat(result).isEmpty()
    }


    @Test
    fun getTvImages_缓存未命中_调用API() = runTest {
        coEvery { tmdbApiService.getTvImages(200, any()) } returns Response.success(TmdbImagesResponse(backdrops = testImages))

        val result = repository.getTvImages(200)

        assertThat(result).isEqualTo(testImages)
        coVerify(exactly = 1) { tmdbApiService.getTvImages(200, any()) }
    }

    // ==================== searchMovie ====================

    @Test
    fun searchMovie_缓存命中_不调用API() = runTest {
        val result1 = TmdbSearchResult(id = 100, title = "电影A")
        val cache = getTtlCache<TmdbSearchResult?>("searchMovieCache")
        cache.put("电影A_zh-CN", result1)

        val result = repository.searchMovie("电影A")

        assertThat(result).isEqualTo(result1)
        coVerify(exactly = 0) { tmdbApiService.searchMovie(any(), any(), any()) }
    }

    @Test
    fun searchMovie_缓存未命中_调用API() = runTest {
        val searchResult = TmdbSearchResult(id = 100, title = "电影A")
        coEvery { tmdbApiService.searchMovie("电影A", any(), any()) } returns Response.success(
            TmdbSearchResponse(results = listOf(searchResult))
        )

        val result = repository.searchMovie("电影A")

        assertThat(result).isEqualTo(searchResult)
        coVerify(exactly = 1) { tmdbApiService.searchMovie("电影A", any(), any()) }
    }

    @Test
    fun searchMovie_网络失败_返回null() = runTest {
        coEvery { tmdbApiService.searchMovie(any(), any(), any()) } throws IOException("网络错误")

        val result = repository.searchMovie("电影A")

        assertThat(result).isNull()
    }


    @Test
    fun searchMovie_query前后空格trim生成相同缓存key() = runTest {
        val searchResult = TmdbSearchResult(id = 100, title = "电影A")
        // API 收到原始 query（未 trim），但缓存 key 是 trim 后的
        coEvery { tmdbApiService.searchMovie(any(), any(), any()) } returns Response.success(
            TmdbSearchResponse(results = listOf(searchResult))
        )

        // 第一次：带空格的 query，走网络
        repository.searchMovie("  电影A  ")
        // 第二次：不带空格的 query，应命中缓存（key 相同）
        repository.searchMovie("电影A")

        // 只调用一次 API（第二次命中缓存）
        coVerify(exactly = 1) { tmdbApiService.searchMovie(any(), any(), any()) }
    }

    // ==================== 列表类缓存（getTrendingMovies 等） ====================

    @Test
    fun 列表类方法_缓存命中均不调用对应API() = runTest {
        val results = listOf(TmdbSearchResult(id = 1, title = "电影A"))
        data class CacheCase(val cacheField: String, val cacheKey: String, val endpoint: String)
        val cases = listOf(
            CacheCase("trendingMoviesCache", "day_zh-CN", "trending"),
            CacheCase("popularMoviesCache", "default_zh-CN", "popular"),
            CacheCase("upcomingMoviesCache", "default_zh-CN", "upcoming"),
            CacheCase("topRatedMoviesCache", "default_zh-CN", "topRated")
        )

        cases.forEach { case ->
            getCache<List<TmdbSearchResult>>(case.cacheField).put(case.cacheKey, results)
            val actual = when (case.endpoint) {
                "trending" -> repository.getTrendingMovies("day")
                "popular" -> repository.getPopularMovies()
                "upcoming" -> repository.getUpcomingMovies()
                else -> repository.getTopRatedMovies()
            }
            assertThat(actual).isEqualTo(results)
        }

        coVerify(exactly = 0) { tmdbApiService.getTrendingMovies(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbApiService.getPopularMovies(any(), any()) }
        coVerify(exactly = 0) { tmdbApiService.getUpcomingMovies(any(), any()) }
        coVerify(exactly = 0) { tmdbApiService.getTopRatedMovies(any(), any()) }
    }


    @Test
    fun getTrendingMovies_缓存未命中_调用API() = runTest {
        val results = listOf(TmdbSearchResult(id = 1, title = "电影A"))
        coEvery { tmdbApiService.getTrendingMovies("day", any(), any()) } returns Response.success(
            TmdbSearchResponse(results = results)
        )

        val result = repository.getTrendingMovies("day")

        assertThat(result).isEqualTo(results)
        coVerify(exactly = 1) { tmdbApiService.getTrendingMovies("day", any(), any()) }
    }




    // ==================== 分页方法（绕过缓存） ====================

    @Test
    fun 列表类分页方法_各端点绕过缓存并传递页码() = runTest {
        val results = listOf(TmdbSearchResult(id = 1, title = "电影A"))
        coEvery { tmdbApiService.getTrendingMovies("day", any(), 2) } returns Response.success(TmdbSearchResponse(results = results))
        coEvery { tmdbApiService.getPopularMovies(any(), 3) } returns Response.success(TmdbSearchResponse(results = results))
        coEvery { tmdbApiService.getUpcomingMovies(any(), 2) } returns Response.success(TmdbSearchResponse(results = results))
        coEvery { tmdbApiService.getTopRatedMovies(any(), 2) } returns Response.success(TmdbSearchResponse(results = results))

        val actual = listOf(
            repository.getTrendingMovies("day", page = 2),
            repository.getPopularMovies(page = 3),
            repository.getUpcomingMovies(page = 2),
            repository.getTopRatedMovies(page = 2)
        )
        actual.forEach { assertThat(it).isEqualTo(results) }

        coVerify(exactly = 1) { tmdbApiService.getTrendingMovies("day", any(), 2) }
        coVerify(exactly = 1) { tmdbApiService.getPopularMovies(any(), 3) }
        coVerify(exactly = 1) { tmdbApiService.getUpcomingMovies(any(), 2) }
        coVerify(exactly = 1) { tmdbApiService.getTopRatedMovies(any(), 2) }
    }





    // ==================== getPersonMovieCredits 分页 ====================

    @Test
    fun getPersonMovieCredits_分页切分前20及剩余条目() = runTest {
        val credits = (1..25).map {
            TmdbPersonMovieCredit(id = it, title = "电影$it", vote_average = 10.0 - it * 0.1)
        }
        coEvery { tmdbApiService.getPersonMovieCredits(1, any(), any()) } returns Response.success(
            TmdbPersonMovieCredits(cast = credits)
        )

        val firstPage = repository.getPersonMovieCredits(1, page = 1)
        val secondPage = repository.getPersonMovieCredits(1, page = 2)

        assertThat(firstPage.items).hasSize(20)
        assertThat(firstPage.hasMore).isTrue()
        assertThat(firstPage.items[0].vote_average).isAtLeast(firstPage.items[1].vote_average)
        assertThat(secondPage.items).hasSize(5)
        assertThat(secondPage.hasMore).isFalse()
        coVerify(exactly = 1) { tmdbApiService.getPersonMovieCredits(1, any(), 1) }
    }



    @Test
    fun getPersonMovieCredits_缓存命中_不调用API() = runTest {
        val credits = listOf(TmdbPersonMovieCredit(id = 1, title = "电影1"))
        val cache = getTtlCache<List<TmdbPersonMovieCredit>>("personMovieCreditsCache")
        cache.put("1_zh-CN", credits)

        val result = repository.getPersonMovieCredits(1, page = 1)

        assertThat(result.items).isEqualTo(credits)
        coVerify(exactly = 0) { tmdbApiService.getPersonMovieCredits(any(), any(), any()) }
    }

    @Test
    fun getPersonMovieCredits_网络失败_返回空列表() = runTest {
        coEvery { tmdbApiService.getPersonMovieCredits(1, any(), any()) } throws IOException("网络错误")

        val result = repository.getPersonMovieCredits(1, page = 1)

        assertThat(result.items).isEmpty()
        assertThat(result.hasMore).isFalse()
    }

    @Test
    fun getPersonTvCredits_第一页返回前20条() = runTest {
        val credits = (1..25).map {
            TmdbPersonTvCredit(id = it, name = "剧集$it", vote_average = 10.0 - it * 0.1)
        }
        coEvery { tmdbApiService.getPersonTvCredits(1, any(), any()) } returns Response.success(
            TmdbPersonTvCredits(cast = credits)
        )

        val result = repository.getPersonTvCredits(1, page = 1)

        assertThat(result.items).hasSize(20)
        assertThat(result.hasMore).isTrue()
    }

    // ==================== buildProfileUrl ====================

    @Test
    fun buildProfileUrl_处理空值与完整路径() {
        listOf(
            null to null,
            "/abc.jpg" to "${TmdbImageUrls.W342}/abc.jpg"
        ).forEach { (path, expected) ->
            assertThat(repository.buildProfileUrl(path)).isEqualTo(expected)
        }
    }



    // ==================== getSimilarMovies / getSimilarShows（无缓存） ====================

    @Test
    fun getSimilarMovies_调用API() = runTest {
        val results = listOf(TmdbSearchResult(id = 1, title = "相似电影"))
        coEvery { tmdbApiService.getSimilarMovies(100, any(), any()) } returns Response.success(
            TmdbSearchResponse(results = results)
        )

        val result = repository.getSimilarMovies(100)

        assertThat(result).isEqualTo(results)
    }


    @Test
    fun getSimilarShows_调用API() = runTest {
        val results = listOf(TmdbSearchResult(id = 1, title = "相似剧集"))
        coEvery { tmdbApiService.getSimilarShows(200, any(), any()) } returns Response.success(
            TmdbSearchResponse(results = results)
        )

        val result = repository.getSimilarShows(200)

        assertThat(result).isEqualTo(results)
    }

    // ==================== getCollection ====================

    @Test
    fun getCollection_调用API() = runTest {
        val collection = TmdbCollectionResponse(id = 10, name = "合集", parts = listOf(
            TmdbCollectionPart(id = 1, title = "电影1")
        ))
        coEvery { tmdbApiService.getCollection(10, any()) } returns Response.success(collection)

        val result = repository.getCollection(10)

        assertThat(result).isEqualTo(collection)
    }


    // ==================== getTvSeasonDetail ====================

    @Test
    fun getTvSeasonDetail_调用API() = runTest {
        val season = TmdbTvSeasonDetail(id = 1, name = "第一季", episodes = listOf(
            TmdbSeasonEpisode(id = 1, name = "第1集", episode_number = 1)
        ))
        coEvery { tmdbApiService.getTvSeasonDetail(200, 1, any()) } returns Response.success(season)

        val result = repository.getTvSeasonDetail(200, 1)

        assertThat(result).isEqualTo(season)
    }


    // ==================== searchPerson / searchMulti ====================

    @Test
    fun searchPerson_缓存命中_不调用API() = runTest {
        val results = listOf(TmdbPersonSearchResult(id = 1, name = "演员A"))
        val cache = getTtlCache<List<TmdbPersonSearchResult>>("searchPersonCache")
        cache.put("演员A_zh-CN", results)

        val result = repository.searchPerson("演员A")

        assertThat(result).isEqualTo(results)
        coVerify(exactly = 0) { tmdbApiService.searchPerson(any(), any(), any()) }
    }

    @Test
    fun searchPerson_缓存未命中_调用API() = runTest {
        val results = listOf(TmdbPersonSearchResult(id = 1, name = "演员A"))
        coEvery { tmdbApiService.searchPerson("演员A", any(), any()) } returns Response.success(
            TmdbPersonSearchResponse(results = results)
        )

        val result = repository.searchPerson("演员A")

        assertThat(result).isEqualTo(results)
        coVerify(exactly = 1) { tmdbApiService.searchPerson("演员A", any(), any()) }
    }


    @Test
    fun searchMulti_缓存命中_不调用API() = runTest {
        val response = TmdbMultiSearchResponse(results = listOf(
            TmdbMultiSearchResult(id = 1, title = "电影A", media_type = "movie")
        ))
        val cache = getTtlCache<TmdbMultiSearchResponse?>("searchMultiCache")
        cache.put("电影A_zh-CN", response)

        val result = repository.searchMulti("电影A")

        assertThat(result).isEqualTo(response)
        coVerify(exactly = 0) { tmdbApiService.searchMulti(any(), any(), any()) }
    }

    @Test
    fun searchMulti_缓存未命中_调用API() = runTest {
        val response = TmdbMultiSearchResponse(results = listOf(
            TmdbMultiSearchResult(id = 1, title = "电影A", media_type = "movie")
        ))
        coEvery { tmdbApiService.searchMulti("电影A", any(), any()) } returns Response.success(response)

        val result = repository.searchMulti("电影A")

        assertThat(result).isEqualTo(response)
        coVerify(exactly = 1) { tmdbApiService.searchMulti("电影A", any(), any()) }
    }

    // ==================== getPersonImages / getPersonTaggedImages ====================

    @Test
    fun getPersonImages_缓存命中_不调用API() = runTest {
        val urls = listOf("https://image.tmdb.org/t/p/h632/abc.jpg")
        val cache = getTtlCache<List<String>>("personImagesCache")
        cache.put("1_zh-CN", urls)

        val result = repository.getPersonImages(1)

        assertThat(result).isEqualTo(urls)
        coVerify(exactly = 0) { tmdbApiService.getPersonImages(any()) }
    }

    @Test
    fun getPersonImages_缓存未命中_调用API() = runTest {
        coEvery { tmdbApiService.getPersonImages(1) } returns Response.success(
            TmdbPersonImagesResponse(id = 1, profiles = listOf(TmdbImage(file_path = "/abc.jpg")))
        )

        val result = repository.getPersonImages(1)

        assertThat(result).hasSize(1)
        assertThat(result[0]).contains("/abc.jpg")
        coVerify(exactly = 1) { tmdbApiService.getPersonImages(1) }
    }



    @Test
    fun getPersonTaggedImages_缓存未命中_调用API() = runTest {
        coEvery { tmdbApiService.getPersonTaggedImages(1, 1) } returns Response.success(
            TmdbPersonTaggedImagesResponse(id = 1, results = listOf(
                TmdbTaggedImage(file_path = "/xyz.jpg")
            ))
        )

        val result = repository.getPersonTaggedImages(1)

        assertThat(result).hasSize(1)
        assertThat(result[0]).contains("/xyz.jpg")
        coVerify(exactly = 1) { tmdbApiService.getPersonTaggedImages(1, 1) }
    }

    // ==================== discover ====================

    @Test
    fun discover_MOVIE类型_调用discoverMovie() = runTest {
        val results = listOf(TmdbSearchResult(id = 1, title = "电影A"))
        coEvery {
            tmdbApiService.discoverMovie(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(TmdbSearchResponse(results = results, total_pages = 1, total_results = 1))

        val filter = TmdbRepository.DiscoverFilter(
            type = TmdbRepository.DiscoverType.MOVIE,
            genreIds = listOf(28),
            originCountries = listOf("CN"),
            keywordIds = emptyList(),
            voteAverageMin = 7f,
            voteAverageMax = 10f,
            releaseDateStart = "2024-01-01",
            releaseDateEnd = "2024-12-31",
            sortBy = TmdbRepository.DiscoverSort.POPULARITY_DESC,
            hideWatched = false
        )
        val result = repository.discover(filter, page = 1)

        assertThat(result.items).isEqualTo(results)
        assertThat(result.totalPages).isEqualTo(1)
        assertThat(result.totalResults).isEqualTo(1)
    }

    @Test
    fun discover_SHOW类型_调用discoverTv() = runTest {
        val results = listOf(TmdbSearchResult(id = 1, title = "剧集A"))
        coEvery {
            tmdbApiService.discoverTv(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(TmdbSearchResponse(results = results, total_pages = 1, total_results = 1))

        val filter = TmdbRepository.DiscoverFilter(
            type = TmdbRepository.DiscoverType.SHOW,
            genreIds = emptyList(),
            originCountries = emptyList(),
            keywordIds = emptyList(),
            voteAverageMin = 0f,
            voteAverageMax = 10f,
            releaseDateStart = null,
            releaseDateEnd = null,
            sortBy = TmdbRepository.DiscoverSort.VOTE_AVERAGE_DESC,
            hideWatched = false
        )
        val result = repository.discover(filter, page = 1)

        assertThat(result.items).isEqualTo(results)
    }

    @Test
    fun discover_网络失败_抛出异常给调用方() = runTest {
        coEvery {
            tmdbApiService.discoverMovie(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws IOException("网络错误")

        val filter = TmdbRepository.DiscoverFilter(
            type = TmdbRepository.DiscoverType.MOVIE,
            genreIds = emptyList(), originCountries = emptyList(),
            keywordIds = emptyList(),
            voteAverageMin = 0f, voteAverageMax = 10f,
            releaseDateStart = null, releaseDateEnd = null,
            sortBy = TmdbRepository.DiscoverSort.POPULARITY_DESC,
            hideWatched = false
        )

        // 失败不再降级成空页：筛选页要靠这个异常区分「请求失败」与「确实没有符合条件的结果」
        try {
            repository.discover(filter, page = 1)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertThat(e).hasMessageThat().isEqualTo("网络错误")
        }
    }


    @Test
    fun discover_评分下限0不传参() = runTest {
        coEvery {
            tmdbApiService.discoverMovie(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(TmdbSearchResponse())

        val filter = TmdbRepository.DiscoverFilter(
            type = TmdbRepository.DiscoverType.MOVIE,
            genreIds = emptyList(), originCountries = emptyList(),
            keywordIds = emptyList(),
            voteAverageMin = 0f, voteAverageMax = 10f,
            releaseDateStart = null, releaseDateEnd = null,
            sortBy = TmdbRepository.DiscoverSort.POPULARITY_DESC,
            hideWatched = false
        )
        repository.discover(filter, page = 1)

        // voteAverageGte=null, voteAverageLte=null, voteCountGte=null（边界值不传）
        coVerify {
            tmdbApiService.discoverMovie(any(), any(), any(), any(), any(), null, null, null, any(), any(), any(), any())
        }
    }

    /** 多选是「任选其一」：TMDB 里逗号是 AND、竖线才是 OR，用逗号时两个关键词直接 0 条 */
    @Test
    fun discover_多选类型地区标签_用竖线拼成OR() = runTest {
        coEvery {
            tmdbApiService.discoverMovie(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(TmdbSearchResponse())

        val filter = TmdbRepository.DiscoverFilter(
            type = TmdbRepository.DiscoverType.MOVIE,
            genreIds = listOf(28, 35),
            originCountries = listOf("CN", "JP"),
            keywordIds = listOf(180547, 2076),
            voteAverageMin = 0f, voteAverageMax = 10f,
            releaseDateStart = null, releaseDateEnd = null,
            sortBy = TmdbRepository.DiscoverSort.POPULARITY_DESC,
            hideWatched = false
        )
        repository.discover(filter, page = 1)

        coVerify {
            tmdbApiService.discoverMovie(
                any(), any(),
                "28|35", "CN|JP", "180547|2076",
                any(), any(), any(), any(), any(), any(), any()
            )
        }
    }

    /** 剧集的「上映日期」排序要用 first_air_date：primary_release_date 是电影专用，TMDB 会静默忽略 */
    @Test
    fun discover_SHOW按上映日期排序_用first_air_date() = runTest {
        coEvery {
            tmdbApiService.discoverTv(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(TmdbSearchResponse())

        val filter = TmdbRepository.DiscoverFilter(
            type = TmdbRepository.DiscoverType.SHOW,
            genreIds = emptyList(), originCountries = emptyList(), keywordIds = emptyList(),
            voteAverageMin = 0f, voteAverageMax = 10f,
            releaseDateStart = null, releaseDateEnd = null,
            sortBy = TmdbRepository.DiscoverSort.RELEASE_DATE_DESC,
            hideWatched = false
        )
        repository.discover(filter, page = 1)

        coVerify {
            tmdbApiService.discoverTv(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                "first_air_date.desc", any()
            )
        }
    }

    @Test
    fun discover_MOVIE按上映日期排序_用primary_release_date() = runTest {
        coEvery {
            tmdbApiService.discoverMovie(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(TmdbSearchResponse())

        val filter = TmdbRepository.DiscoverFilter(
            type = TmdbRepository.DiscoverType.MOVIE,
            genreIds = emptyList(), originCountries = emptyList(), keywordIds = emptyList(),
            voteAverageMin = 0f, voteAverageMax = 10f,
            releaseDateStart = null, releaseDateEnd = null,
            sortBy = TmdbRepository.DiscoverSort.RELEASE_DATE_DESC,
            hideWatched = false
        )
        repository.discover(filter, page = 1)

        coVerify {
            tmdbApiService.discoverMovie(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                "primary_release_date.desc", any()
            )
        }
    }

    /** 按评分排序必须带最低投票数，否则榜首全是 1 票的 10.0 分冷门片 */
    @Test
    fun discover_按评分排序_带最低投票数() = runTest {
        coEvery {
            tmdbApiService.discoverMovie(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(TmdbSearchResponse())

        val filter = TmdbRepository.DiscoverFilter(
            type = TmdbRepository.DiscoverType.MOVIE,
            genreIds = emptyList(), originCountries = emptyList(), keywordIds = emptyList(),
            // 评分区间是满量程：只有排序方式要求下限
            voteAverageMin = 0f, voteAverageMax = 10f,
            releaseDateStart = null, releaseDateEnd = null,
            sortBy = TmdbRepository.DiscoverSort.VOTE_AVERAGE_DESC,
            hideWatched = false
        )
        repository.discover(filter, page = 1)

        coVerify {
            tmdbApiService.discoverMovie(
                any(), any(), any(), any(), any(), any(), any(), 200, any(), any(), any(), any()
            )
        }
    }

    /** 通用浏览入口不带成人内容 */
    @Test
    fun discover_不包含成人内容() = runTest {
        coEvery {
            tmdbApiService.discoverMovie(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(TmdbSearchResponse())

        val filter = TmdbRepository.DiscoverFilter(
            type = TmdbRepository.DiscoverType.MOVIE,
            genreIds = emptyList(), originCountries = emptyList(), keywordIds = emptyList(),
            voteAverageMin = 0f, voteAverageMax = 10f,
            releaseDateStart = null, releaseDateEnd = null,
            sortBy = TmdbRepository.DiscoverSort.POPULARITY_DESC,
            hideWatched = false
        )
        repository.discover(filter, page = 1)

        coVerify {
            tmdbApiService.discoverMovie(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), false
            )
        }
    }
}
