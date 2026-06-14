package com.tracktosearch.data.remote.trakt

import com.tracktosearch.BuildConfig
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.remote.trakt.dto.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktAuthManager @Inject constructor(
    private val tokenStorage: TokenStorage
) {
    companion object {
        const val AUTH_URL = "https://trakt.tv/oauth/authorize"
        const val TOKEN_URL = "https://trakt.tv/oauth/token"
        const val API_BASE_URL = "https://api.trakt.tv/"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val traktApiService: TraktApiService by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        retrofit.create(TraktApiService::class.java)
    }

    fun buildAuthorizationUrl(): String {
        return "$AUTH_URL?response_type=code&client_id=${BuildConfig.TRAKT_CLIENT_ID}&redirect_uri=${BuildConfig.TRAKT_REDIRECT_URI}"
    }

    suspend fun exchangeCodeForToken(code: String): Result<TraktTokenResponse> {
        return try {
            val request = TraktTokenRequest(
                code = code,
                client_id = BuildConfig.TRAKT_CLIENT_ID,
                client_secret = BuildConfig.TRAKT_CLIENT_SECRET,
                redirect_uri = BuildConfig.TRAKT_REDIRECT_URI
            )
            val response = traktApiService.exchangeCodeForToken(request)
            if (response.isSuccessful) {
                val tokenResponse = response.body()!!
                tokenStorage.saveTokens(
                    tokenResponse.access_token,
                    tokenResponse.refresh_token,
                    tokenResponse.expires_in
                )
                Result.success(tokenResponse)
            } else {
                Result.failure(Exception("Token exchange failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshAccessToken(): Result<TraktTokenResponse> {
        return try {
            val refreshToken = tokenStorage.getRefreshToken()
                ?: return Result.failure(Exception("No refresh token available"))

            val request = TraktRefreshTokenRequest(
                refresh_token = refreshToken,
                client_id = BuildConfig.TRAKT_CLIENT_ID,
                client_secret = BuildConfig.TRAKT_CLIENT_SECRET,
                redirect_uri = BuildConfig.TRAKT_REDIRECT_URI
            )
            val response = traktApiService.refreshToken(request)
            if (response.isSuccessful) {
                val tokenResponse = response.body()!!
                tokenStorage.saveTokens(
                    tokenResponse.access_token,
                    tokenResponse.refresh_token,
                    tokenResponse.expires_in
                )
                Result.success(tokenResponse)
            } else {
                tokenStorage.clearTokens()
                Result.failure(Exception("Token refresh failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
