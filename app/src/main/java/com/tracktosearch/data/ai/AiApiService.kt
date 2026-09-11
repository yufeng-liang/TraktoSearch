package com.tracktosearch.data.ai

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Streaming

/** AI 网关接口。相对路径由 NetworkModule 统一挂在 /api/ai/ 下。 */
interface AiApiService {
    @GET("characters")
    suspend fun listCharacters(): Response<AiApiResponse<AiCharactersDto>>

    @POST("activate")
    suspend fun activate(@Body request: AiActivateRequest): Response<AiApiResponse<AiActivationDto>>

    @POST("greeting")
    suspend fun getGreeting(@Body request: AiGreetingRequest): Response<AiApiResponse<AiGreetingDto>>

    @POST("taste")
    suspend fun getTaste(@Body request: AiTasteRequest): Response<AiApiResponse<AiTasteDto>>

    @POST("quiz")
    suspend fun getQuiz(@Body request: AiQuizRequest): Response<AiApiResponse<AiQuizDto>>

    /**
     * 流式出题：服务端逐行返回 NDJSON 事件（阶段/进度/心跳/结果）。
     * @Streaming 让 OkHttp 不把响应体整体缓冲，配合逐行读取实现真实进度。
     */
    @Streaming
    @POST("quiz/stream")
    suspend fun getQuizStream(@Body request: AiQuizRequest): Response<ResponseBody>

    @POST("quiz/submit")
    suspend fun submitQuiz(@Body request: AiSubmitQuizRequest): Response<AiApiResponse<AiQuizResultDto>>

    @POST("quiz/feedback")
    suspend fun submitQuizFeedback(@Body request: AiQuizFeedbackRequest): Response<AiApiResponse<AiQuizFeedbackDto>>

    @POST("daily")
    suspend fun getDailyKnowledge(@Body request: AiDailyRequest): Response<AiApiResponse<AiDailyKnowledgeDto>>

    /**
     * 流式每日知识：服务端逐行返回 NDJSON 事件（阶段/进度/心跳/结果）。
     * @Streaming 让 OkHttp 不把响应体整体缓冲，配合逐行读取实现真实进度。
     * 旧版 App 仍走上面的 /daily 一次性接口，两条端点共用同一份生成与缓存。
     */
    @Streaming
    @POST("daily/stream")
    suspend fun getDailyKnowledgeStream(@Body request: AiDailyRequest): Response<ResponseBody>

    @POST("tts")
    suspend fun playTts(@Body request: AiTtsRequest): Response<AiApiResponse<AiAudioDto>>
}
