package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    companion object {
        // 所有发现页栏目 ID（默认顺序）
        val ALL_SECTION_IDS = listOf(
            "douban-movie",
            "douban-weekly",
            "douban-top250",
            "douban-us-box",
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

    /** 获取所有栏目的配置列表（按排序顺序） */
    val sectionConfigs: Flow<List<DiscoverSectionConfig>> = context.discoverSectionDataStore.data.map { prefs ->
        val orderStr = prefs[KEY_ORDER] ?: ALL_SECTION_IDS.joinToString(",")
        val orderedIds = orderStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val allIds = (orderedIds + ALL_SECTION_IDS).distinct()
        allIds.mapIndexed { index, id ->
            DiscoverSectionConfig(
                id = id,
                visible = prefs[visibilityKey(id)] ?: true,
                order = index
            )
        }
    }.distinctUntilChanged()

    suspend fun setSectionVisible(id: String, visible: Boolean) {
        context.discoverSectionDataStore.edit { prefs ->
            prefs[visibilityKey(id)] = visible
        }
    }

    /** 设置栏目排序（ID 列表） */
    suspend fun setSectionOrder(orderedIds: List<String>) {
        context.discoverSectionDataStore.edit { prefs ->
            prefs[KEY_ORDER] = orderedIds.joinToString(",")
        }
    }

    /** 将指定栏目上移 */
    suspend fun moveUp(id: String) {
        val configs = sectionConfigs.first()
        val ids = configs.map { it.id }.toMutableList()
        val index = ids.indexOf(id)
        if (index > 0) {
            java.util.Collections.swap(ids, index, index - 1)
            setSectionOrder(ids)
        }
    }

    /** 将指定栏目下移 */
    suspend fun moveDown(id: String) {
        val configs = sectionConfigs.first()
        val ids = configs.map { it.id }.toMutableList()
        val index = ids.indexOf(id)
        if (index >= 0 && index < ids.size - 1) {
            java.util.Collections.swap(ids, index, index + 1)
            setSectionOrder(ids)
        }
    }
}
