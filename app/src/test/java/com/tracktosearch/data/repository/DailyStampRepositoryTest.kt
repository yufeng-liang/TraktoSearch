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
        // 装完第一屏展示的是开场那一条，而它展示完就被标记掉了：
        // 这里再调 todayQuote 拿到的是日期取模那条，跟屏幕上的不是同一部片。
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
    fun `日历往前翻到第一次签到那个月为止`() = runTest {
        coEvery { dao.earliestDay() } returns LocalDate.of(2026, 3, 14).toEpochDay()

        assertThat(repository().earliestMonth()).isEqualTo(YearMonth.of(2026, 3))
    }

    @Test
    fun `一条日签都没有时没有起始月`() = runTest {
        coEvery { dao.earliestDay() } returns null

        assertThat(repository().earliestMonth()).isNull()
    }
}
