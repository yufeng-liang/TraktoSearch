package com.tracktosearch.data.repository

import android.util.Log
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.local.ChangelogStorage
import com.tracktosearch.data.remote.update.GitHubUpdateApiService
import com.tracktosearch.data.remote.update.UpdateManifestApiService
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val latestVersion: String,
    /** APK 直链（发版清单给出的绝对地址）；降级到 GitHub 时没有可用直链，为空 */
    val downloadUrl: String,
    val changelog: String,
    val fileSize: Long,
    val hasUpdate: Boolean,
    /** APK 期望 SHA-256，空表示这次没拿到摘要，客户端跳过校验 */
    val sha256: String = ""
)

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubApi: GitHubUpdateApiService,
    private val manifestApi: UpdateManifestApiService,
    private val changelogStorage: ChangelogStorage
) {
    companion object {
        private const val TAG = "UpdateRepository"
        private const val GITHUB_OWNER = "yufeng-liang"
        private const val GITHUB_REPO = "TraktoSearch"
        // 启动时自动检查更新的最小间隔（2 小时）。
        // 缩短以覆盖"一天内多次发版"场景：用户当日再次启动即可拉到最新版本。
        private const val UPDATE_CHECK_INTERVAL_MS = 2 * 60 * 60 * 1000L
    }

    // 缓存上次获取的 changelog，避免重复请求
    @Volatile
    private var cachedChangelog: String? = null

    @Volatile
    private var cachedFullChangelog: String? = null

    @Volatile
    private var cachedLatestVersion: String? = null

    /** 仅获取更新日志（带缓存），用于设置页展示 */
    suspend fun fetchChangelog(): String {
        cachedChangelog?.let { return it }
        val info = checkForUpdate()
        val changelog = info?.changelog ?: ""
        // 仅缓存非空结果，避免网络错误时缓存空字符串导致后续不再重试
        if (changelog.isNotBlank()) {
            cachedChangelog = changelog
        }
        if (info != null) cachedLatestVersion = info.latestVersion
        return changelog
    }

    /** 获取所有版本的更新日志（带缓存），按版本从新到旧排列。优先内存缓存 → 磁盘缓存 → 网络 */
    suspend fun fetchAllChangelogs(): String {
        cachedFullChangelog?.let { return it }

        // 磁盘缓存：app 重启后仍可用，避免每次打开设置页都走网络。
        // 必须确认它来自一次完整拉取——检查更新后的预热追加也会往这个键里写，
        // 那份只有一条，认了它设置页就永远只显示一个版本。
        // 修复前被预热占过的老缓存没有完整性标记，会走重拉分支并覆写，自愈。
        val diskCached = changelogStorage.getChangelog()
        if (!diskCached.isNullOrBlank() && changelogStorage.isFullChangelogComplete()) {
            cachedFullChangelog = diskCached
            return diskCached
        }

        // 网络获取：全量日志也优先读清单（与更新弹窗同源，且在国内可达），
        // 清单不可用时才退回网关代理的 GitHub releases 列表
        val result = tryFetchAllFromManifest() ?: tryFetchAllFromGitHub()
        if (result != null) {
            cachedFullChangelog = result
            changelogStorage.saveCompleteChangelog(result)
            return result
        }
        return ""
    }

    private suspend fun tryFetchAllFromManifest(): String? {
        return try {
            val releases = manifestApi.getHistory().releases
            if (releases.isEmpty()) return null
            formatAllChangelogs(
                releases.map { ReleaseInfo(it.resolvedTagName(), it.changelogText(), it.releaseDate) }
            )
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "Update history manifest fetch failed", e)
            null
        }
    }

    private suspend fun tryFetchAllFromGitHub(): String? {
        return try {
            val releases = gitHubApi.getAllReleases(GITHUB_OWNER, GITHUB_REPO)
            if (releases.isEmpty()) return null
            formatAllChangelogs(releases.map { ReleaseInfo(it.tag_name, it.body, it.created_at) })
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "GitHub fetch all releases failed", e)
            null
        }
    }

    private fun formatAllChangelogs(releases: List<ReleaseInfo>): String {
        return releases
            .filter { it.body.isNotBlank() }
            .joinToString("\n\n---\n\n") { release ->
                injectDateIntoChangelog(release.tagName, release.body, release.createdAt)
            }
    }

    /**
     * 将发布日期注入到 changelog 标题末尾,供更新弹窗与设置页共用同一渲染规则。
     * - body 已按规范以 "## vX.X.X 更新内容" 开头:复用自带标题,仅把日期追加到该标题行末尾(替换已有日期括号)
     * - body 无标题:补一个外层 header "## vX.X.X 更新内容（日期）"
     */
    private fun injectDateIntoChangelog(tagName: String, body: String, createdAt: String): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val inputFormats = arrayOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        )
        val dateStr = createdAt.takeIf { it.isNotBlank() }?.let { rawDate ->
            inputFormats.firstNotNullOfOrNull { fmt ->
                try { fmt.parse(rawDate)?.let { dateFormat.format(it) } } catch (_: Exception) { null }
            }
        } ?: ""
        val cleanBody = sanitizeChangelog(body)
        val firstLine = cleanBody.lineSequence().firstOrNull()
        val firstLineIsSectionHeader = firstLine?.trimStart()?.startsWith("## ") == true
        return if (firstLineIsSectionHeader && firstLine != null) {
            if (dateStr.isNotBlank()) {
                // 移除已有的尾部日期括号(若有),再追加 (dateStr)
                val titleWithoutDate = firstLine.replace(Regex("（[^）]*\\d{4}-\\d{2}-\\d{2}[^）]*）$"), "").trimEnd()
                val restBody = cleanBody.substringAfter('\n')
                "$titleWithoutDate（$dateStr）\n$restBody"
            } else {
                cleanBody
            }
        } else {
            val header = if (dateStr.isNotBlank()) {
                "## $tagName 更新内容（$dateStr）"
            } else {
                "## $tagName 更新内容"
            }
            "$header\n\n$cleanBody"
        }
    }

    private data class ReleaseInfo(
        val tagName: String,
        val body: String,
        val createdAt: String
    )

    /** 获取缓存的最新版本号 */
    fun getCachedLatestVersion(): String? = cachedLatestVersion

    /**
     * 检查更新。
     * @param force true 时强制走网络（设置页手动检查用）；false 时在有效期内复用上次结果，
     *  避免每次启动都请求清单与 GitHub。
     */
    suspend fun checkForUpdate(force: Boolean = false): UpdateInfo? {
        val currentVersion = BuildConfig.VERSION_NAME

        // 非强制检查：有效期内复用缓存结果，避免每次启动都请求网络
        if (!force) {
            val lastTs = changelogStorage.getLastCheckTimestamp()
            val now = System.currentTimeMillis()
            if (lastTs > 0 && now - lastTs < UPDATE_CHECK_INTERVAL_MS) {
                val cached = changelogStorage.getCachedUpdateInfo()
                if (cached != null) {
                    cachedChangelog = cached.changelog
                    cachedLatestVersion = cached.latestVersion
                    // 重要：不能用缓存的 hasUpdate，必须用当前 versionName 重新判断
                    // 场景：用户在 v1.8.0 启动缓存了 hasUpdate=true（latest=1.9.0），
                    // 升级到 v1.9.0 后再启动仍在有效期内，若透传缓存值会继续提示"有更新到 1.9.0"
                    val hasUpdate = isNewerVersion(cached.latestVersion, currentVersion)
                    return UpdateInfo(
                        latestVersion = cached.latestVersion,
                        downloadUrl = cached.downloadUrl,
                        changelog = cached.changelog,
                        fileSize = cached.fileSize,
                        hasUpdate = hasUpdate,
                        sha256 = cached.sha256
                    )
                }
            }
        }

        // 主源是 CI 发布时写入 R2 的清单：版本号、日志、直链、摘要、体积一次拿全。
        // 清单不可用时退回 GitHub Release —— 那边只够告知"有新版本"和展示日志，没有可用直链。
        val primary = tryFetchFromManifest() ?: tryFetchFromGitHub() ?: return null

        cachedChangelog = primary.changelog
        cachedLatestVersion = primary.latestVersion
        val hasUpdate = isNewerVersion(primary.latestVersion, currentVersion)
        if (hasUpdate) {
            // 将该版本 changelog 追加到完整日志缓存，设置页打开时直接用缓存
            appendToFullChangelog(primary.latestVersion, primary.changelog)
        }
        val info = primary.copy(hasUpdate = hasUpdate)
        changelogStorage.saveCachedUpdateInfo(
            latestVersion = info.latestVersion,
            changelog = info.changelog,
            hasUpdate = info.hasUpdate,
            downloadUrl = info.downloadUrl,
            sha256 = info.sha256,
            fileSize = info.fileSize
        )
        return info
    }

    /**
     * 将新版本的 changelog 追加到完整更新日志缓存（磁盘 + 内存）开头。
     * 如果该版本已存在则跳过。这样设置页打开更新日志时直接用缓存，无需再次请求网络。
     * 注意:传入的 changelog 已经过 injectDateIntoChangelog 注入日期标题,
     * 这里直接作为 entry,不再外包 header,避免重复标题。
     */
    private suspend fun appendToFullChangelog(version: String, changelog: String) {
        if (changelog.isBlank()) return
        val entry = changelog
        val existing = cachedFullChangelog ?: changelogStorage.getChangelog() ?: ""
        // 缓存还不存在时不建：只有一条的缓存会被 fetchAllChangelogs 当成完整历史，
        // 设置页从此只显示这一个版本。等用户真打开更新日志走一次完整拉取后再追加。
        if (existing.isBlank()) return
        // 检查是否已包含该版本(匹配 "## v$version 更新内容" 前缀,兼容带日期括号的情况)
        val versionHeaderPrefix = "## v$version 更新内容"
        if (existing.contains(versionHeaderPrefix)) return
        val updated = "$entry\n\n---\n\n$existing"
        cachedFullChangelog = updated
        changelogStorage.saveChangelog(updated)
    }

    private suspend fun tryFetchFromManifest(): UpdateInfo? {
        return try {
            val release = manifestApi.getLatest().latest ?: return null
            if (!release.isValid()) {
                // 版本号或下载路径缺失就没法下载，判为清单不可用，交给 GitHub 兜底
                Log.w(TAG, "Update manifest incomplete: version='${release.versionName}' url='${release.url}'")
                return null
            }
            UpdateInfo(
                latestVersion = release.versionName,
                downloadUrl = absoluteDownloadUrl(release.url),
                changelog = injectDateIntoChangelog(
                    "v${release.versionName}",
                    release.changelogText(),
                    release.releaseDate
                ),
                fileSize = release.size,
                hasUpdate = false,
                sha256 = release.sha256.lowercase()
            )
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "Update manifest fetch failed", e)
            null
        }
    }

    /**
     * 清单存的是相对路径，这样站点换域名时只改 UPDATE_BASE_URL 一处；
     * 这里补成绝对地址，已带协议的（迁移期手写的清单值）原样透传。
     */
    private fun absoluteDownloadUrl(url: String): String {
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            return url
        }
        return BuildConfig.UPDATE_BASE_URL.trimEnd('/') + "/" + url.trimStart('/')
    }

    private suspend fun tryFetchFromGitHub(): UpdateInfo? {
        return try {
            val release = gitHubApi.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
            // 兜底源带就取：CI 会把同一枚 APK 也挂到本仓库 Release 上。
            // 优先 TraktoSearch- 前缀的最后一个（与清单命名一致，后上传的排在后面）。
            // 国内拉 GitHub 资产不稳，所以只作降级；GitHub 不暴露摘要，sha256 留空即跳过校验。
            val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            val apk = apks.lastOrNull { it.name.startsWith("TraktoSearch-", ignoreCase = true) }
                ?: apks.lastOrNull()
            UpdateInfo(
                latestVersion = release.tag_name.removePrefix("v"),
                downloadUrl = apk?.browser_download_url.orEmpty(),
                changelog = injectDateIntoChangelog(release.tag_name, release.body, release.created_at),
                fileSize = apk?.size ?: 0L,
                hasUpdate = false
            )
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "GitHub update check failed", e)
            null
        }
    }

    /**
     * 清理 changelog：检测乱码（大量问号等）并返回友好文本
     */
    private fun sanitizeChangelog(raw: String): String {
        if (raw.isBlank()) return ""
        val questionMarkCount = raw.count { it == '?' }
        val totalLength = raw.length
        if (totalLength > 0 && questionMarkCount.toFloat() / totalLength > 0.3f) {
            return ""
        }
        return raw
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
