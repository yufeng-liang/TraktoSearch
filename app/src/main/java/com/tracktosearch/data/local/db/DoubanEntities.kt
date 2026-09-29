package com.tracktosearch.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/**
 * SQLite 单条语句的绑定变量上限（Android 框架 SQLite 的 SQLITE_MAX_VARIABLE_NUMBER 为 999）。
 * 豆瓣想看+看过合计很容易超过该值，批量 IN 必须按此大小分块，否则抛 SQLiteException。
 * 取 900 留出余量。
 */
private const val SQLITE_MAX_VARIABLES = 900

/**
 * 豆瓣→Trakt 同步记录。
 *
 * 用途:
 * - Trakt 模式: 记录已同步条目,「重新导入」时跳过
 * - 豆瓣独立模式: 作为 watchlist 主数据源（未连接 trakt 时）,
 *   扩展字段 tmdbId/displayTitle/year/genres/posterUrl/listedAt 支持本地列表渲染,
 *   pendingSync 标记豆瓣 API 失败时的乐观更新条目,下次同步重试
 */
@Entity(
    tableName = "douban_synced_items",
    indices = [Index("imdbId"), Index("status"), Index("pendingSync")]
)
data class DoubanSyncedItem(
    @PrimaryKey val doubanId: String,
    val imdbId: String?,
    val traktId: Int?,
    val title: String,
    val status: String,                 // "wish" | "collect"
    val rating: Int?,                   // 1-5
    val syncedAt: Long,
    val mediaType: String,              // "movie" | "show"
    // —— 豆瓣独立模式扩展字段（v12 新增）——
    val tmdbId: Int? = null,            // TMDB 富化用,同步时 searchByImdb/Tmdb 拿到后回写
    val displayTitle: String? = null,   // 中文标题,TMDB 富化
    val year: Int? = null,              // 年份,TMDB 富化
    val genres: String? = null,         // 类型,TMDB 富化
    val posterUrl: String? = null,      // 海报,TMDB 富化
    val listedAt: String? = null,       // 豆瓣标记时间（区别于 syncedAt 同步时间）
    val pendingSync: Boolean = false,   // 豆瓣 API 失败时乐观更新标记,下次同步重试
    // 豆瓣快照字段：即使没有 IMDb/TMDB/Trakt，也可以直接渲染详情。
    val doubanUrl: String? = null,
    val comment: String? = null,
    val markedAt: String? = null,
    val subtitle: String? = null
)

@Dao
interface DoubanSyncedItemDao {
    @Query("SELECT * FROM douban_synced_items WHERE doubanId = :doubanId")
    suspend fun getByDoubanId(doubanId: String): DoubanSyncedItem?

    /** 按 imdbId 反查同步记录（imdbId 列已有索引），用于详情页预查 doubanId */
    @Query("SELECT * FROM douban_synced_items WHERE imdbId = :imdbId LIMIT 1")
    suspend fun getByImdbId(imdbId: String): DoubanSyncedItem?

    @Query("SELECT doubanId FROM douban_synced_items")
    suspend fun getAllSyncedDoubanIds(): List<String>

    @Query("SELECT * FROM douban_synced_items ORDER BY syncedAt DESC")
    suspend fun getAllSyncedItems(): List<DoubanSyncedItem>

    // —— 豆瓣独立模式专用查询（v12 新增）——

    /** 按 status 拉取条目（豆瓣模式 watchlist 显示 wish / 已看显示 collect） */
    @Query("SELECT * FROM douban_synced_items WHERE status = :status ORDER BY syncedAt DESC")
    suspend fun getByStatus(status: String): List<DoubanSyncedItem>

    /** 拉取待重试的乐观更新条目（pendingSync=true），下次同步时重试豆瓣 API */
    @Query("SELECT * FROM douban_synced_items WHERE pendingSync = 1")
    suspend fun getPendingSyncItems(): List<DoubanSyncedItem>

    /** 删除单条（豆瓣模式移除标记时清本地记录） */
    @Query("DELETE FROM douban_synced_items WHERE doubanId = :doubanId")
    suspend fun deleteByDoubanId(doubanId: String)

    /** 详情页拿到 Rexxar 大图后只更新已有同步记录，避免意外插入个人数据。 */
    @Query("UPDATE douban_synced_items SET posterUrl = :posterUrl WHERE doubanId = :doubanId")
    suspend fun updatePosterUrl(doubanId: String, posterUrl: String)

