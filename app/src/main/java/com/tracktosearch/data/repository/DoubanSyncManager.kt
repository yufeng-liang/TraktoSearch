package com.tracktosearch.data.repository

import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.local.db.DoubanSyncFailureEntity
import com.tracktosearch.data.local.db.DoubanSyncPendingItemDao
import com.tracktosearch.data.local.db.DoubanSyncPendingItemEntity
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanDetailInfo
import com.tracktosearch.data.remote.douban.DoubanMarkItem
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.util.PersistentTtlCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 豆瓣→Trakt 同步进度
 *
 * @param failedItems 完整失败项列表(含豆瓣条目全部信息),用于 UI 展示与持久化
 * @param currentTitle 当前正在处理的条目标题(进度详情展示用)
 * @param recentFailures 最近 5 条失败(实时滚动展示用,避免列表过长导致重组开销)
 */
data class DoubanSyncProgress(
    val isRunning: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val phase: String = "",        // "爬取想看列表" / "同步想看到 Trakt" 等主阶段
    val subPhase: String = "",     // 子阶段："详情页" / "Trakt 查询" / "批量同步"（同步阶段细分）
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val skippedCount: Int = 0,      // 断点续传跳过的条目数
    val cacheHitCount: Int = 0,     // 详情页缓存命中数（省去爬取）
    val failedItems: List<DoubanSyncFailure> = emptyList(), // 完整失败项
    val isComplete: Boolean = false,
    val startTimeMs: Long = 0,      // 同步开始时间戳（用于计算剩余时间）
    val etaSeconds: Long = -1,      // 预计剩余秒数（-1=未知）
    val cookieExpired: Boolean = false,  // 豆瓣 Cookie 过期（需引导用户重新登录）
    val currentTitle: String? = null,    // 当前正在处理的条目标题
    val recentFailures: List<DoubanSyncFailure> = emptyList(), // 最近 5 条失败(滚动展示)
    val isRetry: Boolean = false    // true=重试模式(从失败项数据走,不爬列表)
)

/**
 * 豆瓣→Trakt 同步协调器。
 *
 * 流程：爬取想看/看过列表 → 并发爬详情页拿 imdbId → 反查 Trakt → 批量推送 Trakt。
 *
 * 优化点：
 * - Application scope 跑同步，不依赖调用者（ViewModel/Service 销毁不影响）
 * - 防重入：isRunning 时拒绝重复启动
 * - 详情页并发爬取（并发度 3，兼顾速度与反爬）
 * - Trakt 批量同步（一次 POST 传多个 ids，省去 N 次请求）
 * - 失败项持久化到 douban_sync_failures 表,支持下次重试
 *
 * 冲突处理策略（豆瓣优先覆盖）：
 * - 豆瓣「看过」+ Trakt「想看」→ 覆盖（移除想看，标记已看）
 * - 豆瓣「想看」+ Trakt「看过」→ 不覆盖（保留 Trakt 已看）
 *
 * 评分同步：豆瓣评分（1-5）同步到 Trakt ratings（×2 转换为 1-10）。
 *
 * 断点续传：已成功同步的条目记录在 douban_synced_items 表，forceOverwrite=false 时跳过。
 */
