package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
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

    /** 同步获取当前配置（用于 StateFlow 初始值，避免加载跳动） */
    fun getCurrentConfigsSync(): List<DetailSectionConfig> = runBlocking {
        sectionConfigs.first()
    }

    /** 获取所有模块的配置列表 */
    val sectionConfigs: Flow<List<DetailSectionConfig>> = context.detailSectionDataStore.data.map { prefs ->
        ALL_SECTION_IDS.map { id ->
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
    }
}
