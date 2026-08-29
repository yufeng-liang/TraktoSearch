package com.tracktosearch.data.repository

import com.tracktosearch.data.remote.feedback.FeedbackApiException
import com.tracktosearch.data.remote.feedback.FeedbackApiService
import com.tracktosearch.data.remote.feedback.FeedbackDetailResponse
import com.tracktosearch.data.remote.feedback.FeedbackResponse
import com.tracktosearch.data.remote.feedback.MessagesResponse
import com.tracktosearch.data.remote.feedback.MineResponse
import com.tracktosearch.data.remote.feedback.ReplyRequest
import com.tracktosearch.data.remote.feedback.ReplyResponse
import com.tracktosearch.data.remote.feedback.SubmitFeedbackRequest
import com.tracktosearch.data.remote.feedback.SubmitFeedbackResponse
import com.tracktosearch.data.remote.feedback.UnreadCountResponse
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
class FeedbackRepository(
    private val api: FeedbackApiService,
    private val cacheStore: FeedbackCacheStore
) {
    suspend fun loadCacheFromDisk() = cacheStore.loadFromDisk()
    fun getCachedList(): MineResponse? = cacheStore.getCachedList()

    suspend fun uploadScreenshot(bytes: ByteArray, mimeType: String, fileName: String = "screenshot.jpg"): Result<String> {
        return try {
            val body = bytes.toRequestBody(mimeType.toMediaType())
            val part = MultipartBody.Part.createFormData("file", fileName, body)
            val response = api.uploadScreenshot(part)
            if (response.isSuccessful) {
                val key = response.body()?.data?.key
                if (key != null) Result.success(key) else Result.failure(IllegalStateException("Empty response"))
            } else Result.failure(response.toFeedbackError())
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun submit(
        type: String,
        content: String,
        screenshots: List<String>,
        friendNickname: String,
        traktUsername: String?,
        doubanUsername: String?,
        appVersion: String,
        osVersion: String
    ): Result<SubmitFeedbackResponse> {
        return try {
            val request = SubmitFeedbackRequest(
                type = type,
                content = content,
                contact = null,
                screenshots = screenshots,
                friendNickname = friendNickname,
                traktUsername = traktUsername,
                doubanUsername = doubanUsername,
                appVersion = appVersion,
                osVersion = osVersion
            )
            val response = api.submit(request)
            val data = response.body()?.data
            if (response.isSuccessful && data != null) Result.success(data) else Result.failure(response.toFeedbackError())
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getMine(limit: Int = 20, offset: Int = 0): Result<MineResponse> {
        return try {
            val response = api.getMine(limit, offset)
            val data = response.body()?.data
            if (response.isSuccessful && data != null) { cacheStore.saveList(data); Result.success(data) } else Result.failure(response.toFeedbackError())
        } catch (e: CancellationException) { throw e } catch (e: Exception) { val cached = cacheStore.getCachedList(); if (cached != null) Result.success(cached) else Result.failure(e) }
    }

    suspend fun getDetail(id: String): Result<FeedbackDetailResponse> {
        return try {
            val response = api.getDetail(id)
            val data = response.body()?.data
            if (response.isSuccessful && data != null) { val merged = cacheStore.mergeDetail(id, data); Result.success(merged) } else Result.failure(response.toFeedbackError())
        } catch (e: CancellationException) { throw e } catch (e: Exception) { val cached = cacheStore.getCachedDetail(id); if (cached != null) Result.success(cached) else Result.failure(e) }
    }

    suspend fun markAsRead(id: String): Result<Unit> {
        return try { val response = api.markAsRead(id); if (response.isSuccessful) Result.success(Unit) else Result.failure(response.toFeedbackError()) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun markAllRead(): Result<Unit> {
        return try { val response = api.markAllRead(); if (response.isSuccessful) Result.success(Unit) else Result.failure(response.toFeedbackError()) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getUnreadCount(): Result<UnreadCountResponse> {
        return try { val response = api.getUnreadCount(); val data = response.body()?.data; if (response.isSuccessful && data != null) Result.success(data) else Result.failure(response.toFeedbackError()) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getMessages(limit: Int = 50, offset: Int = 0): Result<MessagesResponse> {
        return try {
            val response = api.getMessages(limit, offset)
            val data = response.body()?.data
            if (response.isSuccessful && data != null) {
                cacheStore.saveMessages(data)
                Result.success(data)
            } else {
                Result.failure(response.toFeedbackError())
            }
        }
        catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun reply(id: String, content: String, screenshots: List<String>): Result<ReplyResponse> {
        return try { val request = ReplyRequest(content = content, screenshots = screenshots); val response = api.reply(id, request); val data = response.body()?.data; if (response.isSuccessful && data != null) Result.success(data) else Result.failure(response.toFeedbackError()) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }

    /** 将非 2xx 响应转换为携带服务端错误码的异常，供 UI 层映射为友好文案。 */
    private fun <T : FeedbackResponse<*>> Response<T>.toFeedbackError(): FeedbackApiException {
        val errorResponse = body()
        val httpCode = code()
        return FeedbackApiException(
            errorCode = errorResponse?.code,
            httpCode = httpCode,
            message = errorResponse?.message ?: "HTTP $httpCode"
        )
    }
}