    /** 豆瓣模式标记操作后更新状态 + pendingSync 标记，同步刷新 syncedAt */
    @Query("UPDATE douban_synced_items SET status = :status, pendingSync = :pendingSync, syncedAt = :now WHERE doubanId = :doubanId")
    suspend fun updateStatusAndPendingSync(doubanId: String, status: String, pendingSync: Boolean, now: Long = System.currentTimeMillis())

    /** 失败项详情页 fallback 数据源场景: 用户手动标注媒体类型时同步更新本地表（与 douban_sync_failures 双写） */
    @Query("UPDATE douban_synced_items SET mediaType = :mediaType WHERE doubanId = :doubanId")
    suspend fun updateMediaType(doubanId: String, mediaType: String)

    /** 重试成功后清除 pendingSync 标记 */
    @Query("UPDATE douban_synced_items SET pendingSync = 0 WHERE doubanId = :doubanId")
    suspend fun clearPendingSync(doubanId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DoubanSyncedItem>)

    /** 按豆瓣 ID 覆盖写入一条完整快照。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DoubanSyncedItem)

    /** 按豆瓣 ID 覆盖写入多条完整快照。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<DoubanSyncedItem>)

    /** 仅更新已存在的完整快照，不会因 ID 不存在而插入意外记录。 */
    @Update
    suspend fun update(item: DoubanSyncedItem)

    /** 删除指定快照；ID 不存在时无副作用。 */
    @Delete
    suspend fun delete(item: DoubanSyncedItem)

    @Query("DELETE FROM douban_synced_items")
    suspend fun clearAll()

    @Query("DELETE FROM douban_synced_items WHERE doubanId IN (:ids)")
    suspend fun deleteByDoubanIdsChunk(ids: List<String>)

    /**
     * 删除不在 [doubanIds] 内的本地记录（全量同步收尾清理已从豆瓣移除的条目）。
     *
     * **不能直接把 NOT IN 的列表分块**：`DELETE WHERE doubanId NOT IN (chunk1)`
     * 会把属于 chunk2..N 的合法记录一并删掉，反而制造数据丢失。正确做法是先取出
     * 全量 doubanId 在内存中求差集，再按正向 IN 分块删除（各块并集等价于差集）。
     *
     * 另外整条 `NOT IN (:doubanIds)` 在条目数超过 [SQLITE_MAX_VARIABLES] 时会抛
     * SQLiteException，且该步骤位于失败项持久化之前，会连带丢掉本次的失败记录。
     *
     * @param doubanIds 本次从豆瓣完整抓取到的 ID；空列表视为清空
     */
    suspend fun deleteNotInDoubanIds(doubanIds: List<String>) {
        if (doubanIds.isEmpty()) {
            clearAll()
            return
        }
        val keep = doubanIds.toHashSet()
        val stale = getAllSyncedDoubanIds().filter { it !in keep }
        for (chunk in stale.chunked(SQLITE_MAX_VARIABLES)) {
            deleteByDoubanIdsChunk(chunk)
        }
    }

    @Query("SELECT COUNT(*) FROM douban_synced_items")
    suspend fun count(): Int

    /** 状态统一检查后回写本地表 status（豆瓣侧标记成功后同步本地记录）。
     *  F-11: 同步刷新 syncedAt 时间戳,便于后续排查"最近一次同步时间" */
    @Query("UPDATE douban_synced_items SET status = :status, syncedAt = :now WHERE doubanId = :doubanId")
    suspend fun updateStatus(doubanId: String, status: String, now: Long = System.currentTimeMillis())

    /** 替换全部同步记录（事务包装，避免 clearAll 后 insertAll 失败导致数据丢失） */
    @Transaction
    suspend fun replaceAll(items: List<DoubanSyncedItem>) {
        clearAll()
        insertAll(items)
    }
}

/**
 * 豆瓣同步失败项持久化记录。
 *
 * 用于:
 * - 下次重新导入豆瓣时检测到失败项 → 提示用户「重试上次失败的 N 项」
 * - 重试成功的项从表中删除
 * - 重试仍失败的项 attemptCount+1,attemptCount>=3 时不再自动建议重试
 *
 * 写入策略:每次同步完成后按 status 分组覆盖写入(先删同 status 旧失败项,再插新的)。
 */
