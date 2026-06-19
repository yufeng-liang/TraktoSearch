package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import com.tracktosearch.data.remote.dto.ResourceItem
import javax.inject.Inject
import javax.inject.Singleton

private val Context.favoriteDataStore: DataStore<Preferences> by preferencesDataStore(name = "favorite_resources")

@Singleton
class FavoriteResourceStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        val KEY_FAVORITE_RESOURCES = stringSetPreferencesKey("favorite_resource_jsons")
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cachedUrls: Set<String>? = null

    val favoriteResources: Flow<List<ResourceItem>> = context.favoriteDataStore.data.map { prefs ->
        val jsonStrings = prefs[KEY_FAVORITE_RESOURCES] ?: emptySet()
        jsonStrings.mapNotNull { jsonStr ->
            runCatching { json.decodeFromString<ResourceItem>(jsonStr) }.getOrNull()
        }
    }

    suspend fun getFavoriteResources(): List<ResourceItem> {
        val jsonStrings = context.favoriteDataStore.data
            .map { it[KEY_FAVORITE_RESOURCES] ?: emptySet() }.first()
        return jsonStrings.mapNotNull { jsonStr ->
            runCatching { json.decodeFromString<ResourceItem>(jsonStr) }.getOrNull()
        }
    }

    suspend fun getFavoriteUrls(): Set<String> {
        cachedUrls?.let { return it }
        val urls = getFavoriteResources().map { it.url }.toSet()
        cachedUrls = urls
        return urls
    }

    fun isFavoriteSync(url: String): Boolean {
        return cachedUrls?.contains(url) == true
    }

    /** 切换收藏状态，返回是否变为已收藏 */
    suspend fun toggleFavorite(item: ResourceItem): Boolean {
        val current = getFavoriteUrls()
        val nowFavorite = item.url !in current
        if (nowFavorite) {
            val jsonStr = json.encodeToString(ResourceItem.serializer(), item)
            context.favoriteDataStore.edit { prefs ->
                val existing = prefs[KEY_FAVORITE_RESOURCES] ?: emptySet()
                // 先移除同 URL 的旧记录（更新数据）
                val filtered = existing.filterNot { existingStr ->
                    runCatching { json.decodeFromString<ResourceItem>(existingStr) }
                        .getOrNull()?.url == item.url
                }.toSet()
                prefs[KEY_FAVORITE_RESOURCES] = filtered + jsonStr
            }
            cachedUrls = (cachedUrls ?: emptySet()) + item.url
        } else {
            context.favoriteDataStore.edit { prefs ->
                val existing = prefs[KEY_FAVORITE_RESOURCES] ?: emptySet()
                val filtered = existing.filterNot { existingStr ->
                    runCatching { json.decodeFromString<ResourceItem>(existingStr) }
                        .getOrNull()?.url == item.url
                }.toSet()
                prefs[KEY_FAVORITE_RESOURCES] = filtered
            }
            cachedUrls = (cachedUrls ?: emptySet()) - item.url
        }
        return nowFavorite
    }

    suspend fun removeFavorite(url: String) {
        context.favoriteDataStore.edit { prefs ->
            val existing = prefs[KEY_FAVORITE_RESOURCES] ?: emptySet()
            val filtered = existing.filterNot { existingStr ->
                runCatching { json.decodeFromString<ResourceItem>(existingStr) }
                    .getOrNull()?.url == url
            }.toSet()
            prefs[KEY_FAVORITE_RESOURCES] = filtered
        }
        cachedUrls = (cachedUrls ?: emptySet()) - url
    }
}
