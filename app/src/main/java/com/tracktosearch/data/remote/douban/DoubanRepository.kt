package com.tracktosearch.data.remote.douban

import com.tracktosearch.data.local.DoubanUserProfile
import com.tracktosearch.data.remote.douban.dto.DoubanRecommendItem
import com.tracktosearch.data.remote.douban.dto.DoubanRecommendResponse
import com.tracktosearch.data.repository.CloudDetailsPoolManager
import com.tracktosearch.data.repository.DoubanPublicDataPoolManager
import com.tracktosearch.data.util.PersistentTtlCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** 豆瓣 Cookie 过期异常（401/403 或响应为登录页）。
 *  message 默认英文（ViewModel error 用英文规范），UI 层基于异常类型映射本地化文案。 */
class DoubanCookieExpiredException(message: String = "Douban cookie expired") : Exception(message)

/** 豆瓣请求在重试后仍失败，供上层区别于 Cookie 失效。 */
class DoubanNetworkException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * 延时信息(用于同步进度弹窗展示倒计时)。
 *
 * 当 DoubanRepository 执行 delay() 前,通过 [DoubanRepository.delayEvent] 上报延时信息,
 * UI 层基于 startMs 和 totalSeconds 计算剩余秒数做倒计时展示。delay() 结束后清 null。
 *
 * @param type 延时类型(决定 UI 展示的文案)
 * @param totalSeconds 总延时秒数
 * @param startMs 延时开始的时间戳(System.currentTimeMillis)
 */
data class DelayInfo(
    val type: DelayType,
    val totalSeconds: Int,
    val startMs: Long
)

/** 延时类型 */
enum class DelayType(val displayKey: String) {
    /** 豆瓣详情页反爬延迟(3-5秒) */
    DOUBAN_DETAIL_CRAWL("douban_detail_crawl"),

    /** 豆瓣列表页爬取延迟(5-10秒) */
    DOUBAN_LIST_CRAWL("douban_list_crawl"),

    /** 豆瓣重试前等待(2-4秒) */
    DOUBAN_RETRY("douban_retry")
}

/**
 * 豆瓣详情页解析结果的持久化缓存条目。
 *
 * 包含 Notion 备份所需的所有字段（条目标题/封面/标记时间/标记状态/短评/评分/豆瓣链接
 * 由 DoubanMarkItem 承载，这里保存详情页补充信息）。doubanId → 这些字段不会变，永久缓存。
 */
@Serializable
data class DoubanDetailCacheEntry(
    val imdbId: String?,
    val isTvShow: Boolean,
    val title: String? = null,         // 条目标题(用于快选列表展示)
    val posterUrl: String? = null,     // 海报地址(用于快选列表展示)
    val genres: List<String> = emptyList(),
    val year: String? = null,
    val countries: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    /**
     * 细分媒体类型: "movie"/"show"/"variety"/"documentary"/null。
     * 用户标注后上传全局池时填充,其他用户拉取后优先读此字段;
     * 为 null 时降级用 isTvShow 映射(true→"show", false→"movie")。
     */
    val mediaType: String? = null,
    // 扩展字段（豆瓣条目页额外提取，用于详情页展示与集数自动分类）
    val doubanRating: Double? = null,      // 豆瓣评分（10 分制，如 9.2；null 表示暂无评分）
    val ratingCount: Int? = null,          // 评分人数
    val summary: String? = null,           // 剧情简介
    val episodeCount: Int? = null,         // 集数（电视剧才有）
    val episodeDuration: String? = null,   // 单集片长（电视剧才有，如"45分钟"）
    val aka: List<String> = emptyList(),   // 又名/译名
    val runtime: String? = null,           // 片长（电影才有，如"120分钟"）
    val writers: List<String> = emptyList(),   // 编剧
    val cast: List<String> = emptyList(),      // 主演名列表
    val languages: List<String> = emptyList(), // 语言
    val initialReleaseDates: List<String> = emptyList(),  // 首播日期(可能多个)
    val ratingDistribution: List<Double> = emptyList(),   // 评分分布 5星→1星百分比
    val celebrities: List<DoubanCelebrityCacheEntry> = emptyList()  // 演职员
)

/** 演职员持久化缓存条目(与 DoubanCelebrity 对应,独立序列化) */
@Serializable
data class DoubanCelebrityCacheEntry(
    val name: String,
    val doubanPersonageUrl: String? = null,
    val avatarUrl: String? = null,
    val role: String? = null
)

/**
 * 豆瓣爬取协调器。
 *
 * 职责：
 * - 分页爬取「想看/看过」标记列表
 * - 爬取条目详情页补全 imdbId（带持久化缓存，避免重复爬取）
 * - 反爬延迟（列表页 5-10 秒，详情页 3-5 秒）
 *
 * fetchDetail 查询链路（减少豆瓣爬取次数）：
 * 内存缓存 → 磁盘缓存（awaitLoaded）→ 全局池 → 爬豆瓣（成功后异步上传全局池）
 *
 * 使用独立的 OkHttpClient（不走 Trakt 拦截器），设置豆瓣所需的 Cookie/UA/Referer。
 */
private const val NEGATIVE_DOUBAN_MAPPING_TTL_MILLIS = 5 * 60 * 1000L

