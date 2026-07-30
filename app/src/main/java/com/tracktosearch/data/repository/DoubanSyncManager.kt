package com.tracktosearch.data.repository

import android.content.Context
import android.os.PowerManager
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.local.db.DoubanSyncPendingItemDao
import com.tracktosearch.data.local.db.DoubanSyncPendingItemEntity
import com.tracktosearch.data.local.db.DoubanSyncRollbackDao
import com.tracktosearch.data.local.db.DoubanSyncRollbackEntity
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DelayInfo
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanDetailInfo
import com.tracktosearch.data.remote.douban.DoubanMarkItem
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.session.SessionMode
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.data.util.PersistentTtlCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
    val etaSeconds: Long = -1,      // 预计剩余秒数（-1=未知,保留供内部计算,UI 不展示）
    val cookieExpired: Boolean = false,  // 豆瓣 Cookie 过期（需引导用户重新登录）
    val currentTitle: String? = null,    // 当前正在处理的条目标题
    val recentFailures: List<DoubanSyncFailure> = emptyList(), // 最近 5 条失败(滚动展示)
    val isRetry: Boolean = false,   // true=重试模式(从失败项数据走,不爬列表)
    val delayInfo: DelayInfo? = null,  // 当前延时信息(豆瓣反爬/重试等待,UI 做倒计时展示)
    val isCancelling: Boolean = false  // true=用户已点击取消,正在停止中的中间态
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
    private val doubanSyncRollbackDao: DoubanSyncRollbackDao,
    private val cloudFailureSyncManager: CloudFailureSyncManager,
    private val cloudPersonalSyncManager: CloudPersonalSyncManager,
    private val cloudDetailsPoolManager: CloudDetailsPoolManager,
    private val doubanSyncMetaStorage: DoubanSyncMetaStorage,
    private val doubanDetailCache: PersistentTtlCache<DoubanDetailCacheEntry>,
    private val tokenStorage: TokenStorage,
    private val statusConsistencyChecker: DoubanTraktStatusConsistencyChecker,
    private val sessionModeManager: SessionModeManager,
    private val tmdbRepository: TmdbRepository,
    @ApplicationContext private val appContext: Context
) {
    private val _progress = MutableStateFlow(DoubanSyncProgress())
    val progress: StateFlow<DoubanSyncProgress> = _progress.asStateFlow()

    /** WakeLock:同步期间保持 CPU 唤醒,避免息屏 Doze 模式下网络请求 timeout */
    private var wakeLock: PowerManager.WakeLock? = null

    /** Application scope：同步协程在此运行，Activity/Service 销毁不影响 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 当前是否豆瓣独立模式（已激活网关 + 已登录豆瓣 + 未连 trakt）。
     *
     * 豆瓣模式下:
     * - 跳过 Trakt 登录态预检（不需要 trakt token）
     * - 跳过 loadWatchlistWatchedIds（没有 trakt 连接，加载会失败）
     * - syncBatchToTrakt 委托给 [syncBatchToDoubanLocal]，跳过 trakt 写入，
     *   改写本地 douban_synced_items 扩展表作为 watchlist 数据源
     * - 网关 API（searchByImdb、TMDB 富化）仍可用
     */
    private suspend fun isDoubanMode(): Boolean =
        sessionModeManager.sessionMode.first() == SessionMode.DOUBAN

    init {
        // 监听 DoubanRepository 的延时事件,合并到 progress.delayInfo
        // UI 层基于 delayInfo(startMs + totalSeconds)做倒计时展示
        appScope.launch {
            doubanRepository.delayEvent.collect { info ->
                _progress.value = _progress.value.copy(delayInfo = info)
            }
        }
    }

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
     * 流程:
     * 1. 立即更新 progress 为 isCancelling=true + phase="正在取消...",UI 即时响应
     * 2. 设置 cancelled=true,同步循环在下一个 checkpoint 退出
     * 3. 异步上传当前进度到云端(pending/synced/sync_meta/dirty 详情池),失败不阻塞取消
     *
     * 用户从 UI 点击取消后立即看到"正在取消..."不可再点,同步作业在后台自然退出。
     */
    fun cancel() {
        cancelled = true
        // 同步状态更新到 isCancelling 中间态:UI 立即禁用取消按钮 + 显示"正在取消..." 文案
        _progress.value = _progress.value.copy(
            isCancelling = true,
            phase = "正在取消...",
            subPhase = "",
            delayInfo = null
        )
        // 异步上传当前进度（不阻塞取消，失败只记日志）
        appScope.launch {
            // 等待同步作业退出后再上传，避免 dirtyDetailIds toList/clear 竞态导致数据丢失
            syncJob?.join()
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

    /** 同步取消是否已进入正在停止的中间态（UI 用于禁用取消按钮并显示"正在取消..."） */
    fun isCancelling(): Boolean = _progress.value.isCancelling

    /**
     * 获取 WakeLock,保持 CPU 唤醒避免息屏 Doze 模式下网络请求 timeout。
     *
     * 同步生命周期内(爬列表→详情页→Trakt 写入→一致性检查→云端上传)全程持有,
     * 比仅 syncBatchToTrakt 阶段持有更可靠：Doze 下任何阶段网络都可能挂死。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackToSearch:DoubanSync")
        // 60 分钟超时,避免异常情况下 WakeLock 泄漏(完整同步通常在 10-30 分钟)
        wakeLock?.acquire(60 * 60 * 1000L)
    }

    /** 释放 WakeLock */
    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /**
     * Trakt 登录态预检(同步层兜底)。
     * - 已登录且 token 有效 → true,继续同步
     * - 未登录或 token 过期 → 设置 phase="未登录 Trakt,请先登录",返回 false
     *
     * 这是 UI 层前置引导的兜底:即使用户绕过 LoginScreen 直接调用同步
     * (如 DoubanLoginScreen 自动触发、设置页重试),也会被拦截。
     *
     * 豆瓣独立模式例外:不需要 trakt token,直接返回 true。
     */
    private suspend fun checkTraktAvailable(): Boolean {
        if (isDoubanMode()) return true
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
        // 同步入口立即 acquire WakeLock,覆盖整个同步生命周期(爬取→写入→一致性检查→云端上传)
        acquireWakeLock()
        syncJob = appScope.launch {
            try {
                if (!checkTraktAvailable()) return@launch
                runSyncLegacy(forceOverwrite)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _progress.value = _progress.value.copy(isRunning = false, isComplete = true, phase = "同步异常: ${e.message}")
            } finally {
                releaseWakeLock()
            }
        }
        return true
    }

    /**
     * 启动同步(指定模式,非 suspend,立即返回)。
     * - [SyncMode.INCREMENTAL_WITH_CHANGES]: 跳过已同步且状态一致的,处理状态变化的
     * - [SyncMode.FULL_REWRITE]: 先清空已同步标记,再 forceOverwrite=true
     *
     * @param forceCrawl true=强制爬取豆瓣列表,忽略 7 天冷却期(用户在冷却期内选择「强制同步」时用)
     */
    fun startSync(mode: SyncMode, forceCrawl: Boolean = false): Boolean {
        if (isRunning()) return false
        cancelled = false
        dirtyDetailIds.clear()
        // 同步入口立即 acquire WakeLock,覆盖整个同步生命周期(爬取→写入→一致性检查→云端上传)
        acquireWakeLock()
        syncJob = appScope.launch {
            try {
                if (!checkTraktAvailable()) return@launch
                runSync(mode, forceCrawl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _progress.value = _progress.value.copy(isRunning = false, isComplete = true, phase = "同步异常: ${e.message}")
            } finally {
                releaseWakeLock()
            }
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
        // 同步入口立即 acquire WakeLock,覆盖整个同步生命周期(爬取→写入→一致性检查→云端上传)
        acquireWakeLock()
        syncJob = appScope.launch {
            try {
                if (!checkTraktAvailable()) return@launch
                runResume()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _progress.value = _progress.value.copy(isRunning = false, isComplete = true, phase = "同步异常: ${e.message}")
            } finally {
                releaseWakeLock()
            }
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
     * 查询回滚记录数量(用于 App 启动时检测是否有未恢复的完整同步残留)。
     *
     * 返回 >0 表示上次完整重写同步未完成,用户有 N 个标记被删除但未恢复。
     */
    suspend fun getRollbackCount(): Int {
        return doubanSyncRollbackDao.count()
    }

    /**
     * 恢复回滚数据:将被删除的标记重新添加到 Trakt watchlist/history。
     *
     * 使用场景:上次完整重写同步失败/取消后,用户选择恢复被删除的标记。
     * 恢复完成后清除回滚表。
     *
     * @return 恢复的条目数(0=无回滚数据或恢复失败)
     */
    suspend fun restoreRollback(): Int {
        val rollbackItems = doubanSyncRollbackDao.getAll()
        if (rollbackItems.isEmpty()) return 0

        val wishMovieIds = mutableListOf<Int>()
        val wishShowIds = mutableListOf<Int>()
        val collectMovieIds = mutableListOf<Int>()
        val collectShowIds = mutableListOf<Int>()
        for (item in rollbackItems) {
            when (item.status) {
                "wish" -> when (item.mediaType) {
                    "movie" -> wishMovieIds.add(item.traktId)
                    "show" -> wishShowIds.add(item.traktId)
                }
                "collect" -> when (item.mediaType) {
                    "movie" -> collectMovieIds.add(item.traktId)
                    "show" -> collectShowIds.add(item.traktId)
                }
            }
        }

        val total = rollbackItems.size
        _progress.value = DoubanSyncProgress(
            isRunning = true, startTimeMs = System.currentTimeMillis(),
            phase = "恢复被删除的标记", total = total, current = 0
        )

        try {
            if (wishMovieIds.isNotEmpty() || wishShowIds.isNotEmpty()) {
                traktRepository.batchAddToWatchlist(wishMovieIds, wishShowIds)
            }
            if (collectMovieIds.isNotEmpty() || collectShowIds.isNotEmpty()) {
                traktRepository.batchMarkAsWatched(collectMovieIds, collectShowIds)
            }
            // 恢复评分
            for (item in rollbackItems) {
                val rating = item.rating ?: continue
                if (rating > 0) {
                    val traktRating = rating * 2  // 豆瓣 1-5 → Trakt 1-10
                    val mediaType = if (item.mediaType == "movie") MediaType.MOVIE else MediaType.SHOW
                    runCatching {
                        traktRepository.addRating(item.traktId, traktRating, mediaType)
                    }
                }
            }
            _progress.value = _progress.value.copy(
                current = total, successCount = total,
                phase = "恢复完成", isComplete = true, isRunning = false
            )
            doubanSyncRollbackDao.clearAll()
            return total
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _progress.value = _progress.value.copy(
                phase = "恢复失败: ${e.message}", isComplete = true, isRunning = false
            )
            return 0
        }
    }

    /**
     * 丢弃回滚数据(用户选择不恢复时调用)。
     */
    suspend fun discardRollback() {
        doubanSyncRollbackDao.clearAll()
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
        // 同步入口立即 acquire WakeLock,覆盖整个同步生命周期(爬取→写入→一致性检查→云端上传)
        acquireWakeLock()
        syncJob = appScope.launch {
            try {
                if (!checkTraktAvailable()) return@launch
                runRetry(failures, selectedReasons)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _progress.value = _progress.value.copy(isRunning = false, isComplete = true, phase = "同步异常: ${e.message}")
            } finally {
                releaseWakeLock()
            }
        }
        return true
    }

    private suspend fun runSync(mode: SyncMode, forceCrawl: Boolean = false) {
        when (mode) {
            SyncMode.INCREMENTAL_WITH_CHANGES -> runSyncIncremental(includeStatusChanges = true, forceCrawl = forceCrawl)
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
        // 豆瓣模式跳过 trakt watchlist 缓存加载（无 trakt 连接，加载会失败）
        val isDoubanMode = isDoubanMode()
        if (!isDoubanMode) {
            traktRepository.loadWatchlistWatchedIds()
        }
        val watchlistWatchedIds = if (!isDoubanMode) traktRepository.getWatchlistWatchedIds() else null

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
            isRetry = false,
            isCancelling = _progress.value.isCancelling
        )
        _progress.value = finalProgress
        // 同步完成后:上传个人数据到云端（跨设备同步，失败不阻塞）
        uploadToCloudAfterSync(mode = "LEGACY", isFullComplete = !cancelled)
    }

    private suspend fun runSyncIncremental(includeStatusChanges: Boolean, forceCrawl: Boolean = false) {
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

        // 豆瓣模式跳过 trakt watchlist 缓存加载（无 trakt 连接，加载会失败）
        val isDoubanMode = isDoubanMode()
        if (!isDoubanMode) {
            traktRepository.loadWatchlistWatchedIds()
        }
        val watchlistWatchedIds = if (!isDoubanMode) traktRepository.getWatchlistWatchedIds() else null

        // 加载已同步记录（模式 A 和模式 B 都需要）：
        // - 模式 A：跳过已同步条目（与 forceOverwrite=false 语义一致，避免重复 POST Trakt）
        // - 模式 B：额外用于状态变化对比（WISH↔COLLECT）
        // 拉取云端后本地 synced_items 已包含 A 手机数据，B 手机可跳过已同步条目
        val syncedItemsList = doubanSyncedItemDao.getAllSyncedItems()
        val syncedItemsMap = syncedItemsList.associateBy { it.doubanId }
        val syncedIds = syncedItemsMap.keys

        // 「近期跳过列表」策略：模式 B 需要爬列表检测状态变化，不跳过；
        // 仅模式 A 且云端最近完整同步 < 7 天且无 pending → 跳过列表爬取
        // forceCrawl=true 时强制爬取(用户在冷却期内选择「强制同步」)
        if (!includeStatusChanges && !forceCrawl && checkSkipListCrawl()) {
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

        // 增量同步跳过已知失败项:不可恢复失败(NO_IMDB_ID/TRAKT_NOT_FOUND) + 重试超限(attemptCount>=3)的可恢复失败
        // 避免每次增量都重爬已知查不到 imdb/trakt 的条目,节省 API 调用
        val skipFailuresIds = runCatching {
            doubanSyncFailureDao.getAll()
                .filter { entity ->
                    val reason = FailureReason.fromString(entity.failureReason)
                    !reason.recoverable || entity.attemptCount >= 3
                }
                .map { it.doubanId }
                .toSet()
        }.getOrDefault(emptySet())
        if (skipFailuresIds.isNotEmpty()) {
            android.util.Log.i("DoubanSyncManager", "增量同步跳过 ${skipFailuresIds.size} 条已知失败项")
        }

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
                },
                skipFailuresIds = skipFailuresIds
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
            isRetry = false,
            isCancelling = _progress.value.isCancelling
        )
        _progress.value = finalProgress
        // 同步完成后:上传个人数据到云端（跨设备同步，失败不阻塞）
        val mode = "INCREMENTAL_WITH_CHANGES"
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
                        // COLLECT → WISH: 先 add 到 watchlist,再 remove 从 watched
                        // (先 add 后 remove:如果第二步失败,标记同时存在于两个列表,
                        //  下次一致性检查会自动统一为 watched 优先,不会丢失标记)
                        traktRepository.batchAddToWatchlist(movieIds, showIds)
                        traktRepository.batchRemoveFromWatched(movieIds, showIds)
                    }
                    DoubanMarkStatus.COLLECT -> {
                        // WISH → COLLECT: 先 markAsWatched,再 remove 从 watchlist
                        // (先 add 后 remove:如果第二步失败,标记同时存在于两个列表,
                        //  下次一致性检查会自动统一为 watched 优先,不会丢失标记)
                        val watchedAtIso = markedAtToIso(item.markedAt)
                        val movieItems = if (mediaType == MediaType.MOVIE) listOf(traktId to watchedAtIso) else emptyList()
                        val showItems = if (mediaType == MediaType.SHOW) listOf(traktId to watchedAtIso) else emptyList()
                        traktRepository.batchMarkAsWatchedAt(movieItems, showItems)
                        traktRepository.batchRemoveFromWatchlist(movieIds, showIds)
                    }
                }

                // 更新 douban_synced_items.status
                doubanSyncedItemDao.insertAll(listOf(
                    synced.copy(status = newStatus.path, syncedAt = System.currentTimeMillis())
                ))
                successCount++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 状态变化失败:不更新 status 字段,下次重试时仍会检测到变化
                android.util.Log.w("DoubanSyncManager", "processStatusChanges failed for ${item.doubanId}: ${e.message}")
            }
        }
        return successCount
    }

    /**
     * 完全重写主流程(模式 C)。
     *
     * 1. 读 douban_synced_items 全表,按 status + mediaType 分组
     * 2. **保存回滚快照**到 douban_sync_rollback 表(同步失败时用于恢复)
     * 3. 批量 removeFromWatchlist + removeFromWatched
     * 4. 清空 douban_synced_items 表
     * 5. 走 forceOverwrite=true 的同步流程
     * 6. 同步成功 → 清除回滚表;同步失败/取消 → 保留回滚表(下次启动提示恢复)
     */
    private suspend fun runSyncFullRewrite() {
        val creds = doubanAuthStorage.getCredentials()
            ?: run {
                _progress.value = DoubanSyncProgress(isComplete = true, phase = "未登录豆瓣")
                return
            }

        // 豆瓣独立模式:跳过 trakt batch remove + rollback,直接清本地表 + 重新同步
        // (未连 trakt,batchRemoveFromWatchlist/Watched 会失败;豆瓣模式全量重写=清本地表+重爬豆瓣)
        if (isDoubanMode()) {
            val startTime = System.currentTimeMillis()
            _progress.value = DoubanSyncProgress(
                isRunning = true, startTimeMs = startTime,
                phase = "清空本地标记", isRetry = false, currentTitle = null
            )
            doubanSyncedItemDao.clearAll()
            _progress.value = _progress.value.copy(phase = "重新应用豆瓣状态")
            runSyncLegacy(forceOverwrite = true)
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

        // 保存回滚快照:记录即将删除的标记,同步失败时用于恢复
        val rollbackItems = allSynced.mapNotNull { item ->
            val traktId = item.traktId ?: return@mapNotNull null
            DoubanSyncRollbackEntity(
                doubanId = item.doubanId,
                traktId = traktId,
                title = item.title,
                status = item.status,
                mediaType = item.mediaType,
                rating = item.rating,
                rollbackAt = System.currentTimeMillis()
            )
        }
        if (rollbackItems.isNotEmpty()) {
            doubanSyncRollbackDao.replaceAll(rollbackItems)
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

        // 同步成功(未取消且 phase 为"同步完成")→ 清除回滚表
        // 同步取消/失败 → 保留回滚表,下次启动提示用户恢复
        if (!cancelled && _progress.value.phase == "同步完成") {
            doubanSyncRollbackDao.clearAll()
        }
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
        // 豆瓣模式跳过 trakt watchlist 缓存加载（无 trakt 连接，加载会失败）
        val isDoubanMode = isDoubanMode()
        if (!isDoubanMode) {
            traktRepository.loadWatchlistWatchedIds()
        }
        val watchlistWatchedIds = if (!isDoubanMode) traktRepository.getWatchlistWatchedIds() else null

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
            isRetry = false,
            isCancelling = _progress.value.isCancelling
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
        // 豆瓣模式跳过 trakt watchlist 缓存加载（无 trakt 连接，加载会失败）
        val isDoubanMode = isDoubanMode()
        if (!isDoubanMode) {
            traktRepository.loadWatchlistWatchedIds()
        }
        val watchlistWatchedIds = if (!isDoubanMode) traktRepository.getWatchlistWatchedIds() else null

        // 加载已同步记录(重试成功的项要插入到这个表)
        val syncedIds = doubanSyncedItemDao.getAllSyncedDoubanIds().toSet()

        // 按 selectedReasons 过滤,转回 DoubanMarkItem 用于复用 syncBatchToTrakt
        val filteredFailures = failures.filter { it.failureReason in selectedReasons }

        // 数据一致性修复:清理已同步表中的失败项。
        // 这些失败项的 doubanId 已经在 douban_synced_items 表中(曾经同步成功过,常见于
        // 从云端拉取 synced_items 后与本地 failures 表重合),不需要重试。
        // 从 douban_sync_failures 表删除,避免下次重试时 pending 为空导致统计全为 0。
        val (alreadySyncedFailures, toRetryFailures) = filteredFailures.partition { it.doubanId in syncedIds }
        for (failure in alreadySyncedFailures) {
            doubanSyncFailureDao.deleteByDoubanId(failure.doubanId)
        }

        // 按 status 分组重试(wish/collect 分别走)
        val allStillFailed = mutableListOf<DoubanSyncFailure>()
        val allRetrySuccess = mutableListOf<String>()  // 重试成功的 doubanId
        var totalSuccess = 0
        var totalSkipped = alreadySyncedFailures.size  // 已同步表跳过的失败项
        var totalCacheHit = 0
        val recentFailuresBuffer = ArrayDeque<DoubanSyncFailure>()

        for (status in listOf(DoubanMarkStatus.WISH, DoubanMarkStatus.COLLECT)) {
            if (cancelled) break
            val statusFailures = toRetryFailures.filter { it.status == status }
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
            // 使用 BatchSyncResult.successDoubanIds(阶段 4 实际写入 Trakt 的项)做精确计数,
            // 避免 items - result.failed 推算时把阶段 4 写入失败的项误判为成功。
            allRetrySuccess.addAll(result.successDoubanIds)
            allStillFailed.addAll(result.failed)
            totalSuccess += result.success
            totalCacheHit += result.cacheHit
            totalSkipped += result.skipped
        }

        // 从 douban_sync_failures 表删除重试成功的项
        for (doubanId in allRetrySuccess) {
            doubanSyncFailureDao.deleteByDoubanId(doubanId)
        }

        // 仍然失败的项:更新 attemptCount + failureReason,用 REPLACE 覆盖回表
        // (重试模式:不删除未重试的失败项)
        persistFailures(allStillFailed, isRetry = true)

        // 先前用 items - result.failed 推算 allRetrySuccess,但 failed 仅含各阶段显式捕获的失败,
        // 阶段 4 写入失败的项会被错误计入 success → 误删仍有失败的记录。
        // 改用 BatchSyncResult.successDoubanIds(阶段 4 实际写入 Trakt 的项)做精确删除。
        val finalProgress = DoubanSyncProgress(
            isRunning = false,
            isComplete = true,
            current = _progress.value.current,
            total = _progress.value.total,
            successCount = totalSuccess,
            failedCount = allStillFailed.size,
            skippedCount = totalSkipped,
            cacheHitCount = totalCacheHit,
            failedItems = allStillFailed,
            phase = if (cancelled) "已取消" else "重试完成",
            startTimeMs = startTime,
            etaSeconds = 0,
            recentFailures = recentFailuresBuffer.toList(),
            isRetry = true,
            isCancelling = _progress.value.isCancelling
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
    /**
     * 持久化失败项到 douban_sync_failures 表。
     *
     * 正常同步流程:对 WISH 和 COLLECT 两个 status 都执行 replaceByStatus,
     * 即使 failures 为空也清空对应 status 的旧失败项记录
     * (否则上次同步的失败项在本次成功后仍然保留在表中)。
     *
     * 重试流程:只覆盖仍然失败的项,不删除未重试的失败项。
     *
     * @param failures 失败项列表
     * @param isRetry true=重试模式,用 REPLACE 语义覆盖(不删除未重试项);
     *                false=正常同步,按 status 覆盖(清空处理过的 status 的旧记录)
     */
    private suspend fun persistFailures(
        failures: List<DoubanSyncFailure>,
        isRetry: Boolean = false
    ) {
        if (isRetry) {
            // 重试模式:用 REPLACE 语义覆盖仍然失败的项,不影响未重试的项
            if (failures.isNotEmpty()) {
                doubanSyncFailureDao.insertAll(failures.map { it.toEntity() })
            }
        } else {
            // 正常同步:对两个 status 都执行 replaceByStatus
            // 即使 failures 为空也清空对应 status 的旧失败项记录
            val byStatus = failures.groupBy { it.status }
            for (status in listOf(DoubanMarkStatus.WISH, DoubanMarkStatus.COLLECT)) {
                val items = byStatus[status] ?: emptyList()
                doubanSyncFailureDao.replaceByStatus(status.path, items.map { it.toEntity() })
            }
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
        // 状态一致性检查（前移到上传之前）：统一豆瓣与 Trakt 状态并回写本地表，
        // 确保上传到云端的 synced_items.json 中的 status 是统一后的值
        runCatching {
            val result = statusConsistencyChecker.checkAndUnify()
            android.util.Log.i("DoubanSync", "同步后状态一致性检查: $result")
        }
        // 上传个人数据（含统一后的 status）
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
        // 从全局池批量填充本地未标注类型的失败项（其他用户已标注的类型）
        runCatching { fillMediaTypeFromCloudPool() }
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
     * 从全局详情池批量填充本地未标注类型（mediaType IS NULL）的失败项。
     *
     * 同步完成后调用：查询本地所有 mediaType 为 null 的失败项 doubanId，
     * 从全局池批量下载,仅填充 entry.mediaType 非 null 的条目(其他用户已标注),
     * 为 null 时跳过(可能是用户主动清除,不降级用 isTvShow 映射,避免覆盖清除操作)。
     * 仅更新 null → 非 null(不覆盖用户已手动标注的值)。
     *
     * 这样其他用户已标注类型的条目，本机用户无需再手动标注。
     * 失败不阻塞主流程。
     */
    private suspend fun fillMediaTypeFromCloudPool() {
        val nullIds = doubanSyncFailureDao.getDoubanIdsWithNullMediaType()
        if (nullIds.isEmpty()) return
        val pooled = cloudDetailsPoolManager.downloadDetails(nullIds)
        if (pooled.isEmpty()) return
        for ((doubanId, entry) in pooled) {
            val mediaType = entry.mediaType ?: continue
            doubanSyncFailureDao.updateMediaTypeIfNull(doubanId, mediaType)
        }
    }

    /**
     * 豆瓣标记时间(yyyy-MM-dd) → Trakt watched_at/rated_at 用的 ISO 8601 UTC 字符串。
     *
     * 直接输出当日 UTC 00:00:00,避免本地→UTC 转时区时 UTC+13/+14 跨日前一日。
     * 解析失败或空串返回 null(Trakt 会用服务端当前时间兜底)。
     */
    private fun markedAtToIso(markedAt: String): String? {
        if (markedAt.isBlank()) return null
        return runCatching {
            val outputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            outputFormat.timeZone = TimeZone.getTimeZone("UTC")
            // 在设备当前时区把 "yyyy-MM-dd" 解析为当天 00:00,再偏移 +12h 锚定到本地正午,
            // 转成 UTC 后,在用户自身时区回看仍是同一天(避免 UTC+13/+14 等跨日, #10)
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(markedAt) ?: return null
            val cal = java.util.Calendar.getInstance()
            cal.time = parsed
            cal.add(java.util.Calendar.HOUR_OF_DAY, 12)
            outputFormat.format(cal.time)
        }.getOrNull()
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
        // 拉取个人数据(synced/pending/mappings/meta)
        val result = runCatching { cloudPersonalSyncManager.downloadAndMerge() }
            .getOrElse { CloudPersonalSyncManager.PullResult() }
        // 同时拉取云端失败数据(A 手机上传的失败项合并到本地,供增量同步跳过已知失败项)
        runCatching { cloudFailureSyncManager.downloadAndMerge() }
        return result
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
        val inferredMediaType: String? = null,  // 从详情推断的细分类型(movie/show/variety/documentary),用于失败项标注
        val originalFailure: DoubanSyncFailure? = null  // 重试模式下传入,用于 attemptCount 累加
    )

    /**
     * 豆瓣独立模式单条解析结果。
     *
     * 与 [SyncResolve] 区别: traktId/tmdbId 均可空（豆瓣模式不要求 traktId，
     * searchByImdb 失败时仍写本地表，traktId/tmdbId 留 null 供未来回写钩子使用）。
     */
    private data class DoubanLocalResolve(
        val item: DoubanMarkItem,
        val imdbId: String,
        val mediaType: MediaType,
        val traktId: Int? = null,
        val tmdbId: Int? = null,
        val detail: DoubanDetailInfo? = null
    )

    private data class BatchSyncResult(
        val success: Int,
        val failed: List<DoubanSyncFailure>,
        val skipped: Int = 0,
        val cacheHit: Int = 0,
        // 明确成功的 doubanId 集合(仅含阶段 4 实际写入 Trakt 的项,避免从 items-failed 推断时遗漏阶段 1~3 的失败)
        val successDoubanIds: Set<String> = emptySet()
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
        existingFailures: List<DoubanSyncFailure>? = null,
        skipFailuresIds: Set<String> = emptySet()
    ): BatchSyncResult {
        // 豆瓣独立模式：跳过 trakt 写入，改写本地 douban_synced_items 扩展表（含 TMDB 富化字段）
        if (isDoubanMode()) {
            return syncBatchToDoubanLocal(
                items = items,
                status = status,
                cookie = cookie,
                syncedIds = syncedIds,
                onProgress = onProgress,
                existingFailures = existingFailures,
                skipFailuresIds = skipFailuresIds
            )
        }

        val failed = mutableListOf<DoubanSyncFailure>()
        val existingMap = existingFailures?.associateBy { it.doubanId } ?: emptyMap()

        // 跳过已同步 + 已知失败项(增量同步场景:不可恢复失败 + 重试超限的可恢复失败)
        val pending = items.filter { it.doubanId !in syncedIds && it.doubanId !in skipFailuresIds }
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
                            val failure = buildFailure(item, status, FailureReason.NO_IMDB_ID, existingMap, inferMediaTypeFromDetail(detail))
                            synchronized(failed) { failed.add(failure) }
                            val done = completedCount.incrementAndGet()
                            onProgress(done, "详情页", 0, item.title, failure)
                            return@async
                        }
                        val mediaType = if (detail.isTvShow) MediaType.SHOW else MediaType.MOVIE
                        val inferredType = inferMediaTypeFromDetail(detail)
                        detailChannel.send(SyncResolve(item, imdbId, traktId = 0, mediaType = mediaType, inferredMediaType = inferredType, originalFailure = existingMap[item.doubanId]))
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
                            val failure = buildFailure(r.item, status, FailureReason.TRAKT_NOT_FOUND, existingMap, r.inferredMediaType)
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
        // COLLECT(看过) 带观看时间,传给 Trakt watched_at(豆瓣标记时间,非同步执行时间)
        val collectMovieItems = mutableListOf<Pair<Int, String?>>()
        val collectShowItems = mutableListOf<Pair<Int, String?>>()
        val removeFromWatchlistMovieIds = mutableListOf<Int>()
        val removeFromWatchlistShowIds = mutableListOf<Int>()
        // 评分带 rated_at(豆瓣标记时间)
        val movieRatings = mutableListOf<Triple<Int, Int, String?>>()
        val showRatings = mutableListOf<Triple<Int, Int, String?>>()
        val batchToInsert = mutableListOf<DoubanSyncedItem>()
        // 记录成功写入 Trakt 的 doubanId(初始为全部 withTraktId,阶段 4 失败时移除)
        val successDoubanIds = withTraktId.map { it.item.doubanId }.toMutableSet()

        for (r in withTraktId) {
            val isInWatchlist = watchlistWatchedIds?.isInWatchlist(r.traktId, null, r.mediaType) == true
            val isWatched = watchlistWatchedIds?.isWatched(r.traktId, null, r.mediaType) == true
            // 豆瓣标记时间 → ISO 8601 UTC(本地 12:00 转 UTC,避免跨日)
            val watchedAtIso = markedAtToIso(r.item.markedAt)

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
                            MediaType.MOVIE -> collectMovieItems.add(r.traktId to watchedAtIso)
                            MediaType.SHOW -> collectShowItems.add(r.traktId to watchedAtIso)
                            else -> {}
                        }
                    }
                }
            }

            if (r.item.rating != null && r.item.rating > 0) {
                val traktRating = r.item.rating * 2
                when (r.mediaType) {
                    MediaType.MOVIE -> movieRatings.add(Triple(r.traktId, traktRating, watchedAtIso))
                    MediaType.SHOW -> showRatings.add(Triple(r.traktId, traktRating, watchedAtIso))
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
        var writeFailedCount = 0
        try {
            withTimeout(60_000L) {
                when (status) {
                    DoubanMarkStatus.WISH -> {
                        traktRepository.batchAddToWatchlist(wishMovieIds, wishShowIds)
                    }
                    DoubanMarkStatus.COLLECT -> {
                        traktRepository.batchRemoveFromWatchlist(removeFromWatchlistMovieIds, removeFromWatchlistShowIds)
                        traktRepository.batchMarkAsWatchedAt(collectMovieItems, collectShowItems)
                    }
                }
                traktRepository.batchAddRatingsAt(movieRatings, showRatings)

                if (batchToInsert.isNotEmpty()) {
                    doubanSyncedItemDao.insertAll(batchToInsert)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            writeFailedCount = withTraktId.size
            val timeoutFailures = withTraktId.map { r ->
                buildFailure(r.item, status, FailureReason.TRAKT_WRITE_TIMEOUT, existingMap)
            }
            synchronized(failed) { failed.addAll(timeoutFailures) }
            successDoubanIds.clear()
        } catch (e: Exception) {
            writeFailedCount = withTraktId.size
            val writeFailures = withTraktId.map { r ->
                buildFailure(r.item, status, FailureReason.TRAKT_WRITE_FAILED, existingMap)
            }
            synchronized(failed) { failed.addAll(writeFailures) }
            successDoubanIds.clear()
        }

        // successCount = 成功写入 Trakt 的数量 = 有 Trakt ID 的数量 - 写入失败的数量
        // (failed 列表包含阶段1详情页失败项,不能直接用 withTraktId.size - failed.size)
        val successCount = withTraktId.size - writeFailedCount
        return BatchSyncResult(success = successCount, failed = failed, skipped = skippedCount, cacheHit = cacheHit, successDoubanIds = successDoubanIds.toSet())
    }

    /**
     * 豆瓣独立模式批量同步（不写 trakt，改写本地 douban_synced_items 扩展表）。
     *
     * 流程:
     * 1. 同步开始时重试上次乐观更新失败的豆瓣 API 标记（[retryPendingSyncItems]，幂等）
     * 2. 过滤已同步条目（断点续传，与 trakt 模式一致）
     * 3. 并发爬详情页拿 imdbId（并发度 3，优先查持久化缓存 + 全局详情池）
     * 4. 并发 searchByImdb 拿 traktId + tmdbId（并发度 5，traktId 可空，查不到不记失败）
     * 5. 用 tmdbId 调 TmdbRepository 富化拿中文标题/海报/年份/类型（失败字段留 null）
     * 6. 构造扩展后的 DoubanSyncedItem（含富化字段 + listedAt=豆瓣标记时间 + pendingSync=false）
     *    写本地 douban_synced_items 表，**跳过** batchAddToWatchlist/batchMarkAsWatched
     *
     * 与 [syncBatchToTrakt] 的区别:
     * - 阶段 4 traktId 查不到不记失败（traktId 仅用于 TMDB 富化和未来回写钩子）
     * - 阶段 5 新增 TMDB 富化（enrichMovie/enrichTv）
     * - 阶段 6 写本地表，跳过 trakt API 写入
     *
     * traktId 仍调 searchByImdb 获取（走网关），用于 TMDB 富化和未来 trakt 回写钩子。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun syncBatchToDoubanLocal(
        items: List<DoubanMarkItem>,
        status: DoubanMarkStatus,
        cookie: String,
        syncedIds: Set<String>,
        onProgress: (current: Int, subPhase: String, cacheHitDelta: Int, currentTitle: String?, recentFailure: DoubanSyncFailure?) -> Unit,
        existingFailures: List<DoubanSyncFailure>? = null,
        skipFailuresIds: Set<String> = emptySet()
    ): BatchSyncResult {
        // 同步开始时重试上次乐观更新失败的豆瓣 API 标记（幂等：pending 为空时直接返回）
        runCatching { retryPendingSyncItems(cookie) }

        val failed = mutableListOf<DoubanSyncFailure>()
        val existingMap = existingFailures?.associateBy { it.doubanId } ?: emptyMap()

        // 跳过已同步 + 已知失败项（与 trakt 模式一致）
        val pending = items.filter { it.doubanId !in syncedIds && it.doubanId !in skipFailuresIds }
        val skippedCount = items.size - pending.size

        if (pending.isEmpty()) {
            onProgress(items.size, "断点续传跳过", 0, null, null)
            return BatchSyncResult(0, emptyList(), skippedCount, 0)
        }

        // 全局详情池前置查询（与 trakt 模式一致，减少豆瓣爬取）
        runCatching {
            val pendingDoubanIds = pending.map { it.doubanId }
            cloudDetailsPoolManager.fetchAndMergeToLocal(pendingDoubanIds, doubanDetailCache)
        }

        // 阶段 1(详情页) → Channel → 阶段 2(searchByImdb 拿 traktId+tmdbId)
        val detailChannel = Channel<DoubanLocalResolve>(capacity = pending.size)
        val detailSemaphore = Semaphore(3)
        val traktSemaphore = Semaphore(5)
        val completedCount = AtomicInteger(0)
        val detailCacheHit = AtomicInteger(0)
        val resolvedList = mutableListOf<DoubanLocalResolve>()

        coroutineScope {
            // ===== 阶段 1: 详情页爬取(生产者,并发度 3) =====
            val detailJobs = pending.map { item ->
                async {
                    if (cancelled) return@async
                    try {
                        val (detail, isCacheHit) = detailSemaphore.withPermit {
                            if (cancelled) return@withPermit Pair<DoubanDetailInfo?, Boolean>(null, false)
                            doubanRepository.fetchDetail(item.doubanUrl, cookie, item.title) { _, _ -> }
                        }
                        if (isCacheHit) detailCacheHit.incrementAndGet()
                        else dirtyDetailIds.add(item.doubanId)
                        if (detail == null) {
                            val failure = buildFailure(item, status, FailureReason.DETAIL_FETCH_FAILED, existingMap)
                            synchronized(failed) { failed.add(failure) }
                            val done = completedCount.incrementAndGet()
                            onProgress(done, "详情页", 0, item.title, failure)
                            return@async
                        }
                        val imdbId = detail.imdbId
                        if (imdbId.isNullOrEmpty()) {
                            val failure = buildFailure(item, status, FailureReason.NO_IMDB_ID, existingMap, inferMediaTypeFromDetail(detail))
                            synchronized(failed) { failed.add(failure) }
                            val done = completedCount.incrementAndGet()
                            onProgress(done, "详情页", 0, item.title, failure)
                            return@async
                        }
                        val mediaType = if (detail.isTvShow) MediaType.SHOW else MediaType.MOVIE
                        detailChannel.send(DoubanLocalResolve(item, imdbId, mediaType))
                    } catch (e: Exception) {
                        val failure = buildFailure(item, status, FailureReason.DETAIL_FETCH_FAILED, existingMap)
                        synchronized(failed) { failed.add(failure) }
                        val done = completedCount.incrementAndGet()
                        onProgress(done, "详情页", 0, item.title, failure)
                    }
                }
            }

            // ===== 阶段 2: searchByImdb 拿 traktId + tmdbId(消费者,并发度 5) =====
            // 豆瓣模式: traktId 查不到不记失败（仅用于 TMDB 富化和未来回写钩子）
            val traktJobs = (1..5).map {
                async {
                    for (r in detailChannel) {
                        if (cancelled) break
                        // searchByImdb 内部有持久化缓存（命中秒回，未命中走网关网络）
                        val searchResult = traktSemaphore.withPermit {
                            if (cancelled) null
                            else try {
                                traktRepository.searchByImdb(r.imdbId, r.mediaType).getOrNull()
                            } catch (e: Exception) { null }
                        }
                        var traktId: Int? = null
                        var tmdbId: Int? = null
                        if (searchResult != null) {
                            for (result in searchResult) {
                                val ids = when (r.mediaType) {
                                    MediaType.MOVIE -> result.movie?.ids
                                    MediaType.SHOW -> result.show?.ids
                                    else -> null
                                }
                                if (ids != null && ids.trakt > 0) {
                                    traktId = ids.trakt
                                    if (ids.tmdb > 0) tmdbId = ids.tmdb
                                    break
                                }
                            }
                        }
                        synchronized(resolvedList) {
                            resolvedList.add(r.copy(traktId = traktId, tmdbId = tmdbId))
                        }
                        val done = completedCount.incrementAndGet()
                        onProgress(done, "Trakt 查询", 0, r.item.title, null)
                    }
                }
            }

            detailJobs.awaitAll()
            detailChannel.close()
            traktJobs.awaitAll()
        }

        if (cancelled) return BatchSyncResult(0, failed, skippedCount, detailCacheHit.get())

        val cacheHit = detailCacheHit.get()
        if (cacheHit > 0) {
            onProgress(completedCount.get(), "详情页", cacheHit, null, null)
        }

        // ===== 阶段 3+4: TMDB 富化 + 写本地表（跳过 trakt 写入） =====
        onProgress(resolvedList.size, "写入本地", 0, null, null)
        if (cancelled) return BatchSyncResult(0, failed, skippedCount, cacheHit)

        val batchToInsert = mutableListOf<DoubanSyncedItem>()
        val successDoubanIds = mutableSetOf<String>()
        for (r in resolvedList) {
            // TMDB 富化（如果 tmdbId 可用，enrichMovie/enrichTv 内部有永久缓存）
            var displayTitle: String? = null
            var year: Int? = null
            var genres: String? = null
            var posterUrl: String? = null
            val tmdbId = r.tmdbId
            if (tmdbId != null && tmdbId > 0) {
                try {
                    when (r.mediaType) {
                        MediaType.MOVIE -> {
                            val enriched = tmdbRepository.enrichMovie(tmdbId, r.item.title, null)
                            displayTitle = enriched.chineseTitle.ifBlank { null }
                            year = enriched.year
                            genres = enriched.genres.ifBlank { null }
                            posterUrl = enriched.posterUrl
                        }
                        MediaType.SHOW -> {
                            val enriched = tmdbRepository.enrichTv(tmdbId, r.item.title, null)
                            displayTitle = enriched.chineseTitle.ifBlank { null }
                            year = enriched.year
                            genres = enriched.genres.ifBlank { null }
                            posterUrl = enriched.posterUrl
                        }
                        else -> {}
                    }
                } catch (e: Exception) {
                    // TMDB 富化失败不阻断,字段留 null
                    android.util.Log.w("DoubanSync", "TMDB enrich failed for tmdbId=$tmdbId: ${e.message}")
                }
            }
            // 富化失败时用豆瓣列表的 posterUrl/title 兜底
            if (posterUrl == null) posterUrl = r.item.posterUrl
            if (displayTitle == null) displayTitle = r.item.title

            batchToInsert.add(
                DoubanSyncedItem(
                    doubanId = r.item.doubanId,
                    imdbId = r.imdbId,
                    traktId = r.traktId,
                    title = r.item.title,
                    status = status.path,
                    rating = r.item.rating,
                    syncedAt = System.currentTimeMillis(),
                    mediaType = r.mediaType.name.lowercase(),
                    tmdbId = r.tmdbId,
                    displayTitle = displayTitle,
                    year = year,
                    genres = genres,
                    posterUrl = posterUrl,
                    listedAt = r.item.markedAt,  // 豆瓣标记时间（区别于 syncedAt 同步时间）
                    pendingSync = false  // 主同步成功,无待重试标记
                )
            )
            successDoubanIds.add(r.item.doubanId)
        }

        // 写本地表（跳过 batchAddToWatchlist / batchMarkAsWatched）
        var writeFailedCount = 0
        try {
            if (batchToInsert.isNotEmpty()) {
                doubanSyncedItemDao.insertAll(batchToInsert)
            }
        } catch (e: Exception) {
            writeFailedCount = resolvedList.size
            val writeFailures = resolvedList.map { r ->
                buildFailure(r.item, status, FailureReason.TRAKT_WRITE_FAILED, existingMap)
            }
            synchronized(failed) { failed.addAll(writeFailures) }
            successDoubanIds.clear()
        }

        val successCount = resolvedList.size - writeFailedCount
        return BatchSyncResult(
            success = successCount,
            failed = failed,
            skipped = skippedCount,
            cacheHit = cacheHit,
            successDoubanIds = successDoubanIds.toSet()
        )
    }

    /**
     * 豆瓣模式: 重试上次乐观更新失败的豆瓣 API 标记。
     *
     * 场景: 用户在豆瓣模式下标记想看/看过时,本地表先乐观更新(pendingSync=true),
     * 豆瓣 API 调用失败时保留 pendingSync=true。下次同步开始时调用本方法重试。
     *
     * 成功后清除 pendingSync 标记; 失败保留,下次再试。幂等: pending 为空时直接返回。
     */
    private suspend fun retryPendingSyncItems(cookie: String) {
        val pending = doubanSyncedItemDao.getPendingSyncItems()
        if (pending.isEmpty()) return
        for (item in pending) {
            if (cancelled) break
            try {
                val ok = when (item.status) {
                    "wish" -> doubanRepository.markWish(item.doubanId, cookie)
                    "collect" -> doubanRepository.markCollect(item.doubanId, cookie, item.rating)
                    else -> false
                }
                if (ok) {
                    doubanSyncedItemDao.clearPendingSync(item.doubanId)
                }
            } catch (e: Exception) {
                // 重试失败,保留 pendingSync=true,下次同步再试
                android.util.Log.w("DoubanSync", "retryPendingSyncItems failed for ${item.doubanId}: ${e.message}")
            }
        }
    }

    /**
     * 从豆瓣详情推断细分媒体类型(与 DoubanRetryManager.inferMediaTypeFromDetail 推断逻辑一致)。
     *
     * - genres 含"综艺"/"真人秀"/"脱口秀"/"音乐" → "variety"
     * - genres 含"纪录片" → "documentary"
     * - episodeCount > 0 → "show"
     * - 否则 → "movie"
     *
     * 同步爬取详情页时调用,让失败项在写入表时即带有推断的类型,无需等用户打开详情页。
     */
    private fun inferMediaTypeFromDetail(detail: DoubanDetailInfo): String {
        return when {
            detail.genres.any { it.contains("综艺") || it.contains("真人秀") || it.contains("脱口秀") || it.contains("音乐") } -> "variety"
            detail.genres.any { it.contains("纪录片") } -> "documentary"
            detail.episodeCount != null && detail.episodeCount > 0 -> "show"
            else -> "movie"
        }
    }

    /** 构造失败项。重试模式下累加 attemptCount,同步模式下 attemptCount=0。inferredMediaType 仅在已有 mediaType 为 null 时填充。 */
    private fun buildFailure(
        item: DoubanMarkItem,
        status: DoubanMarkStatus,
        reason: FailureReason,
        existingMap: Map<String, DoubanSyncFailure>,
        inferredMediaType: String? = null
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
            // 保留用户之前标注的 updatedAt,避免重新构建失败项时丢失云同步时间戳
            updatedAt = existing?.updatedAt ?: 0L,
            attemptCount = (existing?.attemptCount ?: 0) + 1,
            // 保留用户已标注的 mediaType,仅当为 null 时用爬取详情推断的类型填充
            mediaType = existing?.mediaType ?: inferredMediaType,
            mediaTypeCleared = existing?.mediaTypeCleared ?: false,
            subtitle = existing?.subtitle
        )
    }
}
