package com.tracktosearch.data.repository

import android.util.Base64
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.local.db.DoubanSyncFailureEntity
import com.tracktosearch.data.remote.cloud.AesCrypto
import com.tracktosearch.data.remote.cloud.GiteeContentRequest
import com.tracktosearch.data.remote.cloud.GiteeContentUpdateResponse
import com.tracktosearch.data.remote.cloud.GiteeContentsApi
import com.tracktosearch.data.repository.UploadResult
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * CloudFailureSyncManager 单元测试。
 *
 * 覆盖点：
 * - isLoggedIn：已登录/未登录判断
 * - uploadIfHasFailures：未登录跳过、本地空跳过、本地新于云端走 POST、云端已有走 PUT、
 *   本地不比云端新跳过、上传响应失败、上传异常
 * - checkCloudFailures：未登录、404、有数据返回数量、解密失败
 * - downloadAndMerge：未登录、云端空、本地更新、本地无数据、云端更新、GET失败、解密失败
 * - AesCrypto 加密往返：upload → download 端到端字段一致
 *
 * 使用 Robolectric：AesCrypto 和 CloudFailureSyncManager 内部依赖 android.util.Base64 和 android.util.Log。
 *
 * 注意：CloudPayload/CloudFailureDto 是私有类，测试通过手动构造 JsonObject 构建 payload JSON，
 * 加密方式与 Manager 一致：AesCrypto.encrypt(payloadJson) → base64(encrypted.toByteArray(UTF-8))。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CloudFailureSyncManagerTest {

    private val giteeApi = mockk<GiteeContentsApi>(relaxed = true)
    private val doubanSyncFailureDao = mockk<DoubanSyncFailureDao>(relaxed = true)
    private val doubanAuthStorage = mockk<DoubanAuthStorage>(relaxed = true)
    private val cloudFailurePullMetaStorage = mockk<com.tracktosearch.data.local.CloudFailurePullMetaStorage>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val manager = CloudFailureSyncManager(giteeApi, doubanSyncFailureDao, doubanAuthStorage, cloudFailurePullMetaStorage, json)

    @Before
    fun setUp() {
        // 清除前序测试的 stub 和调用记录，确保 coVerify(exactly = 0) 不受干扰
        clearMocks(giteeApi, doubanSyncFailureDao, doubanAuthStorage, cloudFailurePullMetaStorage)
    }

    // ============================================================
    // 辅助函数
    // ============================================================

    /** 构造本地失败项 entity，所有字段可定制 */
    private fun buildFailureEntity(
        doubanId: String = "db-001",
        title: String = "测试电影",
        posterUrl: String? = "https://img.example.com/p.jpg",
        rating: Int? = 5,
        comment: String? = "好片",
        markedAt: String = "2024-01-15",
        doubanUrl: String = "https://book.douban.com/subject/db-001/",
        status: String = "wish",
        failureReason: String = "TRAKT_WRITE_FAILED",
        failedAt: Long = 1000L,
        updatedAt: Long = 1000L,
        attemptCount: Int = 1,
        mediaType: String? = "movie",
        subtitle: String? = null
    ): DoubanSyncFailureEntity = DoubanSyncFailureEntity(
        doubanId = doubanId,
        title = title,
        posterUrl = posterUrl,
        rating = rating,
        comment = comment,
        markedAt = markedAt,
        doubanUrl = doubanUrl,
        status = status,
        failureReason = failureReason,
        failedAt = failedAt,
        updatedAt = updatedAt,
        attemptCount = attemptCount,
        mediaType = mediaType,
        subtitle = subtitle
    )

    /**
     * 手动构造 CloudPayload JSON 字符串（CloudPayload 是私有类，无法直接构造）。
     * 字段结构与 CloudFailureDto 一致：必填字段（无默认值）必须出现，可选字段也全部写出便于调试。
     */
    private fun buildCloudPayloadJson(
        uploadedAt: Long,
        failures: List<DoubanSyncFailureEntity>
    ): String {
        val failuresArray = buildJsonArray {
            failures.forEach { e ->
                add(buildJsonObject {
                    put("doubanId", e.doubanId)
                    put("title", e.title)
                    put("posterUrl", e.posterUrl)
                    put("rating", e.rating)
                    put("comment", e.comment)
                    put("markedAt", e.markedAt)
                    put("doubanUrl", e.doubanUrl)
                    put("status", e.status)
                    put("failureReason", e.failureReason)
                    put("failedAt", e.failedAt)
                    put("updatedAt", e.updatedAt)
                    put("attemptCount", e.attemptCount)
                    put("mediaType", e.mediaType)
                    put("subtitle", e.subtitle)
                })
            }
        }
        val payload = buildJsonObject {
            put("version", 1)
            put("uploadedAt", uploadedAt)
            put("totalFailures", failures.size)
            put("failures", failuresArray)
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    /**
     * 构造云端文件响应 JsonObject：content 为 base64(AesCrypto.encrypt(payloadJson))，sha 为文件 sha。
     * 加密链路与 Manager.uploadIfHasFailures 完全一致。
     */
    private fun buildCloudResponseJson(
        uploadedAt: Long,
        failures: List<DoubanSyncFailureEntity>,
        sha: String
    ): JsonObject {
        val payloadJson = buildCloudPayloadJson(uploadedAt, failures)
        val encrypted = AesCrypto.encrypt(payloadJson)
        val base64Content = Base64.encodeToString(
            encrypted.toByteArray(Charsets.UTF_8),
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
        val encrypted = String(
            Base64.decode(request.content, Base64.NO_WRAP),
            Charsets.UTF_8
        )
        return AesCrypto.decrypt(encrypted)!!
    }

    /** 构造 GET 成功响应（200 + JsonObject body） */
    private fun mockSuccessResponse(body: JsonObject): Response<kotlinx.serialization.json.JsonElement> {
        val resp = mockk<Response<kotlinx.serialization.json.JsonElement>>()
        every { resp.isSuccessful } returns true
        every { resp.body() } returns body
        every { resp.code() } returns 200
        return resp
    }

    /** 构造 GET 404 响应 */
    private fun mockNotFoundResponse(): Response<kotlinx.serialization.json.JsonElement> {
        val resp = mockk<Response<kotlinx.serialization.json.JsonElement>>()
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

    // ============================================================
    // isLoggedIn 测试
    // ============================================================

    @Test
    fun isLoggedIn_未登录_returnsFalse() = runTest {
        every { doubanAuthStorage.getCredentials() } returns null

        assertThat(manager.isLoggedIn()).isFalse()
    }

    @Test
    fun isLoggedIn_已登录_returnsTrue() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")

        assertThat(manager.isLoggedIn()).isTrue()
    }

    // ============================================================
    // uploadIfHasFailures 测试
    // ============================================================

    @Test
    fun uploadIfHasFailures_未登录_returnsFalse且不调用GiteeApi() = runTest {
        every { doubanAuthStorage.getCredentials() } returns null

        val result = manager.uploadIfHasFailures()

        assertThat(result).isEqualTo(UploadResult.Failed)
        coVerify(exactly = 0) {
            giteeApi.getFileContent(any(), any(), any(), any())
            giteeApi.createFileContent(any(), any(), any(), any())
            giteeApi.putFileContent(any(), any(), any(), any())
        }
    }

    @Test
    fun uploadIfHasFailures_本地无失败项_returnsTrue且不调用上传Api() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        coEvery { doubanSyncFailureDao.getAll() } returns emptyList()

        val result = manager.uploadIfHasFailures()

        assertThat(result).isEqualTo(UploadResult.Skipped(0L, 0L))
        // 本地空时早返回，不调用任何 Gitee API（包括 GET）
        coVerify(exactly = 0) {
            giteeApi.getFileContent(any(), any(), any(), any())
            giteeApi.createFileContent(any(), any(), any(), any())
            giteeApi.putFileContent(any(), any(), any(), any())
        }
    }

    @Test
    fun uploadIfHasFailures_本地新于云端_调用CreateFileContent上传() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        val entity = buildFailureEntity(doubanId = "db-001", title = "测试电影", failedAt = 2000L, updatedAt = 2000L)
        coEvery { doubanSyncFailureDao.getAll() } returns listOf(entity)
        // GET 返回 404：existingSha=null, cloudUploadedAt=0 → 走 POST
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()

        val createSlot = slot<GiteeContentRequest>()
        coEvery {
            giteeApi.createFileContent(any(), any(), any(), capture(createSlot))
        } returns mockSuccessUpdateResponse()

        val result = manager.uploadIfHasFailures()

        assertThat(result).isEqualTo(UploadResult.Uploaded)
        coVerify(exactly = 0) { giteeApi.putFileContent(any(), any(), any(), any()) }
        coVerify(exactly = 1) { giteeApi.createFileContent(any(), any(), any(), any()) }

        // POST 不传 sha
        assertThat(createSlot.captured.sha).isNull()

        // 解密上传内容验证字段
        val decryptedPayload = decryptRequestContent(createSlot.captured)
        val payloadObj = json.decodeFromString(JsonObject.serializer(), decryptedPayload)
        assertThat(payloadObj["totalFailures"]!!.jsonPrimitive.content.toInt()).isEqualTo(1)
        val failure = payloadObj["failures"]!!.jsonArray[0].jsonObject
        assertThat(failure["doubanId"]!!.jsonPrimitive.content).isEqualTo("db-001")
        assertThat(failure["title"]!!.jsonPrimitive.content).isEqualTo("测试电影")
        assertThat(failure["status"]!!.jsonPrimitive.content).isEqualTo("wish")
        assertThat(failure["failedAt"]!!.jsonPrimitive.content.toLong()).isEqualTo(2000L)
        assertThat(failure["attemptCount"]!!.jsonPrimitive.content.toInt()).isEqualTo(1)
        assertThat(failure["mediaType"]!!.jsonPrimitive.content).isEqualTo("movie")
    }

    @Test
    fun uploadIfHasFailures_云端已有文件_调用PutFileContent更新() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        val entity = buildFailureEntity(failedAt = 2000L, updatedAt = 2000L)
        coEvery { doubanSyncFailureDao.getAll() } returns listOf(entity)

        // 云端已有文件：uploadedAt=1000 < localNewest=2000 → 本地新于云端，走 PUT
        val cloudBody = buildCloudResponseJson(uploadedAt = 1000L, failures = listOf(entity), sha = "cloud-sha-123")
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockSuccessResponse(cloudBody)

        val putSlot = slot<GiteeContentRequest>()
        coEvery {
            giteeApi.putFileContent(any(), any(), any(), capture(putSlot))
        } returns mockSuccessUpdateResponse()

        val result = manager.uploadIfHasFailures()

        assertThat(result).isEqualTo(UploadResult.Uploaded)
        coVerify(exactly = 0) { giteeApi.createFileContent(any(), any(), any(), any()) }
        coVerify(exactly = 1) { giteeApi.putFileContent(any(), any(), any(), any()) }

        // PUT 请求必须带 sha
        assertThat(putSlot.captured.sha).isEqualTo("cloud-sha-123")
    }

    @Test
    fun uploadIfHasFailures_本地不比云端新_returnsTrue且跳过上传() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        val entity = buildFailureEntity(failedAt = 1000L, updatedAt = 1000L)
        coEvery { doubanSyncFailureDao.getAll() } returns listOf(entity)

        // 云端 uploadedAt=3000 >= localNewest=1000 → 本地不比云端新，跳过上传
        val cloudBody = buildCloudResponseJson(uploadedAt = 3000L, failures = listOf(entity), sha = "cloud-sha-456")
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockSuccessResponse(cloudBody)

        val result = manager.uploadIfHasFailures()

        assertThat(result).isEqualTo(UploadResult.Skipped(3000L, 1000L))
        coVerify(exactly = 1) { giteeApi.getFileContent(any(), any(), any(), any()) }
        coVerify(exactly = 0) { giteeApi.createFileContent(any(), any(), any(), any()) }
        coVerify(exactly = 0) { giteeApi.putFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun uploadIfHasFailures_上传响应失败_returnsFalse() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        val entity = buildFailureEntity()
        coEvery { doubanSyncFailureDao.getAll() } returns listOf(entity)
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()
        coEvery {
            giteeApi.createFileContent(any(), any(), any(), any())
        } returns mockFailedUpdateResponse()

        val result = manager.uploadIfHasFailures()

        assertThat(result).isEqualTo(UploadResult.Failed)
    }

    @Test
    fun uploadIfHasFailures_上传异常_returnsFalse() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        val entity = buildFailureEntity()
        coEvery { doubanSyncFailureDao.getAll() } returns listOf(entity)
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()
        coEvery {
            giteeApi.createFileContent(any(), any(), any(), any())
        } throws RuntimeException("网络异常")

        val result = manager.uploadIfHasFailures()

        assertThat(result).isEqualTo(UploadResult.Failed)
    }

    // ============================================================
    // checkCloudFailures 测试
    // ============================================================

    @Test
    fun checkCloudFailures_未登录_returnsNull() = runTest {
        every { doubanAuthStorage.getCredentials() } returns null

        val result = manager.checkCloudFailures()

        assertThat(result).isNull()
        coVerify(exactly = 0) { giteeApi.getFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun checkCloudFailures_云端404_returnsNull() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()

        val result = manager.checkCloudFailures()

        assertThat(result).isNull()
    }

    @Test
    fun checkCloudFailures_云端有数据_returnsTotalFailures() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        val failures = listOf(
            buildFailureEntity(doubanId = "db-1"),
            buildFailureEntity(doubanId = "db-2"),
            buildFailureEntity(doubanId = "db-3")
        )
        val cloudBody = buildCloudResponseJson(uploadedAt = 1000L, failures = failures, sha = "sha-check")
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockSuccessResponse(cloudBody)

        val result = manager.checkCloudFailures()

        assertThat(result).isEqualTo(3)
    }

    @Test
    fun checkCloudFailures_解密失败_returnsNull() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        // 云端 content 是无效加密数据：AesCrypto.decrypt 返回 null
        val invalidEncrypted = "this-is-not-valid-encrypted-data"
        val base64Content = Base64.encodeToString(
            invalidEncrypted.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val cloudBody = buildJsonObject {
            put("content", base64Content)
            put("sha", "sha-invalid")
        }
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockSuccessResponse(cloudBody)

        val result = manager.checkCloudFailures()

        assertThat(result).isNull()
    }

    // ============================================================
    // downloadAndMerge 测试
    // ============================================================

    @Test
    fun downloadAndMerge_未登录_returnsFailed() = runTest {
        every { doubanAuthStorage.getCredentials() } returns null

        val result = manager.downloadAndMerge()

        assertThat(result).isEqualTo(DownloadResult.Failed)
        coVerify(exactly = 0) { giteeApi.getFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun downloadAndMerge_云端failures为空_returnsCloudEmpty() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        val cloudBody = buildCloudResponseJson(uploadedAt = 1000L, failures = emptyList(), sha = "sha-empty")
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockSuccessResponse(cloudBody)

        val result = manager.downloadAndMerge()

        assertThat(result).isEqualTo(DownloadResult.CloudEmpty)
        coVerify(exactly = 0) { doubanSyncFailureDao.replaceAll(any()) }
    }

    @Test
    fun downloadAndMerge_本地有数据且更新_returnsLocalNewer() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        val cloudFailures = listOf(buildFailureEntity(doubanId = "cloud-1", failedAt = 500L, updatedAt = 500L))
        // 云端 uploadedAt=1000, local failedAt=2000 → localNewest=2000 > 0 && 1000 <= 2000 → LocalNewer
        val cloudBody = buildCloudResponseJson(uploadedAt = 1000L, failures = cloudFailures, sha = "sha-ln")
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockSuccessResponse(cloudBody)

        val localEntities = listOf(buildFailureEntity(doubanId = "local-1", failedAt = 2000L, updatedAt = 2000L))
        coEvery { doubanSyncFailureDao.getAll() } returns localEntities

        val result = manager.downloadAndMerge()

        assertThat(result).isEqualTo(DownloadResult.LocalNewer(cloudTime = 1000L, localTime = 2000L))
        coVerify(exactly = 0) { doubanSyncFailureDao.replaceAll(any()) }
    }

    @Test
    fun downloadAndMerge_本地无数据_returnsSuccess并替换本地() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        val cloudFailures = listOf(
            buildFailureEntity(doubanId = "cloud-1", failedAt = 1000L),
            buildFailureEntity(doubanId = "cloud-2", failedAt = 2000L)
        )
        val cloudBody = buildCloudResponseJson(uploadedAt = 3000L, failures = cloudFailures, sha = "sha-succ")
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockSuccessResponse(cloudBody)
        // 本地无数据 → localNewest=0 → 触发下载替换
        coEvery { doubanSyncFailureDao.getAll() } returns emptyList()

        val replaceSlot = slot<List<DoubanSyncFailureEntity>>()
        coEvery { doubanSyncFailureDao.replaceAll(capture(replaceSlot)) } returns Unit

        val result = manager.downloadAndMerge()

        assertThat(result).isInstanceOf(DownloadResult.Success::class.java)
        assertThat((result as DownloadResult.Success).count).isEqualTo(2)
        assertThat(replaceSlot.captured).hasSize(2)
        assertThat(replaceSlot.captured[0].doubanId).isEqualTo("cloud-1")
        assertThat(replaceSlot.captured[1].doubanId).isEqualTo("cloud-2")
    }

    @Test
    fun downloadAndMerge_云端更新_returnsSuccess并替换本地() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        val cloudFailures = listOf(buildFailureEntity(doubanId = "cloud-new", failedAt = 5000L))
        // 云端 uploadedAt=5000 > localNewest=1000 → 云端更新，替换本地
        val cloudBody = buildCloudResponseJson(uploadedAt = 5000L, failures = cloudFailures, sha = "sha-update")
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockSuccessResponse(cloudBody)

        val localEntities = listOf(buildFailureEntity(doubanId = "local-old", failedAt = 1000L, updatedAt = 1000L))
        coEvery { doubanSyncFailureDao.getAll() } returns localEntities

        val replaceSlot = slot<List<DoubanSyncFailureEntity>>()
        coEvery { doubanSyncFailureDao.replaceAll(capture(replaceSlot)) } returns Unit

        val result = manager.downloadAndMerge()

        assertThat(result).isInstanceOf(DownloadResult.Success::class.java)
        assertThat((result as DownloadResult.Success).count).isEqualTo(1)
        coVerify(exactly = 1) { doubanSyncFailureDao.replaceAll(any()) }
        assertThat(replaceSlot.captured[0].doubanId).isEqualTo("cloud-new")
    }

    @Test
    fun downloadAndMerge_GET失败_returnsFailed() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()

        val result = manager.downloadAndMerge()

        assertThat(result).isEqualTo(DownloadResult.Failed)
        coVerify(exactly = 0) { doubanSyncFailureDao.replaceAll(any()) }
    }

    @Test
    fun downloadAndMerge_解密失败_returnsFailed() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        // 云端 content 是无效加密数据
        val invalidEncrypted = "invalid-encrypted-data"
        val base64Content = Base64.encodeToString(
            invalidEncrypted.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val cloudBody = buildJsonObject {
            put("content", base64Content)
            put("sha", "sha-invalid")
        }
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockSuccessResponse(cloudBody)

        val result = manager.downloadAndMerge()

        assertThat(result).isEqualTo(DownloadResult.Failed)
        coVerify(exactly = 0) { doubanSyncFailureDao.replaceAll(any()) }
    }

    /**
     * 回归测试：本地无数据 + 手动拉取(autoCommit=false) 应返回 Success 而非 OverwritePending。
     *
     * Bug 场景：用户本地无失败数据时选择从云端拉取，downloadAndMerge(autoCommit=false)
     * 因 !autoCommit 分支总是返回 OverwritePending，导致 UI 显示"覆盖现有数据"弹窗
     * （本地无数据何来覆盖）。且 OverwritePending 不落库，用户确认前数据未写入。
     *
     * 修复：!autoCommit && localNewest > 0L 时才返回 OverwritePending，
     * 本地无数据(localNewest=0)时直接 replaceAll 返回 Success。
     *
     * 验证：本地空 + autoCommit=false → 返回 Success（非 OverwritePending），且数据已落库。
     */
    @Test
    fun `downloadAndMerge_本地无数据且手动拉取_returnsSuccess而非OverwritePending`() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        val cloudFailures = listOf(
            buildFailureEntity(doubanId = "cloud-1", failedAt = 1000L),
            buildFailureEntity(doubanId = "cloud-2", failedAt = 2000L)
        )
        val cloudBody = buildCloudResponseJson(uploadedAt = 3000L, failures = cloudFailures, sha = "sha-succ")
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockSuccessResponse(cloudBody)
        // 本地无数据 → localNewest=0
        coEvery { doubanSyncFailureDao.getAll() } returns emptyList()

        val replaceSlot = slot<List<DoubanSyncFailureEntity>>()
        coEvery { doubanSyncFailureDao.replaceAll(capture(replaceSlot)) } returns Unit

        // 手动拉取：autoCommit=false
        val result = manager.downloadAndMerge(autoCommit = false)

        // 应返回 Success（已落库），而非 OverwritePending（等待确认）
        assertThat(result).isInstanceOf(DownloadResult.Success::class.java)
        assertThat(result).isNotInstanceOf(DownloadResult.OverwritePending::class.java)
        assertThat((result as DownloadResult.Success).count).isEqualTo(2)
        // 数据已落库
        coVerify(exactly = 1) { doubanSyncFailureDao.replaceAll(any()) }
        assertThat(replaceSlot.captured).hasSize(2)
    }

    /**
     * 回归测试：本地有数据 + 手动拉取(autoCommit=false) 应返回 OverwritePending。
     *
     * 这是 autoCommit=false 的正常路径：本地有数据时需要用户确认是否覆盖。
     * 与上一个测试对比，确保 localNewest > 0 时仍返回 OverwritePending。
     */
    @Test
    fun `downloadAndMerge_本地有数据且手动拉取_returnsOverwritePending`() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        val cloudFailures = listOf(buildFailureEntity(doubanId = "cloud-new", failedAt = 5000L))
        val cloudBody = buildCloudResponseJson(uploadedAt = 5000L, failures = cloudFailures, sha = "sha-update")
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockSuccessResponse(cloudBody)

        // 本地有数据 → localNewest=1000 > 0
        val localEntities = listOf(buildFailureEntity(doubanId = "local-old", failedAt = 1000L, updatedAt = 1000L))
        coEvery { doubanSyncFailureDao.getAll() } returns localEntities

        // 手动拉取：autoCommit=false
        val result = manager.downloadAndMerge(autoCommit = false)

        // 应返回 OverwritePending（等待用户确认），且未落库
        assertThat(result).isInstanceOf(DownloadResult.OverwritePending::class.java)
        coVerify(exactly = 0) { doubanSyncFailureDao.replaceAll(any()) }
    }

    // ============================================================
    // AES 加密往返测试
    // ============================================================

    @Test
    fun uploadThenDownload_加密往返一致() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-rt", "cookie")

        val originalEntities = listOf(
            buildFailureEntity(
                doubanId = "rt-001",
                title = "往返测试电影",
                posterUrl = "https://img.example.com/rt.jpg",
                rating = 5,
                comment = "往返测试评论",
                markedAt = "2024-03-20",
                doubanUrl = "https://book.douban.com/subject/rt-001/",
                status = "collect",
                failureReason = "TRAKT_WRITE_FAILED",
                failedAt = 1000L,
                updatedAt = 1500L,
                attemptCount = 2,
                mediaType = "show",
                subtitle = "Test Show"
            ),
            buildFailureEntity(
                doubanId = "rt-002",
                title = "第二个条目",
                posterUrl = null,
                rating = null,
                comment = null,
                failedAt = 2000L,
                updatedAt = 2000L,
                mediaType = null,
                subtitle = null
            )
        )

        // ===== 1. 上传阶段：GET 404 → POST =====
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()
        coEvery { doubanSyncFailureDao.getAll() } returns originalEntities

        val createSlot = slot<GiteeContentRequest>()
        coEvery {
            giteeApi.createFileContent(any(), any(), any(), capture(createSlot))
        } returns mockSuccessUpdateResponse()

        val uploadResult = manager.uploadIfHasFailures()
        assertThat(uploadResult).isEqualTo(UploadResult.Uploaded)

        // ===== 2. 用上传的 content 构造云端响应 =====
        val capturedContent = createSlot.captured.content
        val cloudBody = buildCloudResponseFromContent(capturedContent, "after-upload-sha")

        // 重新 stub GET 返回云端文件
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockSuccessResponse(cloudBody)
        // 本地无数据（localNewest=0 → 触发下载替换）
        coEvery { doubanSyncFailureDao.getAll() } returns emptyList()

        val replaceSlot = slot<List<DoubanSyncFailureEntity>>()
        coEvery { doubanSyncFailureDao.replaceAll(capture(replaceSlot)) } returns Unit

        // ===== 3. 下载阶段 =====
        val downloadResult = manager.downloadAndMerge()

        assertThat(downloadResult).isInstanceOf(DownloadResult.Success::class.java)
        assertThat((downloadResult as DownloadResult.Success).count).isEqualTo(2)

        // ===== 4. 验证下载后的字段与原始数据一致 =====
        val downloaded = replaceSlot.captured
        assertThat(downloaded).hasSize(2)

        // 第一条：所有字段完整
        val first = downloaded[0]
        assertThat(first.doubanId).isEqualTo("rt-001")
        assertThat(first.title).isEqualTo("往返测试电影")
        assertThat(first.posterUrl).isEqualTo("https://img.example.com/rt.jpg")
        assertThat(first.rating).isEqualTo(5)
        assertThat(first.comment).isEqualTo("往返测试评论")
        assertThat(first.markedAt).isEqualTo("2024-03-20")
        assertThat(first.doubanUrl).isEqualTo("https://book.douban.com/subject/rt-001/")
        assertThat(first.status).isEqualTo("collect")
        assertThat(first.failureReason).isEqualTo("TRAKT_WRITE_FAILED")
        assertThat(first.failedAt).isEqualTo(1000L)
        assertThat(first.updatedAt).isEqualTo(1500L)
        assertThat(first.attemptCount).isEqualTo(2)
        assertThat(first.mediaType).isEqualTo("show")
        assertThat(first.subtitle).isEqualTo("Test Show")

        // 第二条：nullable 字段为 null
        val second = downloaded[1]
        assertThat(second.doubanId).isEqualTo("rt-002")
        assertThat(second.title).isEqualTo("第二个条目")
        assertThat(second.posterUrl).isNull()
        assertThat(second.rating).isNull()
        assertThat(second.comment).isNull()
        assertThat(second.mediaType).isNull()
        assertThat(second.subtitle).isNull()
    }
}