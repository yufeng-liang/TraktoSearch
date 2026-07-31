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

interface FeedbackApiService {
    @Multipart
    @POST("feedback-api/upload-screenshot")
    suspend fun uploadScreenshot(@Part file: MultipartBody.Part): Response<FeedbackResponse<UploadScreenshotResponse>>

    @POST("feedback-api/submit")
    suspend fun submit(@Body request: SubmitFeedbackRequest): Response<FeedbackResponse<SubmitFeedbackResponse>>

    @GET("feedback-api/mine")
    suspend fun getMine(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<FeedbackResponse<MineResponse>>

    @GET("feedback-api/{id}")
    suspend fun getDetail(@Path("id") id: String): Response<FeedbackResponse<FeedbackDetailResponse>>

    @POST("feedback-api/{id}/read")
    suspend fun markAsRead(@Path("id") id: String): Response<FeedbackResponse<Unit>>

    @POST("feedback-api/read-all")
    suspend fun markAllRead(): Response<FeedbackResponse<Unit>>

    @GET("feedback-api/unread-count")
    suspend fun getUnreadCount(): Response<FeedbackResponse<UnreadCountResponse>>

    @GET("feedback-api/messages")
    suspend fun getMessages(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<FeedbackResponse<MessagesResponse>>

    @POST("feedback-api/{id}/reply")
    suspend fun reply(
        @Path("id") id: String,
        @Body request: ReplyRequest
    ): Response<FeedbackResponse<ReplyResponse>>
}

@Serializable
data class FeedbackResponse<T>(val code: String, val message: String, val requestId: String = "", val data: T? = null)

@Serializable
data class UploadScreenshotResponse(val key: String)

@Serializable
data class SubmitFeedbackRequest(val type: String, val content: String, val contact: String? = null, val screenshots: List<String> = emptyList(), val friendNickname: String, val traktUsername: String? = null, val doubanUsername: String? = null, val appVersion: String, val osVersion: String, val deviceModel: String)

@Serializable
data class SubmitFeedbackResponse(val id: String, val createdAt: Long, val displayId: String = "")

@Serializable
data class MineResponse(val feedbacks: List<FeedbackListItem>, val limit: Int, val offset: Int, val total: Int, val hasMore: Boolean)

@Serializable
data class FeedbackListItem(val id: String, val type: String, val content: String, val screenshots: String? = null, val status: String, val created_at: Long, val display_id: String = "")

@Serializable
data class FeedbackDetailResponse(val feedback: FeedbackDetail, val replies: List<FeedbackReply>)

@Serializable
data class FeedbackDetail(val id: String, val friend_id: String, val friend_nickname: String, val device_id: String? = null, val trakt_username: String? = null, val douban_username: String? = null, val type: String, val content: String, val contact: String? = null, val screenshots: String? = null, val app_version: String, val os_version: String, val device_model: String, val status: String, val created_at: Long, val display_id: String = "", val last_read_at: Long = 0)

@Serializable
data class FeedbackReply(val id: String, val content: String, val created_at: Long, val author_role: String = "developer", val screenshots: String? = null)

@Serializable
data class UnreadCountResponse(val count: Int, val items: List<UnreadItem> = emptyList())

@Serializable
data class UnreadItem(val feedback_id: String, val display_id: String, val type: String, val last_reply: UnreadLastReply)

@Serializable
data class UnreadLastReply(val id: String, val content: String, val created_at: Long, val has_screenshot: Boolean)

@Serializable
data class MessagesResponse(val messages: List<MessageItem>, val limit: Int, val offset: Int, val total: Int, val hasMore: Boolean)

@Serializable
data class MessageItem(val id: String, val feedback_id: String, val display_id: String, val type: String, val author_role: String, val content: String, val screenshots: String? = null, val created_at: Long, val is_unread: Boolean)

@Serializable
data class ReplyRequest(val content: String, val screenshots: List<String> = emptyList())

@Serializable
data class ReplyResponse(val reply: FeedbackReply, val status: String)

fun screenshotUrl(key: String): String {
    return BuildConfig.FEEDBACK_BASE_URL.trimEnd('/') + "/feedback-api/screenshot/" + key
}
