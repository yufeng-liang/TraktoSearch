package com.tracktosearch.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.local.db.DoubanSyncPendingItemDao
import com.tracktosearch.data.local.db.DoubanSyncRollbackDao
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DelayInfo
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanDetailInfo
import com.tracktosearch.data.remote.douban.DoubanMarkItem
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.util.PersistentTtlCache
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * DoubanSyncManager syncBatchToTrakt 流水线单元测试。
 *
 * 覆盖 syncBatchToTrakt 的核心分支:
 * - 空列表短路返回 / 全部跳过 / skipFailuresIds 跳过
 * - 详情页爬取成功/失败/无 IMDB ID
 * - Trakt 查询失败
 * - existingFailures 累加 attemptCount
 * - 完整流水线(详情页→Trakt 查询→写入 Trakt)成功路径
 *
 * syncBatchToTrakt 是 private suspend 方法,通过反射调用,需处理:
 * - JVM 签名比 Kotlin 多一个 Continuation 参数
 * - 返回值可能是 COROUTINE_SUSPENDED(挂起)或实际 BatchSyncResult(同步完成)
 * - 返回类型 BatchSyncResult 是 private 嵌套 data class,通过反射读取字段
 *
 * 构造参数顺序(共15个)与 DoubanSyncManagerCancelTest 保持一致。
 *
 * 注意: DoubanSyncManager 的 appScope 硬编码使用 Dispatchers.IO(真实线程),
 * 但 syncBatchToTrakt 内部的 coroutineScope 继承反射调用线程的上下文,
 * mock 的 fetchDetail/getCachedTraktIdByImdb 同步返回,Channel capacity 充足,
 * 流水线在调用线程快速完成,反射调用同步返回结果。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DoubanSyncManagerBatchPipelineTest {

    // init 块依赖:必须返回有效 StateFlow,否则构造时 collect 会 NPE
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
        // 清除前序测试的 stub 和调用记录,确保 coVerify 不受干扰
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
        // 重新 stub delayEvent(clearMocks 后需重新设置,否则 init 块 collect 会 NPE)
        every { doubanRepository.delayEvent } returns delayEventFlow

        // 默认:已登录 Trakt,未登录豆瓣(避免进入实际同步流程)
        every { tokenStorage.getCachedAccessToken() } returns "fake-token"
        coEvery { tokenStorage.isTokenValid() } returns true
        every { doubanAuthStorage.getCredentials() } returns null
        coEvery { cloudPersonalSyncManager.uploadAll(any(), any(), any()) } returns true

        // 每个测试创建新的 manager 实例,避免 progress/syncJob 状态泄漏
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
    // 空列表短路 / 跳过逻辑
    // ============================================================

    /**
     * 空列表: pending.isEmpty() 触发短路分支,
     * 调用 onProgress(items.size=0, "断点续传跳过", 0, null, null),
     * 返回 BatchSyncResult(success=0, failed=[], skipped=0, cacheHit=0)。
     */
    @Test
    fun 空列表短路返回() {
        val progressCalls = mutableListOf<ProgressCall>()
        val result = invokeSyncBatchToTrakt(
            items = emptyList(),
            status = DoubanMarkStatus.WISH,
            onProgress = { c, p, h, t, f -> progressCalls.add(ProgressCall(c, p, h, t, f)) }
        )
        // onProgress 被调用一次,subPhase="断点续传跳过"
        assertThat(progressCalls).hasSize(1)
        assertThat(progressCalls[0].subPhase).isEqualTo("断点续传跳过")
        assertThat(progressCalls[0].current).isEqualTo(0)
        assertThat(progressCalls[0].cacheHit).isEqualTo(0)
        assertThat(progressCalls[0].title).isNull()
        assertThat(progressCalls[0].failure).isNull()
        // BatchSyncResult 字段
        assertThat(resultSuccess(result)).isEqualTo(0)
        assertThat(resultSkipped(result)).isEqualTo(0)
        assertThat(resultCacheHit(result)).isEqualTo(0)
        assertThat(resultFailed(result)).isEmpty()
        assertThat(resultSuccessDoubanIds(result)).isEmpty()
    }

    /**
     * 所有 items 的 doubanId 都在 syncedIds 中,pending 为空触发短路,
     * skippedCount = items.size - pending.size = 2,
     * onProgress 被调用 with current=items.size=2。
     */
    @Test
    fun 全部跳过syncedIds覆盖() {
        val items = listOf(
            buildMarkItem("1"),
            buildMarkItem("2")
        )
        val progressCalls = mutableListOf<ProgressCall>()
        val result = invokeSyncBatchToTrakt(
            items = items,
            status = DoubanMarkStatus.WISH,
            syncedIds = setOf("1", "2"),
            onProgress = { c, p, h, t, f -> progressCalls.add(ProgressCall(c, p, h, t, f)) }
        )
        assertThat(progressCalls).hasSize(1)
        assertThat(progressCalls[0].subPhase).isEqualTo("断点续传跳过")
        assertThat(progressCalls[0].current).isEqualTo(2)
        // skipped = items.size - pending.size = 2 - 0 = 2
        assertThat(resultSkipped(result)).isEqualTo(2)
        assertThat(resultSuccess(result)).isEqualTo(0)
        assertThat(resultFailed(result)).isEmpty()
        // pending 为空,不会调用 fetchDetail
        coVerify(exactly = 0) {
            doubanRepository.fetchDetail(any(), any(), any(), any(), any())
        }
    }

    /**
     * skipFailuresIds 跳过指定 doubanId:
     * items 有 3 个(doubanId=1,2,3),skipFailuresIds={"2"},
     * pending 只含 doubanId=1,3,skipped=1,
     * fetchDetail 只被调用 2 次(对 doubanId=1,3)。
     */
    @Test
    fun skipFailuresIds跳过指定条目() {
        val items = listOf(
            buildMarkItem("1"),
            buildMarkItem("2"), // 在 skipFailuresIds 中,被跳过
            buildMarkItem("3")
        )
        // fetchDetail 返回 null,让所有 pending 项都进入 DETAIL_FETCH_FAILED 分支
        coEvery {
            doubanRepository.fetchDetail(any(), any(), any(), any(), any())
        } returns Pair(null, false)

        val result = invokeSyncBatchToTrakt(
            items = items,
            status = DoubanMarkStatus.WISH,
            skipFailuresIds = setOf("2")
        )
        // skipped=1(doubanId=2 被跳过)
        assertThat(resultSkipped(result)).isEqualTo(1)
        // fetchDetail 只被调用 2 次(doubanId=1,3)
        coVerify(exactly = 2) {
            doubanRepository.fetchDetail(any(), any(), any(), any(), any())
        }
        // 失败项 2 个(doubanId=1,3)
        assertThat(resultFailed(result)).hasSize(2)
        val failedIds = resultFailed(result).map { it.doubanId }.toSet()
        assertThat(failedIds).containsExactly("1", "3")
    }

    // ============================================================
    // 阶段 1: 详情页爬取
    // ============================================================

    /**
     * 详情页爬取失败: fetchDetail 返回 Pair(null, false),
     * failed 列表包含 DETAIL_FETCH_FAILED 失败项,
     * onProgress 被调用 with subPhase="详情页" 且 failure != null。
     */
    @Test
    fun 详情页爬取失败标记DETAIL_FETCH_FAILED() {
        val items = listOf(buildMarkItem("1"))
        coEvery {
            doubanRepository.fetchDetail(any(), any(), any(), any(), any())
        } returns Pair(null, false)

        val progressCalls = mutableListOf<ProgressCall>()
        val result = invokeSyncBatchToTrakt(
            items = items,
            status = DoubanMarkStatus.WISH,
            onProgress = { c, p, h, t, f -> progressCalls.add(ProgressCall(c, p, h, t, f)) }
        )
        val failed = resultFailed(result)
        assertThat(failed).hasSize(1)
        assertThat(failed[0].failureReason).isEqualTo(FailureReason.DETAIL_FETCH_FAILED)
        assertThat(failed[0].doubanId).isEqualTo("1")
        assertThat(failed[0].title).isEqualTo("测试-1")
        // onProgress 被调用,subPhase="详情页",failure 非空
        assertThat(progressCalls.any { it.subPhase == "详情页" && it.failure != null }).isTrue()
        assertThat(resultSuccess(result)).isEqualTo(0)
    }

    /**
     * 详情页爬取成功但无 IMDB ID: fetchDetail 返回 Pair(detail_with_null_imdbId, false),
     * failed 列表包含 NO_IMDB_ID 失败项,
     * onProgress 被调用 with subPhase="详情页"。
     */
    @Test
    fun 详情页无IMDB_ID标记NO_IMDB_ID() {
        val items = listOf(buildMarkItem("1"))
        coEvery {
            doubanRepository.fetchDetail(any(), any(), any(), any(), any())
        } returns Pair(buildDetail(imdbId = null, isTvShow = false), false)

        val progressCalls = mutableListOf<ProgressCall>()
        val result = invokeSyncBatchToTrakt(
            items = items,
            status = DoubanMarkStatus.WISH,
            onProgress = { c, p, h, t, f -> progressCalls.add(ProgressCall(c, p, h, t, f)) }
        )
        val failed = resultFailed(result)
        assertThat(failed).hasSize(1)
        assertThat(failed[0].failureReason).isEqualTo(FailureReason.NO_IMDB_ID)
        assertThat(failed[0].doubanId).isEqualTo("1")
        // NO_IMDB_ID 不可恢复
        assertThat(failed[0].failureReason.recoverable).isFalse()
        assertThat(progressCalls.any { it.subPhase == "详情页" && it.failure != null }).isTrue()
        assertThat(resultSuccess(result)).isEqualTo(0)
    }

    // ============================================================
    // 阶段 2: Trakt 查询
    // ============================================================

    /**
     * Trakt 查询失败:
     * - fetchDetail 返回有效详情(含 imdbId)
     * - getCachedTraktIdByImdb 返回 null(缓存未命中)
     * - searchByImdb 返回 Result.success(emptyList())
     * failed 列表包含 TRAKT_NOT_FOUND 失败项,
     * onProgress 被调用 with subPhase="Trakt 查询"。
     */
    @Test
    fun Trakt查询失败标记TRAKT_NOT_FOUND() {
        val items = listOf(buildMarkItem("1"))
        coEvery {
            doubanRepository.fetchDetail(any(), any(), any(), any(), any())
        } returns Pair(buildDetail(imdbId = "tt12345", isTvShow = false), false)
        every { traktRepository.getCachedTraktIdByImdb(any(), any()) } returns null
        coEvery { traktRepository.searchByImdb(any(), any()) } returns Result.success(emptyList())

        val progressCalls = mutableListOf<ProgressCall>()
        val result = invokeSyncBatchToTrakt(
            items = items,
            status = DoubanMarkStatus.WISH,
            onProgress = { c, p, h, t, f -> progressCalls.add(ProgressCall(c, p, h, t, f)) }
        )
        val failed = resultFailed(result)
        assertThat(failed).hasSize(1)
        assertThat(failed[0].failureReason).isEqualTo(FailureReason.TRAKT_NOT_FOUND)
        assertThat(failed[0].doubanId).isEqualTo("1")
        // TRAKT_NOT_FOUND 不可恢复
        assertThat(failed[0].failureReason.recoverable).isFalse()
        // onProgress 被调用,subPhase="Trakt 查询",failure 非空
        assertThat(progressCalls.any { it.subPhase == "Trakt 查询" && it.failure != null }).isTrue()
        assertThat(resultSuccess(result)).isEqualTo(0)
    }

    /**
     * 完整流水线成功:
     * - fetchDetail 返回有效详情(含 imdbId,isTvShow=false → MediaType.MOVIE)
     * - getCachedTraktIdByImdb 返回有效 traktId(缓存命中,不调用 searchByImdb)
     * - watchlistWatchedIds=null → 阶段3冲突分类走默认分支(WISH 且未在 watchlist/watched → 加入 watchlist)
     * - 阶段4 batchAddToWatchlist(relaxed mock 自动返回)
     * success=1,failed 为空,successDoubanIds 包含 doubanId。
     *
     * 字段验证补强:用 slot 捕获 batchAddToWatchlist 的 movieIds/showIds 和
     * doubanSyncedItemDao.insertAll 的 DoubanSyncedItem 列表,验证全字段正确传递。
     * 回归场景:若 syncBatchToTrakt 字段映射错误(如 doubanId/traktId 错位、
     * status 写成 "collect" 而非 "wish"、mediaType 推断错误、rating 转换丢失),
     * synced_items 表会写入错误数据,后续状态一致性检查和重试都会受影响。
     */
    @Test
    fun 完整流水线成功写入Trakt() {
        val items = listOf(buildMarkItem("1"))
        coEvery {
            doubanRepository.fetchDetail(any(), any(), any(), any(), any())
        } returns Pair(buildDetail(imdbId = "tt12345", isTvShow = false), false)
        every { traktRepository.getCachedTraktIdByImdb(any(), any()) } returns 12345

        // slot 捕获 batchAddToWatchlist 的 movieIds/showIds
        val movieIdsSlot = slot<List<Int>>()
        val showIdsSlot = slot<List<Int>>()
        coEvery {
            traktRepository.batchAddToWatchlist(capture(movieIdsSlot), capture(showIdsSlot))
        } returns mockk(relaxed = true)
        // slot 捕获 doubanSyncedItemDao.insertAll 的参数
        val syncedItemsSlot = slot<List<DoubanSyncedItem>>()
        coEvery { doubanSyncedItemDao.insertAll(capture(syncedItemsSlot)) } returns Unit

        val progressCalls = mutableListOf<ProgressCall>()
        val result = invokeSyncBatchToTrakt(
            items = items,
            status = DoubanMarkStatus.WISH,
            onProgress = { c, p, h, t, f -> progressCalls.add(ProgressCall(c, p, h, t, f)) }
        )
        assertThat(resultSuccess(result)).isEqualTo(1)
        assertThat(resultFailed(result)).isEmpty()
        assertThat(resultSuccessDoubanIds(result)).containsExactly("1")
        // 缓存命中 traktId,不调用 searchByImdb
        coVerify(exactly = 0) { traktRepository.searchByImdb(any(), any()) }
        // 阶段4: status=WISH 调用 batchAddToWatchlist
        coVerify(atLeast = 1) { traktRepository.batchAddToWatchlist(any(), any()) }

        // 字段验证:batchAddToWatchlist 的 movieIds=[12345], showIds=[]
        assertThat(movieIdsSlot.captured).containsExactly(12345)
        assertThat(showIdsSlot.captured).isEmpty()

        // 字段验证:doubanSyncedItemDao.insertAll 写入的 DoubanSyncedItem 全字段
        assertThat(syncedItemsSlot.captured).hasSize(1)
        val synced = syncedItemsSlot.captured[0]
        assertThat(synced.doubanId).isEqualTo("1") // 来自 buildMarkItem
        assertThat(synced.imdbId).isEqualTo("tt12345") // 来自 buildDetail
        assertThat(synced.traktId).isEqualTo(12345) // 来自 getCachedTraktIdByImdb
        assertThat(synced.title).isEqualTo("测试-1") // 来自 buildMarkItem.title
        assertThat(synced.status).isEqualTo("wish") // status.path（WISH 分支）
        assertThat(synced.rating).isNull() // buildMarkItem.rating=null
        assertThat(synced.syncedAt).isGreaterThan(0L) // System.currentTimeMillis()
        assertThat(synced.mediaType).isEqualTo("movie") // isTvShow=false → MOVIE → "movie"

        // 阶段1成功路径不调用 onProgress(只有失败才调用),且 cacheHit=0 不触发详情页阶段回调;
        // 阶段2成功调用 onProgress(done, "Trakt 查询", 0, title, null),failure=null 表示成功
        assertThat(progressCalls.any { it.subPhase == "Trakt 查询" && it.failure == null }).isTrue()
        // 阶段4开始时调用 onProgress(withTraktId.size, "写入 Trakt", 0, null, null)
        assertThat(progressCalls.any { it.subPhase == "写入 Trakt" }).isTrue()
    }

    /**
     * 字段验证补强:COLLECT 分支的 DoubanSyncedItem 字段传递。
     * status=COLLECT 时,synced_items 表的 status 字段应为 "collect",
     * mediaType 由 isTvShow 推断。验证评分转换:豆瓣 rating=5 → Trakt 评分=10,
     * 但 synced_items 表的 rating 字段保留豆瓣原始 rating=5(不转换)。
     */
    @Test
    fun 完整流水线COLLECT分支_syncedItem字段正确传递() {
        val markItem = DoubanMarkItem(
            doubanId = "2", title = "测试电影-2", rating = 5,
            comment = null, markedAt = "2024-01-02",
            doubanUrl = "https://movie.douban.com/subject/2/",
            posterUrl = null
        )
        coEvery {
            doubanRepository.fetchDetail(any(), any(), any(), any(), any())
        } returns Pair(buildDetail(imdbId = "tt67890", isTvShow = true), false)
        every { traktRepository.getCachedTraktIdByImdb(any(), any()) } returns 67890

        val syncedItemsSlot = slot<List<DoubanSyncedItem>>()
        coEvery { doubanSyncedItemDao.insertAll(capture(syncedItemsSlot)) } returns Unit
        // COLLECT 分支会调用 batchRemoveFromWatchlist + batchMarkAsWatchedAt
        coEvery { traktRepository.batchRemoveFromWatchlist(any(), any()) } returns mockk(relaxed = true)
        coEvery { traktRepository.batchMarkAsWatchedAt(any(), any()) } returns mockk(relaxed = true)
        coEvery { traktRepository.batchAddRatingsAt(any(), any()) } returns Result.success(Unit)

        val result = invokeSyncBatchToTrakt(
            items = listOf(markItem),
            status = DoubanMarkStatus.COLLECT
        )
        assertThat(resultSuccess(result)).isEqualTo(1)

        // 字段验证:COLLECT 分支的 DoubanSyncedItem
        assertThat(syncedItemsSlot.captured).hasSize(1)
        val synced = syncedItemsSlot.captured[0]
        assertThat(synced.doubanId).isEqualTo("2")
        assertThat(synced.imdbId).isEqualTo("tt67890")
        assertThat(synced.traktId).isEqualTo(67890)
        assertThat(synced.title).isEqualTo("测试电影-2")
        assertThat(synced.status).isEqualTo("collect") // COLLECT 分支
        assertThat(synced.rating).isEqualTo(5) // 豆瓣原始 rating,未转换
        assertThat(synced.mediaType).isEqualTo("show") // isTvShow=true → SHOW → "show"
    }

    // ============================================================
    // existingFailures 累加 attemptCount
    // ============================================================

    /**
     * existingFailures 保持 attemptCount:
     * 传入 existingFailures(attemptCount=2),fetchDetail 失败,
     * buildFailure 读取 existingMap[doubanId] 的 attemptCount,
     * 新失败项 attemptCount = 2 + 1 = 3。
     *
     * 验证: existingFailures 的 attemptCount 在原基础累加(同步模式下 attemptCount=0+1=1,
     * 重试模式下 attemptCount=existing+1)。
     */
    @Test
    fun existingFailures累加attemptCount() {
        val items = listOf(buildMarkItem("1"))
        coEvery {
            doubanRepository.fetchDetail(any(), any(), any(), any(), any())
        } returns Pair(null, false)

        val existing = listOf(
            buildExistingFailure(doubanId = "1", attemptCount = 2)
        )
        val result = invokeSyncBatchToTrakt(
            items = items,
            status = DoubanMarkStatus.WISH,
            existingFailures = existing
        )
        val failed = resultFailed(result)
        assertThat(failed).hasSize(1)
        // existing attemptCount=2, 新失败 attemptCount = 2 + 1 = 3
        assertThat(failed[0].attemptCount).isEqualTo(3)
        assertThat(failed[0].doubanId).isEqualTo("1")
        assertThat(failed[0].failureReason).isEqualTo(FailureReason.DETAIL_FETCH_FAILED)
        // 保留 existing 的 updatedAt
        assertThat(failed[0].updatedAt).isEqualTo(1000L)
    }

    /**
     * 无 existingFailures 时 attemptCount 默认为 1(0 + 1)。
     * 作为对比测试,验证同步模式下 attemptCount 的默认行为。
     */
    @Test
    fun 无existingFailures时attemptCount默认为1() {
        val items = listOf(buildMarkItem("1"))
        coEvery {
            doubanRepository.fetchDetail(any(), any(), any(), any(), any())
        } returns Pair(null, false)

        val result = invokeSyncBatchToTrakt(
            items = items,
            status = DoubanMarkStatus.WISH,
            existingFailures = null
        )
        val failed = resultFailed(result)
        assertThat(failed).hasSize(1)
        // 无 existing → attemptCount = 0 + 1 = 1
        assertThat(failed[0].attemptCount).isEqualTo(1)
        assertThat(failed[0].updatedAt).isEqualTo(0L)
    }

    // ============================================================
    // 辅助函数
    // ============================================================

    /** onProgress 回调记录 */
    private data class ProgressCall(
        val current: Int,
        val subPhase: String,
        val cacheHit: Int,
        val title: String?,
        val failure: DoubanSyncFailure?
    )

    /** 构造测试用 DoubanMarkItem */
    private fun buildMarkItem(doubanId: String, title: String = "测试-$doubanId"): DoubanMarkItem =
        DoubanMarkItem(
            doubanId = doubanId,
            title = title,
            rating = null,
            comment = null,
            markedAt = "2024-01-01",
            doubanUrl = "https://movie.douban.com/subject/$doubanId/",
            posterUrl = null
        )

    /** 构造测试用 DoubanDetailInfo */
    private fun buildDetail(imdbId: String?, isTvShow: Boolean = false): DoubanDetailInfo =
        DoubanDetailInfo(
            imdbId = imdbId,
            isTvShow = isTvShow,
            title = "测试电影",
            posterUrl = null,
            genres = emptyList(),
            year = "2024",
            countries = emptyList(),
            directors = emptyList()
        )

    /** 构造测试用 DoubanSyncFailure(用于 existingFailures) */
    private fun buildExistingFailure(doubanId: String, attemptCount: Int = 2): DoubanSyncFailure =
        DoubanSyncFailure(
            doubanId = doubanId,
            title = "失败项-$doubanId",
            posterUrl = null,
            rating = null,
            comment = null,
            markedAt = "2024-01-01",
            doubanUrl = "https://movie.douban.com/subject/$doubanId/",
            status = DoubanMarkStatus.WISH,
            failureReason = FailureReason.DETAIL_FETCH_FAILED,
            failedAt = 0L,
            updatedAt = 1000L,
            attemptCount = attemptCount
        )

    /**
     * 反射调用 private suspend fun syncBatchToTrakt(...)。
     *
     * suspend 方法 JVM 签名多一个 Continuation 参数,返回值为
     * COROUTINE_SUSPENDED(挂起)或实际结果(同步完成)。
     * 用 CountDownLatch 兼容两种完成路径:挂起时由 continuation.resumeWith 唤醒,
     * 同步完成时由主线程直接 countDown。
     *
     * 返回 BatchSyncResult(private 嵌套 data class)实例,通过 [readResultField] 读取字段。
     */
    private fun invokeSyncBatchToTrakt(
        items: List<DoubanMarkItem>,
        status: DoubanMarkStatus,
        cookie: String = "test-cookie",
        syncedIds: Set<String> = emptySet(),
        watchlistWatchedIds: TraktRepository.WatchlistWatchedIds? = null,
        onProgress: (Int, String, Int, String?, DoubanSyncFailure?) -> Unit = { _, _, _, _, _ -> },
        existingFailures: List<DoubanSyncFailure>? = null,
        skipFailuresIds: Set<String> = emptySet()
    ): Any? {
        val method = DoubanSyncManager::class.java.getDeclaredMethod(
            "syncBatchToTrakt",
            List::class.java,
            DoubanMarkStatus::class.java,
            String::class.java,
            Set::class.java,
            TraktRepository.WatchlistWatchedIds::class.java,
            kotlin.Function5::class.java,
            List::class.java,
            Set::class.java,
            Continuation::class.java
        )
        method.isAccessible = true

        val latch = CountDownLatch(1)
        var error: Throwable? = null
        var resultValue: Any? = null
        val continuation = object : Continuation<Any?> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) {
                result.onSuccess { resultValue = it }
                    .onFailure { error = it }
                latch.countDown()
            }
        }

        // Kotlin 5 参数 lambda 编译为实现 Function5 接口的对象,可直接传入反射调用
        val onProgressFun: (Int, String, Int, String?, DoubanSyncFailure?) -> Unit =
            { a, b, c, d, e -> onProgress(a, b, c, d, e) }

        val rawResult = method.invoke(
            manager,
            items,
            status,
            cookie,
            syncedIds,
            watchlistWatchedIds,
            onProgressFun,
            existingFailures,
            skipFailuresIds,
            continuation
        )
        // 同步完成时 rawResult != COROUTINE_SUSPENDED,continuation 不会被调用,主动 countDown
        if (rawResult != COROUTINE_SUSPENDED) {
            resultValue = rawResult
            latch.countDown()
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw AssertionError("syncBatchToTrakt 在 15 秒内未完成")
        }
        error?.let { throw it }
        return resultValue
    }

    /** 反射读取 BatchSyncResult(private 嵌套 data class)的字段 */
    private fun readResultField(result: Any?, fieldName: String): Any? {
        if (result == null) return null
        val field = result.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(result)
    }

    private fun resultSuccess(result: Any?): Int = readResultField(result, "success") as Int
    private fun resultSkipped(result: Any?): Int = readResultField(result, "skipped") as Int
    private fun resultCacheHit(result: Any?): Int = readResultField(result, "cacheHit") as Int

    @Suppress("UNCHECKED_CAST")
    private fun resultFailed(result: Any?): List<DoubanSyncFailure> =
        readResultField(result, "failed") as List<DoubanSyncFailure>

    @Suppress("UNCHECKED_CAST")
    private fun resultSuccessDoubanIds(result: Any?): Set<String> =
        readResultField(result, "successDoubanIds") as Set<String>
}