class DoubanRepository(
    private val detailCache: PersistentTtlCache<DoubanDetailCacheEntry>,
    private val cloudDetailsPoolManager: CloudDetailsPoolManager? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; coerceInputValues = true },
    /** traktId→doubanId 永久映射缓存，详情页预查用 */
    private val idMappingCache: PersistentTtlCache<String>? = null,
    /** IMDb/Trakt/TMDB→豆瓣 ID 公共映射池，仅负责直读和异步上传公开映射。 */
    private val publicDataPoolManager: DoubanPublicDataPoolManager? = null
) {
    private val publicUploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val findDoubanIdFlights = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val negativeFindDoubanIdUntil = ConcurrentHashMap<String, Long>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 延时事件流:delay() 前上报延时信息,delay() 后清 null。
     * DoubanSyncManager collect 此流合并到 progress,UI 层做倒计时展示。
     */
    private val _delayEvent = MutableStateFlow<DelayInfo?>(null)
    val delayEvent: StateFlow<DelayInfo?> = _delayEvent.asStateFlow()

    /** 带延时上报的 delay 封装:delay 前设 delayEvent,delay 后清 null */
    private suspend fun delayWithEvent(type: DelayType, millisRange: LongRange) {
        val millis = Random.nextLong(millisRange.first, millisRange.last + 1)
        _delayEvent.value = DelayInfo(type, (millis / 1000).toInt(), System.currentTimeMillis())
        delay(millis)
        _delayEvent.value = null
    }

    // 不自动跟随重定向的客户端: 豆瓣网页删除收藏表单 POST /subject/{id}/remove 成功后返回 302
    // 重定向回详情页, 需禁止自动跟随才能观测到 302 状态码与 Location, 用于确认删除是否真正生效。
    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 爬取指定状态的标记列表，回调每页结果。
     *
     * @param userId 豆瓣用户 ID（从 dbcl2 cookie 解析）
     * @param cookie 完整 Cookie 字符串
     * @param status 想看 / 看过
     * @param onPage 每页结果回调
     * @param onProgress 进度回调（已处理条目数，总数）
     * @param isCancelled 取消检查回调,返回 true 时立即停止爬取(每页爬完检查一次)
     * @return true=正常爬完或无更多条目；false=Cookie 过期中断
     * @throws DoubanNetworkException 网络异常或重试耗尽
     */
    suspend fun fetchMarkList(
        userId: String,
        cookie: String,
        status: DoubanMarkStatus,
        onPage: suspend (List<DoubanMarkItem>, pageIndex: Int) -> Unit,
        onProgress: (current: Int, total: Int?) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        var start = 0
        var pageIndex = 1
        var knownTotal: Int? = null

        while (true) {
            // 每页爬取前检查取消标志,避免取消后还要爬完当前页(5-10 秒反爬延迟)
            if (isCancelled()) break
            val url = "https://movie.douban.com/people/${userId}/${status.path}?start=${start}&sort=time&mode=grid"
            // 捕获网络异常(超时/连接失败),重试一次
            val html = try {
                fetchHtml(url, cookie, validateStatus = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: DoubanCookieExpiredException) {
                return false
            } catch (e: Exception) {
                // 网络异常重试一次
                delayWithEvent(DelayType.DOUBAN_RETRY, 2000L..4000L)
                try {
                    fetchHtml(url, cookie, validateStatus = true)
                } catch (e2: CancellationException) {
                    throw e2
                } catch (_: DoubanCookieExpiredException) {
                    return false
                } catch (e2: Exception) {
                    throw if (e2 is DoubanNetworkException) {
                        e2
                    } else {
                        DoubanNetworkException(
                            message = "Douban mark list request failed after retry",
                            cause = e2
                        )
                    }
                }
            }
            if (DoubanSpider.isLoginPage(html)) return false
            val page = DoubanSpider.parseMarkListPage(html)
            if (!page.isValid) {
                throw DoubanNetworkException("Douban mark list page parsing failed")
            }
            if (page.items.isEmpty()) break

            // 第一页拿到总数后贯穿整个爬取过程
            if (knownTotal == null && page.totalCount != null && page.totalCount > 0) {
                knownTotal = page.totalCount
            }

            onPage(page.items, pageIndex)
            onProgress(start + page.items.size, knownTotal)
            start += 15
            pageIndex++
            // 反爬延迟前再检查一次,取消后不再等待
            if (isCancelled()) break
            // 反爬延迟：每页间 5-10 秒
            delayWithEvent(DelayType.DOUBAN_LIST_CRAWL, 5000L..10000L)
        }
        return true
    }

    /**
     * 爬取条目详情页,返回 imdbId 等补充信息。null 表示 Cookie 过期或失败。
     * 优先查持久化缓存(doubanId → imdbId/isTvShow 永久不变),未命中才爬取详情页。
     * 首次失败时自动重试一次(豆瓣偶发 403/超时)。
     *
     * @param onProgress 进度回调:
     *   - phase="start": 开始处理此条目(title 参数为标题)
     *   - phase="cache_hit": 缓存命中,跳过爬取
     *   - phase="fetching": 正在爬取详情页
     *   - phase="done": 此条目处理完成
     *   - phase="failed": 此条目处理失败
     * @param uploadToCloudPool 是否在详情抓取成功后立即异步上传全局详情池；同步批处理传 false，统一在收尾阶段批量上传
     * @return Pair<详情, 是否命中缓存>,详情为 null 表示失败
     */
    suspend fun fetchDetail(
        doubanUrl: String,
        cookie: String,
        title: String? = null,
        forceRefresh: Boolean = false,
        uploadToCloudPool: Boolean = true,
        onProgress: (phase: String, title: String?) -> Unit = { _, _ -> }
    ): Pair<DoubanDetailInfo?, Boolean> {
        // 从 URL 解析 doubanId 作为缓存 key
        // F-40: regex 未匹配时,输入可能是裸 doubanId(纯数字)或异常 URL。
        // 裸 doubanId 直接用作 key；异常 URL 记录警告,仍用原值作 key 保持向后兼容(避免功能损失)。
        val regexMatch = Regex("""subject/(\d+)""").find(doubanUrl)?.groupValues?.get(1)
        val doubanId = regexMatch ?: if (doubanUrl.all { it.isDigit() }) {
            doubanUrl  // 裸 doubanId(纯数字),直接用作 key
        } else {
            android.util.Log.w("DoubanRepository", "fetchDetail: 无法从 URL 解析 doubanId,缓存 key 退化为原始 URL: $doubanUrl")
            doubanUrl  // 向后兼容:仍用原值作 key,但记录警告便于排查异常 URL
        }

        // forceRefresh=true 时跳过缓存和全局池,直接爬取豆瓣(用于"重新爬取"按钮)
        if (!forceRefresh) {
            // 优先查本地缓存(命中则跳过详情页爬取,省 3-5 秒反爬延迟)
            detailCache.get(doubanId)?.let { entry ->
                if (!entry.title.isNullOrBlank()) {
                    onProgress("cache_hit", title)
                    return Pair(entry.toDetailInfo(), true) // 命中本地缓存(标题非空=字段完善)
                }
            }
            // 缓存未命中时等待磁盘加载完成再查一次,避免 loadFromDisk 未完成时误判为缓存未命中
            // 导致不必要的 3-5 秒反爬延迟与详情页爬取
            detailCache.awaitLoaded()
            detailCache.get(doubanId)?.let { entry ->
                if (!entry.title.isNullOrBlank()) {
                    onProgress("cache_hit", title)
                    return Pair(entry.toDetailInfo(), true) // 命中本地磁盘缓存
                }
            }

            // 本地缓存未命中 → 查全局池(减少豆瓣爬取次数,用户A爬过的条目用户B直接复用)
            val pool = cloudDetailsPoolManager
            if (pool != null) {
                val cloudEntry = try {
                    pool.downloadDetail(doubanId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (cloudEntry != null && !cloudEntry.title.isNullOrBlank()) {
                    // 全局池命中(标题非空=字段完善),写入本地缓存(永久),后续直接命中本地
                    detailCache.put(doubanId, cloudEntry)
                    onProgress("cache_hit", title)
                    return Pair(cloudEntry.toDetailInfo(), true) // 命中全局池
                }
            }
        }

        // 本地缓存 + 全局池都未命中 → 爬取豆瓣详情页（带一次重试）
        onProgress("fetching", title)
        var lastHtml: String? = null
        for (attempt in 0..1) {
            if (attempt > 0) {
                delayWithEvent(DelayType.DOUBAN_RETRY, 2000L..4000L) // 重试前等待
            }
            // 每次请求(含首次)都施加 3-5 秒反爬延迟:豆瓣反爬严格,首次无失败也必须等待,
            // 否则极易触发 403 / 封禁(故 #22 原报告判定为 bug 实为设计需要,不修)
            delayWithEvent(DelayType.DOUBAN_DETAIL_CRAWL, 3000L..5000L)
            // 捕获网络异常(超时/连接失败),重试一次
            val html = try {
                fetchHtml(doubanUrl, cookie)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 0) continue  // 第一次失败,重试
                lastHtml = null
                break  // 第二次仍失败,退出循环
            }
            if (DoubanSpider.isLoginPage(html)) return Pair(null, false) // Cookie 过期，不重试
            lastHtml = html
            // 解析成功就跳出
            val parsed = DoubanSpider.parseDetail(html)
            if (!parsed.title.isNullOrBlank()) {
                // 标题非空 = 爬取成功,写入持久化缓存（永久，下次再进入直接命中；保留所有字段）
                val entry = DoubanDetailCacheEntry(
                    imdbId = parsed.imdbId,
                    isTvShow = parsed.isTvShow,
                    title = parsed.title,
                    posterUrl = parsed.posterUrl,
                    genres = parsed.genres,
                    year = parsed.year,
                    countries = parsed.countries,
                    directors = parsed.directors,
                    doubanRating = parsed.doubanRating,
                    ratingCount = parsed.ratingCount,
                    summary = parsed.summary,
                    episodeCount = parsed.episodeCount,
                    episodeDuration = parsed.episodeDuration,
                    aka = parsed.aka,
                    runtime = parsed.runtime,
                    writers = parsed.writers,
                    cast = parsed.cast,
                    languages = parsed.languages,
                    initialReleaseDates = parsed.initialReleaseDates,
                    ratingDistribution = parsed.ratingDistribution,
                    celebrities = parsed.celebrities.map {
                        DoubanCelebrityCacheEntry(
                            name = it.name,
                            doubanPersonageUrl = it.doubanPersonageUrl,
                            avatarUrl = it.avatarUrl,
                            role = it.role
                        )
                    }
                )
                detailCache.put(doubanId, entry)
                // 异步上传到全局池,供其他用户复用(失败不阻塞主流程)
                if (uploadToCloudPool) {
                    cloudDetailsPoolManager?.let { p ->
                        GlobalScope.launch(Dispatchers.IO) {
                            try {
                                p.uploadDetailEntry(doubanId, entry)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // 上传失败不影响详情页主流程。
                            }
                        }
                    }
                }
                onProgress("done", title)
                return Pair(parsed, false)
            }
            // 标题为空可能是页面结构变化或加载不全，重试一次
        }

        // 两次都失败，返回最后一次解析结果（可能 imdbId 为 null）
        onProgress("failed", title)
        return Pair(lastHtml?.let { DoubanSpider.parseDetail(it) }, false)
    }

    /**
     * 获取本地所有已缓存的豆瓣详情条目快照（doubanId → entry）。
     *
     * 供爬取测试页「快选已有条目」弹窗使用：列出已爬取过的条目（标题+海报），
     * 点击即填入对应豆瓣条目页 URL，便于快速选条目调试。
     */
    suspend fun getDetailSnapshot(): Map<String, DoubanDetailCacheEntry> {
        detailCache.awaitLoaded()
        return detailCache.snapshotFromDisk()
    }

    /** 缓存条目转运行时详情信息 */
    private fun DoubanDetailCacheEntry.toDetailInfo(): DoubanDetailInfo {
        return DoubanDetailInfo(
            imdbId = imdbId,
            isTvShow = isTvShow,
            title = title,
            posterUrl = posterUrl,
            genres = genres,
            year = year,
            countries = countries,
            directors = directors,
            doubanRating = doubanRating,
            ratingCount = ratingCount,
            summary = summary,
            episodeCount = episodeCount,
            episodeDuration = episodeDuration,
            aka = aka,
            runtime = runtime,
            writers = writers,
            cast = cast,
            languages = languages,
            initialReleaseDates = initialReleaseDates,
            ratingDistribution = ratingDistribution,
            celebrities = celebrities.map {
                DoubanCelebrity(
                    name = it.name,
                    doubanPersonageUrl = it.doubanPersonageUrl,
                    avatarUrl = it.avatarUrl,
                    role = it.role
                )
            }
        )
    }

    private suspend fun fetchHtml(
        url: String,
        cookie: String,
        validateStatus: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Cookie", cookie)
            .header("Referer", "https://movie.douban.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (validateStatus) {
                if (response.code == 401 || response.code == 403) {
                    throw DoubanCookieExpiredException("Douban cookie expired (HTTP ${response.code})")
                }
                if (!response.isSuccessful) {
                    throw DoubanNetworkException(
                        message = "Douban request failed with HTTP ${response.code}",
                        statusCode = response.code
                    )
                }
            }
            val body = response.body?.string()
            if (validateStatus && body == null) {
                throw DoubanNetworkException("Douban response body is empty", response.code)
            }
            body ?: ""
        }
    }

    // ===== 豆瓣标记 API（独立模式标记操作用）=====
    // 端点逆向自豆瓣网页前端: POST /j/subject/{id}/interest 标记 wish/collect/do,
    // POST /j/mine/j_cat_ui 删除标记(sid + ck, Content-Type 必带 charset=UTF-8)。
    // Cookie 依赖: dbcl2(登录凭证) + ck(CSRS 令牌, 从 cookie 提取) + bid。

    /** 从 Cookie 字符串提取 ck 值（CSRF 令牌，4 位字母数字） */
    private fun extractCk(cookie: String): String? {
        // ck 可能在 cookie 中以 ck=xxxx 形式出现,值不含分号/空格/引号
        val regex = Regex("ck=([^;\\s\"']+)")
        return regex.find(cookie)?.groupValues?.getOrNull(1)
    }

    /**
     * 标记豆瓣条目（想看/在看/看过）。
     *
     * @param doubanId 豆瓣条目 ID
     * @param interest "wish"(想看) | "collect"(看过) | "do"(在看)
     * @param cookie 完整 Cookie 字符串（含 dbcl2/ck/bid）
     * @param rating 1-5 评分, null 不评分（想看通常不传）
     * @param tags 标签（空格分隔，全量覆盖）
     * @param comment 短评（全量覆盖）
     * @return true=成功
     */
    suspend fun markInterest(
        doubanId: String,
        interest: String,
        cookie: String,
        rating: Int? = null,
        tags: String = "",
        comment: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val ck = extractCk(cookie) ?: return@withContext false
        val url = "https://movie.douban.com/j/subject/$doubanId/interest"
        val formBuilder = FormBody.Builder()
            .add("ck", ck)
            .add("interest", interest)
            .add("foldcollect", "F")
            .add("tags", tags)
            .add("comment", comment)
        if (rating != null) {
            formBuilder.add("rating", rating.coerceIn(1, 5).toString())
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Cookie", cookie)
            .header("Referer", "https://movie.douban.com/subject/$doubanId/")
            .header("Origin", "https://movie.douban.com")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(formBuilder.build())
            .build()
        try {
            client.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** 便捷: 标记想看 */
    suspend fun markWish(doubanId: String, cookie: String): Boolean =
        markInterest(doubanId, "wish", cookie)

    /** 便捷: 标记看过（带评分） */
    suspend fun markCollect(doubanId: String, cookie: String, rating: Int? = null): Boolean =
        markInterest(doubanId, "collect", cookie, rating)

    /**
     * 移除豆瓣条目标记（取消想看/在看/看过）。
     *
     * POST /j/mine/j_cat_ui, form: sid={doubanId}&ck={ck}
     * 注意: Content-Type 必须显式带 charset=UTF-8, 否则豆瓣会拒绝。
     *
     * @return true=成功
     */
    suspend fun removeInterest(doubanId: String, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val ck = extractCk(cookie) ?: return@withContext false
        val url = "https://movie.douban.com/j/mine/j_cat_ui"
        val body = "sid=$doubanId&ck=$ck"
        val mediaType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Cookie", cookie)
            .header("Referer", "https://movie.douban.com/")
            .header("Origin", "https://movie.douban.com")
            .header("Content-Type", mediaType.toString())
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body.toRequestBody(mediaType))
            .build()
        try {
            client.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    // 移动端 UA(测试页用)
    private val mobileUa =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 测试用:用指定 UA 抓取 URL,返回完整响应(状态码+HTML+耗时)。
     * 不走缓存,不写缓存,不上传全局池。仅爬取测试页调用。
     */
    suspend fun fetchHtmlForTest(
        url: String,
        cookie: String,
        useMobileUa: Boolean
    ): TestFetchResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", if (useMobileUa) mobileUa else ua)
            .header("Cookie", cookie)
            .header("Referer", "https://movie.douban.com/")
            .build()
        client.newCall(request).execute().use { response ->
            val html = response.body?.string() ?: ""
            TestFetchResult(
                statusCode = response.code,
                html = html,
                durationMs = System.currentTimeMillis() - startMs
            )
        }
    }

    /**
     * 测试用:抓取豆瓣「为你推荐」rexxar 端点(movie/tv),返回原始 JSON + 解析出的片单列表。
     * 该端点返回的是「片单/豆列(doulist)」而非单部影视;带登录 Cookie 时为个性化推荐。
     * 仅爬取测试页调用。
     */
    suspend fun fetchRecommendForTest(type: String, cookie: String): RecommendTestResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val url = "https://m.douban.com/rexxar/api/v2/$type/recommend"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", mobileUa)
            .header("Cookie", cookie)
            .header("Referer", "https://m.douban.com/")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val (itemCount, titles) = parseRecommendItems(body)
            RecommendTestResult(
                type = type,
                statusCode = response.code,
                durationMs = System.currentTimeMillis() - startMs,
                body = body,
                itemCount = itemCount,
                titles = titles
            )
        }
    }

    /** 从推荐 JSON 解析 items 内的片单标题(用于测试页结果展示) */
    private fun parseRecommendItems(body: String): Pair<Int?, List<String>> {
        return try {
            val obj = org.json.JSONObject(body)
            val arr = obj.optJSONArray("items") ?: return Pair(null, emptyList())
            val titles = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                it.optString("title").takeIf { t -> t.isNotBlank() }?.let { titles.add(it) }
            }
            Pair<Int?, List<String>>(arr.length(), titles)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Pair(null, emptyList())
        }
    }

    /**
     * 测试用(路径 B):标记「看过(collect)」并一并提交评分。
     *
     * 端点(与正式 [markInterest] 同源, 经真机验证): POST /j/subject/{id}/interest
     *   + ck + interest=collect + rating=N(1..5) + foldcollect=F + tags/comment/private。
     * 成功判据 HTTP 200 且响应体含 {"r":0}。豆瓣「打分」与「标记看过」耦合,
     * 通过 collect 一步提交星级最稳,无需单独打 /subject/{id}/rating。
     *
     * 仅测试页调用,用于逆向确认 rating 字段是否被接受及取值范围(推测 1..5 星)。
     *
     * @param rating 星级(0.5 步长, 范围 1.0..5.0)
     * @param comment 短评(可选)
     */
    suspend fun markWatchedWithRatingForTest(
        doubanId: String,
        cookie: String,
        ck: String,
        rating: Int,
        comment: String = "",
        tags: String = ""
    ): RatingWriteTestResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val ratingStr = rating.toString()
        val url = "https://movie.douban.com/j/subject/$doubanId/interest"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Cookie", cookie)
            .header("Referer", "https://movie.douban.com/subject/$doubanId/")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .post(
                FormBody.Builder()
                    .add("ck", ck)
                    .add("interest", "collect")
                    .add("rating", ratingStr)
                    .add("foldcollect", "F")
                    .add("tags", tags)
                    .add("comment", comment)
                    .add("private", "on")
                    .build()
            )
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val ok = response.isSuccessful && body.contains("\"r\":0")
            RatingWriteTestResult(
                doubanId = doubanId,
                rating = rating,
                success = ok,
                statusCode = response.code,
                durationMs = System.currentTimeMillis() - startMs,
                body = body
            )
        }
    }

    /**
     * 测试用:用 imdbId 搜索豆瓣移动端搜索页(m.douban.com/search/?query={imdbId}),
     * 返回原始 HTML + 解析出的搜索结果列表。
     *
     * 该端点返回服务端渲染 HTML(非 JS 渲染),Jsoup 解析 a[href^="/movie/subject/"] 即可拿到 doubanId。
     * 因 imdbId 是唯一标识,匹配准确率极高,无需二次详情页验证。
     *
     * @param imdbId IMDb ID(如 tt39528392)
     * @param cookie 豆瓣 Cookie(测试 cookie 是否必需;传空字符串可验证未登录场景)
     * @param useMobileUa 是否使用移动端 UA(该端点是移动端页面,建议 true)
     */
    suspend fun searchDoubanIdByImdbForTest(
        imdbId: String,
        cookie: String,
        useMobileUa: Boolean = true
    ): DoubanSearchTestResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val url = "https://m.douban.com/search/?query=$imdbId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", if (useMobileUa) mobileUa else ua)
            .header("Cookie", cookie)
            .header("Referer", "https://m.douban.com/")
            .build()
        client.newCall(request).execute().use { response ->
            val html = response.body?.string() ?: ""
            val isLoginPage = DoubanSpider.isLoginPage(html)
            val results = DoubanSpider.parseSearchByImdb(html)
            DoubanSearchTestResult(
                imdbId = imdbId,
                statusCode = response.code,
                durationMs = System.currentTimeMillis() - startMs,
                html = html,
                isLoginPage = isLoginPage,
                results = results
            )
        }
    }

    /**
     * 正式方法:用 imdbId 搜索豆瓣移动端搜索页,返回首个匹配的 doubanId。
     *
     * 端点: GET https://m.douban.com/search/?query={imdbId}
     * - 服务端渲染 HTML,Jsoup 解析 a[href^="/movie/subject/"] 拿 doubanId
     * - 无需 cookie(已验证),单条标记无反爬延迟
     *
     * @return 匹配到的 doubanId,未匹配返回 null
     */
    suspend fun searchDoubanIdByImdb(imdbId: String): String? = withContext(Dispatchers.IO) {
        val url = "https://m.douban.com/search/?query=$imdbId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", mobileUa)
            .header("Referer", "https://m.douban.com/")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val html = response.body?.string() ?: ""
                DoubanSpider.parseSearchByImdb(html).firstOrNull()?.doubanId
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 详情页预查 doubanId 的完整链路(内存缓存 → 同步表 → 详情缓存 → 网络搜索)。
     *
     * @param traktId Trakt ID，可为 0（允许仅凭 IMDb ID 查询）
     * @param imdbId IMDb ID(可能为 null,此时只查缓存和同步表)
     * @param mediaType "movie" 或 "show"
     * @param tmdbId TMDB ID，可选，用于公共映射池和本地永久缓存
     * @return 匹配到的 doubanId,未匹配返回 null
     */
    suspend fun findDoubanId(
        traktId: Int,
        imdbId: String?,
        mediaType: String,
        tmdbId: Int = 0
    ): String? {
        val lookupKey = mappingLookupKey(traktId, imdbId, tmdbId, mediaType)
            ?: return findDoubanIdUncached(traktId, imdbId, mediaType, tmdbId)
        val now = System.currentTimeMillis()
        negativeFindDoubanIdUntil[lookupKey]?.let { expiresAt ->
            if (now < expiresAt) return null
            negativeFindDoubanIdUntil.remove(lookupKey, expiresAt)
        }

        val candidate = CompletableDeferred<String?>()
        val existing = findDoubanIdFlights.putIfAbsent(lookupKey, candidate)
        if (existing != null) return existing.await()

        return try {
            val result = findDoubanIdUncached(traktId, imdbId, mediaType, tmdbId)
            if (result == null) {
                negativeFindDoubanIdUntil[lookupKey] =
                    System.currentTimeMillis() + NEGATIVE_DOUBAN_MAPPING_TTL_MILLIS
            } else {
                negativeFindDoubanIdUntil.remove(lookupKey)
            }
            candidate.complete(result)
            result
        } catch (e: CancellationException) {
            candidate.completeExceptionally(e)
            throw e
        } catch (e: Exception) {
            candidate.completeExceptionally(e)
            throw e
        } finally {
            findDoubanIdFlights.remove(lookupKey, candidate)
        }
    }

    private suspend fun findDoubanIdUncached(
        traktId: Int,
        imdbId: String?,
        mediaType: String,
        tmdbId: Int = 0
    ): String? {
        val legacyCacheKey = "${traktId}_$mediaType".takeIf { traktId > 0 }
        val publicKeys = buildPublicMappingKeys(traktId, imdbId, tmdbId, mediaType)

        // 1. 先兼容旧 traktId key，再查新版本 IMDb/Trakt/TMDB key。
        if (idMappingCache != null) {
            legacyCacheKey?.let { key ->
                idMappingCache.get(key)?.let { return it }
            }
            idMappingCache.awaitLoaded()
            legacyCacheKey?.let { key ->
                idMappingCache.get(key)?.let { return it }
            }
            publicKeys.forEach { key ->
                idMappingCache.get(key)?.let { doubanId ->
                    legacyCacheKey?.let { idMappingCache.put(it, doubanId) }
                    return doubanId
                }
            }
        }

        // 2. 查 douban_synced_items 表 by imdbId(调用方 DAO 查询)
        // 此处不直接查 DAO,由 ViewModel 层查询后传入,避免 Repository 依赖 DAO

        // 3. 查 Gitee 公共映射池（直连 Raw，不经过网关）。
        if (publicKeys.isNotEmpty()) {
            publicDataPoolManager?.getMappings(publicKeys)?.let { mappings ->
                publicKeys.firstNotNullOfOrNull { key -> mappings[key] }?.let { doubanId ->
                    persistDoubanMapping(legacyCacheKey, publicKeys, doubanId)
                    return doubanId
                }
            }
        }

        // 4. 遍历 DoubanDetailCache by imdbId
        if (!imdbId.isNullOrBlank()) {
            getDetailSnapshot().entries.firstOrNull { (_, entry) ->
                entry.imdbId == imdbId
            }?.key?.let { doubanId ->
                // 命中详情缓存,写入映射缓存
                persistDoubanMapping(legacyCacheKey, publicKeys, doubanId)
                return doubanId
            }
        }

        // 5. 网络搜索 m.douban.com/search/?query={imdbId}
        if (!imdbId.isNullOrBlank()) {
            val doubanId = searchDoubanIdByImdb(imdbId)
            if (doubanId != null) {
                persistDoubanMapping(legacyCacheKey, publicKeys, doubanId)
            }
            return doubanId
        }

        return null
    }

    /**
     * 写入外部 ID→doubanId 映射到永久缓存(供豆瓣导入成功后调用)。
     */
    fun putDoubanIdMapping(
        traktId: Int,
        mediaType: String,
        doubanId: String,
        imdbId: String? = null,
        tmdbId: Int = 0
    ) {
        val legacyKey = "${traktId}_$mediaType".takeIf { traktId > 0 }
        val publicKeys = buildPublicMappingKeys(traktId, imdbId, tmdbId, mediaType)
        persistDoubanMapping(legacyKey, publicKeys, doubanId)
    }

    private fun buildPublicMappingKeys(
        traktId: Int,
        imdbId: String?,
        tmdbId: Int,
        mediaType: String
    ): List<String> = buildList {
        imdbId?.trim()?.takeIf { it.isNotEmpty() }?.let { add("imdb:${it.lowercase()}:$mediaType") }
        if (traktId > 0) add("trakt:$traktId:$mediaType")
        if (tmdbId > 0) add("tmdb:$tmdbId:$mediaType")
    }

    private fun mappingLookupKey(
        traktId: Int,
        imdbId: String?,
        tmdbId: Int,
        mediaType: String
    ): String? {
        val legacyKey = "${traktId}_$mediaType".takeIf { traktId > 0 }
        val publicKeys = buildPublicMappingKeys(traktId, imdbId, tmdbId, mediaType)
        return (listOfNotNull(legacyKey) + publicKeys)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("|")
    }

    private fun persistDoubanMapping(
        legacyKey: String?,
        publicKeys: Collection<String>,
        doubanId: String
    ) {
        legacyKey?.let { idMappingCache?.put(it, doubanId) }
        publicKeys.forEach { key -> idMappingCache?.put(key, doubanId) }
        negativeFindDoubanIdUntil.entries.removeIf { entry ->
            publicKeys.any { key -> entry.key.contains(key) }
        }
        if (publicKeys.isNotEmpty()) {
            publicDataPoolManager?.let { pool ->
                publicUploadScope.launch {
                    runCatching { pool.uploadMappings(publicKeys.associateWith { doubanId }) }
                }
            }
        }
    }

    /**
     * 测试用:对一个标记动作探测候选写接口端点,返回对照结果。
     * 仅爬取测试页调用,用于逆向确认端点与字段(不进入正式业务逻辑)。
     *
     * 标记(wish/do/collect)已实测确认: POST /j/subject/{id}/interest + interest=动作 → r:0 成功。
     *   候选 A(Gazer 风格, 有效): POST /interest + interest=动作 + foldcollect=F + tags/comment/private
     *   候选 B(douban-mcp 风格, 404 对照): POST /j/subject/{id}/{动作} + interest=动作 + foldcollect=F
     *
     * 取消(remove): 经交叉验证, 取消标记根本不走 /interest 端点(此前对 interest 传空/传 remove 均 r:0
     *   但网页仍显示想看, 系假成功)。网页"删除标记"按钮实际提交的是收藏编辑页的删除表单端点:
     *   - "web_remove"(默认): POST /subject/{id}/remove + ck    (网页表单端点, 成功返回 302 重定向回详情页)
     *   - "j_remove":         POST /j/subject/{id}/remove + ck  (JSON 端点变体, 若存在可能返回 {"r":0})
     *   web_remove 用不跟随重定向的客户端观测 302 + Location, 用户仍需在网页最终确认标记消失。
     */
    suspend fun markTestCandidates(
        doubanId: String,
        cookie: String,
        ck: String,
        action: String,
        useMobileUa: Boolean,
        removeMode: String = "web_remove"
    ): MarkTestResult = withContext(Dispatchers.IO) {
        val uaHeader = if (useMobileUa) mobileUa else ua
        val referer = "https://movie.douban.com/subject/$doubanId/"

        // 候选端点列表(含语义标签, 便于 UI 展示与复制)
        val candidates: List<CandidateSpec> = when (action) {
            "remove" -> listOf(
                if (removeMode == "j_remove") {
                    // JSON 端点变体: POST /j/subject/{id}/remove + ck, 可能返回 {"r":0}
                    CandidateSpec(
                        method = "POST",
                        path = "/j/subject/$doubanId/remove",
                        body = FormBody.Builder().add("ck", ck).build(),
                        label = "取消·/j/remove(JSON)"
                    )
                } else {
                    // 网页收藏编辑页删除表单端点: POST /subject/{id}/remove + ck
                    // 这是网页"删除标记"按钮真正提交的地址(非 /interest), 成功返回 302 重定向回详情页。
                    // 用 noRedirect 客户端观测 302 + Location 以确认删除生效。
                    CandidateSpec(
                        method = "POST",
                        path = "/subject/$doubanId/remove",
                        body = FormBody.Builder().add("ck", ck).build(),
                        label = "取消·/remove(网页表单302)",
                        noRedirect = true
                    )
                }
            )
            else -> listOf(
                // A: Gazer 风格 统一 interest 端点(已实测 wish/collect r:0 有效)
                CandidateSpec(
                    method = "POST",
                    path = "/j/subject/$doubanId/interest",
                    body = FormBody.Builder()
                        .add("ck", ck)
                        .add("interest", action)
                        .add("foldcollect", "F")
                        .add("tags", "")
                        .add("comment", "")
                        .add("private", "on")
                        .build(),
                    label = "标记·$action(Gazer)"
                ),
                // B: douban-mcp 风格 分动作路径(实测 404, 作为对照)
                CandidateSpec(
                    method = "POST",
                    path = "/j/subject/$doubanId/$action",
                    body = FormBody.Builder()
                        .add("ck", ck)
                        .add("interest", action)
                        .add("foldcollect", "F")
                        .build(),
                    label = "标记·$action(mcp-404)"
                )
            )
        }

        val results = candidates.map { spec ->
            val url = "https://movie.douban.com${spec.path}"
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", uaHeader)
                .header("Cookie", cookie)
                .header("Referer", referer)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
            val request = if (spec.method == "DELETE") {
                requestBuilder.delete().build()
            } else {
                requestBuilder.post(spec.body!!).build()
            }
            // 网页删除表单需用不跟随重定向的客户端, 才能观测到 302 与 Location
            val useClient = if (spec.noRedirect) noRedirectClient else client
            useClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                // 302 时 body 通常为空, 把 Location 拼进结果便于判断是否重定向回详情页(删除生效标志)
                val location = response.header("Location")
                val displayBody = if (!location.isNullOrBlank()) "[Location: $location] $rawBody" else rawBody
                MarkCandidateResult(
                    endpoint = "${spec.method} ${spec.path}",
                    method = spec.method,
                    statusCode = response.code,
                    responseBody = displayBody,
                    label = spec.label
                )
            }
        }
        MarkTestResult(action = action, candidates = results)
    }

    /**
     * 抓取 PC 详情页 HTML(用于解析 ck 凭证)。
     * Cookie 失效时豆瓣会重定向到登录页,此时返回 null,由调用方提示重新登录。
     */
    suspend fun fetchDetailPageHtml(doubanId: String, cookie: String): String? = withContext(Dispatchers.IO) {
        val html = fetchHtml("https://movie.douban.com/subject/$doubanId/", cookie)
        return@withContext if (DoubanSpider.isLoginPage(html)) null else html
    }

    /**
     * 抓取 PC 详情页并解析 csrf token(ck)。
     * Cookie 失效或解析失败均返回 null,由调用方区分提示。
     */
    suspend fun fetchCsrfToken(doubanId: String, cookie: String): String? = withContext(Dispatchers.IO) {
        val html = fetchDetailPageHtml(doubanId, cookie) ?: return@withContext null
        DoubanSpider.parseCsrfToken(html)
    }

    /**
     * 正式写回:将条目标记为想看(wish)或已看(collect)。
     *
     * 端点(经真机验证): POST /j/subject/{id}/interest + ck + interest=动作(wish|collect)
     *   + foldcollect=F + tags/comment/private,成功判据 HTTP 200 且响应体含 {"r":0}。
     * 注意:标记走 /interest 接口;取消删除走独立的 /subject/{id}/remove(见 [removeMark])。
     *
     * 与 [markInterest] 的区别:本方法需外部预先获取 ck(常用于重试/批量场景),
     * 且返回 [MarkWriteResult] 提供状态码与失败信息;[markInterest] 内部提取 ck 并返回 Boolean。
     *
     * @param action "wish" 或 "collect"
     * @return [MarkWriteResult] 含是否成功 / 状态码 / 信息
     */
    suspend fun markInterestByCk(
        action: String,
        doubanId: String,
        cookie: String,
        ck: String
    ): MarkWriteResult = withContext(Dispatchers.IO) {
        val url = "https://movie.douban.com/j/subject/$doubanId/interest"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Cookie", cookie)
            .header("Referer", "https://movie.douban.com/subject/$doubanId/")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .post(
                FormBody.Builder()
                    .add("ck", ck)
                    .add("interest", action)
                    .add("foldcollect", "F")
                    .add("tags", "")
                    .add("comment", "")
                    .add("private", "on")
                    .build()
            )
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val ok = response.isSuccessful && body.contains("\"r\":0")
            MarkWriteResult(
                success = ok,
                statusCode = response.code,
                message = if (ok) "r:0" else body.take(200)
            )
        }
    }

    /**
     * 正式写回:标记看过+评分+短评（一次请求）。
     *
     * 基于测试页验证通过的 [markWatchedWithRatingForTest]，端点同为 POST /j/subject/{id}/interest，
     * 表单增加 rating 和 comment 字段。豆瓣「打分」与「标记看过」耦合，通过 collect 一步提交星级最稳。
     *
     * @param rating 1..5 豆瓣五星制（整星，不支持半星）
     * @param comment 短评（可选，空串表示不写短评）
     * @return [MarkWriteResult] 含是否成功 / 状态码 / 信息
     */
    suspend fun markWatchedWithRating(
        doubanId: String,
        cookie: String,
        ck: String,
        rating: Int,
        comment: String = ""
    ): MarkWriteResult = withContext(Dispatchers.IO) {
        val ratingStr = rating.coerceIn(1, 5).toString()
        val url = "https://movie.douban.com/j/subject/$doubanId/interest"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Cookie", cookie)
            .header("Referer", "https://movie.douban.com/subject/$doubanId/")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .post(
                FormBody.Builder()
                    .add("ck", ck)
                    .add("interest", "collect")
                    .add("rating", ratingStr)
                    .add("foldcollect", "F")
                    .add("tags", "")
                    .add("comment", comment)
                    .add("private", "on")
                    .build()
            )
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val ok = response.isSuccessful && body.contains("\"r\":0")
            MarkWriteResult(
                success = ok,
                statusCode = response.code,
                message = if (ok) "r:0" else body.take(200)
            )
        }
    }

    /**
     * 正式写回:取消豆瓣标记(删除收藏)。
     *
     * 端点(经真机验证): POST /subject/{id}/remove + ck(网页收藏编辑页"删除"按钮真正提交的表单,
     *   非 /interest 接口)。成功判据 HTTP 302 且 Location 重定向回详情页
     *   (/interest 端点即使返回 r:0 也只是"假成功",标记仍在,见 markTestCandidates 说明)。
     * 需用不跟随重定向的客户端(noRedirectClient)才能观测到 302。
     *
     * @return [MarkWriteResult] 含是否成功 / 状态码 / 信息
     */
    suspend fun removeMark(
        doubanId: String,
        cookie: String,
        ck: String
    ): MarkWriteResult = withContext(Dispatchers.IO) {
        val url = "https://movie.douban.com/subject/$doubanId/remove"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Cookie", cookie)
            .header("Referer", "https://movie.douban.com/subject/$doubanId/")
            .post(FormBody.Builder().add("ck", ck).build())
            .build()
        noRedirectClient.newCall(request).execute().use { response ->
            val location = response.header("Location")
            val ok = response.code == 302 && location?.contains("/subject/$doubanId/") == true
            MarkWriteResult(
                success = ok,
                statusCode = response.code,
                message = if (ok) "302 -> $location" else "HTTP ${response.code}"
            )
        }
    }

    /**
     * 抓取豆瓣用户主页,解析头像、昵称、ID。
     *
     * 头像解析策略(从准到粗,任一命中即用):
     *   1. URL 中含 `u{userId}` 的图片(豆瓣用户头像 URL 模式 https://img.doubaocdn.com/u{userId}-*.jpg)
     *   2. class/alt 含 "user"/"face"/"avatar" 的 <img>
     *   3. og:image meta(排除指向豆瓣主站 logo / brand 的 URL)
     *
     * 昵称解析:og:title(格式「XXX的豆瓣主页」)→ <title> → 截断「的豆瓣...」后缀。
     *
     * @return DoubanUserProfile 或 null(网络失败 / Cookie 过期 / 解析失败)
     */
    suspend fun fetchUserProfile(userId: String, cookie: String): DoubanUserProfile? = withContext(Dispatchers.IO) {
        try {
            // PC 版用户主页:og:image 通常就是用户头像,且页面结构稳定
            val url = "https://www.douban.com/people/$userId/"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Cookie", cookie)
                .header("Referer", "https://www.douban.com/")
                .build()
            val html = client.newCall(request).execute().use { response ->
                response.body?.string() ?: return@withContext null
            }
            // Cookie 过期或被风控时通常重定向到登录页
            if (DoubanSpider.isLoginPage(html)) return@withContext null

            val doc: Document = Jsoup.parse(html)
            val avatarUrl = extractUserAvatar(doc, userId)
            // 昵称：优先 og:title（格式通常是「XXX的豆瓣主页」），其次 <title>，截断到「的」之前
            val nickname = (extractOgTitle(html) ?: extractTitle(html))?.let { cleanDoubanNick(it) }
            DoubanUserProfile(
                userId = userId,
                nickname = nickname,
                avatarUrl = avatarUrl
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 多策略提取用户头像:
     *   1. <img> 的 src 中含 `u{userId}` 路径段(豆瓣用户头像 URL 模式)
     *   2. <img> 的 class 含 "user-face"/"userface"/"avatar" 或 alt 非空且非"豆瓣"
     *   3. og:image meta(排除指向主站 brand/pics 的 logo)
     */
    private fun extractUserAvatar(doc: Document, userId: String): String? {
        // 策略 1:src 含 `u{userId}` 的图片(最准,豆瓣用户头像固定 URL 模式)
        val userAvatarImgRegex = Regex("""u${Regex.escape(userId)}[-\w]*\.(?:jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE)
        for (img in doc.select("img")) {
            val src = img.absUrl("src").ifEmpty { img.attr("src") }
            if (src.isNotBlank() && userAvatarImgRegex.containsMatchIn(src)) {
                return src
            }
        }
        // 策略 2:class / alt 匹配用户头像
        val avatarLike = doc.select("img").firstOrNull { el ->
            val cls = el.className().lowercase()
            val alt = el.attr("alt").lowercase()
            (cls.contains("user-face") || cls.contains("userface") || cls.contains("avatar") || cls.contains("pic"))
                && !alt.equals("豆瓣", ignoreCase = true)
                && !alt.contains("logo")
        }
        avatarLike?.let {
            val src = it.absUrl("src").ifEmpty { it.attr("src") }
            if (src.isNotBlank()) return src
        }
        // 策略 3:og:image(排除主站 logo / brand 路径)
        val og = extractOgImage(doc.outerHtml())
        if (og != null && !og.contains("/brand/", ignoreCase = true) && !og.contains("pics", ignoreCase = true)) {
            return og
        }
        return null
    }

    /** 从 og:image meta 提取头像 URL */
    private fun extractOgImage(html: String): String? {
        val regex = Regex("""<meta\s+property\s*=\s*["']og:image["']\s+content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /** 从 og:title meta 提取标题文本 */
    private fun extractOgTitle(html: String): String? {
        val regex = Regex("""<meta\s+property\s*=\s*["']og:title["']\s+content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /** 从 <title>...</title> 提取标题文本 */
    private fun extractTitle(html: String): String? {
        val regex = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** 清理豆瓣页面标题中的「的豆瓣」「| 豆瓣」等后缀，得到纯昵称 */
    private fun cleanDoubanNick(raw: String): String {
        var s = raw.trim()
        // 移除常见后缀：「的豆瓣」「的豆瓣主页」「| 豆瓣」「- 豆瓣」
        s = s.replace(Regex("""\s*[|｜\-]\s*豆瓣.*"""), "")
        s = s.replace(Regex("""的豆瓣(主页)?.*$"""), "")
        return s.trim().ifBlank { raw }
    }

    /**
     * 抓取豆瓣「为你推荐」rexxar 端点（movie/tv），带登录 cookie 返回个性化单剧推荐。
     *
     * - 带登录 cookie: 返回个性化单剧推荐，每条带 alg_strategy("user_movie"/"user_tv") 和 reason_data 推荐理由
     * - 免登录: 返回通用热门片单（非单剧），不推荐使用
     *
     * @param type "movie" 或 "tv"
     * @param cookie 用户登录后的豆瓣 cookie
     * @return 个性化单剧列表（已过滤片单/豆列，仅保留 type="subject"）
     * @throws DoubanCookieExpiredException 当响应为登录页（cookie 过期）
     */
    suspend fun fetchRecommend(type: String, cookie: String): List<DoubanRecommendItem> = withContext(Dispatchers.IO) {
        val url = "https://m.douban.com/rexxar/api/v2/$type/recommend"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", mobileUa)
            .header("Cookie", cookie)
            .header("Referer", "https://m.douban.com/")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("豆瓣推荐响应为空")
            // cookie 过期:rexxar 端点可能返回 401 或重定向到登录页
            if (response.code == 401 || response.code == 403) {
                throw DoubanCookieExpiredException()
            }
            // 防御性检查:响应是 HTML 登录页而非 JSON
            if (body.contains("<form id=\"lzform\"") || body.contains("\"login\":true")) {
                throw DoubanCookieExpiredException()
            }
            val parsed = json.decodeFromString<DoubanRecommendResponse>(body)
            // 过滤片单/豆列（type=="playlist"）和广告（type=="ad"），保留所有单剧推荐
            parsed.items.filter { it.type == "movie" || it.type == "tv" }
        }
    }
}

/**
 * 测试页抓取结果。
 * @param statusCode HTTP 状态码
 * @param html 响应体
 * @param durationMs 耗时(毫秒)
 */
data class TestFetchResult(
    val statusCode: Int,
    val html: String,
    val durationMs: Long
)

/**
 * 标记写回测试结果(测试页用)。
 * @param endpoint 实际请求的写接口路径(如 wish/do/collect/remove)
 * @param statusCode HTTP 状态码
 * @param responseBody 响应体原始文本(通常为 JSON,如 {"r":0,...})
 */
/** 测试用候选端点规格(含语义标签,便于 UI 展示与复制) */
private data class CandidateSpec(
    val method: String,
    val path: String,
    val body: FormBody?,
    val label: String,
    val noRedirect: Boolean = false  // true=用不跟随重定向的客户端(观测网页删除表单的 302)
)

/** 单次候选端点的写回探测结果 */
data class MarkCandidateResult(
    val endpoint: String,      // 如 "POST /j/subject/{id}/interest"
    val method: String,        // POST / DELETE
    val statusCode: Int,
    val responseBody: String,
    val label: String = ""     // 语义标签, 如 "标记·wish(Gazer)" / "取消·仅ck(去foldcollect)"
)

/** 一个标记动作(wish/do/collect/remove)对多个候选端点的探测结果 */
data class MarkTestResult(
    val action: String,
    val candidates: List<MarkCandidateResult>
)

/** 正式写回结果(标记/取消) */
data class MarkWriteResult(
    val success: Boolean,
    val statusCode: Int,
    val message: String
)

/** 测试页「为你推荐」抓取结果 */
data class RecommendTestResult(
    val type: String,           // "movie" / "tv"
    val statusCode: Int,
    val durationMs: Long,
    val body: String,           // 原始 JSON 响应体
    val itemCount: Int?,        // 解析出的 items 数量(null=非 JSON 或解析失败)
    val titles: List<String>    // 片单标题列表
)

/** 测试页「看过+评分写入」结果(路径 B: /j/subject/{id}/interest + interest=collect + rating) */
data class RatingWriteTestResult(
    val doubanId: String,
    val rating: Int,            // 提交的星级(1..5)
    val success: Boolean,       // HTTP 200 且响应体含 {"r":0}
    val statusCode: Int,
    val durationMs: Long,
    val body: String            // 原始响应体
)

/** 测试页「imdb→豆瓣ID」搜索结果 */
data class DoubanSearchTestResult(
    val imdbId: String,             // 查询用的 imdbId
    val statusCode: Int,
    val durationMs: Long,
    val html: String,               // 原始 HTML 响应体
    val isLoginPage: Boolean,       // 是否为登录页(cookie 过期)
    val results: List<DoubanSearchResultItem>  // 解析出的搜索结果列表
)

/** 豆瓣标记状态 */
enum class DoubanMarkStatus(val path: String) {
    WISH("wish"),       // 想看
    COLLECT("collect");  // 看过

    companion object {
        /** 从字符串反序列化(容错:未知值降级为 WISH) */
        fun fromString(value: String?): DoubanMarkStatus =
            entries.firstOrNull { it.path == value || it.name == value } ?: WISH
    }
}
