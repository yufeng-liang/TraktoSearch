package com.tracktosearch.data.ai

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

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

    @POST("quiz/submit")
    suspend fun submitQuiz(@Body request: AiSubmitQuizRequest): Response<AiApiResponse<AiQuizResultDto>>

    @POST("quiz/feedback")
    suspend fun submitQuizFeedback(@Body request: AiQuizFeedbackRequest): Response<AiApiResponse<AiQuizFeedbackDto>>

    @POST("daily")
    suspend fun getDailyKnowledge(@Body request: AiDailyRequest): Response<AiApiResponse<AiDailyKnowledgeDto>>

    @POST("tts")
    suspend fun playTts(@Body request: AiTtsRequest): Response<AiApiResponse<AiAudioDto>>
}
