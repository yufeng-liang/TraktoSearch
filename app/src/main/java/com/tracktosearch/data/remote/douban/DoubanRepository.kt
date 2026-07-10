package com.tracktosearch.data.remote.douban

import com.tracktosearch.data.local.DoubanUserProfile
import com.tracktosearch.data.repository.CloudDetailsPoolManager
import com.tracktosearch.data.util.PersistentTtlCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit
import kotlin.random.Random

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
class DoubanRepository(
    private val detailCache: PersistentTtlCache<DoubanDetailCacheEntry>,
    private val cloudDetailsPoolManager: CloudDetailsPoolManager? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
        var cookieExpired = false
        var knownTotal: Int? = null

        while (true) {
            // 每页爬取前检查取消标志,避免取消后还要爬完当前页(5-10 秒反爬延迟)
            if (isCancelled()) break
            val url = "https://movie.douban.com/people/${userId}/${status.path}?start=${start}&sort=time&mode=grid"
            val html = fetchHtml(url, cookie)
            if (DoubanSpider.isLoginPage(html)) {
                cookieExpired = true
                break
            }
            val page = DoubanSpider.parseMarkListPage(html)
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
            delay(Random.nextLong(5000, 10000))
        }
        return !cookieExpired
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
     * @return Pair<详情, 是否命中缓存>,详情为 null 表示失败
     */
    suspend fun fetchDetail(
        doubanUrl: String,
        cookie: String,
        title: String? = null,
        forceRefresh: Boolean = false,
        onProgress: (phase: String, title: String?) -> Unit = { _, _ -> }
    ): Pair<DoubanDetailInfo?, Boolean> {
        // 从 URL 解析 doubanId 作为缓存 key
        val doubanId = Regex("""subject/(\d+)""").find(doubanUrl)?.groupValues?.get(1) ?: doubanUrl

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
                val cloudEntry = runCatching {
                    pool.downloadDetail(doubanId)
                }.getOrNull()
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
                delay(Random.nextLong(2000, 4000)) // 重试前等待
            }
            delay(Random.nextLong(3000, 5000)) // 反爬延迟 3-5 秒
            val html = fetchHtml(doubanUrl, cookie)
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
                cloudDetailsPoolManager?.let { p ->
                    GlobalScope.launch(Dispatchers.IO) {
                        runCatching { p.uploadDetailEntry(doubanId, entry) }
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
                com.tracktosearch.data.remote.douban.DoubanCelebrity(
                    name = it.name,
                    doubanPersonageUrl = it.doubanPersonageUrl,
                    avatarUrl = it.avatarUrl,
                    role = it.role
                )
            }
        )
    }

    private suspend fun fetchHtml(url: String, cookie: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Cookie", cookie)
            .header("Referer", "https://movie.douban.com/")
            .build()
        client.newCall(request).execute().use { response ->
            response.body?.string() ?: ""
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
