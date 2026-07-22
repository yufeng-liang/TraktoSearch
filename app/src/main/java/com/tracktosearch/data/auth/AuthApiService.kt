package com.tracktosearch.data.auth

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 授权网关 Retrofit 服务
 */
interface AuthApiService {
    @POST("api/auth/activate")
    suspend fun activate(@Body request: ActivateRequest): Response<GatewayResponse<ActivateResponse>>

    @POST("api/auth/check")
    suspend fun check(): Response<GatewayResponse<CheckResponse>>

    @POST("api/auth/challenge")
    suspend fun challenge(@Body request: ChallengeRequest): Response<GatewayResponse<ChallengeResponse>>

    @POST("api/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<GatewayResponse<RefreshResponse>>

    @POST("api/trakt/oauth/exchange")
    suspend fun exchangeTraktCode(@Body request: TraktOAuthCodeRequest): Response<GatewayResponse<GatewaySuccessResponse>>

    @POST("api/trakt/oauth/refresh")
    suspend fun refreshTrakt(): Response<GatewayResponse<GatewaySuccessResponse>>
}

// === 请求/响应 DTO ===

@Serializable
data class GatewayResponse<T>(
    val code: String,
    val message: String,
    val requestId: String = "",
    val data: T? = null
)

@Serializable
data class ActivateRequest(
    val inviteCode: String,
    val publicKey: String,
    val deviceName: String,
    val appVersion: String,
    val packageName: String
)

@Serializable
data class ActivateResponse(
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
    val nextCheckAt: Long
)

@Serializable
data class ChallengeRequest(
    val deviceId: String
)

@Serializable
data class ChallengeResponse(
    val nonce: String,
    val expiresAt: Long
)

@Serializable
data class RefreshRequest(
    val deviceId: String,
    val refreshToken: String,
    val nonce: String,
    val signature: String
)

@Serializable
data class RefreshResponse(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long
)

@Serializable
data class CheckResponse(
    val authorized: Boolean,
    val friendId: String,
    val deviceId: String,
    val nickname: String,
    val deviceStatus: String,
    val nextCheckAt: Long,
    val configVersion: Int
)

@Serializable
data class TraktOAuthCodeRequest(val code: String)

@Serializable
data class GatewaySuccessResponse(val success: Boolean = true)
