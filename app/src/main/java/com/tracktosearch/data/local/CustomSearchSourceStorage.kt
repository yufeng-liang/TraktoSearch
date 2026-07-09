package com.tracktosearch.data.local

import android.content.Context
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.customSearchSourceDataStore: DataStore<Preferences> by preferencesDataStore(name = "custom_search_sources")

@Singleton
class CustomSearchSourceStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CustomSearchSource.serializer())

    private val _sources = MutableStateFlow<List<CustomSearchSource>>(emptyList())
    val sources: StateFlow<List<CustomSearchSource>> = _sources.asStateFlow()

    init {
        // 预加载:从 DataStore 读取首值填入 StateFlow,消除 stateIn 默认值跳变
        scope.launch {
            val prefs = context.customSearchSourceDataStore.data.first()
            _sources.value = getCurrentList(prefs)
        }
    }

    suspend fun addSource(source: CustomSearchSource) {
        context.customSearchSourceDataStore.edit { prefs ->
            val current = getCurrentList(prefs)
            prefs[KEY_SOURCES] = json.encodeToString(serializer, current + source)
        }
        _sources.value = _sources.value + source
    }

    suspend fun updateSource(source: CustomSearchSource) {
        context.customSearchSourceDataStore.edit { prefs ->
            val current = getCurrentList(prefs)
            val updated = current.map { if (it.id == source.id) source else it }
            prefs[KEY_SOURCES] = json.encodeToString(serializer, updated)
        }
        _sources.value = _sources.value.map { if (it.id == source.id) source else it }
    }

    suspend fun deleteSource(id: String) {
        context.customSearchSourceDataStore.edit { prefs ->
            val current = getCurrentList(prefs)
            prefs[KEY_SOURCES] = json.encodeToString(serializer, current.filterNot { it.id == id })
        }
        _sources.value = _sources.value.filterNot { it.id == id }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        context.customSearchSourceDataStore.edit { prefs ->
            val current = getCurrentList(prefs)
            val updated = current.map { if (it.id == id) it.copy(enabled = enabled) else it }
            prefs[KEY_SOURCES] = json.encodeToString(serializer, updated)
        }
        _sources.value = _sources.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
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