@Entity(
    tableName = "douban_sync_failures",
    indices = [Index("status"), Index("failureReason")]
)
data class DoubanSyncFailureEntity(
    @PrimaryKey val doubanId: String,
    val title: String,
    val posterUrl: String?,
    val rating: Int?,
    val comment: String?,
    val markedAt: String,
    val doubanUrl: String,
    val status: String,             // "wish" / "collect"
    val failureReason: String,     // FailureReason.name
    val failedAt: Long,
    val updatedAt: Long = 0L,       // mediaType/subtitle/status 等字段最近修改时间，用于云同步时间戳比较
    val attemptCount: Int = 0,
    val mediaType: String? = null,  // "movie" / "show" / null(未分类),用户手动标注
    val mediaTypeCleared: Boolean = false, // 用户主动清除标注(区分"从未标注"与"清除标注",防止全局池/自动推断重新填充)
    val subtitle: String? = null    // 用户手动编辑的子标题/外文标题/别名,用于资源搜索
)

@Dao
interface DoubanSyncFailureDao {
    @Query("SELECT * FROM douban_sync_failures")
    suspend fun getAll(): List<DoubanSyncFailureEntity>

    @Query("SELECT * FROM douban_sync_failures WHERE status = :status")
    suspend fun getByStatus(status: String): List<DoubanSyncFailureEntity>

    @Query("SELECT * FROM douban_sync_failures WHERE doubanId = :doubanId")
    suspend fun getById(doubanId: String): DoubanSyncFailureEntity?

    @Query("SELECT COUNT(*) FROM douban_sync_failures")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DoubanSyncFailureEntity>)

    /** 重试成功后删除单条 */
    @Query("DELETE FROM douban_sync_failures WHERE doubanId = :doubanId")
    suspend fun deleteByDoubanId(doubanId: String)

    /** 按 status 清空旧失败项(同步完成后覆盖写入用) */
    @Query("DELETE FROM douban_sync_failures WHERE status = :status")
    suspend fun deleteByStatus(status: String)

    @Query("DELETE FROM douban_sync_failures")
    suspend fun clearAll()

    /** 更新单条媒体类型标注(用户手动标注为电影/电视剧/未分类)。清除标注(null)时设 mediaTypeCleared=true 防止重新填充。同步刷新 updatedAt 用于云同步时间戳比较 */
    @Query("UPDATE douban_sync_failures SET mediaType = :mediaType, mediaTypeCleared = (CASE WHEN :mediaType IS NULL THEN 1 ELSE 0 END), updatedAt = :now WHERE doubanId = :doubanId")
    suspend fun updateMediaType(doubanId: String, mediaType: String?, now: Long = System.currentTimeMillis())

    /** 写回豆瓣成功后持久化新标记状态(wish/collect)。同步刷新 updatedAt */
    @Query("UPDATE douban_sync_failures SET status = :status, updatedAt = :now WHERE doubanId = :doubanId")
    suspend fun updateStatus(doubanId: String, status: String, now: Long = System.currentTimeMillis())

    /** 查询所有 mediaType 为 null 且未被用户主动清除的失败项 doubanId(用于同步后从全局池填充类型) */
    @Query("SELECT doubanId FROM douban_sync_failures WHERE mediaType IS NULL AND mediaTypeCleared = 0")
    suspend fun getDoubanIdsWithNullMediaType(): List<String>

    /** 批量更新媒体类型(全局池填充用，仅更新 null → 非 null) */
    @Query("UPDATE douban_sync_failures SET mediaType = :mediaType WHERE doubanId = :doubanId AND mediaType IS NULL")
    suspend fun updateMediaTypeIfNull(doubanId: String, mediaType: String)

    /** 更新单条子标题(用于资源搜索)。同步刷新 updatedAt */
    @Query("UPDATE douban_sync_failures SET subtitle = :subtitle, updatedAt = :now WHERE doubanId = :doubanId")
    suspend fun updateSubtitle(doubanId: String, subtitle: String?, now: Long = System.currentTimeMillis())

    /** 批量删除(多选模式删除用) */
    @Query("DELETE FROM douban_sync_failures WHERE doubanId IN (:ids)")
    suspend fun deleteByDoubanIds(ids: List<String>)

    /** 批量更新媒体类型(多选模式标注用,覆盖更新)。清除标注(null)时设 mediaTypeCleared=true。同步刷新 updatedAt */
    @Query("UPDATE douban_sync_failures SET mediaType = :mediaType, mediaTypeCleared = (CASE WHEN :mediaType IS NULL THEN 1 ELSE 0 END), updatedAt = :now WHERE doubanId IN (:ids)")
    suspend fun updateMediaTypeBatch(ids: List<String>, mediaType: String?, now: Long = System.currentTimeMillis())

    /** 替换全部失败项（事务包装，避免 clearAll 后 insertAll 失败导致数据丢失） */
    @Transaction
    suspend fun replaceAll(items: List<DoubanSyncFailureEntity>) {
        clearAll()
        insertAll(items)
    }

    /** 按 status 替换失败项（事务包装） */
    @Transaction
    suspend fun replaceByStatus(status: String, items: List<DoubanSyncFailureEntity>) {
        deleteByStatus(status)
        insertAll(items)
    }
}

