package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.SplashPosterStore
import com.tracktosearch.data.local.SplashQuote
import com.tracktosearch.data.local.SplashQuoteCatalog
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
 */
class SplashQuoteRepositoryTest {

    private val catalog = mockk<SplashQuoteCatalog>()
    private val posterStore = mockk<SplashPosterStore>(relaxed = true)

    private fun repository() = SplashQuoteRepository(catalog, posterStore)

    private fun quote(id: String) = SplashQuote(
        id = id,
        year = 1994,
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
        coEvery { posterStore.download(any()) } returns true

        repository().prefetchUpcoming(days = 7)

        coVerify(exactly = 0) { posterStore.download(ready) }
        coVerify(exactly = 1) { posterStore.download(pool[1]) }
        coVerify(exactly = 1) { posterStore.download(pool[2]) }
    }

    @Test
    fun `prefetchAll 先清掉已下线台词的遗留文件再补齐整池`() = runTest {
        val pool = listOf("a", "b").map { quote(it) }
        coEvery { catalog.quotes() } returns pool
        every { posterStore.isReady(any()) } returns false
        coEvery { posterStore.download(any()) } returns true

        repository().prefetchAll()

        coVerify(exactly = 1) { posterStore.pruneOrphans(setOf("a", "b")) }
        coVerify(exactly = 1) { posterStore.download(pool[0]) }
        coVerify(exactly = 1) { posterStore.download(pool[1]) }
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
}
