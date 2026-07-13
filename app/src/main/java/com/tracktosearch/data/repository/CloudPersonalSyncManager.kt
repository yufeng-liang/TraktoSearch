package com.tracktosearch.data.repository

import android.util.Log
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.UserProfileStorage
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.local.db.DoubanSyncPendingItemDao
import com.tracktosearch.data.local.db.DoubanSyncPendingItemEntity
import com.tracktosearch.data.remote.cloud.AesCrypto
import com.tracktosearch.data.remote.cloud.GiteeContentRequest
import com.tracktosearch.data.remote.cloud.GiteeContentResponse
import com.tracktosearch.data.remote.cloud.GiteeContentsApi
import com.tracktosearch.data.remote.trakt.dto.TraktSearchResult
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
 * 个人数据云端同步管理器（跨设备同步豆瓣同步进度和数据）。
 *
 * 解决场景：
 * - A 手机导入豆瓣后，B 手机登录同 trakt 账号选择增量同步 → 智能跳过已同步数据，避免全量爬取豆瓣
 * - A 手机中途取消，B 手机选择增量同步 → 接续 A 的进度（pending items）
 *
 * 云端路径（按 trakt username 隔离，SHA-256 前 16 字节做文件名）：
 * - `personal/{hash}/synced_items.json` - 已同步条目（doubanId → traktId/imdbId 映射）
 * - `personal/{hash}/pending_items.json` - 断点续传 pending（A 中途取消时上传）
 * - `personal/{hash}/id_mappings.json` - IMDb→Trakt ID 映射（避免重复查询 Trakt API）
 * - `personal/{hash}/sync_meta.json` - 同步元信息（lastFullSyncAt 等用于「近期跳过列表」决策）
 *
 * 数据流：本地 Room/DataStore → JSON 序列化 → AES-256-CBC 加密 → Base64 → Gitee Contents API PUT
 *
 * 失败处理：上传/下载失败只记录日志，不阻塞主同步流程。
 *
 * 隔离说明：用 trakt username 而非 douban userId，因为豆瓣同步的前提是已登录 trakt，
 * 且同一 trakt 账号可能换豆瓣账号导入，trakt username 是更稳定的账号标识。
 * （username 理论上可改，但极少改；改了需重新完整同步，可接受）
 */
