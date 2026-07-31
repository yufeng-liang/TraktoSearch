package com.tracktosearch.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.local.db.DoubanSyncPendingItemDao
import com.tracktosearch.data.local.db.DoubanSyncRollbackDao
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DelayInfo
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.util.PersistentTtlCache
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * DoubanSyncManager 取消链路单元测试。
 *
 * 覆盖 cancel() 的完整链路:
 * - 同步状态更新(isCancelling/phase/subPhase/delayInfo)
 * - 异步上传进度(uploadAll("CANCELLED", ...))
 * - 取消后重置并重新启动同步
 * - 取消不影响已完成状态和 cookieExpired
 *
 * 注意: DoubanSyncManager 的 appScope 硬编码使用 Dispatchers.IO(真实线程),
 * 不受测试调度器控制。涉及 startSync 异步完成的测试使用 runBlocking + 轮询等待。
 *
 * 构造参数顺序(共15个,与 DoubanSyncManager 构造函数完全一致):
 * doubanRepository, doubanAuthStorage, traktRepository, doubanSyncedItemDao,
 * doubanSyncFailureDao, doubanSyncPendingItemDao, doubanSyncRollbackDao,
 * cloudFailureSyncManager, cloudPersonalSyncManager, cloudDetailsPoolManager,
 * doubanSyncMetaStorage, doubanDetailCache, tokenStorage, statusConsistencyChecker, appContext
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DoubanSyncManagerCancelTest {

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
            appContext
        )
    }

    // ============================================================
    // 同步测试:cancel() 的状态更新在调用线程同步完成,无需等待 IO 协程
    // ============================================================

    @Test
    fun cancel后isCancelling返回true() {
        manager.cancel()
        assertThat(manager.isCancelling()).isTrue()
    }

    @Test
    fun cancel后progress的isCancelling为true() {
        manager.cancel()
        assertThat(manager.progress.value.isCancelling).isTrue()
    }

    @Test
    fun cancel后progress的phase包含正在取消() {
        manager.cancel()
        assertThat(manager.progress.value.phase).contains("正在取消")
    }

    @Test
    fun cancel后progress的delayInfo清空() {
        manager.cancel()
        assertThat(manager.progress.value.delayInfo).isNull()
    }

    @Test
    fun cancel后progress的subPhase清空() {
        manager.cancel()
        assertThat(manager.progress.value.subPhase).isEmpty()
    }

    @Test
    fun 正在取消时progress的isCancelling为true() {
        manager.cancel()
        assertThat(manager.progress.value.isCancelling).isTrue()
    }

    // ============================================================
    // 异步测试:需等待 IO 协程完成
    // (appScope 硬编码 Dispatchers.IO,不受 MainDispatcherRule/advanceUntilIdle 控制)
    // ============================================================

    /**
     * cancel 后 syncJob 最终完成 isComplete 为 true。
     *
     * 流程: startSync() → IO 协程 → checkTraktAvailable(已登录) → runSyncLegacy
     * → getCredentials 返回 null → 立即设置 isComplete=true 并返回。
     * 然后调用 cancel(),isComplete 保持 true(取消不改变已完成状态)。
     */
    @Test
    fun cancel后syncJob最终完成isComplete为true() = runBlocking {
        // 未登录豆瓣 → runSyncLegacy 立即设置 isComplete=true 并返回
        manager.startSync()
        // 等待 IO 协程完成(appScope 使用 Dispatchers.IO,不受测试调度器控制)
        waitForCondition { manager.progress.value.isComplete }
        manager.cancel()
        // cancel 后 isComplete 保持 true(取消不改变已完成状态)
        assertThat(manager.progress.value.isComplete).isTrue()
    }

    /**
     * cancel 后 uploadAll 以 "CANCELLED" 模式被调用。
     *
     * 流程: cancel() → appScope.launch { syncJob?.join(); uploadAll("CANCELLED", false); ... }
     * syncJob 为 null(未调用 startSync),join() 立即完成,随后 uploadAll 被调用。
     *
     * 字段验证补强:精确匹配 isFullComplete=false(取消是部分完成,不是完整同步完成)。
     * 回归场景:若 cancel 后误传 isFullComplete=true,会更新 lastFullSyncAt 时间戳,
     * 导致 UI 误显示"上次完整同步时间"为取消时间,且会触发不必要的 id_mappings 上传。
     */
    @Test
    fun cancel后uploadAllCancelled被调用() = runBlocking {
        // 不需要 startSync,syncJob 为 null,syncJob?.join() 立即完成
        // cancel() 在 IO 线程异步调用 uploadAll("CANCELLED", false)
        manager.cancel()
        // 等待 IO 协程执行 uploadAll(syncJob 为 null,join 立即完成,uploadAll 紧随其后)
        Thread.sleep(1000)
        // 精确匹配 isFullComplete=false(取消不是完整同步完成)
        coVerify(atLeast = 1) {
            cloudPersonalSyncManager.uploadAll("CANCELLED", false, any())
        }
        // 反向验证:从未以 isFullComplete=true 调用
        coVerify(exactly = 0) {
            cloudPersonalSyncManager.uploadAll("CANCELLED", true, any())
        }
    }

    /**
     * 取消后再 startSync 可正常启动。
     *
     * 流程:
     * 1. 第一次 startSync() → 完成(isComplete=true,syncJob 已完成)
     * 2. cancel() → 设置 isCancelling=true
     * 3. resetProgress() → syncJob 已完成,isRunning=false,允许重置
     * 4. 第二次 startSync() → 检查 isRunning()=false,允许启动新同步
     */
    @Test
    fun 取消后再startSync可正常启动() = runBlocking {
        // 第一次同步(未登录豆瓣,立即完成)
        manager.startSync()
        waitForCondition { manager.progress.value.isComplete }
        assertThat(manager.progress.value.isComplete).isTrue()
        // 取消
        manager.cancel()
        assertThat(manager.isCancelling()).isTrue()
        // 重置进度(同步已完成,isRunning=false,resetProgress 生效)
        manager.resetProgress()
        assertThat(manager.progress.value.isComplete).isFalse()
        // 再次启动同步(startSync 内部会重置 cancelled=false 和 dirtyDetailIds)
        manager.startSync()
        waitForCondition { manager.progress.value.isComplete }
        assertThat(manager.progress.value.isComplete).isTrue()
    }

    /**
     * cancel 不改变 cookieExpired 状态。
     *
     * cancel() 只修改 isCancelling/phase/subPhase/delayInfo,不触碰 cookieExpired。
     */
    @Test
    fun cancel不改变cookieExpired状态() = runBlocking {
        // 未登录豆瓣 → runSyncLegacy 立即设置 isComplete=true
        manager.startSync()
        waitForCondition { manager.progress.value.isComplete }
        val cookieExpiredBefore = manager.progress.value.cookieExpired
        manager.cancel()
        // cancel() 不修改 cookieExpired(只改 isCancelling/phase/subPhase/delayInfo)
        assertThat(manager.progress.value.cookieExpired).isEqualTo(cookieExpiredBefore)
    }

    // ============================================================
    // 辅助函数
    // ============================================================

    /**
     * 轮询等待条件满足,默认超时 3 秒。
     *
     * DoubanSyncManager 的 appScope 使用 Dispatchers.IO(真实线程),
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
