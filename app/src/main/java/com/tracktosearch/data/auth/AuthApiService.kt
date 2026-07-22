package com.tracktosearch.data.auth

import com.tracktosearch.BuildConfig
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 授权网关 Retrofit 服务
 */
interface AuthApiService {
    @POST("api/auth/activate")
    suspend fun activate(@Body request: ActivateRequest): Response<ActivateResponse>

    @POST("api/auth/check")
    suspend fun check(): Response<CheckResponse>

    @POST("api/auth/challenge")
    suspend fun challenge(@Body request: ChallengeRequest): Response<ChallengeResponse>

    @POST("api/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<RefreshResponse>

    @POST("api/trakt/oauth/exchange")
    suspend fun exchangeTraktCode(@Body request: TraktOAuthCodeRequest): Response<GatewaySuccessResponse>

    @POST("api/trakt/oauth/refresh")
    suspend fun refreshTrakt(): Response<GatewaySuccessResponse>
}

// === 请求/响应 DTO ===

data class ActivateRequest(
    val inviteCode: String,
    val publicKey: String,
    val deviceName: String,
    val appVersion: String,
    val packageName: String
)

data class ActivateResponse(
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
    val nextCheckAt: Long
)

data class ChallengeRequest(
    val deviceId: String
)

data class ChallengeResponse(
    val nonce: String,
    val expiresAt: Long
)

data class RefreshRequest(
    val deviceId: String,
    val refreshToken: String,
    val nonce: String,
    val signature: String
)

data class RefreshResponse(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long
)

data class CheckResponse(
    val authorized: Boolean,
    val friendId: String,
    val deviceId: String,
    val nickname: String,
    val deviceStatus: String,
    val nextCheckAt: Long,
    val configVersion: Int
)

data class TraktOAuthCodeRequest(val code: String)

data class GatewaySuccessResponse(val code: String, val message: String)
