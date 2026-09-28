package com.tracktosearch.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// 旧明文 DataStore，仅用于一次性迁移到加密存储后清理
private val Context.legacyViewedDataStore: DataStore<Preferences> by preferencesDataStore(name = "viewed")

@Singleton
class ViewedItemStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val KEY_VIEWED_URLS = "viewed_urls"
        const val KEY_MIGRATED = "migrated_from_datastore"
        const val PREFS_NAME = "viewed_encrypted"
    }

    // EncryptedSharedPreferences 创建涉及 Keystore 解密，耗时 100-500ms
    @Volatile
    private var prefsCache: SharedPreferences? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedUrls: Set<String> = emptySet()

    // 内存缓存锁，防止 markViewed 并发时 read-modify-write 丢更新
    private val cacheLock = Any()

    private val _viewedUrls = MutableStateFlow<Set<String>>(emptySet())
    val viewedUrls: StateFlow<Set<String>> = _viewedUrls.asStateFlow()

    init {
        scope.launch {
            migrateFromLegacyIfNeeded()
            loadCache()
        }
    }

    private suspend fun prefs(): SharedPreferences {
        prefsCache?.let { return it }
        return withContext(Dispatchers.IO) {
            prefsCache?.let { return@withContext it }
            val created = createEncryptedPreferences(context, PREFS_NAME)
            prefsCache = created
            created
        }
    }

    /** 一次性迁移：从明文 DataStore 读取旧浏览历史写入加密 prefs */
    private suspend fun migrateFromLegacyIfNeeded() {
        val p = prefs()
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val legacyUrls = try {
            context.legacyViewedDataStore.data.first()[stringSetPreferencesKey("viewed_urls")]
        } catch (_: Exception) {
            null
        }
        if (!legacyUrls.isNullOrEmpty()) {
            p.edit().putStringSet(KEY_VIEWED_URLS, legacyUrls).apply()
        }
        p.edit().putBoolean(KEY_MIGRATED, true).apply()
        try {
            context.legacyViewedDataStore.edit { it.clear() }
        } catch (_: Exception) {
            // 清理失败不影响功能
        }
    }

    private suspend fun loadCache() {
        val p = prefs()
        val urls = withContext(Dispatchers.IO) {
            p.getStringSet(KEY_VIEWED_URLS, emptySet()) ?: emptySet()
        }
        synchronized(cacheLock) { cachedUrls = urls }
        _viewedUrls.value = urls
    }

    suspend fun getViewedUrls(): Set<String> {
        prefsCache?.let {
            // 已初始化，直接返回内存缓存
            return cachedUrls
        }
        // 未初始化，等待初始化完成
        prefs()
        loadCache()
        return cachedUrls
    }

    fun isViewedSync(url: String): Boolean {
        return cachedUrls.contains(url)
    }

    suspend fun markViewed(url: String) {
        val p = prefs()
        withContext(Dispatchers.IO) {
            val current = p.getStringSet(KEY_VIEWED_URLS, emptySet()) ?: emptySet()
            val updated = current + url
            p.edit().putStringSet(KEY_VIEWED_URLS, updated).apply()
        }
        // 同步更新内存缓存，避免并发 markViewed 时丢更新
        synchronized(cacheLock) {
            cachedUrls = cachedUrls + url
        }
        _viewedUrls.value = cachedUrls
    }
}
