package com.tracktosearch.data.remote.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 云端配置(JSON Schema v1)。
 *
 * 客户端只支持 [version] == 1。未来若 schema 主版本号变更,老 App 会忽略整份配置
 * 继续用本地缓存或 BuildConfig 兜底(详见 RemoteConfigManager 降级链路)。
 *
 * 安全说明:配置文件经 AES-256-GCM 加密,解密 key 嵌入 BuildConfig.CONFIG_AES_KEY。
 * 解密 key 嵌入 APK 理论上可被反编译,这是 YAGNI 范围内的可接受风险
 * (目标是防中间人篡改 + 防爬虫直连明文,不是对抗逆向工程)。
 */
@Serializable
data class RemoteConfig(
    /** Schema 版本号。当前 = 1。 */
    val version: Int = 1,
    /** ISO-8601 更新时间戳,用于客户端判断是否需要拉取(配合 24h 缓存)。 */
    val updatedAt: String = "",
    /** 通用 key-value 配置 map,点号命名空间(如 "tmdb.apiKeys")。 */
    val values: Map<String, JsonElement> = emptyMap()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * 配置查询接口。RemoteConfigManager 实现此接口,便于测试中注入 Mock。
 *
 * 所有方法同步返回(已初始化后必返回值,无缓存返回 default 或 fallback)。
 */
interface RemoteConfigProvider {
    /** 同步查询字符串配置。无值返回 [default]。 */
    fun get(key: String, default: String = ""): String

    /** 同步查询字符串配置。无值返回 null。 */
    fun getOrNull(key: String): String?

    /** 同步查询整数配置。无值返回 [default]。 */
    fun getInt(key: String, default: Int): Int

    /** 同步查询布尔配置。无值返回 [default]。 */
    fun getBoolean(key: String, default: Boolean): Boolean

    /**
     * 同步查询字符串列表(用于 tmdb.apiKeys 等数组字段)。
     * 无值或类型不匹配返回 [fallback]。
     */
    fun getStringList(key: String, fallback: List<String> = emptyList()): List<String>

    /** 强制刷新(从云端重新拉取)。 */
    suspend fun refresh(): Result<Unit>

    /** 是否已初始化完成(DataStore 加载到内存)。 */
    fun isInitialized(): Boolean
}
