package com.tracktosearch.data.remote.trakt

import com.tracktosearch.BuildConfig
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
        const val AUTH_URL = "https://trakt.tv/oauth/authorize"
    }

    fun buildAuthorizationUrl(): String {
        return "$AUTH_URL?response_type=code&client_id=${BuildConfig.TRAKT_CLIENT_ID}&redirect_uri=${BuildConfig.TRAKT_REDIRECT_URI}"
    }

    suspend fun exchangeCodeForToken(code: String): Result<Unit> {
        return try {
            val response = authApiService.exchangeTraktCode(TraktOAuthCodeRequest(code))
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Token exchange failed: ${response.code()}"))
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
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Token refresh failed: ${response.code()}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
