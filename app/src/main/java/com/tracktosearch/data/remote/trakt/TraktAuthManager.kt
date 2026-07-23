package com.tracktosearch.data.remote.trakt

import com.tracktosearch.data.auth.AuthApiService
import com.tracktosearch.data.auth.TraktOAuthCodeRequest
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktAuthManager @Inject constructor(
    private val authApiService: AuthApiService
) {
    companion object {
    }

    suspend fun buildAuthorizationUrl(): Result<String> {
        return try {
            val response = authApiService.getTraktAuthorizeUrl()
            val url = response.body()?.data?.url
            if (response.isSuccessful && !url.isNullOrBlank()) {
                Result.success(url)
            } else {
                Result.failure(Exception(response.body()?.message ?: "Authorization URL unavailable"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exchangeCodeForToken(code: String): Result<Unit> {
        return try {
            val response = authApiService.exchangeTraktCode(TraktOAuthCodeRequest(code))
            if (response.isSuccessful && response.body()?.code == "SUCCESS") {
                Result.success(Unit)
            } else {
                val body = response.body()
                Result.failure(Exception("${body?.code ?: response.code()}: ${body?.message ?: "Token exchange failed"}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshAccessToken(): Result<Unit> {
        return try {
            val response = authApiService.refreshTrakt()
            if (response.isSuccessful && response.body()?.code == "SUCCESS") {
                Result.success(Unit)
            } else {
                val body = response.body()
                Result.failure(Exception("${body?.code ?: response.code()}: ${body?.message ?: "Token refresh failed"}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
