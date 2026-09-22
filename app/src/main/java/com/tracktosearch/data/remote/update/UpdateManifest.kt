package com.tracktosearch.data.remote.update

import kotlinx.serialization.Serializable
import retrofit2.http.GET

/**
 * 发版清单：R2 上的 manifest/latest.json，由 CI 在发布时写入。
 * 官网下载按钮与应用内「检查更新」读同一份，字段契约见 website 仓库
 * functions/manifest/[[path]].js。
 */
@Serializable
data class UpdateManifest(
    val schema: Int = 0,
    val latest: ManifestRelease? = null
)

@Serializable
data class ManifestRelease(
    val versionName: String = "",
    val versionCode: Int = 0,
    /** 与 url 末段一致的 APK 文件名 */
    val fileName: String = "",
    /** 相对站点根的路径，如 /dl/TraktoSearch-v3.6.0.apk */
    val url: String = "",
    val sha256: String = "",
    val size: Long = 0,
    /** yyyy-MM-dd，供 changelog 标题注入日期 */
    val releaseDate: String = "",
    val minSdk: Int = 0,
    val changelog: Map<String, String> = emptyMap()
) {
    /** v1 只写 zh-CN；缺省时退回任一非空语言，避免更新弹窗出现空白正文。 */
    fun changelogText(): String =
        changelog["zh-CN"] ?: changelog.values.firstOrNull { it.isNotBlank() }.orEmpty()

    /** 版本号缺失或格式异常时不可用于判定更新，交给上层走降级链路。 */
    fun isValid(): Boolean = versionName.isNotBlank() && url.isNotBlank()
}

interface UpdateManifestApiService {
    @GET("manifest/latest.json")
    suspend fun getLatest(): UpdateManifest

    /** 全量历史版本更新日志，供设置页展示；由发布流程从 GitHub Release 汇总生成 */
    @GET("manifest/history.json")
    suspend fun getHistory(): UpdateHistory
}

/**
 * 全量更新日志（manifest/history.json）。顺序由发布方保证为新→旧，
 * 客户端不重排，避免版本比较规则与展示顺序耦合。
 */
@Serializable
data class UpdateHistory(
    val schema: Int = 0,
    val releases: List<HistoryEntry> = emptyList()
)

@Serializable
data class HistoryEntry(
    val versionName: String = "",
    /** 形如 v3.6.0；缺失时由 versionName 补出 */
    val tagName: String = "",
    /** yyyy-MM-dd */
    val releaseDate: String = "",
    val changelog: Map<String, String> = emptyMap()
) {
    fun resolvedTagName(): String = tagName.ifBlank { "v$versionName" }

    fun changelogText(): String =
        changelog["zh-CN"] ?: changelog.values.firstOrNull { it.isNotBlank() }.orEmpty()
}
