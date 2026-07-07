package com.tracktosearch.data.remote.config

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * API key 池状态机:管理 key 轮换、429 冷却、401 失效标记。
 *
 * 状态转移:
 * - ACTIVE → COOLING(429):冷却 5 分钟后转回 ACTIVE
 * - ACTIVE → INVALID(401/403):直到下次配置刷新(resetAll)才恢复
 *
 * pickKey() 算法:
 * 1. 第一个 ACTIVE
 * 2. 若无 ACTIVE,第一个 COOLING 且已过冷却期的(转回 ACTIVE)
 * 3. 若全失效,返回第一个 key(宁可重试也不要无 key,可能服务端临时抽风)
 * 4. key 池为空 → 返回 [fallbackKey](BuildConfig 兜底)
 *
 * 线程安全:用 @Synchronized 保证状态读写原子性。
 */
class ApiKeyProvider @Inject constructor(
    private val remoteConfig: RemoteConfigProvider,
    private val configKey: String,         // 如 "tmdb.apiKeys"
    private val fallbackKey: String        // 如 BuildConfig.TMDB_API_KEY
) {
    enum class KeyStatus { ACTIVE, COOLING, INVALID }

    data class KeyState(
        val key: String,
        val status: KeyStatus,
        val cooldownUntilMs: Long  // 仅 COOLING 状态有效
    )

    companion object {
        private const val COOLDOWN_MS = 5L * 60 * 1000  // 5 分钟
    }

    private val states = AtomicReference<List<KeyState>>(emptyList())

    /**
     * 选取当前可用的 key。
     *
     * 优先级:ACTIVE > COOLING 已过期(转回 ACTIVE)> 全失效时返回第一个 > 池空返回 fallback。
     */
    @Synchronized
    fun pickKey(): String {
        // 从 RemoteConfig 加载 key 池(每次调用都查,确保配置刷新后立即生效)
        val keys = remoteConfig.getStringList(configKey)
        if (keys.isEmpty()) return fallbackKey

        // 同步 key 池(添加新 key、保留旧 key 状态)
        syncKeyPool(keys)

        val current = states.get()

        // 1. 第一个 ACTIVE
        current.firstOrNull { it.status == KeyStatus.ACTIVE }?.let { return it.key }

        // 2. 第一个 COOLING 已过冷却期的(转回 ACTIVE)
        val now = System.currentTimeMillis()
        val firstCoolingExpired = current.firstOrNull {
            it.status == KeyStatus.COOLING && it.cooldownUntilMs <= now
        }
        if (firstCoolingExpired != null) {
            val updated = current.map { state ->
                if (state.key == firstCoolingExpired.key) state.copy(status = KeyStatus.ACTIVE)
                else state
            }
            states.set(updated)
            return firstCoolingExpired.key
        }

        // 3. 全失效 → 返回第一个 key(宁可重试也不要无 key)
        return current.firstOrNull()?.key ?: fallbackKey
    }

    /**
     * 标记失败的 key 并触发轮换。
     *
     * @param failedKey 失败的 key
     * @param httpCode HTTP 状态码(429 / 401 / 403)
     */
    @Synchronized
    fun markAndRotate(failedKey: String, httpCode: Int) {
        val current = states.get()
        val now = System.currentTimeMillis()
        val updated = current.map { state ->
            if (state.key == failedKey) {
                when (httpCode) {
                    429 -> state.copy(status = KeyStatus.COOLING, cooldownUntilMs = now + COOLDOWN_MS)
                    401, 403 -> state.copy(status = KeyStatus.INVALID)
                    else -> state  // 其他错误码不切 key
                }
            } else state
        }
        states.set(updated)
    }

    /**
     * 重置所有 key 状态为 ACTIVE。
     * RemoteConfigManager 刷新配置成功后调用。
     */
    @Synchronized
    fun resetAll() {
        val keys = remoteConfig.getStringList(configKey)
        states.set(keys.map { KeyState(it, KeyStatus.ACTIVE, 0) })
    }

    /** 同步 key 池:添加新 key,保留旧 key 状态。 */
    private fun syncKeyPool(keys: List<String>) {
        val current = states.get()
        val currentKeySet = current.map { it.key }.toSet()

        // 检查是否需要更新(key 列表变化时)
        if (currentKeySet == keys.toSet()) return

        val updated = keys.map { newKey ->
            // 旧 key 保留状态,新 key 默认 ACTIVE
            current.firstOrNull { it.key == newKey } ?: KeyState(newKey, KeyStatus.ACTIVE, 0)
        }
        states.set(updated)
    }
}
