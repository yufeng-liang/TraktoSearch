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
 * 头像、昵称也持久化于此（登录后从 m.douban.com/people/{id}/ 抓取）。
 */
@Singleton
class DoubanAuthStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val FILE_NAME = "douban_auth_encrypted"
        private const val KEY_USER_ID = "douban_user_id"
        private const val KEY_COOKIE = "douban_cookie"
        private const val KEY_NICKNAME = "douban_nickname"
        private const val KEY_AVATAR = "douban_avatar"
        private const val KEY_COOKIE_INVALID = "douban_cookie_invalid"
    }

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    /**
     * Cookie 是否已失效（登录态还在，但豆瓣已不认这份 Cookie）。
     *
     * 用于让账号卡在「已登录」和「连接失效」之间可区分：以前 Cookie 过期后账号卡仍渲染头像
     * 加昵称加退出按钮，与正常已登录完全一样，失效信息只在一致性检查弹窗里出现。
     * 持久化，跨重启保留，直到重新登录或退出登录。
     */
    private val _cookieInvalid = MutableStateFlow(false)
    val cookieInvalid: StateFlow<Boolean> = _cookieInvalid.asStateFlow()

    private val _doubanProfile = MutableStateFlow<DoubanUserProfile?>(null)
    val doubanProfile: StateFlow<DoubanUserProfile?> = _doubanProfile.asStateFlow()

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
        val userId = prefs.getString(KEY_USER_ID, null)
        _isLoggedIn.value = userId != null
        _cookieInvalid.value = userId != null && prefs.getBoolean(KEY_COOKIE_INVALID, false)
        // 同步恢复已保存的用户头像/昵称（未抓取过则为 null）
        if (userId != null) {
            val nickname = prefs.getString(KEY_NICKNAME, null)
            val avatar = prefs.getString(KEY_AVATAR, null)
            if (nickname != null || avatar != null) {
                _doubanProfile.value = DoubanUserProfile(
                    userId = userId,
                    nickname = nickname,
                    avatarUrl = avatar
                )
            }
        }
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
            remove(KEY_COOKIE_INVALID)
        }.apply()
        _isLoggedIn.value = true
        _cookieInvalid.value = false
    }

    /** 标记 Cookie 已失效（豆瓣返回 401/403 或登录页时调用） */
    fun markCookieInvalid() {
        if (prefs.getString(KEY_USER_ID, null) == null) return
        prefs.edit().putBoolean(KEY_COOKIE_INVALID, true).apply()
        _cookieInvalid.value = true
    }

    /** 标记 Cookie 仍然有效（带 Cookie 的请求成功后调用），清掉之前的失效标记 */
    fun markCookieValid() {
        if (!_cookieInvalid.value) return
        prefs.edit().remove(KEY_COOKIE_INVALID).apply()
        _cookieInvalid.value = false
    }

    /** 保存豆瓣用户资料（头像/昵称抓取成功后调用），需先登录 */
    fun saveUserProfile(nickname: String?, avatarUrl: String?) {
        val userId = prefs.getString(KEY_USER_ID, null) ?: return
        prefs.edit().apply {
            if (nickname != null) putString(KEY_NICKNAME, nickname) else remove(KEY_NICKNAME)
            if (avatarUrl != null) putString(KEY_AVATAR, avatarUrl) else remove(KEY_AVATAR)
        }.apply()
        _doubanProfile.value = DoubanUserProfile(
            userId = userId,
            nickname = nickname,
            avatarUrl = avatarUrl
        )
    }

    /** 清除豆瓣凭据（退出登录或 Cookie 过期时调用） */
    fun clearCredentials() {
        // 按 key 删除，不清空整个 prefs 文件，避免误删其他可能的共享字段
        prefs.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_COOKIE)
            .remove(KEY_NICKNAME)
            .remove(KEY_AVATAR)
            .remove(KEY_COOKIE_INVALID)
            .apply()
        _isLoggedIn.value = false
        _doubanProfile.value = null
        _cookieInvalid.value = false
    }
}

/** 豆瓣登录凭据 */
data class DoubanCredentials(val userId: String, val cookie: String)

/** 豆瓣用户资料（用于设置页账户区展示） */
data class DoubanUserProfile(
    val userId: String,
    val nickname: String?,
    val avatarUrl: String?
)
