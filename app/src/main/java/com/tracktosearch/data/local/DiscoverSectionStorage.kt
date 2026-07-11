package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import javax.inject.Inject
import javax.inject.Singleton

private val Context.discoverSectionDataStore: DataStore<Preferences> by preferencesDataStore(name = "discover_sections")

data class DiscoverSectionConfig(
    val id: String,
    val visible: Boolean,
    val order: Int
)

@Singleton
class DiscoverSectionStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val SECTION_ID_DOUBAN_RECOMMEND = "douban-recommend"

        // 所有发现页栏目 ID（默认顺序）
        val ALL_SECTION_IDS = listOf(
            SECTION_ID_DOUBAN_RECOMMEND,
            "douban-movie",
            "douban-weekly",
            "douban-top250",
            "douban-nowplaying",
            "tmdb-popular",
            "tmdb-upcoming",
            "trakt-trending-movies",
            "trakt-trending-shows",
            "trakt-anticipated",
            "trakt-recommendations",
            "trakt-show-recommendations",
            "trakt-lists"
        )

        private val KEY_ORDER = stringPreferencesKey("section_order")
        private fun visibilityKey(id: String) = booleanPreferencesKey("visible_$id")
    }

    /** 默认配置（全部可见，默认顺序） */
    private val defaultConfigs: List<DiscoverSectionConfig> =
        ALL_SECTION_IDS.mapIndexed { index, id ->
            DiscoverSectionConfig(id = id, visible = true, order = index)
        }

    private val _sectionConfigs = MutableStateFlow(defaultConfigs)
    val sectionConfigs: StateFlow<List<DiscoverSectionConfig>> = _sectionConfigs.asStateFlow()

    init {
        // 预加载:从 DataStore 读取首值填入 StateFlow,消除 stateIn 默认值跳变
        scope.launch {
            val prefs = context.discoverSectionDataStore.data.first()
            _sectionConfigs.value = readConfigs(prefs)
        }
    }

    private fun readConfigs(prefs: Preferences): List<DiscoverSectionConfig> {
        val orderStr = prefs[KEY_ORDER] ?: ALL_SECTION_IDS.joinToString(",")
        val orderedIds = orderStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val allIds = (orderedIds + ALL_SECTION_IDS).distinct()
        return allIds.mapIndexed { index, id ->
            DiscoverSectionConfig(
                id = id,
                visible = prefs[visibilityKey(id)] ?: true,
                order = index
            )
        }
    }

    suspend fun setSectionVisible(id: String, visible: Boolean) {
        context.discoverSectionDataStore.edit { prefs ->
            prefs[visibilityKey(id)] = visible
        }
        _sectionConfigs.value = _sectionConfigs.value.map {
            if (it.id == id) it.copy(visible = visible) else it
        }
    }

    /** 设置栏目排序（ID 列表） */
    suspend fun setSectionOrder(orderedIds: List<String>) {
        context.discoverSectionDataStore.edit { prefs ->
            prefs[KEY_ORDER] = orderedIds.joinToString(",")
        }
        _sectionConfigs.value = readConfigs(
            context.discoverSectionDataStore.data.first()
        )
    }

    /** 将指定栏目上移 */
    suspend fun moveUp(id: String) {
        val configs = _sectionConfigs.value
        val ids = configs.map { it.id }.toMutableList()
        val index = ids.indexOf(id)
        if (index > 0) {
            java.util.Collections.swap(ids, index, index - 1)
            setSectionOrder(ids)
        }
    }

    /** 将指定栏目下移 */
    suspend fun moveDown(id: String) {
        val configs = _sectionConfigs.value
        val ids = configs.map { it.id }.toMutableList()
        val index = ids.indexOf(id)
        if (index >= 0 && index < ids.size - 1) {
            java.util.Collections.swap(ids, index, index + 1)
            setSectionOrder(ids)
        }
    }
}
