package com.tracktosearch.data.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 带过期时间和容量上限的线程安全内存缓存。
 *
 * - TTL：到期条目在 get 时惰性清理。
 * - maxSize：超过容量时按 LRU 策略淘汰最久未访问条目；为 0 表示无上限。
 * - getOrPut：委托 getOrAwait 实现 per-key single-flight 防止缓存击穿。
 * - getOrAwait：飞行中去重，同一 key 并发请求共享一次 fetch，避免重复网络请求。
 *
 * @param ttlMillis 缓存有效期，默认 10 分钟
 * @param maxSize 最大条目数，0 表示不限制
 */
open class TtlCache<T>(
    private val ttlMillis: Long = 10 * 60 * 1000L,
    private val maxSize: Int = 0
) {
    private data class Entry<V>(val value: V, val expireAt: Long, val accessSeq: Long)

    private val cache = ConcurrentHashMap<String, Entry<T>>()
    private val accessCounter = AtomicLong(0L)

    /** 自上次 trim 后的新增计数，达到阈值才触发排序淘汰，避免每次 put 都全量排序 */
    private val trimCounter = AtomicLong(0L)

    /** 飞行中请求追踪：同一 key 的并发调用共享同一个 CompletableDeferred */
    private val inFlightRequests = ConcurrentHashMap<String, CompletableDeferred<T>>()

    open fun get(key: String): T? {
        val entry = cache[key] ?: return null
        // Long.MAX_VALUE 表示永不过期，跳过过期检查
        if (entry.expireAt != Long.MAX_VALUE && System.currentTimeMillis() > entry.expireAt) {
            cache.remove(key)
            return null
        }
        // 更新访问时间（LRU）—— 用 compute 保护 read-modify-write 原子，
        // 避免并发读同 key 时 accessSeq 更新丢失导致 LRU 序错乱（#28）
        val seq = accessCounter.incrementAndGet()
        cache.compute(key) { _, existing ->
            if (existing == null) null else existing.copy(accessSeq = seq)
        }
        return entry.value
    }

    open fun put(key: String, value: T) {
        val now = System.currentTimeMillis()
        // 防止 now + ttlMillis 溢出（Long.MAX_VALUE 作为"永不过期"时会导致溢出为负数，缓存立即失效）
        val expireAt = if (ttlMillis >= Long.MAX_VALUE - now) Long.MAX_VALUE else now + ttlMillis
        putInternal(key, value, expireAt)
    }

    /** 子类专用：用指定 expireAt 写入（如 PersistentTtlCache 从磁盘恢复时保留原始过期时间） */
    protected fun putInternal(key: String, value: T, expireAt: Long) {
        cache[key] = Entry(value, expireAt, accessCounter.incrementAndGet())
        trimCounter.incrementAndGet()
        trimToSize()
    }

    /** 子类专用：获取 key 的当前 expireAt（用于持久化时保存原始过期时间） */
    protected fun getExpireAt(key: String): Long? = cache[key]?.expireAt

    suspend fun getOrPut(key: String, defaultValue: suspend () -> T): T {
        // 委托给 getOrAwait,使用 per-key 飞行中去重,避免全局 Mutex 瓶颈
        return getOrAwait(key, fetch = defaultValue)
    }

    /**
     * 飞行中去重：同一 key 的并发调用共享一次 fetch。
     *
     * 适用场景：多个 ViewModel 并发请求同一接口，希望只发一次网络请求，
     * 其他调用方等待结果后各自处理 UI 更新。
     *
     * @param key 缓存 key
     * @param skipCache 是否跳过缓存读取（如重试场景），仍享受飞行中去重
     * @param fetch 获取数据的 lambda，仅在无缓存且无飞行中请求时执行
     */
    suspend fun getOrAwait(key: String, skipCache: Boolean = false, fetch: suspend () -> T): T {
        if (!skipCache) {
            get(key)?.let { return it }
        }
        // 尝试注册为飞行中请求的发起者
        val deferred = CompletableDeferred<T>()
        // fetch lambda 被 cancel 时（即使永远不抛异常也不返回）也能清理 inFlight 槽位（#37）
        deferred.invokeOnCompletion { inFlightRequests.remove(key, deferred) }
        val existing = inFlightRequests.putIfAbsent(key, deferred)
        if (existing != null) {
            // 已有飞行中请求，等待其结果
            return existing.await()
        }
        // 当前协程负责 fetch
        return try {
            val value = fetch()
            put(key, value)
            deferred.complete(value)
            value
        } catch (e: CancellationException) {
            // 发起方协程被取消：用普通异常通知等待方，避免级联取消不相关协程
            deferred.completeExceptionally(IOException("Fetch cancelled"))
            throw e
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            // invokeOnCompletion 已处理清理；此处冗余调用安全（remove(key, deferred) 仅在值匹配时删除）
            inFlightRequests.remove(key, deferred)
        }
    }

    fun clear() {
        cache.clear()
        // 清除飞行中请求追踪，防止 clear 后旧请求完成时把旧数据写回缓存，
        // 也防止新调用方 join 到已失效的飞行中请求
        inFlightRequests.clear()
    }

    private fun trimToSize() {
        if (maxSize <= 0) return
        val size = cache.size
        if (size <= maxSize) return
        // 批量化：仅当累积新增达到阈值时才排序淘汰，均摊排序开销
        val counter = trimCounter.get()
        val threshold = (maxSize / 4).coerceAtLeast(4)
        if (counter < threshold && size <= maxSize + threshold) return
        trimCounter.set(0)
        val toRemoveCount = (size - maxSize).coerceAtLeast(0)
        if (toRemoveCount <= 0) return
        val toRemove = cache.entries.sortedBy { it.value.accessSeq }.take(toRemoveCount)
        toRemove.forEach { cache.remove(it.key) }
    }
}
