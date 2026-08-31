package com.tracktosearch.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tracktosearch.data.local.SplashPosterStore
import com.tracktosearch.data.local.SplashPosterUrls
import com.tracktosearch.data.local.SplashQuote
import com.tracktosearch.data.local.SplashQuoteCatalog
import com.tracktosearch.data.local.SplashQuoteStorage
import com.tracktosearch.data.util.PosterColorExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
 * 跨天自动换；卸载重装也不会重头开始。取模的池子是全库，所以同一天在所有设备上
 * 算出来的是同一条——这是刻意的，「每日一句」的意思就是今天大家读的是同一句。
 *
 * 唯一的例外是新用户最初三个实际打开日：依次展示 [OPENING_QUOTE_IDS]，中间断几天也不跳过；
 * 同一天反复启动仍是当天那条，第三条之后再交回日期取模。
 *
 * 关键约束是「开屏永远不出现占位图」。选片因此分两步：
 * 先算出当天应该展示的那条，海报没就绪时不硬等下载，而是在已就绪的子集里
 * 用同一个日期种子再取一次模。这样开屏永远有画面，且换到哪条仍然只由日期决定，
 * 同一天反复启动不会跳来跳去。
 *
 * 海报取哪一张则统一交给 TMDB（见 [posterPath]）：台词库里那条路径是收录当天抄下来的，
 * 而详情页显示的是 TMDB 当前那一版、还分语言。三处（开屏、日签卡片、详情页）用同一个地址，
 * 点开卡片才不用重下一张图、也不用重算一次主色。查不到一律退回台词库自带的路径，
 * 所以离线和接口出错都只是少了「跟 TMDB 一致」这层好处，不会少一张图。
 */
