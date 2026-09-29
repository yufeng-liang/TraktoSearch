package com.tracktosearch.data.remote.config

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程配置管理器:拉取(网关服务端解密) + JSON 解析 + 持久化 + 同步查询。
 *
 * 初始化时序:
 * 1. [initialize] 在 IO 协程异步执行,不阻塞 UI 线程
 * 2. 先从 DataStore 加载缓存到内存 → 立即可用(毫秒级)
 * 3. 若缓存超 24h 或无缓存 → 后台拉取网关 /api/config(明文 JSON)
 * 4. 拉取成功 → 解析 → 校验 schema 版本 → 更新内存 + DataStore
 * 5. 失败则继续用缓存;首次安装无缓存 → 调用方用 BuildConfig 兜底
 *
 * 降级链路(任何环节失败都不得让 App 无 key 可用):
 * - 网络失败 → 用 DataStore 缓存(即使已过期)
 * - JSON 解析失败 → 同上
 * - schema 版本不匹配 → 忽略新配置,继续用缓存
 *
 * 安全说明:配置解密由网关 auth-worker 服务端完成(CONFIG_AES_KEY 作为 Worker Secret),
 * 客户端只接收明文 JSON,密钥不再编译进 APK。
 */
@Singleton
class RemoteConfigManager @Inject constructor(
    private val configApiService: ConfigApiService,
    private val storage: RemoteConfigStorage,
    private val json: Json
) : RemoteConfigProvider {

    companion object {
        private const val TAG = "RemoteConfigManager"
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000  // 24 小时
    }

    /** 内存缓存:配置 values map + schema 版本,初始化后立即可读 */
    @Volatile
    private var cachedValues: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()

    @Volatile
    private var cachedSchemaVersion: Int = 1

    /** 初始化完成标志,供 awaitInitialized 挂起等待 */
    private val initializedDeferred = CompletableDeferred<Unit>()

    /** 拉取互斥锁,避免并发触发多次拉取 */
    private val refreshMutex = Mutex()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 初始化:从 DataStore 加载缓存到内存,并按需后台拉取。
     * 应在 Application.onCreate 中调用(IO 协程,不阻塞 UI)。
     */
    fun initialize() {
        ioScope.launch {
            // 1. 从 DataStore 加载缓存到内存
            try {
                val cachedJson = storage.getCachedConfigJson()
                val cachedVersion = storage.getCachedSchemaVersion()
                if (!cachedJson.isNullOrBlank() && cachedVersion != null) {
                    val config = json.decodeFromString(RemoteConfig.serializer(), cachedJson)
                    if (config.version == RemoteConfig.CURRENT_VERSION) {
                        cachedValues = config.values
                        cachedSchemaVersion = config.version
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "从 DataStore 加载缓存失败,稍后尝试从网关拉取", e)
            }

            // 2. 标记初始化完成(无论是否有缓存,后续查询都可返回 fallback)
            initializedDeferred.complete(Unit)

            // 3. 若缓存超 24h 或无缓存,后台拉取
            val lastFetchTs = storage.getLastFetchTimestamp()
            val now = System.currentTimeMillis()
            if (lastFetchTs == 0L || now - lastFetchTs > CACHE_TTL_MS) {
                refresh()
            }
        }
    }

    /** 等待初始化完成(磁盘加载完毕)。供需要确保读到缓存的调用方使用。 */
    suspend fun awaitInitialized() = initializedDeferred.await()

    /**
     * 强制刷新:从网关重新拉取明文配置。
     *
     * @return 成功返回 Result.success,失败返回 Result.failure(不抛异常)
     */
    override suspend fun refresh(): Result<Unit> = refreshMutex.withLock {
        try {
            // 网关服务端已解密,直接拿到明文 JSON
            val decryptedJson = configApiService.fetchConfig()
            val config = json.decodeFromString(RemoteConfig.serializer(), decryptedJson)

            // schema 版本不匹配 → 忽略,继续用本地缓存
            if (config.version != RemoteConfig.CURRENT_VERSION) {
                Log.w(TAG, "schema 版本不匹配:云端=${config.version},本地=${RemoteConfig.CURRENT_VERSION},忽略新配置")
                return Result.failure(Exception("schema 版本不匹配:云端=${config.version},本地=${RemoteConfig.CURRENT_VERSION}"))
            }

            // 更新内存 + DataStore
            cachedValues = config.values
            cachedSchemaVersion = config.version
            storage.saveConfig(decryptedJson, config.version)
            Log.d(TAG, "配置已更新,values 数量=${config.values.size}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "拉取网关配置失败,继续用缓存", e)
            Result.failure(e)
        }
    }

    // ==================== 同步查询 API ====================

    override fun get(key: String, default: String): String {
        return getOrNull(key) ?: default
    }

    override fun getOrNull(key: String): String? {
        val element = cachedValues[key] ?: return null
        return try {
            element.jsonPrimitive.contentOrNull
        } catch (e: Exception) {
            null
        }
    }

    override fun getInt(key: String, default: Int): Int {
        return getOrNull(key)?.toIntOrNull() ?: default
    }

    override fun getBoolean(key: String, default: Boolean): Boolean {
        return getOrNull(key)?.toBooleanStrictOrNull() ?: default
    }
}
