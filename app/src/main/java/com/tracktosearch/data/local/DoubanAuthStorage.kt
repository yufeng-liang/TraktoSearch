package com.tracktosearch.data.local

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 豆瓣凭据（userId + Cookie）加密存储。
 *
 * 使用 EncryptedSharedPreferences，与 TokenStorage 同套加密方案。
 * 通过 isLoggedIn StateFlow 暴露登录态，供 UI 订阅。
 * 头像、昵称也持久化于此（登录后从 m.douban.com/people/{id}/ 抓取）。
 *
 * 启动性能：EncryptedSharedPreferences 首次创建要走 Keystore 解密（实测 100-500ms）。
 * 构造期不再同步读 prefs（Hilt 主线程建图时触发会卡启动），改为 init 里先发后台预热；
 * 全部读路径走 @Volatile 内存镜像，预热未及完成时的同步读取退化为「当场加载」，
 * 与旧行为等价，不会读错。预热只在 loaded 翻转时发布一次 StateFlow，
 * 写路径一律先 ensureLoaded 再写，保证不出现「后台旧值覆盖前台新值」。
 */
@Singleton
class DoubanAuthStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DoubanAuthStorage"
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

    private val loadMutex = Any()

    @Volatile private var loaded = false
    @Volatile private var cachedUserId: String? = null
    @Volatile private var cachedCookie: String? = null
    @Volatile private var cachedNickname: String? = null
    @Volatile private var cachedAvatar: String? = null

    // lazy 初始化 EncryptedSharedPreferences（首次访问走 Keystore 解密，见类注释）
    private val prefs by lazy { createEncryptedPreferences(context, FILE_NAME) }

    init {
        // 把 Keystore 解密挪出主线程建图：init 在 Hilt 建图（主线程）时执行，
        // 这里只发后台预热，绝不同步读 prefs
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ensureLoaded() }
    }

    /** 确保镜像已从磁盘加载。预热未及完成时在调用线程当场加载（与旧行为等价）。 */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadMutex) {
            if (loaded) return
            val p = prefs
            val userId = p.getString(KEY_USER_ID, null)
            val nickname = p.getString(KEY_NICKNAME, null)
            val avatar = p.getString(KEY_AVATAR, null)
            cachedUserId = userId
            cachedCookie = p.getString(KEY_COOKIE, null)
            val invalid = userId != null && p.getBoolean(KEY_COOKIE_INVALID, false)
            cachedNickname = nickname
            cachedAvatar = avatar
            // 只此一次发布：此后 StateFlow 只由写路径更新，后台不会再回灌磁盘旧值
            _isLoggedIn.value = userId != null
            _cookieInvalid.value = invalid
            if (userId != null && (nickname != null || avatar != null)) {
                _doubanProfile.value = DoubanUserProfile(
                    userId = userId,
                    nickname = nickname,
                    avatarUrl = avatar
                )
            }
            loaded = true
        }
    }

    /** 获取已保存的豆瓣凭据，未登录返回 null */
    fun getCredentials(): DoubanCredentials? {
        ensureLoaded()
        val userId = cachedUserId ?: return null
        val cookie = cachedCookie ?: return null
        return DoubanCredentials(userId, cookie)
    }

    /** 保存豆瓣凭据（登录成功后调用） */
    fun saveCredentials(userId: String, cookie: String) {
        ensureLoaded()
        prefs.edit().apply {
            putString(KEY_USER_ID, userId)
            putString(KEY_COOKIE, cookie)
            remove(KEY_COOKIE_INVALID)
        }.apply()
        cachedUserId = userId
        cachedCookie = cookie
        _isLoggedIn.value = true
        _cookieInvalid.value = false
    }

    /** 标记 Cookie 已失效（豆瓣返回 401/403 或登录页时调用） */
    fun markCookieInvalid() {
        ensureLoaded()
        if (cachedUserId == null) return
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
        ensureLoaded()
        val userId = cachedUserId ?: return
        prefs.edit().apply {
            if (nickname != null) putString(KEY_NICKNAME, nickname) else remove(KEY_NICKNAME)
            if (avatarUrl != null) putString(KEY_AVATAR, avatarUrl) else remove(KEY_AVATAR)
        }.apply()
        cachedNickname = nickname
        cachedAvatar = avatarUrl
        _doubanProfile.value = DoubanUserProfile(
            userId = userId,
            nickname = nickname,
            avatarUrl = avatarUrl
        )
    }

    /** 清除豆瓣凭据（退出登录或 Cookie 过期时调用） */
    fun clearCredentials() {
        ensureLoaded()
        // 按 key 删除，不清空整个 prefs 文件，避免误删其他可能的共享字段
        prefs.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_COOKIE)
            .remove(KEY_NICKNAME)
            .remove(KEY_AVATAR)
            .remove(KEY_COOKIE_INVALID)
            .apply()
        cachedUserId = null
        cachedCookie = null
        cachedNickname = null
        cachedAvatar = null
        _isLoggedIn.value = false
        _doubanProfile.value = null
        _cookieInvalid.value = false
        // 必须同步清掉全局 WebView Cookie：否则登录页 WebView 会带着残留的 dbcl2 Cookie
        // 打开豆瓣登录页，onPageFinished 一检测到 Cookie 就立即误判"登录成功"把旧账号存回，
        // 导致永远无法真正退出/换账号。CookieManager 在无 WebView 环境可能抛异常，兜底捕获。
        try {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "清除 WebView Cookie 失败: ${e.message}")
        }
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
