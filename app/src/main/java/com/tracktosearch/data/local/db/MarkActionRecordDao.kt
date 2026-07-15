package com.tracktosearch.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MarkActionRecordDao {
    @Insert
    suspend fun insert(record: MarkActionRecordEntity)

    @Insert
    suspend fun insertAll(records: List<MarkActionRecordEntity>)

    /**
     * 分页查询，支持按操作类型/媒体类型/时间范围/标题模糊匹配
     * @param actionTypes 操作类型集合，空则不限（actionTypesEmpty 必须同步传 true）
     * @param mediaTypes 媒体类型集合，空则不限
     * @param startTime 起始时间戳（含），0 则不限
     * @param endTime 结束时间戳（含），0 则不限
     * @param titleQuery 标题模糊匹配（已加 %），null 则不限
     * @param ascending true=时间正序，false=时间倒序（默认）
     */
    @Query("""
        SELECT * FROM mark_action_record
        WHERE (:actionTypesEmpty OR actionType IN (:actionTypes))
          AND (:mediaTypesEmpty OR mediaType IN (:mediaTypes))
          AND (:startTime = 0 OR actedAt >= :startTime)
          AND (:endTime = 0 OR actedAt <= :endTime)
          AND (:titleQuery IS NULL OR title LIKE :titleQuery OR displayTitle LIKE :titleQuery)
        ORDER BY CASE WHEN :ascending = 1 THEN actedAt END ASC,
                 CASE WHEN :ascending = 0 THEN actedAt END DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun query(
        actionTypes: List<String>,
        actionTypesEmpty: Boolean,
        mediaTypes: List<String>,
        mediaTypesEmpty: Boolean,
        startTime: Long,
        endTime: Long,
        titleQuery: String?,
        ascending: Boolean,
        limit: Int,
        offset: Int
    ): List<MarkActionRecordEntity>

    @Query("SELECT COUNT(*) FROM mark_action_record")
    suspend fun count(): Int

    /** 超上限时删最旧的 N 条 */
    @Query("DELETE FROM mark_action_record WHERE id IN (SELECT id FROM mark_action_record ORDER BY actedAt ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    @Query("DELETE FROM mark_action_record")
    suspend fun deleteAll()
}
