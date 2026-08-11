package com.tracktosearch.data.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.aiDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_cache")

enum class AiCacheFeature(val wireName: String) {
    CHARACTERS("characters"),
    GREETING("greeting"),
    TASTE("taste"),
    QUIZ("quiz"),
    DAILY_KNOWLEDGE("daily_knowledge"),
    QUIZ_RESULT("quiz_result"),
    TTS("tts")
}

object AiStorageKey {
    const val SCHEMA_VERSION = 2

    /**
     * friendId 长度前缀编码后拼进 key，避免 friendId 内嵌 `_` 与
     * `friendId_后缀` 形式的 key 碰撞（`x_y` vs `x`+`_y`），也保证 clearFriend 精确清理。
     */
    fun encodeFriendId(friendId: String): String {
        val normalizedFriendId = friendId.trim()
        require(normalizedFriendId.isNotEmpty()) { "friendId must not be blank" }
        return "${normalizedFriendId.length}:$normalizedFriendId"
    }

    fun forFriend(friendId: String, feature: AiCacheFeature, suffix: String? = null): String {
        val normalizedSuffix = suffix?.trim()?.takeIf { it.isNotEmpty() }?.let { "_$it" }.orEmpty()
        return "ai_v${SCHEMA_VERSION}_${feature.wireName}_${encodeFriendId(friendId)}${normalizedSuffix}"
    }
}

/**
 * AI 缓存：小 JSON 存 DataStore preferences；大内容（问候/TTS 的 base64 音频，可数百 KB）
 * 落到 cacheDir 文件，prefs 只存 `file:<key>` 标记，避免 DataStore 把整图常驻内存且每次 edit 全量重写文件。
 * 登出清理由上层在会话生命周期中调用 clearFriend。
 */
@Singleton
class AiStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cacheDir: File
        get() = File(context.cacheDir, "ai_cache").apply { mkdirs() }

    private val FILE_MARKER_PREFIX = "file:"

    /** 超过该字节数的缓存内容落到文件（主要是音频 dataUrl）。 */
    private val FILE_THRESHOLD_BYTES = 64 * 1024

    suspend fun read(friendId: String, feature: AiCacheFeature, suffix: String? = null): String? {
        val raw = context.aiDataStore.data.first()[stringPreferencesKey(AiStorageKey.forFriend(friendId, feature, suffix))]
            ?: return null
        return resolveStored(raw)
    }

    suspend fun write(friendId: String, feature: AiCacheFeature, json: String, suffix: String? = null) {
        require(json.isNotBlank()) { "AI cache JSON must not be blank" }
        val key = stringPreferencesKey(AiStorageKey.forFriend(friendId, feature, suffix))
        if (json.length > FILE_THRESHOLD_BYTES) {
            val fileKey = fileKeyFor(friendId, feature, suffix)
            withContext(Dispatchers.IO) {
                cacheDir.resolve("$fileKey.json").writeText(json)
            }
            context.aiDataStore.edit { preferences -> preferences[key] = "$FILE_MARKER_PREFIX$fileKey" }
        } else {
            context.aiDataStore.edit { preferences -> preferences[key] = json }
        }
    }

    suspend fun remove(friendId: String, feature: AiCacheFeature, suffix: String? = null) {
        val key = stringPreferencesKey(AiStorageKey.forFriend(friendId, feature, suffix))
        val stored = context.aiDataStore.data.first()[key]
        if (stored != null && stored.startsWith(FILE_MARKER_PREFIX)) {
            deleteFileQuietly(stored.removePrefix(FILE_MARKER_PREFIX))
        }
        context.aiDataStore.edit { preferences -> preferences.remove(key) }
    }

    suspend fun clearFriend(friendId: String) {
        val encodedFriendId = AiStorageKey.encodeFriendId(friendId)
        val featurePrefixes = AiCacheFeature.entries.map { feature ->
            "ai_v${AiStorageKey.SCHEMA_VERSION}_${feature.wireName}_${encodedFriendId}"
        }
        val matches = context.aiDataStore.data.first().asMap().filterKeys { key ->
            featurePrefixes.any { prefix -> key.name == prefix || key.name.startsWith("${prefix}_") }
        }
        // 该用户名下的大内容文件一并清理，再移除 prefs 条目
        val fileKeys = matches.values.mapNotNull { value ->
            (value as? String)?.takeIf { it.startsWith(FILE_MARKER_PREFIX) }?.removePrefix(FILE_MARKER_PREFIX)
        }
        if (fileKeys.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                fileKeys.forEach { fileKey -> cacheDir.resolve("$fileKey.json").delete() }
            }
        }
        context.aiDataStore.edit { preferences ->
            matches.keys.forEach { key ->
                @Suppress("UNCHECKED_CAST")
                preferences.remove(key as Preferences.Key<Any>)
            }
        }
    }

    private suspend fun resolveStored(raw: String): String? {
        if (!raw.startsWith(FILE_MARKER_PREFIX)) return raw
        val fileKey = raw.removePrefix(FILE_MARKER_PREFIX)
        return withContext(Dispatchers.IO) {
            val file = cacheDir.resolve("$fileKey.json")
            if (file.exists()) file.readText() else null
        }
    }

    private fun fileKeyFor(friendId: String, feature: AiCacheFeature, suffix: String?): String {
        // 文件名只需唯一且文件系统安全：用完整 key 的 SHA-256 前缀，避免原始 key 中 friendId 的非法字符
        val rawKey = AiStorageKey.forFriend(friendId, feature, suffix)
        return MessageDigest.getInstance("SHA-256")
            .digest(rawKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    private fun deleteFileQuietly(fileKey: String) {
        runCatching { cacheDir.resolve("$fileKey.json").delete() }
    }
}
