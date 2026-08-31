package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.SplashPosterStore
import com.tracktosearch.data.local.SplashQuote
import com.tracktosearch.data.local.SplashQuoteCatalog
import com.tracktosearch.data.local.SplashQuoteStorage
import com.tracktosearch.data.util.PosterColorExtractor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

/**
 * 开屏台词选片与预取的单测。
 *
 * 重点验证「开屏永远不出现占位图」这条约束是怎么落地的：
 * 当天该展示的那条海报没就绪时，必须退到已就绪的子集里选，而不是把没图的那条硬推给 UI。
 * 海报路径查 TMDB 那条链路同理——查不到只是少了「跟详情页一致」，不能少一张图。
 */
class SplashQuoteRepositoryTest {

    private val catalog = mockk<SplashQuoteCatalog>()
    private val posterStore = mockk<SplashPosterStore>(relaxed = true)
    private val storage = mockk<SplashQuoteStorage>(relaxed = true)
    private val tmdbRepository = mockk<TmdbRepository>(relaxed = true)
    private val posterColorExtractor = mockk<PosterColorExtractor>(relaxed = true)

    /**
     * 默认按「固定开场序列早已展示完」配置，日期取模的用例才是常规路径。
     *
     * TMDB 默认查不到路径、海报字节默认读不出来：前者让选片走兜底，后者让取色预热早退，
     * 两条都不是这些用例的被测对象，喂空值免得它们干扰下载次数的断言。
     */
    private fun repository() = SplashQuoteRepository(
        catalog,
        posterStore,
        storage,
        tmdbRepository,
        posterColorExtractor,
    ).also {
        coEvery { storage.openingSequenceIndex(any(), any()) } returns null
        coEvery { tmdbRepository.posterPath(any(), any()) } returns null
        coEvery { posterColorExtractor.getCachedColor(any()) } returns null
        coEvery { posterStore.readBytes(any()) } returns null
    }

    private fun quote(id: String, mediaType: String = SplashQuote.MEDIA_TYPE_MOVIE) = SplashQuote(
        id = id,
        year = 1994,
        mediaType = mediaType,
        tmdbId = 1,
        posterPath = "/$id.jpg",
        bundled = false,
        lines = mapOf("en" to listOf("line")),
        title = mapOf("en" to id),
    )

    /** 与被测代码一致的日期种子：测试和实现读同一个时钟，结果可预期 */
    private fun seed(): Long = LocalDate.now().toEpochDay()

    @Test
    fun `指定条海报就绪时返回按日期算出的那一条`() = runTest {
        val pool = listOf("a", "b", "c", "d", "e").map { quote(it) }
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } returns true

        val result = repository().todayQuote()

