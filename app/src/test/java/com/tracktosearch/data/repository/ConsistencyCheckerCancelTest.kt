package com.tracktosearch.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.LastConsistencyCheckStorage
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DelayInfo
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.remote.douban.DoubanRepository
import io.mockk.clearMocks
import io.mockk.coEvery
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
 * DoubanTraktStatusConsistencyChecker cancel 链路单元测试。
 *
 * 覆盖关注点:
 * - cancel() 同步设置 isCancelling=true 和 phase="正在取消..."
 * - 无运行任务时 cancel() 不触发 isCancelled(无 CancellationException)
 * - 有运行任务时 cancel() 异步触发 isCancelled=true(CancellationException catch 块)
 * - resetProgress() 清除所有状态
 * - 正常完成时 isCancelled=false
 * - cancel 后 resetProgress 可重新启动检查
 *
 * 注意: ConsistencyChecker 的 appScope 硬编码 Dispatchers.IO(真实线程),
 * 不受测试调度器控制。涉及异步完成的测试使用 runBlocking + 轮询等待,
 * 与 ConsistencyCheckerCrawlTest 保持一致的测试范式。
 *
 * 构造参数顺序(6个): doubanSyncedItemDao, traktRepository, doubanRepository,
 * doubanAuthStorage, lastConsistencyCheckStorage, context。
 *
 * 关键差异(与计划不同):
 * - 无 isCancelling() 公开方法,通过 checkProgress.value.isCancelling 验证
 * - cancel() 同步设置 isCancelling,但 isCancelled 由 CancellationException catch 块异步设置
 * - 无运行任务时 cancel() 不触发 isCancelled(因 checkJob 为 null,无 CancellationException)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConsistencyCheckerCancelTest {

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
        // 清除前序测试的 stub 和调用记录,确保状态隔离
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
    // cancel() 同步行为(无运行任务)
    // ============================================================

    /**
     * cancel() 同步设置 isCancelling=true。
     *
     * 无运行任务时 cancel() 仍同步更新 checkProgress,
     * UI 可立即响应(禁用取消按钮 + 显示"正在取消..."文案)。
     */
    @Test
    fun cancel后checkProgress的isCancelling为true() {
        checker.cancel()
        assertThat(checker.checkProgress.value.isCancelling).isTrue()
    }

    /**
     * cancel() 同步设置 phase="正在取消..."。
     */
    @Test
    fun cancel后checkProgress的phase包含正在取消() {
        checker.cancel()
        assertThat(checker.checkProgress.value.phase).contains("正在取消")
    }

    /**
     * 无运行任务时 cancel() 不触发 isCancelled。
     *
     * isCancelled 由 CancellationException catch 块异步设置,
     * 无运行任务时 checkJob 为 null,不触发 CancellationException,isCancelled 保持 false。
     * 这区分了"用户点了取消"的中间态(isCancelling)和"检查已被取消"的终态(isCancelled)。
     */
    @Test
    fun cancel后无运行任务时isCancelled为false() {
        checker.cancel()
        assertThat(checker.checkProgress.value.isCancelling).isTrue()
        assertThat(checker.checkProgress.value.isCancelled).isFalse()
    }

    // ============================================================
    // cancel() 异步行为(有运行任务)
    // ============================================================

    /**
     * cancel() 取消运行中的任务后 isCancelled=true。
     *
     * 让 fetchMarkList(WISH) 阻塞在 gate 上,确保 checkJob 处于 active 状态。
     * cancel() 调用 checkJob?.cancel(),gate.await() 抛出 CancellationException,
     * catch 块设置 isCancelled=true, isComplete=true, phase="已取消"。
     */
    @Test
    fun cancel运行中的任务后isCancelled为true() = runBlocking {
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

        assertThat(checker.checkAndUnifyWithCrawl()).isTrue()
        // 等待协程进入 fetchMarkList(WISH) 并阻塞在 gate
        waitForCondition { checker.isRunning() }

        // cancel 后 isCancelling 同步为 true
        checker.cancel()
        assertThat(checker.checkProgress.value.isCancelling).isTrue()

        // 等待 CancellationException 被 catch,isCancelled 异步设置为 true
        // 同时等待 !isRunning() 确保 checkJob 已完全结束(含 finally 块 releaseWakeLock)
        waitForCondition {
            checker.checkProgress.value.isCancelled &&
                checker.checkProgress.value.isComplete &&
                !checker.isRunning()
        }
        assertThat(checker.checkProgress.value.isCancelled).isTrue()
        assertThat(checker.checkProgress.value.isComplete).isTrue()
    }

    // ============================================================
    // resetProgress()
    // ============================================================

    /**
     * cancel() 后 resetProgress() 清除所有状态。
     *
     * resetProgress() 在非运行状态下将 checkProgress 重置为默认 ConsistencyCheckResult()。
     * 无运行任务时 isRunning() 为 false,resetProgress() 正常执行。
     */
    @Test
    fun cancel后resetProgress清除所有状态() {
        checker.cancel()
        // cancel 后 isCancelling=true
        assertThat(checker.checkProgress.value.isCancelling).isTrue()
        checker.resetProgress()
        // resetProgress 后所有状态恢复默认
        assertThat(checker.checkProgress.value.isCancelling).isFalse()
        assertThat(checker.checkProgress.value.isCancelled).isFalse()
        assertThat(checker.checkProgress.value.isComplete).isFalse()
    }

    // ============================================================
    // 正常完成 vs 取消
    // ============================================================

    /**
     * 正常完成时 isCancelled=false。
     *
     * 未登录场景直接完成,不触发 CancellationException,isCancelled 保持 false。
     * 与 cancel 运行中任务的 isCancelled=true 形成对比。
     */
    @Test
    fun 正常完成时isCancelled为false() = runBlocking {
        checker.checkAndUnifyWithCrawl()
        waitForCondition { checker.checkProgress.value.isComplete && !checker.isRunning() }
        assertThat(checker.checkProgress.value.isCancelled).isFalse()
        assertThat(checker.checkProgress.value.isComplete).isTrue()
    }

    /**
     * cancel 后 resetProgress 可重新启动检查。
     *
     * 第一次运行:有运行任务 → cancel → 等待 isCancelled=true 且 checkJob 结束
     * resetProgress 清除状态(isCancelling/isCancelled/isComplete 全部 false)
     * 第二次运行:改为未登录 → 快速完成 → isComplete=true, isCancelled=false
     *
     * 验证 cancel 不会导致 checker 进入无法重启的死状态。
     */
    @Test
    fun cancel后再checkAndUnifyWithCrawl可正常启动() = runBlocking {
        // 第一次运行:有运行中的任务,然后取消
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

        assertThat(checker.checkAndUnifyWithCrawl()).isTrue()
        waitForCondition { checker.isRunning() }
        checker.cancel()
        // 等待取消完成(isCancelled=true 且 checkJob 已结束)
        waitForCondition {
            checker.checkProgress.value.isCancelled && !checker.isRunning()
        }

        // resetProgress 清除状态
        checker.resetProgress()
        assertThat(checker.checkProgress.value.isComplete).isFalse()
        assertThat(checker.checkProgress.value.isCancelled).isFalse()
        assertThat(checker.checkProgress.value.isCancelling).isFalse()

        // 第二次运行:改为未登录,快速完成
        every { doubanAuthStorage.getCredentials() } returns null
        assertThat(checker.checkAndUnifyWithCrawl()).isTrue()
        waitForCondition { checker.checkProgress.value.isComplete && !checker.isRunning() }
        assertThat(checker.checkProgress.value.isComplete).isTrue()
        assertThat(checker.checkProgress.value.isCancelled).isFalse()
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
