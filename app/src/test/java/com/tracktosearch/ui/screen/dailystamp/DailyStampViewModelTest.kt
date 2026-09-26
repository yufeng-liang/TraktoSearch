package com.tracktosearch.ui.screen.dailystamp

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DailyStampReadStorage
import com.tracktosearch.data.local.SplashQuoteStorage
import com.tracktosearch.data.repository.DailyStamp
import com.tracktosearch.data.repository.DailyStampRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 日签页整月数据的落地时机。
 *
 * 这一屏是 42 个格子带最多 31 张海报缩略图，每落一次状态就是整页重排，所以这里守的不是
 * 数值对不对（那些在 DailyStampRepositoryTest 与 DailyStampModelsTest 里），而是
 * **一次发布**与**发布的先后**：
 *
 * - 签到格、错过的那些天、还没到的那些天、两个计数一起到，不能一前一后画三遍；
 * - 「哪些天看过」的镜像先落地，否则已经补看过的格子会先按 12px 糊着请求一次、镜像到了
 *   再换 160px 重请求一遍——白解一张图，还看得见一下跳变；
 * - 切月当场就换月份，否则连点箭头时后几下会被翻页闸门按掉。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DailyStampViewModelTest {

    private val repository = mockk<DailyStampRepository>(relaxed = true)
    private val splashQuoteStorage = mockk<SplashQuoteStorage>(relaxed = true)
    private val readStorage = mockk<DailyStampReadStorage>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()

    /** ViewModel 的初始月就是当前月，测试数据跟着它走，免得用例放久了自己变味 */
    private val month get() = YearMonth.now()
    private val firstUse get() = month.atDay(1)

    private fun stamp(day: Int) = DailyStamp(date = month.atDay(day), quote = null, poster = null)

    /** 订阅用的热流：换月时同一份桩数据照样能再喂一批 */
    private val monthRows = MutableStateFlow<List<DailyStamp>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        monthRows.value = listOf(stamp(1), stamp(5))
        every { repository.observeMonth(any()) } returns monthRows
        coEvery { repository.firstUseDate() } returns firstUse
        coEvery { repository.streak(any()) } returns 3
        coEvery { repository.totalDays() } returns 41
        coEvery { repository.missedMonth(any(), any(), any()) } returns listOf(stamp(2), stamp(3))
        coEvery { repository.latentMonth(any(), any()) } returns listOf(stamp(20), stamp(21))
        coEvery { readStorage.loadIntoMirror() } returns setOf(firstUse.toEpochDay())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        DailyStampViewModel(repository, splashQuoteStorage, readStorage)

    @Test
    fun `整月数据一次落状态，不留只画一半的中间帧`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val seen = mutableListOf<DailyStampUiState>()
        // Unconfined 的收集器：换月时 StateFlow 会合流丢掉中间值，而这条用例断言的正是中间值
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.toList(seen)
        }

        awaitMonthPublished(viewModel)

        val full = seen.last()
        assertThat(full.stamps.map { it.date.dayOfMonth }).containsExactly(1, 5)
        assertThat(full.missed).isNotEmpty()
        assertThat(full.latent).isNotEmpty()
        assertThat(full.streak).isEqualTo(3)
        assertThat(full.total).isEqualTo(41)
        assertThat(full.firstDay).isEqualTo(firstUse)
        assertThat(full.earliestMonth).isEqualTo(month)
        // 关键断言：只要出现过「有签到格但还没到」的帧，就是分波发布的原样
        assertThat(seen.count { it.stamps.isNotEmpty() && it.missed.isEmpty() }).isEqualTo(0)
    }

    /** 错过那几格的糊/清晰由镜像决定：镜像没落地就发布，等于重播一次 12px→160px */
    @Test
    fun `先读回已读镜像再算错过的那些天`() = runTest(dispatcher) {
        val viewModel = viewModel()

        awaitMonthPublished(viewModel)

        coVerifyOrder {
            readStorage.loadIntoMirror()
            repository.missedMonth(any(), any(), any())
        }
    }

    /**
     * 一次发布只问一遍「最早那条签到在哪天」。
     *
     * 起始月、初次使用那天、错过区间的下界三处同源，各查一遍就是把同一条 MIN 跑三趟。
     */
    @Test
    fun `一次发布只查一遍初次使用那天`() = runTest(dispatcher) {
        val viewModel = viewModel()

        awaitMonthPublished(viewModel)

        coVerify(exactly = 1) { repository.firstUseDate() }
    }

    /** 报头月名与两个箭头按「请求中的那个月」走：等数据到位才换，连点箭头就会丢几下 */
    @Test
    fun `切月当场换月份不等数据`() = runTest(dispatcher) {
        val viewModel = viewModel()
        awaitMonthPublished(viewModel)

        viewModel.nextMonth()

        assertThat(viewModel.uiState.value.month).isEqualTo(month.plusMonths(1))
    }

    /**
     * 等到整月那一次发布真的落进状态。
     *
     * gather 走的是 [kotlinx.coroutines.Dispatchers.IO] —— 真实线程，不受虚拟时钟管，
     * 光 `advanceUntilIdle()` 会在 IO 还没回来的时候就把断言跑完（表现是 stamps 为空）。
     * 所以这里让出真实时间片、再推进虚拟队列，直到 stamps 非空为止：判据是状态本身，
     * 不是猜一个等待时长，超时只负责把「永远等不到」暴露成失败而不是挂住。
     */
    private fun TestScope.awaitMonthPublished(viewModel: DailyStampViewModel) {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        while (viewModel.uiState.value.stamps.isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                fail("整月数据没有落进状态：${viewModel.uiState.value}")
            }
            advanceUntilIdle()
            Thread.sleep(5)
        }
        advanceUntilIdle()
    }

    private companion object {
        const val SETTLE_TIMEOUT_MS = 3_000L
    }
}
