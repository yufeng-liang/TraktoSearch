package com.tracktosearch.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.local.db.DoubanSyncPendingItemDao
import com.tracktosearch.data.local.db.DoubanSyncRollbackDao
import com.tracktosearch.data.local.db.DoubanSyncRollbackEntity
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DelayInfo
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.trakt.dto.TraktSyncResponse
import com.tracktosearch.data.util.PersistentTtlCache
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * DoubanSyncManager 单元测试。
 *
 * 聚焦于可直接 runTest 测试的 suspend 方法（clearPendingItems / getPendingItemsCount /
 * getRollbackCount / discardRollback / restoreRollback）以及状态管理方法
 * （isRunning / isCancelling / cancel / resetProgress）。
 *
 * 不测试 startSync / startResume / startRetry 的完整流程：这些方法内部在真实
 * Dispatchers.IO 上启动协程并涉及豆瓣爬取+Trakt 同步的复杂链路，mock 成本极高，
 * 属于集成测试范畴。
 *
 * 覆盖点（共14个）：
 * 1. clearPendingItems() → 调用 doubanSyncPendingItemDao.clearAll()
 * 2. getPendingItemsCount() → 返回 dao.count() 的值
 * 3. getRollbackCount() → 返回 dao.count() 的值
 * 4. discardRollback() → 调用 doubanSyncRollbackDao.clearAll()
 * 5. restoreRollback() 空列表 → 返回 0，不调用任何 Trakt 方法
 * 6. restoreRollback() wish+movie 条目 → 调用 batchAddToWatchlist([traktId], [])
 * 7. restoreRollback() collect+show 条目 → 调用 batchMarkAsWatched([], [traktId])
 * 8. restoreRollback() rating=4 → 调用 addRating(traktId, 8, MediaType)（豆瓣4→Trakt8）
 * 9. restoreRollback() rating=null → 不调用 addRating
 * 10. restoreRollback() batchAddToWatchlist 抛异常 → 返回 0，phase 含"恢复失败"
 * 11. restoreRollback() 成功 → 调用 clearAll()，返回 total，phase="恢复完成"
 * 12. isRunning() 初始为 false；isCancelling() 初始为 false
 * 13. cancel() → isCancelling() 为 true，phase 为 "正在取消..."
 * 14. resetProgress() 非运行时 → progress 重置为初始值
 *
 * 注意：DoubanSyncManager init 块在 appScope（Dispatchers.IO 真实线程）启动协程
 * 收集 doubanRepository.delayEvent，构造 manager 前必须 mock 返回有效的
 * MutableStateFlow，否则 init 块 collect 会 NPE。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DoubanSyncManagerTest {

    // init 块依赖：必须返回有效 StateFlow，否则构造时 collect 会 NPE
    private val delayEventFlow = MutableStateFlow<DelayInfo?>(null)

    private val doubanRepository = mockk<DoubanRepository>(relaxed = true).also {
        every { it.delayEvent } returns delayEventFlow
    }
    private val doubanAuthStorage = mockk<DoubanAuthStorage>(relaxed = true)
    private val traktRepository = mockk<TraktRepository>(relaxed = true)
    private val doubanSyncedItemDao = mockk<DoubanSyncedItemDao>(relaxed = true)
    private val doubanSyncFailureDao = mockk<DoubanSyncFailureDao>(relaxed = true)
    private val doubanSyncPendingItemDao = mockk<DoubanSyncPendingItemDao>(relaxed = true)
    private val doubanSyncRollbackDao = mockk<DoubanSyncRollbackDao>(relaxed = true)
    private val cloudFailureSyncManager = mockk<CloudFailureSyncManager>(relaxed = true)
    private val cloudPersonalSyncManager = mockk<CloudPersonalSyncManager>(relaxed = true)
    private val cloudDetailsPoolManager = mockk<CloudDetailsPoolManager>(relaxed = true)
    private val doubanSyncMetaStorage = mockk<DoubanSyncMetaStorage>(relaxed = true)
    private val doubanDetailCache = mockk<PersistentTtlCache<DoubanDetailCacheEntry>>(relaxed = true)
    private val tokenStorage = mockk<TokenStorage>(relaxed = true)
    private val statusConsistencyChecker = mockk<DoubanTraktStatusConsistencyChecker>(relaxed = true)
    private val sessionModeManager = mockk<com.tracktosearch.data.session.SessionModeManager>(relaxed = true)
    private val tmdbRepository = mockk<TmdbRepository>(relaxed = true)
    private val appContext: Context = RuntimeEnvironment.getApplication()

    private lateinit var manager: DoubanSyncManager

    @Before
    fun setUp() {
        // 清除前序测试的 stub 和调用记录，确保 coVerify(exactly = 0) 不受干扰
        clearMocks(
            doubanRepository,
            doubanAuthStorage,
            traktRepository,
            doubanSyncedItemDao,
            doubanSyncFailureDao,
            doubanSyncPendingItemDao,
            doubanSyncRollbackDao,
            cloudFailureSyncManager,
            cloudPersonalSyncManager,
            cloudDetailsPoolManager,
            doubanSyncMetaStorage,
            doubanDetailCache,
            tokenStorage,
            statusConsistencyChecker,
            sessionModeManager,
            tmdbRepository
        )
        // 重新 stub delayEvent（init 块已 collect 过，但保持 stub 一致避免后续访问 NPE）
        every { doubanRepository.delayEvent } returns delayEventFlow
        every { sessionModeManager.sessionMode } returns kotlinx.coroutines.flow.flowOf(com.tracktosearch.data.session.SessionMode.TRAKT)
        // 每个测试创建新的 manager 实例，避免 progress/syncJob 状态泄漏
        manager = DoubanSyncManager(
            doubanRepository,
            doubanAuthStorage,
            traktRepository,
            doubanSyncedItemDao,
            doubanSyncFailureDao,
            doubanSyncPendingItemDao,
            doubanSyncRollbackDao,
            cloudFailureSyncManager,
            cloudPersonalSyncManager,
            cloudDetailsPoolManager,
            doubanSyncMetaStorage,
            doubanDetailCache,
            tokenStorage,
            statusConsistencyChecker,
            sessionModeManager,
            tmdbRepository,
            appContext
        )
    }

    // ============================================================
    // 辅助函数
    // ============================================================

    /** 构造测试用的回滚记录 */
    private fun buildRollbackItem(
        doubanId: String = "db-001",
        traktId: Int = 1,
        title: String = "测试条目",
        status: String = "wish",       // "wish" | "collect"
        mediaType: String = "movie",  // "movie" | "show"
        rating: Int? = null,           // 1-5 (豆瓣评分)
        rollbackAt: Long = 1000L
    ): DoubanSyncRollbackEntity = DoubanSyncRollbackEntity(
        doubanId = doubanId,
        traktId = traktId,
        title = title,
        status = status,
        mediaType = mediaType,
        rating = rating,
        rollbackAt = rollbackAt
    )

    // ============================================================
    // 测试点1：clearPendingItems() → 调用 dao.clearAll()
    // ============================================================

    /**
     * 测试点1：clearPendingItems() 调用 doubanSyncPendingItemDao.clearAll()。
     */
    @Test
    fun clearPendingItems_调用Dao的ClearAll() = runTest {
        manager.clearPendingItems()

        coVerify(exactly = 1) { doubanSyncPendingItemDao.clearAll() }
    }

    // ============================================================
    // 测试点2：getPendingItemsCount() → 返回 dao.count()
    // ============================================================

    /**
     * 测试点2：getPendingItemsCount() 返回 doubanSyncPendingItemDao.count() 的值。
     */
    @Test
    fun getPendingItemsCount_返回DaoCount的值() = runTest {
        coEvery { doubanSyncPendingItemDao.count() } returns 5

        val result = manager.getPendingItemsCount()

        assertThat(result).isEqualTo(5)
        coVerify(exactly = 1) { doubanSyncPendingItemDao.count() }
    }

    // ============================================================
    // 测试点3：getRollbackCount() → 返回 dao.count()
    // ============================================================

    /**
     * 测试点3：getRollbackCount() 返回 doubanSyncRollbackDao.count() 的值。
     */
    @Test
    fun getRollbackCount_返回DaoCount的值() = runTest {
        coEvery { doubanSyncRollbackDao.count() } returns 3

        val result = manager.getRollbackCount()

        assertThat(result).isEqualTo(3)
        coVerify(exactly = 1) { doubanSyncRollbackDao.count() }
    }

    // ============================================================
    // 测试点4：discardRollback() → 调用 dao.clearAll()
    // ============================================================

    /**
     * 测试点4：discardRollback() 调用 doubanSyncRollbackDao.clearAll()。
     */
    @Test
    fun discardRollback_调用Dao的ClearAll() = runTest {
        manager.discardRollback()

        coVerify(exactly = 1) { doubanSyncRollbackDao.clearAll() }
    }

    // ============================================================
    // 测试点5：restoreRollback() 空列表 → 返回 0，不调用任何 Trakt 方法
    // ============================================================

    /**
     * 测试点5：restoreRollback() 回滚列表为空 → 返回 0，不调用任何 Trakt 方法，也不调用 clearAll。
     */
    @Test
    fun restoreRollback_空列表_返回0不调用Trakt方法() = runTest {
        coEvery { doubanSyncRollbackDao.getAll() } returns emptyList()

        val result = manager.restoreRollback()

        assertThat(result).isEqualTo(0)
        coVerify(exactly = 0) { traktRepository.batchAddToWatchlist(any(), any()) }
        coVerify(exactly = 0) { traktRepository.batchMarkAsWatched(any(), any()) }
        coVerify(exactly = 0) { traktRepository.addRating(any(), any(), any()) }
        // 空列表时不调用 clearAll（在 isEmpty() 分支直接 return）
        coVerify(exactly = 0) { doubanSyncRollbackDao.clearAll() }
    }

    // ============================================================
    // 测试点6：restoreRollback() wish+movie 条目 → 调用 batchAddToWatchlist
    // ============================================================

    /**
     * 测试点6：restoreRollback() 有 wish+movie 条目 → 调用 batchAddToWatchlist([traktId], [])，
     * 不调用 batchMarkAsWatched，返回 total。
     */
    @Test
    fun restoreRollback_wishMovie条目_调用BatchAddToWatchlist() = runTest {
        val item = buildRollbackItem(
            doubanId = "db-1", traktId = 1,
            status = "wish", mediaType = "movie", rating = null
        )
        coEvery { doubanSyncRollbackDao.getAll() } returns listOf(item)
        coEvery { traktRepository.batchAddToWatchlist(any(), any()) } returns Result.success(TraktSyncResponse())

        val result = manager.restoreRollback()

        assertThat(result).isEqualTo(1)
        // wish+movie → batchAddToWatchlist(movieIds=[1], showIds=[])
        coVerify(exactly = 1) { traktRepository.batchAddToWatchlist(listOf(1), emptyList()) }
        coVerify(exactly = 0) { traktRepository.batchMarkAsWatched(any(), any()) }
        // rating=null → 不调用 addRating
        coVerify(exactly = 0) { traktRepository.addRating(any(), any(), any()) }
        // 成功后清除回滚表
        coVerify(exactly = 1) { doubanSyncRollbackDao.clearAll() }
    }

    // ============================================================
    // 测试点7：restoreRollback() collect+show 条目 → 调用 batchMarkAsWatched
    // ============================================================

    /**
     * 测试点7：restoreRollback() 有 collect+show 条目 → 调用 batchMarkAsWatched([], [traktId])，
     * 不调用 batchAddToWatchlist，返回 total。
     */
    @Test
    fun restoreRollback_collectShow条目_调用BatchMarkAsWatched() = runTest {
        val item = buildRollbackItem(
            doubanId = "db-2", traktId = 2,
            status = "collect", mediaType = "show", rating = null
        )
        coEvery { doubanSyncRollbackDao.getAll() } returns listOf(item)
        coEvery { traktRepository.batchMarkAsWatched(any(), any()) } returns Result.success(TraktSyncResponse())

        val result = manager.restoreRollback()

        assertThat(result).isEqualTo(1)
        // collect+show → batchMarkAsWatched(movieIds=[], showIds=[2])
        coVerify(exactly = 1) { traktRepository.batchMarkAsWatched(emptyList(), listOf(2)) }
        coVerify(exactly = 0) { traktRepository.batchAddToWatchlist(any(), any()) }
        coVerify(exactly = 0) { traktRepository.addRating(any(), any(), any()) }
        coVerify(exactly = 1) { doubanSyncRollbackDao.clearAll() }
    }

    // ============================================================
    // 测试点8：restoreRollback() rating=4 → addRating(traktId, 8, MediaType)
    // ============================================================

    /**
     * 测试点8：restoreRollback() 条目有评分 rating=4 → 调用 addRating(traktId, 8, MediaType.MOVIE)。
     * 豆瓣评分 1-5 → Trakt 评分 1-10（×2 转换），4→8。
     */
    @Test
    fun restoreRollback_评分4_转换为Trakt评分8调用AddRating() = runTest {
        val item = buildRollbackItem(
            doubanId = "db-1", traktId = 1,
            status = "wish", mediaType = "movie", rating = 4
        )
        coEvery { doubanSyncRollbackDao.getAll() } returns listOf(item)
        coEvery { traktRepository.batchAddToWatchlist(any(), any()) } returns Result.success(TraktSyncResponse())
        coEvery { traktRepository.addRating(any(), any(), any()) } returns Result.success(Unit)

        val result = manager.restoreRollback()

        assertThat(result).isEqualTo(1)
        // 豆瓣4 → Trakt8 (rating * 2)
        coVerify(exactly = 1) { traktRepository.addRating(1, 8, MediaType.MOVIE) }
        coVerify(exactly = 1) { doubanSyncRollbackDao.clearAll() }
    }

    // ============================================================
    // 测试点9：restoreRollback() rating=null → 不调用 addRating
    // ============================================================

    /**
     * 测试点9：restoreRollback() 条目 rating=null → 不调用 addRating（`val rating = item.rating ?: continue`）。
     */
    @Test
    fun restoreRollback_评分为Null_不调用AddRating() = runTest {
        val item = buildRollbackItem(
            doubanId = "db-1", traktId = 1,
            status = "wish", mediaType = "movie", rating = null
        )
        coEvery { doubanSyncRollbackDao.getAll() } returns listOf(item)
        coEvery { traktRepository.batchAddToWatchlist(any(), any()) } returns Result.success(TraktSyncResponse())

        val result = manager.restoreRollback()

        assertThat(result).isEqualTo(1)
        coVerify(exactly = 0) { traktRepository.addRating(any(), any(), any()) }
    }

    // ============================================================
    // 测试点10：restoreRollback() batchAddToWatchlist 抛异常 → 返回 0
    // ============================================================

    /**
     * 测试点10：restoreRollback() batchAddToWatchlist 抛异常 → 返回 0，
     * progress.phase 含"恢复失败"，不调用 clearAll（失败路径不清除回滚表）。
     */
    @Test
    fun restoreRollback_batchAddToWatchlist抛异常_返回0且Phase含恢复失败() = runTest {
        val item = buildRollbackItem(
            doubanId = "db-1", traktId = 1,
            status = "wish", mediaType = "movie", rating = null
        )
        coEvery { doubanSyncRollbackDao.getAll() } returns listOf(item)
        coEvery { traktRepository.batchAddToWatchlist(any(), any()) } throws RuntimeException("网络异常")

        val result = manager.restoreRollback()

        assertThat(result).isEqualTo(0)
        // phase 含"恢复失败: 网络异常"
        assertThat(manager.progress.value.phase).contains("恢复失败")
        // 失败时不调用 clearAll（回滚数据保留供下次重试）
        coVerify(exactly = 0) { doubanSyncRollbackDao.clearAll() }
    }

    // ============================================================
    // 测试点11：restoreRollback() 成功 → clearAll 被调用，返回 total
    // ============================================================

    /**
     * 测试点11：restoreRollback() 多条目成功 → 返回 total=2，调用 clearAll，
     * progress.phase="恢复完成"，isComplete=true，successCount=2。
     *
     * 覆盖 wish+movie 和 collect+show 两条目，且 collect 条目带评分（rating=3→6）。
     */
    @Test
    fun restoreRollback_成功_调用ClearAll并返回Total() = runTest {
        val items = listOf(
            buildRollbackItem(
                doubanId = "db-1", traktId = 1,
                status = "wish", mediaType = "movie", rating = null
            ),
            buildRollbackItem(
                doubanId = "db-2", traktId = 2,
                status = "collect", mediaType = "show", rating = 3
            )
        )
        coEvery { doubanSyncRollbackDao.getAll() } returns items
        coEvery { traktRepository.batchAddToWatchlist(any(), any()) } returns Result.success(TraktSyncResponse())
        coEvery { traktRepository.batchMarkAsWatched(any(), any()) } returns Result.success(TraktSyncResponse())
        coEvery { traktRepository.addRating(any(), any(), any()) } returns Result.success(Unit)

        val result = manager.restoreRollback()

        assertThat(result).isEqualTo(2)
        // 成功后清除回滚表
        coVerify(exactly = 1) { doubanSyncRollbackDao.clearAll() }
        // 验证最终进度状态
        val p = manager.progress.value
        assertThat(p.phase).isEqualTo("恢复完成")
        assertThat(p.isComplete).isTrue()
        assertThat(p.isRunning).isFalse()
        assertThat(p.successCount).isEqualTo(2)
        assertThat(p.current).isEqualTo(2)
        assertThat(p.total).isEqualTo(2)
    }

    // ============================================================
    // 测试点12：isRunning() / isCancelling() 初始为 false
    // ============================================================

    /**
     * 测试点12：构造后未启动任何同步，isRunning() 为 false，isCancelling() 为 false。
     */
    @Test
    fun 初始状态_isRunning和IsCancelling均为False() {
        assertThat(manager.isRunning()).isFalse()
        assertThat(manager.isCancelling()).isFalse()
        // progress 也保持初始状态
        val p = manager.progress.value
        assertThat(p.isRunning).isFalse()
        assertThat(p.isCancelling).isFalse()
        assertThat(p.isComplete).isFalse()
    }

    // ============================================================
    // 测试点13：cancel() → isCancelling=true，phase="正在取消..."
    // ============================================================

    /**
     * 测试点13：cancel() 同步设置 cancelled=true + progress.isCancelling=true + phase="正在取消..."。
     *
     * 注意：cancel() 还会启动 appScope.launch 协程异步上传进度，但 isCancelling/phase
     * 是在 cancel() 主线程同步设置的，不依赖协程完成。
     */
    @Test
    fun cancel_设置IsCancelling为True且Phase为正在取消() {
        // 调用前确认初始状态
        assertThat(manager.isCancelling()).isFalse()

        manager.cancel()

        // 同步设置的状态
        assertThat(manager.isCancelling()).isTrue()
        assertThat(manager.progress.value.phase).isEqualTo("正在取消...")
        assertThat(manager.progress.value.isCancelling).isTrue()
    }

    // ============================================================
    // 测试点14：resetProgress() 非运行时重置为初始值
    // ============================================================

    /**
     * 测试点14：resetProgress() 在非运行时重置 progress 为 DoubanSyncProgress()（初始值）。
     *
     * 先 cancel() 让 progress 变化（isCancelling=true, phase="正在取消..."），
     * 再 resetProgress()（syncJob 为 null → isRunning()=false → 允许重置），
     * 验证 progress 恢复为初始状态。
     */
    @Test
    fun resetProgress_非运行时重置为初始值() {
        // 先 cancel 让 progress 偏离初始状态
        manager.cancel()
        assertThat(manager.isCancelling()).isTrue()
        assertThat(manager.progress.value.phase).isEqualTo("正在取消...")

        // resetProgress：syncJob 为 null（未运行同步），isRunning()=false，允许重置
        manager.resetProgress()

        // 验证重置为初始值
        val p = manager.progress.value
        assertThat(p.isCancelling).isFalse()
        assertThat(p.phase).isEqualTo("")
        assertThat(p.isRunning).isFalse()
        assertThat(p.isComplete).isFalse()
        assertThat(p.total).isEqualTo(0)
        assertThat(p.current).isEqualTo(0)
        assertThat(p.successCount).isEqualTo(0)
    }

    /**
     * 回归测试：startResume 在已有同步运行时返回 false。
     *
     * Bug 场景：App 启动时检测到 pending items，弹出续传对话框，
     * 用户点击"继续同步"时，如果已有同步在运行（如自动触发的同步），
     * startResume 返回 false，但 AppNavigation 忽略返回值，
     * 用户感觉"按钮无反应"。
     *
     * 此测试验证 DoubanSyncManager 层：isRunning=true 时 startResume 返回 false。
     * Composable 层的 Toast 反馈需要 Compose UI 测试覆盖。
     *
     * 技巧：mock getCachedAccessToken 挂起（delay Long.MAX_VALUE），
     * 让 syncJob 在 IO 线程上保持 active 不完成。
     */
    @Test
    fun `startResume_已有同步运行时返回false`() = runTest {
        // 让 checkTraktAvailable 内部的 getCachedAccessToken 挂起，syncJob 保持 active
        coEvery { tokenStorage.getCachedAccessToken() } coAnswers { delay(Long.MAX_VALUE); "" }

        // 启动同步（startSync 返回 true，syncJob 已创建且 active）
        val started = manager.startSync(SyncMode.FULL_REWRITE)
        assertThat(started).isTrue()
        assertThat(manager.isRunning()).isTrue()

        // 再次启动 resume 应返回 false（已有同步在运行）
        val resumeResult = manager.startResume()
        assertThat(resumeResult).isFalse()
    }

    /**
     * 回归测试：startSync 在已有同步运行时返回 false。
     *
     * 与 startResume 测试配合，确保两个启动方法都正确防护并发。
     */
    @Test
    fun `startSync_已有同步运行时返回false`() = runTest {
        coEvery { tokenStorage.getCachedAccessToken() } coAnswers { delay(Long.MAX_VALUE); "" }

        // 第一次 startSync 成功
        val first = manager.startSync(SyncMode.FULL_REWRITE)
        assertThat(first).isTrue()

        // 第二次 startSync 应返回 false
        val second = manager.startSync(SyncMode.FULL_REWRITE)
        assertThat(second).isFalse()
    }
}
