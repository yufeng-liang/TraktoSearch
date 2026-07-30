package com.tracktosearch.data.repository

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DelayInfo
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 批量移除豆瓣标记的阶段。
 *
 * UI 层（Compose）用 `when (phase)` 映射 `stringResource`，
 * Service 层用 `context.getString` 映射，避免中文硬编码违反 i18n 硬约束。
 */
enum class BatchRemovalPhase {
    /** 移除中：正在批量移除豆瓣标记 */
    REMOVING,
    /** 取消中：用户点击取消,等待正在执行的条目完成 */
    CANCELLING,
    /** 完成（未取消） */
    DONE,
    /** 已取消 */
    CANCELLED
}

/**
 * 批量移除豆瓣标记进度（兼进度数据类）。
 *
 * 参考 [ConsistencyCheckResult] 与 [DoubanSyncProgress] 的结构，
 * 暴露给 UI 显示横幅进度、给 Service 显示通知栏进度。
 */
data class BatchRemovalProgress(
    val isRunning: Boolean = false,
    val isComplete: Boolean = false,
    val isCancelling: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val skipCount: Int = 0,          // 找不到 doubanId 跳过的条目
    val currentTitle: String? = null,
    val phase: BatchRemovalPhase = BatchRemovalPhase.REMOVING,
    val delayInfo: DelayInfo? = null,
    val cookieExpired: Boolean = false,
    val startTimeMs: Long = 0
)

/** 单条待移除条目的轻量数据（从 MediaUiItem 转换，避免 ViewModel 依赖 Manager 的内部数据结构） */
data class BatchRemovalItem(
    val traktId: Int,
    val imdbId: String,
    val title: String,
    // 豆瓣模式直接传入 doubanId,避免 findDoubanId 网络请求
    val doubanId: String? = null
)

/**
 * 豆瓣标记批量移除协调器。
 *
 * 使用场景：Watchlist 页多选移除条目后，同步移除豆瓣平台对应的标记。
 *
 * 后台运行保障（参考 [DoubanTraktStatusConsistencyChecker] 与 [DoubanSyncManager]）：
 * - Application scope 跑移除协程，Activity/Service 销毁不影响
 * - WakeLock 保持 CPU 唤醒，避免息屏 Doze 下网络请求 timeout
 * - Semaphore(2) 控制并发，避免触发豆瓣反爬封禁
 * - 监听 [DoubanRepository.delayEvent]，进度流暴露反爬倒计时信息
 * - [DoubanBatchRemovalService] 显示通知栏进度，带取消按钮
 *
 * 单条移除链路（与豆瓣失败详情页 [DoubanItemDetailViewModel.removeMark] 一致）：
 * 1. 查同步表 by imdbId（快速路径，无网络请求）
 * 2. 未命中 → [DoubanRepository.findDoubanId]（可能触发 m.douban.com 网络搜索）
 * 3. [DoubanRepository.fetchCsrfToken] 拿 ck
 * 4. [DoubanRepository.removeMark] POST /subject/{id}/remove + ck（302 确认生效）
 */
