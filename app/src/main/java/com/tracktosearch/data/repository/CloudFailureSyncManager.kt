package com.tracktosearch.data.repository

import android.util.Log
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.remote.cloud.AesCrypto
import com.tracktosearch.data.remote.cloud.GiteeContentRequest
import com.tracktosearch.data.remote.cloud.GiteeContentResponse
import com.tracktosearch.data.remote.cloud.GiteeContentsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 豆瓣失败项云端同步管理器。
 *
 * - 上传:同步完成后调用,把本地失败项加密后上传到 Gitee 仓库
 *   `failures/{SHA256(doubanUserId)前16位}.json`
 * - 下载:豆瓣登录后调用,检测云端是否有该用户的失败数据,
 *   有则返回数量供 UI 弹窗提示,用户同意后下载并合并到本地 Room
 *
 * 数据流:
 *   失败项列表 → JSON 序列化 → AES-256-CBC 加密 → base64 → Gitee Contents API PUT
 *   下载:GET → base64 解码 → AES 解密 → JSON 反序列化 → Room REPLACE 合并
 *
 * 失败处理:上传/下载失败只记录日志,不阻塞主流程(云端同步是辅助功能)。
 */
@Singleton
class CloudFailureSyncManager @Inject constructor(
    private val giteeContentsApi: GiteeContentsApi,
    private val doubanSyncFailureDao: DoubanSyncFailureDao,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val json: Json
) {

    companion object {
        private const val TAG = "CloudFailureSync"
        private const val OWNER = "yufeng-liang"
        private const val REPO = "TrackToSearch"
        private const val PATH_PREFIX = "failures/"
        private const val FILE_SUFFIX = ".json"
    }

    @Serializable
    private data class CloudPayload(
        val version: Int = 1,
        /** 云端数据上传时间戳,用于和本地最新 failedAt 比较判断新旧(0=旧版数据无此字段) */
        val uploadedAt: Long = 0L,
        val totalFailures: Int,
        val failures: List<CloudFailureDto>
    )

    @Serializable
    private data class CloudFailureDto(
        val doubanId: String,
        val title: String,
        val posterUrl: String?,
        val rating: Int?,
        val comment: String?,
        val markedAt: String,
        val doubanUrl: String,
        val status: String,
        val failureReason: String,
        val failedAt: Long,
        val updatedAt: Long = 0L,
        val attemptCount: Int,
        val mediaType: String? = null,
        val subtitle: String? = null
    )

    private fun buildPath(doubanUserId: String): String {
        val hash = AesCrypto.hashUserId(doubanUserId)
        return "$PATH_PREFIX$hash$FILE_SUFFIX"
    }

    /**
     * 从 GET 响应中安全提取文件信息。
     *
     * Gitee Contents API 在文件不存在时可能返回:
     * - 404(标准情况)
     * - 200 + `[]`(空数组,非标准但实际存在)
     *
     * 本方法把 JsonElement 解析为 [GiteeContentResponse]:
     * - JsonObject → 正常解析 content 和 sha
     * - JsonArray → 视为文件不存在,返回 null
     */
    private fun parseContentResponse(element: kotlinx.serialization.json.JsonElement?): GiteeContentResponse? {
        if (element == null) return null
        if (element !is JsonObject) return null  // JsonArray(`[]`)或其他类型 → 文件不存在
        return try {
            json.decodeFromJsonElement(GiteeContentResponse.serializer(), element)
        } catch (e: Exception) {
            Log.w(TAG, "解析 GiteeContentResponse 失败: ${e.message}")
            null
        }
    }

    /**
     * 检查当前是否已登录豆瓣账号(用于设置页手动上传/下载按钮前置校验)。
     */
    suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        doubanAuthStorage.getCredentials() != null
    }

    /**
     * 上传本地失败项到云端(同步完成后调用)。
     * - 无失败项时:跳过上传(保留云端历史,避免误删)
     * - 有失败项时:先比较本地最新 failedAt 和云端 uploadedAt,
     *   本地不比云端新时跳过上传,新于云端时才加密上传
     *
     * @return true 成功,false 失败(失败只记日志,不阻塞主流程)
     */
    suspend fun uploadIfHasFailures(): Boolean = withContext(Dispatchers.IO) {
        val creds = doubanAuthStorage.getCredentials() ?: run {
            Log.d(TAG, "未登录豆瓣,跳过上传")
            return@withContext false
        }
        val path = buildPath(creds.userId)
        val entities = doubanSyncFailureDao.getAll()

        try {
            if (entities.isEmpty()) {
                // 本地无失败项,不主动删除云端(保留云端历史,避免误删)
                Log.d(TAG, "本地无失败项,跳过上传")
                return@withContext true
            }

            // 先 GET 云端文件,同时获取 sha(更新必传)和 uploadedAt(时间戳比较)
            // 注意:文件不存在时 Gitee 可能返回 200 + `[]`,parseContentResponse 会返回 null
            var existingSha: String? = null
            var cloudUploadedAt: Long = 0L
            try {
                val resp = giteeContentsApi.getFileContent(OWNER, REPO, path)
                if (resp.isSuccessful) {
                    val body = parseContentResponse(resp.body())
                    if (body != null) {
                        existingSha = body.sha
                        // 尝试解密并解析 payload 获取 uploadedAt
                        val base64Content = body.content
                        if (base64Content != null) {
                            val encrypted = String(
                                android.util.Base64.decode(base64Content, android.util.Base64.NO_WRAP),
                                Charsets.UTF_8
                            )
                            val jsonStr = AesCrypto.decrypt(encrypted)
                            if (jsonStr != null) {
                                val cloudPayload = json.decodeFromString(CloudPayload.serializer(), jsonStr)
                                cloudUploadedAt = cloudPayload.uploadedAt
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // GET 失败不阻塞上传(无法比较时间戳时保守上传)
                Log.d(TAG, "GET 云端失败,跳过时间戳比较: ${e.message}")
            }

            // 时间戳比较:本地最新 max(failedAt, updatedAt) vs 云端 uploadedAt
            // cloudUploadedAt > 0 才比较(0=旧版数据或云端不存在,无法比较时保守上传)
            // 使用 max(failedAt, updatedAt) 确保用户标注 mediaType/subtitle 后(只更新 updatedAt)也能触发上传
            val localNewest = entities.maxOfOrNull { maxOf(it.failedAt, it.updatedAt) } ?: 0L
            if (cloudUploadedAt > 0L && localNewest <= cloudUploadedAt) {
                Log.d(TAG, "本地数据不比云端新(localNewest=$localNewest, cloudUploadedAt=$cloudUploadedAt),跳过上传")
                return@withContext true
            }

            val payload = CloudPayload(
                totalFailures = entities.size,
                uploadedAt = System.currentTimeMillis(),
                failures = entities.map { e ->
                    CloudFailureDto(
                        doubanId = e.doubanId,
                        title = e.title,
                        posterUrl = e.posterUrl,
                        rating = e.rating,
                        comment = e.comment,
                        markedAt = e.markedAt,
                        doubanUrl = e.doubanUrl,
                        status = e.status,
                        failureReason = e.failureReason,
                        failedAt = e.failedAt,
                        updatedAt = e.updatedAt,
                        attemptCount = e.attemptCount,
                        mediaType = e.mediaType,
                        subtitle = e.subtitle
                    )
                }
            )
            val jsonStr = json.encodeToString(CloudPayload.serializer(), payload)
            val encrypted = AesCrypto.encrypt(jsonStr)
            val base64Content = android.util.Base64.encodeToString(
                encrypted.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )

            // Gitee API 区分新建(POST)和更新(PUT):
            // - 文件不存在(sha=null) → POST 创建新文件,不传 sha
            // - 文件已存在(sha≠null) → PUT 更新文件,必传 sha
            // 用 PUT 创建新文件会报 "sha is missing","sha is empty"
            val request = if (existingSha != null) {
                GiteeContentRequest(
                    content = base64Content,
                    message = "sync failures: ${entities.size} items",
                    sha = existingSha
                )
            } else {
                GiteeContentRequest(
                    content = base64Content,
                    message = "sync failures: ${entities.size} items"
                )
            }
            val putResp = if (existingSha != null) {
                giteeContentsApi.putFileContent(OWNER, REPO, path, request)
            } else {
                giteeContentsApi.createFileContent(OWNER, REPO, path, request)
            }
            if (putResp.isSuccessful) {
                Log.d(TAG, "上传成功: ${entities.size} 条失败项 → $path (${if (existingSha != null) "PUT 更新" else "POST 新建"}, uploadedAt=${payload.uploadedAt})")
                true
            } else {
                val errorBody = putResp.errorBody()?.string()
                Log.w(TAG, "上传失败: ${putResp.code()} ${putResp.message()} | path=$path | error=$errorBody")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "上传异常: ${e.message}")
            false
        }
    }

    /**
     * 检测云端是否有当前豆瓣用户的失败数据。
     * 用于豆瓣登录后自动检测。
     *
     * @return 云端失败项数量(null 表示无数据或检测失败,>0 表示云端有数据)
     */
    suspend fun checkCloudFailures(): Int? = withContext(Dispatchers.IO) {
        val creds = doubanAuthStorage.getCredentials() ?: return@withContext null
        val path = buildPath(creds.userId)

        try {
            val resp = giteeContentsApi.getFileContent(OWNER, REPO, path)
            if (!resp.isSuccessful) {
                if (resp.code() != 404) {
                    Log.w(TAG, "检测云端失败: ${resp.code()}")
                }
                return@withContext null
            }
            val body = parseContentResponse(resp.body()) ?: return@withContext null
            val base64Content = body.content ?: return@withContext null

            val encrypted = String(
                android.util.Base64.decode(base64Content, android.util.Base64.NO_WRAP),
                Charsets.UTF_8
            )
            val jsonStr = AesCrypto.decrypt(encrypted) ?: run {
                Log.w(TAG, "云端数据解密失败")
                return@withContext null
            }
            val payload = json.decodeFromString(CloudPayload.serializer(), jsonStr)
            payload.totalFailures
        } catch (e: Exception) {
            Log.w(TAG, "检测云端异常: ${e.message}")
            null
        }
    }

    /**
     * 下载云端失败数据并替换全部本地失败项。
     *
     * 时间戳比较逻辑:
     * - 云端 uploadedAt > 本地最新 failedAt → 云端更新,下载并 clearAll+insertAll 替换全部
     * - 本地有数据且云端 uploadedAt <= 本地 failedAt → 本地更新,忽略云端
     * - 本地无数据(failedAt=0)且云端有失败项 → 无论 uploadedAt 多少都下载替换
     *
     * 替换后「查看同步失败项」数量刷新:
     * - 同步流程中调用([DoubanSyncManager.pullFromCloudBeforeSync])→ 同步完成后 [DoubanRetryManager] 自动刷新
     * - 手动拉取(设置页/登录页)→ 调用方需在 [DownloadResult.Success] 时调用 [DoubanRetryManager.refreshRetryState]
     *
     * @return [DownloadResult] 语义化返回值,调用方根据类型做 UI 反馈和刷新
     */
    suspend fun downloadAndMerge(): DownloadResult = withContext(Dispatchers.IO) {
        val creds = doubanAuthStorage.getCredentials() ?: return@withContext DownloadResult.Failed
        val path = buildPath(creds.userId)

        try {
            val resp = giteeContentsApi.getFileContent(OWNER, REPO, path)
            if (!resp.isSuccessful) return@withContext DownloadResult.Failed
            val body = parseContentResponse(resp.body()) ?: return@withContext DownloadResult.Failed
            val base64Content = body.content ?: return@withContext DownloadResult.Failed

            val encrypted = String(
                android.util.Base64.decode(base64Content, android.util.Base64.NO_WRAP),
                Charsets.UTF_8
            )
            val jsonStr = AesCrypto.decrypt(encrypted) ?: return@withContext DownloadResult.Failed
            val payload = json.decodeFromString(CloudPayload.serializer(), jsonStr)

            if (payload.failures.isEmpty()) return@withContext DownloadResult.CloudEmpty

            // 时间戳比较:云端 uploadedAt vs 本地最新 max(failedAt, updatedAt)
            val localEntities = doubanSyncFailureDao.getAll()
            val localNewest = localEntities.maxOfOrNull { maxOf(it.failedAt, it.updatedAt) } ?: 0L

            if (localNewest > 0L && payload.uploadedAt <= localNewest) {
                // 本地有数据且本地更新或相等 → 忽略云端,以本地显示为准
                Log.d(TAG, "本地数据更新(localNewest=$localNewest, cloudUploadedAt=${payload.uploadedAt}),忽略云端")
                return@withContext DownloadResult.LocalNewer
            }

            // 本地无数据 或 云端更新 → 替换全部本地失败数据(事务包装)
            val entities = payload.failures.map { dto ->
                com.tracktosearch.data.local.db.DoubanSyncFailureEntity(
                    doubanId = dto.doubanId,
                    title = dto.title,
                    posterUrl = dto.posterUrl,
                    rating = dto.rating,
                    comment = dto.comment,
                    markedAt = dto.markedAt,
                    doubanUrl = dto.doubanUrl,
                    status = dto.status,
                    failureReason = dto.failureReason,
                    failedAt = dto.failedAt,
                    updatedAt = dto.updatedAt,
                    attemptCount = dto.attemptCount,
                    mediaType = dto.mediaType,
                    subtitle = dto.subtitle
                )
            }
            doubanSyncFailureDao.replaceAll(entities)
            Log.d(TAG, "云端数据替换本地成功: ${entities.size} 条 (cloudUploadedAt=${payload.uploadedAt}, localNewest=$localNewest)")
            DownloadResult.Success(entities.size)
        } catch (e: Exception) {
            Log.w(TAG, "下载合并异常: ${e.message}")
            DownloadResult.Failed
        }
    }
}

/**
 * 云端失败数据下载结果(语义化返回值)。
 *
 * - [CloudEmpty] 云端无失败数据
 * - [LocalNewer] 本地数据更新,跳过云端(本地数据未变更,无需刷新 UI)
 * - [Success] 云端更新,已替换全部本地失败数据(调用方需刷新 retryState)
 * - [Failed] 下载失败(网络/解密/服务端错误)
 */
sealed class DownloadResult {
    /** 云端无失败数据 */
    object CloudEmpty : DownloadResult()

    /** 本地数据更新,跳过云端(本地数据未变更) */
    object LocalNewer : DownloadResult()

    /** 云端更新,已替换全部本地失败数据(调用方需刷新 retryState) */
    data class Success(val count: Int) : DownloadResult()

    /** 下载失败 */
    object Failed : DownloadResult()
}
