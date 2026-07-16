package com.tracktosearch.data.repository

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.LastConsistencyCheckStorage
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DelayInfo
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.remote.douban.DoubanRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 豆瓣与 Trakt 状态统一检查结果（兼进度数据类）
 */
data class ConsistencyCheckResult(
    val isRunning: Boolean = false,
    val phase: String = "",           // "爬取豆瓣列表" / "对比状态" / "更新Trakt" / "更新豆瓣" / "完成"
    val subPhase: String = "",        // "想看列表" / "已看列表" / "详情页拿ck" 等
    val current: Int = 0,
    val total: Int = 0,
    val currentTitle: String? = null,  // 当前处理的条目标题
    val totalChecked: Int = 0,
    val conflictsFound: Int = 0,
    val doubanUpdated: Int = 0,
    val traktUpdated: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
    val delayInfo: DelayInfo? = null,  // 豆瓣反爬延迟信息
    val cookieExpired: Boolean = false,
    val isComplete: Boolean = false,
    val startTimeMs: Long = 0,
    val isCancelling: Boolean = false,  // true=用户已点击取消,正在停止中的中间态
    val isCancelled: Boolean = false    // true=检查已被用户取消(区分正常完成与取消,避免 WatchlistScreen 自动弹窗)
)

/**
 * 豆瓣与 Trakt 影视状态一致性检查器。
 *
 * 统一原则：**已看过优先于想看**
 *
 * | 豆瓣状态 | Trakt 状态 | 统一后 | 操作 |
 * |---------|-----------|--------|------|
 * | 已看(collect) | 想看 | 已看 | Trakt 标记已看（移除想看） |
 * | 想看(wish) | 已看 | 已看 | 豆瓣标记已看（升级） |
 * | 已看 | 未标记 | 已看 | Trakt 标记已看 |
 * | 想看 | 未标记 | 想看 | Trakt 标记想看 |
 * | 未标记 | 已看 | 已看 | 豆瓣标记已看 |
 * | 未标记 | 想看 | 想看 | 豆瓣标记想看 |
 * | 已看 | 已看 | 已看 | 无操作 |
 * | 想看 | 想看 | 想看 | 无操作 |
 *
 * 两类检查入口：
 * - [checkAndUnify]：同步后自动触发，只读本地表快照（秒级）
 * - [checkAndUnifyWithCrawl]：设置页手动触发，先爬豆瓣列表拿最新状态（分钟级）
 */
