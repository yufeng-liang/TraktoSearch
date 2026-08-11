package com.tracktosearch.data.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.aiDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_cache")

enum class AiCacheFeature(val wireName: String) {
    CHARACTERS("characters"),
    GREETING("greeting"),
    TASTE("taste"),
    QUIZ("quiz"),
    DAILY_KNOWLEDGE("daily_knowledge"),
    TTS("tts")
}

object AiStorageKey {
    const val SCHEMA_VERSION = 1

    fun forFriend(friendId: String, feature: AiCacheFeature, suffix: String? = null): String {
        val normalizedFriendId = friendId.trim()
        require(normalizedFriendId.isNotEmpty()) { "friendId must not be blank" }
        val normalizedSuffix = suffix?.trim()?.takeIf { it.isNotEmpty() }?.let { "_$it" }.orEmpty()
        return "ai_v${SCHEMA_VERSION}_${feature.wireName}_${normalizedFriendId}${normalizedSuffix}"
    }
}

/** 小范围 AI JSON 缓存；登出清理由上层在会话生命周期中调用 clearFriend。 */
@Singleton
class AiStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun read(friendId: String, feature: AiCacheFeature, suffix: String? = null): String? {
        val key = stringPreferencesKey(AiStorageKey.forFriend(friendId, feature, suffix))
        return context.aiDataStore.data.first()[key]
    }

    suspend fun write(friendId: String, feature: AiCacheFeature, json: String, suffix: String? = null) {
        require(json.isNotBlank()) { "AI cache JSON must not be blank" }
        val key = stringPreferencesKey(AiStorageKey.forFriend(friendId, feature, suffix))
        context.aiDataStore.edit { preferences -> preferences[key] = json }
    }

    suspend fun remove(friendId: String, feature: AiCacheFeature, suffix: String? = null) {
        val key = stringPreferencesKey(AiStorageKey.forFriend(friendId, feature, suffix))
        context.aiDataStore.edit { preferences -> preferences.remove(key) }
    }

    suspend fun clearFriend(friendId: String) {
        val prefix = "ai_v${AiStorageKey.SCHEMA_VERSION}_"
        val normalizedFriendId = friendId.trim()
        require(normalizedFriendId.isNotEmpty()) { "friendId must not be blank" }
        val friendPrefixes = AiCacheFeature.entries.map { feature ->
            "$prefix${feature.wireName}_${normalizedFriendId}"
        }
        context.aiDataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { key ->
                    friendPrefixes.any { prefix ->
                        key.name == prefix || key.name.startsWith("${prefix}_")
                    }
                }
                .forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    preferences.remove(key as Preferences.Key<Any>)
                }
        }
    }
}
