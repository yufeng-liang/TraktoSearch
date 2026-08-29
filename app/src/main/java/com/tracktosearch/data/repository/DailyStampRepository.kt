package com.tracktosearch.data.repository

import com.tracktosearch.data.local.SplashPosterStore
import com.tracktosearch.data.local.SplashQuote
import com.tracktosearch.data.local.SplashQuoteCatalog
import com.tracktosearch.data.local.db.DailyStampDao
import com.tracktosearch.data.local.db.DailyStampEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一天的日签：那天来过，以及那天开屏给的是哪条台词。
 *
 * [quote] 可能为 null：日签只存了 id，台词万一从库里消失就解析不出来。
 * 按约定台词 id 只增不删（见 AGENTS.md），所以这是防御性的分支——
 * 真发生时格子照旧显示「来过」，只是点不开卡片，不能凭一个空壳硬凑一张卡。
 *
 * [poster] 是 Coil 能直接加载的来源（内置海报的 assets URI 或磁盘文件），
 * 由数据层解析而不是让 UI 自己去碰 SplashPosterStore。海报还没下载时为 null。
 */
data class DailyStamp(
    val date: LocalDate,
    val quote: SplashQuote?,
    val poster: Any?,
) {
    /** 卡片能不能打开：解析不出台词的格子只是个印记 */
    val openable: Boolean get() = quote != null
}

/**
 * 日签：打开 App 就算来过，一天记一条。
 *
 * 为什么必须落库而不是按日期回算：选片不是纯函数。台词库以后会加条目（取模的池子一变，
 * 历史全错位）、海报没就绪时会在已就绪子集里改取一次模（同一天不同设备可能不同）、
 * 装完第一屏还有固定的开场那一条。日历要能翻回上个月那天真正读过的句子，
 * 就只能在当天把 (epochDay, quoteId) 写下来。
 *
 * 写入用 INSERT OR IGNORE，首写生效：同一天会被写两次（冷启动一次、跨零点回到前台再一次），
 * 而两次之间海报可能补齐、选中的那条随之变化——认第一次，才和当天开屏看到的那条对得上。
 */
@Singleton
class DailyStampRepository @Inject constructor(
    private val dao: DailyStampDao,
    private val quotes: SplashQuoteRepository,
    private val catalog: SplashQuoteCatalog,
    private val posterStore: SplashPosterStore,
) {

    /**
     * 记下今天来过。
     *
     * [shownQuoteId] 传开屏真正渲染出来的那条 id。必须传而不是让这里自己再算一次：
     * 装完第一屏有固定的开场那一条，而它展示完就被标记掉了——开屏之后再调
     * [SplashQuoteRepository.todayQuote] 拿到的是日期取模那条，跟当天屏幕上的不是同一部片。
     *
     * 开屏台词被用户关掉、或海报解码失败整层跳过时传 null，这里退回按日期算：
     * 那天照样来过，日历上不该空一格。真拿不到台词（台词库为空、整池海报都没就绪）才不写——
     * 日签的意义在那句话上，记一个解析不出台词的空日子没有价值，下次启动会再试。
     *
     * @param shownQuoteId 开屏展示过的台词 id，没展示则 null
     * @param today 今天，注入以便测试；线上一律用系统日期
     * @return true 表示这次写入成功，即今天的第一次；已签到过或拿不到台词都是 false
     */
    suspend fun checkIn(shownQuoteId: String? = null, today: LocalDate = LocalDate.now()): Boolean {
        val epochDay = today.toEpochDay()
        if (dao.find(epochDay) != null) return false
        val quoteId = shownQuoteId ?: quotes.todayQuote()?.id ?: return false
        val inserted = dao.insertIfAbsent(
            DailyStampEntity(
                epochDay = epochDay,
                quoteId = quoteId,
                stampedAt = System.currentTimeMillis(),
            )
        )
        return inserted != IGNORED
    }

    /** 某天的日签，没签到过返回 null */
    suspend fun stamp(date: LocalDate): DailyStamp? {
        val entity = dao.find(date.toEpochDay()) ?: return null
        return resolve(entity, index())
    }

    /**
     * 某个月的日签，按日期升序；只含签到过的那些天，没来过的日子不占位。
     *
     * 月视图的空格由 UI 按 [YearMonth] 自己排，仓库不返回空日子——
     * 一个月里没签到的格子在数据层没有任何内容可带。
     */
    suspend fun month(month: YearMonth): List<DailyStamp> {
        val index = index()
        return dao.range(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay())
            .map { resolve(it, index) }
    }

    /** 同 [month]，但随当天签到落库实时更新，日历页订阅这个 */
    fun observeMonth(month: YearMonth): Flow<List<DailyStamp>> =
        dao.observeRange(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay())
            .map { entities ->
                val index = index()
                entities.map { resolve(it, index) }
            }

    /**
     * 连续签到天数，从 [today] 往回数。
     *
     * 今天还没签到就是 0：日历只在 App 里看得到，进来之前签到已经写完，
     * 真出现 0 说明台词没拿到，那天确实不该算。
     */
    suspend fun streak(today: LocalDate = LocalDate.now()): Int {
        val epochDay = today.toEpochDay()
        if (dao.find(epochDay) == null) return 0
        val start = dao.streakStart(epochDay) ?: return 0
        return (epochDay - start + 1).toInt()
    }

    /** 累计签到天数 */
    suspend fun totalDays(): Int = dao.count()

    /** 日历能往前翻到哪个月为止：第一次签到那个月 */
    suspend fun earliestMonth(): YearMonth? =
        dao.earliestDay()?.let { YearMonth.from(LocalDate.ofEpochDay(it)) }

    private suspend fun index(): Map<String, SplashQuote> =
        catalog.quotes().associateBy { it.id }

    private fun resolve(entity: DailyStampEntity, index: Map<String, SplashQuote>): DailyStamp {
        val quote = index[entity.quoteId]
        return DailyStamp(
            date = LocalDate.ofEpochDay(entity.epochDay),
            quote = quote,
            poster = quote?.let { posterStore.posterModel(it) },
        )
    }

    private companion object {
        /** Room 的 INSERT OR IGNORE 在冲突被忽略时返回的 rowId */
        const val IGNORED = -1L
    }
}
