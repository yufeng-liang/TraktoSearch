package com.tracktosearch.data.remote.feedback

import com.tracktosearch.BuildConfig
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 反馈与建议 API 服务
 * 注意：baseUrl 由 NetworkModule 提供，应为 FEEDBACK_BASE_URL。
 */
interface FeedbackApiService {

    /** 上传截图，返回 R2 key（App 端拼完整 URL） */
    @Multipart
    @POST("feedback-api/upload-screenshot")
    suspend fun uploadScreenshot(@Part file: MultipartBody.Part): Response<FeedbackResponse<UploadScreenshotResponse>>

    /** 提交反馈 */
    @POST("feedback-api/submit")
    suspend fun submit(@Body request: SubmitFeedbackRequest): Response<FeedbackResponse<SubmitFeedbackResponse>>

    /** 我的反馈列表 */
    @GET("feedback-api/mine")
    suspend fun getMine(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<FeedbackResponse<MineResponse>>

    /** 单条反馈详情 */
    @GET("feedback-api/{id}")
    suspend fun getDetail(@Path("id") id: String): Response<FeedbackResponse<FeedbackDetailResponse>>
}

@Serializable
data class FeedbackResponse<T>(
    val code: String,
    val message: String,
    val requestId: String = "",
    val data: T? = null
)

@Serializable
data class UploadScreenshotResponse(val key: String)

@Serializable
data class SubmitFeedbackRequest(
    val type: String,
    val content: String,
    val contact: String? = null,
    val screenshots: List<String> = emptyList(),
    val friendNickname: String,
    val traktUsername: String? = null,
    val doubanUsername: String? = null,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String
)

@Serializable
data class SubmitFeedbackResponse(val id: String, val createdAt: Long)

@Serializable
data class MineResponse(
    val feedbacks: List<FeedbackListItem>,
    val limit: Int,
    val offset: Int,
    val total: Int,
    val hasMore: Boolean
)

@Serializable
data class FeedbackListItem(
    val id: String,
    val type: String,
    val content: String,
    val screenshots: String? = null,    // JSON 数组字符串
    val status: String,
    val created_at: Long
)

@Serializable
data class FeedbackDetailResponse(
    val feedback: FeedbackDetail,
    val replies: List<FeedbackReply>
)

@Serializable
data class FeedbackDetail(
    val id: String,
    val friend_id: String,
    val friend_nickname: String,
    val device_id: String? = null,
    val trakt_username: String? = null,
    val douban_username: String? = null,
    val type: String,
    val content: String,
    val contact: String? = null,
    val screenshots: String? = null,
    val app_version: String,
    val os_version: String,
    val device_model: String,
    val status: String,
    val created_at: Long
)

@Serializable
data class FeedbackReply(
    val id: String,
    val content: String,
    val created_at: Long
)

/**
 * 截图 URL 拼接 helper。
 * feedback-worker 暴露 GET /feedback-api/screenshot/{key} 从 R2 读取，
 * App 端把 key 拼成完整 URL 供 Coil 加载。
 */
fun screenshotUrl(key: String): String {
    return BuildConfig.FEEDBACK_BASE_URL.trimEnd('/') + "/feedback-api/screenshot/" + key
}
