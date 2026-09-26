package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.SplashPosterStore
import com.tracktosearch.data.local.SplashQuote
import com.tracktosearch.data.local.SplashQuoteCatalog
import com.tracktosearch.data.local.db.DailyStampDao
import com.tracktosearch.data.local.db.DailyStampEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 日签的写入与读出单测。
 *
 * 这里守的是三件事：当天只认第一次写入（同一天可能被写两次，而两次之间选中的台词会变）、
 * 月视图不越界（多取一天就会把上/下个月的格子画进来）、连续天数从今天往回数到断点为止。
 */
class DailyStampRepositoryTest {

    private val dao = mockk<DailyStampDao>(relaxed = true)
    private val quotes = mockk<SplashQuoteRepository>()
    private val catalog = mockk<SplashQuoteCatalog>()
    private val posterStore = mockk<SplashPosterStore>(relaxed = true)

    private fun repository() = DailyStampRepository(dao, quotes, catalog, posterStore)

    private fun quote(id: String) = SplashQuote(
        id = id,
        year = 1994,
        tmdbId = 1,
        posterPath = "/$id.jpg",
        lines = mapOf("en" to listOf("line")),
        title = mapOf("en" to id),
        keyword = mapOf("en" to id),
    )

    private fun entity(date: LocalDate, id: String) = DailyStampEntity(
        epochDay = date.toEpochDay(),
        quoteId = id,
        stampedAt = 0L,
    )

    private val today = LocalDate.of(2026, 8, 29)

    @Test
    fun `签到记下开屏真正展示过的那条而不是自己再算一次`() = runTest {
        // 固定开场序列和普通轮换都应记录屏幕真正展示的 id；仓库不自行重算，
        // 避免展示状态推进或海报就绪子集变化时把另一部片写进日签。
        coEvery { dao.find(today.toEpochDay()) } returns null
        coEvery { dao.insertIfAbsent(any()) } returns 1L
        coEvery { quotes.todayQuote() } returns quote("date-seeded")
        val captured = slot<DailyStampEntity>()

        assertThat(repository().checkIn("chungking-express", today)).isTrue()

        coVerify { dao.insertIfAbsent(capture(captured)) }
        assertThat(captured.captured.quoteId).isEqualTo("chungking-express")
        assertThat(captured.captured.epochDay).isEqualTo(today.toEpochDay())
    }

    @Test
    fun `没展示台词时按日期兜底那天照样算来过`() = runTest {
        coEvery { dao.find(any()) } returns null
        coEvery { dao.insertIfAbsent(any()) } returns 1L
        coEvery { quotes.todayQuote() } returns quote("date-seeded")
        val captured = slot<DailyStampEntity>()

        assertThat(repository().checkIn(null, today)).isTrue()

        coVerify { dao.insertIfAbsent(capture(captured)) }
        assertThat(captured.captured.quoteId).isEqualTo("date-seeded")
    }

    @Test
    fun `当天已签到时不再写入`() = runTest {
        coEvery { dao.find(today.toEpochDay()) } returns entity(today, "already")

        assertThat(repository().checkIn("other", today)).isFalse()

        coVerify(exactly = 0) { dao.insertIfAbsent(any()) }
    }

    /** 两个入口撞在一起时数据库层兜住：IGNORE 让首写留下，后写返回 -1 */
    @Test
    fun `写入被数据库忽略时返回 false`() = runTest {
        coEvery { dao.find(any()) } returns null
        coEvery { dao.insertIfAbsent(any()) } returns -1L

        assertThat(repository().checkIn("first", today)).isFalse()
    }

    @Test
    fun `连台词都拿不到时不记一个空日子`() = runTest {
        coEvery { dao.find(any()) } returns null
        coEvery { quotes.todayQuote() } returns null

        assertThat(repository().checkIn(null, today)).isFalse()

        coVerify(exactly = 0) { dao.insertIfAbsent(any()) }
    }

