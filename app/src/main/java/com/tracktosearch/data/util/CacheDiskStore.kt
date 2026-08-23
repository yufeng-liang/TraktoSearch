package com.tracktosearch.data.util

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

internal data class CacheDiskEntry<T>(
    val key: String,
    val value: T,
    val expireAt: Long?
)

internal data class CacheDiskWrite<T>(
    val key: String,
    val value: T,
    val expireAt: Long
)

/**
 * PersistentTtlCache 的 DataStore adapter。
 *
 * 这里只负责 key 编码、JSON 编解码、过期过滤和磁盘互斥；缓存代次与加载期间
 * 的前台写入保护仍由 PersistentTtlCache 持有，避免把内存状态泄漏到磁盘层。
 */
internal class CacheDiskStore<T>(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val serializer: KSerializer<T>,
    private val keyPrefix: String
) {
    private companion object {
        private const val TAG = "CacheDiskStore"
    }

    private val prefix = "$keyPrefix:"
    private val mutationMutex = Mutex()

    /** 读取当前前缀下所有未过期、可反序列化的条目。 */
    suspend fun readEntries(): List<CacheDiskEntry<T>> {
        return try {
            val prefs = dataStore.data.first()
            val now = System.currentTimeMillis()
            val entries = mutableListOf<CacheDiskEntry<T>>()
            prefs.asMap().forEach { (key, rawValue) ->
                val keyName = key.name
                if (!keyName.startsWith(prefix) || keyName.endsWith(":exp")) return@forEach
                val cacheKey = keyName.removePrefix(prefix)
                val jsonValue = rawValue as? String ?: return@forEach
                val expireAt = prefs[longPreferencesKey(expirationKey(keyName))]
                if (expireAt != null && expireAt != Long.MAX_VALUE && now > expireAt) return@forEach
                try {
                    entries += CacheDiskEntry(
                        key = cacheKey,
                        value = json.decodeFromString(serializer, jsonValue),
                        expireAt = expireAt
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "decode failed for key=$keyName: ${e.message}")
                }
            }
            entries
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "read failed for prefix=$keyPrefix: ${e.message}")
            emptyList()
        }
    }

    /** 写入一个条目。所有 DataStore mutation 通过同一把锁串行化。 */
    suspend fun write(
        entry: CacheDiskWrite<T>,
        shouldWrite: () -> Boolean = { true }
    ): Boolean {
        return mutationMutex.withLock {
            if (!shouldWrite()) return@withLock false
            dataStore.edit { prefs ->
                putValue(prefs, entry)
            }
            true
        }
    }

    /**
     * 在一次 DataStore transaction 中写入一批已算好 expireAt 的条目。
     *
     * Preferences DataStore 每次 edit 都会整份序列化并原子替换文件，
     * 逐 key 写入会把一次列表富化放大成上百次全文件重写；攒批写把它压成一次。
     *
     * [shouldWrite] 在 transaction 内逐条求值，保证代次校验和实际落盘之间没有窗口。
     */
    suspend fun writeBatch(
        entries: List<CacheDiskWrite<T>>,
        shouldWrite: (CacheDiskWrite<T>) -> Boolean = { true }
    ): Int {
        if (entries.isEmpty()) return 0
        return mutationMutex.withLock {
            var written = 0
            dataStore.edit { prefs ->
                entries.forEach { entry ->
                    if (shouldWrite(entry)) {
                        putValue(prefs, entry)
                        written++
                    }
                }
            }
            written
        }
    }

    /** 在一次 DataStore transaction 中批量写入条目。 */
    suspend fun writeAll(
        entries: List<Pair<String, T>>,
        expireAt: (String) -> Long?,
        shouldWrite: () -> Boolean = { true }
    ): Int {
        if (entries.isEmpty()) return 0
        return mutationMutex.withLock {
            if (!shouldWrite()) return@withLock 0
            var written = 0
            dataStore.edit { prefs ->
                entries.forEach { (key, value) ->
                    expireAt(key)?.let { valueExpireAt ->
                        putValue(prefs, CacheDiskWrite(key, value, valueExpireAt))
                        written++
                    }
                }
            }
            written
        }
    }

    /** 清理当前 key 前缀下的所有值和过期时间元数据。 */
    suspend fun clearAll() {
        mutationMutex.withLock {
            dataStore.edit { prefs ->
                prefs.asMap().keys
                    .filter { it.name.startsWith(prefix) }
                    .forEach { prefs.remove(it) }
            }
        }
    }

    /** 读取磁盘中所有未过期条目，供跨设备同步导出使用。 */
    suspend fun snapshot(): Map<String, T> =
        readEntries().associate { entry -> entry.key to entry.value }

    /** 估算当前 key 前缀的 JSON UTF-8 字节数。 */
    suspend fun sizeBytes(): Long {
        return try {
            val prefs = dataStore.data.first()
            prefs.asMap().asSequence()
                .filter { (key, value) -> key.name.startsWith(prefix) && value is String }
                .sumOf { (_, value) -> (value as String).toByteArray(Charsets.UTF_8).size.toLong() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "size failed for prefix=$keyPrefix: ${e.message}")
            0L
        }
    }

    private fun putValue(prefs: MutablePreferences, entry: CacheDiskWrite<T>) {
        val valueKey = valueKey(entry.key)
        prefs[stringPreferencesKey(valueKey)] = json.encodeToString(serializer, entry.value)
        prefs[longPreferencesKey(expirationKey(valueKey))] = entry.expireAt
    }

    private fun valueKey(cacheKey: String): String = "$prefix$cacheKey"

    private fun expirationKey(valueKey: String): String = "$valueKey:exp"
}
