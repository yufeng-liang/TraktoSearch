package com.tracktosearch.data.repository

import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.CustomSearchSourceStorage
import com.tracktosearch.data.local.PanHubConfigStorage
import com.tracktosearch.data.local.SearchSourceStorage
import com.tracktosearch.data.remote.custom.CustomSearchService
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.panhub.PanHubApiService
import com.tracktosearch.data.remote.pansou.PanSouApiService
import com.tracktosearch.data.remote.zreso.ZresoApiService
import javax.inject.Named
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResourceRepository @Inject constructor(
    private val panSouApiService: PanSouApiService,
    @Named("panhub") private val panHubApiService: PanSouApiService,
    private val panHubGranularApiService: PanHubApiService,
    private val panHubConfigStorage: PanHubConfigStorage,
    private val zresoApiService: ZresoApiService,
    private val searchSourceStorage: SearchSourceStorage,
    private val customSearchSourceStorage: CustomSearchSourceStorage,
    private val customSearchService: CustomSearchService
) {

    /**
     * 根据设置页开关计算实际启用的搜索源（与调用方传入的 enabledSources 取交集）。
     * 设置页关闭的源永远不参与搜索。
     */
    private fun storageEnabledSourcesFlow(): Flow<Set<String>> = combine(
        searchSourceStorage.pansouEnabled,
        searchSourceStorage.panhubEnabled,
        searchSourceStorage.zresoEnabled
    ) { pansou, panhub, zreso ->
        buildSet {
            if (pansou) add(SOURCE_PANSOU)
            if (panhub) add(SOURCE_PANHUB)
            if (zreso) add(SOURCE_ZRESO)
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
        val ALL_SOURCES = setOf(SOURCE_PANSOU, SOURCE_PANHUB, SOURCE_ZRESO)
        val ALL_DISK_TYPES = setOf(
            DiskType.QUARK, DiskType.BAIDU, DiskType.ALI,
            DiskType.XUNLEI, DiskType.UC, DiskType.ONEONEFIVE, DiskType.MAGNET
        )
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private val MULTI_SEASON_FULL = Regex("""全\s*季|合集|1[-~]\d+\s*季|第\s*\d+\s*[-~]\s*\d+\s*季""")
        private val MULTI_SEASON_SINGLE = Regex("""第\s*\d+\s*季""")
    }

    private data class KeywordCache(
        val items: List<ResourceItem>,
        val timestamp: Long
    )

    // 搜索关键词结果缓存，LRU 限制 50 条防内存增长
    private val cache = android.util.LruCache<String, KeywordCache>(50)

    // LruCache 非线程安全，多协程并发 get/put/remove 会触发 ConcurrentModificationException
    // 或 get 进入死循环（LinkedHashMap 结构破坏），用 Mutex 串行化所有 cache 访问
    private val cacheMutex = Mutex()

    /**
     * 搜索接口：
     * 1) 第一次调用（按 keyword）：用全 sources + 全 disk_types 调 API，结果缓存
     * 2) 后续调用（同 keyword，不同 filter）：从缓存里本地过滤，不调 API
     */
    suspend fun searchResources(
        keyword: String,
        enabledSources: Set<String> = ALL_SOURCES,
        enabledDiskTypes: Set<DiskType> = ALL_DISK_TYPES,
        isShow: Boolean = false
    ): Result<List<ResourceItem>> {
        if (keyword.isBlank()) return Result.success(emptyList())

        // 设置页关闭的源不参与搜索
        val effectiveSources = enabledSources.intersect(storageEnabledSourcesFlow().first())

        val now = System.currentTimeMillis()
        val cached = cacheMutex.withLock { cache[keyword] }
        val allItems = if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
            cached.items
        } else {
            // 只查询用户选中的源，避免无效请求
            val fetched = fetchEnabledSources(keyword, effectiveSources, enabledDiskTypes, isShow)
            // 只缓存非空结果，避免"空结果被缓存导致后续一直空"的问题
            if (fetched.isNotEmpty()) {
                cacheMutex.withLock { cache.put(keyword, KeywordCache(fetched, now)) }
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
        isShow: Boolean = false
    ): Result<List<ResourceItem>> {
        if (keyword.isBlank()) return Result.success(emptyList())
        cacheMutex.withLock { cache.remove(keyword) }
        return searchResources(keyword, enabledSources, enabledDiskTypes, isShow)
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
        onSourceComplete: ((String) -> Unit)? = null
    ): Flow<List<ResourceItem>> = channelFlow {
        if (keyword.isBlank()) { send(emptyList()); return@channelFlow }

        // 设置页关闭的源不参与搜索
        val effectiveSources = enabledSources.intersect(storageEnabledSourcesFlow().first())

        val now = System.currentTimeMillis()
        val cached = cacheMutex.withLock { cache[keyword] }
        if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
            send(filterItems(cached.items, effectiveSources, enabledDiskTypes))
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
            if (items.isNotEmpty()) cacheMutex.withLock { cache.put(keyword, KeywordCache(items, now)) }
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

            val current = accumulated
                .distinctBy { it.url }
                .sortedWith(resourceComparator(isShow))
            send(current)
        }

        // 缓存最终合并结果
        if (accumulated.isNotEmpty()) {
            cacheMutex.withLock {
                cache.put(keyword, KeywordCache(
                    accumulated.distinctBy { it.url }.sortedWith(resourceComparator(isShow)),
                    System.currentTimeMillis()
                ))
            }
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
        isShow: Boolean
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
            allResults.flatten().sortedWith(resourceComparator(isShow))
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
     * 资源排序规则：
     * 1. 夸克网盘 > 其它
     * 2. 日期新的在前
     * 3. 电视剧多季结果优先
     */
    private fun resourceComparator(isShow: Boolean) = compareByDescending<ResourceItem> {
        if (isShow) multiSeasonScore(it.name) else 0
    }.thenByDescending { it.diskType == DiskType.QUARK } // 夸克优先
        .thenByDescending { it.fileDate } // 日期新
        .thenByDescending { it.fileCount }
        .thenByDescending { it.source == SOURCE_PANSOU }

    /**
     * 合并外部传入的资源列表并写入缓存（用于中英文搜索结果合并）
     */
    suspend fun mergeAndCacheResources(keyword: String, items: List<ResourceItem>, isShow: Boolean = false): List<ResourceItem> {
        val merged = items
            .distinctBy { it.url }
            .sortedWith(resourceComparator(isShow))
        if (merged.isNotEmpty()) {
            cacheMutex.withLock { cache.put(keyword, KeywordCache(merged, System.currentTimeMillis())) }
        }
        return merged
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
    suspend fun getCachedAllResources(keyword: String): List<ResourceItem> {
        if (keyword.isBlank()) return emptyList()
        val cached = cacheMutex.withLock { cache[keyword] } ?: return emptyList()
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

    private suspend fun searchPanSource(
        apiService: PanSouApiService,
        keyword: String,
        enabledDiskTypes: Set<DiskType>,
        sourceName: String
    ): List<ResourceItem> {
        return try {
            val response = withTimeoutOrNull(8_000) {
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
        return try {
            val response = withTimeoutOrNull(8_000) {
                zresoApiService.search(keyword = keyword, cloud = "")
            } ?: return emptyList()
            val allowedTypes = enabledDiskTypes.map { diskTypeToZreso(it) }.filter { it.isNotEmpty() }.toSet()
            response.data.results.flatMap { result ->
                result.links.mapNotNull { link ->
                    val fullUrl = if (link.url.startsWith("http")) {
                        link.url
                    } else {
                        "https://zreso.cn${link.url}"
                    }
                    val diskType = mapZresoType(link.type)
                    val typeStr = diskTypeToZreso(diskType)
                    if (allowedTypes.isNotEmpty() && typeStr !in allowedTypes) {
                        return@mapNotNull null
                    }
                    ResourceItem(
                        name = result.title,
                        diskType = diskType,
                        fileSize = "",
                        fileDate = result.datetime.ifBlank { result.date },
                        fileCount = result.links.size,
                        status = result.status,
                        url = fullUrl,
                        source = SOURCE_ZRESO
                    )
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
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