@Singleton
class DoubanBatchRemovalManager @Inject constructor(
    private val doubanRepository: DoubanRepository,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanSyncedItemDao: DoubanSyncedItemDao,
    @ApplicationContext private val appContext: Context
) {
    companion object {
        private const val TAG = "BatchRemoval"
        /** 豆瓣标记并发度（单条操作+反爬延迟，控制并发避免封禁） */
        private const val DOUBAN_CONCURRENCY = 2
    }

    private val _progress = MutableStateFlow(BatchRemovalProgress())
    val progress: StateFlow<BatchRemovalProgress> = _progress.asStateFlow()

    /** Application scope：移除协程在此运行，Activity/Service 销毁不影响 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var removalJob: Job? = null

    /** F-01: 改用 AtomicBoolean 避免 check-then-act 竞态。
     *  原实现 @Volatile var Boolean 在 cancel() 写入与 startRemoval() 重置之间存在非原子窗口,
     *  AtomicBoolean 的 set/get 本身原子,语义更明确。 */
    private val cancelled = AtomicBoolean(false)

    /** WakeLock:移除期间保持 CPU 唤醒,避免息屏 Doze 模式下网络请求 timeout */
    private var wakeLock: PowerManager.WakeLock? = null

    init {
        // 监听 DoubanRepository 的延时事件，合并到 progress.delayInfo
        appScope.launch {
            doubanRepository.delayEvent.collect { info ->
                _progress.value = _progress.value.copy(delayInfo = info)
            }
        }
    }

    /** 移除是否在运行中 */
    fun isRunning(): Boolean = removalJob?.isActive == true

    /** 重置进度状态（完成后调用，避免 StateFlow 旧值 isComplete=true 导致重复弹窗） */
    fun resetProgress() {
        if (isRunning()) return  // 运行中不重置
        _progress.value = BatchRemovalProgress()
    }

    /** 取消正在进行的移除 */
    fun cancel() {
        cancelled.set(true)
        _progress.value = _progress.value.copy(
            isCancelling = true,
            phase = BatchRemovalPhase.CANCELLING,
            delayInfo = null
        )
    }

    /**
     * 启动批量移除豆瓣标记（非 suspend，立即返回，进度通过 [progress] StateFlow 暴露）。
     *
     * @param items 待移除条目列表（需要 traktId、imdbId、title）
     * @param isMovie true=电影，false=电视剧
     * @return true=已启动；false=已有移除在运行（防重入）或未登录豆瓣
     */
    fun startRemoval(items: List<BatchRemovalItem>, isMovie: Boolean): Boolean {
        if (isRunning()) return false
        if (items.isEmpty()) return false
        // 未登录豆瓣时静默跳过（Trakt 已移除，不阻塞用户）
        val cred = doubanAuthStorage.getCredentials() ?: return false

        cancelled.set(false)
        acquireWakeLock()
        val mediaTypeStr = if (isMovie) "movie" else "show"
        val total = items.size

        removalJob = appScope.launch {
            _progress.value = BatchRemovalProgress(
                isRunning = true,
                total = total,
                phase = BatchRemovalPhase.REMOVING,
                startTimeMs = System.currentTimeMillis()
            )
            val successCount = AtomicInteger(0)
            val failCount = AtomicInteger(0)
            val skipCount = AtomicInteger(0)
            val current = AtomicInteger(0)

            try {
                val semaphore = Semaphore(DOUBAN_CONCURRENCY)
                coroutineScope {
                    items.map { item ->
                        async {
                            semaphore.withPermit {
                                // 用户取消时立即停止派发新条目
                                if (cancelled.get()) return@withPermit

                                // 更新当前条目（UI 展示）
                                val cur = current.get()
                                _progress.value = _progress.value.copy(
                                    current = cur,
                                    currentTitle = item.title
                                )

                                val ok = runCatching {
                                    // 1. 优先用 item.doubanId (豆瓣模式直接传,避免网络请求)
                                    //    否则查同步表 by imdbId (Trakt 模式快速路径)
                                    //    最后才 findDoubanId 网络搜索
                                    // 注意: 用括号明确优先级,避免 ?: 被 else 分支吞掉
                                    val doubanId = item.doubanId
                                        ?: (if (item.imdbId.isNotBlank()) {
                                            runCatching { doubanSyncedItemDao.getByImdbId(item.imdbId) }
                                                .getOrNull()?.doubanId
                                        } else null)
                                            ?: doubanRepository.findDoubanId(item.traktId, item.imdbId, mediaTypeStr)
                                    if (doubanId == null) {
                                        Log.w(TAG, "找不到 doubanId, 跳过: traktId=${item.traktId}, imdbId=${item.imdbId}, title=${item.title}")
                                        return@runCatching null  // skip
                                    }
                                    // 2. 获取 ck
                                    val ck = doubanRepository.fetchCsrfToken(doubanId, cred.cookie)
                                    if (ck == null) {
                                        Log.w(TAG, "获取 ck 失败: $doubanId")
                                        return@runCatching false  // fail
                                    }
                                    // 3. 移除标记
                                    doubanRepository.removeMark(doubanId, cred.cookie, ck).success
                                }.getOrElse { e ->
                                    Log.e(TAG, "移除豆瓣标记异常: traktId=${item.traktId}", e)
                                    false
                                }

                                val newCur = current.incrementAndGet()
                                when (ok) {
                                    null -> skipCount.incrementAndGet()
                                    true -> successCount.incrementAndGet()
                                    false -> failCount.incrementAndGet()
                                }
                                _progress.value = _progress.value.copy(
                                    current = newCur,
                                    successCount = successCount.get(),
                                    failCount = failCount.get(),
                                    skipCount = skipCount.get()
                                )
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "批量移除异常", e)
            } finally {
                releaseWakeLock()
                _progress.value = _progress.value.copy(
                    isRunning = false,
                    isComplete = true,
                    isCancelling = false,
                    currentTitle = null,
                    current = total,
                    phase = if (cancelled.get()) BatchRemovalPhase.CANCELLED else BatchRemovalPhase.DONE
                )
                Log.i(TAG, "豆瓣批量移除完成: 成功 ${successCount.get()}, 失败 ${failCount.get()}, 跳过 ${skipCount.get()}, 共 $total 条")
            }
        }
        return true
    }

    /** 获取 PARTIAL_WAKE_LOCK,保持 CPU 唤醒避免息屏后网络请求 timeout */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackToSearch:BatchRemoval")
        // 30 分钟超时,避免异常情况下 WakeLock 泄漏(批量移除通常在几分钟内完成)
        wakeLock?.acquire(30 * 60 * 1000L)
    }

    /** 释放 WakeLock */
    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }
}
