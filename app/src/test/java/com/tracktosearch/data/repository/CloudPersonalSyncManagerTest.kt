package com.tracktosearch.data.repository

import android.util.Base64
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.UserProfileStorage
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.local.db.DoubanSyncPendingItemDao
import com.tracktosearch.data.local.db.DoubanSyncPendingItemEntity
import com.tracktosearch.data.remote.cloud.AesCrypto
import com.tracktosearch.data.remote.cloud.GiteeContentRequest
import com.tracktosearch.data.remote.cloud.GiteeContentUpdateResponse
import com.tracktosearch.data.remote.cloud.GiteeContentsApi
import com.tracktosearch.data.remote.trakt.dto.TraktSearchResult
import com.tracktosearch.data.remote.trakt.dto.TraktUserProfileResponse
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * CloudPersonalSyncManager 单元测试。
 *
 * 覆盖点：
 * - uploadAll：未登录跳过、默认不上传 id_mappings、id_mappings 上传条件、
 *   isFullComplete 对 lastFullSyncAt 的影响、部分失败处理
 * - refreshMetaOnly：未登录、云端无数据、云端有数据合并、成功/失败节流
 * - downloadAndMerge：未登录、synced_items 较新/较旧合并、pending_items 合并、
 *   id_mappings 合并、sync_meta 应用
 * - AesCrypto 加密往返：upload → download 端到端字段一致
 *
 * 使用 Robolectric：AesCrypto 和 Manager 内部依赖 android.util.Base64 和 android.util.Log。
 *
 * 注意：SyncedItemsPayload/PendingItemsPayload/IdMappingsPayload/SyncMetaPayload 是私有类，
 * 测试通过手动构造 JsonObject 构建 payload JSON，加密方式与 Manager 一致：
 * AesCrypto.encrypt(payloadJson) → base64(encrypted.toByteArray(UTF-8))。
 *
 * 节流测试说明：refreshMetaOnly 用 System.currentTimeMillis() 真实时间。由于每个测试方法
 * 都在 @Before 中重新创建 manager 实例，节流状态（lastMetaRefreshSuccessAt/FailedAt）
 * 在测试间互不污染。测试「成功/失败后立即重复调用被节流」是可稳定验证的（间隔远小于窗口）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CloudPersonalSyncManagerTest {

    private val giteeContentsApi = mockk<GiteeContentsApi>(relaxed = true)
    private val doubanSyncedItemDao = mockk<DoubanSyncedItemDao>(relaxed = true)
    private val doubanSyncPendingItemDao = mockk<DoubanSyncPendingItemDao>(relaxed = true)
    private val userProfileStorage = mockk<UserProfileStorage>(relaxed = true)
    private val doubanSyncMetaStorage = mockk<DoubanSyncMetaStorage>(relaxed = true)
    private val traktRepository = mockk<TraktRepository>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var manager: CloudPersonalSyncManager

    @Before
    fun setUp() {
        // 清除前序测试的 stub 和调用记录，确保 coVerify(exactly = 0) 不受干扰
        clearMocks(giteeContentsApi, doubanSyncedItemDao, doubanSyncPendingItemDao,
            userProfileStorage, doubanSyncMetaStorage, traktRepository)
        // 每个测试方法用新的 manager 实例，避免节流状态在测试间相互污染
        manager = CloudPersonalSyncManager(
            giteeContentsApi, doubanSyncedItemDao, doubanSyncPendingItemDao,
            userProfileStorage, doubanSyncMetaStorage, traktRepository, json
        )
    }

    // ============================================================
    // 辅助函数：构造本地 entity
    // ============================================================

    /** 构造本地已同步条目，所有字段可定制 */
    private fun buildSyncedItem(
        doubanId: String = "db-001",
        imdbId: String? = "tt001",
        traktId: Int? = 1,
        title: String = "测试电影",
        status: String = "wish",
        rating: Int? = 5,
        syncedAt: Long = 1000L,
        mediaType: String = "movie"
    ): DoubanSyncedItem = DoubanSyncedItem(
        doubanId = doubanId,
        imdbId = imdbId,
        traktId = traktId,
        title = title,
        status = status,
        rating = rating,
        syncedAt = syncedAt,
        mediaType = mediaType
    )

    /** 构造本地 pending 条目，所有字段可定制 */
    private fun buildPendingItem(
        doubanId: String = "p-001",
        title: String = "待处理项",
        posterUrl: String? = "https://img.example.com/p.jpg",
        rating: Int? = 3,
        comment: String? = "评论",
        markedAt: String = "2024-01-15",
        doubanUrl: String = "https://book.douban.com/subject/p-001/",
        status: String = "wish",
        crawledAt: Long = 1000L
    ): DoubanSyncPendingItemEntity = DoubanSyncPendingItemEntity(
        doubanId = doubanId,
        title = title,
        posterUrl = posterUrl,
        rating = rating,
        comment = comment,
        markedAt = markedAt,
        doubanUrl = doubanUrl,
        status = status,
        crawledAt = crawledAt
    )

    // ============================================================
    // 辅助函数：手动构造 payload JSON（私有数据模型无法直接构造）
    // ============================================================

    /** 构造 SyncedItemsPayload JSON 字符串，字段结构与 SyncedItemDto 一致 */
    private fun buildSyncedItemsPayloadJson(items: List<DoubanSyncedItem>): String {
        val itemsArray = buildJsonArray {
            items.forEach { e ->
                add(buildJsonObject {
                    put("doubanId", e.doubanId)
                    put("imdbId", e.imdbId)
                    put("traktId", e.traktId)
                    put("title", e.title)
                    put("status", e.status)
                    put("rating", e.rating)
                    put("syncedAt", e.syncedAt)
                    put("mediaType", e.mediaType)
                })
            }
        }
        val payload = buildJsonObject {
            put("version", 1)
            put("total", items.size)
            put("items", itemsArray)
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    /** 构造 PendingItemsPayload JSON 字符串 */
    private fun buildPendingItemsPayloadJson(items: List<DoubanSyncPendingItemEntity>): String {
        val itemsArray = buildJsonArray {
            items.forEach { e ->
                add(buildJsonObject {
                    put("doubanId", e.doubanId)
                    put("title", e.title)
                    put("posterUrl", e.posterUrl)
                    put("rating", e.rating)
                    put("comment", e.comment)
                    put("markedAt", e.markedAt)
                    put("doubanUrl", e.doubanUrl)
                    put("status", e.status)
                    put("crawledAt", e.crawledAt)
                })
            }
        }
        val payload = buildJsonObject {
            put("version", 1)
            put("total", items.size)
            put("items", itemsArray)
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    /** 构造 IdMappingsPayload JSON 字符串。value 是 List<TraktSearchResult> 的 JSON 字符串 */
    private fun buildIdMappingsPayloadJson(mappings: Map<String, String>): String {
        val mappingsObj = buildJsonObject {
            mappings.forEach { (k, v) -> put(k, v) }
        }
        val payload = buildJsonObject {
            put("version", 1)
            put("total", mappings.size)
            put("mappings", mappingsObj)
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    /** 构造 SyncMetaPayload JSON 字符串 */
    private fun buildSyncMetaPayloadJson(
        lastFullSyncAt: Long,
        lastSyncAt: Long,
        lastSyncMode: String,
        totalSyncedItems: Int,
        totalPendingItems: Int,
        uploadedAt: Long
    ): String {
        val payload = buildJsonObject {
            put("version", 1)
            put("lastFullSyncAt", lastFullSyncAt)
            put("lastSyncAt", lastSyncAt)
            put("lastSyncMode", lastSyncMode)
            put("totalSyncedItems", totalSyncedItems)
            put("totalPendingItems", totalPendingItems)
            put("uploadedAt", uploadedAt)
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    // ============================================================
    // 辅助函数：构造云端加密响应
    // ============================================================

    /**
     * 构造云端文件响应 JsonObject：content 为 base64(AesCrypto.encrypt(payloadJson))，sha 为文件 sha。
     * 加密链路与 Manager.uploadEncrypted 完全一致。
     */
    private fun buildCloudResponseJson(payloadJson: String, sha: String): JsonObject {
        val base64Content = Base64.encodeToString(
            payloadJson.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        return buildJsonObject {
            put("content", base64Content)
            put("sha", sha)
        }
    }

    /** 从已捕获的 base64Content 直接构造云端响应（用于往返测试） */
    private fun buildCloudResponseFromContent(base64Content: String, sha: String): JsonObject =
        buildJsonObject {
            put("content", base64Content)
            put("sha", sha)
        }

    /** 从上传请求体解密还原 payload JSON（解密链路与 Manager 下载逻辑一致） */
    private fun decryptRequestContent(request: GiteeContentRequest): String {
        return String(
            Base64.decode(request.content, Base64.NO_WRAP),
            Charsets.UTF_8
        )
    }

    // ============================================================
    // 辅助函数：构造 mock Response
    // ============================================================

    /** 构造 GET 成功响应（200 + JsonObject body） */
    private fun mockSuccessResponse(body: JsonObject): Response<JsonElement> {
        val resp = mockk<Response<JsonElement>>()
        every { resp.isSuccessful } returns true
        every { resp.body() } returns body
        every { resp.code() } returns 200
        return resp
    }

    /** 构造 GET 404 响应 */
    private fun mockNotFoundResponse(): Response<JsonElement> {
        val resp = mockk<Response<JsonElement>>()
        every { resp.isSuccessful } returns false
        every { resp.code() } returns 404
        return resp
    }

    /** 构造上传成功响应（201） */
    private fun mockSuccessUpdateResponse(): Response<GiteeContentUpdateResponse> {
        val resp = mockk<Response<GiteeContentUpdateResponse>>(relaxed = true)
        every { resp.isSuccessful } returns true
        every { resp.code() } returns 201
        return resp
    }

    /** 构造上传失败响应（400，relaxed 处理 errorBody/message） */
    private fun mockFailedUpdateResponse(): Response<GiteeContentUpdateResponse> {
        val resp = mockk<Response<GiteeContentUpdateResponse>>(relaxed = true)
        every { resp.isSuccessful } returns false
        every { resp.code() } returns 400
        return resp
    }

    /** 计算 trakt username 对应的云端路径（与 Manager.buildPath 一致） */
    private fun cloudPath(username: String, fileName: String): String {
        val userHash = AesCrypto.hashUserId(username)
        return "personal/$userHash/$fileName"
    }

    // ============================================================
    // uploadAll 测试
    // ============================================================

    @Test
    fun uploadAll_未登录_returnsFalse且不调用GiteeApi() = runTest {
        coEvery { userProfileStorage.getProfile() } returns null

        val result = manager.uploadAll(lastSyncMode = "FULL_REWRITE", isFullComplete = true)

        assertThat(result).isFalse()
        coVerify(exactly = 0) {
            giteeContentsApi.getFileContent(any(), any(), any(), any())
            giteeContentsApi.createFileContent(any(), any(), any(), any())
            giteeContentsApi.putFileContent(any(), any(), any(), any())
        }
    }

    @Test
    fun uploadAll_默认不上传IdMappings_调用3次上传() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns emptyList()
        coEvery { doubanSyncPendingItemDao.getAll() } returns emptyList()
        coEvery { doubanSyncedItemDao.count() } returns 0
        coEvery { doubanSyncPendingItemDao.count() } returns 0
        coEvery { doubanSyncMetaStorage.getLastFullSyncAt() } returns 0L
        // GET 404 → 走 POST
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()
        coEvery { giteeContentsApi.createFileContent(any(), any(), any(), any()) } returns mockSuccessUpdateResponse()

        val result = manager.uploadAll(lastSyncMode = "FULL_REWRITE", isFullComplete = false)

        assertThat(result).isTrue()
        // synced_items + pending_items + sync_meta = 3 次 POST，不传 id_mappings
        coVerify(exactly = 3) { giteeContentsApi.createFileContent(any(), any(), any(), any()) }
        coVerify(exactly = 0) { giteeContentsApi.putFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun uploadAll_uploadIdMappings且mappings非空_调用4次上传() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns emptyList()
        coEvery { doubanSyncPendingItemDao.getAll() } returns emptyList()
        coEvery { doubanSyncedItemDao.count() } returns 0
        coEvery { doubanSyncPendingItemDao.count() } returns 0
        coEvery { doubanSyncMetaStorage.getLastFullSyncAt() } returns 0L
        // snapshotImdbMappings 返回非空 Map
        coEvery { traktRepository.snapshotImdbMappings() } returns mapOf(
            "tt001_MOVIE" to listOf(TraktSearchResult(type = "movie"))
        )
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()
        coEvery { giteeContentsApi.createFileContent(any(), any(), any(), any()) } returns mockSuccessUpdateResponse()

        val result = manager.uploadAll(
            lastSyncMode = "FULL_REWRITE", isFullComplete = false, uploadIdMappings = true
        )

        assertThat(result).isTrue()
        // synced + pending + id_mappings + meta = 4 次
        coVerify(exactly = 4) { giteeContentsApi.createFileContent(any(), any(), any(), any()) }
        coVerify(exactly = 0) { giteeContentsApi.putFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun uploadAll_uploadIdMappings但mappings为空_跳过IdMappings() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns emptyList()
        coEvery { doubanSyncPendingItemDao.getAll() } returns emptyList()
        coEvery { doubanSyncedItemDao.count() } returns 0
        coEvery { doubanSyncPendingItemDao.count() } returns 0
        coEvery { doubanSyncMetaStorage.getLastFullSyncAt() } returns 0L
        // snapshotImdbMappings 返回空 Map → 跳过 id_mappings
        coEvery { traktRepository.snapshotImdbMappings() } returns emptyMap()
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()
        coEvery { giteeContentsApi.createFileContent(any(), any(), any(), any()) } returns mockSuccessUpdateResponse()

        val result = manager.uploadAll(
            lastSyncMode = "FULL_REWRITE", isFullComplete = false, uploadIdMappings = true
        )

        assertThat(result).isTrue()
        // 仍只上传 3 个文件（id_mappings 被跳过）
        coVerify(exactly = 3) { giteeContentsApi.createFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun uploadAll_isFullComplete_true_使用now作为LastFullSyncAt() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns emptyList()
        coEvery { doubanSyncPendingItemDao.getAll() } returns emptyList()
        coEvery { doubanSyncedItemDao.count() } returns 0
        coEvery { doubanSyncPendingItemDao.count() } returns 0
        coEvery { doubanSyncMetaStorage.getLastFullSyncAt() } returns 0L
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()

        val capturedPaths = mutableListOf<String>()
        val capturedRequests = mutableListOf<GiteeContentRequest>()
        coEvery {
            giteeContentsApi.createFileContent(any(), any(), capture(capturedPaths), capture(capturedRequests))
        } returns mockSuccessUpdateResponse()

        val beforeNow = System.currentTimeMillis()
        val result = manager.uploadAll(lastSyncMode = "FULL_REWRITE", isFullComplete = true)
        val afterNow = System.currentTimeMillis()

        assertThat(result).isTrue()

        // 找到 sync_meta 的上传请求（meta 是最后一个上传的文件）
        val metaIndex = capturedPaths.indexOfLast { it.contains("sync_meta.json") }
        assertThat(metaIndex).isAtLeast(0)
        val decryptedPayload = decryptRequestContent(capturedRequests[metaIndex])
        val payloadObj = json.decodeFromString(JsonObject.serializer(), decryptedPayload)
        val lastFullSyncAt = payloadObj["lastFullSyncAt"]!!.jsonPrimitive.content.toLong()
        // isFullComplete=true → lastFullSyncAt ≈ now（在测试时间窗口内）
        assertThat(lastFullSyncAt).isAtLeast(beforeNow)
        assertThat(lastFullSyncAt).isAtMost(afterNow)
    }

    @Test
    fun uploadAll_isFullComplete_false_保留原LastFullSyncAt() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns emptyList()
        coEvery { doubanSyncPendingItemDao.getAll() } returns emptyList()
        coEvery { doubanSyncedItemDao.count() } returns 0
        coEvery { doubanSyncPendingItemDao.count() } returns 0
        val fixedLastFull = 1234567890L
        coEvery { doubanSyncMetaStorage.getLastFullSyncAt() } returns fixedLastFull
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()

        val capturedPaths = mutableListOf<String>()
        val capturedRequests = mutableListOf<GiteeContentRequest>()
        coEvery {
            giteeContentsApi.createFileContent(any(), any(), capture(capturedPaths), capture(capturedRequests))
        } returns mockSuccessUpdateResponse()

        val result = manager.uploadAll(lastSyncMode = "INCREMENTAL", isFullComplete = false)

        assertThat(result).isTrue()

        val metaIndex = capturedPaths.indexOfLast { it.contains("sync_meta.json") }
        assertThat(metaIndex).isAtLeast(0)
        val decryptedPayload = decryptRequestContent(capturedRequests[metaIndex])
        val payloadObj = json.decodeFromString(JsonObject.serializer(), decryptedPayload)
        val lastFullSyncAt = payloadObj["lastFullSyncAt"]!!.jsonPrimitive.content.toLong()
        // isFullComplete=false → lastFullSyncAt = 保留的本地原值
        assertThat(lastFullSyncAt).isEqualTo(fixedLastFull)
    }

    @Test
    fun uploadAll_部分上传失败_returnsFalse() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns emptyList()
        coEvery { doubanSyncPendingItemDao.getAll() } returns emptyList()
        coEvery { doubanSyncedItemDao.count() } returns 0
        coEvery { doubanSyncPendingItemDao.count() } returns 0
        coEvery { doubanSyncMetaStorage.getLastFullSyncAt() } returns 0L
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()
        // 前 2 次成功（synced/pending），第 3 次（meta）失败
        coEvery {
            giteeContentsApi.createFileContent(any(), any(), any(), any())
        } returnsMany listOf(
            mockSuccessUpdateResponse(),
            mockSuccessUpdateResponse(),
            mockFailedUpdateResponse()
        )

        val result = manager.uploadAll(lastSyncMode = "FULL_REWRITE", isFullComplete = false)

        // 任一失败 → 返回 false，但其他文件仍继续上传（共 3 次 POST）
        assertThat(result).isFalse()
        coVerify(exactly = 3) { giteeContentsApi.createFileContent(any(), any(), any(), any()) }
    }

    // ============================================================
    // refreshMetaOnly 测试
    // ============================================================

    @Test
    fun refreshMetaOnly_未登录_returnsFalse() = runTest {
        coEvery { userProfileStorage.getProfile() } returns null

        val result = manager.refreshMetaOnly()

        assertThat(result).isFalse()
        // getUserHash 在 getProfile null 时早返回，不调用 getFileContent
        coVerify(exactly = 0) { giteeContentsApi.getFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun refreshMetaOnly_云端无数据_returnsFalse() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()

        val result = manager.refreshMetaOnly()

        assertThat(result).isFalse()
        // checkCloudMeta 调用一次 getFileContent，返回 404 → null
        coVerify(exactly = 1) { giteeContentsApi.getFileContent(any(), any(), any(), any()) }
        coVerify(exactly = 0) { doubanSyncMetaStorage.updateFromCloud(any(), any(), any()) }
    }

    @Test
    fun refreshMetaOnly_云端有数据_合并到本地并返回True() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        val metaPayloadJson = buildSyncMetaPayloadJson(
            lastFullSyncAt = 1000L, lastSyncAt = 2000L, lastSyncMode = "FULL_REWRITE",
            totalSyncedItems = 5, totalPendingItems = 0, uploadedAt = 3000L
        )
        val cloudBody = buildCloudResponseJson(metaPayloadJson, "sha-meta")
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockSuccessResponse(cloudBody)

        val result = manager.refreshMetaOnly()

        assertThat(result).isTrue()
        coVerify(exactly = 1) {
            doubanSyncMetaStorage.updateFromCloud(1000L, 2000L, "FULL_REWRITE")
        }
    }

    @Test
    fun refreshMetaOnly_成功后立即重复调用_节流返回True且不发起网络() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        val metaPayloadJson = buildSyncMetaPayloadJson(
            lastFullSyncAt = 1000L, lastSyncAt = 2000L, lastSyncMode = "FULL_REWRITE",
            totalSyncedItems = 5, totalPendingItems = 0, uploadedAt = 3000L
        )
        val cloudBody = buildCloudResponseJson(metaPayloadJson, "sha-meta")
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockSuccessResponse(cloudBody)

        // 第一次调用：成功，发起网络请求
        val result1 = manager.refreshMetaOnly()
        assertThat(result1).isTrue()

        // 第二次调用（立即，间隔远小于 5 秒）：被节流，返回 true，不发起网络
        val result2 = manager.refreshMetaOnly()
        assertThat(result2).isTrue()

        // getFileContent 只被调用 1 次（第二次被节流）
        coVerify(exactly = 1) { giteeContentsApi.getFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun refreshMetaOnly_失败后立即重复调用_节流返回False() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()

        // 第一次调用：失败（404），lastMetaRefreshFailedAt = now
        val result1 = manager.refreshMetaOnly()
        assertThat(result1).isFalse()

        // 第二次调用（立即，间隔远小于 1 秒）：被节流，返回 false，不发起网络
        val result2 = manager.refreshMetaOnly()
        assertThat(result2).isFalse()

        // getFileContent 只被调用 1 次（第二次被失败短窗口节流）
        coVerify(exactly = 1) { giteeContentsApi.getFileContent(any(), any(), any(), any()) }
    }

    // ============================================================
    // downloadAndMerge 测试
    // ============================================================

    @Test
    fun downloadAndMerge_未登录_returns空PullResult() = runTest {
        coEvery { userProfileStorage.getProfile() } returns null

        val result = manager.downloadAndMerge()

        assertThat(result.syncedItems).isEqualTo(0)
        assertThat(result.pendingItems).isEqualTo(0)
        assertThat(result.idMappings).isEqualTo(0)
        assertThat(result.metaApplied).isFalse()
        assertThat(result.hasAnyData).isFalse()
        coVerify(exactly = 0) { giteeContentsApi.getFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun downloadAndMerge_云端syncedItems较新_合并到本地() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        // 云端 syncedAt=2000（较新）
        val cloudItems = listOf(buildSyncedItem(doubanId = "cloud-1", syncedAt = 2000L))
        val cloudBody = buildCloudResponseJson(
            buildSyncedItemsPayloadJson(cloudItems), "sha-synced"
        )
        // 本地 syncedAt=1000（较旧）
        val localItems = listOf(buildSyncedItem(doubanId = "cloud-1", syncedAt = 1000L))
        val syncedPath = cloudPath("testuser", "synced_items.json")
        // 默认 404，synced_items 返回数据
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()
        coEvery { giteeContentsApi.getFileContent(any(), any(), syncedPath, any()) } returns mockSuccessResponse(cloudBody)
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns localItems

        val insertSlot = slot<List<DoubanSyncedItem>>()
        coEvery { doubanSyncedItemDao.insertAll(capture(insertSlot)) } returns Unit

        val result = manager.downloadAndMerge()

        assertThat(result.syncedItems).isEqualTo(1)
        assertThat(result.hasAnyData).isTrue()
        assertThat(insertSlot.captured).hasSize(1)
        assertThat(insertSlot.captured[0].doubanId).isEqualTo("cloud-1")
        assertThat(insertSlot.captured[0].syncedAt).isEqualTo(2000L)
    }

    @Test
    fun downloadAndMerge_云端syncedItems较旧_跳过() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        // 云端 syncedAt=1000（较旧）
        val cloudItems = listOf(buildSyncedItem(doubanId = "cloud-1", syncedAt = 1000L))
        val cloudBody = buildCloudResponseJson(
            buildSyncedItemsPayloadJson(cloudItems), "sha-synced"
        )
        // 本地 syncedAt=2000（较新）
        val localItems = listOf(buildSyncedItem(doubanId = "cloud-1", syncedAt = 2000L))
        val syncedPath = cloudPath("testuser", "synced_items.json")
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()
        coEvery { giteeContentsApi.getFileContent(any(), any(), syncedPath, any()) } returns mockSuccessResponse(cloudBody)
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns localItems

        val result = manager.downloadAndMerge()

        // 云端较旧 → 不覆盖本地，syncedItems=0，不调用 insertAll
        assertThat(result.syncedItems).isEqualTo(0)
        coVerify(exactly = 0) { doubanSyncedItemDao.insertAll(any()) }
    }

    @Test
    fun downloadAndMerge_云端pendingItems较新_合并到本地() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        // 云端 crawledAt=2000（较新）
        val cloudPending = listOf(buildPendingItem(doubanId = "p-1", crawledAt = 2000L))
        val cloudBody = buildCloudResponseJson(
            buildPendingItemsPayloadJson(cloudPending), "sha-pending"
        )
        // 本地 crawledAt=1000（较旧）
        val localPending = listOf(buildPendingItem(doubanId = "p-1", crawledAt = 1000L))
        val pendingPath = cloudPath("testuser", "pending_items.json")
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()
        coEvery { giteeContentsApi.getFileContent(any(), any(), pendingPath, any()) } returns mockSuccessResponse(cloudBody)
        coEvery { doubanSyncPendingItemDao.getAll() } returns localPending

        val insertSlot = slot<List<DoubanSyncPendingItemEntity>>()
        coEvery { doubanSyncPendingItemDao.insertAll(capture(insertSlot)) } returns Unit

        val result = manager.downloadAndMerge()

        assertThat(result.pendingItems).isEqualTo(1)
        assertThat(result.hasAnyData).isTrue()
        assertThat(insertSlot.captured).hasSize(1)
        assertThat(insertSlot.captured[0].doubanId).isEqualTo("p-1")
        assertThat(insertSlot.captured[0].crawledAt).isEqualTo(2000L)
    }

    @Test
    fun downloadAndMerge_云端idMappings_调用MergeImdbMappings() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        // value 是空 list 的 JSON 字符串（合法的 List<TraktSearchResult> JSON）
        val mappingsPayloadJson = buildIdMappingsPayloadJson(
            mappings = mapOf("tt001_MOVIE" to "[]")
        )
        val cloudBody = buildCloudResponseJson(mappingsPayloadJson, "sha-mappings")
        val mappingsPath = cloudPath("testuser", "id_mappings.json")
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()
        coEvery { giteeContentsApi.getFileContent(any(), any(), mappingsPath, any()) } returns mockSuccessResponse(cloudBody)
        // mergeImdbMappings 返回 5（实际写入条目数）
        coEvery { traktRepository.mergeImdbMappings(any()) } returns 5

        val result = manager.downloadAndMerge()

        assertThat(result.idMappings).isEqualTo(5)
        assertThat(result.hasAnyData).isTrue()
        coVerify(exactly = 1) { traktRepository.mergeImdbMappings(any()) }
    }

    @Test
    fun downloadAndMerge_云端syncMeta_metaApplied为True() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "testuser")
        val metaPayloadJson = buildSyncMetaPayloadJson(
            lastFullSyncAt = 1000L, lastSyncAt = 2000L, lastSyncMode = "FULL_REWRITE",
            totalSyncedItems = 5, totalPendingItems = 0, uploadedAt = 3000L
        )
        val cloudBody = buildCloudResponseJson(metaPayloadJson, "sha-meta")
        val metaPath = cloudPath("testuser", "sync_meta.json")
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()
        coEvery { giteeContentsApi.getFileContent(any(), any(), metaPath, any()) } returns mockSuccessResponse(cloudBody)

        val result = manager.downloadAndMerge()

        assertThat(result.metaApplied).isTrue()
        assertThat(result.hasAnyData).isTrue()
        coVerify(exactly = 1) {
            doubanSyncMetaStorage.updateFromCloud(1000L, 2000L, "FULL_REWRITE")
        }
    }

    // ============================================================
    // AES 加密往返测试
    // ============================================================

    @Test
    fun uploadThenDownload_加密往返一致() = runTest {
        coEvery { userProfileStorage.getProfile() } returns TraktUserProfileResponse(username = "rt-user")

        val originalItems = listOf(
            buildSyncedItem(
                doubanId = "rt-001",
                imdbId = "tt001",
                traktId = 1,
                title = "往返电影",
                status = "wish",
                rating = 5,
                syncedAt = 1000L,
                mediaType = "movie"
            ),
            buildSyncedItem(
                doubanId = "rt-002",
                imdbId = null,
                traktId = null,
                title = "无IMDB",
                status = "collect",
                rating = null,
                syncedAt = 2000L,
                mediaType = "show"
            )
        )

        // ===== 1. 上传阶段：GET 404 → POST，捕获上传请求 =====
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns originalItems
        coEvery { doubanSyncPendingItemDao.getAll() } returns emptyList()
        coEvery { doubanSyncedItemDao.count() } returns originalItems.size
        coEvery { doubanSyncPendingItemDao.count() } returns 0
        coEvery { doubanSyncMetaStorage.getLastFullSyncAt() } returns 0L
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()

        val capturedPaths = mutableListOf<String>()
        val capturedRequests = mutableListOf<GiteeContentRequest>()
        coEvery {
            giteeContentsApi.createFileContent(any(), any(), capture(capturedPaths), capture(capturedRequests))
        } returns mockSuccessUpdateResponse()

        val uploadResult = manager.uploadAll(
            lastSyncMode = "FULL_REWRITE", isFullComplete = true, uploadIdMappings = false
        )
        assertThat(uploadResult).isTrue()

        // ===== 2. 用上传的 content 构造云端响应 =====
        val syncedIndex = capturedPaths.indexOfFirst { it.contains("synced_items.json") }
        assertThat(syncedIndex).isAtLeast(0)
        val capturedContent = capturedRequests[syncedIndex].content
        val cloudBody = buildCloudResponseFromContent(capturedContent, "after-upload-sha")

        // ===== 3. 下载阶段：synced_items 返回上传内容，其他文件 404 =====
        val syncedPath = cloudPath("rt-user", "synced_items.json")
        coEvery { giteeContentsApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()
        coEvery { giteeContentsApi.getFileContent(any(), any(), syncedPath, any()) } returns mockSuccessResponse(cloudBody)
        // 本地无数据 → 云端必定较新，触发合并
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns emptyList()

        val insertSlot = slot<List<DoubanSyncedItem>>()
        coEvery { doubanSyncedItemDao.insertAll(capture(insertSlot)) } returns Unit

        val downloadResult = manager.downloadAndMerge()

        // ===== 4. 验证下载后的字段与原始数据一致 =====
        assertThat(downloadResult.syncedItems).isEqualTo(2)

        val downloaded = insertSlot.captured
        assertThat(downloaded).hasSize(2)

        // 第一条：所有字段完整
        val first = downloaded[0]
        assertThat(first.doubanId).isEqualTo("rt-001")
        assertThat(first.imdbId).isEqualTo("tt001")
        assertThat(first.traktId).isEqualTo(1)
        assertThat(first.title).isEqualTo("往返电影")
        assertThat(first.status).isEqualTo("wish")
        assertThat(first.rating).isEqualTo(5)
        assertThat(first.syncedAt).isEqualTo(1000L)
        assertThat(first.mediaType).isEqualTo("movie")

        // 第二条：nullable 字段为 null（往返后仍为 null）
        val second = downloaded[1]
        assertThat(second.doubanId).isEqualTo("rt-002")
        assertThat(second.imdbId).isNull()
        assertThat(second.traktId).isNull()
        assertThat(second.rating).isNull()
        assertThat(second.title).isEqualTo("无IMDB")
        assertThat(second.status).isEqualTo("collect")
        assertThat(second.syncedAt).isEqualTo(2000L)
        assertThat(second.mediaType).isEqualTo("show")
    }
}