    @Test
    fun `月视图只查这个月的第一天到最后一天`() = runTest {
        coEvery { catalog.quotes() } returns emptyList()
        coEvery { dao.range(any(), any()) } returns emptyList()

        repository().month(YearMonth.of(2026, 2))

        // 2026 不是闰年：2 月 1 日到 2 月 28 日，多取一天就会把 3 月 1 日画进 2 月的格子里
        coVerify {
            dao.range(
                LocalDate.of(2026, 2, 1).toEpochDay(),
                LocalDate.of(2026, 2, 28).toEpochDay(),
            )
        }
    }

    @Test
    fun `闰年二月查到 29 号`() = runTest {
        coEvery { catalog.quotes() } returns emptyList()
        coEvery { dao.range(any(), any()) } returns emptyList()

        repository().month(YearMonth.of(2028, 2))

        coVerify {
            dao.range(
                LocalDate.of(2028, 2, 1).toEpochDay(),
                LocalDate.of(2028, 2, 29).toEpochDay(),
            )
        }
    }

    @Test
    fun `月视图把 id 解析成台词并按日期升序`() = runTest {
        val first = LocalDate.of(2026, 8, 3)
        val second = LocalDate.of(2026, 8, 17)
        coEvery { catalog.quotes() } returns listOf(quote("a"), quote("b"))
        coEvery { dao.range(any(), any()) } returns listOf(entity(first, "a"), entity(second, "b"))

        val days = repository().month(YearMonth.of(2026, 8))

        assertThat(days.map { it.date }).containsExactly(first, second).inOrder()
        assertThat(days.map { it.quote?.id }).containsExactly("a", "b").inOrder()
    }

    /** 台词 id 约定只增不删，真丢了也只是这一格点不开，不能拿空壳凑一张卡 */
    @Test
    fun `台词已下线的那天仍算来过但打不开`() = runTest {
        coEvery { catalog.quotes() } returns listOf(quote("a"))
        coEvery { dao.range(any(), any()) } returns listOf(entity(today, "gone"))

        val day = repository().month(YearMonth.of(2026, 8)).single()

        assertThat(day.date).isEqualTo(today)
        assertThat(day.quote).isNull()
    }

    @Test
    fun `没签到过的那天取不到日签`() = runTest {
        coEvery { catalog.quotes() } returns listOf(quote("a"))
        coEvery { dao.find(today.toEpochDay()) } returns null

        assertThat(repository().stamp(today)).isNull()
    }

    @Test
    fun `签到过的那天取到解析好的日签`() = runTest {
        coEvery { catalog.quotes() } returns listOf(quote("a"))
        coEvery { dao.find(today.toEpochDay()) } returns entity(today, "a")

        val stamp = repository().stamp(today)

        assertThat(stamp?.date).isEqualTo(today)
        assertThat(stamp?.quote?.id).isEqualTo("a")
    }

    /** 海报来源由数据层解析：UI 不该自己去碰 SplashPosterStore 拼路径 */
    @Test
    fun `日签带上海报来源`() = runTest {
        val target = quote("a")
        coEvery { catalog.quotes() } returns listOf(target)
        coEvery { dao.find(today.toEpochDay()) } returns entity(today, "a")
        every { posterStore.posterModel(target) } returns "poster-model"

        assertThat(repository().stamp(today)?.poster).isEqualTo("poster-model")
    }

    @Test
    fun `海报还没下载时日签的海报为空`() = runTest {
        val target = quote("a")
        coEvery { catalog.quotes() } returns listOf(target)
        coEvery { dao.find(today.toEpochDay()) } returns entity(today, "a")
        every { posterStore.posterModel(target) } returns null

        assertThat(repository().stamp(today)?.poster).isNull()
    }

    @Test
    fun `连续天数从今天数到断点`() = runTest {
        val start = today.minusDays(4)
        coEvery { dao.find(today.toEpochDay()) } returns entity(today, "a")
        coEvery { dao.streakStart(today.toEpochDay()) } returns start.toEpochDay()

        assertThat(repository().streak(today)).isEqualTo(5)
    }

