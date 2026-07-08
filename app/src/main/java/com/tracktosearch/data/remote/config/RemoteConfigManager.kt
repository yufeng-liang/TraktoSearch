package com.tracktosearch.data.remote.config

import android.util.Base64
import android.util.Log
import com.tracktosearch.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程配置管理器:拉取 + AES-256-GCM 解密 + JSON 解析 + 持久化 + 同步查询。
 *
 * 初始化时序:
 * 1. [initialize] 在 IO 协程异步执行,不阻塞 UI 线程
 * 2. 先从 DataStore 加载缓存到内存 → 立即可用(毫秒级)
 * 3. 若缓存超 24h 或无缓存 → 后台拉取云端 config.json.enc
 * 4. 拉取成功 → 解密 → 解析 → 校验 schema 版本 → 更新内存 + DataStore
 * 5. 失败则继续用缓存;首次安装无缓存 → 调用方用 BuildConfig 兜底
 *
 * 降级链路(任何环节失败都不得让 App 无 key 可用):
 * - 网络失败 → 用 DataStore 缓存(即使已过期)
 * - 解密失败 → 用 DataStore 缓存;若无缓存 → 调用方用 BuildConfig
 * - JSON 解析失败 → 同上
 * - schema 版本不匹配 → 忽略新配置,继续用缓存
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
        private const val GCM_IV_LENGTH = 12   // 字节
        private const val GCM_TAG_LENGTH = 16   // 字节
        private const val GCM_TAG_LENGTH_BITS = GCM_TAG_LENGTH * 8  // 128 bits
        private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
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
                Log.w(TAG, "从 DataStore 加载缓存失败,稍后尝试从云端拉取", e)
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

    /** 是否已初始化完成(DataStore 加载到内存)。 */
    override fun isInitialized(): Boolean = initializedDeferred.isCompleted

    /**
     * 强制刷新:从云端重新拉取配置。
     *
     * @return 成功返回 Result.success,失败返回 Result.failure(不抛异常)
     */
    override suspend fun refresh(): Result<Unit> = refreshMutex.withLock {
        try {
            val encryptedBase64 = configApiService.fetchEncryptedConfig().string()
            val decryptedJson = decrypt(encryptedBase64)
                ?: return Result.failure(Exception("解密失败:密钥不匹配或数据损坏"))
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
            Log.w(TAG, "拉取云端配置失败,继续用缓存", e)
            Result.failure(e)
        }
    }

    /**
     * AES-256-GCM 解密。
     *
     * 密文格式:base64(iv(12) || ciphertext || tag(16))
     *
     * @return 解密后的 JSON 字符串,失败返回 null
     */
    private fun decrypt(encryptedBase64: String): String? {
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.DEFAULT)
            if (combined.size < GCM_IV_LENGTH + GCM_TAG_LENGTH) return null

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            // Java GCM 契约: doFinal 输入必须是 ciphertext || tag,引擎从末尾 16 字节取 tag 校验
            // 服务端封包格式: iv(12) || ciphertext || tag(16),故 [12, size) 即为所需
            val cipherPayload = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val keyBytes = hexToBytes(BuildConfig.CONFIG_AES_KEY)
            if (keyBytes.size != 32) return null

            val keySpec = SecretKeySpec(keyBytes, KEY_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)

            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val decrypted = cipher.doFinal(cipherPayload)
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "AES-GCM 解密失败", e)
            null
        }
    }

    /** hex 字符串转字节数组。 */
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
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

    override fun getStringList(key: String, fallback: List<String>): List<String> {
        val element = cachedValues[key] ?: return fallback
        return try {
            val array = element as? JsonArray ?: return fallback
            array.mapNotNull { item ->
                (item as? JsonPrimitive)?.contentOrNull
            }
        } catch (e: Exception) {
            fallback
        }
    }
}
