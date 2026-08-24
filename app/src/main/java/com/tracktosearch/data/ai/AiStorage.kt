package com.tracktosearch.data.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// 旧版本明文缓存仅用于一次性迁移，迁移完成后立即清空。
private val Context.legacyAiDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_cache")

enum class AiCacheFeature(val wireName: String) {
    CHARACTERS("characters"),
    GREETING("greeting"),
    TASTE("taste"),
    QUIZ("quiz"),
    DAILY_KNOWLEDGE("daily_knowledge"),
    QUIZ_RESULT("quiz_result"),
    // 已激活角色：不落盘的话杀进程后激活即失效，用户得再喊一次名字并再烧一次配额
    ACTIVATION("activation"),
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
 * AI 私有缓存统一存入 EncryptedSharedPreferences。
 *
 * 旧版本把小 JSON 存入明文 DataStore、大音频存入明文 cacheDir 文件；初始化时会把旧值读出
 * 写入加密 prefs，并清空旧 DataStore 与目录。新实现不再把 AI JSON 或音频 data URL 写入明文文件。
 */
@Singleton
class AiStorage private constructor(
    private val context: Context,
    private val preferencesFactory: () -> SharedPreferences
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    })

    /** Robolectric 无 Android Keystore 时使用隔离的测试 prefs，不影响生产 Hilt 构造。 */
    internal constructor(context: Context, preferences: SharedPreferences) : this(context, { preferences })

    companion object {
        private const val PREFS_NAME = "ai_cache_encrypted"
        private const val MIGRATED_KEY = "ai_cache_migrated_v3"
        private const val CURRENT_FRIEND_ID_KEY = "ai_current_friend_id"
        private const val FILE_MARKER_PREFIX = "file:"
    }

    private val migrationMutex = Mutex()

    @Volatile
    private var migrationComplete = false

    private val encryptedPreferences: SharedPreferences by lazy(preferencesFactory)

    private val legacyCacheDir: File
        get() = File(context.cacheDir, "ai_cache")

    suspend fun read(friendId: String, feature: AiCacheFeature, suffix: String? = null): String? =
        withSecurePreferences { preferences ->
            preferences.getString(AiStorageKey.forFriend(friendId, feature, suffix), null)
        }

    suspend fun write(friendId: String, feature: AiCacheFeature, json: String, suffix: String? = null) {
        require(json.isNotBlank()) { "AI cache JSON must not be blank" }
        withSecurePreferences { preferences ->
            val committed = preferences.edit()
                .putString(AiStorageKey.forFriend(friendId, feature, suffix), json)
                .commit()
            check(committed) { "Unable to persist encrypted AI cache" }
        }
    }

    suspend fun remove(friendId: String, feature: AiCacheFeature, suffix: String? = null) {
        withSecurePreferences { preferences ->
            val committed = preferences.edit()
                .remove(AiStorageKey.forFriend(friendId, feature, suffix))
                .commit()
            check(committed) { "Unable to remove encrypted AI cache" }
        }
    }

    suspend fun clearFriend(friendId: String) {
        val normalizedFriendId = friendId.trim()
        if (normalizedFriendId.isEmpty()) return
        withSecurePreferences { preferences ->
            val encodedFriendId = AiStorageKey.encodeFriendId(normalizedFriendId)
            val prefixes = AiCacheFeature.entries.map { feature ->
                "ai_v${AiStorageKey.SCHEMA_VERSION}_${feature.wireName}_${encodedFriendId}"
            }
            val editor = preferences.edit()
            preferences.all.keys
                .filter { key -> prefixes.any { prefix -> key == prefix || key.startsWith("${prefix}_") } }
                .forEach { key -> editor.remove(key) }
            if (preferences.getString(CURRENT_FRIEND_ID_KEY, null) == normalizedFriendId) {
                editor.remove(CURRENT_FRIEND_ID_KEY)
            }
            check(editor.commit()) { "Unable to clear encrypted AI cache" }
        }
    }

    suspend fun saveCurrentFriendId(friendId: String) {
        val normalizedFriendId = friendId.trim()
        require(normalizedFriendId.isNotEmpty()) { "friendId must not be blank" }
        withSecurePreferences { preferences ->
            check(
                preferences.edit()
                    .putString(CURRENT_FRIEND_ID_KEY, normalizedFriendId)
                    .commit()
            ) { "Unable to persist current friendId" }
        }
    }

    suspend fun readCurrentFriendId(): String? = withSecurePreferences { preferences ->
        preferences.getString(CURRENT_FRIEND_ID_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    suspend fun clearCurrentFriendId() {
        withSecurePreferences { preferences ->
            check(preferences.edit().remove(CURRENT_FRIEND_ID_KEY).commit()) {
                "Unable to clear current friendId"
            }
        }
    }

    private suspend fun <T> withSecurePreferences(block: (SharedPreferences) -> T): T {
        ensureMigrated()
        return withContext(Dispatchers.IO) { block(encryptedPreferences) }
    }

    /** 串行完成迁移，避免启动时多个 AI 请求各自读取旧明文 DataStore。 */
    private suspend fun ensureMigrated() {
        if (migrationComplete) return
        migrationMutex.withLock {
            if (migrationComplete) return
            withContext(Dispatchers.IO) {
                if (!encryptedPreferences.getBoolean(MIGRATED_KEY, false)) {
                    val legacyPreferences = context.legacyAiDataStore.data.first()
                    val editor = encryptedPreferences.edit()
                    legacyPreferences.asMap().forEach { (key, value) ->
                        val raw = value as? String ?: return@forEach
                        val migrated = resolveLegacyValue(raw)
                        if (migrated != null) editor.putString(key.name, migrated)
                    }
                    check(editor.putBoolean(MIGRATED_KEY, true).commit()) {
                        "Unable to migrate encrypted AI cache"
                    }
                }
                // 即使迁移标记已经存在，也清掉可能残留的旧明文目录。
                try {
                    context.legacyAiDataStore.edit { preferences -> preferences.clear() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 清理失败不影响新缓存；下次初始化仍会再次尝试删除旧目录。
                }
                runCatching { legacyCacheDir.deleteRecursively() }
                migrationComplete = true
            }
        }
    }

    private fun resolveLegacyValue(raw: String): String? {
        if (!raw.startsWith(FILE_MARKER_PREFIX)) return raw
        val fileKey = raw.removePrefix(FILE_MARKER_PREFIX)
        val file = legacyCacheDir.resolve("$fileKey.json")
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }
}
