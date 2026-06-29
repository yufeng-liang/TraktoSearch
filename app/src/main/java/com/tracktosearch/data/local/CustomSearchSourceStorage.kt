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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.customSearchSourceDataStore: DataStore<Preferences> by preferencesDataStore(name = "custom_search_sources")

@Singleton
class CustomSearchSourceStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CustomSearchSource.serializer())

    val sources: Flow<List<CustomSearchSource>> = context.customSearchSourceDataStore.data.map { prefs ->
        val jsonStr = prefs[KEY_SOURCES] ?: "[]"
        try {
            json.decodeFromString(serializer, jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }.distinctUntilChanged()

    suspend fun addSource(source: CustomSearchSource) {
        context.customSearchSourceDataStore.edit { prefs ->
            val current = getCurrentList(prefs)
            prefs[KEY_SOURCES] = json.encodeToString(serializer, current + source)
        }
    }

    suspend fun updateSource(source: CustomSearchSource) {
        context.customSearchSourceDataStore.edit { prefs ->
            val current = getCurrentList(prefs)
            val updated = current.map { if (it.id == source.id) source else it }
            prefs[KEY_SOURCES] = json.encodeToString(serializer, updated)
        }
    }

    suspend fun deleteSource(id: String) {
        context.customSearchSourceDataStore.edit { prefs ->
            val current = getCurrentList(prefs)
            prefs[KEY_SOURCES] = json.encodeToString(serializer, current.filterNot { it.id == id })
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        context.customSearchSourceDataStore.edit { prefs ->
            val current = getCurrentList(prefs)
            val updated = current.map { if (it.id == id) it.copy(enabled = enabled) else it }
            prefs[KEY_SOURCES] = json.encodeToString(serializer, updated)
        }
    }

    private fun getCurrentList(prefs: Preferences): List<CustomSearchSource> {
        val jsonStr = prefs[KEY_SOURCES] ?: "[]"
        return try {
            json.decodeFromString(serializer, jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private val KEY_SOURCES = stringPreferencesKey("sources_json")
    }
}
