package com.tracktosearch.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // EncryptedSharedPreferences 创建涉及 Keystore 解密，耗时 100-500ms
    // 用 volatile + 双重检查锁，在 IO 线程首次初始化，避免阻塞主线程
    @Volatile
    private var prefsCache: SharedPreferences? = null

    private suspend fun prefs(): SharedPreferences {
        prefsCache?.let { return it }
        return withContext(Dispatchers.IO) {
            prefsCache?.let { return@withContext it }
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val created = EncryptedSharedPreferences.create(
                context,
                "auth_encrypted",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefsCache = created
            created
        }
    }

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var cachedExpiresAt: Long = 0L

    @Volatile
    private var cacheLoaded: Boolean = false

    private val _accessTokenFlow = MutableStateFlow<String?>(null)
    val accessToken: Flow<String?> = _accessTokenFlow

    // 异步加载 token，由 MainActivity 在 IO 线程调用
    suspend fun ensureCacheLoaded() {
        if (cacheLoaded) return
        withContext(Dispatchers.IO) {
            if (cacheLoaded) return@withContext
            val p = prefs()
            val token = p.getString(KEY_ACCESS_TOKEN, null)
            val expiresAt = p.getLong(KEY_EXPIRES_AT, 0L)
            cachedAccessToken = token
            cachedExpiresAt = expiresAt
            cacheLoaded = true
            if (!token.isNullOrEmpty()) {
                _accessTokenFlow.value = token
            }
        }
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {
        val expiresAt = System.currentTimeMillis() / 1000 + expiresIn
        val p = prefs()
        withContext(Dispatchers.IO) {
            p.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .apply()
        }
        cachedAccessToken = accessToken
        cachedExpiresAt = expiresAt
        cacheLoaded = true
        _accessTokenFlow.value = accessToken
    }

    suspend fun getAccessToken(): String? {
        cachedAccessToken?.let { return it }
        val p = prefs()
        return withContext(Dispatchers.IO) {
            p.getString(KEY_ACCESS_TOKEN, null).also {
                cachedAccessToken = it
            }
        }
    }

    fun getCachedAccessToken(): String? = cachedAccessToken

    suspend fun getRefreshToken(): String? {
        val p = prefs()
        return withContext(Dispatchers.IO) {
            p.getString(KEY_REFRESH_TOKEN, null)
        }
    }

    suspend fun isTokenValid(): Boolean {
        if (cacheLoaded) {
            val now = System.currentTimeMillis() / 1000
            return cachedExpiresAt > now && cachedAccessToken != null
        }
        ensureCacheLoaded()
        val now = System.currentTimeMillis() / 1000
        return cachedExpiresAt > now && cachedAccessToken != null
    }

    suspend fun clearTokens() {
        val p = prefs()
        withContext(Dispatchers.IO) {
            p.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_EXPIRES_AT)
                .apply()
        }
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
