package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
        val KEY_HISTORY = stringSetPreferencesKey("search_keywords")
        const val MAX_HISTORY = 30
    }

    val history: Flow<List<SearchHistoryItem>> = context.searchHistoryDataStore.data.map { prefs ->
        (prefs[KEY_HISTORY] ?: emptySet()).map { raw ->
            if (raw.contains("::")) {
                val parts = raw.split("::", limit = 2)
                SearchHistoryItem(keyword = parts[1], type = parts[0])
            } else {
                SearchHistoryItem(keyword = raw, type = "disk")
            }
        }
    }.distinctUntilChanged()

    suspend fun add(keyword: String, type: String = "disk") {
        if (keyword.isBlank()) return
        val encoded = "$type::$keyword"
        context.searchHistoryDataStore.edit { prefs ->
            val current = (prefs[KEY_HISTORY] ?: emptySet()).toMutableList()
            current.removeAll { it.endsWith("::$keyword") && it.startsWith("$type::") }
            current.remove(keyword)
            current.add(0, encoded)
            if (current.size > MAX_HISTORY) {
                prefs[KEY_HISTORY] = current.take(MAX_HISTORY).toSet()
            } else {
                prefs[KEY_HISTORY] = current.toSet()
            }
        }
    }

    suspend fun remove(keyword: String) {
        context.searchHistoryDataStore.edit { prefs ->
            val current = prefs[KEY_HISTORY] ?: emptySet()
            prefs[KEY_HISTORY] = current.filter { !it.endsWith("::$keyword") && it != keyword }.toSet()
        }
    }

    suspend fun clear() {
        context.searchHistoryDataStore.edit { prefs ->
            prefs.remove(KEY_HISTORY)
        }
    }
}
