package com.tracktosearch.data.repository

import android.util.Log
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.local.ChangelogStorage
import com.tracktosearch.data.remote.update.GitHubUpdateApiService
import com.tracktosearch.data.remote.update.GiteeUpdateApiService
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,       // 公开 Gitee 仓库的 APK 下载链接
    val changelog: String,
    val fileSize: Long,
    val hasUpdate: Boolean
)

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubApi: GitHubUpdateApiService,
    private val giteeApi: GiteeUpdateApiService,
    private val changelogStorage: ChangelogStorage
) {
    companion object {
        private const val TAG = "UpdateRepository"
        private const val GITHUB_OWNER = "yufeng-liang"
        private const val GITHUB_REPO = "TrackToSearch"
        private const val GITEE_OWNER = "yufeng-liang"
        private const val GITEE_REPO = "TrackToSearch"
        // 公开仓库，专门存放 release APK
        private const val RELEASE_REPO_OWNER = "yufeng-liang"
        private const val RELEASE_REPO = "TrackToSearch-release"
        // 启动时自动检查更新的最小间隔（24 小时），避免每次启动都请求 GitHub API
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
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

        // 磁盘缓存：app 重启后仍可用，避免每次打开设置页都走网络
        val diskCached = changelogStorage.getChangelog()
        if (!diskCached.isNullOrBlank()) {
            cachedFullChangelog = diskCached
            return diskCached
        }

        // 网络获取
        val result = tryFetchAllFromGitHub() ?: tryFetchAllFromGitee()
        if (result != null) {
            cachedFullChangelog = result
            changelogStorage.saveChangelog(result)
            return result
        }
        return ""
    }

    private suspend fun tryFetchAllFromGitHub(): String? {
        return try {
            val releases = gitHubApi.getAllReleases(GITHUB_OWNER, GITHUB_REPO)
            if (releases.isEmpty()) return null
            formatAllChangelogs(releases.map { ReleaseInfo(it.tag_name, it.body, it.created_at) })
        } catch (e: Exception) {
            Log.w(TAG, "GitHub fetch all releases failed", e)
            null
        }
    }

    private suspend fun tryFetchAllFromGitee(): String? {
        return try {
            val releases = giteeApi.getAllReleases(GITEE_OWNER, GITEE_REPO)
            if (releases.isEmpty()) return null
            formatAllChangelogs(releases.map { ReleaseInfo(it.tag_name, it.body, it.created_at) })
        } catch (e: Exception) {
            Log.w(TAG, "Gitee fetch all releases failed", e)
            null
        }
    }

    private fun formatAllChangelogs(releases: List<ReleaseInfo>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val inputFormats = arrayOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        )

        return releases
            .filter { it.body.isNotBlank() }
            .joinToString("\n\n---\n\n") { release ->
                val dateStr = release.createdAt.takeIf { it.isNotBlank() }?.let { dateStr ->
                    inputFormats.firstNotNullOfOrNull { fmt ->
                        try { fmt.parse(dateStr)?.let { dateFormat.format(it) } } catch (_: Exception) { null }
                    }
                } ?: ""
                val cleanBody = sanitizeChangelog(release.body)
                // body 已按 project_rules.md 规范以 "## vX.X.X 更新内容" 开头时:
                // 复用 body 自带标题(避免外层 wrapper 重复),仅把日期注入到该标题行末尾(替换或追加)
                // body 无标题时:补一个外层 header(legacy 兼容)
                val firstLine = cleanBody.lineSequence().firstOrNull()
                val firstLineIsSectionHeader = firstLine?.trimStart()?.startsWith("## ") == true
                if (firstLineIsSectionHeader && firstLine != null) {
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
                        "## ${release.tagName} 更新内容（$dateStr）"
                    } else {
                        "## ${release.tagName} 更新内容"
                    }
                    "$header\n\n$cleanBody"
                }
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
     * @param force true 时强制走网络（设置页手动检查用）；false 时 24 小时内复用上次结果，避免每次启动都请求 GitHub API
     */
    suspend fun checkForUpdate(force: Boolean = false): UpdateInfo? {
        val currentVersion = BuildConfig.VERSION_NAME

        // 非强制检查：24 小时内复用缓存结果，避免每次启动都请求 GitHub
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
                    // 升级到 v1.9.0 后再启动仍在 24h 内，若透传缓存值会继续提示"有更新到 1.9.0"
                    val hasUpdate = isNewerVersion(cached.latestVersion, currentVersion)
                    return UpdateInfo(
                        latestVersion = cached.latestVersion,
                        downloadUrl = cached.downloadUrl,
                        changelog = cached.changelog,
                        fileSize = 0,
                        hasUpdate = hasUpdate
                    )
                }
            }
        }

        // 优先尝试 GitHub（版本检测）
        val gitHubResult = tryFetchFromGitHub()
        if (gitHubResult != null) {
            cachedChangelog = gitHubResult.changelog
            cachedLatestVersion = gitHubResult.latestVersion
            if (isNewerVersion(gitHubResult.latestVersion, currentVersion)) {
                // 有新版本：将该版本 changelog 追加到完整日志缓存，设置页打开时直接用缓存
                appendToFullChangelog(gitHubResult.latestVersion, gitHubResult.changelog)
                val downloadUrl = fetchDownloadUrl(gitHubResult.latestVersion)
                if (downloadUrl.isEmpty()) return null
                val info = gitHubResult.copy(downloadUrl = downloadUrl, hasUpdate = true)
                changelogStorage.saveCachedUpdateInfo(
                    info.latestVersion, info.changelog, info.hasUpdate, info.downloadUrl
                )
                return info
            }
            // 版本相同，返回 hasUpdate=false 的信息以便 UI 显示"已是最新"
            val info = gitHubResult.copy(hasUpdate = false)
            changelogStorage.saveCachedUpdateInfo(
                info.latestVersion, info.changelog, info.hasUpdate, info.downloadUrl
            )
            return info
        }

        // GitHub 请求失败，降级到 Gitee
        val giteeResult = tryFetchFromGitee()
        if (giteeResult != null) {
            cachedChangelog = giteeResult.changelog
            cachedLatestVersion = giteeResult.latestVersion
            if (isNewerVersion(giteeResult.latestVersion, currentVersion)) {
                appendToFullChangelog(giteeResult.latestVersion, giteeResult.changelog)
                val downloadUrl = fetchDownloadUrl(giteeResult.latestVersion)
                if (downloadUrl.isEmpty()) return null
                val info = giteeResult.copy(downloadUrl = downloadUrl, hasUpdate = true)
                changelogStorage.saveCachedUpdateInfo(
                    info.latestVersion, info.changelog, info.hasUpdate, info.downloadUrl
                )
                return info
            }
            val info = giteeResult.copy(hasUpdate = false)
            changelogStorage.saveCachedUpdateInfo(
                info.latestVersion, info.changelog, info.hasUpdate, info.downloadUrl
            )
            return info
        }

        return null
    }

    /**
     * 将新版本的 changelog 追加到完整更新日志缓存（磁盘 + 内存）开头。
     * 如果该版本已存在则跳过。这样设置页打开更新日志时直接用缓存，无需再次请求网络。
     */
    private suspend fun appendToFullChangelog(version: String, changelog: String) {
        if (changelog.isBlank()) return
        val entry = "## v$version 更新内容\n\n$changelog"
        val existing = cachedFullChangelog ?: changelogStorage.getChangelog() ?: ""
        // 检查是否已包含该版本
        if (existing.contains("## v$version 更新内容")) return
        val updated = if (existing.isBlank()) entry else "$entry\n\n---\n\n$existing"
        cachedFullChangelog = updated
        changelogStorage.saveChangelog(updated)
    }

    /**
     * 从公开仓库获取 APK 下载链接
     * 选择策略：在所有 .apk 附件中优先按命名规则匹配（TraktToSearch-*.apk），
     * 若有多个匹配则在匹配集中取最小体积的，避免选到因 UTF-8 重新编码而膨胀的损坏文件
     */
    private suspend fun fetchDownloadUrl(version: String): String {
        return try {
            val releases = giteeApi.getLatestRelease(RELEASE_REPO_OWNER, RELEASE_REPO)
            val release = releases.firstOrNull() ?: return ""

            val apkAssets = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            if (apkAssets.isEmpty()) return ""

            // 优先 TraktToSearch-*.apk 命名的附件；候选中取最后一个（后上传的排在后面）
            val namedCandidates = apkAssets.filter {
                it.name.startsWith("TraktToSearch-", ignoreCase = true)
            }
            val apkAsset = (namedCandidates.ifEmpty { apkAssets })
                .lastOrNull()

            apkAsset?.browser_download_url ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch download URL from release repo", e)
            ""
        }
    }

    private suspend fun tryFetchFromGitHub(): UpdateInfo? {
        return try {
            val release = gitHubApi.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
            UpdateInfo(
                latestVersion = release.tag_name.removePrefix("v"),
                downloadUrl = "",
                changelog = sanitizeChangelog(release.body),
                fileSize = 0,
                hasUpdate = false
            )
        } catch (e: Exception) {
            Log.w(TAG, "GitHub update check failed", e)
            null
        }
    }

    private suspend fun tryFetchFromGitee(): UpdateInfo? {
        return try {
            val releases = giteeApi.getLatestRelease(GITEE_OWNER, GITEE_REPO)
            val release = releases.firstOrNull() ?: return null
            UpdateInfo(
                latestVersion = release.tag_name.removePrefix("v"),
                downloadUrl = "",
                changelog = sanitizeChangelog(release.body),
                fileSize = 0,
                hasUpdate = false
            )
        } catch (e: Exception) {
            Log.w(TAG, "Gitee update check failed", e)
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
