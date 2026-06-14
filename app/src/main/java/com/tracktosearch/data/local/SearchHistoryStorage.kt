package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.searchHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_history")

@Singleton
class SearchHistoryStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        val KEY_HISTORY = stringSetPreferencesKey("search_keywords")
        const val MAX_HISTORY = 30
    }

    val history: Flow<List<String>> = context.searchHistoryDataStore.data.map { prefs ->
        (prefs[KEY_HISTORY] ?: emptySet()).toList()
    }

    suspend fun add(keyword: String) {
        if (keyword.isBlank()) return
        context.searchHistoryDataStore.edit { prefs ->
            val current = (prefs[KEY_HISTORY] ?: emptySet()).toMutableList()
            current.remove(keyword)
            current.add(0, keyword)
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
            prefs[KEY_HISTORY] = current - keyword
        }
    }

    suspend fun clear() {
        context.searchHistoryDataStore.edit { prefs ->
            prefs.remove(KEY_HISTORY)
        }
    }
}
