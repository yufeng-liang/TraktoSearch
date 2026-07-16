package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class SearchHistoryItem(val keyword: String, val type: String)

private val Context.searchHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_history")

@Singleton
class SearchHistoryStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        // 用 String 存储有序历史（换行符分隔），避免 Set 无序导致历史顺序丢失
        val KEY_HISTORY = stringPreferencesKey("search_keywords_joined")
        const val MAX_HISTORY = 30
        const val SEPARATOR = "\n"
    }

    val history: Flow<List<SearchHistoryItem>> = context.searchHistoryDataStore.data.map { prefs ->
        val joined = prefs[KEY_HISTORY] ?: ""
        if (joined.isBlank()) {
            emptyList()
        } else {
            joined.split(SEPARATOR).filter { it.isNotBlank() }.map { raw ->
                if (raw.contains("::")) {
                    val parts = raw.split("::", limit = 2)
                    SearchHistoryItem(keyword = parts[1], type = parts[0])
                } else {
                    SearchHistoryItem(keyword = raw, type = "disk")
                }
            }
        }
    }.distinctUntilChanged()

    suspend fun add(keyword: String, type: String = "disk") {
        if (keyword.isBlank()) return
        val encoded = "$type::$keyword"
        context.searchHistoryDataStore.edit { prefs ->
            val current = (prefs[KEY_HISTORY] ?: "")
                .split(SEPARATOR)
                .filter { it.isNotBlank() }
                .toMutableList()
            // 移除同类型同关键词的旧记录
            current.removeAll { it.endsWith("::$keyword") && it.startsWith("$type::") }
            current.remove(keyword)
            // 新记录置顶
            current.add(0, encoded)
            prefs[KEY_HISTORY] = current.take(MAX_HISTORY).joinToString(SEPARATOR)
        }
    }

    suspend fun remove(keyword: String, type: String? = null) {
        context.searchHistoryDataStore.edit { prefs ->
            val current = (prefs[KEY_HISTORY] ?: "")
                .split(SEPARATOR)
                .filter { it.isNotBlank() }
            prefs[KEY_HISTORY] = current
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
        }
    }

    suspend fun clear() {
        context.searchHistoryDataStore.edit { prefs ->
            prefs.remove(KEY_HISTORY)
        }
    }
}