/**
 * 豆瓣同步列表爬取进度持久化记录。
 *
 * 用于:
 * - 同步过程中取消时,已爬到的列表数据(pageItems)持久化保留
 * - 下次 App 启动时检测到该表有数据 → 弹续传对话框「上次有 N 条未处理完,是否继续?」
 * - 续传模式:跳过列表爬取,直接从该表加载 pageItems 走 syncBatchToTrakt
 * - 处理完一批后删除已处理的 doubanId(无论成功还是失败)
 * - 处理完所有条目后该表自然清空
 */
@Entity(
    tableName = "douban_sync_pending_items",
    indices = [Index("status")]
)
data class DoubanSyncPendingItemEntity(
    @PrimaryKey val doubanId: String,
    val title: String,
    val posterUrl: String?,
    val rating: Int?,
    val comment: String?,
    val markedAt: String,
    val doubanUrl: String,
    val status: String,             // "wish" / "collect"
    val crawledAt: Long             // 爬到该条目的时间戳
)

@Dao
interface DoubanSyncPendingItemDao {
    @Query("SELECT * FROM douban_sync_pending_items")
    suspend fun getAll(): List<DoubanSyncPendingItemEntity>

    @Query("SELECT COUNT(*) FROM douban_sync_pending_items")
    suspend fun count(): Int

    @Query("SELECT * FROM douban_sync_pending_items WHERE status = :status")
    suspend fun getByStatus(status: String): List<DoubanSyncPendingItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DoubanSyncPendingItemEntity>)

    @Query("DELETE FROM douban_sync_pending_items WHERE doubanId IN (:ids)")
    suspend fun deleteByDoubanIdsChunk(ids: List<String>)

    /**
     * 处理完一批后删除已处理的 doubanId(无论成功还是失败)。
     *
     * 正向 IN 可以安全分块:各块的并集等价于整条 `IN (:ids)`。分块是为了避开
     * SQLite 绑定变量上限(见 [SQLITE_MAX_VARIABLES]),超出后整条语句会抛
     * SQLiteException,导致同步在收尾阶段失败。
     */
    suspend fun deleteByDoubanIds(ids: List<String>) {
        for (chunk in ids.chunked(SQLITE_MAX_VARIABLES)) {
            deleteByDoubanIdsChunk(chunk)
        }
    }

    /** 用户选择「完整同步」时清空未处理数据 */
    @Query("DELETE FROM douban_sync_pending_items")
    suspend fun clearAll()

    /** 替换全部 pending items（事务包装） */
    @Transaction
    suspend fun replaceAll(items: List<DoubanSyncPendingItemEntity>) {
        clearAll()
        insertAll(items)
    }
}

/**
 * 豆瓣全量重写同步的回滚记录。
 *
 * 用于:
 * - 「完整重写」同步前,保存用户当前 Trakt 标记(watchlist/history)快照
 * - 同步成功后清除;同步失败/取消时保留
 * - 下次 App 启动时检测到该表有数据 → 提示用户「上次完整同步未完成,是否恢复被删除的标记?」
 * - 恢复后重新批量添加到 Trakt watchlist/history,然后清除该表
 */
