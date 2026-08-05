package com.tracktosearch.data.remote.douban

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.CloudDetailsPoolManager
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val idMappingCache = mockk<PersistentTtlCache<String>>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private lateinit var repository: DoubanRepository
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setup(): Unit = runBlocking {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        repository = spyk(
            DoubanRepository(detailCache, cloudPool, json, idMappingCache),
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
    // ==================== fetchMarkList error semantics ====================

    @Test
    fun fetchMarkList_HTTP500重试耗尽抛出网络异常(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        var thrown: Throwable? = null
        try {
            repository.fetchMarkList(
                userId = "user123",
                cookie = "testcookie",
                status = DoubanMarkStatus.WISH,
                onPage = { _, _ -> }
            )
        } catch (e: Throwable) {
            thrown = e
        }

        assertThat(thrown).isInstanceOf(IOException::class.java)
        assertThat(thrown).isNotInstanceOf(DoubanCookieExpiredException::class.java)
        assertThat(mockWebServer.requestCount).isEqualTo(2)
    }

    @Test
    fun fetchMarkList_HTTP403按Cookie过期处理且不重试(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))

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

    private val loginPageHtml: String =
        """<html><head><title>登录豆瓣</title></head><body><form id="lzform"></form></body></html>"""

    // ==================== fetchDetail 四层链路 ====================

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
    fun fetchDetail_缓存标题为空_不视为命中(): Unit = runBlocking {
        // 缓存存在但标题为空（字段不完善），不应视为命中
        val blankTitleEntry = testEntry.copy(title = null)
        coEvery { detailCache.get("123") } returns blankTitleEntry
        // 全局池也返回标题为空的条目
        coEvery { cloudPool.downloadDetail("123") } returns blankTitleEntry
        // MockWebServer 返回有效详情
        mockWebServer.enqueue(MockResponse().setBody(detailHtml()))

        val (info, cached) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        // 应走爬取（cached=false）
        assertThat(cached).isFalse()
        assertThat(info?.title).isEqualTo("情书")
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
    fun fetchDetail_全局池返回标题为空_降级爬豆瓣(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns null
        // 全局池返回标题为空的条目（字段不完善）
        coEvery { cloudPool.downloadDetail("123") } returns testEntry.copy(title = null)
        mockWebServer.enqueue(MockResponse().setBody(detailHtml()))

        val (info, cached) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        // 全局池条目标题为空，降级爬豆瓣
        assertThat(cached).isFalse()
        assertThat(info?.title).isEqualTo("情书")
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
    fun fetchDetail_两次HTTP500_返回字段为空的详情(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns null
        coEvery { cloudPool.downloadDetail("123") } returns null
        // 两次都返回 500（OkHttp 对 500 不抛异常，fetchHtml 返回错误 body）
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        val (info, cached) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        // 500 错误页解析后 title 为空，但对象本身非 null
        assertThat(info).isNotNull()
        assertThat(info?.title).isNull()
        assertThat(info?.imdbId).isNull()
        assertThat(cached).isFalse()
        assertThat(mockWebServer.requestCount).isEqualTo(2)
    }

    @Test
    fun fetchDetail_标题为空_重试一次(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns null
        coEvery { cloudPool.downloadDetail("123") } returns null
        // 第一次返回标题为空的 HTML（解析后标题为空），第二次返回有效详情
        mockWebServer.enqueue(MockResponse().setBody("<html><body>empty</body></html>"))
        mockWebServer.enqueue(MockResponse().setBody(detailHtml()))

        val (info, _) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        // 重试后成功
        assertThat(info?.title).isEqualTo("情书")
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
    fun fetchDetail_电视剧_集数正确解析(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns null
        coEvery { cloudPool.downloadDetail("123") } returns null
        mockWebServer.enqueue(MockResponse().setBody(detailHtml(isTvShow = true)))

        val (info, _) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        assertThat(info?.isTvShow).isTrue()
        assertThat(info?.episodeCount).isEqualTo(12)
    }

    // ==================== fetchDetail onProgress 回调 ====================

    @Test
    fun fetchDetail_缓存命中_回调cache_hit(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns testEntry

        val phases = mutableListOf<String>()
        repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie",
            title = "情书"
        ) { phase, _ -> phases.add(phase) }

        assertThat(phases).contains("cache_hit")
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
    fun fetchDetail_爬取失败_回调fetching和failed(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns null
        coEvery { cloudPool.downloadDetail("123") } returns null
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        val phases = mutableListOf<String>()
        repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        ) { phase, _ -> phases.add(phase) }

        assertThat(phases).containsAtLeast("fetching", "failed")
    }

    // ==================== 反爬延迟事件 ====================

    @Test
    fun delayEvent_初始为null(): Unit = runBlocking {
        assertThat(repository.delayEvent.value).isNull()
    }

    // ==================== findDoubanId 链路 ====================

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
    fun findDoubanId_imdbId为空字符串_返回null(): Unit = runBlocking {
        coEvery { idMappingCache.get("100_movie") } returns null

        val result = repository.findDoubanId(traktId = 100, imdbId = "", mediaType = "movie")

        assertThat(result).isNull()
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

    // ==================== putDoubanIdMapping ====================

    @Test
    fun putDoubanIdMapping_写入idMappingCache() {
        repository.putDoubanIdMapping(traktId = 100, mediaType = "movie", doubanId = "123")

        coVerify { idMappingCache.put("100_movie", "123") }
    }

    @Test
    fun putDoubanIdMapping_show类型_key正确() {
        repository.putDoubanIdMapping(traktId = 200, mediaType = "show", doubanId = "456")

        coVerify { idMappingCache.put("200_show", "456") }
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
    fun getDetailSnapshot_空缓存返回空Map(): Unit = runBlocking {
        coEvery { detailCache.snapshotFromDisk() } returns emptyMap()

        val result = repository.getDetailSnapshot()

        assertThat(result).isEmpty()
    }

    // ==================== DoubanMarkStatus.fromString ====================

    @Test
    fun DoubanMarkStatus_fromString_wish返回WISH() {
        assertThat(DoubanMarkStatus.fromString("wish")).isEqualTo(DoubanMarkStatus.WISH)
    }

    @Test
    fun DoubanMarkStatus_fromString_collect返回COLLECT() {
        assertThat(DoubanMarkStatus.fromString("collect")).isEqualTo(DoubanMarkStatus.COLLECT)
    }

    @Test
    fun DoubanMarkStatus_fromString_null降级为WISH() {
        assertThat(DoubanMarkStatus.fromString(null)).isEqualTo(DoubanMarkStatus.WISH)
    }

    @Test
    fun DoubanMarkStatus_fromString_未知值降级为WISH() {
        assertThat(DoubanMarkStatus.fromString("unknown")).isEqualTo(DoubanMarkStatus.WISH)
    }

    @Test
    fun DoubanMarkStatus_fromString_大写名称也可解析() {
        assertThat(DoubanMarkStatus.fromString("WISH")).isEqualTo(DoubanMarkStatus.WISH)
        assertThat(DoubanMarkStatus.fromString("COLLECT")).isEqualTo(DoubanMarkStatus.COLLECT)
    }

    @Test
    fun DoubanMarkStatus_path字段正确() {
        assertThat(DoubanMarkStatus.WISH.path).isEqualTo("wish")
        assertThat(DoubanMarkStatus.COLLECT.path).isEqualTo("collect")
    }

    // ==================== toDetailInfo 数据转换 ====================

    @Test
    fun toDetailInfo_完整字段转换(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns testEntry

        val (info, _) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        assertThat(info?.imdbId).isEqualTo("tt0000001")
        assertThat(info?.isTvShow).isFalse()
        assertThat(info?.title).isEqualTo("情书")
        assertThat(info?.posterUrl).isEqualTo("https://img.doubanio.com/poster.jpg")
        assertThat(info?.genres).containsExactly("剧情", "爱情")
        assertThat(info?.year).isEqualTo("1995")
        assertThat(info?.countries).contains("日本")
        assertThat(info?.directors).contains("岩井俊二")
        assertThat(info?.doubanRating).isEqualTo(8.9)
        assertThat(info?.ratingCount).isEqualTo(123456)
        assertThat(info?.summary).contains("一封寄往天国的信")
    }

    @Test
    fun toDetailInfo_celebrities正确转换(): Unit = runBlocking {
        coEvery { detailCache.get("123") } returns testEntry

        val (info, _) = repository.fetchDetail(
            doubanUrl = "https://movie.douban.com/subject/123/",
            cookie = "testcookie"
        )

        assertThat(info?.celebrities).hasSize(1)
        assertThat(info?.celebrities?.first()?.name).isEqualTo("中山美穗")
        assertThat(info?.celebrities?.first()?.role).isEqualTo("饰 渡边博子")
    }

    // ==================== fetchCsrfToken / fetchDetailPageHtml ====================

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
    fun fetchCsrfToken_Cookie过期返回null(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody(loginPageHtml))

        val ck = repository.fetchCsrfToken("123", "expiredcookie")

        assertThat(ck).isNull()
    }

    @Test
    fun fetchCsrfToken_无ck字段返回null(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody("<html><body>no ck</body></html>"))

        val ck = repository.fetchCsrfToken("123", "testcookie")

        assertThat(ck).isNull()
    }

    @Test
    fun fetchDetailPageHtml_正常返回HTML(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody(detailHtml()))

        val html = repository.fetchDetailPageHtml("123", "testcookie")

        assertThat(html).isNotNull()
        assertThat(html).contains("情书")
    }

    @Test
    fun fetchDetailPageHtml_Cookie过期返回null(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody(loginPageHtml))

        val html = repository.fetchDetailPageHtml("123", "expiredcookie")

        assertThat(html).isNull()
    }

    // ==================== markInterest ====================

    @Test
    fun markInterest_成功返回r0(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody("""{"r":0}"""))

        val result = repository.markInterestByCk("wish", "123", "testcookie", "ck123")

        assertThat(result.success).isTrue()
        assertThat(result.statusCode).isEqualTo(200)
        assertThat(result.message).isEqualTo("r:0")
    }

    @Test
    fun markInterest_非r0返回失败(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody("""{"r":1,"msg":"已标记"}"""))

        val result = repository.markInterestByCk("wish", "123", "testcookie", "ck123")

        assertThat(result.success).isFalse()
        assertThat(result.statusCode).isEqualTo(200)
    }

    @Test
    fun markInterest_500返回失败(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        val result = repository.markInterestByCk("wish", "123", "testcookie", "ck123")

        assertThat(result.success).isFalse()
        assertThat(result.statusCode).isEqualTo(500)
    }

    // ==================== markWatchedWithRating ====================

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
    fun fetchRecommend_401抛CookieExpiredException(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        try {
            repository.fetchRecommend("movie", "expiredcookie")
            // 应抛异常，不应执行到这里
            assertThat(false).isTrue()
        } catch (e: DoubanCookieExpiredException) {
            assertThat(e.message).contains("Douban cookie expired")
        }
    }

    @Test
    fun fetchRecommend_403抛CookieExpiredException(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))

        try {
            repository.fetchRecommend("tv", "expiredcookie")
            assertThat(false).isTrue()
        } catch (e: DoubanCookieExpiredException) {
            // 预期异常
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
    fun fetchRecommend_正常返回过滤片单和广告(): Unit = runBlocking {
        val recommendJson = """
        {
            "items": [
                {"type": "movie", "title": "情书", "subject": {"id": "1"}},
                {"type": "tv", "title": "剧集A", "subject": {"id": "2"}},
                {"type": "playlist", "title": "片单"},
                {"type": "ad", "title": "广告"}
            ]
        }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setBody(recommendJson))

        val result = repository.fetchRecommend("movie", "testcookie")

        // 应过滤掉 playlist 和 ad，只保留 movie 和 tv
        assertThat(result).hasSize(2)
        assertThat(result[0].type).isEqualTo("movie")
        assertThat(result[1].type).isEqualTo("tv")
    }

    // ==================== fetchUserProfile ====================

    @Test
    fun fetchUserProfile_Cookie过期返回null(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody(loginPageHtml))

        val profile = repository.fetchUserProfile("user123", "expiredcookie")

        assertThat(profile).isNull()
    }

    @Test
    fun fetchUserProfile_正常解析昵称和头像(): Unit = runBlocking {
        val html = """
            <html><head>
            <meta property="og:title" content="小明"/>
            <meta property="og:image" content="https://img.doubanio.com/uuser123-1.jpg"/>
            </head><body>
            <img src="https://img.doubanio.com/uuser123-2.jpg" alt="头像"/>
            </body></html>
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setBody(html))

        val profile = repository.fetchUserProfile("user123", "testcookie")

        assertThat(profile).isNotNull()
        assertThat(profile?.userId).isEqualTo("user123")
        assertThat(profile?.nickname).isEqualTo("小明")
    }

    @Test
    fun fetchUserProfile_网络断开返回null(): Unit = runBlocking {
        // 用 DISCONNECT_AT_START 模拟真实网络异常（IOException），而非 500 状态码
        mockWebServer.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )

        val profile = repository.fetchUserProfile("user123", "testcookie")

        assertThat(profile).isNull()
    }

    // ==================== searchDoubanIdByImdb ====================

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

    @Test
    fun searchDoubanIdByImdb_网络异常返回null(): Unit = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        val doubanId = repository.searchDoubanIdByImdb("tt0000001")

        assertThat(doubanId).isNull()
    }
}