@Singleton
class SplashQuoteRepository @Inject constructor(
    private val catalog: SplashQuoteCatalog,
    private val posterStore: SplashPosterStore,
    private val storage: SplashQuoteStorage,
    private val tmdbRepository: TmdbRepository,
    private val posterColorExtractor: PosterColorExtractor,
) {

    /** 当天该展示的台词；整池海报都没就绪（首启且 assets 被裁掉）时返回 null，调用方跳过台词层 */
    suspend fun todayQuote(): SplashQuote? {
        val pool = catalog.quotes()
        if (pool.isEmpty()) return null
        val seed = daySeed()
        openingQuote(pool, seed)?.let { return it }
        val designated = pool[(seed % pool.size).toInt()]
        if (posterStore.isReady(designated)) return designated
        val ready = pool.filter { posterStore.isReady(it) }
        if (ready.isEmpty()) return null
        return ready[(seed % ready.size).toInt()]
    }

    /**
     * 某一天按日期取模「本该是」哪条台词。
     *
     * 只给日签日历用：错过签到的日子没有落库的行，未来的日子还没到，两种都只能现算。
     * 这里不看海报就绪、不管固定开场序列——它回答的是「日期落在池子的哪一格」这一个问题，
     * [todayQuote] 那些为了「开屏永远有画面」加的分支在这里都是噪音。
     *
     * 现算的结果不是历史真相：往台词库里加删条目会让取模错位（见 DailyStampEntity 的
     * KDoc），同一个错过的日子在版本更新前后可能换一部片。那天没人看过，没有真相可违背，
     * 所以接受这个代价，换掉一列数据库字段和一次迁移。
     *
     * 用 floorMod 而不是 `%`：1970 年之前的日期 epochDay 是负数，`%` 会给出负下标。
     * 真实用户碰不到，但这个函数的入参是任意日期，不该由调用方来保证这件事。
     */
    suspend fun quoteFor(date: LocalDate): SplashQuote? {
        val pool = catalog.quotes()
        if (pool.isEmpty()) return null
        return pool[Math.floorMod(date.toEpochDay(), pool.size.toLong()).toInt()]
    }

    /**
     * 台词真的渲染出来之后调用；若属于固定开场序列，就按不同本地日期推进一格。
     *
     * 标记放在「渲染成功」而不是「选中」之后：选中之后海报仍可能解码失败，
     * 那时整个台词层会被跳过，这一条其实没人看见，标记掉就等于永远错过了。
     * 同一个时机顺带记下当天看过（[SplashQuoteStorage.markShownOn]）——「今天第一次」
     * 说的就是「今天真的看见过一次」，没有比这里更准的落点。
     */
    suspend fun markShown(quote: SplashQuote) = markShown(quote.id)

    /** UI 层只保留轻量 quoteId 时使用；写入语义与领域对象重载完全一致。 */
    suspend fun markShown(quoteId: String) {
        val today = daySeed()
        val openingIndex = storage.openingSequenceIndex(today, OPENING_QUOTE_IDS.size)
        if (openingIndex != null && OPENING_QUOTE_IDS[openingIndex] == quoteId) {
            storage.markOpeningQuoteShown(openingIndex, today, OPENING_QUOTE_IDS.size)
        }
        storage.markShownOn(today)
    }

    /**
     * 今天是不是第一次看开屏台词，台词层的停留时长按它分档。
     *
     * 必须在 [markShown] 之前问，否则今天已经被记下，永远返回 false。
     */
    suspend fun isFirstShowToday(): Boolean = storage.isFirstShowToday(daySeed())

    /**
     * 新用户最初三个实际打开日固定给 [OPENING_QUOTE_IDS]，序列结束后返回 null 走常规路径。
     *
     * 三条都是内置海报，所以这条路径不会出现没图的情况；万一台词库里缺了当前 id
     * （库被改过），就当作没有例外，直接落回日期取模。
     */
    private suspend fun openingQuote(pool: List<SplashQuote>, today: Long): SplashQuote? {
        val index = storage.openingSequenceIndex(today, OPENING_QUOTE_IDS.size) ?: return null
        val id = OPENING_QUOTE_IDS[index]
        return pool.firstOrNull { it.id == id && posterStore.isReady(it) }
    }

    /**
     * 开屏散场之后的收尾：预取未来 [days] 天要用的海报，再把当天那张的沉浸色算好。
     *
     * 只取一小段而不是整池：台词库覆盖全年 365 条，整池海报有十几 MB，
     * 启动阶段不该为了半年后的画面占用带宽。整池补齐交给不计费网络下的
     * [com.tracktosearch.data.worker.SplashPosterWorker]。
     *
     * 取色排在最后，而且先等一段：解位图加色彩量化是实打实的 CPU 活儿，台词层那几秒要留给动画。
     * 等的时长按台词层最长时长取（见 SplashQuoteTiming，当天首看是 5 秒停留加首尾动画）。
     * 调用方若有更准的时机——台词层散场的回调——直接调 [warmPosterColor] 更好，
     * 重复调用在主色已缓存时只是一次查询。
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
        delay(SPLASH_QUOTE_MAX_DURATION_MS)
        warmPosterColor()
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
                    gate.withPermit { posterStore.download(quote, posterPath(quote)) }
                }
            }
        }
    }

    /**
     * 这条台词该用哪张海报：问 TMDB 当前那一版，问不到退回台词库自带的那条。
     *
     * 台词库里的路径是收录当天抄下来的，TMDB 之后可能换图，而且海报分语言；
     * 详情页拿的是 TMDB 这一版，开屏和日签卡片跟着它才算「同一张」。
     * 电影和剧集分两个命名空间查（见 [TmdbRepository.posterPath]），剧集条目不会拿到别人的海报。
     *
     * 网络不通、接口 404、缓存没命中都会走兜底，所以这个方法不会失败——
     * 「开屏永远不出现占位图」那条约束不依赖 TMDB 是否可达。
     */
    private suspend fun posterPath(quote: SplashQuote): String {
        val fromTmdb = tmdbRepository.posterPath(quote.tmdbId, quote.tmdbMediaType())
        return fromTmdb?.takeIf { it.isNotBlank() } ?: quote.posterPath
    }

    /** 台词库的 mediaType 映射到 TMDB 命名空间：`show` 查剧集，其余按电影 */
    private fun SplashQuote.tmdbMediaType(): MediaType =
        if (mediaType == SplashQuote.MEDIA_TYPE_SHOW) MediaType.SHOW else MediaType.MOVIE

    /**
     * 把当天这张海报的主色先算好，写进 PosterColorCache（key 就是海报地址）。
     *
     * 为的是从日签卡片点进详情页时沉浸背景与海报同帧出现：详情页查的地址与这里完全一样
     * （都出自 [SplashPosterUrls]），命中缓存就不必等海报加载完再提取一次。
     *
     * 时机必须在开屏散场之后。解位图加色彩量化是实打实的 CPU 活儿，
     * 开屏那几秒要留给动画，多这一份计算就可能掉帧；而它晚几秒做完全不影响效果，
     * 用户走到日签卡片至少还要几次点击。已经算过的直接跳过，稳定状态下这里只是一次缓存查询。
     *
     * [shownQuoteId] 传开屏真正展示过的那条，口径同 [DailyStampRepository.checkIn]。
     * 不传就重新走当天选片；固定序列在同一个本地日内保持同一条，正常轮换也仍由日期决定，
     * 所以结果稳定。调用方有真实展示 id 时直接传入，少一次目录查找。
     */
    suspend fun warmPosterColor(shownQuoteId: String? = null) {
        try {
            val quote = shownQuoteId?.let { id -> catalog.quotes().firstOrNull { it.id == id } }
                ?: todayQuote()
                ?: return
            val url = posterStore.rememberPosterUrl(quote, posterPath(quote))
            if (posterColorExtractor.getCachedColor(url) != null) return
            val bytes = posterStore.readBytes(quote) ?: return
            val bitmap = decodeForColor(bytes) ?: return
            posterColorExtractor.extractDominantColor(url, bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 预热失败没有任何副作用：详情页仍会在海报加载完之后自己提取一次主色
        }
    }

    /**
     * 只为取色解一次位图，降采样 4 倍。
     *
     * 取色前还要缩到 48px（见 [PosterColorExtractor]），按原尺寸解出来的像素全是白解的：
     * w342 的海报降到四分之一仍有 85px 宽，量化足够用，内存和耗时都省一个数量级。
     */
    private fun decodeForColor(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = COLOR_SAMPLE_STEP }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** 本地日期的 epochDay：用本地日而非 UTC，跨零点换台词的时刻和用户的「今天」一致 */
    private fun daySeed(): Long = LocalDate.now().toEpochDay()

    companion object {
        /** 新用户最初三个实际打开日的固定顺序；海报都必须随 APK 内置 */
        val OPENING_QUOTE_IDS = listOf(
            "la-la-land",
            "we-made-a-beautiful-bouquet",
            "the-great-buddha-plus",
        )
        private const val DEFAULT_PREFETCH_DAYS = 7
        private const val MAX_PARALLEL_DOWNLOADS = 3

        /** 取色前的降采样倍数，见 [decodeForColor] */
        private const val COLOR_SAMPLE_STEP = 4

        /**
         * 台词层最长能演多久（毫秒），[prefetchUpcoming] 用它把取色推到散场之后。
         *
         * 取自 SplashQuoteTiming：当天首看是 5 秒停留，加上浮现和退场不到 6.5 秒，
         * 这里留到 7 秒。多等一会儿没有代价——用户走到日签卡片还有好几次点击。
         */
        private const val SPLASH_QUOTE_MAX_DURATION_MS = 7_000L
    }
}
