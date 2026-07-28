package com.tracktosearch.data.repository

import com.tracktosearch.data.remote.feedback.FeedbackApiService
import com.tracktosearch.data.remote.feedback.SubmitFeedbackRequest
import com.tracktosearch.data.remote.feedback.SubmitFeedbackResponse
import com.tracktosearch.data.remote.feedback.MineResponse
import com.tracktosearch.data.remote.feedback.FeedbackDetailResponse
import com.tracktosearch.data.remote.feedback.UploadScreenshotResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 反馈与建议 Repository
 * - 提交反馈、上传截图、查询我的反馈、查询详情
 */
@Singleton
class FeedbackRepository @Inject constructor(
    private val api: FeedbackApiService
) {
    /** 上传截图，返回 R2 key */
    suspend fun uploadScreenshot(bytes: ByteArray, mimeType: String, fileName: String = "screenshot.jpg"): Result<String> {
        return try {
            val body = bytes.toRequestBody(mimeType.toMediaType())
            val part = MultipartBody.Part.createFormData("file", fileName, body)
            val response = api.uploadScreenshot(part)
            if (response.isSuccessful) {
                val key = response.body()?.data?.key
                if (key != null) Result.success(key)
                else Result.failure(IllegalStateException("Empty response"))
            } else {
                Result.failure(IllegalStateException("HTTP ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 提交反馈 */
    suspend fun submit(
        type: String,
        content: String,
        contact: String?,
        screenshots: List<String>,
        friendNickname: String,
        traktUsername: String?,
        doubanUsername: String?,
        appVersion: String,
        osVersion: String,
        deviceModel: String
    ): Result<SubmitFeedbackResponse> {
        return try {
            val request = SubmitFeedbackRequest(
                type = type,
                content = content,
                contact = contact,
                screenshots = screenshots,
                friendNickname = friendNickname,
                traktUsername = traktUsername,
                doubanUsername = doubanUsername,
                appVersion = appVersion,
                osVersion = osVersion,
                deviceModel = deviceModel
            )
            val response = api.submit(request)
            val data = response.body()?.data
            if (response.isSuccessful && data != null) Result.success(data)
            else Result.failure(IllegalStateException(response.body()?.message ?: "HTTP ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 我的反馈列表 */
    suspend fun getMine(limit: Int = 20, offset: Int = 0): Result<MineResponse> {
        return try {
            val response = api.getMine(limit, offset)
            val data = response.body()?.data
            if (response.isSuccessful && data != null) Result.success(data)
            else Result.failure(IllegalStateException(response.body()?.message ?: "HTTP ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 单条详情 */
    suspend fun getDetail(id: String): Result<FeedbackDetailResponse> {
        return try {
            val response = api.getDetail(id)
            val data = response.body()?.data
            if (response.isSuccessful && data != null) Result.success(data)
            else Result.failure(IllegalStateException(response.body()?.message ?: "HTTP ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
