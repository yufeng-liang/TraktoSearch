package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.feedback.FeedbackApiException
import com.tracktosearch.data.remote.feedback.FeedbackApiService
import com.tracktosearch.data.remote.feedback.FeedbackDetailResponse
import com.tracktosearch.data.remote.feedback.FeedbackResponse
import com.tracktosearch.data.remote.feedback.MineResponse
import com.tracktosearch.data.remote.feedback.SubmitFeedbackResponse
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class FeedbackRepositoryTest {

    private abstract class TestFeedbackApi : FeedbackApiService {
        override suspend fun markAsRead(id: String) = error("not used")
        override suspend fun markAllRead() = error("not used")
        override suspend fun getUnreadCount() = error("not used")
        override suspend fun getMessages(limit: Int, offset: Int) = error("not used")
        override suspend fun reply(id: String, request: com.tracktosearch.data.remote.feedback.ReplyRequest) = error("not used")
    }

    private val cacheStore = io.mockk.mockk<FeedbackCacheStore>(relaxed = true)

    private val friendNickname = "测试朋友"

    @Test
    fun `submit success returns id`() = runTest {
        var submittedRequest: com.tracktosearch.data.remote.feedback.SubmitFeedbackRequest? = null
        val api = object : TestFeedbackApi() {
            override suspend fun uploadScreenshot(file: MultipartBody.Part) = Response.success(
                FeedbackResponse("SUCCESS", "OK", "r1", com.tracktosearch.data.remote.feedback.UploadScreenshotResponse("k1"))
            )
            override suspend fun submit(request: com.tracktosearch.data.remote.feedback.SubmitFeedbackRequest): Response<FeedbackResponse<SubmitFeedbackResponse>> {
                submittedRequest = request
                return Response.success(
                    FeedbackResponse("SUCCESS", "OK", "r1", SubmitFeedbackResponse("fb1", 1700000000L))
                )
            }
            override suspend fun getMine(limit: Int, offset: Int) = Response.success(
                FeedbackResponse("SUCCESS", "OK", "r1", MineResponse(emptyList(), 20, 0, 0, false))
            )
            override suspend fun getDetail(id: String) = Response.success(
                FeedbackResponse("SUCCESS", "OK", "r1", FeedbackDetailResponse(
                    com.tracktosearch.data.remote.feedback.FeedbackDetail("fb1", "f1", friendNickname, null, null, null, "BUG", "content", null, null, "1.0", "14", "Pixel", "PENDING", 1700000000L),
                    emptyList()
                ))
            )
        }
        val repo = FeedbackRepository(api, cacheStore)
        val result = repo.submit(
            type = "BUG",
            content = "test content",
            screenshots = emptyList(),
            friendNickname = friendNickname,
            traktUsername = null,
            doubanUsername = null,
            appVersion = "1.0",
            osVersion = "14"
        )
        assertTrue(result.isSuccess)
        assertEquals("fb1", result.getOrNull()?.id)
        assertThat(submittedRequest?.contact).isNull()
    }

    @Test
    fun `submit network error returns failure`() = runTest {
        val api = object : TestFeedbackApi() {
            override suspend fun uploadScreenshot(file: MultipartBody.Part) = error("not used")
            override suspend fun submit(request: com.tracktosearch.data.remote.feedback.SubmitFeedbackRequest) = error("network")
            override suspend fun getMine(limit: Int, offset: Int) = error("not used")
            override suspend fun getDetail(id: String) = error("not used")
        }
        val repo = FeedbackRepository(api, cacheStore)
        val result = repo.submit("BUG", "test", emptyList(), friendNickname, null, null, "1.0", "14")
        assertTrue(result.isFailure)
    }

    @Test
    fun `uploadScreenshot success returns key`() = runTest {
        val api = object : TestFeedbackApi() {
            override suspend fun uploadScreenshot(file: MultipartBody.Part) = Response.success(
                FeedbackResponse("SUCCESS", "OK", "r1", com.tracktosearch.data.remote.feedback.UploadScreenshotResponse("r2key-abc"))
            )
            override suspend fun submit(request: com.tracktosearch.data.remote.feedback.SubmitFeedbackRequest) = error("not used")
            override suspend fun getMine(limit: Int, offset: Int) = error("not used")
            override suspend fun getDetail(id: String) = error("not used")
        }
        val repo = FeedbackRepository(api, cacheStore)
        val result = repo.uploadScreenshot(byteArrayOf(1, 2, 3), "image/jpeg")
        assertTrue(result.isSuccess)
        assertEquals("r2key-abc", result.getOrNull())
    }

    @Test
    fun `uploadScreenshot http failure returns failure`() = runTest {
        val api = object : TestFeedbackApi() {
            override suspend fun uploadScreenshot(file: MultipartBody.Part) = Response.error<FeedbackResponse<com.tracktosearch.data.remote.feedback.UploadScreenshotResponse>>(
                413,
                okhttp3.ResponseBody.Companion.create(null, "")
            )
            override suspend fun submit(request: com.tracktosearch.data.remote.feedback.SubmitFeedbackRequest) = error("not used")
            override suspend fun getMine(limit: Int, offset: Int) = error("not used")
            override suspend fun getDetail(id: String) = error("not used")
        }
        val repo = FeedbackRepository(api, cacheStore)
        val result = repo.uploadScreenshot(byteArrayOf(1, 2, 3), "image/jpeg")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FeedbackApiException)
        assertTrue(result.exceptionOrNull()?.message?.contains("413") == true)
    }

    @Test
    fun `getMine success returns list`() = runTest {
        val items = listOf(
            com.tracktosearch.data.remote.feedback.FeedbackListItem("fb1", "BUG", "content1", null, "PENDING", 1700000000L),
            com.tracktosearch.data.remote.feedback.FeedbackListItem("fb2", "SUGGESTION", "content2", """["key1"]""", "REPLIED", 1700000001L)
        )
        val api = object : TestFeedbackApi() {
            override suspend fun uploadScreenshot(file: MultipartBody.Part) = error("not used")
            override suspend fun submit(request: com.tracktosearch.data.remote.feedback.SubmitFeedbackRequest) = error("not used")
            override suspend fun getMine(limit: Int, offset: Int) = Response.success(
                FeedbackResponse("SUCCESS", "OK", "r1", MineResponse(items, 20, 0, 2, false))
            )
            override suspend fun getDetail(id: String) = error("not used")
        }
        val repo = FeedbackRepository(api, cacheStore)
        val result = repo.getMine()
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()?.feedbacks?.size)
        assertEquals("fb1", result.getOrNull()?.feedbacks?.first()?.id)
        assertFalse(result.getOrNull()?.hasMore == true)
    }

    @Test
    fun `getDetail http failure returns failure`() = runTest {
        val api = object : TestFeedbackApi() {
            override suspend fun uploadScreenshot(file: MultipartBody.Part) = error("not used")
            override suspend fun submit(request: com.tracktosearch.data.remote.feedback.SubmitFeedbackRequest) = error("not used")
            override suspend fun getMine(limit: Int, offset: Int) = error("not used")
            override suspend fun getDetail(id: String) = Response.error<FeedbackResponse<FeedbackDetailResponse>>(
                404,
                okhttp3.ResponseBody.Companion.create(null, "")
            )
        }
        val repo = FeedbackRepository(api, cacheStore)
        val result = repo.getDetail("not-exist")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("404") == true)
    }
}
