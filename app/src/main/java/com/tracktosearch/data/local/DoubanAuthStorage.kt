package com.tracktosearch.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 豆瓣凭据（userId + Cookie）加密存储。
 *
 * 使用 EncryptedSharedPreferences，与 TokenStorage 同套加密方案。
 * 通过 isLoggedIn StateFlow 暴露登录态，供 UI 订阅。
 */
@Singleton
class DoubanAuthStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val FILE_NAME = "douban_auth_encrypted"
        private const val KEY_USER_ID = "douban_user_id"
        private const val KEY_COOKIE = "douban_cookie"
    }

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    // lazy 初始化 EncryptedSharedPreferences，避免主线程阻塞（首次访问在 IO 线程或后台）
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, FILE_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        // 初始化时同步读取登录态（prefs 首次访问会触发 Keystore 解密，但 init 在注入时即调用，
        // 通常在 Application 创建期间，不在主线程关键路径上）
        _isLoggedIn.value = prefs.getString(KEY_USER_ID, null) != null
    }

    /** 获取已保存的豆瓣凭据，未登录返回 null */
    fun getCredentials(): DoubanCredentials? {
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val cookie = prefs.getString(KEY_COOKIE, null) ?: return null
        return DoubanCredentials(userId, cookie)
    }

    /** 保存豆瓣凭据（登录成功后调用） */
    fun saveCredentials(userId: String, cookie: String) {
        prefs.edit().apply {
            putString(KEY_USER_ID, userId)
            putString(KEY_COOKIE, cookie)
        }.apply()
        _isLoggedIn.value = true
    }

    /** 清除豆瓣凭据（退出登录或 Cookie 过期时调用） */
    fun clearCredentials() {
        prefs.edit().clear().apply()
        _isLoggedIn.value = false
    }
}

/** 豆瓣登录凭据 */
data class DoubanCredentials(val userId: String, val cookie: String)
