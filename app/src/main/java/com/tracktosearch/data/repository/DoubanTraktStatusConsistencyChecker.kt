package com.tracktosearch.data.repository

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.tracktosearch.R
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.LastConsistencyCheckStorage
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.local.db.DoubanConsistencyCheckDao
import com.tracktosearch.data.local.db.DoubanConsistencyCheckRunEntity
import com.tracktosearch.data.local.db.DoubanConsistencyCheckTaskEntity
import com.tracktosearch.data.local.db.DoubanConsistencyConflictEntity
import com.tracktosearch.data.remote.douban.DelayInfo
import com.tracktosearch.data.remote.douban.DoubanCookieExpiredException
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.remote.douban.DoubanNetworkException
import com.tracktosearch.data.remote.douban.DoubanRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

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
    /** 从未登录豆瓣(区分 cookieExpired:cookieExpired 是登录后过期,本字段是从未登录) */
    val neverLoggedInDouban: Boolean = false,
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
    private val consistencyCheckDao: DoubanConsistencyCheckDao,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "StatusConsistency"
        private const val CHECK_RUN_ID = "1"
        /** 豆瓣标记并发度（单条操作+反爬延迟，控制并发避免封禁） */
        private const val DOUBAN_CONCURRENCY = 2
    }

    private val _checkProgress = MutableStateFlow(ConsistencyCheckResult())
    val checkProgress: StateFlow<ConsistencyCheckResult> = _checkProgress.asStateFlow()

    /** Application scope：手动检查协程在此运行，Activity/Service 销毁不影响 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 检查任务的原子占用令牌，覆盖自动检查和手动检查两个入口。 */
    private val checkLock = Any()

    @Volatile
    private var activeCheckToken: Any? = null

    /** 仅手动检查需要保存 Job，供取消按钮使用；自动检查由调用方生命周期管理。 */
    @Volatile
    private var manualCheckJob: Job? = null

    @Volatile
    private var cancelled = false

    /**
     * 检查完成事件是否已被消费（避免 ViewModel 重建后重复触发完成弹窗）。
     *
     * - 检查正常完成时设为 true
     * - 新检查开始 / resetProgress() 时重置为 false
     * - WatchlistViewModel 通过 [consumeCheckCompleteEvent] 消费一次后变回 false
     *
     * 注意：放在 Singleton 内而非 ViewModel 局部 var，确保 ViewModel 重建后状态不丢失。
     */
    @Volatile
    private var checkCompleteHandled = false

    /** WakeLock:手动检查期间保持 CPU 唤醒,避免息屏后网络请求 timeout */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 预解析的 UI 文案缓存。
     *
     * 背景：检查协程运行在 Dispatchers.IO，历史上循环/取消路径直接 context.getString，
     * 与主线程（cancel()/resetProgress() 也会取字符串）形成跨线程资源访问；
     * Robolectric 下该竞争偶发死锁导致取消测试 60s 卡死（真机无碍但属隐患），
     * 生产环境也重复解析浪费。这里首次使用时在调用方线程解析一次并缓存，
     * 协程内只读缓存值；按 locale 标记失效，语言切换后自动重取。
     */
    private class CheckUiStrings(
        val cancelling: String,
        val cancelled: String,
        val alreadyRunning: String,
        val notLoggedIn: String,
        val crawlPhase: String,
        val subWish: String,
        val subCollect: String,
        val cookieExpired: String,
        val done: String,
        val compare: String,
        val updateTrakt: String,
        val updateDouban: String,
        val subDetailCk: String,
        val networkTimeout: String,
        val networkFailed: String,
        val exceptionFormat: String
    )

    private val uiStringsLock = Any()
    private var cachedLocaleTag: String? = null
    private var cachedUiStrings: CheckUiStrings? = null

    private fun uiStrings(): CheckUiStrings {
        val localeTag = context.resources.configuration.locales[0].toLanguageTag()
        synchronized(uiStringsLock) {
            cachedUiStrings?.takeIf { cachedLocaleTag == localeTag }?.let { return it }
            val strings = CheckUiStrings(
                cancelling = context.getString(R.string.consistency_check_phase_cancelling),
                cancelled = context.getString(R.string.consistency_check_phase_cancelled),
                alreadyRunning = context.getString(R.string.consistency_check_already_running),
                notLoggedIn = context.getString(R.string.consistency_check_not_logged_in),
                crawlPhase = context.getString(R.string.consistency_check_phase_crawl),
                subWish = context.getString(R.string.consistency_check_sub_wish),
                subCollect = context.getString(R.string.consistency_check_sub_collect),
                cookieExpired = context.getString(R.string.consistency_check_cookie_expired),
                done = context.getString(R.string.consistency_check_phase_done),
                compare = context.getString(R.string.consistency_check_phase_compare),
                updateTrakt = context.getString(R.string.consistency_check_phase_update_trakt),
                updateDouban = context.getString(R.string.consistency_check_phase_update_douban),
                subDetailCk = context.getString(R.string.consistency_check_sub_detail_ck),
                networkTimeout = context.getString(R.string.consistency_check_network_timeout),
                networkFailed = context.getString(R.string.consistency_check_network_failed),
                exceptionFormat = context.getString(R.string.consistency_check_exception)
            )
            cachedUiStrings = strings
            cachedLocaleTag = localeTag
            return strings
        }
    }

    init {
        // 监听 DoubanRepository 的延时事件，合并到 checkProgress.delayInfo
        appScope.launch {
            doubanRepository.delayEvent.collect { info ->
                _checkProgress.update { progress -> progress.copy(delayInfo = info) }
            }
        }
    }

    /** 检查是否在运行中 */
    fun isRunning(): Boolean = activeCheckToken != null

    private fun tryAcquireCheck(token: Any): Boolean = synchronized(checkLock) {
        if (activeCheckToken != null) {
            false
        } else {
            activeCheckToken = token
            true
        }
    }

    private fun releaseCheck(token: Any) {
        synchronized(checkLock) {
            if (activeCheckToken === token) {
                activeCheckToken = null
                manualCheckJob = null
            }
        }
    }

    /** 取消正在进行的检查 */
    fun cancel() {
        cancelled = true
        manualCheckJob?.cancel()
        // 同步更新 isCancelling 中间态:UI 立即禁用取消按钮 + 显示"正在取消..." 文案
        _checkProgress.value = _checkProgress.value.copy(
            isCancelling = true,
            phase = uiStrings().cancelling,
            subPhase = "",
            delayInfo = null
        )
    }

    /** 重置进度状态（完成后调用，避免 StateFlow 旧值 isComplete=true 导致重复弹窗） */
    fun resetProgress() {
        if (isRunning()) return  // 运行中不重置
        // 同步重置完成事件标志，允许下次检查完成时再次触发弹窗
        checkCompleteHandled = false
        _checkProgress.value = ConsistencyCheckResult()
    }

    /**
     * 消费一次"检查完成"事件。
     *
     * 用于替代 WatchlistViewModel 中的局部 var checkCompleteHandled，确保 ViewModel 重建后
     * 状态不丢失：第一次调用返回 true（触发完成弹窗），之后调用返回 false，直到下次检查
     * 完成或 resetProgress() 重置。
     *
     * 注意：取消（isCancelled=true）完成不设置该标志，因此不会触发弹窗。
     *
     * @return true 表示有待消费的完成事件（应触发弹窗），false 表示无
     */
    fun consumeCheckCompleteEvent(): Boolean {
        return if (checkCompleteHandled) {
            checkCompleteHandled = false
            true
        } else {
            false
        }
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
        // 自动检查和手动检查共用同一个原子令牌，避免 check-then-set 竞态。
        val token = Any()
        if (!tryAcquireCheck(token)) {
            return@withContext ConsistencyCheckResult(
                isComplete = true,
                errors = 1,
                phase = uiStrings().alreadyRunning
            )
        }
        try {
            val accountKey = currentAccountKey()
            val allItems = try {
                doubanSyncedItemDao.getAllSyncedItems()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext failureResult(e)
            }
            val dataVersion = computeDataVersion(allItems)
            val existingRun = consistencyCheckDao.getRun()
            if (existingRun != null && existingRun.status in setOf("RUNNING", "RETRY")) {
                if (existingRun.accountKey != accountKey || existingRun.dataVersion != dataVersion) {
                    val now = System.currentTimeMillis()
                    consistencyCheckDao.upsertRun(
                        existingRun.copy(
                            status = "INVALID",
                            phase = "需要重新检查",
                            updatedAt = now,
                            completedAt = now,
                            invalidReason = "ACCOUNT_OR_DATA_VERSION_CHANGED"
                        )
                    )
                    return@withContext ConsistencyCheckResult(
                        isComplete = true,
                        errors = 1,
                        phase = "账号或数据版本已变化，请重新检查"
                    )
                }
                return@withContext executePersistedTasks(existingRun)
            }

            if (allItems.isEmpty()) {
                persistEmptyRun(accountKey)
                return@withContext ConsistencyCheckResult(isComplete = true)
            }

            // 确保 Trakt 侧 watchlistWatchedIds 已加载
            val watchedIds = try {
                val loaded = traktRepository.loadWatchlistWatchedIds()
                traktRepository.getWatchlistWatchedIds() ?: loaded
            } catch (e: Exception) {
                return@withContext failureResult(e, totalChecked = allItems.size)
            }

            val classifyResult = classifyConflicts(allItems, watchedIds)
            val traktNeedWatched = classifyResult.traktNeedWatched
            val traktNeedWatchlist = classifyResult.traktNeedWatchlist
            val doubanNeedUpdate = classifyResult.doubanNeedUpdate
            val conflicts = classifyResult.conflicts
            val skipped = classifyResult.skipped
            val persistentPlan = buildPersistentPlan(allItems, watchedIds)
            persistRunningPlan(accountKey, dataVersion, allItems.size, persistentPlan)

            // 1. 批量更新 Trakt 侧
            val traktResult = batchUpdateTrakt(traktNeedWatched, traktNeedWatchlist)
            var traktUpdated = traktResult.updated
            var errors = traktResult.errors

            // 1b. 全量清理 Trakt 侧想看+已看双状态冲突（覆盖非豆瓣来源的纯 Trakt 数据）
            val traktConflictResult = resolveTraktWatchlistWatchedConflicts(traktRepository.getWatchlistWatchedIds())
            traktUpdated += traktConflictResult.updated
            errors += traktConflictResult.errors

            // 2. 逐条更新豆瓣侧
            val (doubanUpdated, doubanErrors) = batchUpdateDouban(doubanNeedUpdate)
            errors += doubanErrors

            val result = ConsistencyCheckResult(
                totalChecked = allItems.size,
                conflictsFound = conflicts + traktConflictResult.updated,
                doubanUpdated = doubanUpdated,
                traktUpdated = traktUpdated,
                skipped = skipped,
                errors = errors,
                isComplete = true
            )
            persistCompletedPlan(accountKey, dataVersion, allItems.size, persistentPlan, result)
            // 记录检查完成时间（供设置页二次确认弹窗显示）
            if (result.errors == 0) runCatching { lastConsistencyCheckStorage.recordCheck() }
            Log.i(TAG, "状态一致性检查完成（本地表对比）: $result")
            result
        } finally {
            releaseCheck(token)
        }
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
        val token = Any()
        if (!tryAcquireCheck(token)) return false
        cancelled = false
        // 新检查开始:重置完成事件标志,允许本次完成触发弹窗
        checkCompleteHandled = false
        // 息屏时保持 CPU 唤醒,避免豆瓣爬取过程中网络请求 timeout
        acquireWakeLock()
        val job = appScope.launch {
            try {
                runCheckWithCrawl()
            } catch (e: CancellationException) {
                // 取消时更新进度状态,否则 isRunning 仍为 true 导致弹窗不响应
                // isCancelled=true 区分"取消"与"正常完成",避免 WatchlistScreen 自动弹出结果弹窗
                markCancelledProgress()
                // 延迟清理:让 UI 短暂展示"已取消"终态后重置,避免 checkProgress 永远停留在取消状态
                // 用独立 appScope launch,不阻塞当前被取消的协程
                appScope.launch {
                    delay(1500)
                    // 仅当仍是取消状态时重置(避免新检查已开始时误清新状态)
                    if (_checkProgress.value.isCancelled) {
                        resetProgress()
                    }
                }
                throw e
            } catch (e: Exception) {
                // 网络错误(DNS 解析失败、连接超时、SSL 握手失败等)按异常类型提供友好提示,
                // 不暴露原始异常信息。注意:取消时不设置 checkCompleteHandled,
                // 避免错误完成事件被消费后触发 Watchlist 页弹窗
                _checkProgress.value = failureResult(e, base = _checkProgress.value)
                // 错误完成也应触发一次完成弹窗,让用户看到错误信息
                checkCompleteHandled = true
            } finally {
                // 先释放唤醒锁，再释放运行令牌，避免 UI 观察到 isRunning=false 时仍残留 WakeLock。
                try {
                    releaseWakeLock()
                } finally {
                    releaseCheck(token)
                }
            }
        }
        synchronized(checkLock) {
            // 任务可能在这里之前就完成；只为仍持有当前令牌的任务登记 Job，避免完成后残留旧引用。
            if (activeCheckToken === token) {
                manualCheckJob = job
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                val needsFallback = !_checkProgress.value.isComplete
                if (needsFallback) {
                    // 协程可能在进入 try/catch 前就被取消，补发取消终态并保持 UI 可恢复。
                    markCancelledProgress()
                    appScope.launch {
                        delay(1500)
                        if (_checkProgress.value.isCancelled) {
                            resetProgress()
                        }
                    }
                }
                // 协程体未启动时不会执行 finally，这里补做资源和运行令牌清理。
                try {
                    releaseWakeLock()
                } finally {
                    releaseCheck(token)
                }
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

    /** 发布取消终态，覆盖取消发生在协程体开始前的情况。 */
    private fun markCancelledProgress() {
        val current = _checkProgress.value
        if (current.isComplete && current.isCancelled) return
        _checkProgress.value = current.copy(
            isRunning = false,
            isComplete = true,
            isCancelled = true,
            phase = uiStrings().cancelled
        )
    }

    private suspend fun runCheckWithCrawl() {
        // CC-F05: cancel() 可能在令牌获取后、协程体于 IO 线程启动前已同步写入取消中间态；
        // 此时跳过整体重置，避免抹掉 isCancelling 导致取消链路状态与 UI 断言错乱（竞态）
        if (!cancelled) {
            _checkProgress.value = ConsistencyCheckResult(
                isRunning = true,
                startTimeMs = System.currentTimeMillis()
            )
        }

        val cred = doubanAuthStorage.getCredentials()
        if (cred == null) {
            _checkProgress.value = ConsistencyCheckResult(
                isComplete = true,
                neverLoggedInDouban = true,
                phase = uiStrings().notLoggedIn
            )
            // 错误完成也应触发一次完成弹窗,让用户看到错误信息
            checkCompleteHandled = true
            return
        }

        // ========== 阶段1: 爬取豆瓣列表 ==========
        val latestDoubanStatuses = mutableMapOf<String, Pair<String, String>>() // doubanId → (status, title)

        // 阶段文案使用预解析缓存：协程线程不直接访问资源（见 uiStrings 注释）
        val phaseCrawl = uiStrings().crawlPhase
        val subWish = uiStrings().subWish
        val subCollect = uiStrings().subCollect
        for (status in listOf(DoubanMarkStatus.WISH, DoubanMarkStatus.COLLECT)) {
            // CC-F03: 取消时显式设置终态,避免状态卡在"正在取消..."
            if (cancelled) {
                _checkProgress.value = _checkProgress.value.copy(
                    isRunning = false,
                    isComplete = true,
                    isCancelled = true,
                    phase = uiStrings().cancelled
                )
                return
            }
            val subPhaseName = if (status == DoubanMarkStatus.WISH) subWish else subCollect
            _checkProgress.value = _checkProgress.value.copy(
                phase = phaseCrawl,
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
                // 持久化 Cookie 失效标记：以前失效只出现在检查弹窗里，设置页账号卡照常显示已登录
                doubanAuthStorage.markCookieInvalid()
                _checkProgress.value = _checkProgress.value.copy(
                    isRunning = false,
                    isComplete = true,
                    cookieExpired = true,
                    phase = uiStrings().cookieExpired
                )
                // 错误完成也应触发一次完成弹窗,让用户看到 cookie 过期提示
                checkCompleteHandled = true
                return
            }
        }

        // 远端列表为空时仍需检查本地同步记录，避免跳过用户刚从豆瓣取消的标记。
        val allSyncedItems = doubanSyncedItemDao.getAllSyncedItems()
        allSyncedItems.forEach { item ->
            latestDoubanStatuses.putIfAbsent(item.doubanId, "" to item.title)
        }

        if (latestDoubanStatuses.isEmpty()) {
            // 先记录完成时间，再发布完成态，避免 UI 观察到 isComplete 后读到旧的检查时间。
            runCatching { lastConsistencyCheckStorage.recordCheck() }
            _checkProgress.value = ConsistencyCheckResult(
                isComplete = true,
                totalChecked = 0,
                phase = uiStrings().done
            )
            // 正常完成(空列表),触发一次完成弹窗
            checkCompleteHandled = true
            return
        }

        // ========== 阶段2: 对比状态 ==========
        // CC-F03: 取消时显式设置终态,避免状态卡在"正在取消..."
        if (cancelled) {
            _checkProgress.value = _checkProgress.value.copy(
                isRunning = false,
                isComplete = true,
                isCancelled = true,
                phase = uiStrings().cancelled
            )
            return
        }
        _checkProgress.value = _checkProgress.value.copy(
            phase = uiStrings().compare,
            subPhase = "",
            current = 0,
            total = latestDoubanStatuses.size
        )

        // 手动检查必须强制刷新 Trakt，避免用旧快照误判刚发生的状态变化。
        val watchedIds = traktRepository.loadWatchlistWatchedIds(forceRefresh = true)

        // 从本地表查 traktId 映射（豆瓣ID → traktId + mediaType）
        val doubanIdToSyncedItem = allSyncedItems.associateBy { it.doubanId }

        val traktNeedWatched = mutableListOf<Pair<Int, MediaType>>()
        val traktNeedWatchlist = mutableListOf<Pair<Int, MediaType>>()
        val doubanNeedUpdate = mutableListOf<DoubanStatusUpdate>()
        var conflicts = 0
        var skipped = 0
        var checked = 0

        for ((doubanId, pair) in latestDoubanStatuses) {
            // CC-F03: 取消时显式设置终态,避免状态卡在"正在取消..."
            if (cancelled) {
                _checkProgress.value = _checkProgress.value.copy(
                    isRunning = false,
                    isComplete = true,
                    isCancelled = true,
                    phase = uiStrings().cancelled
                )
                return
            }
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
            val traktIsWatched = watchedIds.isWatched(traktId, null, mediaType)
            val traktIsInWatchlist = watchedIds.isInWatchlist(traktId, null, mediaType)

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
        // CC-F03: 取消时显式设置终态,避免状态卡在"正在取消..."
        if (cancelled) {
            _checkProgress.value = _checkProgress.value.copy(
                isRunning = false,
                isComplete = true,
                isCancelled = true,
                phase = uiStrings().cancelled
            )
            return
        }
        _checkProgress.value = _checkProgress.value.copy(
            phase = uiStrings().updateTrakt,
            subPhase = "",
            current = 0,
            total = 0
        )
        val traktResult = batchUpdateTrakt(traktNeedWatched, traktNeedWatchlist)
        var traktUpdated = traktResult.updated
        var errors = traktResult.errors
        // 全量清理 Trakt 侧想看+已看双状态冲突（覆盖非豆瓣来源的纯 Trakt 数据）
        val traktConflictResult = resolveTraktWatchlistWatchedConflicts(traktRepository.getWatchlistWatchedIds())
        traktUpdated += traktConflictResult.updated
        errors += traktConflictResult.errors
        conflicts += traktConflictResult.updated
        _checkProgress.value = _checkProgress.value.copy(
            traktUpdated = traktUpdated,
            conflictsFound = conflicts,
            current = 0,
            errors = errors,
            total = 0
        )

        // ========== 阶段4: 逐条更新豆瓣侧 ==========
        // CC-F03: 取消时显式设置终态,避免状态卡在"正在取消..."
        if (cancelled) {
            _checkProgress.value = _checkProgress.value.copy(
                isRunning = false,
                isComplete = true,
                isCancelled = true,
                phase = uiStrings().cancelled
            )
            return
        }
        _checkProgress.value = _checkProgress.value.copy(
            phase = uiStrings().updateDouban,
            subPhase = uiStrings().subDetailCk,
            current = 0,
            total = doubanNeedUpdate.size
        )
        val (doubanUpdated, totalErrors) = batchUpdateDoubanWithProgress(
            doubanNeedUpdate,
            initialErrors = errors
        )
        errors = totalErrors

        // ========== 阶段5: 完成 ==========
        // 先记录检查完成时间，再发布完成态，避免 UI 观察到 isComplete 后读到旧的检查时间。
        runCatching { lastConsistencyCheckStorage.recordCheck() }
        _checkProgress.value = _checkProgress.value.copy(
            isRunning = false,
            isComplete = true,
            phase = uiStrings().done,
            subPhase = "",
            currentTitle = null,
            doubanUpdated = doubanUpdated,
            errors = errors,
            delayInfo = null
        )
        // 正常完成,设置完成事件标志,允许 WatchlistViewModel 通过 consumeCheckCompleteEvent 触发一次弹窗
        checkCompleteHandled = true
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

            // 优先检测 Trakt 侧同时存在 watchlist + watched 的冲突（按"已看优先"处理）
            // 注意：Trakt 侧的想看移除统一交给 resolveTraktWatchlistWatchedConflicts 全量清理，
            //       此处只负责豆瓣侧的升级（避免与全量清理重复移除想看）
            if (traktIsWatched && traktIsInWatchlist) {
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
        return ClassifyResult(traktNeedWatched, traktNeedWatchlist, doubanNeedUpdate, conflicts, skipped)
    }

    /**
     * 清理 Trakt 侧同时处于「想看」+「已看」的历史冲突（全量扫描，不依赖豆瓣映射）。
     *
     * 直接取 watchlist∩watched 交集，按「已看优先」从想看移除，可覆盖非豆瓣来源的纯 Trakt 数据
     * （如直接在 Trakt 标记的片、旧逻辑遗留的双状态条目）。
     *
     * @return 成功移除的想看条目数（用于计入 traktUpdated）
     */
    private suspend fun resolveTraktWatchlistWatchedConflicts(
        watchedIds: TraktRepository.WatchlistWatchedIds?
    ): TraktUpdateResult {
        if (watchedIds == null) return TraktUpdateResult()
        val (movieConflicts, showConflicts) = watchedIds.watchlistWatchedConflicts()
        if (movieConflicts.isEmpty() && showConflicts.isEmpty()) return TraktUpdateResult()

        val result = try {
            traktRepository.batchRemoveFromWatchlist(
                movieConflicts.toList(),
                showConflicts.toList()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        return if (result.isSuccess) {
            val removed = movieConflicts.size + showConflicts.size
            Log.i(TAG, "清理 Trakt 想看+已看双状态冲突: 移除想看 $removed 条")
            TraktUpdateResult(updated = removed)
        } else {
            Log.e(TAG, "清理 Trakt 想看+已看冲突失败", result.exceptionOrNull())
            TraktUpdateResult(errors = movieConflicts.size + showConflicts.size)
        }
    }

    /** 批量更新 Trakt 侧 */
    private suspend fun batchUpdateTrakt(
        traktNeedWatched: List<Pair<Int, MediaType>>,
        traktNeedWatchlist: List<Pair<Int, MediaType>>
    ): TraktUpdateResult {
        var traktUpdated = 0
        var errors = 0
        val movieWatched = traktNeedWatched.filter { it.second == MediaType.MOVIE }.map { it.first }
        val showWatched = traktNeedWatched.filter { it.second == MediaType.SHOW }.map { it.first }
        val movieWatchlist = traktNeedWatchlist.filter { it.second == MediaType.MOVIE }.map { it.first }
        val showWatchlist = traktNeedWatchlist.filter { it.second == MediaType.SHOW }.map { it.first }

        if (movieWatched.isNotEmpty() || showWatched.isNotEmpty()) {
            val watchedResult = try {
                traktRepository.batchMarkAsWatched(movieWatched, showWatched)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (watchedResult.isSuccess) {
                traktUpdated += movieWatched.size + showWatched.size
            } else {
                errors += movieWatched.size + showWatched.size
                Log.e(TAG, "Trakt 批量标记已看失败", watchedResult.exceptionOrNull())
            }
        }
        if (movieWatchlist.isNotEmpty() || showWatchlist.isNotEmpty()) {
            val watchlistResult = try {
                traktRepository.batchAddToWatchlist(movieWatchlist, showWatchlist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (watchlistResult.isSuccess) {
                traktUpdated += movieWatchlist.size + showWatchlist.size
            } else {
                errors += movieWatchlist.size + showWatchlist.size
                Log.e(TAG, "Trakt 批量标记想看失败", watchlistResult.exceptionOrNull())
            }
        }
        return TraktUpdateResult(updated = traktUpdated, errors = errors)
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
                            val ck = doubanRepository.fetchCsrfToken(update.doubanId, cred.cookie, respectAntiCrawlDelay = true)
                            if (ck == null) {
                                Log.w(TAG, "获取 ck 失败: ${update.doubanId}")
                                return@runCatching false
                            }
                            val result = doubanRepository.markInterestByCk(
                                update.action, update.doubanId, cred.cookie, ck
                            )
                            if (result.success) {
                                try {
                                    doubanSyncedItemDao.updateStatus(update.syncedDoubanId, update.action)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e(TAG, "本地状态写入失败: ${update.doubanId}", e)
                                    return@runCatching false
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
    private suspend fun batchUpdateDoubanWithProgress(
        doubanNeedUpdate: List<DoubanStatusUpdate>,
        initialErrors: Int = 0
    ): Pair<Int, Int> {
        if (doubanNeedUpdate.isEmpty()) return 0 to initialErrors
        val cred = doubanAuthStorage.getCredentials()
        if (cred == null) {
            _checkProgress.value = _checkProgress.value.copy(cookieExpired = true)
            return 0 to (initialErrors + doubanNeedUpdate.size)
        }
        val semaphore = Semaphore(DOUBAN_CONCURRENCY)
        var doubanUpdated = 0
        var errors = initialErrors
        var done = 0
        val total = doubanNeedUpdate.size
        coroutineScope {
            doubanNeedUpdate.map { update ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            _checkProgress.value = _checkProgress.value.copy(currentTitle = update.title)
                            val ck = doubanRepository.fetchCsrfToken(update.doubanId, cred.cookie, respectAntiCrawlDelay = true)
                            if (ck == null) {
                                Log.w(TAG, "获取 ck 失败: ${update.doubanId}")
                                return@runCatching false
                            }
                            val result = doubanRepository.markInterestByCk(
                                update.action, update.doubanId, cred.cookie, ck
                            )
                            if (result.success) {
                                try {
                                    doubanSyncedItemDao.updateStatus(update.syncedDoubanId, update.action)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e(TAG, "本地状态写入失败: ${update.doubanId}", e)
                                    return@runCatching false
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

    private fun failureResult(
        exception: Exception,
        totalChecked: Int = 0,
        base: ConsistencyCheckResult? = null
    ): ConsistencyCheckResult {
        val phase = when (exception) {
            is DoubanCookieExpiredException -> uiStrings().cookieExpired
            is SocketTimeoutException -> uiStrings().networkTimeout
            is DoubanNetworkException,
            is UnknownHostException,
            is ConnectException,
            is SSLException -> uiStrings().networkFailed
            else -> if (exception.message?.contains("timeout", ignoreCase = true) == true) {
                uiStrings().networkTimeout
            } else {
                // 带参模板用缓存的原型字符串本地格式化，避免协程线程访问资源
                String.format(uiStrings().exceptionFormat, exception.message ?: "")
            }
        }
        val cookieExpired = exception is DoubanCookieExpiredException
        // 持久化失效标记，供设置页账号卡区分「已登录」与「连接失效」
        if (cookieExpired) doubanAuthStorage.markCookieInvalid()
        return (base ?: ConsistencyCheckResult()).copy(
            isRunning = false,
            isComplete = true,
            phase = phase,
            totalChecked = maxOf(base?.totalChecked ?: 0, totalChecked),
            errors = (base?.errors ?: 0) + 1,
            cookieExpired = cookieExpired
        )
    }

    private suspend fun currentAccountKey(): String =
        doubanAuthStorage.getCredentials()?.userId.orEmpty().ifBlank { "unknown" }

    private fun computeDataVersion(items: List<DoubanSyncedItem>): Long =
        items.fold(1L) { hash, item ->
            hash * 31 + item.doubanId.hashCode() + item.status.hashCode() + (item.traktId ?: 0)
        }

    private suspend fun persistEmptyRun(accountKey: String) {
        val now = System.currentTimeMillis()
        consistencyCheckDao.upsertRun(
            DoubanConsistencyCheckRunEntity(
                accountKey = accountKey,
                    dataVersion = computeDataVersion(emptyList()),
                status = "COMPLETED",
                phase = "DONE",
                current = 0,
                total = 0,
                startedAt = now,
                updatedAt = now,
                completedAt = now,
                invalidReason = null
            )
        )
        consistencyCheckDao.clearTasks(CHECK_RUN_ID)
        consistencyCheckDao.clearConflicts(CHECK_RUN_ID)
    }

    private suspend fun persistRunningPlan(
        accountKey: String,
        dataVersion: Long,
        total: Int,
        plan: PersistentPlan
    ) {
        val now = System.currentTimeMillis()
        consistencyCheckDao.clearTasks(CHECK_RUN_ID)
        consistencyCheckDao.clearConflicts(CHECK_RUN_ID)
        consistencyCheckDao.upsertRun(
            DoubanConsistencyCheckRunEntity(
                accountKey = accountKey,
                dataVersion = dataVersion,
                status = "RUNNING",
                phase = "APPLYING",
                current = 0,
                total = total,
                startedAt = now,
                updatedAt = now,
                completedAt = null,
                invalidReason = null
            )
        )
        if (plan.tasks.isNotEmpty()) consistencyCheckDao.upsertTasks(plan.tasks)
        if (plan.conflicts.isNotEmpty()) consistencyCheckDao.upsertConflicts(plan.conflicts)
    }

    private suspend fun persistCompletedPlan(
        accountKey: String,
        dataVersion: Long,
        total: Int,
        plan: PersistentPlan,
        result: ConsistencyCheckResult
    ) {
        val now = System.currentTimeMillis()
        consistencyCheckDao.upsertRun(
            DoubanConsistencyCheckRunEntity(
                accountKey = accountKey,
                dataVersion = dataVersion,
                status = if (result.errors == 0) "COMPLETED" else "RETRY",
                phase = if (result.errors == 0) "DONE" else "RETRY",
                current = total,
                total = total,
                startedAt = now,
                updatedAt = now,
                completedAt = if (result.errors == 0) now else null,
                invalidReason = null
            )
        )
        if (plan.tasks.isNotEmpty()) {
            consistencyCheckDao.upsertTasks(
                plan.tasks.map { task -> task.copy(status = if (result.errors == 0) "DONE" else "RETRY", attemptCount = 1, errorMessage = if (result.errors == 0) null else "Check completed with errors", updatedAt = now) }
            )
        }
        if (plan.conflicts.isNotEmpty()) {
            consistencyCheckDao.upsertConflicts(
                plan.conflicts.map { conflict -> conflict.copy(resolutionStatus = if (result.errors == 0) "FIXED" else "RETRY", errorMessage = if (result.errors == 0) null else "Check completed with errors", updatedAt = now) }
            )
        }
    }

    private data class PersistentPlan(
        val tasks: List<DoubanConsistencyCheckTaskEntity>,
        val conflicts: List<DoubanConsistencyConflictEntity>,
        val skipped: Int
    )

    private fun buildPersistentPlan(
        allItems: List<DoubanSyncedItem>,
        watchedIds: TraktRepository.WatchlistWatchedIds
    ): PersistentPlan {
        val tasks = mutableListOf<DoubanConsistencyCheckTaskEntity>()
        val conflicts = mutableListOf<DoubanConsistencyConflictEntity>()
        var skipped = 0
        val now = System.currentTimeMillis()
        for (item in allItems) {
            val traktId = item.traktId
            if (traktId == null || traktId <= 0) {
                skipped++
                continue
            }
            val mediaType = if (item.mediaType == "show") MediaType.SHOW else MediaType.MOVIE
            val traktWatched = watchedIds.isWatched(traktId, null, mediaType)
            val traktWatchlist = watchedIds.isInWatchlist(traktId, null, mediaType)
            val doubanCollect = item.status == "collect"
            val doubanWish = item.status == "wish"
            val runId = CHECK_RUN_ID
            val mediaValue = if (mediaType == MediaType.SHOW) "show" else "movie"
            fun traktAction(name: String) = "$name|$mediaValue|$traktId"
            fun addTask(action: String) {
                tasks += DoubanConsistencyCheckTaskEntity(runId, item.doubanId, action, "PENDING", updatedAt = now)
            }
            fun addConflict(type: String, traktStatus: String, action: String) {
                val status = if (action.isBlank()) "RESOLVED" else "PENDING"
                conflicts += DoubanConsistencyConflictEntity(
                    id = "$runId:${item.doubanId}:$type",
                    runId = runId,
                    doubanId = item.doubanId,
                    title = item.title,
                    doubanStatus = item.status,
                    traktStatus = traktStatus,
                    conflictType = type,
                    resolutionStatus = status,
                    updatedAt = now
                )
                if (action.isNotBlank()) addTask(action)
            }
            if (traktWatched && traktWatchlist) {
                addConflict("TRAKT_DUAL_STATUS", "WATCHED_AND_WATCHLIST", if (doubanWish) "DOUBAN_COLLECT" else traktAction("TRAKT_REMOVE_WATCHLIST"))
            } else when {
                doubanCollect && traktWatchlist -> addConflict("STATUS_CONFLICT", "WATCHLIST", traktAction("TRAKT_WATCHED"))
                doubanCollect && !traktWatched -> addTask(traktAction("TRAKT_WATCHED"))
                doubanWish && traktWatched -> addConflict("STATUS_CONFLICT", "WATCHED", "DOUBAN_COLLECT")
                doubanWish && !traktWatchlist -> addTask(traktAction("TRAKT_WATCHLIST"))
                !doubanCollect && traktWatched -> addTask("DOUBAN_COLLECT")
                !doubanWish && traktWatchlist -> addTask("DOUBAN_WISH")
            }
        }
        return PersistentPlan(tasks, conflicts, skipped)
    }

    private suspend fun executePersistedTasks(
        run: DoubanConsistencyCheckRunEntity,
        skipped: Int = 0
    ): ConsistencyCheckResult {
        val tasks = consistencyCheckDao.getPendingTasks(runId = CHECK_RUN_ID)
        var traktUpdated = 0
        var doubanUpdated = 0
        var errors = 0
        for (task in tasks) {
            val result = runCatching {
                val actionParts = task.action.split('|')
                when (actionParts.first()) {
                    "TRAKT_WATCHED" -> {
                        val type = if (actionParts[1] == "show") MediaType.SHOW else MediaType.MOVIE
                        val traktId = actionParts[2].toInt()
                        traktRepository.batchMarkAsWatched(
                            if (type == MediaType.MOVIE) listOf(traktId) else emptyList(),
                            if (type == MediaType.SHOW) listOf(traktId) else emptyList()
                        ).isSuccess
                    }
                    "TRAKT_WATCHLIST" -> {
                        val type = if (actionParts[1] == "show") MediaType.SHOW else MediaType.MOVIE
                        val traktId = actionParts[2].toInt()
                        traktRepository.batchAddToWatchlist(
                            if (type == MediaType.MOVIE) listOf(traktId) else emptyList(),
                            if (type == MediaType.SHOW) listOf(traktId) else emptyList()
                        ).isSuccess
                    }
                    "TRAKT_REMOVE_WATCHLIST" -> {
                        val type = if (actionParts[1] == "show") MediaType.SHOW else MediaType.MOVIE
                        val traktId = actionParts[2].toInt()
                        traktRepository.batchRemoveFromWatchlist(
                            if (type == MediaType.MOVIE) listOf(traktId) else emptyList(),
                            if (type == MediaType.SHOW) listOf(traktId) else emptyList()
                        ).isSuccess
                    }
                    "DOUBAN_COLLECT", "DOUBAN_WISH" -> {
                        val cred = doubanAuthStorage.getCredentials() ?: return@runCatching false
                        val action = if (task.action == "DOUBAN_COLLECT") "collect" else "wish"
                        val ck = doubanRepository.fetchCsrfToken(task.doubanId, cred.cookie, respectAntiCrawlDelay = true) ?: return@runCatching false
                        val marked = doubanRepository.markInterestByCk(action, task.doubanId, cred.cookie, ck).success
                        if (marked) doubanSyncedItemDao.updateStatus(task.doubanId, action)
                        marked
                    }
                    else -> false
                }
            }.getOrDefault(false)
            val nextStatus = if (result) "DONE" else "RETRY"
            consistencyCheckDao.updateTaskStatus(CHECK_RUN_ID, task.doubanId, task.action, nextStatus, task.attemptCount + 1, if (result) null else "Task failed", System.currentTimeMillis())
            if (result) {
                if (task.action.startsWith("TRAKT")) traktUpdated++ else doubanUpdated++
            } else errors++
        }
        val completed = errors == 0
        val now = System.currentTimeMillis()
        val allTasks = consistencyCheckDao.getTasks(CHECK_RUN_ID)
        val conflicts = consistencyCheckDao.getConflicts(CHECK_RUN_ID).map { conflict ->
            val related = allTasks.filter { it.doubanId == conflict.doubanId }
            val retry = related.firstOrNull { it.status != "DONE" }
            conflict.copy(
                resolutionStatus = if (retry == null) "FIXED" else "RETRY",
                errorMessage = retry?.errorMessage,
                updatedAt = now
            )
        }
        if (conflicts.isNotEmpty()) consistencyCheckDao.upsertConflicts(conflicts)
        consistencyCheckDao.upsertRun(run.copy(status = if (completed) "COMPLETED" else "RETRY", phase = if (completed) "DONE" else "RETRY", current = tasks.size, updatedAt = now, completedAt = if (completed) now else null))
        return ConsistencyCheckResult(
            totalChecked = run.total,
            conflictsFound = conflicts.size,
            doubanUpdated = doubanUpdated,
            traktUpdated = traktUpdated,
            skipped = skipped,
            errors = errors,
            isComplete = completed
        )
    }

    private data class TraktUpdateResult(
        val updated: Int = 0,
        val errors: Int = 0
    )

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
        val skipped: Int
    )
}
