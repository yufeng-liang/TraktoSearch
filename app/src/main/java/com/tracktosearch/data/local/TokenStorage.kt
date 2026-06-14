package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

@Singleton
class TokenStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val KEY_EXPIRES_AT = longPreferencesKey("expires_at")
    }

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var cachedExpiresAt: Long = 0L

    @Volatile
    private var cacheLoaded: Boolean = false

    val accessToken: Flow<String?> = context.dataStore.data.map { it[KEY_ACCESS_TOKEN] }

    suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {
        val expiresAt = System.currentTimeMillis() / 1000 + expiresIn
        context.dataStore.edit { prefs ->
            prefs[KEY_ACCESS_TOKEN] = accessToken
            prefs[KEY_REFRESH_TOKEN] = refreshToken
            prefs[KEY_EXPIRES_AT] = expiresAt
        }
        cachedAccessToken = accessToken
        cachedExpiresAt = expiresAt
        cacheLoaded = true
    }

    suspend fun getAccessToken(): String? {
        cachedAccessToken?.let { return it }
        return context.dataStore.data.map { it[KEY_ACCESS_TOKEN] }.first().also {
            cachedAccessToken = it
        }
    }

    // 同步获取缓存的 token（不触发 IO，用于 OkHttp 拦截器）
    fun getCachedAccessToken(): String? = cachedAccessToken

    suspend fun getRefreshToken(): String? {
        return context.dataStore.data.map { it[KEY_REFRESH_TOKEN] }.first()
    }

    suspend fun isTokenValid(): Boolean {
        if (cacheLoaded) {
            val now = System.currentTimeMillis() / 1000
            return cachedExpiresAt > now && cachedAccessToken != null
        }
        // 一次读取所有字段，避免多次 IO
        val prefs = context.dataStore.data.first()
        val expiresAt = prefs[KEY_EXPIRES_AT] ?: 0L
        val token = prefs[KEY_ACCESS_TOKEN]
        cachedExpiresAt = expiresAt
        cachedAccessToken = token
        cacheLoaded = true
        val now = System.currentTimeMillis() / 1000
        return expiresAt > now && token != null
    }

    suspend fun clearTokens() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_ACCESS_TOKEN)
            prefs.remove(KEY_REFRESH_TOKEN)
            prefs.remove(KEY_EXPIRES_AT)
        }
        cachedAccessToken = null
        cachedExpiresAt = 0L
        cacheLoaded = false
    }
}
