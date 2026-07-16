package com.tracktosearch.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.LastConsistencyCheckStorage
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DelayInfo
import com.tracktosearch.data.remote.douban.DoubanMarkItem
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.remote.douban.DoubanRepository
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * DoubanTraktStatusConsistencyChecker checkAndUnifyWithCrawl 完整链路单元测试。
 *
 * 覆盖关注点:
 * - 未登录豆瓣: cookieExpired=true 直接完成,不进入爬取流程,不调用 recordCheck
 * - 已在运行时: 第二次调用返回 false
 * - 完整流程: 走完阶段1-5 后调用 recordCheck
 *
 * 注意: ConsistencyChecker 的 appScope 硬编码 Dispatchers.IO(真实线程),
 * 不受测试调度器控制。涉及 checkAndUnifyWithCrawl 异步完成的测试使用 runBlocking + 轮询等待,
 * 与 DoubanSyncManagerLifecycleTest 保持一致的测试范式。
 *
 * 构造参数顺序(6个): doubanSyncedItemDao, traktRepository, doubanRepository,
 * doubanAuthStorage, lastConsistencyCheckStorage, context。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConsistencyCheckerCrawlTest {

    // init 块依赖:必须返回有效 StateFlow,否则构造时 collect 会 NPE
    private val delayEventFlow = MutableStateFlow<DelayInfo?>(null)

    private val doubanSyncedItemDao = mockk<DoubanSyncedItemDao>(relaxed = true)
    private val traktRepository = mockk<TraktRepository>(relaxed = true)
    private val doubanRepository = mockk<DoubanRepository>(relaxed = true).also {
        every { it.delayEvent } returns delayEventFlow
    }
    private val doubanAuthStorage = mockk<DoubanAuthStorage>(relaxed = true)
    private val lastConsistencyCheckStorage = mockk<LastConsistencyCheckStorage>(relaxed = true)
    private val appContext: Context = RuntimeEnvironment.getApplication()

    private lateinit var checker: DoubanTraktStatusConsistencyChecker

    @Before
    fun setUp() {
        // 清除前序测试的 stub 和调用记录,确保 coVerify 不受干扰
        clearMocks(
            doubanSyncedItemDao,
            traktRepository,
            doubanRepository,
            doubanAuthStorage,
            lastConsistencyCheckStorage
        )
        // 重新 stub delayEvent(clearMocks 后需重新设置,否则 init 块 collect 会 NPE)
        every { doubanRepository.delayEvent } returns delayEventFlow

        // 默认:未登录豆瓣(避免进入实际爬取流程)
        every { doubanAuthStorage.getCredentials() } returns null

        // 每个测试创建新的 checker 实例,避免 checkJob/checkProgress 状态泄漏
        checker = DoubanTraktStatusConsistencyChecker(
            doubanSyncedItemDao,
            traktRepository,
            doubanRepository,
            doubanAuthStorage,
            lastConsistencyCheckStorage,
            appContext
        )
    }

    // ============================================================
    // 未登录豆瓣场景
    // ============================================================

    /**
     * 未登录豆瓣 → getCredentials 返回 null → 设置 cookieExpired=true 直接完成。
     */
    @Test
    fun 未登录豆瓣时cookieExpired为true直接完成() = runBlocking {
        checker.checkAndUnifyWithCrawl()
        waitForCondition { checker.checkProgress.value.isComplete }
        val p = checker.checkProgress.value
        assertThat(p.isComplete).isTrue()
        assertThat(p.cookieExpired).isTrue()
    }

    /**
     * 未登录豆瓣 → 直接 return,不调用 doubanRepository.fetchMarkList。
     */
    @Test
    fun 未登录豆瓣时不进入爬取流程() = runBlocking {
        checker.checkAndUnifyWithCrawl()
        waitForCondition { checker.checkProgress.value.isComplete }
        // fetchMarkList 有 6 个参数(2 个有默认值),用 any() 匹配必填的 4 个
        coVerify(exactly = 0) {
            doubanRepository.fetchMarkList(any(), any(), any(), any())
        }
    }

    /**
     * 完成后(未登录场景)checkProgress.isComplete 为 true。
     */
    @Test
    fun 完成后checkProgress的isComplete为true() = runBlocking {
        checker.checkAndUnifyWithCrawl()
        waitForCondition { checker.checkProgress.value.isComplete }
        assertThat(checker.checkProgress.value.isComplete).isTrue()
    }

    /**
     * 完成后(未登录场景)isRunning() 为 false。
     *
     * 注意: isComplete 设置在 runCheckWithCrawl return 时,而 checkJob 完全结束
     * (包括 finally 块的 releaseWakeLock)在之后。直接轮询 isComplete 会有竞态
     * (isComplete=true 但 checkJob 仍 active),需轮询 !isRunning() 确保 checkJob 已结束。
     */
    @Test
    fun 完成后isRunning为false() = runBlocking {
        checker.checkAndUnifyWithCrawl()
        waitForCondition { checker.checkProgress.value.isComplete && !checker.isRunning() }
        assertThat(checker.isRunning()).isFalse()
    }

    /**
     * 未登录豆瓣 → 直接 return → 不调用 recordCheck。
     *
     * 说明: recordCheck 只在完整流程(阶段5完成)时调用,未登录场景在阶段1之前就 return。
     */
    @Test
    fun 未登录豆瓣时不调用recordCheck() = runBlocking {
        checker.checkAndUnifyWithCrawl()
        waitForCondition { checker.checkProgress.value.isComplete }
        coVerify(exactly = 0) { lastConsistencyCheckStorage.recordCheck() }
    }

    // ============================================================
    // 并发保护
    // ============================================================

    /**
     * 已在运行时 → 第二次调用返回 false。
     *
     * 让 fetchMarkList(WISH) 阻塞在 gate 上,确保第一次调用期间 checkJob 仍 active。
     * COLLECT 不阻塞(直接返回 true),避免 gate 释放后第二次 fetchMarkList 卡死。
     */
    @Test
    fun 已在运行时返回false() = runBlocking {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("uid", "cookie")
        val gate = CompletableDeferred<Boolean>()
        coEvery {
            doubanRepository.fetchMarkList(any(), any(), any(), any(), any(), any())
        } coAnswers {
            val status = thirdArg<DoubanMarkStatus>()
            if (status == DoubanMarkStatus.WISH) {
                gate.await()
            }
            true
        }

        val first = checker.checkAndUnifyWithCrawl()
        assertThat(first).isTrue()
        // 等待协程进入 fetchMarkList(WISH) 并阻塞在 gate
        waitForCondition { checker.isRunning() }
        val second = checker.checkAndUnifyWithCrawl()
        assertThat(second).isFalse()

        // 释放 gate,让协程完成
        gate.complete(true)
        waitForCondition { checker.checkProgress.value.isComplete }
    }

    // ============================================================
    // 完整流程
    // ============================================================

    /**
     * 完整流程走完后调用 recordCheck。
     *
     * 构造: getCredentials 返回非 null,fetchMarkList(WISH) 回调一个条目,
     * COLLECT 不回调。doubanSyncedItemDao.getAllSyncedItems 返回空(relaxed 默认),
     * 条目找不到 syncedItem → traktId=null → skipped。流程走完阶段2-5,recordCheck 被调用。
     */
    @Test
    fun 完整流程完成后调用recordCheck() = runBlocking {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("uid", "cookie")
        coEvery {
            doubanRepository.fetchMarkList(any(), any(), any(), any(), any(), any())
        } coAnswers {
            val status = thirdArg<DoubanMarkStatus>()
            @Suppress("UNCHECKED_CAST")
            val onPage = args[3] as suspend (List<DoubanMarkItem>, Int) -> Unit
            if (status == DoubanMarkStatus.WISH) {
                onPage.invoke(
                    listOf(
                        DoubanMarkItem(
                            doubanId = "1",
                            title = "测试电影",
                            rating = null,
                            comment = null,
                            markedAt = "2024-01-01",
                            doubanUrl = "https://movie.douban.com/subject/1/",
                            posterUrl = null
                        )
                    ),
                    1
                )
            }
            // COLLECT 不回调任何条目
            true
        }

        checker.checkAndUnifyWithCrawl()
        waitForCondition { checker.checkProgress.value.isComplete }

        coVerify(exactly = 1) { lastConsistencyCheckStorage.recordCheck() }
    }

    // ============================================================
    // 辅助函数
    // ============================================================

    /**
     * 轮询等待条件满足,默认超时 3 秒。
     *
     * ConsistencyChecker 的 appScope 使用 Dispatchers.IO(真实线程),
     * 无法用 runTest 的 advanceUntilIdle 控制,需用 Thread.sleep 轮询。
     */
    private fun waitForCondition(
        timeoutMs: Long = 3000L,
        intervalMs: Long = 50L,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        throw AssertionError("条件在 ${timeoutMs}ms 内未满足")
    }
}