    @Test
    fun `只签到过今天时连续天数为 1`() = runTest {
        coEvery { dao.find(today.toEpochDay()) } returns entity(today, "a")
        coEvery { dao.streakStart(today.toEpochDay()) } returns today.toEpochDay()

        assertThat(repository().streak(today)).isEqualTo(1)
    }

    @Test
    fun `今天没签到时连续天数为 0`() = runTest {
        coEvery { dao.find(today.toEpochDay()) } returns null

        assertThat(repository().streak(today)).isEqualTo(0)
        coVerify(exactly = 0) { dao.streakStart(any()) }
    }

    @Test
    fun `一条日签都没有时连续天数为 0`() = runTest {
        coEvery { dao.find(today.toEpochDay()) } returns entity(today, "a")
        coEvery { dao.streakStart(any()) } returns null

        assertThat(repository().streak(today)).isEqualTo(0)
    }

    @Test
    fun `日历往前翻的起点是最早那条签到`() = runTest {
        coEvery { dao.earliestDay() } returns LocalDate.of(2026, 3, 14).toEpochDay()

        // 起始月由调用方从这一天派生（YearMonth.from）：仓库只留一个查 MIN 的入口，
        // 否则一次日历发布要为同一条 SQL 跑三趟
        assertThat(repository().firstUseDate()).isEqualTo(LocalDate.of(2026, 3, 14))
    }

    @Test
    fun `一条日签都没有时没有起始日`() = runTest {
        coEvery { dao.earliestDay() } returns null

        assertThat(repository().firstUseDate()).isNull()
    }

    /**
     * 一个月最多 31 格，逐条问文件系统就是 62 笔 stat，而这笔钱每次翻月都要付一遍。
     * 钉住「只列一次目录」：改成循环里问就绪，这里立刻红。
     */
    @Test
    fun `解析整月只列一次目录`() = runTest {
        val first = LocalDate.of(2026, 8, 3)
        val second = LocalDate.of(2026, 8, 17)
        coEvery { catalog.quotes() } returns listOf(quote("a"), quote("b"))
        coEvery { dao.range(any(), any()) } returns listOf(entity(first, "a"), entity(second, "b"))
        coEvery { posterStore.posterModels(any()) } returns mapOf("a" to "pa", "b" to "pb")

        val days = repository().month(YearMonth.of(2026, 8))

        coVerify(exactly = 1) { posterStore.posterModels(any()) }
        assertThat(days.map { it.poster }).containsExactly("pa", "pb").inOrder()
    }

    /** 错过那些天的 firstUse 由调用方传进来：这里再查一次 MIN 就是第三趟同一条 SQL */
    @Test
    fun `错过的那些天不再自己查初次使用那天`() = runTest {
        coEvery { catalog.quotes() } returns listOf(quote("a"))
        coEvery { quotes.quoteFor(any()) } returns quote("a")
        coEvery { dao.range(any(), any()) } returns emptyList()
        coEvery { posterStore.posterModels(any()) } returns emptyMap()

        repository().missedMonth(
            month = YearMonth.of(2026, 8),
            firstUse = LocalDate.of(2026, 8, 20),
            today = today,
        )

        coVerify(exactly = 0) { dao.earliestDay() }
    }

    /** 还没到的那些天格子上不印海报，一次目录都不该问（卡片糊的那张走 w92 地址） */
    @Test
    fun `未来的那些天一次目录都不列`() = runTest {
        coEvery { catalog.quotes() } returns listOf(quote("a"))
        coEvery { quotes.quoteFor(any()) } returns quote("a")

        val days = repository().latentMonth(
            month = YearMonth.of(2026, 8),
            today = LocalDate.of(2026, 8, 20),
        )

        assertThat(days).hasSize(11)
        assertThat(days.count { it.poster != null }).isEqualTo(0)
        coVerify(exactly = 0) { posterStore.posterModels(any()) }
    }
}
