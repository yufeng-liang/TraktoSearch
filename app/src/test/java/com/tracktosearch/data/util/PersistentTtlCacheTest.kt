package com.tracktosearch.data.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Serializable
data class PersistentTestItem(val name: String, val value: Int)

/**
 * PersistentTtlCache 单元测试（Robolectric + 真实 DataStore）。
 *
 * 测试策略：
 * - put/get/putAll 测内存层（同步），不依赖磁盘 I/O 时序
 * - loadFromDisk/snapshotFromDisk/clearAll/getSizeBytes 测磁盘层，
 *   直接用 dataStore.edit 写入数据（同步可靠），避免 put 的异步磁盘写入竞态
 * - 每个测试用唯一 DataStore 文件名 + keyPrefix，避免跨测试污染
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PersistentTtlCacheTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer: KSerializer<PersistentTestItem> = PersistentTestItem.serializer()
    private var dataStoreCounter = 0

    // ==================== 1. put 后 get 返回值（内存缓存） ====================

    @Test
    fun put_thenGet_returnsValue() = runTest {
        val cache = createCache(createDataStore(), "v1", backgroundScope)
        cache.put("k1", PersistentTestItem("foo", 1))
        assertThat(cache.get("k1")).isEqualTo(PersistentTestItem("foo", 1))
    }

    // ==================== 2. loadFromDisk 从 DataStore 恢复数据到内存 ====================

    @Test
    fun loadFromDisk_restoresEntries() = runTest {
        val dataStore = createDataStore()
        // 直接写 DataStore（同步），避免 put 的异步磁盘写入竞态
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("v2:k1")] = """{"name":"foo","value":1}"""
            prefs[longPreferencesKey("v2:k1:exp")] = System.currentTimeMillis() + 60_000
        }
        val cache = createCache(dataStore, "v2", backgroundScope)
        cache.loadFromDisk()
        assertThat(cache.get("k1")).isEqualTo(PersistentTestItem("foo", 1))
    }

    // ==================== 3. loadFromDisk 跳过已过期条目 ====================

    @Test
    fun loadFromDisk_skipsExpiredEntries() = runTest {
        val dataStore = createDataStore()
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("v3:k1")] = """{"name":"foo","value":1}"""
            // expireAt 为过去时间 → 已过期
            prefs[longPreferencesKey("v3:k1:exp")] = System.currentTimeMillis() - 1000
        }
        val cache = createCache(dataStore, "v3", backgroundScope)
        cache.loadFromDisk()
        assertThat(cache.get("k1")).isNull()
    }

    // ==================== 4. awaitLoaded 等待磁盘加载完成 ====================

    @Test
    fun awaitLoaded_afterLoadFromDisk_returnsImmediately() = runTest {
        val cache = createCache(createDataStore(), "v4", backgroundScope)
        cache.loadFromDisk()
        // loadFromDisk 已完成，awaitLoaded 应立即返回
        cache.awaitLoaded()
    }

    @Test
    fun awaitLoaded_beforeLoadFromDisk_blocksUntilLoadCompletes() = runTest {
        val cache = createCache(createDataStore(), "v4b", backgroundScope)
        var awaitCompleted = false
        val awaitJob = launch {
            cache.awaitLoaded()
            awaitCompleted = true
        }
        // 让 launched 协程启动并挂起在 awaitLoaded
        runCurrent()
        assertThat(awaitCompleted).isFalse()

        // 调用 loadFromDisk 完成 loadedDeferred
        cache.loadFromDisk()
        awaitJob.join()
        assertThat(awaitCompleted).isTrue()
    }

    // ==================== 5. snapshotFromDisk 返回磁盘所有未过期条目 ====================

    @Test
    fun snapshotFromDisk_returnsAllUnexpiredEntries() = runTest {
        val dataStore = createDataStore()
        dataStore.edit { prefs ->
            // 两个未过期条目
            prefs[stringPreferencesKey("v5:k1")] = """{"name":"a","value":1}"""
            prefs[longPreferencesKey("v5:k1:exp")] = System.currentTimeMillis() + 60_000
            prefs[stringPreferencesKey("v5:k2")] = """{"name":"b","value":2}"""
            prefs[longPreferencesKey("v5:k2:exp")] = System.currentTimeMillis() + 60_000
            // 一个已过期条目（应被排除）
            prefs[stringPreferencesKey("v5:k3")] = """{"name":"c","value":3}"""
            prefs[longPreferencesKey("v5:k3:exp")] = System.currentTimeMillis() - 1000
        }
        val cache = createCache(dataStore, "v5", backgroundScope)
        val snapshot = cache.snapshotFromDisk()
        assertThat(snapshot).hasSize(2)
        assertThat(snapshot["k1"]).isEqualTo(PersistentTestItem("a", 1))
        assertThat(snapshot["k2"]).isEqualTo(PersistentTestItem("b", 2))
        assertThat(snapshot).doesNotContainKey("k3")
    }

    // ==================== 6. putAll(多条) 批量写入 ====================

    @Test
    fun putAll_multipleEntries_writesAll() = runTest {
        val cache = createCache(createDataStore(), "v6", backgroundScope)
        val entries = mapOf(
            "k1" to PersistentTestItem("a", 1),
            "k2" to PersistentTestItem("b", 2),
            "k3" to PersistentTestItem("c", 3)
        )
        val written = cache.putAll(entries)
        assertThat(written).isEqualTo(3)
        assertThat(cache.get("k1")).isEqualTo(PersistentTestItem("a", 1))
        assertThat(cache.get("k2")).isEqualTo(PersistentTestItem("b", 2))
        assertThat(cache.get("k3")).isEqualTo(PersistentTestItem("c", 3))
    }

    // ==================== 7. putAll(overwrite=true) 覆盖已有条目 ====================

    @Test
    fun putAll_overwriteTrue_overwritesExisting() = runTest {
        val cache = createCache(createDataStore(), "v7", backgroundScope)
        cache.put("k1", PersistentTestItem("original", 0))
        val written = cache.putAll(
            mapOf("k1" to PersistentTestItem("updated", 1)),
            overwrite = true
        )
        assertThat(written).isEqualTo(1)
        assertThat(cache.get("k1")).isEqualTo(PersistentTestItem("updated", 1))
    }

    // ==================== 8. putAll(overwrite=false) 跳过已有条目 ====================

    @Test
    fun putAll_overwriteFalse_skipsExisting() = runTest {
        val cache = createCache(createDataStore(), "v8", backgroundScope)
        cache.put("k1", PersistentTestItem("original", 0))
        val written = cache.putAll(
            mapOf("k1" to PersistentTestItem("updated", 1)),
            overwrite = false
        )
        assertThat(written).isEqualTo(0)
        assertThat(cache.get("k1")).isEqualTo(PersistentTestItem("original", 0))
    }

    // ==================== 9. clearAll 清空内存与磁盘 ====================

    @Test
    fun clearAll_removesAllEntries() = runTest {
        val dataStore = createDataStore()
        // 直接写 DataStore，避免 put 的异步写入竞态
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("v9:k1")] = """{"name":"foo","value":1}"""
            prefs[longPreferencesKey("v9:k1:exp")] = System.currentTimeMillis() + 60_000
        }
        val cache = createCache(dataStore, "v9", backgroundScope)
        cache.loadFromDisk()
        assertThat(cache.get("k1")).isNotNull()

        cache.clearAll()
        // 内存已清空
        assertThat(cache.get("k1")).isNull()
        // 磁盘也已清空
        assertThat(cache.snapshotFromDisk()).isEmpty()
    }

    // ==================== 10. getSizeBytes 返回正数 ====================

    @Test
    fun getSizeBytes_returnsPositive() = runTest {
        val dataStore = createDataStore()
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("v10:k1")] = """{"name":"foo","value":1}"""
            prefs[longPreferencesKey("v10:k1:exp")] = System.currentTimeMillis() + 60_000
        }
        val cache = createCache(dataStore, "v10", backgroundScope)
        val size = cache.getSizeBytes()
        assertThat(size).isGreaterThan(0L)
    }

    // ==================== 11. key 版本号失效：keyPrefix 变更后旧缓存不命中 ====================

    @Test
    fun keyPrefixChange_oldCacheMisses() = runTest {
        val dataStore = createDataStore()
        // 用旧 keyPrefix 写入数据
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("v11_old:k1")] = """{"name":"foo","value":1}"""
            prefs[longPreferencesKey("v11_old:k1:exp")] = System.currentTimeMillis() + 60_000
        }
        // 用新 keyPrefix 创建 cache，旧数据不应命中
        val cache = createCache(dataStore, "v11_new", backgroundScope)
        cache.loadFromDisk()
        assertThat(cache.get("k1")).isNull()
        assertThat(cache.snapshotFromDisk()).isEmpty()
        // getSizeBytes 也只统计新前缀的 key
        assertThat(cache.getSizeBytes()).isEqualTo(0L)
    }

    // ==================== 辅助方法 ====================

    @Suppress("unused")
    private val dummyProperty: Int = 0

    private fun createDataStore(): DataStore<Preferences> {
        val context: Context = RuntimeEnvironment.getApplication()
        val name = "test_cache_${dataStoreCounter++}_${System.nanoTime()}"
        // 每次调用 preferencesDataStore 创建新 delegate 实例（独立 INSTANCE 缓存）
        val delegate = preferencesDataStore(name = name)
        return delegate.getValue(context, ::dummyProperty)
    }

    private fun createCache(
        dataStore: DataStore<Preferences>,
        keyPrefix: String,
        scope: CoroutineScope,
        ttlMillis: Long = 60_000L
    ): PersistentTtlCache<PersistentTestItem> {
        return PersistentTtlCache(
            ttlMillis = ttlMillis,
            maxSize = 100,
            dataStore = dataStore,
            json = json,
            serializer = serializer,
            keyPrefix = keyPrefix,
            scope = scope
        )
    }
}
