package com.tracktosearch.data.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 带过期时间和容量上限的线程安全内存缓存。
 *
 * - TTL：到期条目在 get 时惰性清理。
 * - maxSize：超过容量时按 LRU 策略淘汰最久未访问条目；为 0 表示无上限。
 * - getOrPut：用 Mutex single-flight 防止缓存击穿（同一 key 并发 miss 只触发一次 defaultValue）。
 *
 * @param ttlMillis 缓存有效期，默认 10 分钟
 * @param maxSize 最大条目数，0 表示不限制
 */
class TtlCache<T>(
    private val ttlMillis: Long = 10 * 60 * 1000L,
    private val maxSize: Int = 0
) {
    private data class Entry<V>(val value: V, val expireAt: Long, val accessSeq: Long)

    private val cache = ConcurrentHashMap<String, Entry<T>>()
    private val accessCounter = AtomicLong(0L)
    private val loadMutex = Mutex()

    fun get(key: String): T? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() > entry.expireAt) {
            cache.remove(key)
            return null
        }
        // 更新访问时间（LRU）
        cache[key] = entry.copy(accessSeq = accessCounter.incrementAndGet())
        return entry.value
    }

    fun put(key: String, value: T) {
        val now = System.currentTimeMillis()
        cache[key] = Entry(value, now + ttlMillis, accessCounter.incrementAndGet())
        trimToSize()
    }

    suspend fun getOrPut(key: String, defaultValue: suspend () -> T): T {
        get(key)?.let { return it }
        // single-flight：防止缓存击穿，同一时刻只允许一个 defaultValue 执行
        return loadMutex.withLock {
            // double-check：等待期间可能已被其他协程填充
            get(key) ?: defaultValue().also { put(key, it) }
        }
    }

    fun clear() = cache.clear()

    private fun trimToSize() {
        if (maxSize <= 0) return
        val size = cache.size
        if (size <= maxSize) return
        // 淘汰 accessSeq 最小的条目（最久未访问）
        val sorted = cache.entries.sortedBy { it.value.accessSeq }
        val toRemove = sorted.dropLast(maxSize)
        toRemove.forEach { cache.remove(it.key) }
    }
}
