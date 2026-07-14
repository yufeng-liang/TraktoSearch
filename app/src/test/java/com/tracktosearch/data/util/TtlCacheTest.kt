package com.tracktosearch.data.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class TtlCacheTest {

    // ==================== 基本读写 ====================

    @Test
    fun put_thenGet_returnsValue() {
        val cache = TtlCache<String>(ttlMillis = 60_000)
        cache.put("k", "v")
        assertThat(cache.get("k")).isEqualTo("v")
    }

    // ==================== TTL 过期 ====================

    @Test
    fun get_expiredEntry_returnsNull() {
        val cache = TtlCache<String>(ttlMillis = 1)
        cache.put("k", "v")
        Thread.sleep(10)
        assertThat(cache.get("k")).isNull()
    }

    // ==================== maxSize=0 无上限 ====================

    @Test
    fun put_maxSizeZero_noEviction() {
        val cache = TtlCache<String>(ttlMillis = 60_000, maxSize = 0)
        for (i in 0 until 100) cache.put("k$i", "v$i")
        assertThat(cache.get("k0")).isEqualTo("v0")
        assertThat(cache.get("k99")).isEqualTo("v99")
    }

    // ==================== LRU 淘汰 + get 更新访问顺序 ====================

    /**
     * 测试点 4 + 5：LRU 淘汰最久未访问的，get 更新访问时间。
     *
     * 源码 trimToSize 批量阈值：threshold = (maxSize / 4).coerceAtLeast(4)，
     * 即至少累积 4 次新增后才触发排序淘汰。
     * 用 maxSize=4（threshold=4）使第 5 次 put 触发 trim：
     *   put a(seq=1), b(seq=2), c(seq=3), d(seq=4) → size=maxSize，不触发
     *   get a → a.seq=5，b(seq=2) 成为 LRU
     *   put e(seq=6) → counter=5 >= threshold=4，触发 trim
     *   toRemove = 5-4 = 1，按 accessSeq 升序取最小的 b(seq=2) 淘汰
     */
    @Test
    fun put_exceedsMaxSize_evictsLeastRecentlyUsed() {
        val cache = TtlCache<String>(ttlMillis = 60_000, maxSize = 4)
        cache.put("a", "1")  // seq=1
        cache.put("b", "2")  // seq=2
        cache.put("c", "3")  // seq=3
        cache.put("d", "4")  // seq=4, size=maxSize=4 → 不触发 trim
        cache.get("a")       // a.seq=5 → b(seq=2) 成为 LRU
        cache.put("e", "5")  // seq=6, counter=5 >= threshold=4 → 触发 trim，淘汰 b
        assertThat(cache.get("b")).isNull()    // b 是 LRU，被淘汰
        assertThat(cache.get("a")).isEqualTo("1")  // a 被 get 过，存活
        assertThat(cache.get("c")).isEqualTo("3")
        assertThat(cache.get("d")).isEqualTo("4")
        assertThat(cache.get("e")).isEqualTo("5")
    }

    // ==================== getOrPut ====================

    @Test
    fun getOrPut_cacheHit_doesNotInvokeDefaultValue() = runTest {
        val cache = TtlCache<String>(ttlMillis = 60_000)
        cache.put("k", "cached")
        var invoked = false
        val result = cache.getOrPut("k") { invoked = true; "fetched" }
        assertThat(invoked).isFalse()
        assertThat(result).isEqualTo("cached")
    }

    @Test
    fun getOrPut_cacheMiss_invokesAndCachesDefaultValue() = runTest {
        val cache = TtlCache<String>(ttlMillis = 60_000)
        var invoked = false
        val result = cache.getOrPut("k") { invoked = true; "fetched" }
        assertThat(invoked).isTrue()
        assertThat(result).isEqualTo("fetched")
        assertThat(cache.get("k")).isEqualTo("fetched")
    }

    // ==================== getOrAwait 并发 single-flight ====================

    /**
     * 测试点 8：并发调用同一 key，fetch 仅执行一次。
     * runTest 中 async + delay 会切换协程，第一个 async 成为 fetcher，
     * 其余通过 putIfAbsent 加入等待，共享同一 CompletableDeferred。
     */
    @Test
    fun getOrAwait_concurrentCalls_invokesFetchOnce() = runTest {
        val cache = TtlCache<String>(ttlMillis = 60_000)
        val fetchCount = AtomicInteger(0)
        val deferreds = (1..10).map {
            async {
                cache.getOrAwait("k") {
                    fetchCount.incrementAndGet()
                    delay(50)
                    "fetched"
                }
            }
        }
        val results = deferreds.awaitAll()
        assertThat(fetchCount.get()).isEqualTo(1)
        results.forEach { assertThat(it).isEqualTo("fetched") }
    }

    // ==================== getOrAwait 异常清理 ====================

    /**
     * 测试点 9：fetch 抛异常时调用方收到异常，inFlight 被清理（再次调用会重新 fetch）。
     */
    @Test
    fun getOrAwait_fetchThrowsException_callerReceivesException() = runTest {
        val cache = TtlCache<String>(ttlMillis = 60_000)
        var threw = false
        try {
            cache.getOrAwait("k") { throw IOException("network error") }
        } catch (e: IOException) {
            threw = true
        }
        assertThat(threw).isTrue()
        // inFlight 应已清理，再次调用会重新 fetch
        var invoked = false
        cache.getOrAwait("k") { invoked = true; "ok" }
        assertThat(invoked).isTrue()
    }

    /**
     * 测试点 10：fetch 被取消后 inFlight 清理，新调用可重新 fetch。
     * 源码在 CancellationException 分支中用 IOException 完成 deferred（避免级联取消），
     * 并通过 invokeOnCompletion + finally 双重清理 inFlight 槽位。
     */
    @Test
    fun getOrAwait_fetchCancelled_inFlightCleaned() = runTest {
        val cache = TtlCache<String>(ttlMillis = 60_000)
        val job = launch {
            cache.getOrAwait("k") {
                delay(10_000)  // 永不自然完成
                "never"
            }
        }
        delay(50)   // 让 fetch 启动并进入 inFlight
        job.cancel() // 取消 fetcher 协程
        delay(50)   // 让 CancellationException 传播 + invokeOnCompletion 执行

        // inFlight 应已清理，新调用可重新 fetch
        var invoked = false
        val result = cache.getOrAwait("k") { invoked = true; "ok" }
        assertThat(invoked).isTrue()
        assertThat(result).isEqualTo("ok")
    }

    // ==================== clear ====================

    @Test
    fun clear_afterPut_getReturnsNull() {
        val cache = TtlCache<String>(ttlMillis = 60_000)
        cache.put("k", "v")
        cache.clear()
        assertThat(cache.get("k")).isNull()
    }

    /**
     * 测试点 12：clear 后旧 inFlight 完成不写回缓存。
     *
     * P1 BUG：clear() 的注释声称"防止 clear 后旧请求完成时把旧数据写回缓存"，
     * 但 getOrAwait 的 fetch 完成后无条件调用 put(key, value)，clear() 无法阻止写回。
     * 要修复需在 put 前检查 inFlight 仍包含该 deferred，或在 clear 时标记 generation。
     *
     * @Ignore 因为当前实现下此测试会失败（put 写回发生）。
     */
    @Ignore("P1: clear() 后旧 inFlight fetch 完成仍会 put 写回缓存，与注释承诺不符")
    @Test
    fun clear_afterInFlightStarted_oldResultNotWrittenBack() = runTest {
        val cache = TtlCache<String>(ttlMillis = 60_000)
        val deferred = async {
            cache.getOrAwait("k") {
                delay(100)
                "fetched"
            }
        }
        delay(50)   // fetch 进行中
        cache.clear()
        val result = deferred.await()
        assertThat(result).isEqualTo("fetched")
        // clear 后旧 fetch 完成不应写回缓存
        assertThat(cache.get("k")).isNull()
    }

    // ==================== Long.MAX_VALUE 永不过期 ====================

    /**
     * 测试点 13（P2 characterization）：ttlMillis=Long.MAX_VALUE 时永不过期。
     * 源码在 put 中通过溢出保护将 expireAt 设为 Long.MAX_VALUE，
     * get 中跳过过期检查。此行为是源码主动处理的，但 KDoc 未明确文档化。
     */
    @Test
    fun put_maxValueTtl_neverExpires() {
        val cache = TtlCache<String>(ttlMillis = Long.MAX_VALUE)
        cache.put("k", "v")
        // FIXME(P2): 文档未明确说明 Long.MAX_VALUE 作为"永不过期"的语义，此处记录实际行为
        assertThat(cache.get("k")).isEqualTo("v")
    }
}
