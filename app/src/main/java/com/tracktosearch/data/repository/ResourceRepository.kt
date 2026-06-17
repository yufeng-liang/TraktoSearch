package com.tracktosearch.data.repository

import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.pansou.PanSouApiService
import com.tracktosearch.data.remote.zreso.ZresoApiService
import javax.inject.Named
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResourceRepository @Inject constructor(
    private val panSouApiService: PanSouApiService,
    @Named("panhub") private val panHubApiService: PanSouApiService,
    private val zresoApiService: ZresoApiService
) {

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
    }

    private data class KeywordCache(
        val items: List<ResourceItem>,
        val timestamp: Long
    )

    private val cache = ConcurrentHashMap<String, KeywordCache>()

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

        val now = System.currentTimeMillis()
        val cached = cache[keyword]
        val allItems = if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
            cached.items
        } else {
            // 只查询用户选中的源，避免无效请求
            val fetched = fetchEnabledSources(keyword, enabledSources, enabledDiskTypes, isShow)
            // 只缓存非空结果，避免"空结果被缓存导致后续一直空"的问题
            if (fetched.isNotEmpty()) {
                cache[keyword] = KeywordCache(fetched, now)
            }
            fetched
        }

        return Result.success(filterItems(allItems, enabledSources, enabledDiskTypes))
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
        cache.remove(keyword)
        return searchResources(keyword, enabledSources, enabledDiskTypes, isShow)
    }

    /**
     * 增量搜索接口：先到的源先发射，等所有源完成后发射合并结果。
     * 发射 1~2 次：
     *   - 第 1 次：最快的源返回时立即发射（先到先显示）
     *   - 第 2 次：所有源完成后发射合并去重结果（数据完整性）
     * 如果有缓存则只发射 1 次。
     */
    fun searchResourcesFlow(
        keyword: String,
        enabledSources: Set<String> = ALL_SOURCES,
        enabledDiskTypes: Set<DiskType> = ALL_DISK_TYPES,
        isShow: Boolean = false
    ): Flow<List<ResourceItem>> = channelFlow {
        if (keyword.isBlank()) { send(emptyList()); return@channelFlow }

        val now = System.currentTimeMillis()
        val cached = cache[keyword]
        if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
            send(filterItems(cached.items, enabledSources, enabledDiskTypes))
            return@channelFlow
        }

        val deferreds = mutableListOf<Deferred<List<ResourceItem>>>()
        if (SOURCE_PANSOU in enabledSources) {
            deferreds.add(async {
                runCatching { searchPanSou(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
            })
        }
        if (SOURCE_PANHUB in enabledSources) {
            deferreds.add(async {
                runCatching { searchPanHub(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
            })
        }
        if (SOURCE_ZRESO in enabledSources) {
            deferreds.add(async {
                runCatching { searchZreso(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
            })
        }

        if (deferreds.isEmpty()) { send(emptyList()); return@channelFlow }
        if (deferreds.size == 1) {
            val items = deferreds.first().await()
            if (items.isNotEmpty()) cache[keyword] = KeywordCache(items, now)
            send(items)
            return@channelFlow
        }

        // 多源：select 先拿到最快的源，立即发射
        val firstCompleted = select<Deferred<List<ResourceItem>>> {
            for (d in deferreds) d.onAwait { d }
        }
        val firstItems = firstCompleted.await()
        send(firstItems)

        // 等剩余源完成，合并去重后再次发射
        val restItems = deferreds
            .filter { it !== firstCompleted }
            .flatMap { runCatching { it.await() }.getOrDefault(emptyList()) }
        val allItems = (firstItems + restItems)
            .distinctBy { it.url }
            .sortedWith(resourceComparator(isShow))
        if (allItems.isNotEmpty()) {
            cache[keyword] = KeywordCache(allItems, System.currentTimeMillis())
        }
        send(allItems)
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
        return coroutineScope {
            val deferreds = mutableListOf<Deferred<List<ResourceItem>>>()

            if (SOURCE_PANSOU in enabledSources) {
                deferreds.add(async {
                    runCatching { searchPanSou(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
                })
            }
            if (SOURCE_PANHUB in enabledSources) {
                deferreds.add(async {
                    runCatching { searchPanHub(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
                })
            }
            if (SOURCE_ZRESO in enabledSources) {
                deferreds.add(async {
                    runCatching { searchZreso(keyword, enabledDiskTypes) }.getOrDefault(emptyList())
                })
            }

            if (deferreds.isEmpty()) return@coroutineScope emptyList()

            // 等待所有选中源都返回，合并结果
            val allResults = deferreds.awaitAll()
            allResults.flatten().sortedWith(resourceComparator(isShow))
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
    fun mergeAndCacheResources(keyword: String, items: List<ResourceItem>, isShow: Boolean = false): List<ResourceItem> {
        val merged = items
            .distinctBy { it.url }
            .sortedWith(resourceComparator(isShow))
        if (merged.isNotEmpty()) {
            cache[keyword] = KeywordCache(merged, System.currentTimeMillis())
        }
        return merged
    }

    /** 判断资源名是否包含多季信息，返回优先级分数 */
    private fun multiSeasonScore(name: String): Int {
        val n = name.lowercase()
        return when {
            Regex("""全\s*季|合集|1[-~]\d+\s*季|第\s*\d+\s*[-~]\s*\d+\s*季""").containsMatchIn(n) -> 2
            Regex("""第\s*\d+\s*季""").containsMatchIn(n) -> 1
            else -> 0
        }
    }

    fun clearCache() {
        cache.clear()
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

    /**
     * 检查缓存是否存在且有效
     */
    fun hasValidCache(keyword: String): Boolean {
        if (keyword.isBlank()) return false
        val cached = cache[keyword] ?: return false
        return System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS
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

    private suspend fun searchPanSou(keyword: String, enabledDiskTypes: Set<DiskType>): List<ResourceItem> {
        return try {
            val response = withTimeoutOrNull(8_000) {
                panSouApiService.search(keyword = keyword, cloudTypes = cloudTypesForPanSou(enabledDiskTypes))
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
                    source = SOURCE_PANSOU
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun searchPanHub(keyword: String, enabledDiskTypes: Set<DiskType>): List<ResourceItem> {
        return try {
            val response = withTimeoutOrNull(8_000) {
                panHubApiService.search(keyword = keyword, cloudTypes = cloudTypesForPanSou(enabledDiskTypes))
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
                    source = SOURCE_PANHUB
                )
            }
        } catch (e: Exception) {
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
            val cloud = if (enabledDiskTypes == ALL_DISK_TYPES || enabledDiskTypes.size > 1) {
                enabledDiskTypes.firstOrNull()?.let { diskTypeToZreso(it) } ?: ""
            } else {
                enabledDiskTypes.firstOrNull()?.let { diskTypeToZreso(it) } ?: ""
            }
            val response = withTimeoutOrNull(8_000) {
                zresoApiService.search(keyword = keyword, cloud = cloud)
            } ?: return emptyList()
            response.data.results.mapNotNull { result ->
                val link = result.links.firstOrNull() ?: return@mapNotNull null
                val fullUrl = if (link.url.startsWith("http")) {
                    link.url
                } else {
                    "https://zreso.cn${link.url}"
                }
                val diskType = mapZresoType(link.type)
                if (cloud.isNotEmpty() && diskType != mapZresoTypeFirst(cloud)) {
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
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun mapZresoTypeFirst(cloud: String): DiskType? = when (cloud.lowercase()) {
        "quark" -> DiskType.QUARK
        "baidu" -> DiskType.BAIDU
        "aliyun" -> DiskType.ALI
        "xunlei" -> DiskType.XUNLEI
        "uc" -> DiskType.UC
        "115" -> DiskType.ONEONEFIVE
        "magnet" -> DiskType.MAGNET
        else -> null
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
