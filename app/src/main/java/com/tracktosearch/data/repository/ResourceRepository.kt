package com.tracktosearch.data.repository

import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.pansou.PanSouApiService
import com.tracktosearch.data.remote.zreso.ZresoApiService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResourceRepository @Inject constructor(
    private val panSouApiService: PanSouApiService,
    private val zresoApiService: ZresoApiService
) {

    companion object {
        const val SOURCE_PANSOU = "pansou"
        const val SOURCE_ZRESO = "zreso"
        val ALL_SOURCES = setOf(SOURCE_PANSOU, SOURCE_ZRESO)
        val ALL_DISK_TYPES = setOf(
            DiskType.QUARK, DiskType.BAIDU, DiskType.ALI,
            DiskType.XUNLEI, DiskType.UC, DiskType.ONEONEFIVE
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
        enabledDiskTypes: Set<DiskType> = ALL_DISK_TYPES
    ): Result<List<ResourceItem>> {
        if (keyword.isBlank()) return Result.success(emptyList())

        val now = System.currentTimeMillis()
        val cached = cache[keyword]
        val allItems = if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
            cached.items
        } else {
            // 调两个 API 拉全量
            val fetched = fetchAllSources(keyword)
            cache[keyword] = KeywordCache(fetched, now)
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
        enabledDiskTypes: Set<DiskType> = ALL_DISK_TYPES
    ): Result<List<ResourceItem>> {
        if (keyword.isBlank()) return Result.success(emptyList())
        cache.remove(keyword)
        return searchResources(keyword, enabledSources, enabledDiskTypes)
    }

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

    private suspend fun fetchAllSources(keyword: String): List<ResourceItem> {
        val (panSouItems, zresoItems) = coroutineScope {
            val panSouDeferred = async {
                runCatching { searchPanSou(keyword, ALL_DISK_TYPES) }.getOrDefault(emptyList())
            }
            val zresoDeferred = async {
                runCatching { searchZreso(keyword, ALL_DISK_TYPES) }.getOrDefault(emptyList())
            }
            panSouDeferred.await() to zresoDeferred.await()
        }
        return (panSouItems + zresoItems)
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<ResourceItem> { it.fileDate }
                    .thenByDescending { it.fileCount }
                    .thenByDescending { it.diskType == DiskType.QUARK }
                    .thenByDescending { it.source == SOURCE_PANSOU }
            )
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
            return "quark,baidu,aliyun,xunlei,uc,115"
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
        DiskType.OTHER -> "others"
    }

    private fun diskTypeToZreso(type: DiskType): String = when (type) {
        DiskType.QUARK -> "quark"
        DiskType.BAIDU -> "baidu"
        DiskType.ALI -> "aliyun"
        DiskType.XUNLEI -> "xunlei"
        DiskType.UC -> "uc"
        DiskType.ONEONEFIVE -> "115"
        DiskType.OTHER -> ""
    }

    private suspend fun searchPanSou(keyword: String, enabledDiskTypes: Set<DiskType>): List<ResourceItem> {
        val cloudTypes = cloudTypesForPanSou(enabledDiskTypes)
        val response = panSouApiService.search(keyword = keyword, cloudTypes = cloudTypes)
        if (response.code != 0) return emptyList()
        val data = response.data ?: return emptyList()
        val allLinks = data.merged_by_type.flatMap { (type, links) ->
            links.map { link -> type to link }
        }
        return allLinks.mapNotNull { (type, link) ->
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
    }

    private fun mapPanSouType(type: String): DiskType? = when (type.lowercase()) {
        "quark" -> DiskType.QUARK
        "baidu" -> DiskType.BAIDU
        "aliyun" -> DiskType.ALI
        "xunlei" -> DiskType.XUNLEI
        "uc" -> DiskType.UC
        "115" -> DiskType.ONEONEFIVE
        else -> null
    }

    private suspend fun searchZreso(keyword: String, enabledDiskTypes: Set<DiskType>): List<ResourceItem> {
        val cloud = if (enabledDiskTypes == ALL_DISK_TYPES || enabledDiskTypes.size > 1) {
            enabledDiskTypes.firstOrNull()?.let { diskTypeToZreso(it) } ?: ""
        } else {
            enabledDiskTypes.firstOrNull()?.let { diskTypeToZreso(it) } ?: ""
        }
        val response = zresoApiService.search(keyword = keyword, cloud = cloud)
        return response.data.results.mapNotNull { result ->
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
    }

    private fun mapZresoTypeFirst(cloud: String): DiskType? = when (cloud.lowercase()) {
        "quark" -> DiskType.QUARK
        "baidu" -> DiskType.BAIDU
        "aliyun" -> DiskType.ALI
        "xunlei" -> DiskType.XUNLEI
        "uc" -> DiskType.UC
        "115" -> DiskType.ONEONEFIVE
        else -> null
    }

    private fun mapZresoType(type: String): DiskType = when (type.lowercase()) {
        "quark" -> DiskType.QUARK
        "baidu" -> DiskType.BAIDU
        "aliyun", "ali" -> DiskType.ALI
        "xunlei" -> DiskType.XUNLEI
        "uc" -> DiskType.UC
        "115" -> DiskType.ONEONEFIVE
        else -> DiskType.OTHER
    }
}
