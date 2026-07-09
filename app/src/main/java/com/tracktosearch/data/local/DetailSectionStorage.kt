package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
import javax.inject.Inject
import javax.inject.Singleton

private val Context.detailSectionDataStore: DataStore<Preferences> by preferencesDataStore(name = "detail_sections")

data class DetailSectionConfig(
    val id: String,
    val visible: Boolean
)

@Singleton
class DetailSectionStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // 所有详情页模块 ID（默认顺序）
        val ALL_SECTION_IDS = listOf(
            "cast",
            "videos-images",
            "overview",
            "my-rating",
            "comments",
            "recommendations"
        )

        private fun visibilityKey(id: String) = booleanPreferencesKey("visible_$id")
    }

    /** 默认配置（全部可见） */
    private val defaultConfigs: List<DetailSectionConfig> =
        ALL_SECTION_IDS.map { id -> DetailSectionConfig(id = id, visible = true) }

    private val _sectionConfigs = MutableStateFlow(defaultConfigs)
    val sectionConfigs: StateFlow<List<DetailSectionConfig>> = _sectionConfigs.asStateFlow()

    init {
        // 预加载:从 DataStore 读取首值填入 StateFlow,消除 stateIn 默认值跳变
        scope.launch {
            val prefs = context.detailSectionDataStore.data.first()
            _sectionConfigs.value = readConfigs(prefs)
        }
    }

    private fun readConfigs(prefs: Preferences): List<DetailSectionConfig> {
        return ALL_SECTION_IDS.map { id ->
            DetailSectionConfig(
                id = id,
                visible = prefs[visibilityKey(id)] ?: true
            )
        }
    }

    suspend fun setSectionVisible(id: String, visible: Boolean) {
        context.detailSectionDataStore.edit { prefs ->
            prefs[visibilityKey(id)] = visible
        }
        _sectionConfigs.value = _sectionConfigs.value.map {
            if (it.id == id) it.copy(visible = visible) else it
        }
    }
}