@Singleton
class CloudPersonalSyncManager @Inject constructor(
    private val giteeContentsApi: GiteeContentsApi,
    private val doubanSyncedItemDao: DoubanSyncedItemDao,
    private val doubanSyncPendingItemDao: DoubanSyncPendingItemDao,
    private val userProfileStorage: UserProfileStorage,
    private val doubanSyncMetaStorage: DoubanSyncMetaStorage,
    private val traktRepository: TraktRepository,
    private val json: Json
) {
    companion object {
        private const val TAG = "CloudPersonalSync"
        private const val OWNER = "yufeng-liang"
        private const val REPO = "meta-data"
        private const val PATH_PREFIX = "personal/"
        private const val FILE_SYNCED = "synced_items.json"
        private const val FILE_PENDING = "pending_items.json"
        private const val FILE_ID_MAPPINGS = "id_mappings.json"
        private const val FILE_META = "sync_meta.json"
        // 节流窗口:5 秒内的重复 refreshMetaOnly 调用只发一次网络请求
        // (设置页 LaunchedEffect(Unit) 和 LaunchedEffect(doubanLoggedIn) 首次会并发触发两次)
        private const val META_REFRESH_THROTTLE_MS = 5_000L
        // 失败后短节流窗口:允许快速重试(1 秒),避免失败被长节流掩盖
        private const val META_REFRESH_RETRY_THROTTLE_MS = 1_000L
    }

    // 节流锁 + 上次刷新时间戳(分成功/失败),避免并发/短时重复请求 gitee
    private val metaRefreshMutex = Mutex()
    @Volatile private var lastMetaRefreshSuccessAt = 0L
    @Volatile private var lastMetaRefreshFailedAt = 0L

    // ===== 数据模型 =====

    @Serializable
    private data class SyncedItemsPayload(
        val version: Int = 1,
        val total: Int,
        val items: List<SyncedItemDto>
    )

    @Serializable
    private data class SyncedItemDto(
        val doubanId: String,
        val imdbId: String?,
        val traktId: Int?,
        val title: String,
        val status: String,
        val rating: Int?,
        val syncedAt: Long,
        val mediaType: String
    )

    @Serializable
    private data class PendingItemsPayload(
        val version: Int = 1,
        val total: Int,
        val items: List<PendingItemDto>
    )

    @Serializable
    private data class PendingItemDto(
        val doubanId: String,
        val title: String,
        val posterUrl: String?,
        val rating: Int?,
        val comment: String?,
        val markedAt: String,
        val doubanUrl: String,
        val status: String,
        val crawledAt: Long
    )

    @Serializable
    private data class IdMappingsPayload(
        val version: Int = 1,
        val total: Int,
        /** key 格式: "{imdbId}_{MOVIE|SHOW}"，value: TraktSearchResult JSON */
        val mappings: Map<String, String>
    )

    @Serializable
    private data class SyncMetaPayload(
        val version: Int = 1,
        val lastFullSyncAt: Long = 0L,
        val lastSyncAt: Long = 0L,
        val lastSyncMode: String = "",
        val totalSyncedItems: Int = 0,
        val totalPendingItems: Int = 0,
        val uploadedAt: Long = System.currentTimeMillis()
    )

    // ===== 工具方法 =====

    /**
     * 获取当前 trakt username 的哈希（用于云端文件隔离）。
     * @return 哈希字符串，未登录 trakt 返回 null
     */
    private suspend fun getUserHash(): String? {
        val profile = userProfileStorage.getProfile() ?: return null
        if (profile.username.isBlank()) return null
        return AesCrypto.hashUserId(profile.username)
    }

    private fun buildPath(userHash: String, fileName: String): String =
        "$PATH_PREFIX$userHash/$fileName"

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
     * 加密并上传内容到指定路径（带乐观锁）。
     * - 文件不存在 → POST 创建
     * - 文件已存在 → GET sha → PUT 更新
     * @return true 成功，false 失败
     */
    private suspend fun uploadEncrypted(path: String, jsonStr: String, commitMsg: String): Boolean {
        return try {
            val encrypted = AesCrypto.encrypt(jsonStr)
            val base64Content = android.util.Base64.encodeToString(
                encrypted.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            // 先 GET sha
            val existingSha = try {
                val resp = giteeContentsApi.getFileContent(OWNER, REPO, path)
                if (resp.isSuccessful) parseContentResponse(resp.body())?.sha else null
            } catch (_: Exception) {
                null
            }
            val request = if (existingSha != null) {
                GiteeContentRequest(content = base64Content, message = commitMsg, sha = existingSha)
            } else {
                GiteeContentRequest(content = base64Content, message = commitMsg)
            }
            val resp = if (existingSha != null) {
                giteeContentsApi.putFileContent(OWNER, REPO, path, request)
            } else {
                giteeContentsApi.createFileContent(OWNER, REPO, path, request)
            }
            if (resp.isSuccessful) {
                Log.d(TAG, "上传成功: $path (${if (existingSha != null) "PUT" else "POST"})")
                true
            } else {
                Log.w(TAG, "上传失败: ${resp.code()} ${resp.message()} | path=$path")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "上传异常: path=$path | ${e.message}")
            false
        }
    }

    /**
     * 下载并解密云端文件。
     * @return 解密后的 JSON 字符串，文件不存在或失败返回 null
     */
    private suspend fun downloadDecrypted(path: String): String? {
        return try {
            val resp = giteeContentsApi.getFileContent(OWNER, REPO, path)
            if (!resp.isSuccessful) {
                if (resp.code() != 404) Log.w(TAG, "下载失败: ${resp.code()} | path=$path")
                return null
            }
            val body = parseContentResponse(resp.body()) ?: return null
            val base64Content = body.content ?: return null
            val encrypted = String(
                android.util.Base64.decode(base64Content, android.util.Base64.NO_WRAP),
                Charsets.UTF_8
            )
            AesCrypto.decrypt(encrypted)
        } catch (e: Exception) {
            Log.w(TAG, "下载异常: path=$path | ${e.message}")
            null
        }
    }

    // ===== 上传 API =====

    /**
     * 上传本地全部个人数据到云端（同步完成或取消时调用）。
     *
     * @param lastSyncMode 同步模式（"INCREMENTAL_WITH_CHANGES" / "FULL_REWRITE" / "CANCELLED" 等）
     * @param isFullComplete true=完整同步完成（更新 lastFullSyncAt）
     * @param uploadIdMappings 是否上传 IMDb→Trakt 映射（数据量较大，仅在完整同步完成时上传）
     * @return true=全部上传成功，false=部分或全部失败（失败不阻塞主流程）
     */
    suspend fun uploadAll(
        lastSyncMode: String,
        isFullComplete: Boolean,
        uploadIdMappings: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val userHash = getUserHash() ?: run {
            Log.d(TAG, "未登录 trakt，跳过上传")
            return@withContext false
        }

        var allSuccess = true

        // 1. 上传 synced_items
        try {
            val syncedItems = doubanSyncedItemDao.getAllSyncedItems()
            val syncedPayload = SyncedItemsPayload(
                total = syncedItems.size,
                items = syncedItems.map {
                    SyncedItemDto(
                        doubanId = it.doubanId,
                        imdbId = it.imdbId,
                        traktId = it.traktId,
                        title = it.title,
                        status = it.status,
                        rating = it.rating,
                        syncedAt = it.syncedAt,
                        mediaType = it.mediaType
                    )
                }
            )
            val syncedJson = json.encodeToString(SyncedItemsPayload.serializer(), syncedPayload)
            allSuccess = uploadEncrypted(
                buildPath(userHash, FILE_SYNCED),
                syncedJson,
                "sync synced_items: ${syncedItems.size} items"
            ) && allSuccess
        } catch (e: Exception) {
            Log.w(TAG, "上传 synced_items 异常: ${e.message}")
            allSuccess = false
        }

        // 2. 上传 pending_items（取消时可能有数据；完成时通常为空）
        try {
            val pendingItems = doubanSyncPendingItemDao.getAll()
            val pendingPayload = PendingItemsPayload(
                total = pendingItems.size,
                items = pendingItems.map {
                    PendingItemDto(
                        doubanId = it.doubanId,
                        title = it.title,
                        posterUrl = it.posterUrl,
                        rating = it.rating,
                        comment = it.comment,
                        markedAt = it.markedAt,
                        doubanUrl = it.doubanUrl,
                        status = it.status,
                        crawledAt = it.crawledAt
                    )
                }
            )
            val pendingJson = json.encodeToString(PendingItemsPayload.serializer(), pendingPayload)
            allSuccess = uploadEncrypted(
                buildPath(userHash, FILE_PENDING),
                pendingJson,
                "sync pending_items: ${pendingItems.size} items"
            ) && allSuccess
        } catch (e: Exception) {
            Log.w(TAG, "上传 pending_items 异常: ${e.message}")
            allSuccess = false
        }

        // 3. 上传 id_mappings（仅在完整同步完成时，避免频繁上传大数据）
        if (uploadIdMappings) {
            try {
                val mappings = traktRepository.snapshotImdbMappings()
                if (mappings.isNotEmpty()) {
                    // value 序列化为 JSON 字符串（保持类型信息）
                    val mappingsMap = mappings.mapValues { (_, value) ->
                        json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(TraktSearchResult.serializer()),
                            value
                        )
                    }
                    val mappingsPayload = IdMappingsPayload(
                        total = mappingsMap.size,
                        mappings = mappingsMap
                    )
                    val mappingsJson = json.encodeToString(IdMappingsPayload.serializer(), mappingsPayload)
                    allSuccess = uploadEncrypted(
                        buildPath(userHash, FILE_ID_MAPPINGS),
                        mappingsJson,
                        "sync id_mappings: ${mappingsMap.size} entries"
                    ) && allSuccess
                }
            } catch (e: Exception) {
                Log.w(TAG, "上传 id_mappings 异常: ${e.message}")
                allSuccess = false
            }
        }

        // 4. 上传 sync_meta（每次都上传，记录同步状态）
        try {
            val totalSynced = doubanSyncedItemDao.count()
            val totalPending = doubanSyncPendingItemDao.count()
            val lastFullSyncAt = doubanSyncMetaStorage.getLastFullSyncAt()
            val now = System.currentTimeMillis()
            val effectiveLastFull = if (isFullComplete) now else lastFullSyncAt

            val metaPayload = SyncMetaPayload(
                lastFullSyncAt = effectiveLastFull,
                lastSyncAt = now,
                lastSyncMode = lastSyncMode,
                totalSyncedItems = totalSynced,
                totalPendingItems = totalPending,
                uploadedAt = now
            )
            val metaJson = json.encodeToString(SyncMetaPayload.serializer(), metaPayload)
            allSuccess = uploadEncrypted(
                buildPath(userHash, FILE_META),
                metaJson,
                "sync meta: mode=$lastSyncMode synced=$totalSynced pending=$totalPending"
            ) && allSuccess
        } catch (e: Exception) {
            Log.w(TAG, "上传 sync_meta 异常: ${e.message}")
            allSuccess = false
        }

        if (allSuccess) {
            Log.d(TAG, "全部上传成功 (user=$userHash, mode=$lastSyncMode, fullComplete=$isFullComplete)")
        } else {
            Log.w(TAG, "部分上传失败 (user=$userHash, mode=$lastSyncMode)")
        }
        allSuccess
    }

    // ===== 下载 API =====

    /**
     * 检测云端是否有当前 trakt 用户的个人数据（仅查 sync_meta 是否存在）。
     * 用于决定是否触发拉取流程。
     * @return SyncMetaPayload 或 null（云端无数据或检测失败）
     */
    private suspend fun checkCloudMeta(): SyncMetaPayload? = withContext(Dispatchers.IO) {
        val userHash = getUserHash() ?: return@withContext null
        val metaJson = downloadDecrypted(buildPath(userHash, FILE_META)) ?: return@withContext null
        try {
            json.decodeFromString(SyncMetaPayload.serializer(), metaJson)
        } catch (e: Exception) {
            Log.w(TAG, "解析 sync_meta 失败: ${e.message}")
            null
        }
    }

    // 节流状态由 companion object 内的 lastMetaRefreshSuccessAt/lastMetaRefreshFailedAt 记录（避免重复声明）

    /**
     * 轻量刷新:仅拉取云端 sync_meta 并合并到本地,不下载 synced/pending/mappings。
     * 供设置页显示「冷却期」状态前调用,确保跨设备 lastFullSyncAt 准确。
     *
     * 节流:上次成功刷新后 5 秒内重复调用直接返回 true;
     * 上次失败时仅 1 秒内节流(允许快速重试,避免失败被长节流掩盖)。
     * @return true=云端 meta 拉取并合并成功;false=未登录/云端无数据/失败
     */
    suspend fun refreshMetaOnly(): Boolean = metaRefreshMutex.withLock {
        val now = System.currentTimeMillis()
        // 节流:上次成功窗口内快速返回 true;上次失败时短节流让快速重试能通过
        if (lastMetaRefreshSuccessAt > lastMetaRefreshFailedAt) {
            if (now - lastMetaRefreshSuccessAt < META_REFRESH_THROTTLE_MS) {
                return@withLock true
            }
        } else if (now - lastMetaRefreshFailedAt < META_REFRESH_RETRY_THROTTLE_MS) {
            return@withLock true
        }
        val success = doRefreshMetaOnly()
        if (success) lastMetaRefreshSuccessAt = now else lastMetaRefreshFailedAt = now
        success
    }

    private suspend fun doRefreshMetaOnly(): Boolean = withContext(Dispatchers.IO) {
        val payload = checkCloudMeta() ?: return@withContext false
        try {
            doubanSyncMetaStorage.updateFromCloud(
                cloudLastFullSyncAt = payload.lastFullSyncAt,
                cloudLastSyncAt = payload.lastSyncAt,
                cloudLastSyncMode = payload.lastSyncMode
            )
            Log.d(TAG, "refreshMetaOnly: 已合并云端 meta lastFull=${payload.lastFullSyncAt}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "refreshMetaOnly 合并失败: ${e.message}")
            false
        }
    }

    /**
     * 从云端拉取全部个人数据并合并到本地。
     *
     * 合并策略：
     * - synced_items: REPLACE（同 doubanId 覆盖）
     * - pending_items: REPLACE（同 doubanId 覆盖）
     * - id_mappings: 不覆盖本地已有（本地新数据优先）
     * - sync_meta: 取云端和本地较新值更新本地镜像
     *
     * @return PullResult 拉取结果统计
     */
    suspend fun downloadAndMerge(): PullResult = withContext(Dispatchers.IO) {
        val userHash = getUserHash() ?: return@withContext PullResult()
        var syncedCount = 0
        var pendingCount = 0
        var mappingsCount = 0
        var metaApplied = false

        // 1. 下载并合并 synced_items（按 syncedAt 比较，仅云端较新才覆盖）
        try {
            val jsonStr = downloadDecrypted(buildPath(userHash, FILE_SYNCED))
            if (jsonStr != null) {
                val payload = json.decodeFromString(SyncedItemsPayload.serializer(), jsonStr)
                if (payload.items.isNotEmpty()) {
                    val localItems = doubanSyncedItemDao.getAllSyncedItems().associateBy { it.doubanId }
                    val toInsert = payload.items.mapNotNull { dto ->
                        val local = localItems[dto.doubanId]
                        // 本地不存在 或 云端 syncedAt 更新 → 覆盖；本地较新或相等 → 保留本地
                        if (local == null || dto.syncedAt > local.syncedAt) {
                            DoubanSyncedItem(
                                doubanId = dto.doubanId,
                                imdbId = dto.imdbId,
                                traktId = dto.traktId,
                                title = dto.title,
                                status = dto.status,
                                rating = dto.rating,
                                syncedAt = dto.syncedAt,
                                mediaType = dto.mediaType
                            )
                        } else null
                    }
                    if (toInsert.isNotEmpty()) {
                        doubanSyncedItemDao.insertAll(toInsert)
                        syncedCount = toInsert.size
                        Log.d(TAG, "合并 synced_items: $syncedCount 条（云端较新），本地保留 ${localItems.size - toInsert.size} 条")
                    } else {
                        Log.d(TAG, "synced_items 本地均较新，跳过 ${payload.items.size} 条")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "下载合并 synced_items 异常: ${e.message}")
        }

        // 2. 下载并合并 pending_items（按 crawledAt 比较，仅云端较新才覆盖）
        try {
            val jsonStr = downloadDecrypted(buildPath(userHash, FILE_PENDING))
            if (jsonStr != null) {
                val payload = json.decodeFromString(PendingItemsPayload.serializer(), jsonStr)
                if (payload.items.isNotEmpty()) {
                    val localPending = doubanSyncPendingItemDao.getAll().associateBy { it.doubanId }
                    val toInsert = payload.items.mapNotNull { dto ->
                        val local = localPending[dto.doubanId]
                        if (local == null || dto.crawledAt > local.crawledAt) {
                            DoubanSyncPendingItemEntity(
                                doubanId = dto.doubanId,
                                title = dto.title,
                                posterUrl = dto.posterUrl,
                                rating = dto.rating,
                                comment = dto.comment,
                                markedAt = dto.markedAt,
                                doubanUrl = dto.doubanUrl,
                                status = dto.status,
                                crawledAt = dto.crawledAt
                            )
                        } else null
                    }
                    if (toInsert.isNotEmpty()) {
                        doubanSyncPendingItemDao.insertAll(toInsert)
                        pendingCount = toInsert.size
                        Log.d(TAG, "合并 pending_items: $pendingCount 条（云端较新）")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "下载合并 pending_items 异常: ${e.message}")
        }

        // 3. 下载并合并 id_mappings（不覆盖本地已有）
        try {
            val jsonStr = downloadDecrypted(buildPath(userHash, FILE_ID_MAPPINGS))
            if (jsonStr != null) {
                val payload = json.decodeFromString(IdMappingsPayload.serializer(), jsonStr)
                if (payload.mappings.isNotEmpty()) {
                    val mappings = payload.mappings.mapValues { (_, v) ->
                        json.decodeFromString(
                            kotlinx.serialization.builtins.ListSerializer(TraktSearchResult.serializer()),
                            v
                        )
                    }
                    mappingsCount = traktRepository.mergeImdbMappings(mappings)
                    Log.d(TAG, "合并 id_mappings: $mappingsCount 条（云端 ${payload.mappings.size} 条）")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "下载合并 id_mappings 异常: ${e.message}")
        }

        // 4. 下载并应用 sync_meta
        try {
            val jsonStr = downloadDecrypted(buildPath(userHash, FILE_META))
            if (jsonStr != null) {
                val payload = json.decodeFromString(SyncMetaPayload.serializer(), jsonStr)
                doubanSyncMetaStorage.updateFromCloud(
                    cloudLastFullSyncAt = payload.lastFullSyncAt,
                    cloudLastSyncAt = payload.lastSyncAt,
                    cloudLastSyncMode = payload.lastSyncMode
                )
                metaApplied = true
                Log.d(TAG, "应用 sync_meta: lastFull=${payload.lastFullSyncAt} mode=${payload.lastSyncMode}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "下载应用 sync_meta 异常: ${e.message}")
        }

        PullResult(
            syncedItems = syncedCount,
            pendingItems = pendingCount,
            idMappings = mappingsCount,
            metaApplied = metaApplied
        )
    }

    /** 拉取结果统计 */
    data class PullResult(
        val syncedItems: Int = 0,
        val pendingItems: Int = 0,
        val idMappings: Int = 0,
        val metaApplied: Boolean = false
    ) {
        val hasAnyData: Boolean get() = syncedItems > 0 || pendingItems > 0 || idMappings > 0 || metaApplied
    }
}
