package com.tracktosearch.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 日签实体：一天一行，记下那天开屏给的是哪条台词。
 *
 * 必须落库而不是按日期重算，因为选片不是纯函数：
 * 台词库以后会加条目（取模的池子一变，历史全错位）、海报没就绪时会在已就绪子集里改取一次模、
 * 装完第一屏还有固定的开场那一条。日签日历要能翻回三个月前那天真正看过的句子，
 * 就只能在当天把 (epochDay, quoteId) 记下来。
 *
 * [epochDay] 用本地日期的 epochDay 作主键：既天然去重（一天一行），
 * 又让「查某个月」变成一次主键区间扫描。用本地日而非 UTC，
 * 「今天」的边界和用户看到的日期一致。
 */
@Entity(tableName = "daily_stamp")
data class DailyStampEntity(
    @PrimaryKey val epochDay: Long,
    val quoteId: String,       // 对应 assets/quotes.json 里的 id
    val stampedAt: Long        // 当天第一次打开 App 的时间戳（毫秒）
)
