package com.tracktosearch.data.repository

import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val doubanSyncManager: DoubanSyncManager
) {
    private val _retryState = MutableStateFlow(RetryState())
    val retryState: StateFlow<RetryState> = _retryState.asStateFlow()

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

    /** 按 doubanId 加载单条失败项(用于豆瓣条目详情页) */
    suspend fun getFailure(doubanId: String): DoubanSyncFailure? = withContext(Dispatchers.IO) {
        doubanSyncFailureDao.getById(doubanId)?.let { DoubanSyncFailure.fromEntity(it) }
    }

    /** 更新单条媒体类型标注(用户手动标注为电影/电视剧/清除标注) */
    suspend fun updateMediaType(doubanId: String, mediaType: String?) = withContext(Dispatchers.IO) {
        doubanSyncFailureDao.updateMediaType(doubanId, mediaType)
    }

    /** 更新单条子标题(用于资源搜索) */
    suspend fun updateSubtitle(doubanId: String, subtitle: String?) = withContext(Dispatchers.IO) {
        // trim 后空串转 null,避免空子标题触发搜索
        val normalized = subtitle?.trim()?.takeIf { it.isNotBlank() }
        doubanSyncFailureDao.updateSubtitle(doubanId, normalized)
    }

    /** 删除单条失败项(用户在详情页删除,不同于 clearAll) */
    suspend fun deleteFailure(doubanId: String) = withContext(Dispatchers.IO) {
        doubanSyncFailureDao.deleteByDoubanId(doubanId)
    }
}
