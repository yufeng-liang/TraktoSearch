package com.tracktosearch.data.repository

import android.content.Context
import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.local.db.DoubanSyncFailureEntity
import com.tracktosearch.data.local.db.DoubanSyncPendingItemDao
import com.tracktosearch.data.local.db.DoubanSyncPendingItemEntity
import com.tracktosearch.data.local.db.DoubanSyncRollbackDao
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DelayInfo
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanDetailInfo
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.remote.douban.DoubanMarkItem
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.trakt.dto.TraktSyncResponse
import com.tracktosearch.data.util.PersistentTtlCache
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
 * DoubanSyncManager 生命周期单元测试。
 *
 * 覆盖三个关注点:
 * - [checkTraktAvailable]: token 有效/空/失效分支,以及失效时不进入同步流程
 * - [persistFailures]: 正常同步按 status 覆盖 vs 重试模式 REPLACE 语义,空列表行为
 * - WakeLock: 同步启动 acquire,同步完成 release
 *
 * 注意: DoubanSyncManager 的 appScope 硬编码使用 Dispatchers.IO(真实线程),
 * 不受测试调度器控制。涉及 startSync 异步完成的测试使用 runBlocking + 轮询等待。
 *
 * persistFailures 是 private suspend 方法,通过反射调用,需处理 Continuation 参数
 * 和 COROUTINE_SUSPENDED 返回值两种完成路径。
 *
 * 构造参数顺序(共15个)与 DoubanSyncManagerCancelTest 保持一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DoubanSyncManagerLifecycleTest {

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
        every { sessionModeManager.sessionMode } returns kotlinx.coroutines.flow.flowOf(com.tracktosearch.data.session.SessionMode.TRAKT)

        // 默认:已登录 Trakt,未登录豆瓣(避免进入实际同步流程)
        // getCachedAccessToken() 是普通函数,用 every;isTokenValid() 是 suspend,用 coEvery
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
            Semaphore(3),
            appContext
        )
    }

    // ============================================================
    // checkTraktAvailable 分支
    // ============================================================

    @Test
    fun 列表页回调实时发布预览条目和统一阶段() = runBlocking {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("uid", "cookie")
        val listGate = CompletableDeferred<Unit>()
        val detailStarted = CompletableDeferred<Unit>()
        val detailGate = CompletableDeferred<Unit>()
        coEvery {
            doubanRepository.fetchMarkList(any(), any(), any(), any(), any(), any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val status = args[2] as DoubanMarkStatus
            val onPage = args[3] as suspend (List<DoubanMarkItem>, Int) -> Unit
            if (status == DoubanMarkStatus.WISH) {
                onPage(
                    listOf(
                        DoubanMarkItem(
                            doubanId = "preview-1",
                            title = "实时条目",
                            rating = 4,
                            comment = "短评",
                            markedAt = "2024-06-01",
                            doubanUrl = "https://movie.douban.com/subject/preview-1/",
                            posterUrl = null
                        )
                    ),
                    1
                )
                listGate.await()
            }
            true
        }
        coEvery {
            doubanRepository.fetchDetail(any(), any(), any(), any(), any(), any())
        } coAnswers {
            detailStarted.complete(Unit)
            detailGate.await()
            Pair(null, false)
        }

        manager.startSync()
        waitForCondition { manager.progress.value.recentItems.isNotEmpty() }

        val progress = manager.progress.value
        assertThat(progress.stage).isEqualTo(DoubanSyncStage.FETCHING_LIST)
        assertThat(progress.recentItems).containsExactly(
            DoubanSyncPreviewItem(
                doubanId = "preview-1",
                title = "实时条目",
                status = DoubanMarkStatus.WISH,
                rating = 4,
                markedAt = "2024-06-01"
            )
        )

        listGate.complete(Unit)
        detailStarted.await()
        waitForCondition { manager.progress.value.stage == DoubanSyncStage.PARSING_DATA }
        assertThat(manager.progress.value.recentItems).isEmpty()
        assertThat(manager.progress.value.processingItems).isNotEmpty()
        detailGate.complete(Unit)
        waitForCondition { manager.progress.value.isComplete }
    }

    /**
     * token 有效 → checkTraktAvailable 返回 true → 进入 runSyncLegacy
     * 未登录豆瓣 → phase="未登录豆瓣"(包含"豆瓣",不包含"Trakt")
     */
    @Test
    fun token有效时checkTraktAvailable进入同步流程phase包含豆瓣() = runBlocking {
        // 默认 stub:token 有效,豆瓣未登录
        manager.startSync()
        // 等待 IO 协程完成(appScope 硬编码 Dispatchers.IO)
        waitForCondition { manager.progress.value.isComplete }
        // 进入 runSyncLegacy → getCredentials 返回 null → phase="未登录豆瓣"
        assertThat(manager.progress.value.phase).contains("豆瓣")
        assertThat(manager.progress.value.phase).doesNotContain("Trakt")
    }

    /**
     * token 为空 → checkTraktAvailable 设置 phase="未登录 Trakt,请先登录" 并返回 false。
     */
    @Test
    fun token为空时直接完成且phase提示未登录Trakt() = runBlocking {
        every { tokenStorage.getCachedAccessToken() } returns null
        coEvery { tokenStorage.isTokenValid() } returns false
        manager.startSync()
        waitForCondition { manager.progress.value.isComplete }
        assertThat(manager.progress.value.isComplete).isTrue()
        assertThat(manager.progress.value.phase).contains("Trakt")
    }

    /**
     * token 存在但失效(isTokenValid=false)→ checkTraktAvailable 设置
     * phase="未登录 Trakt,请先登录" 并返回 false。
     */
    @Test
    fun token失效时直接完成且phase提示未登录Trakt() = runBlocking {
        every { tokenStorage.getCachedAccessToken() } returns "expired-token"
        coEvery { tokenStorage.isTokenValid() } returns false
        manager.startSync()
        waitForCondition { manager.progress.value.isComplete }
        assertThat(manager.progress.value.isComplete).isTrue()
        assertThat(manager.progress.value.phase).contains("Trakt")
    }

    /**
     * token 失效 → checkTraktAvailable 返回 false → 不进入 runSyncLegacy →
     * 不调用 doubanRepository.fetchMarkList。
     */
    @Test
    fun token失效时不进入同步流程不调用fetchMarkList() = runBlocking {
        every { tokenStorage.getCachedAccessToken() } returns "expired-token"
        coEvery { tokenStorage.isTokenValid() } returns false
        manager.startSync()
        waitForCondition { manager.progress.value.isComplete }
        // fetchMarkList 有 6 个参数(2 个有默认值),用 any() 匹配必填的 4 个
        coVerify(exactly = 0) {
            doubanRepository.fetchMarkList(any(), any(), any(), any())
        }
    }

    /**
     * token 失效 → 同步作业立即结束 → isRunning() 返回 false。
     */
    @Test
    fun token失效时isRunning为false() = runBlocking {
        every { tokenStorage.getCachedAccessToken() } returns "expired-token"
        coEvery { tokenStorage.isTokenValid() } returns false
        manager.startSync()
        waitForCondition { manager.progress.value.isComplete && !manager.isRunning() }
        assertThat(manager.isRunning()).isFalse()
    }

    // ============================================================
    // persistFailures 分支(反射调用 private suspend 方法)
    // ============================================================

    /**
     * 正常同步(isRetry=false):对 WISH 和 COLLECT 两个 status 各调用一次 replaceByStatus。
     *
     * 字段验证补强:用 answers 记录每次 replaceByStatus 的 (status, entities) 参数,
     * 验证 toEntity() 字段映射正确(doubanId/title/status/failureReason)。
     * 回归场景:若 DoubanSyncFailure.toEntity() 字段错位(如 status 写成枚举名而非 path,
     * 或 failureReason 写成 ordinal 而非 name),持久化的失败项会被错误覆盖,
     * 下次重试时 status 分组失效,UI 展示与重试链路都会受影响。
     */
    @Test
    fun 全量重写删除Trakt失败时保留本地同步表和回滚信息() = runBlocking {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("uid", "cookie")
        val syncedItem = DoubanSyncedItem(
            doubanId = "full-rewrite-1",
            imdbId = "tt-full-rewrite-1",
            traktId = 1001,
            title = "全量重写条目",
            status = "wish",
            rating = null,
            syncedAt = 1L,
            mediaType = "movie"
        )
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns listOf(syncedItem)
        coEvery {
            traktRepository.batchRemoveFromWatchlist(any(), any())
        } returns Result.failure(IllegalStateException("Trakt 删除失败"))
        coEvery {
            traktRepository.batchRemoveFromWatched(any(), any())
        } returns Result.success(TraktSyncResponse())

        manager.startSync(SyncMode.FULL_REWRITE)
        waitForCondition { manager.progress.value.isComplete && !manager.isRunning() }

        assertThat(manager.progress.value.stage).isEqualTo(DoubanSyncStage.FAILED)
        coVerify(exactly = 0) { doubanSyncedItemDao.clearAll() }
        coVerify(exactly = 1) { doubanSyncRollbackDao.replaceAll(any()) }
    }

    @Test
    fun 续传条目Trakt失败时保留pending以便重试() = runBlocking {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("uid", "cookie")
        val pendingItem = DoubanSyncPendingItemEntity(
            doubanId = "resume-failed",
            title = "续传失败",
            posterUrl = null,
            rating = null,
            comment = null,
            markedAt = "2024-01-01",
            doubanUrl = "https://movie.douban.com/subject/resume-failed/",
            status = "wish",
            crawledAt = 1L
        )
        coEvery { doubanSyncedItemDao.getAllSyncedDoubanIds() } returns emptyList()
        coEvery { doubanSyncPendingItemDao.getByStatus("wish") } returns listOf(pendingItem)
        coEvery { doubanSyncPendingItemDao.getByStatus("collect") } returns emptyList()
        coEvery {
            doubanRepository.fetchDetail(any(), any(), any(), any(), any(), any())
        } returns Pair(DoubanDetailInfo(
            imdbId = "tt-resume-failed",
            isTvShow = false,
            title = "续传失败",
            posterUrl = null,
            genres = emptyList(),
            year = "2024",
            countries = emptyList(),
            directors = emptyList()
        ), false)
        every { traktRepository.getCachedTraktIdByImdb(any(), any()) } returns 1002
        coEvery {
            traktRepository.batchAddToWatchlist(any(), any())
        } returns Result.failure(IllegalStateException("Trakt 写入失败"))

        manager.startResume()
        waitForCondition { manager.progress.value.isComplete && !manager.isRunning() }

        coVerify(exactly = 0) { doubanSyncPendingItemDao.deleteByDoubanIds(any()) }
    }

    @Test
    fun 续传取消时保留pending以便重试() = runBlocking {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("uid", "cookie")
        val pendingItem = DoubanSyncPendingItemEntity(
            doubanId = "resume-cancelled",
            title = "续传取消",
            posterUrl = null,
            rating = null,
            comment = null,
            markedAt = "2024-01-01",
            doubanUrl = "https://movie.douban.com/subject/resume-cancelled/",
            status = "wish",
            crawledAt = 1L
        )
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Pair<DoubanDetailInfo?, Boolean>>()
        val cancellationUploadStarted = CompletableDeferred<Unit>()
        val releaseCancellationUpload = CompletableDeferred<Unit>()
        coEvery { doubanSyncedItemDao.getAllSyncedDoubanIds() } returns emptyList()
        coEvery { doubanSyncPendingItemDao.getByStatus("wish") } returns listOf(pendingItem)
        coEvery { doubanSyncPendingItemDao.getByStatus("collect") } returns emptyList()
        coEvery {
            doubanRepository.fetchDetail(any(), any(), any(), any(), any(), any())
        } coAnswers {
            fetchStarted.complete(Unit)
            releaseFetch.await()
        }
        coEvery {
            cloudPersonalSyncManager.uploadAll(
                lastSyncMode = "CANCELLED",
                isFullComplete = false,
                uploadIdMappings = false
            )
        } coAnswers {
            cancellationUploadStarted.complete(Unit)
            releaseCancellationUpload.await()
            true
        }

        manager.startResume()
        fetchStarted.await()
        manager.cancel()
        releaseFetch.complete(Pair(null, false))
        cancellationUploadStarted.await()
        assertThat(manager.progress.value.isComplete).isFalse()
        assertThat(manager.progress.value.stage).isEqualTo(DoubanSyncStage.CANCELLING)
        assertThat(manager.isRunning()).isTrue()
        releaseCancellationUpload.complete(Unit)
        waitForCondition { manager.progress.value.isComplete && !manager.isRunning() }

        assertThat(manager.progress.value.isComplete).isTrue()
        assertThat(manager.progress.value.stage).isEqualTo(DoubanSyncStage.CANCELLING)
        coVerify(exactly = 0) { doubanSyncPendingItemDao.deleteByDoubanIds(any()) }
    }

    @Test
    fun 自动一致性检查失败时同步结果不得被吞掉() = runBlocking {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("uid", "cookie")
        coEvery {
            doubanRepository.fetchMarkList(any(), any(), any(), any(), any(), any())
        } returns true
        coEvery {
            statusConsistencyChecker.checkAndUnify()
        } throws IllegalStateException("一致性检查失败")

        manager.startSync(SyncMode.INCREMENTAL_WITH_CHANGES)
        waitForCondition { manager.progress.value.isComplete && !manager.isRunning() }

        assertThat(manager.progress.value.stage).isEqualTo(DoubanSyncStage.FAILED)
        assertThat(manager.progress.value.errorMessage).contains("一致性检查失败")
        coVerify(exactly = 0) { cloudPersonalSyncManager.uploadAll(any(), any(), any()) }
    }

    @Test
    fun persistFailures正常同步按status覆盖调用replaceByStatus() {
        val failures = listOf(
            buildFailure(DoubanMarkStatus.WISH),
            buildFailure(DoubanMarkStatus.COLLECT)
        )
        val capturedCalls = mutableListOf<Pair<String, List<DoubanSyncFailureEntity>>>()
        coEvery {
            doubanSyncFailureDao.replaceByStatus(any(), any())
        } answers {
            capturedCalls.add(firstArg<String>() to secondArg<List<DoubanSyncFailureEntity>>())
        }

        invokePersistFailures(failures, isRetry = false)

        // 两个 status 都调用 replaceByStatus(空列表也清空)
        coVerify(exactly = 1) { doubanSyncFailureDao.replaceByStatus("wish", any()) }
        coVerify(exactly = 1) { doubanSyncFailureDao.replaceByStatus("collect", any()) }

        // 字段验证:按 status 分组,每个 status 的 entity 列表字段正确传递
        assertThat(capturedCalls).hasSize(2)
        val wishCall = capturedCalls.first { it.first == "wish" }
        val collectCall = capturedCalls.first { it.first == "collect" }

        assertThat(wishCall.second).hasSize(1)
        val wishEntity = wishCall.second[0]
        assertThat(wishEntity.doubanId).isEqualTo("id-wish")
        assertThat(wishEntity.title).isEqualTo("title-wish")
        assertThat(wishEntity.status).isEqualTo("wish") // status.path,非枚举名
        assertThat(wishEntity.failureReason).isEqualTo("DETAIL_FETCH_FAILED") // 枚举 name,非 ordinal
        assertThat(wishEntity.failedAt).isEqualTo(0L)

        assertThat(collectCall.second).hasSize(1)
        val collectEntity = collectCall.second[0]
        assertThat(collectEntity.doubanId).isEqualTo("id-collect")
        assertThat(collectEntity.title).isEqualTo("title-collect")
        assertThat(collectEntity.status).isEqualTo("collect")
        assertThat(collectEntity.failureReason).isEqualTo("DETAIL_FETCH_FAILED")
    }

    /**
     * 重试模式(isRetry=true)+ 非空列表:用 REPLACE 语义调用 insertAll,不调用 replaceByStatus。
     */
    @Test
    fun persistFailures重试模式调用insertAll不调用replaceByStatus() {
        val failures = listOf(buildFailure(DoubanMarkStatus.WISH))
        invokePersistFailures(failures, isRetry = true)
        coVerify(exactly = 1) { doubanSyncFailureDao.insertAll(any()) }
        coVerify(exactly = 0) { doubanSyncFailureDao.replaceByStatus(any(), any()) }
    }

    /**
     * 正常同步 + 空列表:仍对两个 status 调用 replaceByStatus(清空对应 status 旧失败项)。
     */
    @Test
    fun persistFailures正常同步空列表清空对应status() {
        invokePersistFailures(emptyList(), isRetry = false)
        // 空列表也调用 replaceByStatus 清空(避免上次失败项残留)
        coVerify(exactly = 1) { doubanSyncFailureDao.replaceByStatus("wish", any()) }
        coVerify(exactly = 1) { doubanSyncFailureDao.replaceByStatus("collect", any()) }
    }

    /**
     * 重试模式 + 空列表:failures.isNotEmpty()=false → 不调用任何 DAO 方法。
     */
    @Test
    fun persistFailures重试模式空列表不调用DAO() {
        invokePersistFailures(emptyList(), isRetry = true)
        coVerify(exactly = 0) { doubanSyncFailureDao.insertAll(any()) }
        coVerify(exactly = 0) { doubanSyncFailureDao.replaceByStatus(any(), any()) }
    }

    // ============================================================
    // WakeLock 生命周期
    // ============================================================

    /**
     * startSync 同步调用 acquireWakeLock(在 appScope.launch 之前)。
     * 让 isTokenValid 阻塞,确保同步流程停留在 checkTraktAvailable,wakelock 保持持有。
     */
    @Test
    fun 同步启动时acquireWakeLock() {
        // 用永不完成的 gate 阻塞 isTokenValid,使同步协程停留在 checkTraktAvailable
        val gate = CompletableDeferred<Boolean>()
        coEvery { tokenStorage.isTokenValid() } coAnswers { gate.await() }
        try {
            val started = manager.startSync()
            assertThat(started).isTrue()
            // 等待 IO 协程进入 checkTraktAvailable(并阻塞在 isTokenValid)
            Thread.sleep(300)
            val wl = readWakeLockField()
            assertThat(wl).isNotNull()
            assertThat(wl!!.isHeld).isTrue()
        } finally {
            // 释放 gate 让阻塞协程继续完成(避免泄漏),不参与本测试断言
            gate.complete(true)
        }
    }

    /**
     * token 失效 → checkTraktAvailable 返回 false → return@launch →
     * finally releaseWakeLock → wakeLock 字段置 null。
     *
     * 注意: 不能仅轮询 isComplete —— isComplete 由 checkTraktAvailable 设置,
     * 而 releaseWakeLock 在其后 finally 块执行,二者之间存在竞态。
     * 改为轮询 !isRunning()(syncJob 完成意味着 finally 块已执行)。
     */
    @Test
    fun 同步完成后releaseWakeLock() = runBlocking {
        every { tokenStorage.getCachedAccessToken() } returns null
        coEvery { tokenStorage.isTokenValid() } returns false
        manager.startSync()
        // 等待 syncJob 完全结束(包括 finally 块的 releaseWakeLock)
        // isComplete 设置在 finally 之前,直接轮询 isComplete 会有竞态
        waitForCondition { manager.progress.value.isComplete && !manager.isRunning() }
        // finally 块已执行 releaseWakeLock,wakeLock 字段为 null
        assertThat(readWakeLockField()).isNull()
    }

    @Test
    fun 新同步开始和结束时恢复详情池下载() = runBlocking {
        manager.startSync()
        waitForCondition { manager.progress.value.isComplete && !manager.isRunning() }

        verify(atLeast = 1) {
            cloudDetailsPoolManager.resetDownloadSuppression()
        }
    }

    // ============================================================
    // 辅助函数
    // ============================================================

    /** 构造测试用 DoubanSyncFailure */
    private fun buildFailure(status: DoubanMarkStatus): DoubanSyncFailure = DoubanSyncFailure(
        doubanId = "id-${status.path}",
        title = "title-${status.path}",
        posterUrl = null,
        rating = null,
        comment = null,
        markedAt = "2024-01-01",
        doubanUrl = "https://movie.douban.com/subject/id-${status.path}",
        status = status,
        failureReason = FailureReason.DETAIL_FETCH_FAILED,
        failedAt = 0L
    )

    /**
     * 反射调用 private suspend fun persistFailures(failures, isRetry, preservedFailures)。
     *
     * suspend 方法 JVM 签名多一个 Continuation 参数,返回值为
     * COROUTINE_SUSPENDED(挂起)或实际结果(同步完成)。
     * 用 CountDownLatch 兼容两种完成路径:挂起时由 continuation.resumeWith 唤醒,
     * 同步完成时由主线程直接 countDown。
     */
    private fun invokePersistFailures(
        failures: List<DoubanSyncFailure>,
        isRetry: Boolean,
        preservedFailures: List<DoubanSyncFailure> = emptyList()
    ) {
        val method = DoubanSyncManager::class.java.getDeclaredMethod(
            "persistFailures",
            List::class.java,
            Boolean::class.javaPrimitiveType,
            List::class.java,
            Continuation::class.java
        )
        method.isAccessible = true

        val latch = CountDownLatch(1)
        var error: Throwable? = null
        val continuation = object : Continuation<Unit> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                result.onFailure { error = it }
                latch.countDown()
            }
        }

        val result = method.invoke(manager, failures, isRetry, preservedFailures, continuation)
        // 同步完成时 result != COROUTINE_SUSPENDED,continuation 不会被调用,主动 countDown
        if (result != COROUTINE_SUSPENDED) {
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw AssertionError("persistFailures 在 5 秒内未完成")
        }
        error?.let { throw it }
    }

    /** 反射读取 private var wakeLock 字段 */
    private fun readWakeLockField(): PowerManager.WakeLock? {
        val field = DoubanSyncManager::class.java.getDeclaredField("wakeLock")
        field.isAccessible = true
        return field.get(manager) as PowerManager.WakeLock?
    }

    /**
     * 轮询等待条件满足,默认超时 3 秒。
     *
     * DoubanSyncManager 的 appScope 使用 Dispatchers.IO(真实线程),
     * 无法用 runTest 的 advanceUntilIdle 控制,需用 Thread.sleep 轮询。
     */
    @Test
    fun doubanModeSync_skipsTraktConsistencyCheck_butUploadsCloudData() = runBlocking {
        every { sessionModeManager.sessionMode } returns
            kotlinx.coroutines.flow.flowOf(com.tracktosearch.data.session.SessionMode.DOUBAN)
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("uid", "cookie")
        coEvery {
            doubanRepository.fetchMarkList(any(), any(), any(), any(), any(), any())
        } returns true
        coEvery {
            statusConsistencyChecker.checkAndUnify()
        } returns ConsistencyCheckResult(isComplete = true, errors = 1)
        val uploadStages = mutableListOf<DoubanSyncSubStage>()
        coEvery {
            cloudPersonalSyncManager.uploadAll(any(), any(), any())
        } coAnswers {
            uploadStages += manager.progress.value.subStage
            assertThat(manager.progress.value.stage).isNotEqualTo(DoubanSyncStage.COMPLETED)
            true
        }

        manager.startSync(SyncMode.INCREMENTAL_WITH_CHANGES)
        waitForCondition { manager.progress.value.isComplete && !manager.isRunning() }

        assertThat(manager.progress.value.stage).isEqualTo(DoubanSyncStage.COMPLETED)
        coVerify(exactly = 0) { statusConsistencyChecker.checkAndUnify() }
        assertThat(uploadStages).containsExactly(DoubanSyncSubStage.UPLOADING_PERSONAL_DATA)
        coVerify(exactly = 1) {
            cloudPersonalSyncManager.uploadAll(
                lastSyncMode = "INCREMENTAL_WITH_CHANGES",
                isFullComplete = true,
                uploadIdMappings = true
            )
        }
    }

    private fun waitForCondition(
        timeoutMs: Long = 10000L,
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
