package com.tracktosearch.data.repository

import com.tracktosearch.data.local.SplashPosterStore
import com.tracktosearch.data.local.SplashQuote
import com.tracktosearch.data.local.SplashQuoteCatalog
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 开屏台词的选片与海报预取。
 *
 * 「每天同一条」用日期取模实现，不存任何游标：同一天进出 App 多次拿到的是同一条，
 * 跨天自动换；卸载重装也不会重头开始。
 *
 * 关键约束是「开屏永远不出现占位图」。选片因此分两步：
 * 先算出当天应该展示的那条，海报没就绪时不硬等下载，而是在已就绪的子集里
 * 用同一个日期种子再取一次模。这样开屏永远有画面，且换到哪条仍然只由日期决定，
 * 同一天反复启动不会跳来跳去。
 */
@Singleton
class SplashQuoteRepository @Inject constructor(
    private val catalog: SplashQuoteCatalog,
    private val posterStore: SplashPosterStore,
) {

    /** 当天该展示的台词；整池海报都没就绪（首启且 assets 被裁掉）时返回 null，调用方跳过台词层 */
    suspend fun todayQuote(): SplashQuote? {
        val pool = catalog.quotes()
        if (pool.isEmpty()) return null
        val seed = daySeed()
        val designated = pool[(seed % pool.size).toInt()]
        if (posterStore.isReady(designated)) return designated
        val ready = pool.filter { posterStore.isReady(it) }
        if (ready.isEmpty()) return null
        return ready[(seed % ready.size).toInt()]
    }

    /**
     * 预取未来 [days] 天要用的海报，供启动后台调用。
     *
     * 只取一小段而不是整池：台词库覆盖全年 365 条，整池海报有十几 MB，
     * 启动阶段不该为了半年后的画面占用带宽。整池补齐交给不计费网络下的
     * [com.tracktosearch.data.worker.SplashPosterWorker]。
     */
    suspend fun prefetchUpcoming(days: Int = DEFAULT_PREFETCH_DAYS) {
        val pool = catalog.quotes()
        if (pool.isEmpty()) return
        val seed = daySeed()
        val targets = (0 until days)
            .map { pool[((seed + it) % pool.size).toInt()] }
            .distinctBy { it.id }
            .filterNot { posterStore.isReady(it) }
        download(targets)
    }

    /** 整池补齐，Worker 在不计费网络下调用；顺带清掉已下线台词的遗留文件 */
    suspend fun prefetchAll() {
        val pool = catalog.quotes()
        if (pool.isEmpty()) return
        posterStore.pruneOrphans(pool.map { it.id }.toSet())
        download(pool.filterNot { posterStore.isReady(it) })
    }

    /** 整池是否已全部就绪，Worker 据此决定还要不要再排下一次 */
    suspend fun isPoolComplete(): Boolean {
        val pool = catalog.quotes()
        return pool.isNotEmpty() && pool.all { posterStore.isReady(it) }
    }

    /**
     * 并发下载，同时最多 3 个。
     *
     * 限并发是因为这活儿跑在用户正在用 App 的时候：海报再重要也不该和当前页面的
     * 图片请求抢连接。单条失败不影响其它条，下次启动或 Worker 会再补。
     */
    private suspend fun download(targets: List<SplashQuote>) {
        if (targets.isEmpty()) return
        val gate = Semaphore(MAX_PARALLEL_DOWNLOADS)
        coroutineScope {
            targets.forEach { quote ->
                launch {
                    gate.withPermit { posterStore.download(quote) }
                }
            }
        }
    }

    /** 本地日期的 epochDay：用本地日而非 UTC，跨零点换台词的时刻和用户的「今天」一致 */
    private fun daySeed(): Long = LocalDate.now().toEpochDay()

    companion object {
        private const val DEFAULT_PREFETCH_DAYS = 7
        private const val MAX_PARALLEL_DOWNLOADS = 3
    }
}