@Singleton
class DoubanTraktStatusConsistencyChecker @Inject constructor(
    private val doubanSyncedItemDao: DoubanSyncedItemDao,
    private val traktRepository: TraktRepository,
    private val doubanRepository: DoubanRepository,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val lastConsistencyCheckStorage: LastConsistencyCheckStorage,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "StatusConsistency"
        /** 豆瓣标记并发度（单条操作+反爬延迟，控制并发避免封禁） */
        private const val DOUBAN_CONCURRENCY = 2
    }

    private val _checkProgress = MutableStateFlow(ConsistencyCheckResult())
    val checkProgress: StateFlow<ConsistencyCheckResult> = _checkProgress.asStateFlow()

    /** Application scope：手动检查协程在此运行，Activity/Service 销毁不影响 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var checkJob: Job? = null

    @Volatile
    private var cancelled = false

    /** WakeLock:手动检查期间保持 CPU 唤醒,避免息屏后网络请求 timeout */
    private var wakeLock: PowerManager.WakeLock? = null

    init {
        // 监听 DoubanRepository 的延时事件，合并到 checkProgress.delayInfo
        appScope.launch {
            doubanRepository.delayEvent.collect { info ->
                _checkProgress.value = _checkProgress.value.copy(delayInfo = info)
            }
        }
    }

    /** 检查是否在运行中 */
    fun isRunning(): Boolean = checkJob?.isActive == true

    /** 取消正在进行的检查 */
    fun cancel() {
        cancelled = true
        checkJob?.cancel()
        // 同步更新 isCancelling 中间态:UI 立即禁用取消按钮 + 显示"正在取消..." 文案
        _checkProgress.value = _checkProgress.value.copy(
            isCancelling = true,
            phase = "正在取消...",
            subPhase = "",
            delayInfo = null
        )
    }

    /** 重置进度状态（完成后调用，避免 StateFlow 旧值 isComplete=true 导致重复弹窗） */
    fun resetProgress() {
        if (isRunning()) return  // 运行中不重置
        _checkProgress.value = ConsistencyCheckResult()
    }

    /**
     * 同步后自动触发：仅读本地表快照对比（秒级，不爬豆瓣列表）。
     *
     * 遍历 douban_synced_items 表所有记录，对比豆瓣 status 与 Trakt 侧状态，
     * 按「已看过优先于想看」原则统一。不更新 checkProgress（静默执行）。
     *
     * @return 检查结果
     */
    suspend fun checkAndUnify(): ConsistencyCheckResult = withContext(Dispatchers.IO) {
        val allItems = runCatching { doubanSyncedItemDao.getAllSyncedItems() }.getOrDefault(emptyList())
        if (allItems.isEmpty()) {
            return@withContext ConsistencyCheckResult(isComplete = true)
        }

        // 确保 Trakt 侧 watchlistWatchedIds 已加载
        runCatching { traktRepository.loadWatchlistWatchedIds() }
        val watchedIds = traktRepository.getWatchlistWatchedIds()

        val classifyResult = classifyConflicts(allItems, watchedIds)
        val traktNeedWatched = classifyResult.traktNeedWatched
        val traktNeedWatchlist = classifyResult.traktNeedWatchlist
        val doubanNeedUpdate = classifyResult.doubanNeedUpdate
        val conflicts = classifyResult.conflicts
        val skipped = classifyResult.skipped
        val traktConflictRemoveFromWatchlist = classifyResult.traktConflictRemoveFromWatchlist

        // 1. 批量更新 Trakt 侧
        val traktUpdated = batchUpdateTrakt(traktNeedWatched, traktNeedWatchlist, traktConflictRemoveFromWatchlist)

        // 2. 逐条更新豆瓣侧
        val (doubanUpdated, errors) = batchUpdateDouban(doubanNeedUpdate)

        val result = ConsistencyCheckResult(
            totalChecked = allItems.size,
            conflictsFound = conflicts,
            doubanUpdated = doubanUpdated,
            traktUpdated = traktUpdated,
            skipped = skipped,
            errors = errors,
            isComplete = true
        )
        // 记录检查完成时间（供设置页二次确认弹窗显示）
        runCatching { lastConsistencyCheckStorage.recordCheck() }
        Log.i(TAG, "状态一致性检查完成（本地表对比）: $result")
        result
    }

    /**
     * 设置页手动触发：先爬豆瓣列表拿最新状态后对比（分钟级）。
     *
     * 流程：
     * 1. 爬取豆瓣想看+已看列表 → 拿到最新豆瓣状态
     * 2. 对比 Trakt 缓存 → 分类冲突项
     * 3. 批量更新 Trakt 侧
     * 4. 逐条更新豆瓣侧（反爬延迟）
     * 5. 完成
     *
     * 在 appScope 中运行，不阻塞调用者。进度通过 checkProgress StateFlow 暴露。
     */
    fun checkAndUnifyWithCrawl(): Boolean {
        if (isRunning()) return false
        cancelled = false
        // 息屏时保持 CPU 唤醒,避免豆瓣爬取过程中网络请求 timeout
        acquireWakeLock()
        checkJob = appScope.launch {
            try {
                runCheckWithCrawl()
            } catch (e: CancellationException) {
                // 取消时更新进度状态,否则 isRunning 仍为 true 导致弹窗不响应
                // isCancelled=true 区分"取消"与"正常完成",避免 WatchlistScreen 自动弹出结果弹窗
                _checkProgress.value = _checkProgress.value.copy(
                    isRunning = false,
                    isComplete = true,
                    isCancelled = true,
                    phase = "已取消"
                )
                throw e
            } catch (e: Exception) {
                // 网络错误(DNS 解析失败、连接超时等)提供友好提示,不暴露原始异常信息
                val friendlyMsg = when {
                    e.message?.contains("resolve", ignoreCase = true) == true ||
                    e.message?.contains("address", ignoreCase = true) == true -> "网络连接失败,请检查网络后重试"
                    e.message?.contains("timeout", ignoreCase = true) == true -> "网络请求超时,请重试"
                    else -> "检查异常: ${e.message}"
                }
                _checkProgress.value = _checkProgress.value.copy(
                    isRunning = false,
                    isComplete = true,
                    phase = friendlyMsg
                )
            } finally {
                releaseWakeLock()
            }
        }
        return true
    }

    /** 获取 PARTIAL_WAKE_LOCK,保持 CPU 唤醒避免息屏后网络请求 timeout */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackToSearch:ConsistencyCheck")
        // 30 分钟超时,避免异常情况下 WakeLock 泄漏
        wakeLock?.acquire(30 * 60 * 1000L)
    }

    /** 释放 WakeLock */
    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private suspend fun runCheckWithCrawl() {
        _checkProgress.value = ConsistencyCheckResult(
            isRunning = true,
            startTimeMs = System.currentTimeMillis()
        )

        val cred = doubanAuthStorage.getCredentials()
        if (cred == null) {
            _checkProgress.value = ConsistencyCheckResult(
                isComplete = true,
                cookieExpired = true,
                phase = "豆瓣未登录"
            )
            return
        }

        // ========== 阶段1: 爬取豆瓣列表 ==========
        val latestDoubanStatuses = mutableMapOf<String, Pair<String, String>>() // doubanId → (status, title)

        for (status in listOf(DoubanMarkStatus.WISH, DoubanMarkStatus.COLLECT)) {
            if (cancelled) return
            val subPhaseName = if (status == DoubanMarkStatus.WISH) "想看列表" else "已看列表"
            _checkProgress.value = _checkProgress.value.copy(
                phase = "爬取豆瓣列表",
                subPhase = subPhaseName,
                current = 0,
                total = 0
            )

            val ok = doubanRepository.fetchMarkList(
                userId = cred.userId,
                cookie = cred.cookie,
                status = status,
                onPage = { items, _ ->
                    items.forEach { item ->
                        latestDoubanStatuses[item.doubanId] = status.path to item.title
                    }
                },
                onProgress = { cur, total ->
                    _checkProgress.value = _checkProgress.value.copy(
                        current = cur,
                        total = total ?: cur
                    )
                },
                isCancelled = { cancelled }
            )

            if (!ok) {
                _checkProgress.value = _checkProgress.value.copy(
                    isRunning = false,
                    isComplete = true,
                    cookieExpired = true,
                    phase = "豆瓣登录已过期"
                )
                return
            }
        }

        if (latestDoubanStatuses.isEmpty()) {
            _checkProgress.value = ConsistencyCheckResult(
                isComplete = true,
                totalChecked = 0,
                phase = "完成"
            )
            return
        }

        // ========== 阶段2: 对比状态 ==========
        if (cancelled) return
        _checkProgress.value = _checkProgress.value.copy(
            phase = "对比状态",
            subPhase = "",
            current = 0,
            total = latestDoubanStatuses.size
        )

        // 确保 Trakt 侧 watchlistWatchedIds 已加载
        runCatching { traktRepository.loadWatchlistWatchedIds() }
        val watchedIds = traktRepository.getWatchlistWatchedIds()

        // 从本地表查 traktId 映射（豆瓣ID → traktId + mediaType）
        val allSyncedItems = runCatching { doubanSyncedItemDao.getAllSyncedItems() }.getOrDefault(emptyList())
        val doubanIdToSyncedItem = allSyncedItems.associateBy { it.doubanId }

        val traktNeedWatched = mutableListOf<Pair<Int, MediaType>>()
        val traktNeedWatchlist = mutableListOf<Pair<Int, MediaType>>()
        val doubanNeedUpdate = mutableListOf<DoubanStatusUpdate>()
        var conflicts = 0
        var skipped = 0
        var checked = 0

        for ((doubanId, pair) in latestDoubanStatuses) {
            if (cancelled) return
            checked++
            val (status, title) = pair
            val doubanIsCollect = status == "collect"
            val doubanIsWish = status == "wish"

            val syncedItem = doubanIdToSyncedItem[doubanId]
            val traktId = syncedItem?.traktId
            if (traktId == null || traktId <= 0) {
                skipped++
                _checkProgress.value = _checkProgress.value.copy(
                    current = checked,
                    totalChecked = checked,
                    skipped = skipped,
                    currentTitle = title
                )
                continue
            }
            val mediaType = if (syncedItem.mediaType == "show") MediaType.SHOW else MediaType.MOVIE
            val traktIsWatched = watchedIds?.isWatched(traktId, null, mediaType) ?: false
            val traktIsInWatchlist = watchedIds?.isInWatchlist(traktId, null, mediaType) ?: false

            when {
                (doubanIsCollect && traktIsWatched) || (doubanIsWish && traktIsInWatchlist) -> {
                    // 一致，跳过
                }
                doubanIsCollect && traktIsInWatchlist -> {
                    conflicts++
                    traktNeedWatched.add(traktId to mediaType)
                }
                doubanIsCollect && !traktIsInWatchlist -> {
                    traktNeedWatched.add(traktId to mediaType)
                }
                doubanIsWish && traktIsWatched -> {
                    conflicts++
                    doubanNeedUpdate.add(DoubanStatusUpdate(doubanId, "collect", doubanId, title))
                }
                doubanIsWish -> {
                    traktNeedWatchlist.add(traktId to mediaType)
                }
                !doubanIsWish && traktIsWatched -> {
                    doubanNeedUpdate.add(DoubanStatusUpdate(doubanId, "collect", doubanId, title))
                }
                !doubanIsWish && traktIsInWatchlist -> {
                    doubanNeedUpdate.add(DoubanStatusUpdate(doubanId, "wish", doubanId, title))
                }
            }

            _checkProgress.value = _checkProgress.value.copy(
                current = checked,
                totalChecked = checked,
                conflictsFound = conflicts,
                skipped = skipped,
                currentTitle = title
            )
        }

        // ========== 阶段3: 批量更新 Trakt 侧 ==========
        if (cancelled) return
        _checkProgress.value = _checkProgress.value.copy(
            phase = "更新Trakt",
            subPhase = "",
            current = 0,
            total = traktNeedWatched.size + traktNeedWatchlist.size
        )
        val traktUpdated = batchUpdateTrakt(traktNeedWatched, traktNeedWatchlist)
        _checkProgress.value = _checkProgress.value.copy(
            traktUpdated = traktUpdated,
            current = traktUpdated,
            total = traktNeedWatched.size + traktNeedWatchlist.size
        )

        // ========== 阶段4: 逐条更新豆瓣侧 ==========
        if (cancelled) return
        _checkProgress.value = _checkProgress.value.copy(
            phase = "更新豆瓣",
            subPhase = "详情页拿ck",
            current = 0,
            total = doubanNeedUpdate.size
        )
        val (doubanUpdated, errors) = batchUpdateDoubanWithProgress(doubanNeedUpdate)

        // ========== 阶段5: 完成 ==========
        _checkProgress.value = _checkProgress.value.copy(
            isRunning = false,
            isComplete = true,
            phase = "完成",
            subPhase = "",
            currentTitle = null,
            doubanUpdated = doubanUpdated,
            errors = errors,
            delayInfo = null
        )
        // 记录检查完成时间（供设置页二次确认弹窗显示）
        runCatching { lastConsistencyCheckStorage.recordCheck() }
        Log.i(TAG, "状态一致性检查完成（爬豆瓣列表）: ${_checkProgress.value}")
    }

    /**
     * 分类冲突项（抽取公共逻辑，供 checkAndUnify 和 checkAndUnifyWithCrawl 复用）
     */
    private fun classifyConflicts(
        allItems: List<DoubanSyncedItem>,
        watchedIds: TraktRepository.WatchlistWatchedIds?
    ): ClassifyResult {
        val traktNeedWatched = mutableListOf<Pair<Int, MediaType>>()
        val traktNeedWatchlist = mutableListOf<Pair<Int, MediaType>>()
        val doubanNeedUpdate = mutableListOf<DoubanStatusUpdate>()
        val traktConflictRemoveFromWatchlist = mutableListOf<Pair<Int, MediaType>>()
        var conflicts = 0
        var skipped = 0

        for (item in allItems) {
            val traktId = item.traktId
            if (traktId == null || traktId <= 0) {
                skipped++
                continue
            }
            val mediaType = if (item.mediaType == "show") MediaType.SHOW else MediaType.MOVIE
            val doubanIsCollect = item.status == "collect"
            val doubanIsWish = item.status == "wish"
            val traktIsWatched = watchedIds?.isWatched(traktId, null, mediaType) ?: false
            val traktIsInWatchlist = watchedIds?.isInWatchlist(traktId, null, mediaType) ?: false

            // 优先检测 Trakt 侧同时存在 watchlist + watched 的冲突（按"已看优先"清理 watchlist）
            if (traktIsWatched && traktIsInWatchlist) {
                conflicts++
                traktConflictRemoveFromWatchlist.add(traktId to mediaType)
                // 若豆瓣是 wish，需升级为 collect（已看优先）
                if (doubanIsWish) {
                    doubanNeedUpdate.add(DoubanStatusUpdate(item.doubanId, "collect", item.doubanId, item.title))
                }
                // 冲突已归类，跳过后续 when 分类
                continue
            }

            when {
                (doubanIsCollect && traktIsWatched) || (doubanIsWish && traktIsInWatchlist) -> {}
                doubanIsCollect && traktIsInWatchlist -> {
                    conflicts++
                    traktNeedWatched.add(traktId to mediaType)
                }
                doubanIsCollect -> {
                    traktNeedWatched.add(traktId to mediaType)
                }
                doubanIsWish && traktIsWatched -> {
                    conflicts++
                    doubanNeedUpdate.add(DoubanStatusUpdate(item.doubanId, "collect", item.doubanId, item.title))
                }
                doubanIsWish -> {
                    traktNeedWatchlist.add(traktId to mediaType)
                }
                traktIsWatched -> {
                    doubanNeedUpdate.add(DoubanStatusUpdate(item.doubanId, "collect", item.doubanId, item.title))
                }
                traktIsInWatchlist -> {
                    doubanNeedUpdate.add(DoubanStatusUpdate(item.doubanId, "wish", item.doubanId, item.title))
                }
            }
        }
        return ClassifyResult(traktNeedWatched, traktNeedWatchlist, doubanNeedUpdate, conflicts, skipped, traktConflictRemoveFromWatchlist)
    }

    /** 批量更新 Trakt 侧 */
    private suspend fun batchUpdateTrakt(
        traktNeedWatched: List<Pair<Int, MediaType>>,
        traktNeedWatchlist: List<Pair<Int, MediaType>>,
        traktConflictRemoveFromWatchlist: List<Pair<Int, MediaType>> = emptyList()
    ): Int {
        var traktUpdated = 0
        val movieWatched = traktNeedWatched.filter { it.second == MediaType.MOVIE }.map { it.first }
        val showWatched = traktNeedWatched.filter { it.second == MediaType.SHOW }.map { it.first }
        val movieWatchlist = traktNeedWatchlist.filter { it.second == MediaType.MOVIE }.map { it.first }
        val showWatchlist = traktNeedWatchlist.filter { it.second == MediaType.SHOW }.map { it.first }
        val movieConflict = traktConflictRemoveFromWatchlist.filter { it.second == MediaType.MOVIE }.map { it.first }
        val showConflict = traktConflictRemoveFromWatchlist.filter { it.second == MediaType.SHOW }.map { it.first }

        if (movieWatched.isNotEmpty() || showWatched.isNotEmpty()) {
            val watchedResult = traktRepository.batchMarkAsWatched(movieWatched, showWatched)
            if (watchedResult.isSuccess) {
                traktUpdated += movieWatched.size + showWatched.size
            } else {
                Log.e(TAG, "Trakt 批量标记已看失败", watchedResult.exceptionOrNull())
            }
        }
        if (movieWatchlist.isNotEmpty() || showWatchlist.isNotEmpty()) {
            val watchlistResult = traktRepository.batchAddToWatchlist(movieWatchlist, showWatchlist)
            if (watchlistResult.isSuccess) {
                traktUpdated += movieWatchlist.size + showWatchlist.size
            } else {
                Log.e(TAG, "Trakt 批量标记想看失败", watchlistResult.exceptionOrNull())
            }
        }
        // 冲突清理：从 watchlist 移除已在 watched 中的 ID（保留 watched，已看优先）
        if (movieConflict.isNotEmpty() || showConflict.isNotEmpty()) {
            val conflictResult = traktRepository.batchRemoveFromWatchlist(movieConflict, showConflict)
            if (conflictResult.isSuccess) {
                traktUpdated += movieConflict.size + showConflict.size
            } else {
                Log.e(TAG, "Trakt 冲突清理 batchRemoveFromWatchlist 失败", conflictResult.exceptionOrNull())
            }
        }
        return traktUpdated
    }

    /** 逐条更新豆瓣侧（静默版，不更新进度） */
    private suspend fun batchUpdateDouban(doubanNeedUpdate: List<DoubanStatusUpdate>): Pair<Int, Int> {
        if (doubanNeedUpdate.isEmpty()) return 0 to 0
        val cred = doubanAuthStorage.getCredentials()
        if (cred == null) {
            Log.w(TAG, "豆瓣未登录，跳过豆瓣侧更新（${doubanNeedUpdate.size}条）")
            return 0 to doubanNeedUpdate.size
        }
        val semaphore = Semaphore(DOUBAN_CONCURRENCY)
        var doubanUpdated = 0
        var errors = 0
        coroutineScope {
            doubanNeedUpdate.map { update ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            val ck = doubanRepository.fetchCsrfToken(update.doubanId, cred.cookie)
                            if (ck == null) {
                                Log.w(TAG, "获取 ck 失败: ${update.doubanId}")
                                return@runCatching false
                            }
                            val result = doubanRepository.markInterest(
                                update.action, update.doubanId, cred.cookie, ck
                            )
                            if (result.success) {
                                runCatching {
                                    doubanSyncedItemDao.updateStatus(update.syncedDoubanId, update.action)
                                }
                                true
                            } else false
                        }.getOrElse {
                            Log.e(TAG, "豆瓣标记失败: ${update.doubanId}", it)
                            false
                        }
                    }
                }
            }.awaitAll().forEach { success ->
                if (success) doubanUpdated++ else errors++
            }
        }
        return doubanUpdated to errors
    }

    /** 逐条更新豆瓣侧（带进度更新版） */
    private suspend fun batchUpdateDoubanWithProgress(doubanNeedUpdate: List<DoubanStatusUpdate>): Pair<Int, Int> {
        if (doubanNeedUpdate.isEmpty()) return 0 to 0
        val cred = doubanAuthStorage.getCredentials()
        if (cred == null) {
            _checkProgress.value = _checkProgress.value.copy(cookieExpired = true)
            return 0 to doubanNeedUpdate.size
        }
        val semaphore = Semaphore(DOUBAN_CONCURRENCY)
        var doubanUpdated = 0
        var errors = 0
        var done = 0
        val total = doubanNeedUpdate.size
        coroutineScope {
            doubanNeedUpdate.map { update ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            _checkProgress.value = _checkProgress.value.copy(currentTitle = update.title)
                            val ck = doubanRepository.fetchCsrfToken(update.doubanId, cred.cookie)
                            if (ck == null) {
                                Log.w(TAG, "获取 ck 失败: ${update.doubanId}")
                                return@runCatching false
                            }
                            val result = doubanRepository.markInterest(
                                update.action, update.doubanId, cred.cookie, ck
                            )
                            if (result.success) {
                                runCatching {
                                    doubanSyncedItemDao.updateStatus(update.syncedDoubanId, update.action)
                                }
                                true
                            } else false
                        }.getOrElse {
                            Log.e(TAG, "豆瓣标记失败: ${update.doubanId}", it)
                            false
                        }
                    }
                }
            }.awaitAll().forEach { success ->
                done++
                if (success) doubanUpdated++ else errors++
                _checkProgress.value = _checkProgress.value.copy(
                    current = done,
                    total = total,
                    doubanUpdated = doubanUpdated,
                    errors = errors
                )
            }
        }
        return doubanUpdated to errors
    }

    private data class DoubanStatusUpdate(
        val doubanId: String,
        val action: String,          // "wish" 或 "collect"
        val syncedDoubanId: String,  // douban_synced_items 表中的 doubanId（用于回写 status）
        val title: String = ""
    )

    private data class ClassifyResult(
        val traktNeedWatched: List<Pair<Int, MediaType>>,
        val traktNeedWatchlist: List<Pair<Int, MediaType>>,
        val doubanNeedUpdate: List<DoubanStatusUpdate>,
        val conflicts: Int,
        val skipped: Int,
        // Trakt 侧同时存在 watchlist + watched 的冲突 ID（按"已看优先"从 watchlist 移除）
        val traktConflictRemoveFromWatchlist: List<Pair<Int, MediaType>> = emptyList()
    )
}
