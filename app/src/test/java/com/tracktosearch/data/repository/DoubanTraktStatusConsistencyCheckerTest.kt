package com.tracktosearch.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.LastConsistencyCheckStorage
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DelayInfo
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.MarkWriteResult
import com.tracktosearch.data.remote.trakt.dto.TraktSyncResponse
import com.tracktosearch.data.repository.TraktRepository.WatchlistWatchedIds
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * DoubanTraktStatusConsistencyChecker 单元测试。
 *
 * 覆盖点（共12个）：
 * 1. checkAndUnify() 空表 → 返回 isComplete=true，不调用任何更新方法
 * 2. checkAndUnify() 一致状态（douban=collect+trakt=watched，douban=wish+trakt=watchlist）→ 无更新，conflictsFound=0
 * 3. checkAndUnify() douban=collect + trakt=watchlist → 冲突，traktNeedWatched，调用 batchMarkAsWatched
 * 4. checkAndUnify() douban=collect + trakt=未标记 → traktNeedWatched，调用 batchMarkAsWatched
 * 5. checkAndUnify() douban=wish + trakt=watched → 冲突，doubanNeedUpdate(collect)，调用 markInterest
 * 6. checkAndUnify() douban=wish + trakt=未标记 → traktNeedWatchlist，调用 batchAddToWatchlist
 * 7. checkAndUnify() traktId=null → skipped 计数
 * 8. checkAndUnify() 完成后调用 lastConsistencyCheckStorage.recordCheck()
 * 9. checkAndUnify() 豆瓣侧更新成功 → doubanUpdated 计数正确，调用 updateStatus
 * 10. checkAndUnifyWithCrawl() 未登录豆瓣 → checkProgress 最终 cookieExpired=true, isComplete=true
 * 11. checkAndUnifyWithCrawl() 已在运行 → 返回 false
 * 12. resetProgress() 非运行时重置为初始值；isRunning() checkAndUnify 后为 false
 *
 * 注意：checker init 块在 appScope（Dispatchers.IO 真实线程）启动协程收集
 * doubanRepository.delayEvent，构造 checker 前必须 mock 返回有效的 MutableStateFlow。
 *
 * checkAndUnify() 是 suspend 函数，可直接在 runTest 中调用（内部 withContext(Dispatchers.IO)
 * 切到真实 IO 线程，mockk 桩线程安全）。
 * checkAndUnifyWithCrawl() 是非 suspend 函数，内部在真实 Dispatchers.IO 上启动协程，
 * 测试通过轮询 checkProgress.value.isComplete 等待协程完成。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubanTraktStatusConsistencyCheckerTest {

    // init 块依赖：必须返回有效 StateFlow，否则构造时 collect 会 NPE
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
        // 清除前序测试的 stub 和调用记录，确保 coVerify(exactly = 0) 不受干扰
        clearMocks(doubanSyncedItemDao, traktRepository, doubanRepository, doubanAuthStorage, lastConsistencyCheckStorage)
        // 重新 stub delayEvent（init 块已 collect 过，保持 stub 一致避免后续访问 NPE）
        every { doubanRepository.delayEvent } returns delayEventFlow
        // 每个测试创建新的 checker 实例，避免 checkProgress/checkJob 状态泄漏
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
    // 辅助函数
    // ============================================================

    /** 构造豆瓣同步记录 */
    private fun buildSyncedItem(
        doubanId: String = "db-001",
        traktId: Int? = 1,
        title: String = "测试电影",
        status: String = "collect",
        mediaType: String = "movie"
    ): DoubanSyncedItem = DoubanSyncedItem(
        doubanId = doubanId,
        imdbId = "tt0000001",
        traktId = traktId,
        title = title,
        status = status,
        rating = null,
        syncedAt = 1000L,
        mediaType = mediaType
    )

    /** 设置已登录凭证 */
    private fun stubLoggedIn(cookie: String = "ck=test-cookie") {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials(
            userId = "u-1",
            cookie = cookie
        )
    }

    /** stub Trakt 批量操作返回成功（Result.success） */
    private fun stubTraktBatchSuccess() {
        coEvery { traktRepository.loadWatchlistWatchedIds() } returns WatchlistWatchedIds()
        coEvery { traktRepository.batchMarkAsWatched(any(), any()) } returns Result.success(TraktSyncResponse())
        coEvery { traktRepository.batchAddToWatchlist(any(), any()) } returns Result.success(TraktSyncResponse())
    }

    /** stub 豆瓣标记成功链路（fetchCsrfToken → markInterest success） */
    private fun stubDoubanMarkSuccess() {
        coEvery { doubanRepository.fetchCsrfToken(any(), any()) } returns "csrf-token"
        coEvery {
            doubanRepository.markInterest(any(), any(), any(), any())
        } returns true
    }

    /** 轮询等待 checkProgress.isComplete == true 且 !isRunning()，带超时保护避免死锁 */
    private fun waitForCompletion(timeoutMs: Long = 5000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val p = checker.checkProgress.value
            if (p.isComplete && !checker.isRunning()) return true
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
    // 测试点1：空表 → 返回 isComplete=true，不调用任何更新方法
    // ============================================================

    /**
     * 测试点1：checkAndUnify() 空表 → 返回 isComplete=true，totalChecked=0，
     * 不调用任何更新方法（batchMarkAsWatched/batchAddToWatchlist/markInterest）。
     */
    @Test
    fun checkAndUnify_空表_返回IsComplete不调用任何更新方法() = runTest {
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns emptyList()

        val result = checker.checkAndUnify()

        assertThat(result.isComplete).isTrue()
        assertThat(result.totalChecked).isEqualTo(0)
        assertThat(result.conflictsFound).isEqualTo(0)
        coVerify(exactly = 0) { traktRepository.batchMarkAsWatched(any(), any()) }
        coVerify(exactly = 0) { traktRepository.batchAddToWatchlist(any(), any()) }
        coVerify(exactly = 0) { doubanRepository.markInterest(any(), any(), any(), any()) }
    }

    // ============================================================
    // 测试点2：一致状态 → 无更新操作，conflictsFound=0
    // ============================================================

    /**
     * 测试点2：checkAndUnify() 一致状态（douban=collect+trakt=watched，douban=wish+trakt=watchlist）
     * → 无更新操作，conflictsFound=0，traktUpdated=0，doubanUpdated=0。
     */
    @Test
    fun checkAndUnify_一致状态_无更新操作ConflictsZero() = runTest {
        val items = listOf(
            // douban=collect + trakt=watched → 一致
            buildSyncedItem(doubanId = "db-1", traktId = 1, status = "collect"),
            // douban=wish + trakt=watchlist → 一致
            buildSyncedItem(doubanId = "db-2", traktId = 2, status = "wish")
        )
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns items
        every { traktRepository.getWatchlistWatchedIds() } returns WatchlistWatchedIds(
            movieWatchedTraktIds = setOf(1),     // traktId=1 已看
            movieWatchlistTraktIds = setOf(2)    // traktId=2 想看
        )
        stubTraktBatchSuccess()

        val result = checker.checkAndUnify()

        assertThat(result.conflictsFound).isEqualTo(0)
        assertThat(result.traktUpdated).isEqualTo(0)
        assertThat(result.doubanUpdated).isEqualTo(0)
        assertThat(result.skipped).isEqualTo(0)
        assertThat(result.totalChecked).isEqualTo(2)
        coVerify(exactly = 0) { traktRepository.batchMarkAsWatched(any(), any()) }
        coVerify(exactly = 0) { traktRepository.batchAddToWatchlist(any(), any()) }
        coVerify(exactly = 0) { doubanRepository.markInterest(any(), any(), any(), any()) }
    }

    // ============================================================
    // 测试点3：douban=collect + trakt=watchlist → 冲突，traktNeedWatched
    // ============================================================

    /**
     * 测试点3：checkAndUnify() douban=collect + trakt=watchlist → 冲突（conflictsFound=1），
     * traktNeedWatched，调用 batchMarkAsWatched，traktUpdated=1。
     *
     * 字段验证补强:用 slot 捕获 batchMarkAsWatched 的 movieIds/showIds,
     * 验证 traktId 和 mediaType 分流正确（movie→movieIds, show→showIds）。
     * 回归场景:若 DoubanSyncedItem.traktId 未正确传递到 batchMarkAsWatched,
     * 或 mediaType 分流出错（movie 错分到 showIds）,会导致错误的影视被标记为已看。
     */
    @Test
    fun checkAndUnify_doubanCollectTraktWatchlist_冲突TraktNeedWatched() = runTest {
        val items = listOf(
            buildSyncedItem(doubanId = "db-1", traktId = 1, status = "collect", mediaType = "movie")
        )
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns items
        every { traktRepository.getWatchlistWatchedIds() } returns WatchlistWatchedIds(
            movieWatchlistTraktIds = setOf(1)  // traktId=1 在想看列表（不是已看）
        )
        // slot 捕获 batchMarkAsWatched 参数
        val movieIdsSlot = slot<List<Int>>()
        val showIdsSlot = slot<List<Int>>()
        coEvery {
            traktRepository.batchMarkAsWatched(capture(movieIdsSlot), capture(showIdsSlot))
        } returns Result.success(TraktSyncResponse())
        coEvery { traktRepository.batchAddToWatchlist(any(), any()) } returns Result.success(TraktSyncResponse())

        val result = checker.checkAndUnify()

        assertThat(result.conflictsFound).isEqualTo(1)
        assertThat(result.traktUpdated).isEqualTo(1)
        coVerify(exactly = 1) { traktRepository.batchMarkAsWatched(any(), any()) }
        coVerify(exactly = 0) { traktRepository.batchAddToWatchlist(any(), any()) }
        // 字段验证:movieIds=[1], showIds=[]（mediaType="movie" 分流到 movieIds）
        assertThat(movieIdsSlot.captured).containsExactly(1)
        assertThat(showIdsSlot.captured).isEmpty()
    }

    /**
     * 字段验证补强:douban=collect + trakt=watchlist 冲突,但 mediaType="show" 时,
     * traktId 应分流到 showIds 而非 movieIds。
     */
    @Test
    fun checkAndUnify_doubanCollectTraktWatchlist_show类型_分流到showIds() = runTest {
        val items = listOf(
            buildSyncedItem(doubanId = "db-2", traktId = 2, status = "collect", mediaType = "show")
        )
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns items
        every { traktRepository.getWatchlistWatchedIds() } returns WatchlistWatchedIds(
            showWatchlistTraktIds = setOf(2)  // traktId=2 在想看列表
        )
        val movieIdsSlot = slot<List<Int>>()
        val showIdsSlot = slot<List<Int>>()
        coEvery {
            traktRepository.batchMarkAsWatched(capture(movieIdsSlot), capture(showIdsSlot))
        } returns Result.success(TraktSyncResponse())

        val result = checker.checkAndUnify()

        assertThat(result.traktUpdated).isEqualTo(1)
        // show 类型分流到 showIds
        assertThat(movieIdsSlot.captured).isEmpty()
        assertThat(showIdsSlot.captured).containsExactly(2)
    }

    // ============================================================
    // 测试点4：douban=collect + trakt=未标记 → traktNeedWatched
    // ============================================================

    /**
     * 测试点4：checkAndUnify() douban=collect + trakt=未标记 → traktNeedWatched（非冲突），
     * 调用 batchMarkAsWatched，traktUpdated=1。
     */
    @Test
    fun checkAndUnify_doubanCollectTrakt未标记_TraktNeedWatched() = runTest {
        val items = listOf(
            buildSyncedItem(doubanId = "db-1", traktId = 1, status = "collect")
        )
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns items
        every { traktRepository.getWatchlistWatchedIds() } returns WatchlistWatchedIds()
        stubTraktBatchSuccess()

        val result = checker.checkAndUnify()

        assertThat(result.conflictsFound).isEqualTo(0)  // 不算冲突（trakt 未标记）
        assertThat(result.traktUpdated).isEqualTo(1)
        coVerify(exactly = 1) { traktRepository.batchMarkAsWatched(any(), any()) }
        coVerify(exactly = 0) { traktRepository.batchAddToWatchlist(any(), any()) }
    }

    // ============================================================
    // 测试点5：douban=wish + trakt=watched → 冲突，doubanNeedUpdate(collect)
    // ============================================================

    /**
     * 测试点5：checkAndUnify() douban=wish + trakt=watched → 冲突（conflictsFound=1），
     * doubanNeedUpdate(action=collect)，调用 markInterest("collect", ...)，doubanUpdated=1。
     */
    @Test
    fun checkAndUnify_doubanWishTraktWatched_冲突DoubanNeedUpdateCollect() = runTest {
        val items = listOf(
            buildSyncedItem(doubanId = "db-1", traktId = 1, status = "wish")
        )
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns items
        every { traktRepository.getWatchlistWatchedIds() } returns WatchlistWatchedIds(
            movieWatchedTraktIds = setOf(1)  // traktId=1 已看
        )
        stubLoggedIn()
        stubDoubanMarkSuccess()

        val result = checker.checkAndUnify()

        assertThat(result.conflictsFound).isEqualTo(1)
        assertThat(result.doubanUpdated).isEqualTo(1)
        // markInterest 以 action="collect" 调用（豆瓣 wish → collect 对齐 trakt watched）
        coVerify(exactly = 1) { doubanRepository.markInterest("collect", "db-1", any(), any()) }
    }

    // ============================================================
    // 测试点6：douban=wish + trakt=未标记 → traktNeedWatchlist
    // ============================================================

    /**
     * 测试点6：checkAndUnify() douban=wish + trakt=未标记 → traktNeedWatchlist（非冲突），
     * 调用 batchAddToWatchlist，traktUpdated=1。
     */
    @Test
    fun checkAndUnify_doubanWishTrakt未标记_TraktNeedWatchlist() = runTest {
        val items = listOf(
            buildSyncedItem(doubanId = "db-1", traktId = 1, status = "wish")
        )
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns items
        every { traktRepository.getWatchlistWatchedIds() } returns WatchlistWatchedIds()
        stubTraktBatchSuccess()

        val result = checker.checkAndUnify()

        assertThat(result.conflictsFound).isEqualTo(0)  // 不算冲突（trakt 未标记）
        assertThat(result.traktUpdated).isEqualTo(1)
        coVerify(exactly = 0) { traktRepository.batchMarkAsWatched(any(), any()) }
        coVerify(exactly = 1) { traktRepository.batchAddToWatchlist(any(), any()) }
    }

    // ============================================================
    // 测试点7：traktId=null → skipped 计数
    // ============================================================

    /**
     * 测试点7：checkAndUnify() traktId=null → 该条计为 skipped，不参与分类/更新。
     */
    @Test
    fun checkAndUnify_traktIdNull_计为Skipped() = runTest {
        val items = listOf(
            buildSyncedItem(doubanId = "db-1", traktId = null, status = "collect")
        )
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns items
        every { traktRepository.getWatchlistWatchedIds() } returns WatchlistWatchedIds()
        stubTraktBatchSuccess()

        val result = checker.checkAndUnify()

        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.conflictsFound).isEqualTo(0)
        assertThat(result.traktUpdated).isEqualTo(0)
        assertThat(result.doubanUpdated).isEqualTo(0)
        assertThat(result.totalChecked).isEqualTo(1)
        coVerify(exactly = 0) { traktRepository.batchMarkAsWatched(any(), any()) }
        coVerify(exactly = 0) { traktRepository.batchAddToWatchlist(any(), any()) }
    }

    // ============================================================
    // 测试点8：完成后调用 lastConsistencyCheckStorage.recordCheck()
    // ============================================================

    /**
     * 测试点8：checkAndUnify() 完成后调用 lastConsistencyCheckStorage.recordCheck()。
     */
    @Test
    fun checkAndUnify_完成后调用RecordCheck() = runTest {
        val items = listOf(
            buildSyncedItem(doubanId = "db-1", traktId = 1, status = "collect")
        )
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns items
        every { traktRepository.getWatchlistWatchedIds() } returns WatchlistWatchedIds()
        stubTraktBatchSuccess()

        val result = checker.checkAndUnify()

        assertThat(result.isComplete).isTrue()
        coVerify(exactly = 1) { lastConsistencyCheckStorage.recordCheck() }
    }

    // ============================================================
    // 测试点9：豆瓣侧更新成功 → doubanUpdated 计数正确，调用 updateStatus
    // ============================================================

    /**
     * 测试点9：checkAndUnify() 豆瓣侧更新成功 → doubanUpdated 计数正确，
     * 调用 doubanSyncedItemDao.updateStatus(syncedDoubanId, action)。
     * 2 条 douban=wish + trakt=watched → 2 条 doubanNeedUpdate(collect) → 2 次 markInterest 成功。
     */
    @Test
    fun checkAndUnify_豆瓣侧更新成功_DoubanUpdated计数正确调用UpdateStatus() = runTest {
        val items = listOf(
            buildSyncedItem(doubanId = "db-1", traktId = 1, status = "wish"),
            buildSyncedItem(doubanId = "db-2", traktId = 2, status = "wish")
        )
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns items
        every { traktRepository.getWatchlistWatchedIds() } returns WatchlistWatchedIds(
            movieWatchedTraktIds = setOf(1, 2)  // traktId=1,2 已看
        )
        stubLoggedIn()
        stubDoubanMarkSuccess()

        val result = checker.checkAndUnify()

        assertThat(result.doubanUpdated).isEqualTo(2)
        assertThat(result.conflictsFound).isEqualTo(2)
        // 每条 markInterest 成功后调用 updateStatus(doubanId, "collect")
        coVerify(exactly = 1) { doubanSyncedItemDao.updateStatus("db-1", "collect") }
        coVerify(exactly = 1) { doubanSyncedItemDao.updateStatus("db-2", "collect") }
    }

    // ============================================================
    // 测试点10：checkAndUnifyWithCrawl() 未登录 → cookieExpired=true, isComplete=true
    // ============================================================

    /**
     * 测试点10：checkAndUnifyWithCrawl() 未登录豆瓣（getCredentials 返回 null）
     * → checkProgress 最终 cookieExpired=true, isComplete=true。
     */
    @Test
    fun checkAndUnifyWithCrawl_未登录_cookieExpiredTrueIsCompleteTrue() = runTest {
        every { doubanAuthStorage.getCredentials() } returns null

        val started = checker.checkAndUnifyWithCrawl()
        assertThat(started).isTrue()

        val completed = waitForCompletion()
        assertThat(completed).isTrue()

        val p = checker.checkProgress.value
        assertThat(p.isComplete).isTrue()
        assertThat(p.cookieExpired).isTrue()
        assertThat(p.isRunning).isFalse()
    }

    // ============================================================
    // 测试点11：checkAndUnifyWithCrawl() 已在运行 → 返回 false
    // ============================================================

    /**
     * 测试点11：checkAndUnifyWithCrawl() 已在运行 → 第二次调用返回 false（防重入）。
     * 用 CompletableDeferred 阻塞 fetchMarkList 使第一次任务保持运行状态。
     */
    @Test
    fun checkAndUnifyWithCrawl_已在运行_returnsFalse() = runTest {
        stubLoggedIn()
        val latch = CompletableDeferred<Unit>()
        // 阻塞 fetchMarkList 使第一次任务保持运行
        coEvery {
            doubanRepository.fetchMarkList(any(), any(), any(), any(), any(), any())
        } coAnswers {
            latch.await()
            false  // 返回 false → 走 cookieExpired 分支结束
        }

        val first = checker.checkAndUnifyWithCrawl()
        assertThat(first).isTrue()

        // 等待协程进入运行状态
        assertThat(waitForCondition { checker.isRunning() }).isTrue()

        // 第二次调用应被拒绝
        val second = checker.checkAndUnifyWithCrawl()
        assertThat(second).isFalse()

        // 释放并等待完成
        latch.complete(Unit)
        waitForCompletion()
    }

    // ============================================================
    // 测试点12：resetProgress() 非运行时重置；isRunning() checkAndUnify 后为 false
    // ============================================================

    /**
     * 测试点12：
     * Part 1：checkAndUnify() 后 isRunning() 为 false（checkAndUnify 不设置 checkJob）
     * Part 2：cancel() 修改 _checkProgress（isCancelling=true），resetProgress() 重置为初始值
     */
    @Test
    fun resetProgress_非运行时重置_IsRunningAfterCheckAndUnifyFalse() = runTest {
        // === Part 1：checkAndUnify 不设置 checkJob，isRunning() 为 false ===
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns emptyList()
        val result = checker.checkAndUnify()
        assertThat(result.isComplete).isTrue()
        // checkAndUnify 不使用 checkJob，isRunning 应为 false
        assertThat(checker.isRunning()).isFalse()

        // === Part 2：cancel() 修改 _checkProgress，resetProgress 重置 ===
        // cancel() 会修改 _checkProgress（isCancelling=true），即使没有运行中的 job
        checker.cancel()
        assertThat(checker.checkProgress.value.isCancelling).isTrue()
        assertThat(checker.checkProgress.value.phase).isEqualTo("正在取消...")

        // resetProgress 应重置（isRunning 为 false，不阻止）
        checker.resetProgress()

        // 验证重置为初始值
        val reset = checker.checkProgress.value
        assertThat(reset.isCancelling).isFalse()
        assertThat(reset.isComplete).isFalse()
        assertThat(reset.isRunning).isFalse()
        assertThat(reset.phase).isEmpty()
    }

    // ============================================================
    // 测试点13+：Trakt 侧同时存在 watchlist + watched 冲突清理
    // ============================================================

    /**
     * 测试点13：checkAndUnify() Trakt 同时存在 watchlist + watched → 冲突计数增加，
     * 调用 batchRemoveFromWatchlist 清理（保留 watched，移除 watchlist）。
     */
    @Test
    fun checkAndUnify_Trakt同时存在两个状态_调用batchRemoveFromWatchlist() = runTest {
        val items = listOf(
            // douban=collect + trakt 同时在 watched 和 watchlist → 冲突清理
            buildSyncedItem(doubanId = "db-1", traktId = 1, status = "collect")
        )
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns items
        // Trakt 侧：ID=1 同时在 watched 和 watchlist
        val conflictIds = WatchlistWatchedIds(
            movieWatchedTraktIds = setOf(1),
            movieWatchlistTraktIds = setOf(1)
        )
        every { traktRepository.getWatchlistWatchedIds() } returns conflictIds
        coEvery { traktRepository.batchRemoveFromWatchlist(any(), any()) } returns Result.success(TraktSyncResponse())

        val result = checker.checkAndUnify()

        assertThat(result.isComplete).isTrue()
        assertThat(result.conflictsFound).isGreaterThan(0)
        // 调用 batchRemoveFromWatchlist 清理 watchlist（保留 watched）
        coVerify(exactly = 1) { traktRepository.batchRemoveFromWatchlist(listOf(1), emptyList()) }
        // 不再调用 batchMarkAsWatched（已 watched 不需要重复标记）
        coVerify(exactly = 0) { traktRepository.batchMarkAsWatched(any(), any()) }
    }

    /**
     * 测试点14：checkAndUnify() Trakt 同时存在 + douban=wish → 冲突，douban 升级为 collect（已看优先）
     */
    @Test
    fun checkAndUnify_Trakt同时存在_doubanWish_升级豆瓣为Collect() = runTest {
        val items = listOf(
            buildSyncedItem(doubanId = "db-1", traktId = 1, status = "wish")
        )
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns items
        val conflictIds = WatchlistWatchedIds(
            movieWatchedTraktIds = setOf(1),
            movieWatchlistTraktIds = setOf(1)
        )
        every { traktRepository.getWatchlistWatchedIds() } returns conflictIds
        coEvery { traktRepository.batchRemoveFromWatchlist(any(), any()) } returns Result.success(TraktSyncResponse())
        stubDoubanMarkSuccess()

        val result = checker.checkAndUnify()

        assertThat(result.isComplete).isTrue()
        assertThat(result.conflictsFound).isGreaterThan(0)
        // 清理 watchlist
        coVerify(exactly = 1) { traktRepository.batchRemoveFromWatchlist(listOf(1), emptyList()) }
        // 豆瓣升级为 collect（已看优先）
        coVerify(exactly = 1) { doubanRepository.markInterest(eq("collect"), any(), any(), any()) }
    }

    /**
     * 测试点15：checkAndUnify() Trakt 冲突清理失败 → 仅记录日志，不影响完成状态
     */
    @Test
    fun checkAndUnify_Trakt冲突清理失败_仅记录日志() = runTest {
        val items = listOf(
            buildSyncedItem(doubanId = "db-1", traktId = 1, status = "collect")
        )
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns items
        val conflictIds = WatchlistWatchedIds(
            movieWatchedTraktIds = setOf(1),
            movieWatchlistTraktIds = setOf(1)
        )
        every { traktRepository.getWatchlistWatchedIds() } returns conflictIds
        coEvery { traktRepository.batchRemoveFromWatchlist(any(), any()) } returns Result.failure(Exception("网络错误"))

        val result = checker.checkAndUnify()

        // 仍完成（失败仅记录日志）
        assertThat(result.isComplete).isTrue()
        coVerify(exactly = 1) { traktRepository.batchRemoveFromWatchlist(any(), any()) }
    }
}
