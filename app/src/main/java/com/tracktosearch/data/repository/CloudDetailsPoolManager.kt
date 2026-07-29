package com.tracktosearch.data.repository

import android.util.Log
import com.tracktosearch.data.remote.cloud.AesCrypto
import com.tracktosearch.data.remote.cloud.GiteeContentRequest
import com.tracktosearch.data.remote.cloud.GiteeContentResponse
import com.tracktosearch.data.remote.cloud.GiteeContentsApi
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.util.PersistentTtlCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局豆瓣详情缓存池管理器（跨用户共享）。
 *
 * 核心目标：减少对豆瓣网站的整体请求量。任意用户爬过的豆瓣详情页（doubanId → imdbId/isTvShow 等）
 * 上传到全局共享池后，其他用户命中即可跳过爬取。
 *
 * 分片策略：SHA-256(doubanId) 前 3 个十六进制字符 → 4096 分片（每片平均几十~几百条）。
 * 文件路径：`details_pool/{prefix}.json`
 *
 * 数据流：
 * - 上传：本地详情 → 按分片聚合 → GET 云端已有 → 合并新数据 → AES 加密 → PUT（乐观锁，重试 3 次）
 * - 下载：按 doubanId 计算分片 → GET 对应分片 → 解密 → 提取目标条目
 *
 * 冲突处理：分片细（4096），冲突率极低；冲突时 GET 最新再合并重试。
 *
 * 安全说明：数据为豆瓣公开详情页信息（imdbId、isTvShow、类型、年份等），非用户私有数据，
 * 加密仅为防止 Gitee token 泄露时被随意浏览，不要求强安全。
 */
