package com.tracktosearch.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyStampDao {

    /**
     * 记下某天的日签，已有则原样保留。
     *
     * 用 IGNORE 而不是 REPLACE：同一天可能被写两次（冷启动一次、跨零点回到前台再一次），
     * 也可能在写入之后海报才补齐、选中的那条随之变化。日签要认的是「那天真正展示过的那条」，
     * 所以首写生效、后写作废。
     *
     * @return 插入的 rowId，被忽略时返回 -1
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(stamp: DailyStampEntity): Long

    @Query("SELECT * FROM daily_stamp WHERE epochDay = :epochDay")
    suspend fun find(epochDay: Long): DailyStampEntity?

    /**
     * 查一段日期区间内的日签，供日历月视图用。
     *
     * @param from 起始 epochDay（含）
     * @param to 结束 epochDay（含）
     */
    @Query("SELECT * FROM daily_stamp WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay ASC")
    suspend fun range(from: Long, to: Long): List<DailyStampEntity>

    /** 同 [range]，但随写入实时更新：当天签到落库后日历不用手动刷新 */
    @Query("SELECT * FROM daily_stamp WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay ASC")
    fun observeRange(from: Long, to: Long): Flow<List<DailyStampEntity>>

    /** 累计签到天数 */
    @Query("SELECT COUNT(*) FROM daily_stamp")
    suspend fun count(): Int

    /** 最早签到的那天，用于日历往前翻到哪里为止 */
    @Query("SELECT MIN(epochDay) FROM daily_stamp")
    suspend fun earliestDay(): Long?

    /**
     * 当前连续签到段的起点。
     *
     * 「段起点」= 有签到、但前一天没有的那些天；取 [today] 之前最大的那个。
     * 这样连续天数只要 today - 起点 + 1，不用把所有日期拉回内存走一遍，
     * 也不用给「往回数多少天」拍一个没有依据的上限。
     * 起点到 [today] 之间一定不含空档：若中间断过，断点之后那天也是段起点且更大，与取最大矛盾。
     *
     * @param today 今天的 epochDay
     * @return 段起点的 epochDay；一条都没有时为 null
     */
    @Query("""
        SELECT MAX(s.epochDay) FROM daily_stamp s
        WHERE s.epochDay <= :today
          AND NOT EXISTS (SELECT 1 FROM daily_stamp p WHERE p.epochDay = s.epochDay - 1)
    """)
    suspend fun streakStart(today: Long): Long?
}
