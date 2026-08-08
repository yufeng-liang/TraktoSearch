package com.tracktosearch.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 豆瓣同步失败项 JSON 导出/导入工具。
 *
 * - 导出:从 [DoubanSyncFailureDao] 读取全部失败项,序列化为 JSON,写到 cacheDir,
 *   通过 FileProvider 生成可分享的 content URI,触发 ACTION_SEND 分享面板。
 * - 导入:从用户选择的 content URI 读取 JSON,反序列化为 [List<DoubanSyncFailure>],
 *   交给 [DoubanSyncManager.startRetry] 走重试流程。
 *
 * JSON 格式版本化(version 字段),未来格式变更时升级 version 即可向后兼容。
 */

/**
 * 失败项 JSON 导入结果。
 * - [Success] 导入成功,返回导入条目数
 * - [InvalidFormat] JSON 格式不符(反序列化失败或非预期结构)
 * - [Empty] 文件为空或失败项列表为空
 * - [Error] 其他异常
 */
sealed class ImportResult {
    data class Success(val count: Int) : ImportResult()
    object InvalidFormat : ImportResult()
    object Empty : ImportResult()
    data class Error(val message: String) : ImportResult()
}

@Singleton
class DoubanFailureExporter @Inject constructor(
    private val doubanSyncFailureDao: DoubanSyncFailureDao,
    private val json: Json
) {

    @Serializable
    private data class FailureDto(
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
        val attemptCount: Int,
        // 新增字段(默认 null 向后兼容旧版导出的 JSON)
        val mediaType: String? = null,
        val subtitle: String? = null
    )

    @Serializable
    private data class ExportPayload(
        val version: Int = 1,
        val exportedAt: String,
        val source: String = "TraktoSearch",
        val totalFailures: Int,
        val failures: List<FailureDto>
    )

    /**
     * 导出失败项到 JSON 文件,返回用于分享的 Intent。
     *
     * @param context 用于访问 cacheDir 和 FileProvider
     * @return 分享 Intent,调用方 startActivity(Intent.createChooser(it, ...))
     */
    suspend fun exportToFile(context: Context): Uri? = withContext(Dispatchers.IO) {
        val entities = doubanSyncFailureDao.getAll()
        if (entities.isEmpty()) return@withContext null

        val payload = ExportPayload(
            exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
            totalFailures = entities.size,
            failures = entities.map { e ->
                FailureDto(
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
                    attemptCount = e.attemptCount,
                    mediaType = e.mediaType,
                    subtitle = e.subtitle
                )
            }
        )
        val jsonStr = json.encodeToString(ExportPayload.serializer(), payload)

        // 写到 cacheDir/share 子目录,通过 FileProvider 暴露
        val shareDir = File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val file = File(shareDir, "TraktoSearch-失败项-$dateStr.json")
        file.writeText(jsonStr, Charsets.UTF_8)

        // 通过 FileProvider 生成 content URI
        val authority = "${context.packageName}.fileprovider"
        return@withContext FileProvider.getUriForFile(context, authority, file)
    }

    /**
     * 从用户选择的 URI 导入失败项 JSON,解析为 [List<DoubanSyncFailure>]。
     *
     * @param context 用于打开 InputStream
     * @param uri 用户通过 ACTION_OPEN_DOCUMENT 选择的文件 URI
     * @return 解析后的失败项列表;失败时返回 null
     */
    suspend fun importFromFile(context: Context, uri: Uri): List<DoubanSyncFailure>? =
        withContext(Dispatchers.IO) {
            try {
                val jsonStr = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                } ?: return@withContext null

                val payload = json.decodeFromString(ExportPayload.serializer(), jsonStr)
                payload.failures.map { dto ->
                    DoubanSyncFailure(
                        doubanId = dto.doubanId,
                        title = dto.title,
                        posterUrl = dto.posterUrl,
                        rating = dto.rating,
                        comment = dto.comment,
                        markedAt = dto.markedAt,
                        doubanUrl = dto.doubanUrl,
                        status = com.tracktosearch.data.remote.douban.DoubanMarkStatus.fromString(dto.status),
                        failureReason = FailureReason.fromString(dto.failureReason),
                        failedAt = dto.failedAt,
                        attemptCount = dto.attemptCount,
                        mediaType = dto.mediaType,
                        subtitle = dto.subtitle
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /**
     * 从内存中的失败项列表导出(用于同步完成后立即导出,不依赖 DAO)。
     */
    suspend fun exportFromList(
        context: Context,
        failures: List<DoubanSyncFailure>
    ): Uri? = withContext(Dispatchers.IO) {
        if (failures.isEmpty()) return@withContext null

        val payload = ExportPayload(
            exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
            totalFailures = failures.size,
            failures = failures.map { f ->
                FailureDto(
                    doubanId = f.doubanId,
                    title = f.title,
                    posterUrl = f.posterUrl,
                    rating = f.rating,
                    comment = f.comment,
                    markedAt = f.markedAt,
                    doubanUrl = f.doubanUrl,
                    status = f.status.path,
                    failureReason = f.failureReason.name,
                    failedAt = f.failedAt,
                    attemptCount = f.attemptCount,
                    mediaType = f.mediaType,
                    subtitle = f.subtitle
                )
            }
        )
        val jsonStr = json.encodeToString(ExportPayload.serializer(), payload)

        val shareDir = File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val file = File(shareDir, "TraktoSearch-失败项-$dateStr.json")
        file.writeText(jsonStr, Charsets.UTF_8)

        val authority = "${context.packageName}.fileprovider"
        FileProvider.getUriForFile(context, authority, file)
    }

    /**
     * 从 Room 数据库读取所有失败项并导出 JSON(重试弹窗中导出按钮调用)。
     * 与 [exportToFile] 实现一致,但语义明确:从本地 Room 读取,而非从内存列表。
     * 返回 Uri 后调用方触发 ShareSheet。
     */
    suspend fun exportFromLocal(context: Context): Uri? = withContext(Dispatchers.IO) {
        val entities = doubanSyncFailureDao.getAll()
        if (entities.isEmpty()) return@withContext null

        val payload = ExportPayload(
            exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
            totalFailures = entities.size,
            failures = entities.map { e ->
                FailureDto(
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
                    attemptCount = e.attemptCount,
                    mediaType = e.mediaType,
                    subtitle = e.subtitle
                )
            }
        )
        val jsonStr = json.encodeToString(ExportPayload.serializer(), payload)

        val shareDir = File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val file = File(shareDir, "TraktoSearch-失败项-$dateStr.json")
        file.writeText(jsonStr, Charsets.UTF_8)

        val authority = "${context.packageName}.fileprovider"
        FileProvider.getUriForFile(context, authority, file)
    }

    /**
     * 从用户选择的 URI 导入失败项 JSON,解析后写入 Room(REPLACE 策略合并)。
     *
     * 用于跨设备查看场景:A 手机导出失败数据 → B 手机导入 → 在查看页显示。
     * 同 doubanId 覆盖,不删除本地已有的、云端没有的项(合并而非覆盖)。
     *
     * @return [ImportResult]:
     * - [ImportResult.InvalidFormat] JSON 格式不符或读取失败
     * - [ImportResult.Empty] 失败项列表为空
     * - [ImportResult.Success] 导入成功,携带条目数
     * - [ImportResult.Error] 其他异常
     */
    suspend fun importToRoom(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val failures = importFromFile(context, uri)
                ?: return@withContext ImportResult.InvalidFormat
            if (failures.isEmpty()) return@withContext ImportResult.Empty
            val entities = failures.map { it.toEntity() }
            doubanSyncFailureDao.insertAll(entities)
            ImportResult.Success(entities.size)
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }
}
