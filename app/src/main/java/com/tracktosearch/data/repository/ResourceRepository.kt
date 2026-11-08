package com.tracktosearch.data.repository

import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.CustomSearchSourceStorage
import com.tracktosearch.data.local.PanHubConfigStorage
import com.tracktosearch.data.local.SearchSourceStorage
import com.tracktosearch.data.remote.custom.CustomSearchService
import com.tracktosearch.data.remote.ataw.AtawApiService
import com.tracktosearch.data.remote.baoys.BaoysApiService
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.melost.MelostApiService
import com.tracktosearch.data.remote.melost.dto.MelostSearchRequest
import com.tracktosearch.data.remote.panhub.PanHubApiService
import com.tracktosearch.data.remote.pansou.PanSouApiService
import com.tracktosearch.data.remote.panxiaozi.PanxiaoziApiService
import com.tracktosearch.data.remote.panxiaozi.PanxiaoziParser
import com.tracktosearch.data.remote.yunso.YunsoApiService
import com.tracktosearch.data.remote.zreso.ZresoApiService
import com.tracktosearch.data.remote.zreso.ZresoWebViewVerifier
import com.tracktosearch.data.remote.zreso.dto.ZresoLink
import com.tracktosearch.data.remote.zreso.dto.ZresoResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ResourceRepository @Inject constructor(
    private val panSouApiService: PanSouApiService,
    @Named("panhub") private val panHubApiService: PanSouApiService,
    private val panHubGranularApiService: PanHubApiService,
    private val panHubConfigStorage: PanHubConfigStorage,
    private val zresoApiService: ZresoApiService,
    private val zresoWebViewVerifier: ZresoWebViewVerifier,
    @Named("useai") private val useAiApiService: PanSouApiService,
    private val yunsoApiService: YunsoApiService,
    private val atawApiService: AtawApiService,
    private val melostApiService: MelostApiService,
    private val panxiaoziApiService: PanxiaoziApiService,
    private val baoysApiService: BaoysApiService,
    private val searchSourceStorage: SearchSourceStorage,
    private val customSearchSourceStorage: CustomSearchSourceStorage,
    private val customSearchService: CustomSearchService
) {

    /**
     * 根据设置页开关计算实际启用的搜索源（与调用方传入的 enabledSources 取交集）。
     * 设置页关闭的源永远不参与搜索。
     */
    private fun storageEnabledSourcesFlow(): Flow<Set<String>> = combine(
        listOf(
            searchSourceStorage.pansouEnabled,
            searchSourceStorage.panhubEnabled,
            searchSourceStorage.zresoEnabled,
            searchSourceStorage.useaiEnabled,
            searchSourceStorage.yunsoEnabled,
            searchSourceStorage.atawEnabled,
            searchSourceStorage.melostEnabled,
            searchSourceStorage.panxiaoziEnabled,
            searchSourceStorage.baoysEnabled
        )
    ) { flags ->
        buildSet {
            if (flags[0]) add(SOURCE_PANSOU)
            if (flags[1]) add(SOURCE_PANHUB)
            if (flags[2]) add(SOURCE_ZRESO)
            if (flags[3]) add(SOURCE_USEAI)
            if (flags[4]) add(SOURCE_YUNSO)
            if (flags[5]) add(SOURCE_ATAW)
            if (flags[6]) add(SOURCE_MELOST)
            if (flags[7]) add(SOURCE_PANXIAOZI)
            if (flags[8]) add(SOURCE_BAOYS)
        }
    }

    /** 获取当前启用的搜索源（同步，用于 UI 初始化），包含自定义源 */
    suspend fun getEnabledSources(): Set<String> {
        val builtIn = storageEnabledSourcesFlow().first()
        val customEnabled = customSearchSourceStorage.sources.first()
            .filter { it.enabled }
            .map { it.id }
            .toSet()
        return builtIn + customEnabled
    }

    /** 获取所有自定义源（已启用的） */
    suspend fun getEnabledCustomSources(): List<CustomSearchSource> {
        return customSearchSourceStorage.sources.first().filter { it.enabled }
    }

    /** 根据 ID 获取自定义源名称 */
    suspend fun getSourceName(sourceId: String): String? {
        return customSearchSourceStorage.sources.first().find { it.id == sourceId }?.name
    }

    companion object {
        const val SOURCE_PANSOU = "pansou"
        const val SOURCE_PANHUB = "panhub"
        const val SOURCE_ZRESO = "zreso"
        const val SOURCE_USEAI = "useai"
        const val SOURCE_YUNSO = "yunso"
        const val SOURCE_ATAW = "ataw"
        const val SOURCE_MELOST = "melost"
        const val SOURCE_PANXIAOZI = "panxiaozi"
        const val SOURCE_BAOYS = "baoys"
        val ALL_SOURCES = setOf(
            SOURCE_PANSOU, SOURCE_PANHUB, SOURCE_ZRESO,
            SOURCE_USEAI, SOURCE_YUNSO, SOURCE_ATAW,
            SOURCE_MELOST, SOURCE_PANXIAOZI, SOURCE_BAOYS
        )
        val ALL_DISK_TYPES = setOf(
            DiskType.QUARK, DiskType.BAIDU, DiskType.ALI,
            DiskType.XUNLEI, DiskType.UC, DiskType.ONEONEFIVE, DiskType.MAGNET
        )
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        /** useai 实例聚合 68 个插件，慢关键词实测 11-12 秒，单源超时放宽到 25s */
        private const val USEAI_TIMEOUT_MS = 25_000L
        /** 云搜单页上限 ~30 条，并行翻页数 */
        private const val YUNSO_PAGES = 2
        /** 影盘社单页 size 上限实测 50 以内按请求给，翻页数 */
        private const val MELOST_PAGES = 2
        private const val MELOST_TIMEOUT_MS = 15_000L
        /** 盘小子每页固定 10 条，并行翻页数 */
        private const val PANXIAOZI_PAGES = 2
        private const val PANXIAOZI_TIMEOUT_MS = 15_000L
        /** 宝藏郎实测 10-25 秒才返回，不传 limit 反而更快 */
        private const val BAOYS_TIMEOUT_MS = 22_000L
        /** 影盘社标题里的搜索词高亮标签 */
        private val MELOST_EM_TAG_REGEX = Regex("</?em>")
        private val MULTI_SEASON_FULL = Regex("""全\s*季|合集|1[-~]\d+\s*季|第\s*\d+\s*[-~]\s*\d+\s*季""")
        private val MULTI_SEASON_SINGLE = Regex("""第\s*\d+\s*季""")
        /** 阿涛搜条目里混排的直链（link/description 字段都可能是大段文案） */
        private val ATAW_URL_REGEX = Regex("""https?://[^\s，。"'<>）)]+""")
        private const val ZRESO_BASE_URL = "https://zreso.cn"
        /** zreso 结果链接形如 /api/wash?t=<uuid>，需再换一次真实网盘直链 */
        private val ZRESO_WASH_TOKEN_REGEX = Regex("""[?&]t=([^&]+)""")
        /** wash 解析并发闸门：zreso 对高频 /api/wash 会 429，几十条结果瞬间并发会反噬熔断窗口 */
        private val washSemaphore = Semaphore(4)
    }

    private data class KeywordCache(
        val items: List<ResourceItem>,
        val timestamp: Long
    )

    /** zreso 搜索结果里的一条待展开链接 */
    private data class ZresoCandidate(
        val result: ZresoResult,
        val link: ZresoLink,
        val diskType: DiskType
    )

    /** zreso 请求超时（用于区分普通异常，触发熔断） */
    private class ZresoTimeoutException : Exception("zreso 请求超时")

    // 搜索关键词结果缓存，LRU 限制 50 条防内存增长
    // 搜索结果关键词缓存：synchronized LinkedHashMap(removeEldestEntry)实现并发 LRU
    @Suppress("UNCHECKED_CAST")
    private val cache: MutableMap<String, KeywordCache> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, KeywordCache>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KeywordCache>?): Boolean = size > 50
        }
    )

    /** zreso 熔断截止时间：429/5xx/超时后短路一段时间，避免每次搜索都白等 8 秒 */
    @Volatile
    private var zresoCooldownUntil = 0L



    /**
     * 搜索接口：
     * 1) 第一次调用（按 keyword）：用全 sources + 全 disk_types 调 API，结果缓存
     * 2) 后续调用（同 keyword，不同 filter）：从缓存里本地过滤，不调 API
     */
    suspend fun searchResources(
        keyword: String,
        enabledSources: Set<String> = ALL_SOURCES,
        enabledDiskTypes: Set<DiskType> = ALL_DISK_TYPES,
        isShow: Boolean = false,
        query: ResourceQuery? = null
    ): Result<List<ResourceItem>> {
        if (keyword.isBlank()) return Result.success(emptyList())

        // 设置页关闭的源不参与搜索
        val effectiveSources = enabledSources.intersect(storageEnabledSourcesFlow().first())

        val now = System.currentTimeMillis()
        val cached = cache[keyword]
        val allItems = if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
            // 命中缓存：query 非空时按当前 query 重算相关度（同关键词不同影视用各自元数据）
            rank(cached.items, isShow, query).items
        } else {
            // 只查询用户选中的源，避免无效请求
            val fetched = fetchEnabledSources(keyword, effectiveSources, enabledDiskTypes, isShow, query)
            // 只缓存非空结果，避免"空结果被缓存导致后续一直空"的问题
            if (fetched.isNotEmpty()) {
                cache.put(keyword, KeywordCache(fetched, now))
            }
            fetched
        }

        return Result.success(filterItems(allItems, effectiveSources, enabledDiskTypes))
    }

    /**
     * 强制刷新（清除缓存后重拉）
     */
    suspend fun refreshResources(
        keyword: String,
        enabledSources: Set<String> = ALL_SOURCES,
        enabledDiskTypes: Set<DiskType> = ALL_DISK_TYPES,
        isShow: Boolean = false,
        query: ResourceQuery? = null
    ): Result<List<ResourceItem>> {
        if (keyword.isBlank()) return Result.success(emptyList())
        cache.remove(keyword)
        return searchResources(keyword, enabledSources, enabledDiskTypes, isShow, query)
    }

    /**
     * 增量搜索接口：每完成一个源就发射累积结果。
     * 发射次数等于启用的源数量（每个源完成后各发射一次）。
     * 如果有缓存则只发射 1 次。
     *
     * @param onSourceComplete 每个源完成时的回调（参数为源名称）
     */
    fun searchResourcesFlow(
        keyword: String,
        enabledSources: Set<String> = ALL_SOURCES,
        enabledDiskTypes: Set<DiskType> = ALL_DISK_TYPES,
        isShow: Boolean = false,
        query: ResourceQuery? = null,
        onSourceComplete: ((String) -> Unit)? = null
    ): Flow<List<ResourceItem>> = channelFlow {
        if (keyword.isBlank()) { send(emptyList()); return@channelFlow }

        // 设置页关闭的源不参与搜索
        val effectiveSources = enabledSources.intersect(storageEnabledSourcesFlow().first())

        val now = System.currentTimeMillis()
        val cached = cache[keyword]
        if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
            // 命中缓存：query 非空时按当前 query 重算相关度
            send(filterItems(rank(cached.items, isShow, query).items, effectiveSources, enabledDiskTypes))
            // 缓存命中：所有源视为已完成
            effectiveSources.forEach { onSourceComplete?.invoke(it) }
            return@channelFlow
        }

        val sourceList = mutableListOf<Pair<String, Deferred<List<ResourceItem>>>>()
        if (SOURCE_PANSOU in effectiveSources) {
            sourceList.add(SOURCE_PANSOU to async {
                runCatching { searchPanSource(panSouApiService, keyword, enabledDiskTypes, SOURCE_PANSOU) }.getOrDefault(emptyList())
            })
        }
        if (SOURCE_PANHUB in effectiveSources) {
            sourceList.add(SOURCE_PANHUB to async {
                runCatching { searchPanHubGranular(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
            })
        }
        if (SOURCE_ZRESO in effectiveSources) {
            sourceList.add(SOURCE_ZRESO to async {
                runCatching { searchZreso(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
            })
        }
        if (SOURCE_USEAI in effectiveSources) {
            sourceList.add(SOURCE_USEAI to async {
                runCatching {
                    searchPanSource(
                        apiService = useAiApiService,
                        keyword = keyword,
                        enabledDiskTypes = enabledDiskTypes,
                        sourceName = SOURCE_USEAI,
                        timeoutMs = USEAI_TIMEOUT_MS
                    )
                }.getOrDefault(emptyList())
            })
        }
        if (SOURCE_YUNSO in effectiveSources) {
            sourceList.add(SOURCE_YUNSO to async {
                runCatching { searchYunso(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
            })
        }
        if (SOURCE_ATAW in effectiveSources) {
            sourceList.add(SOURCE_ATAW to async {
                runCatching { searchAtaw(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
            })
        }
        if (SOURCE_MELOST in effectiveSources) {
            sourceList.add(SOURCE_MELOST to async {
                runCatching { searchMelost(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
            })
        }
        if (SOURCE_PANXIAOZI in effectiveSources) {
            sourceList.add(SOURCE_PANXIAOZI to async {
                runCatching { searchPanxiaozi(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
            })
        }
        if (SOURCE_BAOYS in effectiveSources) {
            sourceList.add(SOURCE_BAOYS to async {
                runCatching { searchBaoys(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
            })
        }
        // 自定义源
        val customSources = customSearchSourceStorage.sources.first().filter { it.enabled }
        for (source in customSources) {
            sourceList.add(source.id to async {
                runCatching {
                    withTimeoutOrNull(8_000) {
                        customSearchService.search(source, keyword)
                    } ?: emptyList()
                }.getOrDefault(emptyList())
            })
        }

        if (sourceList.isEmpty()) { send(emptyList()); return@channelFlow }
        if (sourceList.size == 1) {
            val (source, deferred) = sourceList.first()
            val items = deferred.await()
            if (items.isNotEmpty()) cache.put(keyword, KeywordCache(items, now))
            onSourceComplete?.invoke(source)
            send(items)
            return@channelFlow
        }

        // 多源：逐个等待完成，每次发射累积结果（去重排序）
        val accumulated = mutableListOf<ResourceItem>()
        val pending = sourceList.toMutableList()
        while (pending.isNotEmpty()) {
            val (completedSource, completedItems) = select<Pair<String, List<ResourceItem>>> {
                for ((source, deferred) in pending) {
                    deferred.onAwait { items -> source to items }
                }
            }
            pending.removeIf { it.first == completedSource }
            accumulated.addAll(completedItems)
            onSourceComplete?.invoke(completedSource)

            val current = rank(accumulated.distinctBy { it.url }, isShow, query)
            send(current.items)
        }

        // 缓存最终合并结果（只存原始 items，分值不缓存）
        if (accumulated.isNotEmpty()) {
            cache.put(keyword, KeywordCache(
                accumulated.distinctBy { it.url },
                System.currentTimeMillis()))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 本地过滤（不调 API）
     */
    fun filterItems(
        items: List<ResourceItem>,
        enabledSources: Set<String>,
        enabledDiskTypes: Set<DiskType>
    ): List<ResourceItem> {
        return items
            .filter { !it.isInvalid }
            .filter { it.source in enabledSources }
            .filter { it.diskType in enabledDiskTypes }
    }

    /**
     * 只查询用户选中的源，并行请求所有选中源，等全部返回后合并
     */
    private suspend fun fetchEnabledSources(
        keyword: String,
        enabledSources: Set<String>,
        enabledDiskTypes: Set<DiskType>,
        isShow: Boolean,
        query: ResourceQuery? = null
    ): List<ResourceItem> {
        // 获取启用的自定义源
        val customSources = customSearchSourceStorage.sources.first().filter { it.enabled }

        return coroutineScope {
            val deferreds = mutableListOf<Deferred<List<ResourceItem>>>()

            if (SOURCE_PANSOU in enabledSources) {
                deferreds.add(async {
                    runCatching { searchPanSource(panSouApiService, keyword, enabledDiskTypes, SOURCE_PANSOU) }.getOrDefault(emptyList())
                })
            }
            if (SOURCE_PANHUB in enabledSources) {
                deferreds.add(async {
                    runCatching { searchPanHubGranular(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
                })
            }
            if (SOURCE_ZRESO in enabledSources) {
                deferreds.add(async {
                    runCatching { searchZreso(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
                })
            }
            if (SOURCE_USEAI in enabledSources) {
                deferreds.add(async {
                    runCatching {
                        searchPanSource(
                            apiService = useAiApiService,
                            keyword = keyword,
                            enabledDiskTypes = enabledDiskTypes,
                            sourceName = SOURCE_USEAI,
                            timeoutMs = USEAI_TIMEOUT_MS
                        )
                    }.getOrDefault(emptyList())
                })
            }
            if (SOURCE_YUNSO in enabledSources) {
                deferreds.add(async {
                    runCatching { searchYunso(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
                })
            }
            if (SOURCE_ATAW in enabledSources) {
                deferreds.add(async {
                    runCatching { searchAtaw(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
                })
            }
            if (SOURCE_MELOST in enabledSources) {
                deferreds.add(async {
                    runCatching { searchMelost(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
                })
            }
            if (SOURCE_PANXIAOZI in enabledSources) {
                deferreds.add(async {
                    runCatching { searchPanxiaozi(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
                })
            }
            if (SOURCE_BAOYS in enabledSources) {
                deferreds.add(async {
                    runCatching { searchBaoys(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
                })
            }
            // 自定义源
            for (source in customSources) {
                deferreds.add(async {
                    runCatching {
                        withTimeoutOrNull(8_000) {
                            customSearchService.search(source, keyword)
                        } ?: emptyList()
                    }.getOrDefault(emptyList())
                })
            }

            if (deferreds.isEmpty()) return@coroutineScope emptyList()

            // 等待所有选中源都返回，合并结果
            val allResults = deferreds.awaitAll()
            rank(allResults.flatten(), isShow, query).items
        }
    }

    private suspend fun searchPanHubGranular(
        keyword: String,
        enabledDiskTypes: Set<DiskType>
    ): List<ResourceItem> {
        val config = panHubConfigStorage.config.first()
        if (config.enabledPlugins.isEmpty() && config.enabledChannels.isEmpty()) return emptyList()
        val ext = """{"__plugin_timeout_ms":${config.timeoutMs}}"""

        val specs = mutableListOf<suspend () -> com.tracktosearch.data.remote.pansou.dto.PanSouResponse?>()

        config.enabledPlugins.forEach { pluginId ->
            specs.add {
                panHubGranularApiService.search(
                    keyword = keyword,
                    res = "merged_by_type",
                    src = "plugin",
                    concurrency = config.concurrency,
                    ext = ext,
                    plugins = pluginId
                )
            }
        }

        config.enabledChannels.chunked(4).forEach { chunk ->
            specs.add {
                panHubGranularApiService.search(
                    keyword = keyword,
                    res = "merged_by_type",
                    src = "tg",
                    concurrency = config.concurrency,
                    ext = ext,
                    channels = chunk.joinToString(",")
                )
            }
        }

        return coroutineScope {
            specs.chunked(config.concurrency).flatMap { batch ->
                batch.map { spec ->
                    async {
                        runCatching {
                            withTimeoutOrNull(config.timeoutMs.toLong()) {
                                spec()
                            }
                        }.getOrNull()
                    }
                }.awaitAll()
            }.flatMap { response ->
                response?.data?.merged_by_type?.flatMap { (type, links) ->
                    links.mapNotNull { link ->
                        val diskType = mapPanSouType(type) ?: return@mapNotNull null
                        ResourceItem(
                            name = link.note.ifBlank { keyword },
                            diskType = diskType,
                            fileSize = "",
                            fileDate = link.datetime,
                            fileCount = 1,
                            url = link.url,
                            source = SOURCE_PANHUB
                        )
                    }
                } ?: emptyList()
            }.distinctBy { it.url }
        }
    }

    /**
     * 排序后的资源结果。
     * 用 url 作为 key 而非对象引用，避免 ResourceItem 作为可变共享状态在跨影视缓存场景下产生竞态。
     */
    data class RankedResources(
        val items: List<ResourceItem>,
        val scoreMap: Map<String, Int>, // url -> relevanceScore（query 为空时为空 map）
        val highRelevanceMap: Map<String, Boolean> // url -> high relevance（query 为空时为空 map）
    )

    /**
     * 资源排序规则：
     * - scoreMap 非空时，主排序按相关度分值降序，再按以下次级规则
     * - scoreMap 为空时（无目标影视上下文，如通用搜索），沿用原规则：
     *   1. 夸克网盘 > 其它  2. 日期新的在前  3. 电视剧多季结果优先
     */
    private fun resourceComparator(isShow: Boolean, scoreMap: Map<String, Int> = emptyMap()) =
        compareByDescending<ResourceItem> { scoreMap[it.url] ?: 0 }
            .thenByDescending { if (isShow) multiSeasonScore(it.name) else 0 }
            .thenByDescending { it.diskType == DiskType.QUARK } // 夸克优先
            .thenByDescending { it.fileDate } // 日期新
            .thenByDescending { it.fileCount }
            .thenByDescending { it.source == SOURCE_PANSOU }

    /**
     * 按相关度（若有 query）排序，返回排好序的 items 与分值/资格 map。
     * query 为空时两个 map 都为空，沿用原排序规则（夸克/日期/多季）。
     */
    private fun rank(items: List<ResourceItem>, isShow: Boolean, query: ResourceQuery?): RankedResources {
        val distinct = items.distinctBy { it.url }
        val relevanceMap = if (query != null) {
            val scorer = RelevanceScorerProvider.get()
            distinct.associate { it.url to scorer.evaluate(it, query) }
        } else {
            emptyMap()
        }
        val scoreMap = relevanceMap.mapValues { it.value.score }
        val highRelevanceMap = relevanceMap.mapValues { it.value.isHighRelevance }
        val sorted = distinct.sortedWith(resourceComparator(isShow, scoreMap))
        return RankedResources(sorted, scoreMap, highRelevanceMap)
    }

    /**
     * 合并外部传入的资源列表并写入缓存（用于中英文搜索结果合并）。
     * 返回排好序的结果（含 scoreMap 供 ViewModel 后续本地过滤复用分值，避免重复打分）。
     */
    fun mergeAndCacheResources(
        keyword: String,
        items: List<ResourceItem>,
        isShow: Boolean = false,
        query: ResourceQuery? = null
    ): RankedResources {
        val ranked = rank(items, isShow, query)
        if (ranked.items.isNotEmpty()) {
            // 缓存只存原始 items（不打分），分值是 per-query 的不该被缓存
            cache.put(keyword, KeywordCache(ranked.items, System.currentTimeMillis()))
        }
        return ranked
    }

    /** 判断资源名是否包含多季信息，返回优先级分数 */
    private fun multiSeasonScore(name: String): Int {
        val n = name.lowercase()
        return when {
            MULTI_SEASON_FULL.containsMatchIn(n) -> 2
            MULTI_SEASON_SINGLE.containsMatchIn(n) -> 1
            else -> 0
        }
    }

    /**
     * 直接从缓存拿全量（不调 API，不按 filter 过滤）。
     * 如果缓存不存在返回空。
     */
    fun getCachedAllResources(keyword: String): List<ResourceItem> {
        if (keyword.isBlank()) return emptyList()
        val cached = cache[keyword] ?: return emptyList()
        val now = System.currentTimeMillis()
        return if (now - cached.timestamp < CACHE_TTL_MS) {
            cached.items
        } else {
            emptyList()
        }
    }

    private fun cloudTypesForPanSou(types: Set<DiskType>): String {
        if (types == ALL_DISK_TYPES) {
            return "quark,baidu,aliyun,xunlei,uc,115,magnet"
        }
        return types.joinToString(",") { diskTypeToPanSou(it) }
    }

    private fun diskTypeToPanSou(type: DiskType): String = when (type) {
        DiskType.QUARK -> "quark"
        DiskType.BAIDU -> "baidu"
        DiskType.ALI -> "aliyun"
        DiskType.XUNLEI -> "xunlei"
        DiskType.UC -> "uc"
        DiskType.ONEONEFIVE -> "115"
        DiskType.MAGNET -> "magnet"
        DiskType.OTHER -> "others"
    }

    private fun diskTypeToZreso(type: DiskType): String = when (type) {
        DiskType.QUARK -> "quark"
        DiskType.BAIDU -> "baidu"
        DiskType.ALI -> "aliyun"
        DiskType.XUNLEI -> "xunlei"
        DiskType.UC -> "uc"
        DiskType.ONEONEFIVE -> "115"
        DiskType.MAGNET -> "magnet"
        DiskType.OTHER -> ""
    }

    /**
     * 请求 pansou 同构实例（内置 PanSou / 盘搜 useai 共用）。
     *
     * @param timeoutMs 单源超时：默认 20s（公共 PanSou 实例高峰期常超过 8s）；useai 聚合插件多，传 25s
     */
    private suspend fun searchPanSource(
        apiService: PanSouApiService,
        keyword: String,
        enabledDiskTypes: Set<DiskType>,
        sourceName: String,
        timeoutMs: Long = 20_000L
    ): List<ResourceItem> {
        return try {
            val response = withTimeoutOrNull(timeoutMs) {
                apiService.search(keyword = keyword, cloudTypes = cloudTypesForPanSou(enabledDiskTypes))
            } ?: return emptyList()
            if (response.code != 0) return emptyList()
            val data = response.data ?: return emptyList()
            val allLinks = data.merged_by_type.flatMap { (type, links) ->
                links.map { link -> type to link }
            }
            allLinks.mapNotNull { (type, link) ->
                val diskType = mapPanSouType(type) ?: return@mapNotNull null
                ResourceItem(
                    name = link.note.ifBlank { keyword },
                    diskType = diskType,
                    fileSize = "",
                    fileDate = link.datetime,
                    fileCount = 1,
                    url = link.url,
                    source = sourceName
                )
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
    }

    private fun mapPanSouType(type: String): DiskType? = when (type.lowercase()) {
        "quark" -> DiskType.QUARK
        "baidu" -> DiskType.BAIDU
        "aliyun" -> DiskType.ALI
        "xunlei" -> DiskType.XUNLEI
        "uc" -> DiskType.UC
        "115" -> DiskType.ONEONEFIVE
        "magnet" -> DiskType.MAGNET
        else -> null
    }

    private suspend fun searchZreso(keyword: String, enabledDiskTypes: Set<DiskType>): List<ResourceItem> {
        if (System.currentTimeMillis() < zresoCooldownUntil) return emptyList()
        val first = runCatching { requestZreso(keyword, enabledDiskTypes) }
        first.getOrNull()?.let { return it }
        val error = first.exceptionOrNull() ?: return emptyList()
        if (error is CancellationException) throw error
        if (error is HttpException) {
            val dailyLimit = isZresoDailyLimit(error)
            // 429（非每日上限）说明 Cap 验证的配额窗口用尽：用隐藏 WebView 自动过一次人机
            // 验证，再重试一次 /api/search。验证放行按出口 IP 生效，同设备普通 HTTP 请求随即可用。
            if (error.code() == 429 && !dailyLimit && zresoWebViewVerifier.ensureVerified()) {
                val retried = runCatching { requestZreso(keyword, enabledDiskTypes) }
                retried.getOrNull()?.let { return it }
                val retryError = retried.exceptionOrNull()
                if (retryError is CancellationException) throw retryError
                if (retryError is HttpException) {
                    zresoCooldownUntil = System.currentTimeMillis() +
                        cooldownForZreso(retryError, isZresoDailyLimit(retryError))
                }
                return emptyList()
            }
            zresoCooldownUntil = System.currentTimeMillis() + cooldownForZreso(error, dailyLimit)
        } else if (error is ZresoTimeoutException) {
            // 站点无响应：10 分钟内不再重试，避免连续搜索都卡满超时
            zresoCooldownUntil = System.currentTimeMillis() + 10 * 60_000L
        }
        return emptyList()
    }

    /** zreso 429 的冷却时间：每日总次数上限直接停 6 小时；普通窗口配额 3 分钟后可再试。 */
    private fun cooldownForZreso(error: HttpException, dailyLimit: Boolean): Long = when {
        error.code() == 429 && dailyLimit -> 6 * 60 * 60_000L
        error.code() == 429 -> 3 * 60_000L
        else -> 10 * 60_000L
    }

    /** 429 响应体里区分「今日获取次数已达上限」和普通窗口配额。 */
    private fun isZresoDailyLimit(error: HttpException): Boolean = runCatching {
        error.response()?.errorBody()?.string()?.contains("今日获取次数已达上限") == true
    }.getOrDefault(false)

    /** zreso 单次请求与结果映射（不含熔断与验证重试逻辑）。 */
    private suspend fun requestZreso(
        keyword: String,
        enabledDiskTypes: Set<DiskType>
    ): List<ResourceItem> {
        val response = withTimeoutOrNull(8_000) {
            zresoApiService.search(keyword = keyword, cloud = "")
        } ?: throw ZresoTimeoutException()
        val allowedTypes = enabledDiskTypes.map { diskTypeToZreso(it) }.filter { it.isNotEmpty() }.toSet()
        val candidates = response.data.results.flatMap { result ->
            result.links.mapNotNull { link ->
                val diskType = mapZresoType(link.type)
                val typeStr = diskTypeToZreso(diskType)
                if (allowedTypes.isNotEmpty() && typeStr !in allowedTypes) {
                    return@mapNotNull null
                }
                ZresoCandidate(result, link, diskType)
            }
        }
        // zreso 搜索结果只给 /api/wash?t=... 中转地址，需再换一次真实网盘直链。
        // 并发解析但限流（zreso 对高频 /api/wash 会 429，反噬熔断窗口）；
        // 单条失败保留原地址，不因个别链接失效拖垮整次搜索。
        val resolvedUrls = coroutineScope {
            candidates.map { candidate ->
                async {
                    washSemaphore.withPermit { resolveZresoLink(candidate.link.url) }
                }
            }.awaitAll()
        }
        return candidates.mapIndexed { index, candidate ->
            ResourceItem(
                name = candidate.result.title,
                diskType = candidate.diskType,
                fileSize = "",
                fileDate = candidate.result.datetime.ifBlank { candidate.result.date },
                fileCount = candidate.result.links.size,
                status = candidate.result.status,
                url = resolvedUrls[index],
                source = SOURCE_ZRESO
            )
        }
    }

    /**
     * zreso 链接归一化：相对路径补域名；`/api/wash?t=` 中转地址换取真实网盘直链。
     * wash 接口免 Turnstile，但 token 一次性、有效期短，失败时退回原地址。
     */
    private suspend fun resolveZresoLink(rawUrl: String): String {
        val absoluteUrl = if (rawUrl.startsWith("http")) rawUrl else "$ZRESO_BASE_URL$rawUrl"
        if (!rawUrl.contains("/api/wash")) return absoluteUrl
        val token = ZRESO_WASH_TOKEN_REGEX.find(rawUrl)?.groupValues?.getOrNull(1) ?: return absoluteUrl
        val wash = runCatching {
            withTimeoutOrNull(4_000) { zresoApiService.wash(token) }
        }.getOrNull()
        val rawLink = wash?.takeIf { it.ok }?.rawUrl?.takeIf { it.isNotBlank() } ?: return absoluteUrl
        // 上游偶尔返回旧域名 pan.quarks.cn，替换成可用的 pan.quark.cn
        return rawLink.replace("pan.quarks.cn", "pan.quark.cn")
    }

    /**
     * 云搜（yunso.net）：自有 JSON 结构，免鉴权 GET 直连。
     * 网盘类型优先看 `Scrurlname` 中文名，缺失时按 URL 主机兜底。
     */
    private suspend fun searchYunso(keyword: String, enabledDiskTypes: Set<DiskType>): List<ResourceItem> {
        return try {
            // 云搜单页只给 30 来条、total 上限 100，翻两页并行拉，结果量翻倍
            val responses = withTimeoutOrNull(12_000) {
                coroutineScope {
                    (1..YUNSO_PAGES).map { page ->
                        async {
                            runCatching { yunsoApiService.search(keyword = keyword, page = page) }.getOrNull()
                        }
                    }.awaitAll()
                }
            } ?: return emptyList()
            val items = responses.filterNotNull()
                .filter { it.code == 0 }
                .flatMap { it.items ?: emptyList() }
                .distinctBy { it.url }
            items.mapNotNull { item ->
                val url = item.url.trim()
                if (!url.startsWith("http")) return@mapNotNull null
                val diskType = mapYunsoType(item.cloudName, url)
                if (diskType !in enabledDiskTypes) return@mapNotNull null
                ResourceItem(
                    name = item.name.ifBlank { keyword },
                    diskType = diskType,
                    fileSize = "",
                    fileDate = formatEpochMillisDate(item.addTimeMillis),
                    fileCount = 1,
                    url = url,
                    source = SOURCE_YUNSO
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 阿涛搜（so.ataw.top）：逆向接口，需 `x-miniapp-session` 头（接口层已随机 UUID）。
     * 一次请求拿跨网盘混合结果，逐条按 links[] 拆成多条直链。
     */
    private suspend fun searchAtaw(keyword: String, enabledDiskTypes: Set<DiskType>): List<ResourceItem> {
        return try {
            val response = withTimeoutOrNull(15_000) {
                atawApiService.search(keyword = keyword, pageSize = 50)
            } ?: return emptyList()
            response.items.flatMap { item ->
                // 多数条目 links[] 为空：真实地址在 link 字段（可能是空串或多行）或 description 正文「链接：…」里
                val candidates = if (item.links.isNotEmpty()) {
                    item.links.map { link -> link.platform.ifBlank { item.mainPlatform ?: "" } to link.url }
                } else {
                    val urls = extractUrls(item.link).ifEmpty { extractUrls(item.description) }
                    urls.map { url -> (item.mainPlatform ?: "") to url }
                }
                // fileCount 为 0 会被 isInvalid 判成失效，至少给 1
                val fileCount = (item.filePreview?.fileCount ?: 0).coerceAtLeast(1)
                val fileDate = item.submitTime.take(10)
                candidates.distinctBy { it.second }.mapNotNull { (platform, url) ->
                    val cleanUrl = url.trim()
                    if (!cleanUrl.startsWith("http") && !cleanUrl.startsWith("magnet:")) return@mapNotNull null
                    val diskType = mapAtawType(platform, cleanUrl)
                    if (diskType !in enabledDiskTypes) return@mapNotNull null
                    ResourceItem(
                        name = item.title.ifBlank { keyword },
                        diskType = diskType,
                        fileSize = "",
                        fileDate = fileDate,
                        fileCount = fileCount,
                        // 站点侧删除标记 → 卡片「已失效」徽章；空串表示未标记
                        status = if (item.isDeleted) "deleted" else "",
                        url = cleanUrl,
                        source = SOURCE_ATAW
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 影盘社（melost.cn）：POST JSON 免鉴权，disk_name 带搜索词 <em> 高亮需清洗。
     * files 字段多行文本，每行 `file:` 开头一条，可数文件数；shared_time 取前 10 位日期。
     */
    private suspend fun searchMelost(keyword: String, enabledDiskTypes: Set<DiskType>): List<ResourceItem> {
        return try {
            val responses = withTimeoutOrNull(MELOST_TIMEOUT_MS) {
                coroutineScope {
                    (1..MELOST_PAGES).map { page ->
                        async {
                            runCatching {
                                melostApiService.search(MelostSearchRequest(q = keyword, page = page))
                            }.getOrNull()
                        }
                    }.awaitAll()
                }
            } ?: return emptyList()
            responses.filterNotNull()
                .filter { it.code == 200 }
                .flatMap { it.data?.list ?: emptyList() }
                .filter { it.enabled && it.status == 0 && it.link.isNotBlank() }
                .distinctBy { it.link }
                .mapNotNull { item ->
                    val url = item.link.trim()
                    if (!url.startsWith("http")) return@mapNotNull null
                    val diskType = mapAtawType(item.diskType, url)
                    if (diskType !in enabledDiskTypes) return@mapNotNull null
                    ResourceItem(
                        name = MELOST_EM_TAG_REGEX.replace(item.diskName, "").ifBlank { keyword },
                        diskType = diskType,
                        fileSize = "",
                        fileDate = item.sharedTime.take(10),
                        fileCount = item.files.lines().count { it.startsWith("file:") }.coerceAtLeast(1),
                        url = url,
                        source = SOURCE_MELOST
                    )
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 盘小子（pan.xiaozi.cc）：无公开 JSON API（全挂 admin-token），取列表页 SSR 的
     * RSC payload 用 PanxiaoziParser 正则提取；diskType 是中文（"夸克"），按云搜映射。
     */
    private suspend fun searchPanxiaozi(keyword: String, enabledDiskTypes: Set<DiskType>): List<ResourceItem> {
        return try {
            val pages = withTimeoutOrNull(PANXIAOZI_TIMEOUT_MS) {
                coroutineScope {
                    (1..PANXIAOZI_PAGES).map { page ->
                        async {
                            runCatching {
                                panxiaoziApiService.search(keyword = keyword, page = page).use { it.string() }
                            }.getOrNull()
                        }
                    }.awaitAll()
                }
            } ?: return emptyList()
            pages.filterNotNull()
                .flatMap { PanxiaoziParser.parse(it) }
                .mapNotNull { record ->
                    val diskType = mapYunsoType(record.diskType, record.url)
                    if (diskType !in enabledDiskTypes) return@mapNotNull null
                    ResourceItem(
                        name = record.title.ifBlank { keyword },
                        diskType = diskType,
                        fileSize = "",
                        fileDate = record.updatedAt.take(10),
                        fileCount = 1,
                        url = record.url,
                        source = SOURCE_PANXIAOZI
                    )
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 宝藏郎（baoys.baozang.asia）：GET /api?keyword= 免鉴权；实测 10-25 秒返回，
     * 不传 limit 反而更快；无日期字段；cloud_name 是中文（"夸克网盘"），按云搜映射。
     */
    private suspend fun searchBaoys(keyword: String, enabledDiskTypes: Set<DiskType>): List<ResourceItem> {
        return try {
            val response = withTimeoutOrNull(BAOYS_TIMEOUT_MS) {
                baoysApiService.search(keyword = keyword)
            } ?: return emptyList()
            response.results.mapNotNull { item ->
                val url = item.shareLink.trim()
                if (!url.startsWith("http")) return@mapNotNull null
                val diskType = mapYunsoType(item.cloudName, url)
                if (diskType !in enabledDiskTypes) return@mapNotNull null
                ResourceItem(
                    name = item.name.ifBlank { keyword },
                    diskType = diskType,
                    fileSize = "",
                    fileDate = "",
                    fileCount = 1,
                    url = url,
                    source = SOURCE_BAOYS
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 从任意文本里抠出 http(s) 链接（阿涛搜的 link/description 常混排文案） */
    private fun extractUrls(text: String): List<String> {        if (text.isBlank()) return emptyList()
        return ATAW_URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', ';', '，', '。', '、') }.toList()
    }

    /** 云搜网盘中文名 → DiskType，认不出就看 URL 主机 */
    private fun mapYunsoType(cloudName: String, url: String): DiskType = when {
        cloudName.contains("夸克") || url.contains("quark.cn") -> DiskType.QUARK
        cloudName.contains("百度") || url.contains("pan.baidu.com") -> DiskType.BAIDU
        cloudName.contains("阿里") || url.contains("aliyundrive.com") ||
            url.contains("alipan.com") -> DiskType.ALI
        cloudName.contains("迅雷") || url.contains("xunlei.com") -> DiskType.XUNLEI
        cloudName.contains("UC") || url.contains("uc.cn") -> DiskType.UC
        cloudName.contains("115") || url.contains("115.com") -> DiskType.ONEONEFIVE
        cloudName.contains("磁") || url.startsWith("magnet:") -> DiskType.MAGNET
        else -> DiskType.OTHER
    }

    /** 阿涛搜 platform → DiskType，认不出时按 URL 主机兜底 */
    private fun mapAtawType(platform: String, url: String): DiskType = when (platform.lowercase()) {
        "quark" -> DiskType.QUARK
        "baidu" -> DiskType.BAIDU
        "ali", "aliyun" -> DiskType.ALI
        "xunlei" -> DiskType.XUNLEI
        "uc" -> DiskType.UC
        "115" -> DiskType.ONEONEFIVE
        "magnet" -> DiskType.MAGNET
        else -> mapYunsoType("", url)
    }

    /** 毫秒时间戳字符串 → yyyy-MM-dd；解析不了给空串（列表按无日期展示） */
    private fun formatEpochMillisDate(raw: String): String {
        val millis = raw.toLongOrNull() ?: return ""
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(millis))
        }.getOrDefault("")
    }

    private fun mapZresoType(type: String): DiskType = when (type.lowercase()) {
        "quark" -> DiskType.QUARK
        "baidu" -> DiskType.BAIDU
        "aliyun", "ali" -> DiskType.ALI
        "xunlei" -> DiskType.XUNLEI
        "uc" -> DiskType.UC
        "115" -> DiskType.ONEONEFIVE
        "magnet" -> DiskType.MAGNET
        else -> DiskType.OTHER
    }
}
