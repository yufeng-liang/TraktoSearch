package com.tracktosearch.data.remote.douban

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.CloudDetailsPoolManager
import com.tracktosearch.data.repository.DoubanPublicDataPoolManager
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DoubanRepository 单元测试。
 *
 * 覆盖：
 * - fetchDetail 四层缓存链路（内存 → 磁盘 awaitLoaded → 全局池 → 爬豆瓣）
 * - forceRefresh 跳过缓存
 * - Cookie 过期不重试
 * - 网络异常重试逻辑
 * - 反爬延迟事件（DOUBAN_DETAIL_CRAWL / DOUBAN_RETRY）
 * - findDoubanId 链路（idMappingCache → 详情缓存 → 网络搜索）
 * - putDoubanIdMapping / getDetailSnapshot
 * - 标记写回（markInterest / markWatchedWithRating / removeMark）
 * - fetchCsrfToken / fetchDetailPageHtml
 * - DoubanMarkStatus.fromString
 * - toDetailInfo 数据转换
 *
 * 测试策略：
 * - MockWebServer 模拟豆瓣 HTTP 响应
 * - 反射替换 DoubanRepository 内部的 client/noRedirectClient 为指向 MockWebServer 的实例
 * - spyk + mock private delayWithEvent 跳过 3-5 秒反爬延迟（避免测试过慢）
 * - mock PersistentTtlCache 和 CloudDetailsPoolManager
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DoubanRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val detailCache = mockk<PersistentTtlCache<DoubanDetailCacheEntry>>(relaxed = true)
    private val cloudPool = mockk<CloudDetailsPoolManager>(relaxed = true)
    private val publicPool = mockk<DoubanPublicDataPoolManager>(relaxed = true)
    private val idMappingCache = mockk<PersistentTtlCache<String>>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private lateinit var repository: DoubanRepository
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setup(): Unit = runBlocking {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        repository = spyk(
            DoubanRepository(detailCache, cloudPool, json, idMappingCache, publicPool),
            recordPrivateCalls = true
        )

        // 反射替换 client 和 noRedirectClient，把所有豆瓣请求重定向到 MockWebServer
        val mockClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val newPath = original.url.encodedPath
                val newUrl = mockWebServer.url(newPath).newBuilder()
                    .query(original.url.encodedQuery)
                    .build()
                chain.proceed(original.newBuilder().url(newUrl).build())
            }
            .build()

        val clientField = DoubanRepository::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(repository, mockClient)

        val noRedirectField = DoubanRepository::class.java.getDeclaredField("noRedirectClient")
        noRedirectField.isAccessible = true
        noRedirectField.set(
            repository,
            mockClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
        )

        // mock 掉反爬延迟，避免测试等待 3-5 秒
        coEvery {
            repository["delayWithEvent"](any<DelayType>(), any<LongRange>())
        } returns Unit

        // detailCache 默认 awaitLoaded 不挂起
        coEvery { detailCache.awaitLoaded() } returns Unit
        coEvery { idMappingCache.awaitLoaded() } returns Unit
        coEvery {
            idMappingCache.get(match { key ->
                key.startsWith("imdb:") ||
                    key.startsWith("trakt:") ||
                    key.startsWith("tmdb:")
            })
        } returns null
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
    }

    // ==================== 测试数据 ====================

    private val testEntry = DoubanDetailCacheEntry(
        imdbId = "tt0000001",
        isTvShow = false,
        title = "情书",
        posterUrl = "https://img.doubanio.com/poster.jpg",
        genres = listOf("剧情", "爱情"),
        year = "1995",
        countries = listOf("日本"),
        directors = listOf("岩井俊二"),
        doubanRating = 8.9,
        ratingCount = 123456,
        summary = "一封寄往天国的信...",
        celebrities = listOf(
            DoubanCelebrityCacheEntry(name = "中山美穗", role = "饰 渡边博子")
        )
    )

    /** 构造一个最简详情页 HTML（标题非空 = 解析成功） */
    private fun detailHtml(
        title: String = "情书",
        imdbId: String = "tt0000001",
        isTvShow: Boolean = false
    ): String {
        val episodeInfo = if (isTvShow) {
            """<span class="pl">集数:</span> 12<br/>"""
        } else ""
        val imdbLine = if (imdbId.isNotBlank()) {
            """<span class="pl">IMDb:</span> $imdbId<br/>"""
        } else ""
        return """
            <html><head>
            <meta property="og:title" content="$title"/>
            <meta property="og:image" content="https://img.doubanio.com/poster.jpg"/>
            </head><body>
            <h1>$title (1995)</h1>
            <div id="info">
            <span class="pl">导演:</span> 岩井俊二<br/>
            <span class="pl">制片国家/地区:</span> 日本<br/>
            $episodeInfo
            $imdbLine
            </div>
            <strong class="ll rating_num" property="v:average">8.9</strong>
            <span property="v:votes">123456</span>
            </body></html>
        """.trimIndent()
    }

    /** 登录页 HTML（Cookie 过期） */
    private val loginPageHtml: String =
        """<html><head><title>登录豆瓣</title></head><body><form id="lzform"></form></body></html>"""

    // ==================== fetchMarkList error semantics ====================

    @Test
    fun fetchMarkList_登录页按Cookie过期处理且不重试(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody(loginPageHtml))

        val result = repository.fetchMarkList(
            userId = "user123",
            cookie = "expiredcookie",
            status = DoubanMarkStatus.WISH,
            onPage = { _, _ -> }
        )

        assertThat(result).isFalse()
        assertThat(mockWebServer.requestCount).isEqualTo(1)
    }

    @Test
    fun fetchDetail_内存缓存命中_不爬取不查全局池(): Unit = runBlocking {
        // detailCache.get 返回有效条目（标题非空）
        coEvery { detailCache.get("123") } returns testEntry

        val (info, cached) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        assertThat(cached).isTrue()
        assertThat(info?.title).isEqualTo("情书")
        assertThat(info?.imdbId).isEqualTo("tt0000001")
        // 不应查全局池
        coVerify(exactly = 0) { cloudPool.downloadDetail(any()) }
    }

    @Test
    fun fetchDetail_磁盘缓存命中_不爬取(): Unit = runBlocking {
        // 首次 get 返回 null（内存未命中），awaitLoaded 后 get 返回有效条目
        coEvery { detailCache.get("123") } returnsMany listOf(null, testEntry)

        val (info, cached) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        assertThat(cached).isTrue()
        assertThat(info?.title).isEqualTo("情书")
        // 应调用 awaitLoaded
        coVerify(atLeast = 1) { detailCache.awaitLoaded() }
        // 不应查全局池
        coVerify(exactly = 0) { cloudPool.downloadDetail(any()) }
    }

    @Test
    fun fetchDetail_全局池命中_写本地缓存不爬取(): Unit = runBlocking {
        // 本地缓存未命中
        coEvery { detailCache.get("123") } returns null
        // 全局池返回有效条目
        coEvery { cloudPool.downloadDetail("123") } returns testEntry

        val (info, cached) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        assertThat(cached).isTrue()
        assertThat(info?.title).isEqualTo("情书")
        // 全局池命中后应写本地缓存
        coVerify(atLeast = 1) { detailCache.put("123", testEntry) }
    }

    @Test
    fun fetchDetail_全局池异常_降级爬豆瓣(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns null
        // 全局池抛异常（网络错误等）
        coEvery { cloudPool.downloadDetail("123") } throws RuntimeException("网络错误")
        // MockWebServer 返回有效详情
        mockWebServer.enqueue(MockResponse().setBody(detailHtml()))

        val (info, cached) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        // 全局池异常被 runCatching 捕获，降级爬豆瓣
        assertThat(cached).isFalse()
        assertThat(info?.title).isEqualTo("情书")
        // 爬取成功后应写本地缓存
        coVerify(atLeast = 1) { detailCache.put("123", any()) }
    }

    @Test
    fun fetchDetail_全局池取消_向上传播且不降级爬豆瓣(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns null
        coEvery { cloudPool.downloadDetail("123") } throws CancellationException("pool download cancelled")
        mockWebServer.enqueue(MockResponse().setBody(detailHtml()))

        val thrown = runCatching {
            repository.fetchDetail(
                doubanUrl = "https://movie.douban.com/subject/123/",
                cookie = "testcookie"
            )
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(mockWebServer.requestCount).isEqualTo(0)
    }

    @Test
    fun fetchDetail_全未命中_爬取成功_写缓存上传全局池(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns null
        coEvery { cloudPool.downloadDetail("123") } returns null
        mockWebServer.enqueue(MockResponse().setBody(detailHtml()))

        val (info, cached) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        assertThat(cached).isFalse()
        assertThat(info?.title).isEqualTo("情书")
        assertThat(info?.imdbId).isEqualTo("tt0000001")
        // 写本地缓存
        coVerify(atLeast = 1) { detailCache.put("123", any()) }
        // 异步上传全局池（等待异步执行）
        Thread.sleep(200)
        coVerify(atLeast = 1) { cloudPool.uploadDetailEntry("123", any()) }
    }

    @Test
    fun fetchDetail_同步调用关闭即时详情池上传(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns null
        coEvery { cloudPool.downloadDetail("123") } returns null
        mockWebServer.enqueue(MockResponse().setBody(detailHtml()))

        val (info, cached) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie",
            uploadToCloudPool = false
        )

        assertThat(cached).isFalse()
        assertThat(info?.title).isEqualTo("情书")
        Thread.sleep(200)
        coVerify(exactly = 0) { cloudPool.uploadDetailEntry("123", any()) }
    }

    @Test
    fun fetchDetail_forceRefresh_跳过所有缓存直接爬取(): Unit = runBlocking {
        // 即使缓存命中，forceRefresh 也应跳过
        coEvery { detailCache.get("123") } returns testEntry
        coEvery { cloudPool.downloadDetail("123") } returns testEntry
        mockWebServer.enqueue(MockResponse().setBody(detailHtml(title = "情书1995")))

        val (info, cached) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie",
            forceRefresh = true
        )

        // forceRefresh 走爬取，cached=false
        assertThat(cached).isFalse()
        assertThat(info?.title).isEqualTo("情书1995")
        // 不应查缓存
        coVerify(exactly = 0) { detailCache.get(any()) }
        // 不应查全局池
        coVerify(exactly = 0) { cloudPool.downloadDetail(any()) }
    }

    @Test
    fun fetchDetail_Cookie过期_返回null不重试(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns null
        coEvery { cloudPool.downloadDetail("123") } returns null
        // MockWebServer 返回登录页
        mockWebServer.enqueue(MockResponse().setBody(loginPageHtml))

        val (info, cached) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "expiredcookie"
        )

        assertThat(info).isNull()
        assertThat(cached).isFalse()
        // Cookie 过期不重试，只请求一次
        assertThat(mockWebServer.requestCount).isEqualTo(1)
    }

    @Test
    fun fetchDetail_首次网络异常_重试一次成功(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns null
        coEvery { cloudPool.downloadDetail("123") } returns null
        // 第一次返回 500（网络异常），第二次返回有效详情
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        mockWebServer.enqueue(MockResponse().setBody(detailHtml()))

        val (info, cached) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        // 重试成功
        assertThat(info?.title).isEqualTo("情书")
        assertThat(cached).isFalse()
        // 应请求两次（首次 + 重试）
        assertThat(mockWebServer.requestCount).isEqualTo(2)
    }

    @Test
    fun fetchDetail_两次HTTP500_返回null表示解析失败(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns null
        coEvery { cloudPool.downloadDetail("123") } returns null
        // 两次都返回 500（OkHttp 对 500 不抛异常，fetchHtml 返回错误 body）
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        val (info, cached) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        // 两次错误页解析失败后按 null=失败契约返回 null，避免把空详情当成功
        assertThat(info).isNull()
        assertThat(cached).isFalse()
        assertThat(mockWebServer.requestCount).isEqualTo(2)
    }

    @Test
    fun fetchDetail_doubanUrl为纯ID_用作缓存key(): Unit = runBlocking {
        // doubanUrl 不是完整 URL 而是纯数字 ID
        coEvery { detailCache.get("123") } returns testEntry

        val (info, _) = repository.fetchDetail(
            doubanUrl = "123",
            cookie = "testcookie"
        )

        assertThat(info?.title).isEqualTo("情书")
        coVerify(atLeast = 1) { detailCache.get("123") }
    }

    @Test
    fun fetchDetail_爬取成功_写入完整字段(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns null
        coEvery { cloudPool.downloadDetail("123") } returns null
        mockWebServer.enqueue(MockResponse().setBody(detailHtml()))

        repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        // 验证写入缓存的条目包含完整字段
        coVerify {
            detailCache.put("123", withArg { entry ->
                assertThat(entry.title).isEqualTo("情书")
                assertThat(entry.imdbId).isEqualTo("tt0000001")
                assertThat(entry.isTvShow).isFalse()
                assertThat(entry.doubanRating).isEqualTo(8.9)
                assertThat(entry.ratingCount).isEqualTo(123456)
                assertThat(entry.directors).contains("岩井俊二")
                assertThat(entry.countries).contains("日本")
                assertThat(entry.year).isEqualTo("1995")
            })
        }
    }

    @Test
    fun fetchDetail_爬取成功_回调fetching和done(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns null
        coEvery { cloudPool.downloadDetail("123") } returns null
        mockWebServer.enqueue(MockResponse().setBody(detailHtml()))

        val phases = mutableListOf<String>()
        repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie",
            title = "情书"
        ) { phase, _ -> phases.add(phase) }

        assertThat(phases).containsAtLeast("fetching", "done")
    }

    @Test
    fun findDoubanId_idMappingCache命中_直接返回(): Unit = runBlocking {
        coEvery { idMappingCache.get("100_movie") } returns "123"

        val result = repository.findDoubanId(traktId = 100, imdbId = "tt0000001", mediaType = "movie")

        assertThat(result).isEqualTo("123")
        // 不应查详情缓存
        coVerify(exactly = 0) { detailCache.snapshotFromDisk() }
    }

    @Test
    fun findDoubanId_内存未命中磁盘命中_返回(): Unit = runBlocking {
        coEvery { idMappingCache.get("100_movie") } returnsMany listOf(null, "123")

        val result = repository.findDoubanId(traktId = 100, imdbId = "tt0000001", mediaType = "movie")

        assertThat(result).isEqualTo("123")
        coVerify(atLeast = 1) { idMappingCache.awaitLoaded() }
    }

    @Test
    fun findDoubanId_缓存未命中_详情缓存有imdbId匹配_返回并写映射(): Unit = runBlocking {
        coEvery { idMappingCache.get("100_movie") } returns null
        // 详情缓存快照包含 imdbId 匹配的条目
        coEvery { detailCache.snapshotFromDisk() } returns mapOf("123" to testEntry)

        val result = repository.findDoubanId(traktId = 100, imdbId = "tt0000001", mediaType = "movie")

        assertThat(result).isEqualTo("123")
        // 应写入映射缓存
        coVerify { idMappingCache.put("100_movie", "123") }
    }

    @Test
    fun findDoubanId_缓存全未命中_网络搜索命中_写映射(): Unit = runBlocking {
        coEvery { idMappingCache.get("100_movie") } returns null
        coEvery { detailCache.snapshotFromDisk() } returns emptyMap()
        // MockWebServer 返回搜索结果页
        val searchHtml = """
            <html><body>
            <ul class="search_results_subjects">
            <li><a href="/movie/subject/456/">link</a>
            <span class="subject-title">情书</span>
            <span class="rating-stars" data-rating="89.0"></span>
            </li>
            </ul>
            </body></html>
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setBody(searchHtml))

        val result = repository.findDoubanId(traktId = 100, imdbId = "tt0000001", mediaType = "movie")

        assertThat(result).isEqualTo("456")
        coVerify { idMappingCache.put("100_movie", "456") }
    }

    @Test
    fun findDoubanId_imdbId为空_返回null(): Unit = runBlocking {
        coEvery { idMappingCache.get("100_movie") } returns null

        val result = repository.findDoubanId(traktId = 100, imdbId = null, mediaType = "movie")

        assertThat(result).isNull()
        // 不应查详情缓存（imdbId 为空时跳过）
        coVerify(exactly = 0) { detailCache.snapshotFromDisk() }
    }

    @Test
    fun findDoubanId_网络搜索无结果_返回null(): Unit = runBlocking {
        coEvery { idMappingCache.get("100_movie") } returns null
        coEvery { detailCache.snapshotFromDisk() } returns emptyMap()
        // 空搜索结果
        mockWebServer.enqueue(MockResponse().setBody("<html><body>no results</body></html>"))

        val result = repository.findDoubanId(traktId = 100, imdbId = "tt9999999", mediaType = "movie")

        assertThat(result).isNull()
    }

    @Test
    fun findDoubanId_traktId为零_公共池按IMDb命中_不搜索豆瓣(): Unit = runBlocking {
        coEvery { idMappingCache.get(any()) } returns null
        coEvery { publicPool.getMappings(listOf("imdb:tt0000001:movie")) } returns
            mapOf("imdb:tt0000001:movie" to "789")

        val result = repository.findDoubanId(
            traktId = 0,
            imdbId = "tt0000001",
            mediaType = "movie"
        )

        assertThat(result).isEqualTo("789")
        coVerify(exactly = 1) { publicPool.getMappings(listOf("imdb:tt0000001:movie")) }
        coVerify(exactly = 0) { detailCache.snapshotFromDisk() }
        coVerify(exactly = 0) { idMappingCache.get("0_movie") }
    }

    @Test
    fun findDoubanId_noMatch_isNegativeCached(): Unit = runBlocking {
        coEvery { idMappingCache.get(any()) } returns null
        coEvery { publicPool.getMappings(any()) } returns emptyMap()
        coEvery { detailCache.snapshotFromDisk() } returns emptyMap()
        mockWebServer.enqueue(MockResponse().setBody("<html><body>no results</body></html>"))

        val first = repository.findDoubanId(0, "tt-no-match", "movie", tmdbId = 0)
        val second = repository.findDoubanId(0, "tt-no-match", "movie", tmdbId = 0)

        assertThat(first).isNull()
        assertThat(second).isNull()
        assertThat(mockWebServer.requestCount).isEqualTo(1)
        coVerify(exactly = 1) { publicPool.getMappings(any()) }
    }

    @Test
    fun findDoubanId_concurrentMisses_shareSearchRequest(): Unit = runBlocking {
        coEvery { idMappingCache.get(any()) } returns null
        coEvery { publicPool.getMappings(any()) } returns emptyMap()
        coEvery { detailCache.snapshotFromDisk() } returns emptyMap()
        mockWebServer.enqueue(
            MockResponse()
                .setBody("<html><body>no results</body></html>")
                .setBodyDelay(150, TimeUnit.MILLISECONDS)
        )

        val first = async {
            repository.findDoubanId(0, "tt-concurrent", "movie", tmdbId = 0)
        }
        val second = async {
            repository.findDoubanId(0, "tt-concurrent", "movie", tmdbId = 0)
        }

        assertThat(first.await()).isNull()
        assertThat(second.await()).isNull()
        assertThat(mockWebServer.requestCount).isEqualTo(1)
    }

    @Test
    fun findDoubanId_仅TMDBId_公共池命中并持久化映射(): Unit = runBlocking {
        coEvery { idMappingCache.get(any()) } returns null
        coEvery { publicPool.getMappings(listOf("tmdb:42:show")) } returns
            mapOf("tmdb:42:show" to "900")

        val result = repository.findDoubanId(
            traktId = 0,
            imdbId = null,
            mediaType = "show",
            tmdbId = 42
        )

        assertThat(result).isEqualTo("900")
        coVerify { idMappingCache.put("tmdb:42:show", "900") }
        coVerify(exactly = 1) { publicPool.getMappings(listOf("tmdb:42:show")) }
    }

    // ==================== putDoubanIdMapping ====================

    @Test
    fun putDoubanIdMapping_写入idMappingCache() {
        repository.putDoubanIdMapping(traktId = 100, mediaType = "movie", doubanId = "123")

        coVerify { idMappingCache.put("100_movie", "123") }
    }

    @Test
    fun putDoubanIdMapping_traktId为零_只写有效公共外部Id() {
        repository.putDoubanIdMapping(
            traktId = 0,
            mediaType = "movie",
            doubanId = "789",
            imdbId = "tt0000001",
            tmdbId = 42
        )

        coVerify { idMappingCache.put("imdb:tt0000001:movie", "789") }
        coVerify { idMappingCache.put("tmdb:42:movie", "789") }
        coVerify(exactly = 0) { idMappingCache.put("0_movie", any()) }
        coVerify(exactly = 0) { idMappingCache.put("trakt:0:movie", any()) }
    }

    // ==================== getDetailSnapshot ====================

    @Test
    fun getDetailSnapshot_调用awaitLoaded和snapshotFromDisk(): Unit = runBlocking {
        coEvery { detailCache.snapshotFromDisk() } returns mapOf("123" to testEntry)

        val result = repository.getDetailSnapshot()

        assertThat(result).hasSize(1)
        assertThat(result["123"]?.title).isEqualTo("情书")
        coVerify(atLeast = 1) { detailCache.awaitLoaded() }
        coVerify(atLeast = 1) { detailCache.snapshotFromDisk() }
    }

    @Test
    fun fetchCsrfToken_正常返回ck(): Unit = runBlocking {
        val html = """
            <html><body>
            <input type="hidden" name="ck" value="abc123"/>
            </body></html>
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setBody(html))

        val ck = repository.fetchCsrfToken("123", "testcookie")

        assertThat(ck).isEqualTo("abc123")
    }

    @Test
    fun fetchDetailPageHtml_正常返回HTML(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody(detailHtml()))

        val html = repository.fetchDetailPageHtml("123", "testcookie")

        assertThat(html).isNotNull()
        assertThat(html).contains("情书")
    }

    private val cookieWithCk = "dbcl2=\"1234567:abc\"; ck=AbCd; bid=xyz"

    @Test
    fun markInterest_r0返回成功(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody("""{"r":0}"""))

        val result = repository.markWish("123", cookieWithCk)

        assertThat(result).isTrue()
    }

    @Test
    fun markInterest_HTTP200但r1返回失败(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody("""{"r":1,"msg":"已标记"}"""))

        val result = repository.markWish("123", cookieWithCk)

        assertThat(result).isFalse()
    }

    @Test
    fun removeInterest_r0返回成功(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody("""{"r":0,"result":"y"}"""))

        val result = repository.removeInterest("123", cookieWithCk)

        assertThat(result).isTrue()
    }

    @Test
    fun removeInterest_HTTP200但r1返回失败(): Unit = runBlocking {
        // j_cat_ui 返回带空格的 JSON，字面量匹配 "\"r\":0" 会漏判，必须按 r 字段取值
        mockWebServer.enqueue(MockResponse().setBody("""{"r": 1, "code": 403}"""))

        val result = repository.removeInterest("123", cookieWithCk)

        assertThat(result).isFalse()
    }

    @Test
    fun markWatchedWithRating_成功(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody("""{"r":0}"""))

        val result = repository.markWatchedWithRating("123", "testcookie", "ck123", rating = 5)

        assertThat(result.success).isTrue()
        // 验证请求体包含 rating=5 和 interest=collect
        val recordedRequest = mockWebServer.takeRequest()
        val body = recordedRequest.body.readUtf8()
        assertThat(body).contains("rating=5")
        assertThat(body).contains("interest=collect")
    }

    @Test
    fun markWatchedWithRating_rating被coerceIn到1_5(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody("""{"r":0}"""))

        repository.markWatchedWithRating("123", "testcookie", "ck123", rating = 10)

        val body = mockWebServer.takeRequest().body.readUtf8()
        // rating=10 应被强制为 5
        assertThat(body).contains("rating=5")
    }

    @Test
    fun markWatchedWithRating_短评被提交(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody("""{"r":0}"""))

        repository.markWatchedWithRating("123", "testcookie", "ck123", rating = 4, comment = "好看")

        val body = mockWebServer.takeRequest().body.readUtf8()
        assertThat(body).contains("comment=")
    }

    // ==================== removeMark ====================

    @Test
    fun removeMark_302重定向回详情页_成功(): Unit = runBlocking {
        // 302 重定向到详情页（noRedirectClient 不跟随重定向）
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/subject/123/")
                .setBody("")
        )

        val result = repository.removeMark("123", "testcookie", "ck123")

        assertThat(result.success).isTrue()
        assertThat(result.statusCode).isEqualTo(302)
    }

    @Test
    fun removeMark_非302_失败(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val result = repository.removeMark("123", "testcookie", "ck123")

        assertThat(result.success).isFalse()
        assertThat(result.statusCode).isEqualTo(200)
    }

    @Test
    fun removeMark_302但Location不匹配_失败(): Unit = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/login")
                .setBody("")
        )

        val result = repository.removeMark("123", "testcookie", "ck123")

        assertThat(result.success).isFalse()
    }

    // ==================== fetchRecommend ====================

    @Test
    fun fetchRecommend_403抛DoubanRateLimitedException(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))

        try {
            repository.fetchRecommend("tv", "expiredcookie")
            assertThat(false).isTrue()
        } catch (e: DoubanRateLimitedException) {
            assertThat(e.message).contains("Douban rate limited")
        }
    }

    @Test
    fun fetchRecommend_登录页HTML抛CookieExpiredException(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody(loginPageHtml))

        try {
            repository.fetchRecommend("movie", "expiredcookie")
            assertThat(false).isTrue()
        } catch (e: DoubanCookieExpiredException) {
            // 预期异常
        }
    }

    @Test
    fun searchDoubanIdByImdb_正常返回doubanId(): Unit = runBlocking {
        val searchHtml = """
            <html><body>
            <ul class="search_results_subjects">
            <li><a href="/movie/subject/456/">link</a>
            <span class="subject-title">情书</span>
            </li>
            </ul>
            </body></html>
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setBody(searchHtml))

        val doubanId = repository.searchDoubanIdByImdb("tt0000001")

        assertThat(doubanId).isEqualTo("456")
    }

    @Test
    fun searchDoubanIdByImdb_无结果返回null(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody("<html><body>no results</body></html>"))

        val doubanId = repository.searchDoubanIdByImdb("tt9999999")

        assertThat(doubanId).isNull()
    }
}
