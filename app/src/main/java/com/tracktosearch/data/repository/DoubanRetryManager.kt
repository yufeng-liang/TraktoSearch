package com.tracktosearch.data.repository

import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DoubanDetailInfo
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 重试数据源。
 *
 * - [LocalDatabase]: 从 douban_sync_failures 表读取(默认选项,无需用户操作)
 * - [ImportedJson]: 从用户选择的 JSON 文件加载(跨设备/重装后恢复重试数据)
 */
sealed class RetrySource {
    object LocalDatabase : RetrySource()
    data class ImportedJson(val failures: List<DoubanSyncFailure>) : RetrySource()
}

/**
 * 重试请求。
 *
 * @param source 重试数据源
 * @param selectedReasons 要重试的失败类型集合(默认仅可恢复类型,
 *   用户可在子对话框中手动勾选不可恢复类型强制重试)
 */
data class RetryRequest(
    val source: RetrySource,
    val selectedReasons: Set<FailureReason>
)

/**
 * 豆瓣同步失败项重试状态。
 *
 * 用于 UI 检测是否有可重试的失败项数据。
 */
data class RetryState(
    val totalFailures: Int = 0,
    val recoverableCount: Int = 0,
    val nonRecoverableCount: Int = 0,
    val byReason: Map<FailureReason, Int> = emptyMap(),
    val maxAttemptCount: Int = 0  // 用于 UI 提示「已重试 N 次」
) {
    /** 是否有可重试的失败项 */
    val hasFailures: Boolean get() = totalFailures > 0
}

/**
 * 豆瓣失败项重试管理器。
 *
 * 职责:
 * - 加载本地失败项数据(用于「有失败才弹」入口逻辑检测)
 * - 统计可恢复/不可恢复分布(用于重试选项子对话框)
 * - 编排重试请求:根据 [RetrySource] 加载数据 → 调用 [DoubanSyncManager.startRetry]
 *
 * 重试流程的实际执行(详情页 → TraktId → Trakt 写入)由 [DoubanSyncManager] 负责,
 * 本类只负责数据加载和分发,不参与网络请求。
 */
