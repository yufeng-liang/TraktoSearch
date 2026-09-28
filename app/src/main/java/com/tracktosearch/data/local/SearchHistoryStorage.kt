package com.tracktosearch.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class SearchHistoryItem(val keyword: String, val type: String)

// 旧明文 DataStore，仅用于一次性迁移到加密存储后清理
private val Context.legacySearchHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_history")

@Singleton
class SearchHistoryStorage private constructor(
    private val context: Context,
    private val prefsFactory: () -> SharedPreferences
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        prefsFactory = {
            createEncryptedPreferences(context, PREFS_NAME)
        }
    )

    internal constructor(
        context: Context,
        sharedPreferences: SharedPreferences
    ) : this(context, { sharedPreferences })

    private companion object {
        // 用 String 存储有序历史（换行符分隔），避免 Set 无序导致历史顺序丢失
        const val KEY_HISTORY = "search_keywords_joined"
        const val KEY_MIGRATED = "migrated_from_datastore"
        const val MAX_HISTORY = 30
        const val SEPARATOR = "\n"
        const val PREFS_NAME = "search_history_encrypted"
    }

    // EncryptedSharedPreferences 创建涉及 Keystore 解密，耗时 100-500ms
    // 用 volatile + 双重检查锁，在 IO 线程首次初始化，避免阻塞主线程
    @Volatile
    private var prefsCache: SharedPreferences? = null

    private suspend fun prefs(): SharedPreferences {
        prefsCache?.let { return it }
        return withContext(Dispatchers.IO) {
            prefsCache?.let { return@withContext it }
            val created = prefsFactory()
            prefsCache = created
            created
        }
    }

    private val _history = MutableStateFlow<List<SearchHistoryItem>>(emptyList())
    val history: StateFlow<List<SearchHistoryItem>> = _history.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 启动时异步加载历史数据 + 迁移旧 DataStore 数据
        scope.launch {
            migrateFromLegacyIfNeeded()
            refreshFlow()
        }
    }

    /** 一次性迁移：从明文 DataStore 读取旧搜索历史写入加密 prefs，避免用户历史丢失 */
    private suspend fun migrateFromLegacyIfNeeded() {
        val p = prefs()
        if (p.getBoolean(KEY_MIGRATED, false)) return
        // 从旧 DataStore 读取
        val legacyJoined = try {
            context.legacySearchHistoryDataStore.data.first()[stringPreferencesKey("search_keywords_joined")]
        } catch (_: Exception) {
            null
        }
        if (!legacyJoined.isNullOrBlank()) {
            p.edit().putString(KEY_HISTORY, legacyJoined).apply()
        }
        // 标记迁移完成，并清理旧 DataStore
        p.edit().putBoolean(KEY_MIGRATED, true).apply()
        try {
            context.legacySearchHistoryDataStore.edit { it.clear() }
        } catch (_: Exception) {
            // 清理失败不影响功能，旧明文数据会被忽略
        }
    }

    private fun refreshFlow() {
        val joined = prefsCache?.getString(KEY_HISTORY, "") ?: ""
        _history.value = parseJoined(joined)
    }

    private fun parseJoined(joined: String): List<SearchHistoryItem> {
        if (joined.isBlank()) return emptyList()
        return joined.split(SEPARATOR).filter { it.isNotBlank() }.mapNotNull { raw ->
            if (raw.contains("::")) {
                val parts = raw.split("::", limit = 2)
                val type = parts.getOrNull(0) ?: return@mapNotNull null
                val keyword = parts.getOrNull(1) ?: return@mapNotNull null
                SearchHistoryItem(keyword = keyword, type = type)
            } else {
                SearchHistoryItem(keyword = raw, type = "disk")
            }
        }
    }

    suspend fun add(keyword: String, type: String = "disk") {
        if (keyword.isBlank()) return
        val encoded = "$type::$keyword"
        val p = prefs()
        withContext(Dispatchers.IO) {
            val current = (p.getString(KEY_HISTORY, "") ?: "")
                .split(SEPARATOR)
                .filter { it.isNotBlank() }
                .toMutableList()
            // 移除同类型同关键词的旧记录
            current.removeAll { it.endsWith("::$keyword") && it.startsWith("$type::") }
            current.remove(keyword)
            // 新记录置顶
            current.add(0, encoded)
            p.edit().putString(KEY_HISTORY, current.take(MAX_HISTORY).joinToString(SEPARATOR)).apply()
        }
        refreshFlow()
    }

    suspend fun remove(keyword: String, type: String? = null) {
        val p = prefs()
        withContext(Dispatchers.IO) {
            val current = (p.getString(KEY_HISTORY, "") ?: "")
                .split(SEPARATOR)
                .filter { it.isNotBlank() }
            val updated = current
                .filter { entry ->
                    if (type != null) {
                        // 按 (type, keyword) 复合 key 精确匹配删除，保留同 keyword 不同 type 的记录
                        entry != "$type::$keyword"
                    } else {
                        // type 为 null 时维持原行为：按 keyword 全删（兼容旧调用方）
                        !entry.endsWith("::$keyword") && entry != keyword
                    }
                }
                .joinToString(SEPARATOR)
            p.edit().putString(KEY_HISTORY, updated).apply()
        }
        refreshFlow()
    }

    suspend fun clear() {
        val p = prefs()
        withContext(Dispatchers.IO) {
            p.edit().remove(KEY_HISTORY).apply()
        }
        refreshFlow()
    }
}
