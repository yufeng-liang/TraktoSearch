package com.tracktosearch.data.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * 持久化 TTL 缓存：内存缓存（一级）+ DataStore（二级）。
 *
 * - 内存命中 → 秒进（同步返回）
 * - 内存未命中但磁盘有 → 启动时已加载到内存，仍秒进
 * - 都没有 → 走网络，结果同时写入内存和磁盘
 *
 * 适用场景：基本不变的数据（海报路径、tmdbId、imdbId、演职员头像等），
 * 跨 App 重启保留，避免重复请求。
 *
 * 注意：缓存 key 已含语言后缀（如 "12345_zh-CN"），不同语言的海报/标题会分别存储。
 *
 * @param ttlMillis 缓存有效期，Long.MAX_VALUE 表示永久
 * @param maxSize 内存缓存最大条目数
 * @param dataStore DataStore 实例（多个 PersistentTtlCache 可共享同一个文件）
 * @param json Json 序列化器
 * @param serializer T 的序列化器（避免泛型擦除导致运行时无法获取类型）
 * @param keyPrefix DataStore 中的 key 前缀，避免不同缓存类型冲突
 * @param scope 用于异步写入磁盘的协程作用域
 */
class PersistentTtlCache<T>(
    ttlMillis: Long,
    maxSize: Int,
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val serializer: KSerializer<T>,
    private val keyPrefix: String,
    private val scope: CoroutineScope
) : TtlCache<T>(ttlMillis, maxSize) {

    /** 是否已从磁盘加载完毕的 Deferred，供 awaitLoaded() 挂起等待，避免竞态导致缓存未命中 */
    private val loadedDeferred = CompletableDeferred<Unit>()

    /** putAll 批量写入互斥：避免并发 putAll 同一 key 双写（#30） */
    private val putAllMutex = Mutex()

    /**
     * 串行化 DataStore 写入与 clearAll 删除。
     * clearAll 先切换 generation，再持有该锁删除；旧代次写入要么在删除前完成，要么被跳过。
     */
    private val diskMutationMutex = Mutex()

    /** 同一缓存只允许一次磁盘回填，期间记录前台写入，避免旧快照覆盖新值。 */
    private val loadMutex = Mutex()
    private val loadStateLock = Any()
    private var nextLoadId = 0L
    private var activeLoadId = 0L
    private var clearedDuringLoad = false
    private val keysWrittenDuringLoad = mutableSetOf<String>()

    /**
     * 从磁盘加载所有条目到内存缓存。
     * 应在 Application 启动时调用（后台线程，不阻塞 UI）。
     */
    suspend fun loadFromDisk() {
        loadMutex.withLock {
            val loadId = beginDiskLoad()
            try {
                val prefs = dataStore.data.first()
                val now = System.currentTimeMillis()
                prefs.asMap().forEach { (key, value) ->
                    val keyStr = key.name
                    if (!keyStr.startsWith("$keyPrefix:")) return@forEach
                    // 跳过 expireAt 元数据 key（格式: {prefix}:{cacheKey}:exp）
                    if (keyStr.endsWith(":exp")) return@forEach
                    val cacheKey = keyStr.removePrefix("$keyPrefix:")
                    val jsonStr = value as? String ?: return@forEach
                    try {
                        val item = json.decodeFromString(serializer, jsonStr)
                        // 读取持久化的 expireAt，恢复原始过期时间（避免重启后 TTL 被重置导致缓存永不过期）
                        val expireAt = prefs[longPreferencesKey("$keyStr:exp")]
                        if (expireAt != null && expireAt != Long.MAX_VALUE && now > expireAt) {
                            // 已过期，跳过（不加载到内存）
                            return@forEach
                        }
                        putDiskValueIfCurrent(loadId, cacheKey, item, expireAt)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        // 反序列化失败（数据格式变更），跳过该条目
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // 磁盘读取失败不阻塞应用启动
            } finally {
                finishDiskLoad(loadId)
                loadedDeferred.complete(Unit)
            }
        }
    }

    /**
     * 等待磁盘加载完成。缓存未命中时调用，确保先尝试从磁盘加载的数据中查找，
     * 再决定是否走网络。已加载完成时立即返回。
     */
    suspend fun awaitLoaded() = loadedDeferred.await()

    override fun put(key: String, value: T) {
        withGenerationLock {
            markKeyWrittenDuringLoad(key)
            super.put(key, value)
            val writeGeneration = currentGeneration()
            // 异步写入磁盘，不阻塞内存写入返回
            val expireAt = getExpireAt(key) ?: return@withGenerationLock
            scope.launch {
                try {
                    diskMutationMutex.withLock {
                        if (!isCurrentGeneration(writeGeneration)) return@withLock
                        val jsonStr = json.encodeToString(serializer, value)
                        dataStore.edit { prefs ->
                            prefs[stringPreferencesKey("$keyPrefix:$key")] = jsonStr
                            prefs[longPreferencesKey("$keyPrefix:$key:exp")] = expireAt
                        }
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    // 磁盘写入失败静默处理，不影响内存缓存
                }
            }
        }
    }

    /**
     * 清除本缓存的所有条目（内存 + DataStore 中前缀匹配的 key）。
     * 用于设置页「缓存管理」按类目清除。
     */
    suspend fun clearAll() {
        // 清内存
        clear()
        // 清 DataStore 中本前缀的所有 key
        try {
            diskMutationMutex.withLock {
                dataStore.edit { prefs ->
                    val keysToRemove = prefs.asMap().keys.filter { it.name.startsWith("$keyPrefix:") }
                    keysToRemove.forEach { prefs.remove(it) }
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            // DataStore 删除失败静默处理
        }
    }

    /**
     * 从磁盘 DataStore 导出本缓存所有条目（用于云端同步上传）。
     *
     * 直接读 DataStore 而非内存 cache，确保拿到磁盘上所有条目
     * （内存 cache 可能因 LruCache 淘汰丢失部分条目，但磁盘是完整的）。
     *
     * 过滤掉已过期条目，避免把过期数据同步到其他设备（#29）。
     *
     * @return key → value 映射；磁盘读取失败返回空 Map
     */
    suspend fun snapshotFromDisk(): Map<String, T> {
        return try {
            val prefs = dataStore.data.first()
            val now = System.currentTimeMillis()
            val result = mutableMapOf<String, T>()
            prefs.asMap().forEach { (key, value) ->
                val keyStr = key.name
                if (!keyStr.startsWith("$keyPrefix:")) return@forEach
                if (keyStr.endsWith(":exp")) return@forEach
                val cacheKey = keyStr.removePrefix("$keyPrefix:")
                val jsonStr = value as? String ?: return@forEach
                // 检查持久化的 expireAt；已过期条目不导出，避免同步过期数据到其他设备（#29）
                val expireAt = prefs[longPreferencesKey("$keyStr:exp")]
                if (expireAt != null && expireAt != Long.MAX_VALUE && now > expireAt) return@forEach
                try {
                    result[cacheKey] = json.decodeFromString(serializer, jsonStr)
                } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    // 反序列化失败跳过
                }
            }
            result
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 批量写入条目（内存 + 磁盘），用于云端同步下载后合并到本地缓存。
     * 仅写入不存在的 key（已有数据不覆盖，避免本地更新的数据被云端旧数据覆盖）。
     *
     * @param entries key → value 映射
     * @param overwrite true=覆盖已有 key；false=只写入不存在的 key（默认）
     * @return 实际写入的条目数
     */
    suspend fun putAll(entries: Map<String, T>, overwrite: Boolean = false): Int {
        if (entries.isEmpty()) return 0
        // putAll 整次加锁，避免并发 putAll 同 key 双写且 written 计数虚高（#30）
        return putAllMutex.withLock {
            val writeGeneration = currentGeneration()
            var written = 0
            // 先写入内存缓存（获取 expireAt），再批量写入 DataStore
            val toWrite = mutableListOf<Pair<String, T>>()
            entries.forEach { (key, value) ->
                if (overwrite || get(key) == null) {
                    withGenerationLock {
                        if (!isCurrentGeneration(writeGeneration)) return@withGenerationLock
                        markKeyWrittenDuringLoad(key)
                        super.put(key, value)
                        toWrite.add(key to value)
                    }
                }
            }
            // 批量写入 DataStore（一次事务）
            try {
                diskMutationMutex.withLock {
                    if (!isCurrentGeneration(writeGeneration)) return@withLock
                    dataStore.edit { prefs ->
                        toWrite.forEach { (key, value) ->
                            val jsonStr = json.encodeToString(serializer, value)
                            prefs[stringPreferencesKey("$keyPrefix:$key")] = jsonStr
                            val expireAt = getExpireAt(key)
                            if (expireAt != null) {
                                prefs[longPreferencesKey("$keyPrefix:$key:exp")] = expireAt
                            }
                            written++
                        }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                // 磁盘写入失败静默
            }
            written
        }
    }

    /**
     * 估算本缓存在 DataStore 中占用的字节数（按 JSON 字符串长度近似）。
     * 用于设置页「缓存管理」展示各项大小。
     */
    suspend fun getSizeBytes(): Long {
        return try {
            val prefs = dataStore.data.first()
            var total = 0L
            prefs.asMap().forEach { (key, value) ->
                if (!key.name.startsWith("$keyPrefix:")) return@forEach
                val jsonStr = value as? String ?: return@forEach
                // UTF-8 字节数近似（中文字符占 3 字节，String.length 是 char 数）
                // 粗略估算：JSON 字符串字节数 ≈ char 数 × 1.5（保守估计）
                total += jsonStr.toByteArray(Charsets.UTF_8).size.toLong()
            }
            total
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            0L
        }
    }

    override fun clear() {
        withGenerationLock {
            synchronized(loadStateLock) {
                if (activeLoadId != 0L) clearedDuringLoad = true
            }
            super.clear()
        }
    }

    private fun beginDiskLoad(): Long = synchronized(loadStateLock) {
        val loadId = ++nextLoadId
        activeLoadId = loadId
        clearedDuringLoad = false
        keysWrittenDuringLoad.clear()
        loadId
    }

    private fun finishDiskLoad(loadId: Long) = synchronized(loadStateLock) {
        if (activeLoadId == loadId) {
            activeLoadId = 0L
            clearedDuringLoad = false
            keysWrittenDuringLoad.clear()
        }
    }

    private fun markKeyWrittenDuringLoad(key: String) = synchronized(loadStateLock) {
        if (activeLoadId != 0L) keysWrittenDuringLoad += key
    }

    private fun putDiskValueIfCurrent(loadId: Long, key: String, value: T, expireAt: Long?) {
        withGenerationLock {
            synchronized(loadStateLock) {
                if (activeLoadId != loadId || clearedDuringLoad || key in keysWrittenDuringLoad) {
                    return@synchronized
                }
                if (expireAt != null) {
                    putInternal(key, value, expireAt)
                } else {
                    // 旧格式无 expireAt，降级为重置 TTL
                    super.put(key, value)
                }
            }
        }
    }
}

/**
 * 创建 PersistentTtlCache 的便捷工厂函数（reified 内联，自动推断 serializer）。
 */
inline fun <reified T> persistentTtlCache(
    ttlMillis: Long,
    maxSize: Int,
    dataStore: DataStore<Preferences>,
    json: Json,
    keyPrefix: String,
    scope: CoroutineScope
): PersistentTtlCache<T> {
    val serializer = json.serializersModule.serializer<T>()
    return PersistentTtlCache(
        ttlMillis = ttlMillis,
        maxSize = maxSize,
        dataStore = dataStore,
        json = json,
        serializer = serializer,
        keyPrefix = keyPrefix,
        scope = scope
    )
}

/** 内联 reified 工具，供 factory 函数获取 serializer */
inline fun <reified T> Json.serializer(): KSerializer<T> =
    serializersModule.serializer<T>()