@Singleton
class DoubanRetryManager @Inject constructor(
    private val doubanSyncFailureDao: DoubanSyncFailureDao,
    private val doubanSyncManager: DoubanSyncManager,
    private val cloudDetailsPoolManager: CloudDetailsPoolManager,
    // 豆瓣独立模式: 失败项详情页 fallback 读取 + 标记操作双写本地表用
    private val doubanSyncedItemDao: DoubanSyncedItemDao
) {
    private val _retryState = MutableStateFlow(RetryState())
    val retryState: StateFlow<RetryState> = _retryState.asStateFlow()

    /** Application scope:监听同步完成事件,不依赖调用者生命周期 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 监听同步进度:isComplete 从 false → true 时自动刷新失败项状态
        // 这样设置页「重试上次失败项」一栏在每次同步完成后自动更新为最新失败数据
        doubanSyncManager.progress
            .map { it.isComplete }
            .distinctUntilChanged()
            .filter { it }  // 只关心 isComplete = true 的变化
            .onEach { refreshRetryState() }
            .launchIn(appScope)
    }

    /** 重新加载失败项统计(用于入口检测和对话框展示) */
    suspend fun refreshRetryState() = withContext(Dispatchers.IO) {
        val entities = doubanSyncFailureDao.getAll()
        val failures = entities.map { DoubanSyncFailure.fromEntity(it) }
        val byReason = failures.groupingBy { it.failureReason }.eachCount()
        val state = RetryState(
            totalFailures = failures.size,
            recoverableCount = failures.count { it.failureReason.recoverable },
            nonRecoverableCount = failures.count { !it.failureReason.recoverable },
            byReason = byReason,
            maxAttemptCount = failures.maxOfOrNull { it.attemptCount } ?: 0
        )
        _retryState.value = state
    }

    /**
     * 从本地 Room 表加载失败项并启动重试。
     *
     * @param selectedReasons 用户勾选的要重试的失败类型集合
     * @return true=已启动;false=同步正在运行(防重入)或无失败项
     */
    suspend fun startRetryFromLocal(selectedReasons: Set<FailureReason>): Boolean =
        withContext(Dispatchers.IO) {
            val entities = doubanSyncFailureDao.getAll()
            val failures = entities.map { DoubanSyncFailure.fromEntity(it) }
            if (failures.isEmpty()) return@withContext false
            doubanSyncManager.startRetry(failures, selectedReasons)
        }

    /**
     * 从导入的 JSON 失败项列表启动重试。
     *
     * 注意:JSON 模式下重试成功的项无法从本地 Room 表删除(JSON 数据是临时的)。
     * 仍然失败的项会覆盖写入本地 Room 表,下次可走「重试上次失败」继续重试。
     *
     * @param failures 从 JSON 解析出的失败项列表
     * @param selectedReasons 用户勾选的要重试的失败类型集合
     * @return true=已启动;false=同步正在运行(防重入)
     */
    fun startRetryFromJson(
        failures: List<DoubanSyncFailure>,
        selectedReasons: Set<FailureReason>
    ): Boolean {
        if (failures.isEmpty()) return false
        return doubanSyncManager.startRetry(failures, selectedReasons)
    }

    /**
     * 清空所有失败项记录(用于用户主动放弃重试)。
     */
    suspend fun clearAllFailures() = withContext(Dispatchers.IO) {
        doubanSyncFailureDao.clearAll()
        _retryState.value = RetryState()
    }

    /** 加载全部失败项(用于失败项查看页) */
    suspend fun getAllFailures(): List<DoubanSyncFailure> = withContext(Dispatchers.IO) {
        doubanSyncFailureDao.getAll().map { DoubanSyncFailure.fromEntity(it) }
    }

    /**
     * 从全局详情池批量刷新本地未标注类型(mediaType IS NULL)的失败项。
     *
     * 进入失败项查看页时后台调用：其他用户已标注类型的条目自动填充到本地，
     * 优先读 entry.mediaType(细分类型),为 null 时降级用 isTvShow 映射,
     * 仅更新 null → 非 null(不覆盖用户已手动标注的值)。
     *
     * @return 本次实际填充的条目数(用于调用方决定是否刷新 UI)
     */
    suspend fun refreshMediaTypesFromCloudPool(): Int = withContext(Dispatchers.IO) {
        val nullIds = doubanSyncFailureDao.getDoubanIdsWithNullMediaType()
        if (nullIds.isEmpty()) return@withContext 0
        val pooled = runCatching {
            cloudDetailsPoolManager.downloadDetails(nullIds)
        }.getOrElse { return@withContext 0 }
        if (pooled.isEmpty()) return@withContext 0
        var updated = 0
        for ((doubanId, entry) in pooled) {
            // 全局池 mediaType 为 null 表示用户主动清除了标注,不降级用 isTvShow 映射,
            // 避免覆盖其他用户的清除操作;仅非 null 时才填充本地 null → 非 null
            val mediaType = entry.mediaType ?: continue
            doubanSyncFailureDao.updateMediaTypeIfNull(doubanId, mediaType)
            updated++
        }
        updated
    }

    /**
     * 按 doubanId 加载单条失败项(用于豆瓣条目详情页)。
     *
     * 查询链路: douban_sync_failures 表 → fallback 查 douban_synced_items 本地表。
     * 豆瓣独立模式下,无 imdbId 的条目不进失败项表(直接写本地表),详情页需通过 fallback 读取。
     * fallback 数据用 NO_IMDB_ID 作为失败原因标记(实际并非失败,仅复用失败项详情页 UI),
     * 缺失字段(comment/doubanUrl/subtitle)用合理默认值填充。
     */
    suspend fun getFailure(doubanId: String): DoubanSyncFailure? = withContext(Dispatchers.IO) {
        doubanSyncFailureDao.getById(doubanId)?.let { DoubanSyncFailure.fromEntity(it) }
            ?: doubanSyncedItemDao.getByDoubanId(doubanId)?.let { it.toFailure() }
    }

    /** DoubanSyncedItem → DoubanSyncFailure 映射（fallback 路径,缺失字段用默认值填充） */
    private fun com.tracktosearch.data.local.db.DoubanSyncedItem.toFailure(): DoubanSyncFailure = DoubanSyncFailure(
        doubanId = doubanId,
        title = title,
        posterUrl = posterUrl,
        rating = rating,
        comment = null,  // synced_items 表无 comment 字段
        markedAt = listedAt ?: "",  // 用豆瓣标记时间兜底
        doubanUrl = "https://movie.douban.com/subject/$doubanId/",  // 豆瓣电影 URL 固定模板
        status = DoubanMarkStatus.fromString(status),
        failureReason = FailureReason.NO_IMDB_ID,  // 进入 fallback 路径的主因是无 imdbId
        failedAt = syncedAt,
        updatedAt = 0L,
        attemptCount = 0,
        mediaType = mediaType,
        mediaTypeCleared = false,
        subtitle = null
    )

    /**
     * 根据豆瓣详情的集数自动推断媒体类型（不覆盖用户已标注的值）。
     *
     * 规则：
     * - 失败项 mediaType 已标注(非 null) → 不覆盖,直接返回 false
     * - episodeCount > 0 → 电视剧类:
     *   - genres 含"综艺" → "variety"
     *   - genres 含"纪录片" → "documentary"
     *   - 否则 → "show"
     * - episodeCount == null/0 → "movie"
     *
     * 调用时机:用户打开失败项详情页,fetchDetail 成功后调用。
     *
     * @return true 表示推断成功并更新了本地 mediaType
     */
    suspend fun inferMediaTypeFromDetail(doubanId: String, detailInfo: DoubanDetailInfo): Boolean = withContext(Dispatchers.IO) {
        val existing = doubanSyncFailureDao.getById(doubanId) ?: return@withContext false

        // 强覆盖：genre 含"综艺"或"真人秀" → 综艺
        val forceType = when {
            detailInfo.genres.any { it.contains("综艺") || it.contains("真人秀") || it.contains("脱口秀") || it.contains("音乐") } -> "variety"
            detailInfo.genres.any { it.contains("纪录片") } -> "documentary"
            else -> null
        }
        if (forceType != null) {
            // 强覆盖：即使已有标注也覆盖
            if (existing.mediaType != forceType) {
                doubanSyncFailureDao.updateMediaType(doubanId, forceType)
                runCatching {
                    cloudDetailsPoolManager.uploadUserMarkedMediaType(doubanId, forceType)
                }
            }
            return@withContext true
        }

        // 非强覆盖：已标注则不覆盖（自动推断是第一优先级,用户清除标注后进入详情页仍会重新推断）
        if (existing.mediaType != null) return@withContext false

        val inferred = if (detailInfo.episodeCount != null && detailInfo.episodeCount > 0) {
            "show"
        } else {
            "movie"
        }
        // 用 updateMediaType 而非 updateMediaTypeIfNull,确保同时清除 mediaTypeCleared 标记
        doubanSyncFailureDao.updateMediaType(doubanId, inferred)
        // 推断的类型也异步上传全局池,供其他用户复用(不覆盖池中已有非null值)
        runCatching {
            cloudDetailsPoolManager.uploadUserMarkedMediaType(doubanId, inferred)
        }
        true
    }

    /** 更新单条媒体类型标注(用户手动标注为电影/电视剧/综艺/纪录片/清除标注)，并异步上传到全局共享池 */
    suspend fun updateMediaType(doubanId: String, mediaType: String?) = withContext(Dispatchers.IO) {
        doubanSyncFailureDao.updateMediaType(doubanId, mediaType)
        // 豆瓣独立模式 fallback 数据源场景: 同步双写本地表(保持两表 mediaType 一致)
        runCatching { doubanSyncedItemDao.updateMediaType(doubanId, mediaType) }
        // 标注(含清除标注null)均异步上传到全局池强覆盖,供其他用户同步
        runCatching {
            cloudDetailsPoolManager.uploadUserMarkedMediaType(doubanId, mediaType)
        }
    }

    /**
     * 批量更新媒体类型(多选模式标注用),并并发上传到全局池。
     *
     * @param doubanIds 要更新的 doubanId 列表
     * @param mediaType "movie"/"show"/"variety"/"documentary"; null=清除标注(也上传强覆盖)
     */
    suspend fun batchUpdateMediaType(doubanIds: List<String>, mediaType: String?) = withContext(Dispatchers.IO) {
        if (doubanIds.isEmpty()) return@withContext
        doubanSyncFailureDao.updateMediaTypeBatch(doubanIds, mediaType)
        // 并发上传到全局池(含清除标注null),并发度 3,避免逐条串行
        val semaphore = kotlinx.coroutines.sync.Semaphore(3)
        coroutineScope {
            doubanIds.map { id ->
                launch {
                    semaphore.withPermit {
                        runCatching {
                            cloudDetailsPoolManager.uploadUserMarkedMediaType(id, mediaType)
                        }
                    }
                }
            }.joinAll()
        }
    }

    /** 批量删除失败项(多选模式删除用) */
    suspend fun batchDeleteFailures(doubanIds: List<String>) = withContext(Dispatchers.IO) {
        if (doubanIds.isEmpty()) return@withContext
        doubanSyncFailureDao.deleteByDoubanIds(doubanIds)
    }

    /** 更新单条子标题(用于资源搜索) */
    suspend fun updateSubtitle(doubanId: String, subtitle: String?) = withContext(Dispatchers.IO) {
        // trim 后空串转 null,避免空子标题触发搜索
        val normalized = subtitle?.trim()?.takeIf { it.isNotBlank() }
        doubanSyncFailureDao.updateSubtitle(doubanId, normalized)
    }

    /** 删除单条失败项(用户在详情页删除,不同于 clearAll)。同时删除本地表对应记录,保持双表一致 */
    suspend fun deleteFailure(doubanId: String) = withContext(Dispatchers.IO) {
        doubanSyncFailureDao.deleteByDoubanId(doubanId)
        // 豆瓣独立模式: 同步删除本地表记录(fallback 数据源场景)
        runCatching { doubanSyncedItemDao.deleteByDoubanId(doubanId) }
    }

    /**
     * 写回豆瓣成功后持久化新的标记状态(wish/collect)。
     * 持久化路径用 status.path(与 toEntity 一致),不从列表移除条目。
     * 同时双写本地表(fallback 数据源场景),保持两表 status 一致。
     */
    suspend fun updateStatus(doubanId: String, status: DoubanMarkStatus) = withContext(Dispatchers.IO) {
        doubanSyncFailureDao.updateStatus(doubanId, status.path)
        // 豆瓣独立模式: 同步更新本地表 status + pendingSync=true(下次同步重试豆瓣 API)
        runCatching { doubanSyncedItemDao.updateStatusAndPendingSync(doubanId, status.path, true) }
    }
}