@Entity(
    tableName = "douban_sync_rollback",
    indices = [Index("status"), Index("mediaType")]
)
data class DoubanSyncRollbackEntity(
    @PrimaryKey val doubanId: String,
    val traktId: Int,
    val title: String,
    val status: String,         // "wish" | "collect"
    val mediaType: String,      // "movie" | "show"
    val rating: Int?,           // 1-5 (豆瓣评分,恢复时同步到 Trakt ratings)
    val rollbackAt: Long        // 回滚记录创建时间戳
)

@Dao
interface DoubanSyncRollbackDao {
    @Query("SELECT * FROM douban_sync_rollback")
    suspend fun getAll(): List<DoubanSyncRollbackEntity>

    @Query("SELECT COUNT(*) FROM douban_sync_rollback")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DoubanSyncRollbackEntity>)

    @Query("DELETE FROM douban_sync_rollback")
    suspend fun clearAll()

    /** 替换全部回滚记录（事务包装，避免 clearAll 后 insertAll 失败导致回滚数据丢失） */
    @Transaction
    suspend fun replaceAll(items: List<DoubanSyncRollbackEntity>) {
        clearAll()
        insertAll(items)
    }
}

/** 可恢复的一致性检查批次。单例 id=1，账号或数据版本变化时批次失效。 */
@Entity(
    tableName = "douban_consistency_check_runs",
    indices = [Index("status"), Index("accountKey", "dataVersion")]
)
data class DoubanConsistencyCheckRunEntity(
    @PrimaryKey val id: Int = 1,
    val accountKey: String,
    val dataVersion: Long,
    val status: String,
    val phase: String,
    val current: Int,
    val total: Int,
    val startedAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val invalidReason: String?
)

/** 一致性批次中的幂等待处理/修复任务。 */
@Entity(
    tableName = "douban_consistency_check_tasks",
    primaryKeys = ["runId", "doubanId", "action"],
    indices = [Index("runId", "status"), Index("doubanId")]
)
data class DoubanConsistencyCheckTaskEntity(
    val runId: String,
    val doubanId: String,
    val action: String,
    val status: String,
    val attemptCount: Int = 0,
    val errorMessage: String? = null,
    val updatedAt: Long
)

/** 结构化冲突记录，区分普通冲突、Trakt 双状态脏数据、已修复、待重试和错误。 */
@Entity(
    tableName = "douban_consistency_conflicts",
    indices = [Index("runId", "resolutionStatus"), Index("doubanId")]
)
data class DoubanConsistencyConflictEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val doubanId: String,
    val title: String,
    val doubanStatus: String,
    val traktStatus: String,
    val conflictType: String,
    val resolutionStatus: String,
    val errorMessage: String? = null,
    val updatedAt: Long
)

@Dao
interface DoubanConsistencyCheckDao {
    @Query("SELECT * FROM douban_consistency_check_runs WHERE id = 1")
    suspend fun getRun(): DoubanConsistencyCheckRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: DoubanConsistencyCheckRunEntity)

    @Query("DELETE FROM douban_consistency_check_runs WHERE id = 1")
    suspend fun clearRun()

    @Query("SELECT * FROM douban_consistency_check_tasks WHERE runId = :runId AND status IN ('PENDING', 'RETRY')")
    suspend fun getPendingTasks(runId: String): List<DoubanConsistencyCheckTaskEntity>

    @Query("SELECT * FROM douban_consistency_check_tasks WHERE runId = :runId")
    suspend fun getTasks(runId: String): List<DoubanConsistencyCheckTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<DoubanConsistencyCheckTaskEntity>)

    @Query("UPDATE douban_consistency_check_tasks SET status = :status, attemptCount = :attemptCount, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE runId = :runId AND doubanId = :doubanId AND action = :action")
    suspend fun updateTaskStatus(runId: String, doubanId: String, action: String, status: String, attemptCount: Int, errorMessage: String?, updatedAt: Long)

    @Query("DELETE FROM douban_consistency_check_tasks WHERE runId = :runId")
    suspend fun clearTasks(runId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConflicts(conflicts: List<DoubanConsistencyConflictEntity>)

    @Query("SELECT * FROM douban_consistency_conflicts WHERE runId = :runId ORDER BY updatedAt DESC")
    suspend fun getConflicts(runId: String): List<DoubanConsistencyConflictEntity>

    @Query("DELETE FROM douban_consistency_conflicts WHERE runId = :runId")
    suspend fun clearConflicts(runId: String)
}