        assertThat(result).isEqualTo(pool[(seed() % pool.size).toInt()])
    }

    @Test
    fun `指定条未就绪时回落到已就绪子集而不是返回没海报的那条`() = runTest {
        val pool = listOf("a", "b", "c", "d", "e").map { quote(it) }
        val designated = pool[(seed() % pool.size).toInt()]
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } answers { firstArg<SplashQuote>() != designated }

        val result = repository().todayQuote()

        assertThat(result).isNotNull()
        assertThat(result).isNotEqualTo(designated)
        val ready = pool.filter { it != designated }
        assertThat(result).isEqualTo(ready[(seed() % ready.size).toInt()])
    }

    @Test
    fun `整池海报都没就绪时返回 null 让调用方整层跳过`() = runTest {
        coEvery { catalog.quotes() } returns listOf(quote("a"), quote("b"))
        every { posterStore.isReady(any()) } returns false

        assertThat(repository().todayQuote()).isNull()
    }

    @Test
    fun `台词库为空时返回 null`() = runTest {
        coEvery { catalog.quotes() } returns emptyList()

        assertThat(repository().todayQuote()).isNull()
    }

    @Test
    fun `同一天多次调用返回同一条`() = runTest {
        val pool = listOf("a", "b", "c").map { quote(it) }
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } returns true
        val repository = repository()

        assertThat(repository.todayQuote()).isEqualTo(repository.todayQuote())
    }

    @Test
    fun `prefetchUpcoming 跳过已就绪的海报只下载缺的`() = runTest {
        val pool = listOf("a", "b", "c").map { quote(it) }
        val ready = pool[0]
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } answers { firstArg<SplashQuote>() == ready }
        coEvery { posterStore.download(any(), any()) } returns true

        repository().prefetchUpcoming(days = 7)

        coVerify(exactly = 0) { posterStore.download(ready, any()) }
        coVerify(exactly = 1) { posterStore.download(pool[1], any()) }
        coVerify(exactly = 1) { posterStore.download(pool[2], any()) }
    }

    @Test
    fun `prefetchAll 先清掉已下线台词的遗留文件再补齐整池`() = runTest {
        val pool = listOf("a", "b").map { quote(it) }
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } returns false
        coEvery { posterStore.download(any(), any()) } returns true

        repository().prefetchAll()

        coVerify(exactly = 1) { posterStore.pruneOrphans(setOf("a", "b")) }
        coVerify(exactly = 1) { posterStore.download(pool[0], any()) }
        coVerify(exactly = 1) { posterStore.download(pool[1], any()) }
    }

    @Test
    fun `isPoolComplete 只有整池都就绪才为 true`() = runTest {
        val pool = listOf("a", "b").map { quote(it) }
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } answers { firstArg<SplashQuote>() == pool[0] }

        assertThat(repository().isPoolComplete()).isFalse()

        every { posterStore.isReady(any()) } returns true
        assertThat(repository().isPoolComplete()).isTrue()
    }

    @Test
    fun `isPoolComplete 台词库为空时为 false`() = runTest {
        coEvery { catalog.quotes() } returns emptyList()

        assertThat(repository().isPoolComplete()).isFalse()
    }

    @Test
    fun `固定开场序列按存储下标依次选三条`() = runTest {
        val opening = SplashQuoteRepository.OPENING_QUOTE_IDS.map { quote(it) }
        val pool = listOf(quote("a")) + opening + quote("z")
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } returns true
        val repository = repository()

        opening.forEachIndexed { index, expected ->
            coEvery { storage.openingSequenceIndex(any(), opening.size) } returns index
            assertThat(repository.todayQuote()).isEqualTo(expected)
        }
    }

    @Test
    fun `固定开场序列完成后回到日期取模`() = runTest {
        val pool = (listOf("a") + SplashQuoteRepository.OPENING_QUOTE_IDS + listOf("z"))
            .map { quote(it) }
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } returns true

        val result = repository().todayQuote()

        assertThat(result).isEqualTo(pool[(seed() % pool.size).toInt()])
    }

    @Test
    fun `台词库缺少当前固定条目时直接走日期取模`() = runTest {
        val pool = listOf("a", "b", "c").map { quote(it) }
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } returns true
        val repository = repository()
        coEvery { storage.openingSequenceIndex(any(), any()) } returns 1

        assertThat(repository.todayQuote()).isEqualTo(pool[(seed() % pool.size).toInt()])
    }

    @Test
    fun `markShown 只在展示当前固定条目时推进序列`() = runTest {
        val repository = repository()
        val index = 1
        coEvery { storage.openingSequenceIndex(any(), any()) } returns index

        repository.markShown("a")
        coVerify(exactly = 0) { storage.markOpeningQuoteShown(any(), any(), any()) }

        repository.markShown(SplashQuoteRepository.OPENING_QUOTE_IDS[index])
        coVerify(exactly = 1) {
            storage.markOpeningQuoteShown(index, seed(), SplashQuoteRepository.OPENING_QUOTE_IDS.size)
        }
    }

    /** 停留时长分两档要靠它，而它只在「今天真的看见过一次」之后才该翻面 */
    @Test
    fun `markShown 顺带记下今天看过`() = runTest {
        repository().markShown(quote("a"))

        coVerify(exactly = 1) { storage.markShownOn(seed()) }
    }

    @Test
    fun `isFirstShowToday 按本地日期问存储`() = runTest {
        val repository = repository()
        coEvery { storage.isFirstShowToday(seed()) } returns true

        assertThat(repository.isFirstShowToday()).isTrue()
    }

    @Test
    fun `下载用 TMDB 查到的那一版海报路径`() = runTest {
        val pool = listOf(quote("a"))
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } returns false
        coEvery { posterStore.download(any(), any()) } returns true
        val repository = repository()
        coEvery { tmdbRepository.posterPath(any(), any()) } returns "/from-tmdb.jpg"

        repository.prefetchUpcoming(days = 1)

        coVerify(exactly = 1) { posterStore.download(pool[0], "/from-tmdb.jpg") }
    }

    /** TMDB 不可达时仍要下得到图：这是「开屏永远不出现占位图」在网络层的兜底 */
    @Test
    fun `TMDB 查不到路径时退回台词库自带的那条`() = runTest {
        val pool = listOf(quote("a"))
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } returns false
        coEvery { posterStore.download(any(), any()) } returns true

        repository().prefetchUpcoming(days = 1)

        coVerify(exactly = 1) { posterStore.download(pool[0], "/a.jpg") }
    }

    /**
     * 剧集条目必须按剧集命名空间查。
     *
     * `/movie/{id}` 和 `/tv/{id}` 各自编号，查错接口不是 404 就是另一部片的海报，
     * 开屏会配上一张毫不相干的图。
     */
    @Test
    fun `剧集条目按 show 命名空间查海报路径`() = runTest {
        val pool = listOf(quote("s", mediaType = SplashQuote.MEDIA_TYPE_SHOW))
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } returns false
        coEvery { posterStore.download(any(), any()) } returns true

        repository().prefetchUpcoming(days = 1)

        coVerify(exactly = 1) { tmdbRepository.posterPath(1, MediaType.SHOW) }
        coVerify(exactly = 0) { tmdbRepository.posterPath(1, MediaType.MOVIE) }
    }

    /**
     * 取色预热用的地址必须是海报那一版的地址。
     *
     * 详情页按同一个地址查 PosterColorCache，算在别的 key 上等于没算。
     */
    @Test
    fun `开屏之后按同一个海报地址预热沉浸取色`() = runTest {
        val pool = listOf(quote("a"))
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } returns true
        val repository = repository()
        coEvery { tmdbRepository.posterPath(any(), any()) } returns "/from-tmdb.jpg"
        every { posterStore.rememberPosterUrl(pool[0], "/from-tmdb.jpg") } returns "poster-url"

        repository.prefetchUpcoming(days = 1)

        coVerify(exactly = 1) { posterColorExtractor.getCachedColor("poster-url") }
    }

    /** 已经算过主色的不再解一遍位图：开屏之后这趟应该只是一次缓存查询 */
    @Test
    fun `主色已缓存时不再读海报字节`() = runTest {
        val pool = listOf(quote("a"))
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } returns true
        val repository = repository()
        every { posterStore.rememberPosterUrl(any(), any()) } returns "poster-url"
        coEvery { posterColorExtractor.getCachedColor("poster-url") } returns 0xFF102030L

        repository.prefetchUpcoming(days = 1)

        coVerify(exactly = 0) { posterStore.readBytes(any()) }
    }
}
