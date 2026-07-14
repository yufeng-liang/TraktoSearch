package com.tracktosearch.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DelayInfo
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.MarkWriteResult
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/**
 * DoubanBatchRemovalManager 单元测试。
 *
 * 覆盖点（共12个）：
 * 1. startRemoval(items, isMovie) 已登录 → 执行完整移除链路，removeMark 被调用 N 次
 * 2. startRemoval 未登录（getCredentials 返回 null）→ 返回 false，不调用任何 API
 * 3. startRemoval 空列表 → 返回 false
 * 4. startRemoval 已在运行 → 返回 false（防重入）
 * 5. 单条 removeMark 抛异常 → 该条计为失败，其他条目仍继续执行
 * 6. findDoubanId 返回 null → 该条计为 skip，不调用 fetchCsrfToken/removeMark
 * 7. fetchCsrfToken 返回 null → 该条计为 fail
 * 8. isRunning() 执行中返回 true，完成后返回 false
 * 9. resetProgress() 非运行时重置 progress 为初始值；运行中不重置
 * 10. 进度 StateFlow 最终 isComplete=true，successCount/failCount/skipCount 正确
 * 11. 未登录豆瓣 → 静默跳过（返回 false，不调用任何网络 API）
 * 12. WakeLock 在 startRemoval 时 acquire，完成时 release
 *
 * 注意：DoubanBatchRemovalManager init 块在 appScope（Dispatchers.IO 真实线程）启动协程
 * 收集 doubanRepository.delayEvent，构造 manager 前必须 mock 返回有效的 MutableStateFlow。
 *
 * startRemoval 是非 suspend 函数，内部在真实 Dispatchers.IO 上启动协程，
 * 测试通过轮询 manager.progress.value.isComplete 等待协程完成（加超时保护避免死锁）。
 * 需要阻塞协程以验证执行中状态时，使用 CompletableDeferred 作为门闩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubanBatchRemovalManagerTest {

    // init 块依赖：必须返回有效 StateFlow，否则构造时 collect 会 NPE
    private val delayEventFlow = MutableStateFlow<DelayInfo?>(null)

    private val doubanRepository = mockk<DoubanRepository>(relaxed = true).also {
        every { it.delayEvent } returns delayEventFlow
    }
    private val doubanAuthStorage = mockk<DoubanAuthStorage>(relaxed = true)
    private val doubanSyncedItemDao = mockk<DoubanSyncedItemDao>(relaxed = true)
    private val appContext: Context = RuntimeEnvironment.getApplication()

    private lateinit var manager: DoubanBatchRemovalManager

    @Before
    fun setUp() {
        // 清除前序测试的 stub 和调用记录，确保 coVerify(exactly = 0) 不受干扰
        clearMocks(doubanRepository, doubanAuthStorage, doubanSyncedItemDao)
        // 重新 stub delayEvent（init 块已 collect 过，但保持 stub 一致避免后续访问 NPE）
        every { doubanRepository.delayEvent } returns delayEventFlow
        // 每个测试创建新的 manager 实例，避免 progress/removalJob 状态泄漏
        manager = DoubanBatchRemovalManager(
            doubanRepository,
            doubanAuthStorage,
            doubanSyncedItemDao,
            appContext
        )
    }

    // ============================================================
    // 辅助函数
    // ============================================================

    /** 构造测试用的批量移除条目 */
    private fun buildItem(
        traktId: Int = 1,
        imdbId: String = "tt0000001",
        title: String = "测试电影"
    ): BatchRemovalItem = BatchRemovalItem(
        traktId = traktId,
        imdbId = imdbId,
        title = title
    )

    /** 构造豆瓣同步记录 */
    private fun buildSyncedItem(
        doubanId: String = "db-001",
        imdbId: String? = "tt0000001",
        traktId: Int? = 1,
        title: String = "测试电影"
    ): DoubanSyncedItem = DoubanSyncedItem(
        doubanId = doubanId,
        imdbId = imdbId,
        traktId = traktId,
        title = title,
        status = "collect",
        rating = null,
        syncedAt = 1000L,
        mediaType = "movie"
    )

    /** 设置已登录凭证 */
    private fun stubLoggedIn(cookie: String = "ck=test-cookie") {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials(
            userId = "u-1",
            cookie = cookie
        )
    }

    /** 设置单条完整成功链路 mock（getByImdbId 命中 → fetchCsrfToken → removeMark success） */
    private fun stubSuccessChain(doubanId: String = "db-001", cookie: String = "ck=test-cookie") {
        coEvery { doubanSyncedItemDao.getByImdbId(any()) } returns buildSyncedItem(doubanId = doubanId)
        coEvery { doubanRepository.fetchCsrfToken(doubanId, cookie) } returns "csrf-token"
        coEvery {
            doubanRepository.removeMark(doubanId, cookie, "csrf-token")
        } returns MarkWriteResult(success = true, statusCode = 302, message = "ok")
    }

    /** 轮询等待 progress.isComplete == true 且 job 已完全结束（isActive=false），带超时保护避免死锁 */
    private fun waitForCompletion(timeoutMs: Long = 5000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // 同时检查 isComplete（finally 块设置）和 !isRunning()（job 已完全结束），
            // 避免 finally 块设置 isComplete=true 后 job 仍短暂 isActive 导致 resetProgress 误判
            if (manager.progress.value.isComplete && !manager.isRunning()) return true
            Thread.sleep(20)
        }
        return false
    }

    /** 轮询等待条件满足，带超时保护 */
    private fun waitForCondition(
        timeoutMs: Long = 3000L,
        intervalMs: Long = 20L,
        condition: () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(intervalMs)
        }
        return false
    }

    // ============================================================
    // 测试点1：已登录 → 执行完整移除链路
    // ============================================================

    /**
     * 测试点1：startRemoval(items, isMovie=true) 已登录 → 执行完整移除链路，
     * removeMark 被调用 N 次，全部成功。
     * 链路：getByImdbId 命中 → fetchCsrfToken → removeMark success
     */
    @Test
    fun startRemoval_已登录_执行完整移除链路() = runTest {
        val items = listOf(
            buildItem(traktId = 1, imdbId = "tt0000001"),
            buildItem(traktId = 2, imdbId = "tt0000002"),
            buildItem(traktId = 3, imdbId = "tt0000003")
        )
        stubLoggedIn()
        // 每个 imdbId 命中不同的 doubanId
        coEvery { doubanSyncedItemDao.getByImdbId("tt0000001") } returns buildSyncedItem(doubanId = "db-1")
        coEvery { doubanSyncedItemDao.getByImdbId("tt0000002") } returns buildSyncedItem(doubanId = "db-2")
        coEvery { doubanSyncedItemDao.getByImdbId("tt0000003") } returns buildSyncedItem(doubanId = "db-3")
        coEvery { doubanRepository.fetchCsrfToken(any(), any()) } returns "csrf-token"
        coEvery {
            doubanRepository.removeMark(any(), any(), any())
        } returns MarkWriteResult(success = true, statusCode = 302, message = "ok")

        val result = manager.startRemoval(items, isMovie = true)

        assertThat(result).isTrue()
        val completed = waitForCompletion()
        assertThat(completed).isTrue()

        // 验证完整链路：getByImdbId 调用 3 次（findDoubanId 未被调用，因为 getByImdbId 命中）
        coVerify(exactly = 3) { doubanSyncedItemDao.getByImdbId(any()) }
        coVerify(exactly = 0) { doubanRepository.findDoubanId(any(), any(), any()) }
        coVerify(exactly = 3) { doubanRepository.fetchCsrfToken(any(), any()) }
        coVerify(exactly = 3) { doubanRepository.removeMark(any(), any(), any()) }

        // 验证最终进度
        val p = manager.progress.value
        assertThat(p.isComplete).isTrue()
        assertThat(p.isRunning).isFalse()
        assertThat(p.successCount).isEqualTo(3)
        assertThat(p.failCount).isEqualTo(0)
        assertThat(p.skipCount).isEqualTo(0)
        assertThat(p.total).isEqualTo(3)
        assertThat(p.current).isEqualTo(3)
    }

    // ============================================================
    // 测试点2：未登录 → 返回 false，不调用任何 API
    // ============================================================

    /**
     * 测试点2：startRemoval 未登录（getCredentials 返回 null）→ 返回 false，不调用任何 API。
     */
    @Test
    fun startRemoval_未登录_returnsFalse不调用任何API() = runTest {
        every { doubanAuthStorage.getCredentials() } returns null
        val items = listOf(buildItem())

        val result = manager.startRemoval(items, isMovie = true)

        assertThat(result).isFalse()
        coVerify(exactly = 0) { doubanSyncedItemDao.getByImdbId(any()) }
        coVerify(exactly = 0) { doubanRepository.findDoubanId(any(), any(), any()) }
        coVerify(exactly = 0) { doubanRepository.fetchCsrfToken(any(), any()) }
        coVerify(exactly = 0) { doubanRepository.removeMark(any(), any(), any()) }
        // progress 保持初始状态
        assertThat(manager.progress.value.isRunning).isFalse()
        assertThat(manager.progress.value.isComplete).isFalse()
    }

    // ============================================================
    // 测试点3：空列表 → 返回 false
    // ============================================================

    /**
     * 测试点3：startRemoval 空列表 → 返回 false（即使已登录）。
     */
    @Test
    fun startRemoval_空列表_returnsFalse() = runTest {
        stubLoggedIn()

        val result = manager.startRemoval(emptyList(), isMovie = true)

        assertThat(result).isFalse()
        coVerify(exactly = 0) { doubanRepository.removeMark(any(), any(), any()) }
    }

    // ============================================================
    // 测试点4：已在运行 → 返回 false（防重入）
    // ============================================================

    /**
     * 测试点4：startRemoval 已在运行 → 第二次调用返回 false（防重入）。
     * 用 CompletableDeferred 阻塞 removeMark 使第一次任务保持运行状态。
     */
    @Test
    fun startRemoval_已在运行_returnsFalse防重入() = runTest {
        stubLoggedIn()
        val latch = CompletableDeferred<Unit>()
        coEvery { doubanSyncedItemDao.getByImdbId(any()) } returns buildSyncedItem()
        coEvery { doubanRepository.fetchCsrfToken(any(), any()) } returns "csrf-token"
        coEvery { doubanRepository.removeMark(any(), any(), any()) } coAnswers {
            latch.await()  // 阻塞直到测试显式释放
            MarkWriteResult(success = true, statusCode = 302, message = "ok")
        }

        val items = listOf(buildItem())
        val first = manager.startRemoval(items, isMovie = true)
        assertThat(first).isTrue()

        // 等待协程进入运行状态
        assertThat(waitForCondition { manager.isRunning() }).isTrue()

        // 第二次调用应被拒绝
        val second = manager.startRemoval(items, isMovie = true)
        assertThat(second).isFalse()

        // 释放并等待完成
        latch.complete(Unit)
        waitForCompletion()
    }

    // ============================================================
    // 测试点5：单条 removeMark 抛异常 → 该条失败，其他继续
    // ============================================================

    /**
     * 测试点5：单条 removeMark 抛异常 → 该条计为失败（failCount+1），其他条目仍继续执行。
     * 源码中 runCatching 会捕获异常，getOrElse 返回 false，计入 failCount。
     */
    @Test
    fun startRemoval_单条removeMark抛异常_该条失败其他继续() = runTest {
        val items = listOf(
            buildItem(traktId = 1, imdbId = "tt0000001"),
            buildItem(traktId = 2, imdbId = "tt0000002")
        )
        stubLoggedIn()
        coEvery { doubanSyncedItemDao.getByImdbId("tt0000001") } returns buildSyncedItem(doubanId = "db-1")
        coEvery { doubanSyncedItemDao.getByImdbId("tt0000002") } returns buildSyncedItem(doubanId = "db-2")
        coEvery { doubanRepository.fetchCsrfToken(any(), any()) } returns "csrf-token"
        // db-1 抛异常，db-2 成功
        coEvery { doubanRepository.removeMark("db-1", any(), any()) } throws RuntimeException("网络异常")
        coEvery {
            doubanRepository.removeMark("db-2", any(), any())
        } returns MarkWriteResult(success = true, statusCode = 302, message = "ok")

        val result = manager.startRemoval(items, isMovie = true)
        assertThat(result).isTrue()
        val completed = waitForCompletion()
        assertThat(completed).isTrue()

        // 验证两条 removeMark 都被调用（异常不影响其他条目）
        coVerify(exactly = 1) { doubanRepository.removeMark("db-1", any(), any()) }
        coVerify(exactly = 1) { doubanRepository.removeMark("db-2", any(), any()) }

        // 验证计数：1 失败 + 1 成功
        val p = manager.progress.value
        assertThat(p.successCount).isEqualTo(1)
        assertThat(p.failCount).isEqualTo(1)
        assertThat(p.skipCount).isEqualTo(0)
        assertThat(p.isComplete).isTrue()
    }

    // ============================================================
    // 测试点6：findDoubanId 返回 null → 计为 skip
    // ============================================================

    /**
     * 测试点6：findDoubanId 返回 null → 该条计为 skip，不调用 fetchCsrfToken/removeMark。
     * 用空白 imdbId 绕过 getByImdbId（relaxed mock 对 nullable 返回类型可能返回非 null 实例），
     * 直接测试 findDoubanId 返回 null → skip 的链路。
     */
    @Test
    fun startRemoval_findDoubanId返回Null_计为Skip() = runTest {
        // imdbId 为空 → isNotBlank() 为 false → 跳过 getByImdbId，直接调用 findDoubanId
        val items = listOf(buildItem(traktId = 1, imdbId = ""))
        stubLoggedIn()
        coEvery { doubanRepository.findDoubanId(any(), any(), any()) } returns null  // 远程未找到

        val result = manager.startRemoval(items, isMovie = true)
        assertThat(result).isTrue()
        val completed = waitForCompletion()
        assertThat(completed).isTrue()

        // 验证 getByImdbId 未被调用（imdbId 为空时跳过）
        coVerify(exactly = 0) { doubanSyncedItemDao.getByImdbId(any()) }
        // 验证 findDoubanId 被调用，mediaType="movie"
        coVerify(exactly = 1) { doubanRepository.findDoubanId(1, "", "movie") }
        // 验证 fetchCsrfToken 和 removeMark 未被调用（skip 不进入后续链路）
        coVerify(exactly = 0) { doubanRepository.fetchCsrfToken(any(), any()) }
        coVerify(exactly = 0) { doubanRepository.removeMark(any(), any(), any()) }

        // 验证计数：1 skip
        val p = manager.progress.value
        assertThat(p.skipCount).isEqualTo(1)
        assertThat(p.successCount).isEqualTo(0)
        assertThat(p.failCount).isEqualTo(0)
    }

    // ============================================================
    // 测试点7：fetchCsrfToken 返回 null → 计为 fail
    // ============================================================

    /**
     * 测试点7：fetchCsrfToken 返回 null → 该条计为 fail，不调用 removeMark。
     */
    @Test
    fun startRemoval_fetchCsrfToken返回Null_计为Fail() = runTest {
        val items = listOf(buildItem(traktId = 1, imdbId = "tt0000001"))
        stubLoggedIn()
        coEvery { doubanSyncedItemDao.getByImdbId(any()) } returns buildSyncedItem(doubanId = "db-1")
        coEvery { doubanRepository.fetchCsrfToken("db-1", any()) } returns null  // ck 获取失败

        val result = manager.startRemoval(items, isMovie = true)
        assertThat(result).isTrue()
        val completed = waitForCompletion()
        assertThat(completed).isTrue()

        // 验证 fetchCsrfToken 被调用但 removeMark 未被调用
        coVerify(exactly = 1) { doubanRepository.fetchCsrfToken("db-1", any()) }
        coVerify(exactly = 0) { doubanRepository.removeMark(any(), any(), any()) }

        // 验证计数：1 fail
        val p = manager.progress.value
        assertThat(p.failCount).isEqualTo(1)
        assertThat(p.successCount).isEqualTo(0)
        assertThat(p.skipCount).isEqualTo(0)
    }

    // ============================================================
    // 测试点8：isRunning() 执行中返回 true，完成后返回 false
    // ============================================================

    /**
     * 测试点8：isRunning() 在 startRemoval 后返回 true，完成后返回 false。
     * 用 CompletableDeferred 阻塞 removeMark 以便在执行中验证 isRunning。
     */
    @Test
    fun isRunning_执行中True完成后False() = runTest {
        stubLoggedIn()
        val latch = CompletableDeferred<Unit>()
        coEvery { doubanSyncedItemDao.getByImdbId(any()) } returns buildSyncedItem()
        coEvery { doubanRepository.fetchCsrfToken(any(), any()) } returns "csrf-token"
        coEvery { doubanRepository.removeMark(any(), any(), any()) } coAnswers {
            latch.await()
            MarkWriteResult(success = true, statusCode = 302, message = "ok")
        }

        // 启动前 isRunning 为 false
        assertThat(manager.isRunning()).isFalse()

        manager.startRemoval(listOf(buildItem()), isMovie = true)
        // 等待协程进入运行状态
        assertThat(waitForCondition { manager.isRunning() }).isTrue()

        // 释放 latch，等待完成
        latch.complete(Unit)
        waitForCompletion()
        assertThat(manager.isRunning()).isFalse()
    }

    // ============================================================
    // 测试点9：resetProgress() 非运行时重置；运行中不重置
    // ============================================================

    /**
     * 测试点9：resetProgress() 非运行时重置 progress 为初始值；运行中调用不重置。
     * Part 1：完成一次移除后 resetProgress → 重置为初始值
     * Part 2：运行中 resetProgress → 不重置
     */
    @Test
    fun resetProgress_非运行时重置_运行中不重置() = runTest {
        // === Part 1：非运行时 resetProgress 重置为初始值 ===
        stubLoggedIn()
        stubSuccessChain()
        // 先完成一次移除，让 progress 变为 isComplete=true
        manager.startRemoval(listOf(buildItem()), isMovie = true)
        waitForCompletion()
        assertThat(manager.progress.value.isComplete).isTrue()
        assertThat(manager.progress.value.successCount).isEqualTo(1)

        // 调用 resetProgress，验证重置
        manager.resetProgress()
        val reset = manager.progress.value
        assertThat(reset.isComplete).isFalse()
        assertThat(reset.isRunning).isFalse()
        assertThat(reset.successCount).isEqualTo(0)
        assertThat(reset.failCount).isEqualTo(0)
        assertThat(reset.skipCount).isEqualTo(0)
        assertThat(reset.total).isEqualTo(0)

        // === Part 2：运行中 resetProgress 不重置 ===
        val latch = CompletableDeferred<Unit>()
        // 覆盖 removeMark stub，用 latch 阻塞（后注册的 stub 优先级更高）
        coEvery { doubanRepository.removeMark(any(), any(), any()) } coAnswers {
            latch.await()
            MarkWriteResult(success = true, statusCode = 302, message = "ok")
        }
        manager.startRemoval(listOf(buildItem()), isMovie = true)
        assertThat(waitForCondition { manager.isRunning() }).isTrue()
        val progressBeforeReset = manager.progress.value

        manager.resetProgress()  // 应被忽略（isRunning 为 true）

        // progress 仍为运行中状态
        assertThat(manager.progress.value.isRunning).isTrue()
        assertThat(manager.progress.value.total).isEqualTo(progressBeforeReset.total)

        // 释放并等待完成
        latch.complete(Unit)
        waitForCompletion()
    }

    // ============================================================
    // 测试点10：进度 StateFlow 最终 isComplete=true，计数正确
    // ============================================================

    /**
     * 测试点10：3 个条目分别 success/fail/skip → 最终 isComplete=true，计数正确。
     * - item1: getByImdbId 命中 → fetchCsrfToken → removeMark success
     * - item2: getByImdbId 命中 → fetchCsrfToken 返回 null → fail
     * - item3: getByImdbId 返回 null + findDoubanId 返回 null → skip
     */
    @Test
    fun progress_完成时IsComplete且计数正确() = runTest {
        val items = listOf(
            buildItem(traktId = 1, imdbId = "tt0000001"),  // success
            buildItem(traktId = 2, imdbId = "tt0000002"),  // fail
            buildItem(traktId = 3, imdbId = "tt0000003")   // skip
        )
        stubLoggedIn()
        // item1: getByImdbId 命中 → fetchCsrfToken → removeMark success
        coEvery { doubanSyncedItemDao.getByImdbId("tt0000001") } returns buildSyncedItem(doubanId = "db-1")
        coEvery { doubanRepository.fetchCsrfToken("db-1", any()) } returns "csrf-token"
        coEvery {
            doubanRepository.removeMark("db-1", any(), any())
        } returns MarkWriteResult(success = true, statusCode = 302, message = "ok")

        // item2: getByImdbId 命中 db-2 → fetchCsrfToken 返回 null → fail
        coEvery { doubanSyncedItemDao.getByImdbId("tt0000002") } returns buildSyncedItem(doubanId = "db-2")
        coEvery { doubanRepository.fetchCsrfToken("db-2", any()) } returns null

        // item3: getByImdbId 返回 null + findDoubanId 返回 null → skip
        coEvery { doubanSyncedItemDao.getByImdbId("tt0000003") } returns null
        coEvery { doubanRepository.findDoubanId(3, "tt0000003", "movie") } returns null

        val result = manager.startRemoval(items, isMovie = true)
        assertThat(result).isTrue()
        val completed = waitForCompletion()
        assertThat(completed).isTrue()

        // 验证最终状态
        val p = manager.progress.value
        assertThat(p.isComplete).isTrue()
        assertThat(p.isRunning).isFalse()
        assertThat(p.isCancelling).isFalse()
        assertThat(p.successCount).isEqualTo(1)
        assertThat(p.failCount).isEqualTo(1)
        assertThat(p.skipCount).isEqualTo(1)
        assertThat(p.total).isEqualTo(3)
        assertThat(p.current).isEqualTo(3)
        assertThat(p.currentTitle).isNull()  // 完成后清空
        assertThat(p.phase).isEqualTo("完成")
    }

    // ============================================================
    // 测试点11：未登录 → 静默跳过（不调用任何网络 API）
    // ============================================================

    /**
     * 测试点11：未登录豆瓣 → 静默跳过，返回 false，不调用任何网络 API
     * （findDoubanId/fetchCsrfToken/removeMark），也不调用 DAO（getByImdbId），
     * progress 保持初始状态。
     */
    @Test
    fun startRemoval_未登录_静默跳过无网络调用() = runTest {
        every { doubanAuthStorage.getCredentials() } returns null
        val items = listOf(
            buildItem(traktId = 1, imdbId = "tt0000001"),
            buildItem(traktId = 2, imdbId = "tt0000002")
        )

        val result = manager.startRemoval(items, isMovie = true)

        // 返回 false，无异常
        assertThat(result).isFalse()
        // 不调用任何网络 API
        coVerify(exactly = 0) { doubanRepository.findDoubanId(any(), any(), any()) }
        coVerify(exactly = 0) { doubanRepository.fetchCsrfToken(any(), any()) }
        coVerify(exactly = 0) { doubanRepository.removeMark(any(), any(), any()) }
        // 也不调用 DAO
        coVerify(exactly = 0) { doubanSyncedItemDao.getByImdbId(any()) }
        // progress 保持初始状态（未启动）
        assertThat(manager.progress.value.isRunning).isFalse()
        assertThat(manager.progress.value.isComplete).isFalse()
        assertThat(manager.progress.value.total).isEqualTo(0)
    }

    // ============================================================
    // 测试点12：WakeLock 在 startRemoval 时 acquire，完成时 release
    // ============================================================

    /**
     * 测试点12：WakeLock 在 startRemoval 时 acquire（isHeld=true），完成时 release（isHeld=false）。
     * 使用 Robolectric 的 ShadowPowerManager 获取最新的 WakeLock 实例验证。
     * 用 CompletableDeferred 阻塞 removeMark 以便在执行中验证 isHeld=true。
     */
    @Test
    fun wakeLock_startRemoval时Acquire完成时Release() = runTest {
        stubLoggedIn()
        val latch = CompletableDeferred<Unit>()
        coEvery { doubanSyncedItemDao.getByImdbId(any()) } returns buildSyncedItem()
        coEvery { doubanRepository.fetchCsrfToken(any(), any()) } returns "csrf-token"
        coEvery { doubanRepository.removeMark(any(), any(), any()) } coAnswers {
            latch.await()
            MarkWriteResult(success = true, statusCode = 302, message = "ok")
        }

        manager.startRemoval(listOf(buildItem()), isMovie = true)
        // 等待协程进入 removeMark（此时 WakeLock 应已 acquire）
        assertThat(waitForCondition { manager.isRunning() }).isTrue()

        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertThat(wakeLock).isNotNull()
        assertThat(wakeLock!!.isHeld).isTrue()

        // 释放 latch，等待完成
        latch.complete(Unit)
        waitForCompletion()

        // 完成后 WakeLock 应已 release
        assertThat(wakeLock.isHeld).isFalse()
    }
}
