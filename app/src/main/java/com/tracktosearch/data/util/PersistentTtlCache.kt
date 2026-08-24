package com.tracktosearch.data.util

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.util.concurrent.atomic.AtomicBoolean

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
    private val putAllMutex = kotlinx.coroutines.sync.Mutex()

    private val diskStore = CacheDiskStore(dataStore, json, serializer, keyPrefix)

    /** 同一缓存只允许一次磁盘回填，期间记录前台写入，避免旧快照覆盖新值。 */
    private val loadMutex = Mutex()
    private val loadStateLock = Any()
    private var nextLoadId = 0L
    private var activeLoadId = 0L
    private var clearedDuringLoad = false
    private val keysWrittenDuringLoad = mutableSetOf<String>()

    /**
     * 从磁盘加载所有条目到内存缓存。
     * 首次使用时由 awaitLoaded() 触发，也允许调用方显式提前加载。
     * 读盘始终在 IO 线程执行，且同一缓存只加载一次。
     */
    suspend fun loadFromDisk() {
        loadMutex.withLock {
            if (loadedDeferred.isCompleted) return@withLock
            val loadId = beginDiskLoad()
            withContext(Dispatchers.IO) {
                try {
                    diskStore.readEntries().forEach { entry ->
                        putDiskValueIfCurrent(loadId, entry.key, entry.value, entry.expireAt)
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    // 磁盘读取失败不阻塞应用启动
                    Log.w("PersistentTtlCache", "loadFromDisk failed for prefix=$keyPrefix: ${e.message}")
                } finally {
                    finishDiskLoad(loadId)
                    loadedDeferred.complete(Unit)
                }
            }
        }
    }

    /**
     * 等待磁盘加载完成。缓存未命中时调用，确保先尝试从磁盘加载的数据中查找，
     * 再决定是否走网络。已加载完成时立即返回。
     */
    suspend fun awaitLoaded() {
        // Application 不再全量预热缓存；页面首次真正访问缓存时才加载对应前缀。
        loadFromDisk()
        loadedDeferred.await()
    }

    /**
     * 内存未命中时等磁盘加载完再查一次，仍未命中返回 null。
     *
     * 供只做 `get()` 判断的调用方替换使用：直接 `get()` 会在冷启动（内存空、磁盘有效）时
     * 误判未命中而白发一次网络请求。
     */
    suspend fun getAfterLoad(key: String): T? {
        get(key)?.let { return it }
        awaitLoaded()
        return get(key)
    }

    /**
     * 覆写以补齐"内存未命中先等磁盘"这一步。
     *
     * 基类只查内存就走 fetch，冷启动时磁盘上仍有效的缓存会被整体跳过；
     * 这里先等磁盘回填并复查，命中即返回，未命中才交给基类做飞行中去重 + fetch。
     */
    override suspend fun getOrAwait(key: String, skipCache: Boolean, fetch: suspend () -> T): T {
        if (!skipCache) {
            get(key)?.let { return it }
            awaitLoaded()
            get(key)?.let { return it }
        }
        // 已确认内存 + 磁盘都未命中，交给基类只做飞行中去重（不再重复查内存）
        return super.getOrAwait(key, skipCache = true, fetch = fetch)
    }

    /** 导入外部缓存时保留原始 expireAt，避免命中公共池后重新计算完整 TTL。 */
    fun putWithExpireAt(key: String, value: T, expireAt: Long) {
        if (expireAt != Long.MAX_VALUE && expireAt <= System.currentTimeMillis()) return
        withGenerationLock {
            markKeyWrittenDuringLoad(key)
            putInternal(key, value, expireAt)
            schedulePersist(key, value, expireAt, currentGeneration())
        }
    }

    override fun put(key: String, value: T) {
        withGenerationLock {
            markKeyWrittenDuringLoad(key)
            super.put(key, value)
            val expireAt = getExpireAt(key) ?: return@withGenerationLock
            // 攒批异步落盘，不阻塞内存写入返回
            schedulePersist(key, value, expireAt, currentGeneration())
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
            diskStore.clearAll()
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
        return diskStore.snapshot()
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
                written = diskStore.writeAll(
                    entries = toWrite,
                    expireAt = ::getExpireAt,
                    shouldWrite = { isCurrentGeneration(writeGeneration) }
                )
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // 磁盘写入失败静默
                Log.w("PersistentTtlCache", "putAll DataStore write failed for prefix=$keyPrefix: ${e.message}")
            }
            written
        }
    }

    /**
     * 估算本缓存在 DataStore 中占用的字节数（按 JSON 字符串长度近似）。
     * 用于设置页「缓存管理」展示各项大小。
     */
    suspend fun getSizeBytes(): Long {
        return diskStore.sizeBytes()
    }

    override fun clear() {
        withGenerationLock {
            synchronized(loadStateLock) {
                if (activeLoadId != 0L) clearedDuringLoad = true
            }
            // 待落盘条目属于旧代次，直接丢弃（drain 里的代次校验也会拦，这里顺带释放内存）
            synchronized(pendingWriteLock) { pendingWrites.clear() }
            super.clear()
        }
    }

    // ========== 攒批落盘 ==========

    /** 待落盘条目：同 key 多次写入按最后一次生效。 */
    private class PendingDiskWrite<V>(val value: V, val expireAt: Long, val generation: Long)

    private val pendingWrites = LinkedHashMap<String, PendingDiskWrite<T>>()
    private val pendingWriteLock = Any()
    private val persistSignal = Channel<Unit>(Channel.CONFLATED)
    private val persistWorkerStarted = AtomicBoolean(false)

    /**
     * 登记一条待落盘数据并唤醒落盘协程。
     *
     * Preferences DataStore 每次 edit 都整份序列化并原子替换文件，逐 key 写会把
     * 「一屏列表富化」放大成上百次全文件重写，把磁盘和 GC 都拖满。
     * 这里先攒 [PERSIST_DEBOUNCE_MS]，再一次事务写完整批。
     */
    private fun schedulePersist(key: String, value: T, expireAt: Long, writeGeneration: Long) {
        synchronized(pendingWriteLock) {
            pendingWrites[key] = PendingDiskWrite(value, expireAt, writeGeneration)
        }
        startPersistWorkerIfNeeded()
        persistSignal.trySend(Unit)
    }

    private fun startPersistWorkerIfNeeded() {
        if (!persistWorkerStarted.compareAndSet(false, true)) return
        scope.launch {
            for (unused in persistSignal) {
                // 攒批窗口：让同一批富化产生的多次写入合并成一次 DataStore 事务
                delay(PERSIST_DEBOUNCE_MS)
                drainPendingWrites()
            }
        }
    }

    private suspend fun drainPendingWrites() {
        while (true) {
            val batch = synchronized(pendingWriteLock) {
                if (pendingWrites.isEmpty()) return
                val snapshot = pendingWrites.toList()
                pendingWrites.clear()
                snapshot
            }
            val generations = batch.associate { (key, pending) -> key to pending.generation }
            val writes = batch.map { (key, pending) ->
                CacheDiskWrite(key, pending.value, pending.expireAt)
            }
            try {
                // 代次校验在 transaction 内逐条执行：clear()/clearAll() 之后的旧代次数据不回写磁盘
                diskStore.writeBatch(writes) { entry ->
                    generations[entry.key]?.let { isCurrentGeneration(it) } == true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 磁盘写入失败静默处理，不影响内存缓存
                Log.w("PersistentTtlCache", "batch write failed for prefix=$keyPrefix: ${e.message}")
            }
        }
    }

    private companion object {
        /** 攒批窗口（毫秒）：足够合并一屏列表的富化写入，又不至于让缓存长时间只在内存里。 */
        const val PERSIST_DEBOUNCE_MS = 400L
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