@Singleton
class CloudDetailsPoolManager @Inject constructor(
    private val giteeContentsApi: GiteeContentsApi,
    private val json: Json
) {
    companion object {
        private const val TAG = "CloudDetailsPool"
        private const val OWNER = "yufeng-liang"
        private const val REPO = "meta-data"
        private const val PATH_PREFIX = "details_pool/"
        private const val FILE_SUFFIX = ".json"
        private const val SHARD_HASH_LENGTH = 3  // SHA-256 前 3 个十六进制字符 = 4096 分片
        private const val MAX_RETRY = 3
    }

    @Serializable
    private data class ShardPayload(
        val version: Int = 1,
        val total: Int,
        /** key = doubanId，value = DoubanDetailCacheEntry 的 JSON 字符串 */
        val entries: Map<String, String>
    )

    /** 分片上传互斥锁（同一分片串行上传，避免并发 PUT 互相覆盖） */
    private val shardLocks = mutableMapOf<String, Mutex>()
    private val shardLocksLock = Mutex()

    private suspend fun getShardLock(shard: String): Mutex {
        return shardLocksLock.withLock {
            shardLocks.getOrPut(shard) { Mutex() }
        }
    }

    /** 计算 doubanId 所属分片前缀 */
    private fun shardPrefix(doubanId: String): String {
        val hash = AesCrypto.hashUserId(doubanId)  // 复用 SHA-256 前 16 字节逻辑
        return hash.substring(0, SHARD_HASH_LENGTH)
    }

    private fun buildPath(shard: String): String = "$PATH_PREFIX$shard$FILE_SUFFIX"

    private fun parseContentResponse(element: kotlinx.serialization.json.JsonElement?): GiteeContentResponse? {
        if (element == null) return null
        if (element !is JsonObject) return null
        return try {
            json.decodeFromJsonElement(GiteeContentResponse.serializer(), element)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 批量上传详情到全局池（按分片聚合后上传）。
     *
     * @param entries doubanId → DoubanDetailCacheEntry 映射（通常来自 doubanDetailCache.snapshotFromDisk()）
     * @param detailCache 本地缓存，上传成功后无需回写（数据来源就是它）
     * @return 实际上传的分片数（用于日志统计）
     */
    suspend fun uploadDetails(entries: Map<String, DoubanDetailCacheEntry>): Int = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext 0

        // 按分片聚合
        val byShard = entries.entries.groupBy { shardPrefix(it.key) }
        var uploadedShards = 0

        for ((shard, shardEntries) in byShard) {
            if (shardEntries.isEmpty()) continue
            val lock = getShardLock(shard)
            val success = lock.withLock {
                uploadShardWithRetry(shard, shardEntries.associate { it.key to it.value })
            }
            if (success) uploadedShards++
        }
        Log.d(TAG, "上传详情: ${entries.size} 条 → ${byShard.size} 分片，成功 $uploadedShards 片")
        uploadedShards
    }

    /**
     * 上传单个分片（带乐观锁重试）。
     * - GET 云端已有 → 合并新条目 → PUT（带 sha）
     * - 文件不存在 → POST 创建
     * - 冲突（sha 不匹配）→ 重试 GET + 合并 + PUT
     */
    private suspend fun uploadShardWithRetry(shard: String, newEntries: Map<String, DoubanDetailCacheEntry>): Boolean {
        val path = buildPath(shard)
        var attempt = 0
        while (attempt < MAX_RETRY) {
            attempt++
            try {
                // 1. GET 云端已有
                var existingSha: String? = null
                var existingEntries: Map<String, DoubanDetailCacheEntry> = emptyMap()
                try {
                    val resp = giteeContentsApi.getFileContent(OWNER, REPO, path)
                    if (resp.isSuccessful) {
                        val body = parseContentResponse(resp.body())
                        if (body != null) {
                            existingSha = body.sha
                            val content = body.content
                            if (!content.isNullOrEmpty()) {
                                // 网关服务端已解密，content 为 base64(明文 JSON)
                                val plaintext = String(
                                    android.util.Base64.decode(content, android.util.Base64.NO_WRAP),
                                    Charsets.UTF_8
                                )
                                existingEntries = parseShardPayload(plaintext)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // GET 失败，视为文件不存在
                }

                // 2. 合并：新条目覆盖云端旧条目（同 doubanId 以新为准）
                val merged = existingEntries.toMutableMap().apply { putAll(newEntries) }
                if (merged.size == existingEntries.size && newEntries.all { existingEntries.containsKey(it.key) && existingEntries[it.key] == it.value }) {
                    // 云端已包含全部新条目且内容一致，无需上传
                    Log.d(TAG, "分片 $shard 无变化，跳过上传")
                    return true
                }

                // 3. 序列化 + base64 编码（网关服务端透明加密）
                val payload = ShardPayload(
                    total = merged.size,
                    entries = merged.mapValues { (_, v) ->
                        json.encodeToString(DoubanDetailCacheEntry.serializer(), v)
                    }
                )
                val jsonStr = json.encodeToString(ShardPayload.serializer(), payload)
                val base64Content = android.util.Base64.encodeToString(
                    jsonStr.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )

                val request = if (existingSha != null) {
                    GiteeContentRequest(
                        content = base64Content,
                        message = "details_pool/$shard: +${newEntries.size} (total ${merged.size})",
                        sha = existingSha
                    )
                } else {
                    GiteeContentRequest(
                        content = base64Content,
                        message = "details_pool/$shard: +${newEntries.size} (total ${merged.size})"
                    )
                }
                val resp = if (existingSha != null) {
                    giteeContentsApi.putFileContent(OWNER, REPO, path, request)
                } else {
                    giteeContentsApi.createFileContent(OWNER, REPO, path, request)
                }
                if (resp.isSuccessful) {
                    Log.d(TAG, "分片 $shard 上传成功: +${newEntries.size} (total ${merged.size})")
                    return true
                }
                // 409 / 422 等冲突错误 → 重试
                if (resp.code() !in setOf(409, 422)) {
                    Log.w(TAG, "分片 $shard 上传失败: ${resp.code()} ${resp.message()}")
                    return false
                }
                Log.w(TAG, "分片 $shard 冲突，重试 $attempt/$MAX_RETRY")
            } catch (e: Exception) {
                Log.w(TAG, "分片 $shard 上传异常 (attempt=$attempt): ${e.message}")
            }
        }
        Log.w(TAG, "分片 $shard 上传失败: 重试 $MAX_RETRY 次仍冲突")
        return false
    }

    private fun parseShardPayload(jsonStr: String): Map<String, DoubanDetailCacheEntry> {
        return try {
            val payload = json.decodeFromString(ShardPayload.serializer(), jsonStr)
            payload.entries.mapValues { (_, v) ->
                json.decodeFromString(DoubanDetailCacheEntry.serializer(), v)
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 按需下载多个 doubanId 的详情（按分片聚合 GET，提取目标条目）。
     *
     * @param doubanIds 需要查询的 doubanId 列表
     * @return doubanId → DoubanDetailCacheEntry（仅包含命中的）
     */
    suspend fun downloadDetails(doubanIds: List<String>): Map<String, DoubanDetailCacheEntry> = withContext(Dispatchers.IO) {
        if (doubanIds.isEmpty()) return@withContext emptyMap()
        val result = mutableMapOf<String, DoubanDetailCacheEntry>()

        // 按分片聚合
        val byShard = doubanIds.groupBy { shardPrefix(it) }
        for ((shard, ids) in byShard) {
            val idSet = ids.toSet()
            val shardEntries = downloadShard(shard) ?: continue
            for (id in idSet) {
                shardEntries[id]?.let { result[id] = it }
            }
        }
        Log.d(TAG, "下载详情: 请求 ${doubanIds.size} 条，命中 ${result.size} 条")
        result
    }

    /** 下载单条详情 */
    suspend fun downloadDetail(doubanId: String): DoubanDetailCacheEntry? = withContext(Dispatchers.IO) {
        val shard = shardPrefix(doubanId)
        val shardEntries = downloadShard(shard) ?: return@withContext null
        shardEntries[doubanId]
    }

    private suspend fun downloadShard(shard: String): Map<String, DoubanDetailCacheEntry>? {
        // 持分片锁,避免与 uploadShardWithRetry 并发导致乐观锁 409 放大
        val lock = getShardLock(shard)
        return lock.withLock { downloadShardUnlocked(shard) }
    }

    private suspend fun downloadShardUnlocked(shard: String): Map<String, DoubanDetailCacheEntry>? {
        val path = buildPath(shard)
        return try {
            val resp = giteeContentsApi.getFileContent(OWNER, REPO, path)
            if (!resp.isSuccessful) {
                if (resp.code() != 404) Log.w(TAG, "下载分片 $shard 失败: ${resp.code()}")
                return null
            }
            val body = parseContentResponse(resp.body()) ?: return null
            val base64Content = body.content ?: return null
            // 网关服务端已解密，content 为 base64(明文 JSON)
            val plaintext = String(
                android.util.Base64.decode(base64Content, android.util.Base64.NO_WRAP),
                Charsets.UTF_8
            )
            parseShardPayload(plaintext)
        } catch (e: Exception) {
            Log.w(TAG, "下载分片 $shard 异常: ${e.message}")
            null
        }
    }

    /**
     * 上传单条用户标注的媒体类型到全局池。
     *
     * 用户在失败项详情页/列表页标注 movie/show/variety/documentary 后立即调用。
     * 映射: movie → isTvShow=false; show/variety/documentary → isTvShow=true。
     * 上传时 GET 对应分片 → 合并(用户标注覆盖 isTvShow + mediaType,其他字段保留池中原值) → PUT。
     * 若池中无该 doubanId,则创建仅含 isTvShow + mediaType 的条目。
     *
     * 清除标注(mediaType=null)也会上传,强覆盖池中 mediaType 为 null(用户主动清除优先于池中其他用户标注)。
     * 自动推断(综艺/纪录片)优先级仍最高,会在进入详情页时覆盖用户清除。
     *
     * @param doubanId 豆瓣条目 ID
     * @param mediaType "movie"/"show"/"variety"/"documentary"; null=清除标注(也上传强覆盖)
     * @return 是否上传成功
     */
    suspend fun uploadUserMarkedMediaType(doubanId: String, mediaType: String?): Boolean = withContext(Dispatchers.IO) {
        val shard = shardPrefix(doubanId)
        val lock = getShardLock(shard)
        lock.withLock {
            // 构造仅含 isTvShow + mediaType 的条目；其他字段尝试保留池中原值
            val existing = downloadShardUnlocked(shard) ?: emptyMap()
            // F-42: 清除标注(mediaType=null)且池中无该条目时,无需上传空条目,直接返回成功
            if (mediaType == null && existing[doubanId] == null) {
                Log.d(TAG, "用户清除 $doubanId mediaType 但池中无该条目,跳过上传")
                return@withLock true
            }
            val existingEntry = existing[doubanId]
            // 清除标注(null)时保留池中原 isTvShow;标注非null时按 mediaType 推导;池中无条目且清除时默认 false
            val isTvShow = when {
                mediaType != null -> mediaType != "movie"
                existingEntry != null -> existingEntry.isTvShow
                else -> false
            }
            val newEntry = if (existingEntry != null) {
                // 覆盖 isTvShow + mediaType,保留其他字段
                existingEntry.copy(isTvShow = isTvShow, mediaType = mediaType)
            } else {
                // 池中无该条目,创建仅含 isTvShow + mediaType 的条目
                DoubanDetailCacheEntry(imdbId = null, isTvShow = isTvShow, mediaType = mediaType)
            }
            // 若 isTvShow + mediaType 与池中一致则跳过上传
            if (existingEntry != null && existingEntry.isTvShow == isTvShow && existingEntry.mediaType == mediaType) {
                Log.d(TAG, "用户标注 $doubanId mediaType=$mediaType 与池中一致,跳过上传")
                return@withLock true
            }
            uploadShardWithRetry(shard, mapOf(doubanId to newEntry))
        }
    }

    /**
     * 上传单条完整详情条目到全局池（字段级合并：非 null 字段覆盖，null 字段保留池中原值）。
     *
     * fetchDetail 成功爬取豆瓣详情后调用，供其他用户复用，减少豆瓣爬取次数。
     * 合并规则：
     * - 池中无该条目 → 直接上传完整条目
     * - 池中有该条目 → 非 null 字段覆盖池中原值，null 字段保留池中原值（避免覆盖其他用户已标注的 mediaType 等）
     *
     * @param doubanId 豆瓣条目 ID
     * @param entry 本地爬取的完整详情条目
     * @return 是否上传成功
     */
    suspend fun uploadDetailEntry(doubanId: String, entry: DoubanDetailCacheEntry): Boolean = withContext(Dispatchers.IO) {
        val shard = shardPrefix(doubanId)
        val lock = getShardLock(shard)
        lock.withLock {
            val existing = downloadShardUnlocked(shard) ?: emptyMap()
            val existingEntry = existing[doubanId]
            val mergedEntry = if (existingEntry != null) {
                // 字段级合并：非 null 字段覆盖，null 字段保留池中原值
                existingEntry.copy(
                    imdbId = entry.imdbId ?: existingEntry.imdbId,
                    isTvShow = entry.isTvShow,
                    title = entry.title ?: existingEntry.title,
                    posterUrl = entry.posterUrl ?: existingEntry.posterUrl,
                    genres = entry.genres.ifEmpty { existingEntry.genres },
                    year = entry.year ?: existingEntry.year,
                    countries = entry.countries.ifEmpty { existingEntry.countries },
                    directors = entry.directors.ifEmpty { existingEntry.directors },
                    mediaType = entry.mediaType ?: existingEntry.mediaType,
                    doubanRating = entry.doubanRating ?: existingEntry.doubanRating,
                    ratingCount = entry.ratingCount ?: existingEntry.ratingCount,
                    summary = entry.summary ?: existingEntry.summary,
                    episodeCount = entry.episodeCount ?: existingEntry.episodeCount,
                    episodeDuration = entry.episodeDuration ?: existingEntry.episodeDuration,
                    aka = entry.aka.ifEmpty { existingEntry.aka },
                    runtime = entry.runtime ?: existingEntry.runtime,
                    writers = entry.writers.ifEmpty { existingEntry.writers },
                    cast = entry.cast.ifEmpty { existingEntry.cast },
                    languages = entry.languages.ifEmpty { existingEntry.languages },
                    initialReleaseDates = entry.initialReleaseDates.ifEmpty { existingEntry.initialReleaseDates },
                    ratingDistribution = entry.ratingDistribution.ifEmpty { existingEntry.ratingDistribution },
                    celebrities = entry.celebrities.ifEmpty { existingEntry.celebrities }
                )
            } else {
                entry
            }
            // 若合并后与池中完全一致则跳过上传（比较关键字段，避免 List 引用差异导致恒假）
            if (existingEntry != null && existingEntry.isSameContent(mergedEntry)) {
                Log.d(TAG, "详情 $doubanId 与池中一致,跳过上传")
                return@withLock true
            }
            uploadShardWithRetry(shard, mapOf(doubanId to mergedEntry))
        }
    }

    /** 内容级等价比较：跳过 List 引用/顺序等物理差异，仅比较业务字段。
     *  set-like List 字段(genres/countries/directors/aka/writers/cast/languages/initialReleaseDates)先排序再比较，
     *  避免顺序差异导致 isSameContent 恒假、触发不必要的分片上传。
     *  ratingDistribution(评分分布,位置敏感)与 celebrities(演职员,按番位排序)保留原始顺序比较。 */
    private fun DoubanDetailCacheEntry.isSameContent(other: DoubanDetailCacheEntry): Boolean {
        return imdbId == other.imdbId && isTvShow == other.isTvShow && title == other.title &&
            posterUrl == other.posterUrl && year == other.year && mediaType == other.mediaType &&
            doubanRating == other.doubanRating && ratingCount == other.ratingCount && summary == other.summary &&
            episodeCount == other.episodeCount && episodeDuration == other.episodeDuration &&
            runtime == other.runtime &&
            // set-like 字段：排序后比较，顺序无关
            genres.sorted() == other.genres.sorted() &&
            countries.sorted() == other.countries.sorted() &&
            directors.sorted() == other.directors.sorted() &&
            aka.sorted() == other.aka.sorted() &&
            writers.sorted() == other.writers.sorted() &&
            cast.sorted() == other.cast.sorted() &&
            languages.sorted() == other.languages.sorted() &&
            initialReleaseDates.sorted() == other.initialReleaseDates.sorted() &&
            // 位置敏感字段：保留原始顺序比较
            ratingDistribution == other.ratingDistribution && celebrities == other.celebrities
    }

    /**
     * 拉取全局池中指定 doubanId 的详情并合并到本地缓存（不覆盖本地已有）。
     *
     * 用于同步流程阶段 1 前置查询：本地缓存未命中时，先查全局池，命中则写入本地缓存秒回，
     * 避免对豆瓣网站的爬取请求。
     *
     * @param doubanIds 需要补全详情的 doubanId 列表
     * @param detailCache 本地持久化缓存
     * @return 命中并写入本地缓存的条目数
     */
    suspend fun fetchAndMergeToLocal(
        doubanIds: List<String>,
        detailCache: PersistentTtlCache<DoubanDetailCacheEntry>
    ): Int = withContext(Dispatchers.IO) {
        if (doubanIds.isEmpty()) return@withContext 0
        // 过滤掉本地已有的（避免不必要的分片 GET）
        val needFetch = doubanIds.filter { detailCache.get(it) == null }
        if (needFetch.isEmpty()) return@withContext 0

        val cloudEntries = downloadDetails(needFetch)
        if (cloudEntries.isEmpty()) return@withContext 0

        // 写入本地缓存（不覆盖已有，虽然前面过滤了，但并发可能写入）
        var written = 0
        for ((id, entry) in cloudEntries) {
            if (detailCache.get(id) == null) {
                detailCache.put(id, entry)
                written++
            }
        }
        Log.d(TAG, "全局池 → 本地缓存: 请求 ${needFetch.size}，命中 ${cloudEntries.size}，写入 $written")
        written
    }
}