@Singleton
class DoubanSyncManager @Inject constructor(
    private val doubanRepository: DoubanRepository,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val traktRepository: TraktRepository,
    private val doubanSyncedItemDao: DoubanSyncedItemDao,
    private val doubanSyncFailureDao: DoubanSyncFailureDao,
    private val doubanSyncPendingItemDao: DoubanSyncPendingItemDao,
    private val cloudFailureSyncManager: CloudFailureSyncManager,
    private val cloudPersonalSyncManager: CloudPersonalSyncManager,
    private val cloudDetailsPoolManager: CloudDetailsPoolManager,
    private val doubanSyncMetaStorage: DoubanSyncMetaStorage,
    private val doubanDetailCache: PersistentTtlCache<DoubanDetailCacheEntry>,
    private val tokenStorage: TokenStorage
) {
    private val _progress = MutableStateFlow(DoubanSyncProgress())
    val progress: StateFlow<DoubanSyncProgress> = _progress.asStateFlow()

    /** Application scope：同步协程在此运行，Activity/Service 销毁不影响 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var syncJob: Job? = null

    @Volatile
    private var cancelled = false

    /**
     * 同步过程中新爬取的豆瓣详情 doubanId 集合（非缓存命中的）。
     * 同步完成时一次性批量上传到全局详情池，避免每批次上传导致的多次网络请求。
     * 线程安全：syncBatchToTrakt 的详情协程并发写入。
     */
    private val dirtyDetailIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 取消正在进行的同步。
     *
     * 取消后异步上传当前进度到云端（pending items + synced items + sync_meta + dirty 详情池），
     * 这样 B 手机登录同账号选择增量同步时可接续进度，避免全量爬取豆瓣。
     * 上传失败不阻塞取消流程。
     */
    fun cancel() {
        cancelled = true
        // 异步上传当前进度（不阻塞取消，失败只记日志）
        appScope.launch {
            runCatching {
                cloudPersonalSyncManager.uploadAll(
                    lastSyncMode = "CANCELLED",
                    isFullComplete = false
                )
                cloudFailureSyncManager.uploadIfHasFailures()
                uploadDirtyDetails()
            }
        }
    }

    /** 同步是否在运行中 */
    fun isRunning(): Boolean = syncJob?.isActive == true

    /** 重置进度状态（Cookie 过期重新登录时调用，让 UI 不再显示旧进度） */
    fun resetProgress() {
        if (isRunning()) return  // 运行中不重置
        _progress.value = DoubanSyncProgress()
    }

    /**
     * Trakt 登录态预检(同步层兜底)。
     * - 已登录且 token 有效 → true,继续同步
     * - 未登录或 token 过期 → 设置 phase="未登录 Trakt,请先登录",返回 false
     *
     * 这是 UI 层前置引导的兜底:即使用户绕过 LoginScreen 直接调用同步
     * (如 DoubanLoginScreen 自动触发、设置页重试),也会被拦截。
     */
    private suspend fun checkTraktAvailable(): Boolean {
        val token = tokenStorage.getCachedAccessToken()
        if (token == null || !tokenStorage.isTokenValid()) {
            _progress.value = DoubanSyncProgress(
                isComplete = true,
                isRunning = false,
                phase = "未登录 Trakt,请先登录"
            )
            return false
        }
        return true
    }

    /**
     * 启动同步（非 suspend，立即返回，进度通过 progress StateFlow 暴露）。
     * @param forceOverwrite true=强制重新同步已同步过的条目；false=跳过已同步条目（断点续传）
     * @return true=已启动；false=已有同步在运行（防重入）
     */
    fun startSync(forceOverwrite: Boolean = false): Boolean {
        if (isRunning()) return false
        cancelled = false
        dirtyDetailIds.clear()
        syncJob = appScope.launch {
            if (!checkTraktAvailable()) return@launch
            runSyncLegacy(forceOverwrite)
        }
        return true
    }

    /**
     * 启动同步(指定模式,非 suspend,立即返回)。
     * - [SyncMode.INCREMENTAL_ONLY]: 等价于 forceOverwrite=false
     * - [SyncMode.INCREMENTAL_WITH_CHANGES]: 跳过已同步且状态一致的,处理状态变化的
     * - [SyncMode.FULL_REWRITE]: 先清空已同步标记,再 forceOverwrite=true
     */
    fun startSync(mode: SyncMode): Boolean {
        if (isRunning()) return false
        cancelled = false
        dirtyDetailIds.clear()
        syncJob = appScope.launch {
            if (!checkTraktAvailable()) return@launch
            runSync(mode)
        }
        return true
    }

    /**
     * 启动续传同步(非 suspend,立即返回)。
     *
     * 续传流程:
     * 1. 从 douban_sync_pending_items 表加载上次未处理完的列表数据
     * 2. 跳过列表爬取(已有 doubanUrl 等信息)
     * 3. 直接走 syncBatchToTrakt:详情页 → TraktId → Trakt 写入
     * 4. 处理完一批后从 pending items 表删除已处理的 doubanId
     * 5. 处理完所有条目后 pending items 表自然清空
     *
     * @return true=已启动;false=已有同步在运行(防重入)或无 pending items
     */
    fun startResume(): Boolean {
        if (isRunning()) return false
        cancelled = false
        dirtyDetailIds.clear()
        syncJob = appScope.launch {
            if (!checkTraktAvailable()) return@launch
            runResume()
        }
        return true
    }

    /**
     * 清空 pending items 表(用户选择「完整同步」时调用)。
     */
    suspend fun clearPendingItems() {
        doubanSyncPendingItemDao.clearAll()
    }

    /**
     * 查询 pending items 数量(用于 App 启动时检测是否需要弹续传对话框)。
     */
    suspend fun getPendingItemsCount(): Int {
        return doubanSyncPendingItemDao.count()
    }

    /**
     * 启动失败项重试(非 suspend,立即返回)。
     *
     * 重试流程:
     * 1. 从 [failures] 加载失败项数据(已包含 doubanUrl 等信息,无需重新爬列表)
     * 2. 按 [selectedReasons] 过滤(默认重试可恢复类型)
     * 3. 走 syncBatchToTrakt 子流程:详情页 → TraktId 查询 → Trakt 写入
     * 4. 重试成功 → 从 douban_sync_failures 表删除
     * 5. 仍然失败 → attemptCount+1,更新 failureReason
     *
     * @param failures 失败项列表(从本地 Room 或导入 JSON 加载)
     * @param selectedReasons 要重试的失败类型集合
     * @return true=已启动；false=已有同步在运行（防重入）
     */
    fun startRetry(
        failures: List<DoubanSyncFailure>,
        selectedReasons: Set<FailureReason>
    ): Boolean {
        if (isRunning()) return false
        cancelled = false
        dirtyDetailIds.clear()
        syncJob = appScope.launch {
            if (!checkTraktAvailable()) return@launch
            runRetry(failures, selectedReasons)
        }
        return true
    }

    private suspend fun runSync(mode: SyncMode) {
        when (mode) {
            SyncMode.INCREMENTAL_ONLY -> runSyncIncremental(includeStatusChanges = false)
            SyncMode.INCREMENTAL_WITH_CHANGES -> runSyncIncremental(includeStatusChanges = true)
            SyncMode.FULL_REWRITE -> runSyncFullRewrite()
        }
    }

    private suspend fun runSyncLegacy(forceOverwrite: Boolean) {
        val creds = doubanAuthStorage.getCredentials()
            ?: run {
                _progress.value = DoubanSyncProgress(isComplete = true, phase = "未登录豆瓣")
                return
            }

        val startTime = System.currentTimeMillis()
        _progress.value = _progress.value.copy(isRunning = true, startTimeMs = startTime, phase = "准备同步", isRetry = false)

        // 跨设备云端同步：拉取 A 手机已同步的数据和进度（B 手机首次登录场景）
        // forceOverwrite=true（完整重写）时跳过拉取，因为要重新处理全部条目
        if (!forceOverwrite) {
            _progress.value = _progress.value.copy(phase = "拉取云端同步数据")
            pullFromCloudBeforeSync()
        }

        // 加载 Trakt 已有标记缓存（用于冲突检测）
        traktRepository.loadWatchlistWatchedIds()
        val watchlistWatchedIds = traktRepository.getWatchlistWatchedIds()

        // 加载已同步记录（断点续传：跳过已同步条目）
        // 拉取云端后本地 synced_items 已包含 A 手机数据，B 手机可跳过已同步条目
        val syncedIds = if (!forceOverwrite) {
            doubanSyncedItemDao.getAllSyncedDoubanIds().toSet()
        } else {
            emptySet()
        }

        // 「近期跳过列表」策略：forceOverwrite=false 且云端最近完整同步 < 7 天且无 pending
        // → 跳过豆瓣列表爬取（数据已由云端拉取，避免对豆瓣的请求）
        if (!forceOverwrite && checkSkipListCrawl()) {
            _progress.value = DoubanSyncProgress(
                isRunning = false,
                isComplete = true,
                phase = "已跳过列表爬取（数据来自云端，7天内已同步）",
                startTimeMs = startTime,
                skippedCount = syncedIds.size,
                isRetry = false
            )
            return
        }

        val allFailed = mutableListOf<DoubanSyncFailure>()
        val recentFailuresBuffer = ArrayDeque<DoubanSyncFailure>()
        var totalSuccess = 0
        var totalSkipped = 0
        var totalCacheHit = 0

        // 先爬「想看」再爬「看过」
        for (status in listOf(DoubanMarkStatus.WISH, DoubanMarkStatus.COLLECT)) {
            if (cancelled) break
            val phaseName = if (status == DoubanMarkStatus.WISH) "爬取想看列表" else "爬取看过列表"
            _progress.value = _progress.value.copy(
                isRunning = true, phase = phaseName, total = 0, current = 0, subPhase = "",
                currentTitle = null
            )

            val pageItems = mutableListOf<DoubanMarkItem>()
            val ok = doubanRepository.fetchMarkList(
                userId = creds.userId,
                cookie = creds.cookie,
                status = status,
                onPage = { items, _ ->
                    pageItems.addAll(items)
                    // 持久化已爬到的列表数据,取消后可续传
                    val now = System.currentTimeMillis()
                    val pendingEntities = items.map { item ->
                        DoubanSyncPendingItemEntity(
                            doubanId = item.doubanId,
                            title = item.title,
                            posterUrl = item.posterUrl,
                            rating = item.rating,
                            comment = item.comment,
                            markedAt = item.markedAt,
                            doubanUrl = item.doubanUrl,
                            status = status.path,
                            crawledAt = now
                        )
                    }
                    doubanSyncPendingItemDao.insertAll(pendingEntities)
                },
                onProgress = { cur, total ->
                    _progress.value = _progress.value.copy(
                        current = cur,
                        // 列表爬取阶段：total 用豆瓣返回的总条目数；解析失败时回退到 current（不定进度）
                        total = total ?: cur
                    )
                },
                isCancelled = { cancelled }
            )

            if (!ok) {
                _progress.value = _progress.value.copy(
                    phase = "豆瓣登录已过期", isComplete = true, isRunning = false,
                    cookieExpired = true
                )
                return
            }

            if (cancelled) break

            // 同步到 Trakt（批量）
            val syncPhase = if (status == DoubanMarkStatus.WISH) "同步想看到 Trakt" else "同步看过到 Trakt"
            _progress.value = _progress.value.copy(phase = syncPhase, total = pageItems.size, current = 0)

            val result = syncBatchToTrakt(
                items = pageItems,
                status = status,
                cookie = creds.cookie,
                syncedIds = syncedIds,
                watchlistWatchedIds = watchlistWatchedIds,
                onProgress = { cur, subPhase, cacheHit, currentTitle, recentFailure ->
                    // 计算预计剩余时间
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    val eta = if (cur > 0 && _progress.value.total > 0) {
                        (elapsed * (_progress.value.total - cur) / cur).coerceAtLeast(0)
                    } else -1L
                    // 更新最近失败滚动列表
                    if (recentFailure != null) {
                        recentFailuresBuffer.addLast(recentFailure)
                        while (recentFailuresBuffer.size > 5) recentFailuresBuffer.removeFirst()
                    }
                    _progress.value = _progress.value.copy(
                        current = cur,
                        subPhase = subPhase,
                        cacheHitCount = _progress.value.cacheHitCount + cacheHit,
                        etaSeconds = eta,
                        currentTitle = currentTitle,
                        recentFailures = recentFailuresBuffer.toList()
                    )
                }
            )
            allFailed.addAll(result.failed)
            totalSuccess += result.success
            totalSkipped += result.skipped
            totalCacheHit += result.cacheHit
            // 处理完一批后,从 pending items 表删除已处理的 doubanId(无论成功还是失败)
            // 这样取消后 pending items 表只保留未处理的条目,下次续传时直接处理这些
            if (pageItems.isNotEmpty()) {
                doubanSyncPendingItemDao.deleteByDoubanIds(pageItems.map { it.doubanId })
            }
        }

        // 同步完成后:持久化失败项到 douban_sync_failures 表(按 status 精细化覆盖)
        persistFailures(allFailed)

        val finalProgress = DoubanSyncProgress(
            isRunning = false,
            isComplete = true,
            current = _progress.value.current,
            total = _progress.value.total,
            successCount = totalSuccess,
            failedCount = allFailed.size,
            skippedCount = totalSkipped,
            cacheHitCount = totalCacheHit,
            failedItems = allFailed,
            phase = if (cancelled) "已取消" else "同步完成",
            startTimeMs = startTime,
            etaSeconds = 0,
            recentFailures = recentFailuresBuffer.toList(),
            isRetry = false
        )
        _progress.value = finalProgress
        // 同步完成后:上传个人数据到云端（跨设备同步，失败不阻塞）
        uploadToCloudAfterSync(mode = "LEGACY", isFullComplete = !cancelled)
    }

    private suspend fun runSyncIncremental(includeStatusChanges: Boolean) {
        val creds = doubanAuthStorage.getCredentials()
            ?: run {
                _progress.value = DoubanSyncProgress(isComplete = true, phase = "未登录豆瓣")
                return
            }

        val startTime = System.currentTimeMillis()
        val modeLabel = if (includeStatusChanges) "增量+状态变化同步" else "增量同步"
        _progress.value = DoubanSyncProgress(
            isRunning = true, startTimeMs = startTime, phase = "准备$modeLabel",
            isRetry = false
        )

        // 跨设备云端同步：拉取 A 手机已同步的数据和进度（B 手机增量同步场景）
        _progress.value = _progress.value.copy(phase = "拉取云端同步数据")
        pullFromCloudBeforeSync()

        traktRepository.loadWatchlistWatchedIds()
        val watchlistWatchedIds = traktRepository.getWatchlistWatchedIds()

        // 加载已同步记录（模式 A 和模式 B 都需要）：
        // - 模式 A：跳过已同步条目（与 forceOverwrite=false 语义一致，避免重复 POST Trakt）
        // - 模式 B：额外用于状态变化对比（WISH↔COLLECT）
        // 拉取云端后本地 synced_items 已包含 A 手机数据，B 手机可跳过已同步条目
        val syncedItemsList = doubanSyncedItemDao.getAllSyncedItems()
        val syncedItemsMap = syncedItemsList.associateBy { it.doubanId }
        val syncedIds = syncedItemsMap.keys

        // 「近期跳过列表」策略：模式 B 需要爬列表检测状态变化，不跳过；
        // 仅模式 A 且云端最近完整同步 < 7 天且无 pending → 跳过列表爬取
        if (!includeStatusChanges && checkSkipListCrawl()) {
            _progress.value = DoubanSyncProgress(
                isRunning = false,
                isComplete = true,
                phase = "已跳过列表爬取（数据来自云端，7天内已同步）",
                startTimeMs = startTime,
                skippedCount = syncedIds.size,
                isRetry = false
            )
            return
        }

        val allFailed = mutableListOf<DoubanSyncFailure>()
        val recentFailuresBuffer = ArrayDeque<DoubanSyncFailure>()
        var totalSuccess = 0
        var totalSkipped = 0
        var totalCacheHit = 0
        var totalStatusChanged = 0

        for (status in listOf(DoubanMarkStatus.WISH, DoubanMarkStatus.COLLECT)) {
            if (cancelled) break
            val phaseName = if (status == DoubanMarkStatus.WISH) "爬取想看列表" else "爬取看过列表"
            _progress.value = _progress.value.copy(
                isRunning = true, phase = phaseName, total = 0, current = 0, subPhase = "",
                currentTitle = null
            )

            val pageItems = mutableListOf<DoubanMarkItem>()
            val ok = doubanRepository.fetchMarkList(
                userId = creds.userId,
                cookie = creds.cookie,
                status = status,
                onPage = { items, _ ->
                    pageItems.addAll(items)
                    val now = System.currentTimeMillis()
                    val pendingEntities = items.map { item ->
                        DoubanSyncPendingItemEntity(
                            doubanId = item.doubanId,
                            title = item.title,
                            posterUrl = item.posterUrl,
                            rating = item.rating,
                            comment = item.comment,
                            markedAt = item.markedAt,
                            doubanUrl = item.doubanUrl,
                            status = status.path,
                            crawledAt = now
                        )
                    }
                    doubanSyncPendingItemDao.insertAll(pendingEntities)
                },
                onProgress = { cur, total ->
                    _progress.value = _progress.value.copy(
                        current = cur,
                        total = total ?: cur
                    )
                },
                isCancelled = { cancelled }
            )

            if (!ok) {
                _progress.value = _progress.value.copy(
                    phase = "豆瓣登录已过期", isComplete = true, isRunning = false,
                    cookieExpired = true
                )
                return
            }

            if (cancelled) break

            // 模式 B:先处理状态变化项
            if (includeStatusChanges) {
                val changedItems = pageItems.filter { item ->
                    val synced = syncedItemsMap[item.doubanId]
                    synced != null && synced.status != status.path
                }
                if (changedItems.isNotEmpty()) {
                    totalStatusChanged += processStatusChanges(
                        changedItems = changedItems,
                        newStatus = status,
                        syncedItemsMap = syncedItemsMap,
                        watchlistWatchedIds = watchlistWatchedIds
                    )
                }
            }

            // 同步到 Trakt(批量,跳过已同步的)
            val syncPhase = if (status == DoubanMarkStatus.WISH) "同步想看到 Trakt" else "同步看过到 Trakt"
            _progress.value = _progress.value.copy(phase = syncPhase, total = pageItems.size, current = 0)

            val result = syncBatchToTrakt(
                items = pageItems,
                status = status,
                cookie = creds.cookie,
                syncedIds = syncedIds,
                watchlistWatchedIds = watchlistWatchedIds,
                onProgress = { cur, subPhase, cacheHit, currentTitle, recentFailure ->
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    val eta = if (cur > 0 && _progress.value.total > 0) {
                        (elapsed * (_progress.value.total - cur) / cur).coerceAtLeast(0)
                    } else -1L
                    if (recentFailure != null) {
                        recentFailuresBuffer.addLast(recentFailure)
                        while (recentFailuresBuffer.size > 5) recentFailuresBuffer.removeFirst()
                    }
                    _progress.value = _progress.value.copy(
                        current = cur,
                        subPhase = subPhase,
                        cacheHitCount = _progress.value.cacheHitCount + cacheHit,
                        etaSeconds = eta,
                        currentTitle = currentTitle,
                        recentFailures = recentFailuresBuffer.toList()
                    )
                }
            )
            allFailed.addAll(result.failed)
            totalSuccess += result.success
            totalSkipped += result.skipped
            totalCacheHit += result.cacheHit
            if (pageItems.isNotEmpty()) {
                doubanSyncPendingItemDao.deleteByDoubanIds(pageItems.map { it.doubanId })
            }
        }

        persistFailures(allFailed)

        val finalProgress = DoubanSyncProgress(
            isRunning = false,
            isComplete = true,
            current = _progress.value.current,
            total = _progress.value.total,
            successCount = totalSuccess,
            failedCount = allFailed.size,
            skippedCount = totalSkipped,
            cacheHitCount = totalCacheHit,
            failedItems = allFailed,
            phase = if (cancelled) "已取消" else "$modeLabel 完成",
            startTimeMs = startTime,
            etaSeconds = 0,
            recentFailures = recentFailuresBuffer.toList(),
            isRetry = false
        )
        _progress.value = finalProgress
        // 同步完成后:上传个人数据到云端（跨设备同步，失败不阻塞）
        val mode = if (includeStatusChanges) "INCREMENTAL_WITH_CHANGES" else "INCREMENTAL_ONLY"
        uploadToCloudAfterSync(mode = mode, isFullComplete = !cancelled)
    }

    /**
     * 处理状态变化项(模式 B)。
     *
     * - WISH→COLLECT: removeFromWatchlist + markAsWatched
     * - COLLECT→WISH: removeFromWatched + addToWatchlist
     *
     * 更新 douban_synced_items.status 字段。
     *
     * @return 成功处理的状态变化数
     */
    private suspend fun processStatusChanges(
        changedItems: List<DoubanMarkItem>,
        newStatus: DoubanMarkStatus,
        syncedItemsMap: Map<String, DoubanSyncedItem>,
        watchlistWatchedIds: TraktRepository.WatchlistWatchedIds?
    ): Int {
        var successCount = 0
        changedItems.forEachIndexed { idx, item ->
            if (cancelled) return successCount
            val synced = syncedItemsMap[item.doubanId] ?: return@forEachIndexed
            val traktId = synced.traktId ?: return@forEachIndexed
            val mediaType = when (synced.mediaType) {
                "movie" -> MediaType.MOVIE
                "show" -> MediaType.SHOW
                else -> return@forEachIndexed
            }

            _progress.value = _progress.value.copy(
                subPhase = "状态变化", currentTitle = item.title
            )

            try {
                val movieIds = if (mediaType == MediaType.MOVIE) listOf(traktId) else emptyList()
                val showIds = if (mediaType == MediaType.SHOW) listOf(traktId) else emptyList()

                when (newStatus) {
                    DoubanMarkStatus.WISH -> {
                        // COLLECT → WISH: removeFromWatched + addToWatchlist
                        traktRepository.batchRemoveFromWatched(movieIds, showIds)
                        traktRepository.batchAddToWatchlist(movieIds, showIds)
                    }
                    DoubanMarkStatus.COLLECT -> {
                        // WISH → COLLECT: removeFromWatchlist + markAsWatched
                        traktRepository.batchRemoveFromWatchlist(movieIds, showIds)
                        traktRepository.batchMarkAsWatched(movieIds, showIds)
                    }
                }

                // 更新 douban_synced_items.status
                doubanSyncedItemDao.insertAll(listOf(
                    synced.copy(status = newStatus.path, syncedAt = System.currentTimeMillis())
                ))
                successCount++
            } catch (_: Exception) {
                // 状态变化失败:不更新 status 字段,下次重试时仍会检测到变化
            }
        }
        return successCount
    }

    /**
     * 完全重写主流程(模式 C)。
     *
     * 1. 读 douban_synced_items 全表,按 status + mediaType 分组
     * 2. 批量 removeFromWatchlist + removeFromWatched
     * 3. 清空 douban_synced_items 表
     * 4. 走 forceOverwrite=true 的同步流程
     */
    private suspend fun runSyncFullRewrite() {
        val creds = doubanAuthStorage.getCredentials()
            ?: run {
                _progress.value = DoubanSyncProgress(isComplete = true, phase = "未登录豆瓣")
                return
            }

        val startTime = System.currentTimeMillis()
        _progress.value = DoubanSyncProgress(
            isRunning = true, startTimeMs = startTime, phase = "清空已同步标记",
            isRetry = false, currentTitle = null
        )

        traktRepository.loadWatchlistWatchedIds()

        val allSynced = doubanSyncedItemDao.getAllSyncedItems()
        val wishMovieIds = mutableListOf<Int>()
        val wishShowIds = mutableListOf<Int>()
        val collectMovieIds = mutableListOf<Int>()
        val collectShowIds = mutableListOf<Int>()
        for (item in allSynced) {
            val traktId = item.traktId ?: continue
            when (item.status) {
                "wish" -> when (item.mediaType) {
                    "movie" -> wishMovieIds.add(traktId)
                    "show" -> wishShowIds.add(traktId)
                }
                "collect" -> when (item.mediaType) {
                    "movie" -> collectMovieIds.add(traktId)
                    "show" -> collectShowIds.add(traktId)
                }
            }
        }

        _progress.value = _progress.value.copy(
            total = allSynced.size, current = 0,
            subPhase = "移除 watchlist + watched"
        )

        try {
            if (cancelled) return
            traktRepository.batchRemoveFromWatchlist(wishMovieIds, wishShowIds)
            traktRepository.batchRemoveFromWatched(collectMovieIds, collectShowIds)
        } catch (e: Exception) {
            _progress.value = _progress.value.copy(
                phase = "清空失败,已中止", isComplete = true, isRunning = false
            )
            return
        }

        doubanSyncedItemDao.clearAll()

        _progress.value = _progress.value.copy(phase = "重新应用豆瓣状态")
        runSyncLegacy(forceOverwrite = true)
    }

    /**
     * 续传主流程:从 douban_sync_pending_items 表加载未处理完的列表数据,跳过列表爬取。
     *
     * 流程:
     * 1. 从 pending items 表按 status 分组加载
     * 2. 过滤已同步的(断点续传:已成功的会在 douban_synced_items 表里)
     * 3. 走 syncBatchToTrakt:详情页 → TraktId → Trakt 写入
     * 4. 处理完一批后从 pending items 表删除已处理的 doubanId
     * 5. 处理完所有条目后 pending items 表自然清空
     */
    private suspend fun runResume() {
        val creds = doubanAuthStorage.getCredentials()
            ?: run {
                _progress.value = DoubanSyncProgress(isComplete = true, phase = "未登录豆瓣")
                return
            }

        val startTime = System.currentTimeMillis()
        _progress.value = DoubanSyncProgress(
            isRunning = true, startTimeMs = startTime, phase = "续传上次未处理完的列表",
            isRetry = false, currentTitle = null
        )

        // 加载 Trakt 已有标记缓存
        traktRepository.loadWatchlistWatchedIds()
        val watchlistWatchedIds = traktRepository.getWatchlistWatchedIds()

        // 加载已同步记录(续传模式下,已成功的会通过 syncedIds 跳过)
        val syncedIds = doubanSyncedItemDao.getAllSyncedDoubanIds().toSet()

        val allFailed = mutableListOf<DoubanSyncFailure>()
        val recentFailuresBuffer = ArrayDeque<DoubanSyncFailure>()
        var totalSuccess = 0
        var totalSkipped = 0
        var totalCacheHit = 0

        // 按 status 分组处理(wish/collect 分别走)
        for (status in listOf(DoubanMarkStatus.WISH, DoubanMarkStatus.COLLECT)) {
            if (cancelled) break
            val pendingEntities = doubanSyncPendingItemDao.getByStatus(status.path)
            if (pendingEntities.isEmpty()) continue

            // 转换为 DoubanMarkItem,复用 syncBatchToTrakt 流程
            val items = pendingEntities.map { e ->
                DoubanMarkItem(
                    doubanId = e.doubanId,
                    title = e.title,
                    rating = e.rating,
                    comment = e.comment,
                    markedAt = e.markedAt,
                    doubanUrl = e.doubanUrl,
                    posterUrl = e.posterUrl
                )
            }

            val phaseName = if (status == DoubanMarkStatus.WISH) "续传想看列表" else "续传看过列表"
            _progress.value = _progress.value.copy(
                phase = phaseName, total = items.size, current = 0, currentTitle = null
            )

            val result = syncBatchToTrakt(
                items = items,
                status = status,
                cookie = creds.cookie,
                syncedIds = syncedIds,
                watchlistWatchedIds = watchlistWatchedIds,
                onProgress = { cur, subPhase, cacheHit, currentTitle, recentFailure ->
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    val eta = if (cur > 0 && _progress.value.total > 0) {
                        (elapsed * (_progress.value.total - cur) / cur).coerceAtLeast(0)
                    } else -1L
                    if (recentFailure != null) {
                        recentFailuresBuffer.addLast(recentFailure)
                        while (recentFailuresBuffer.size > 5) recentFailuresBuffer.removeFirst()
                    }
                    _progress.value = _progress.value.copy(
                        current = cur,
                        subPhase = subPhase,
                        cacheHitCount = _progress.value.cacheHitCount + cacheHit,
                        etaSeconds = eta,
                        currentTitle = currentTitle,
                        recentFailures = recentFailuresBuffer.toList()
                    )
                }
            )

            allFailed.addAll(result.failed)
            totalSuccess += result.success
            totalSkipped += result.skipped
            totalCacheHit += result.cacheHit
            // 处理完一批后,从 pending items 表删除已处理的 doubanId
            if (items.isNotEmpty()) {
                doubanSyncPendingItemDao.deleteByDoubanIds(items.map { it.doubanId })
            }
        }

        // 持久化失败项
        persistFailures(allFailed)

        val finalProgress = DoubanSyncProgress(
            isRunning = false,
            isComplete = true,
            current = _progress.value.current,
            total = _progress.value.total,
            successCount = totalSuccess,
            failedCount = allFailed.size,
            skippedCount = totalSkipped,
            cacheHitCount = totalCacheHit,
            failedItems = allFailed,
            phase = if (cancelled) "已取消" else "续传完成",
            startTimeMs = startTime,
            etaSeconds = 0,
            recentFailures = recentFailuresBuffer.toList(),
            isRetry = false
        )
        _progress.value = finalProgress
        // 同步完成后:上传个人数据到云端（跨设备同步，失败不阻塞）
        uploadToCloudAfterSync(mode = "RESUME", isFullComplete = !cancelled)
    }

    /**
     * 重试主流程:跳过列表爬取,直接走详情页 → TraktId → Trakt 写入。
     */
    private suspend fun runRetry(
        failures: List<DoubanSyncFailure>,
        selectedReasons: Set<FailureReason>
    ) {
        val creds = doubanAuthStorage.getCredentials()
            ?: run {
                _progress.value = DoubanSyncProgress(isComplete = true, phase = "未登录豆瓣")
                return
            }

        val startTime = System.currentTimeMillis()
        _progress.value = DoubanSyncProgress(
            isRunning = true, startTimeMs = startTime, phase = "重试上次失败项",
            isRetry = true, currentTitle = null
        )

        // 加载 Trakt 已有标记缓存
        traktRepository.loadWatchlistWatchedIds()
        val watchlistWatchedIds = traktRepository.getWatchlistWatchedIds()

        // 加载已同步记录(重试成功的项要插入到这个表)
        val syncedIds = doubanSyncedItemDao.getAllSyncedDoubanIds().toSet()

        // 按 selectedReasons 过滤,转回 DoubanMarkItem 用于复用 syncBatchToTrakt
        val filteredFailures = failures.filter { it.failureReason in selectedReasons }

        // 按 status 分组重试(wish/collect 分别走)
        val allStillFailed = mutableListOf<DoubanSyncFailure>()
        val allRetrySuccess = mutableListOf<String>()  // 重试成功的 doubanId
        var totalSuccess = 0
        var totalCacheHit = 0
        val recentFailuresBuffer = ArrayDeque<DoubanSyncFailure>()

        for (status in listOf(DoubanMarkStatus.WISH, DoubanMarkStatus.COLLECT)) {
            if (cancelled) break
            val statusFailures = filteredFailures.filter { it.status == status }
            if (statusFailures.isEmpty()) continue

            val phaseName = if (status == DoubanMarkStatus.WISH) "重试想看失败项" else "重试看过失败项"
            _progress.value = _progress.value.copy(
                phase = phaseName, total = statusFailures.size, current = 0,
                currentTitle = null
            )

            // 转回 DoubanMarkItem,沿用现有 syncBatchToTrakt 流程
            val items = statusFailures.map { it.toMarkItem() }

            val result = syncBatchToTrakt(
                items = items,
                status = status,
                cookie = creds.cookie,
                syncedIds = syncedIds,  // 重试成功的项如果在已同步表里,会跳过(实际上不会,因为之前失败了)
                watchlistWatchedIds = watchlistWatchedIds,
                onProgress = { cur, subPhase, cacheHit, currentTitle, recentFailure ->
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    val eta = if (cur > 0 && _progress.value.total > 0) {
                        (elapsed * (_progress.value.total - cur) / cur).coerceAtLeast(0)
                    } else -1L
                    if (recentFailure != null) {
                        recentFailuresBuffer.addLast(recentFailure)
                        while (recentFailuresBuffer.size > 5) recentFailuresBuffer.removeFirst()
                    }
                    _progress.value = _progress.value.copy(
                        current = cur,
                        subPhase = subPhase,
                        cacheHitCount = _progress.value.cacheHitCount + cacheHit,
                        etaSeconds = eta,
                        currentTitle = currentTitle,
                        recentFailures = recentFailuresBuffer.toList()
                    )
                },
                existingFailures = statusFailures  // 传入原失败项,用于 attemptCount 累加
            )

            // 区分重试结果:成功 vs 仍然失败
            val successDoubanIds = items.map { it.doubanId }.toSet() - result.failed.map { it.doubanId }.toSet()
            allRetrySuccess.addAll(successDoubanIds)
            allStillFailed.addAll(result.failed)
            totalSuccess += result.success
            totalCacheHit += result.cacheHit
        }

        // 从 douban_sync_failures 表删除重试成功的项
        for (doubanId in allRetrySuccess) {
            doubanSyncFailureDao.deleteByDoubanId(doubanId)
        }

        // 仍然失败的项:更新 attemptCount + failureReason,覆盖回表
        persistFailures(allStillFailed)

        val finalProgress = DoubanSyncProgress(
            isRunning = false,
            isComplete = true,
            current = _progress.value.current,
            total = _progress.value.total,
            successCount = totalSuccess,
            failedCount = allStillFailed.size,
            skippedCount = allRetrySuccess.size,  // 重试模式下,跳过数 = 重试成功数
            cacheHitCount = totalCacheHit,
            failedItems = allStillFailed,
            phase = if (cancelled) "已取消" else "重试完成",
            startTimeMs = startTime,
            etaSeconds = 0,
            recentFailures = recentFailuresBuffer.toList(),
            isRetry = true
        )
        _progress.value = finalProgress
        // 同步完成后:上传个人数据到云端（跨设备同步，失败不阻塞）
        // 重试不算完整同步（仅处理失败项），不更新 lastFullSyncAt，不上传 id_mappings
        uploadToCloudAfterSync(mode = "RETRY", isFullComplete = false)
    }

    /**
     * 持久化失败项到 douban_sync_failures 表。
     *
     * 按 status 精细化覆盖写入(先删同 status 旧失败项,再插新的)。
     *
     * 注意:不再在 failures 为空时调用 clearAll()。
     * 原因:取消同步时,可能只处理了 WISH 状态(没有失败项),但之前有 COLLECT 失败项记录。
     * 如果此时 clearAll() 会丢失 COLLECT 失败项记录。
     * 现在改为:只覆盖当前有数据的 status,保留其他 status 的旧失败项记录。
     * 如果本次同步确实没有任何失败项(正常完成),则按 status 清空对应记录。
     */
    private suspend fun persistFailures(failures: List<DoubanSyncFailure>) {
        if (failures.isEmpty()) {
            // 没有失败项:不调用 clearAll(),保留之前其他 status 的失败项记录
            // (取消时可能只处理了部分 status,其他 status 的失败项记录应保留)
            return
        }
        // 按 status 分组覆盖写入:只清空当前有数据的 status
        val byStatus = failures.groupBy { it.status }
        for ((status, items) in byStatus) {
            doubanSyncFailureDao.deleteByStatus(status.path)
            doubanSyncFailureDao.insertAll(items.map { it.toEntity() })
        }
    }

    /**
     * 同步完成后上传个人数据到云端（跨设备同步）。
     *
     * 上传内容：synced_items、pending_items、sync_meta，可选 id_mappings。
     * 同时上传失败项（复用 CloudFailureSyncManager）。
     * 失败不阻塞主流程，只记日志。
     *
     * @param mode 同步模式标识（用于云端 sync_meta 记录）
     * @param isFullComplete true=完整同步完成（更新 lastFullSyncAt，触发 id_mappings 上传）
     */
    private suspend fun uploadToCloudAfterSync(mode: String, isFullComplete: Boolean) {
        // 先记录本地 sync_meta
        runCatching { doubanSyncMetaStorage.recordLocalSync(mode, isFullComplete) }
        // 上传个人数据
        runCatching {
            cloudPersonalSyncManager.uploadAll(
                lastSyncMode = mode,
                isFullComplete = isFullComplete,
                uploadIdMappings = isFullComplete  // 仅完整同步完成时上传 IMDb 映射
            )
        }
        // 上传失败项（保留原有逻辑）
        runCatching { cloudFailureSyncManager.uploadIfHasFailures() }
        // 一次性批量上传 dirty 详情到全局池
        runCatching { uploadDirtyDetails() }
    }

    /**
     * 将同步过程中新爬取的 dirty 详情批量上传到全局详情池。
     *
     * 从 [dirtyDetailIds] 收集 doubanId，查本地缓存获取详情数据，
     * 按分片聚合后一次性上传（CloudDetailsPoolManager 内部按分片 GET→合并→PUT）。
     * 上传后清空 dirty 集合。
     */
    private suspend fun uploadDirtyDetails() {
        if (dirtyDetailIds.isEmpty()) return
        val entries = mutableMapOf<String, DoubanDetailCacheEntry>()
        val ids = dirtyDetailIds.toList()
        dirtyDetailIds.clear()
        for (id in ids) {
            doubanDetailCache.get(id)?.let { entries[id] = it }
        }
        if (entries.isNotEmpty()) {
            cloudDetailsPoolManager.uploadDetails(entries)
        }
    }

    /**
     * 同步开始前从云端拉取个人数据并合并到本地（B 手机跨设备接续）。
     *
     * 拉取内容：synced_items、pending_items、id_mappings、sync_meta。
     * 合并后本地即拥有 A 手机的同步进度，增量同步时可跳过已同步条目，
     * 配合 [checkSkipListCrawl] 决策可跳过豆瓣列表爬取。
     *
     * 失败不阻塞主流程，只记日志。
     *
     * @return PullResult，失败时返回空的 PullResult
     */
    private suspend fun pullFromCloudBeforeSync(): CloudPersonalSyncManager.PullResult {
        return runCatching { cloudPersonalSyncManager.downloadAndMerge() }
            .getOrElse {
                CloudPersonalSyncManager.PullResult()
            }
    }

    /**
     * 判断是否可跳过豆瓣列表爬取（「近期跳过列表」策略核心）。
     *
     * 条件：
     * 1. 上次完整同步距今 < 7 天（sync_meta.canSkipListCrawl）
     * 2. 本地 pending items 为空（若有未处理完的待续传数据，应先处理 pending）
     *
     * 满足条件时跳过列表爬取，直接返回（不发起任何豆瓣请求），
     * 用户已同步的数据由云端拉取获得，新增条目等下次超过阈值时再校验。
     *
     * @return true=可跳过列表爬取；false=需要爬列表
     */
    private suspend fun checkSkipListCrawl(): Boolean {
        val pendingCount = doubanSyncPendingItemDao.count()
        if (pendingCount > 0) return false
        return doubanSyncMetaStorage.canSkipListCrawl()
    }

    /** 单条同步结果 */
    private data class SyncResolve(
        val item: DoubanMarkItem,
        val imdbId: String,
        val traktId: Int,
        val mediaType: MediaType,
        val originalFailure: DoubanSyncFailure? = null  // 重试模式下传入,用于 attemptCount 累加
    )

    private data class BatchSyncResult(
        val success: Int,
        val failed: List<DoubanSyncFailure>,
        val skipped: Int = 0,
        val cacheHit: Int = 0
    )

    /**
     * 批量同步一批条目到 Trakt：
     * 1. 过滤已同步条目（断点续传）
     * 2. 并发爬详情页拿 imdbId（并发度 3，优先查持久化缓存）
     * 3. 并发查 traktId（并发度 5）
     * 4. 冲突分类后批量 POST Trakt
     *
     * 阶段 1(详情页)与阶段 2(Trakt 查询)用 Channel 流水线连接：
     * - 阶段 1 生产者:并发度 3,结果写入 Channel<SyncResolve>
     * - 阶段 2 消费者:并发度 5,从 Channel 读取,查 traktId,累积到 resolvedTraktList
     * - 进度用 AtomicInteger 计数 completedCount,每完成一条 +1
     * - 阶段 3+4 不变(冲突分类 + 批量 POST)
     *
     * @param existingFailures 重试模式下传入原失败项,用于 attemptCount 累加(同步模式下为 null)
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun syncBatchToTrakt(
        items: List<DoubanMarkItem>,
        status: DoubanMarkStatus,
        cookie: String,
        syncedIds: Set<String>,
        watchlistWatchedIds: TraktRepository.WatchlistWatchedIds?,
        onProgress: (current: Int, subPhase: String, cacheHitDelta: Int, currentTitle: String?, recentFailure: DoubanSyncFailure?) -> Unit,
        existingFailures: List<DoubanSyncFailure>? = null
    ): BatchSyncResult {
        val failed = mutableListOf<DoubanSyncFailure>()
        val existingMap = existingFailures?.associateBy { it.doubanId } ?: emptyMap()

        val pending = items.filter { it.doubanId !in syncedIds }
        val skippedCount = items.size - pending.size

        if (pending.isEmpty()) {
            onProgress(items.size, "断点续传跳过", 0, null, null)
            return BatchSyncResult(0, emptyList(), skippedCount, 0)
        }

        // ===== 全局详情池前置查询：批量从云端拉取本批次 doubanId 的详情，写入本地缓存（不覆盖已有） =====
        // 这样阶段 1 的 fetchDetail 会命中本地缓存秒回，避免对豆瓣的反爬爬取。
        // 失败不阻塞主流程（最坏情况是阶段1重新爬取豆瓣详情页）。
        runCatching {
            val pendingDoubanIds = pending.map { it.doubanId }
            cloudDetailsPoolManager.fetchAndMergeToLocal(pendingDoubanIds, doubanDetailCache)
        }

        // 用 Channel 连接阶段 1(详情页)→ 阶段 2(Trakt 查询)
        val detailChannel = Channel<SyncResolve>(capacity = pending.size)
        val detailSemaphore = Semaphore(3)
        val traktSemaphore = Semaphore(5)
        val completedCount = AtomicInteger(0)
        val detailCacheHit = AtomicInteger(0)
        val resolvedTraktList = mutableListOf<SyncResolve>()

        coroutineScope {
            // ===== 阶段 1: 详情页爬取(生产者,并发度 3) =====
            val detailJobs = pending.mapIndexed { idx, item ->
                async {
                    if (cancelled) return@async
                    try {
                        val (detail, isCacheHit) = detailSemaphore.withPermit {
                            if (cancelled) return@withPermit Pair<DoubanDetailInfo?, Boolean>(null, false)
                            doubanRepository.fetchDetail(item.doubanUrl, cookie, item.title) { _, _ -> }
                        }
                        if (isCacheHit) detailCacheHit.incrementAndGet()
                        else {
                            // 非缓存命中 = 新爬取的详情，标记为 dirty 供同步完成后批量上传到全局池
                            dirtyDetailIds.add(item.doubanId)
                        }
                        if (detail == null) {
                            val failure = buildFailure(item, status, FailureReason.DETAIL_FETCH_FAILED, existingMap)
                            synchronized(failed) { failed.add(failure) }
                            val done = completedCount.incrementAndGet()
                            onProgress(done, "详情页", 0, item.title, failure)
                            return@async
                        }
                        val imdbId = detail.imdbId
                        if (imdbId.isNullOrEmpty()) {
                            val failure = buildFailure(item, status, FailureReason.NO_IMDB_ID, existingMap)
                            synchronized(failed) { failed.add(failure) }
                            val done = completedCount.incrementAndGet()
                            onProgress(done, "详情页", 0, item.title, failure)
                            return@async
                        }
                        val mediaType = if (detail.isTvShow) MediaType.SHOW else MediaType.MOVIE
                        detailChannel.send(SyncResolve(item, imdbId, traktId = 0, mediaType = mediaType, originalFailure = existingMap[item.doubanId]))
                    } catch (e: Exception) {
                        val failure = buildFailure(item, status, FailureReason.DETAIL_FETCH_FAILED, existingMap)
                        synchronized(failed) { failed.add(failure) }
                        val done = completedCount.incrementAndGet()
                        onProgress(done, "详情页", 0, item.title, failure)
                    }
                }
            }

            // ===== 阶段 2: Trakt 查询(消费者,并发度 5) =====
            val traktJobs = (1..5).map {
                async {
                    for (r in detailChannel) {
                        if (cancelled) break
                        var traktId = traktRepository.getCachedTraktIdByImdb(r.imdbId, r.mediaType)
                        if (traktId == null) {
                            traktId = traktSemaphore.withPermit {
                                if (cancelled) null
                                else try {
                                    traktRepository.searchByImdb(r.imdbId, r.mediaType).getOrNull()
                                        ?.firstOrNull()?.let { result ->
                                            when (r.mediaType) {
                                                MediaType.MOVIE -> result.movie?.ids?.trakt
                                                MediaType.SHOW -> result.show?.ids?.trakt
                                                else -> null
                                            }
                                        }
                                } catch (e: Exception) { null }
                            }
                        }
                        val done = completedCount.incrementAndGet()
                        if (traktId == null || traktId <= 0) {
                            val failure = buildFailure(r.item, status, FailureReason.TRAKT_NOT_FOUND, existingMap)
                            synchronized(failed) { failed.add(failure) }
                            onProgress(done, "Trakt 查询", 0, r.item.title, failure)
                        } else {
                            synchronized(resolvedTraktList) { resolvedTraktList.add(r.copy(traktId = traktId)) }
                            onProgress(done, "Trakt 查询", 0, r.item.title, null)
                        }
                    }
                }
            }

            // 等阶段 1 全部完成 → 关闭 Channel → 阶段 2 消费完所有
            detailJobs.awaitAll()
            detailChannel.close()
            traktJobs.awaitAll()
        }

        // 详情池上传已优化：不再每批次上传，改为同步完成时一次性批量上传（uploadToCloudAfterSync）
        if (cancelled) return BatchSyncResult(0, failed, skippedCount, detailCacheHit.get())

        val withTraktId = resolvedTraktList
        val cacheHit = detailCacheHit.get()
        if (cacheHit > 0) {
            onProgress(completedCount.get(), "详情页", cacheHit, null, null)
        }

        // ===== 阶段 3：冲突分类（按豆瓣优先覆盖策略） =====
        val wishMovieIds = mutableListOf<Int>()
        val wishShowIds = mutableListOf<Int>()
        val collectMovieIds = mutableListOf<Int>()
        val collectShowIds = mutableListOf<Int>()
        val removeFromWatchlistMovieIds = mutableListOf<Int>()
        val removeFromWatchlistShowIds = mutableListOf<Int>()
        val movieRatings = mutableListOf<Pair<Int, Int>>()
        val showRatings = mutableListOf<Pair<Int, Int>>()
        val batchToInsert = mutableListOf<DoubanSyncedItem>()

        for (r in withTraktId) {
            val isInWatchlist = watchlistWatchedIds?.isInWatchlist(r.traktId, null, r.mediaType) == true
            val isWatched = watchlistWatchedIds?.isWatched(r.traktId, null, r.mediaType) == true

            when (status) {
                DoubanMarkStatus.WISH -> {
                    if (isWatched) {
                        // 豆瓣想看 + Trakt 看过 → 不覆盖（保留 Trakt 已看）
                    } else if (!isInWatchlist) {
                        when (r.mediaType) {
                            MediaType.MOVIE -> wishMovieIds.add(r.traktId)
                            MediaType.SHOW -> wishShowIds.add(r.traktId)
                            else -> {}
                        }
                    }
                }
                DoubanMarkStatus.COLLECT -> {
                    if (isInWatchlist) {
                        when (r.mediaType) {
                            MediaType.MOVIE -> removeFromWatchlistMovieIds.add(r.traktId)
                            MediaType.SHOW -> removeFromWatchlistShowIds.add(r.traktId)
                            else -> {}
                        }
                    }
                    if (!isWatched) {
                        when (r.mediaType) {
                            MediaType.MOVIE -> collectMovieIds.add(r.traktId)
                            MediaType.SHOW -> collectShowIds.add(r.traktId)
                            else -> {}
                        }
                    }
                }
            }

            if (r.item.rating != null && r.item.rating > 0) {
                val traktRating = r.item.rating * 2
                when (r.mediaType) {
                    MediaType.MOVIE -> movieRatings.add(r.traktId to traktRating)
                    MediaType.SHOW -> showRatings.add(r.traktId to traktRating)
                    else -> {}
                }
            }

            batchToInsert.add(
                DoubanSyncedItem(
                    doubanId = r.item.doubanId,
                    imdbId = r.imdbId,
                    traktId = r.traktId,
                    title = r.item.title,
                    status = status.path,
                    rating = r.item.rating,
                    syncedAt = System.currentTimeMillis(),
                    mediaType = r.mediaType.name.lowercase()
                )
            )
        }

        // ===== 阶段 4：批量 POST Trakt =====
        onProgress(withTraktId.size, "写入 Trakt", 0, null, null)
        if (cancelled) return BatchSyncResult(0, failed, skippedCount, cacheHit)
        try {
            withTimeout(60_000L) {
                when (status) {
                    DoubanMarkStatus.WISH -> {
                        traktRepository.batchAddToWatchlist(wishMovieIds, wishShowIds)
                    }
                    DoubanMarkStatus.COLLECT -> {
                        traktRepository.batchRemoveFromWatchlist(removeFromWatchlistMovieIds, removeFromWatchlistShowIds)
                        traktRepository.batchMarkAsWatched(collectMovieIds, collectShowIds)
                    }
                }
                traktRepository.batchAddRatings(movieRatings, showRatings)

                if (batchToInsert.isNotEmpty()) {
                    doubanSyncedItemDao.insertAll(batchToInsert)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val timeoutFailures = withTraktId.map { r ->
                buildFailure(r.item, status, FailureReason.TRAKT_WRITE_TIMEOUT, existingMap)
            }
            synchronized(failed) { failed.addAll(timeoutFailures) }
        } catch (e: Exception) {
            val writeFailures = withTraktId.map { r ->
                buildFailure(r.item, status, FailureReason.TRAKT_WRITE_FAILED, existingMap)
            }
            synchronized(failed) { failed.addAll(writeFailures) }
        }

        val successCount = (withTraktId.size - failed.size).coerceAtLeast(0)
        return BatchSyncResult(success = successCount, failed = failed, skipped = skippedCount, cacheHit = cacheHit)
    }

    /**
     * 构造失败项。
     * 重试模式下累加 attemptCount,同步模式下 attemptCount=0。
     */
    private fun buildFailure(
        item: DoubanMarkItem,
        status: DoubanMarkStatus,
        reason: FailureReason,
        existingMap: Map<String, DoubanSyncFailure>
    ): DoubanSyncFailure {
        val existing = existingMap[item.doubanId]
        return DoubanSyncFailure(
            doubanId = item.doubanId,
            title = item.title,
            posterUrl = item.posterUrl,
            rating = item.rating,
            comment = item.comment,
            markedAt = item.markedAt,
            doubanUrl = item.doubanUrl,
            status = status,
            failureReason = reason,
            failedAt = System.currentTimeMillis(),
            attemptCount = (existing?.attemptCount ?: 0) + 1
        )
    }
}
