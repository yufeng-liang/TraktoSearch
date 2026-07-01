package com.tracktosearch.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "auth_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var cachedExpiresAt: Long = 0L

    @Volatile
    private var cacheLoaded: Boolean = false

    private val _accessTokenFlow = MutableStateFlow<String?>(null)
    val accessToken: Flow<String?> = _accessTokenFlow

    init {
        // 启动时从磁盘加载 token，确保 Flow 初始值正确
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        cachedAccessToken = token
        cachedExpiresAt = expiresAt
        cacheLoaded = true
        if (!token.isNullOrEmpty()) {
            _accessTokenFlow.value = token
        }
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {
        val expiresAt = System.currentTimeMillis() / 1000 + expiresIn
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .apply()
        cachedAccessToken = accessToken
        cachedExpiresAt = expiresAt
        cacheLoaded = true
        _accessTokenFlow.value = accessToken
    }

    suspend fun getAccessToken(): String? {
        cachedAccessToken?.let { return it }
        return prefs.getString(KEY_ACCESS_TOKEN, null).also {
            cachedAccessToken = it
        }
    }

    fun getCachedAccessToken(): String? = cachedAccessToken

    suspend fun getRefreshToken(): String? {
        return prefs.getString(KEY_REFRESH_TOKEN, null)
    }

    suspend fun isTokenValid(): Boolean {
        if (cacheLoaded) {
            val now = System.currentTimeMillis() / 1000
            return cachedExpiresAt > now && cachedAccessToken != null
        }
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        cachedExpiresAt = expiresAt
        cachedAccessToken = token
        cacheLoaded = true
        val now = System.currentTimeMillis() / 1000
        return expiresAt > now && token != null
    }

    suspend fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
        cachedAccessToken = null
        cachedExpiresAt = 0L
        cacheLoaded = false
        _accessTokenFlow.value = null
    }

    private companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
    }
}
