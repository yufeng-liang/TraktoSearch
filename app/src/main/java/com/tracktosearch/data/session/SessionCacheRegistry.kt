package com.tracktosearch.data.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** 当前会话已经失效，调用方不应继续消费旧会话结果。 */
internal class SessionCacheInvalidatedException : CancellationException("Session cache invalidated")

/**
 * 当前 Trakt 会话私有缓存的统一失效 seam。
 *
 * 每次会话切换先递增代次，再按注册顺序清理缓存。异步读取可以在提交结果前
 * 使用捕获的代次调用 [isCurrent]，从而避免旧会话的结果写入新会话缓存。
 */
class SessionCacheRegistry {
    private val generation = AtomicLong(0L)
    private val lock = Any()
    private val invalidationMutex = Mutex()
    private val invalidators = mutableListOf<suspend () -> Unit>()

    /** 当前会话缓存代次。 */
    fun currentGeneration(): Long = generation.get()

    /** 判断异步操作开始时捕获的代次是否仍属于当前会话。 */
    fun isCurrent(capturedGeneration: Long): Boolean =
        generation.get() == capturedGeneration

    /** 注册一个会话缓存失效动作。重复注册由调用方负责避免。 */
    fun register(invalidator: suspend () -> Unit) {
        synchronized(lock) {
            invalidators += invalidator
        }
    }

    /** 递增缓存代次并清理全部已注册缓存。 */
    suspend fun invalidateAll() {
        invalidationMutex.withLock {
            generation.incrementAndGet()
            val snapshot = synchronized(lock) { invalidators.toList() }
            snapshot.forEach { it() }
        }
    }

    /**
     * 在失效操作与异步结果提交之间建立原子 seam。
     *
     * 网络请求本身不持有该锁；只有最终写入缓存的短临界区需要使用它。
     * 这样失效一旦开始，旧代次就不会在清理完成后重新写回缓存。
     */
    suspend fun <T> withCurrentGeneration(
        capturedGeneration: Long,
        block: () -> T
    ): T? = invalidationMutex.withLock {
        if (!isCurrent(capturedGeneration)) null else block()
    }

    /** 要求提交时仍属于当前会话；失效后以取消语义终止旧调用方。 */
    suspend fun <T> requireCurrentGeneration(
        capturedGeneration: Long,
        block: () -> T
    ): T = withCurrentGeneration(capturedGeneration, block)
        ?: throw SessionCacheInvalidatedException()
}
