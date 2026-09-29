package com.tracktosearch.data.local

import android.content.Context
import android.content.SharedPreferences
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
            val created = createEncryptedPreferences(context, "auth_encrypted")
            prefsCache = created
            created
        }
    }

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var cachedExpiresAt: Long = 0L

    @Volatile
    private var cachedDeviceId: String? = null

    @Volatile
    private var cachedLastOnlineAt: Long = 0L

    @Volatile
    private var cachedNextCheckAt: Long = 0L

    @Volatile
    private var cachedRefreshAttemptId: String? = null

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
            cachedDeviceId = p.getString(KEY_DEVICE_ID, null)
            cachedLastOnlineAt = p.getLong(KEY_LAST_ONLINE_AT, 0L)
            cachedNextCheckAt = p.getLong(KEY_NEXT_CHECK_AT, 0L)
            cachedRefreshAttemptId = p.getString(KEY_REFRESH_ATTEMPT_ID, null)
            cachedAccessToken = token
            cachedExpiresAt = expiresAt
            cacheLoaded = true
            // access_token 非空且未过期才算已登录，避免过期 token 仍显示已登录
            val nowSec = System.currentTimeMillis() / 1000
            if (!token.isNullOrEmpty() && expiresAt > nowSec) {
                _accessTokenFlow.value = token
            }
        }
    }

    suspend fun saveSessionMetadata(deviceId: String, lastOnlineAt: Long, nextCheckAt: Long) {
        val p = prefs()
        commit(p.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putLong(KEY_LAST_ONLINE_AT, lastOnlineAt)
            .putLong(KEY_NEXT_CHECK_AT, nextCheckAt))
        cachedDeviceId = deviceId
        cachedLastOnlineAt = lastOnlineAt
        cachedNextCheckAt = nextCheckAt
    }

    /** 一次性持久化完整授权会话，避免令牌轮换后只写入部分状态。 */
    suspend fun saveSession(
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        deviceId: String,
        lastOnlineAt: Long,
        nextCheckAt: Long,
    ) {
        val expiresAt = System.currentTimeMillis() / 1000 + expiresIn
        val p = prefs()
        commit(p.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putString(KEY_DEVICE_ID, deviceId)
            .putLong(KEY_LAST_ONLINE_AT, lastOnlineAt)
            .putLong(KEY_NEXT_CHECK_AT, nextCheckAt)
            .remove(KEY_REFRESH_ATTEMPT_ID))
        cachedAccessToken = accessToken
        cachedExpiresAt = expiresAt
        cachedDeviceId = deviceId
        cachedLastOnlineAt = lastOnlineAt
        cachedNextCheckAt = nextCheckAt
        cachedRefreshAttemptId = null
        cacheLoaded = true
        _accessTokenFlow.value = accessToken
    }

    fun getCachedDeviceId(): String? = cachedDeviceId

    fun getCachedLastOnlineAt(): Long = cachedLastOnlineAt

    fun getCachedNextCheckAt(): Long = cachedNextCheckAt

    suspend fun getRefreshAttemptId(): String? {
        ensureCacheLoaded()
        return cachedRefreshAttemptId
    }

    suspend fun saveRefreshAttemptId(attemptId: String) {
        val normalized = attemptId.trim()
        require(normalized.isNotEmpty()) { "Refresh attempt ID must not be blank" }
        ensureCacheLoaded()
        commit(prefs().edit().putString(KEY_REFRESH_ATTEMPT_ID, normalized))
        cachedRefreshAttemptId = normalized
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
        commit(p.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_LAST_ONLINE_AT)
            .remove(KEY_NEXT_CHECK_AT)
            .remove(KEY_REFRESH_ATTEMPT_ID))
        cachedAccessToken = null
        cachedExpiresAt = 0L
        cachedDeviceId = null
        cachedLastOnlineAt = 0L
        cachedNextCheckAt = 0L
        cachedRefreshAttemptId = null
        cacheLoaded = false
        _accessTokenFlow.value = null
    }

    private suspend fun commit(editor: SharedPreferences.Editor) {
        check(withContext(Dispatchers.IO) { editor.commit() }) {
            "Failed to persist auth session"
        }
    }

    private companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_ONLINE_AT = "last_online_at"
        private const val KEY_NEXT_CHECK_AT = "next_check_at"
        private const val KEY_REFRESH_ATTEMPT_ID = "refresh_attempt_id"
    }
}
