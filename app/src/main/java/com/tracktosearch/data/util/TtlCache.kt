package com.tracktosearch.data.util

import java.util.concurrent.ConcurrentHashMap

/**
 * 带过期时间的线程安全内存缓存
 * @param ttlMillis 缓存有效期，默认 10 分钟
 */
class TtlCache<T>(private val ttlMillis: Long = 10 * 60 * 1000L) {
    private val cache = ConcurrentHashMap<String, Pair<T, Long>>()

    fun get(key: String): T? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.second > ttlMillis) {
            cache.remove(key)
            return null
        }
        return entry.first
    }

    fun put(key: String, value: T) {
        cache[key] = Pair(value, System.currentTimeMillis())
    }

    suspend fun getOrPut(key: String, defaultValue: suspend () -> T): T {
        get(key)?.let { return it }
        val value = defaultValue()
        put(key, value)
        return value
    }

    fun clear() = cache.clear()
}