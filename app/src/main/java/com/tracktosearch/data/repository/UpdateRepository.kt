package com.tracktosearch.data.repository

import android.util.Log
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.local.ChangelogStorage
import com.tracktosearch.data.remote.update.GitHubUpdateApiService
import com.tracktosearch.data.remote.update.GiteeUpdateApiService
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,       // 公开 Gitee 仓库的 APK 下载链接
    val changelog: String,
    val fileSize: Long,
    val hasUpdate: Boolean,
    /** APK 期望 SHA-256（来自发布时上传的 .sha256 sidecar 文件，空表示无校验） */
    val sha256: String = ""
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
        // 启动时自动检查更新的最小间隔（2 小时）。
        // 缩短以覆盖"一天内多次发版"场景：用户当日再次启动即可拉到最新 release。
        // 仍远低于 GitHub 未鉴权限流（60 次/小时），按每次启动计即使重度使用也远达不到。
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
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "GitHub fetch all releases failed", e)
            null
        }
    }

    private suspend fun tryFetchAllFromGitee(): String? {
        return try {
            val releases = giteeApi.getAllReleases(GITEE_OWNER, GITEE_REPO)
            if (releases.isEmpty()) return null
            formatAllChangelogs(releases.map { ReleaseInfo(it.tag_name, it.body, it.created_at) })
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "Gitee fetch all releases failed", e)
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
                val (downloadUrl, sha256) = fetchDownloadAsset(gitHubResult.latestVersion)
                // 下载链接为空时仍返回 hasUpdate=true,避免旧版本因下载链接获取失败而看不到更新提示
                val info = gitHubResult.copy(downloadUrl = downloadUrl, hasUpdate = true, sha256 = sha256)
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
                val (downloadUrl, sha256) = fetchDownloadAsset(giteeResult.latestVersion)
                // 下载链接为空时仍返回 hasUpdate=true(同 GitHub 路径)
                val info = giteeResult.copy(downloadUrl = downloadUrl, hasUpdate = true, sha256 = sha256)
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
     * 注意:传入的 changelog 已由 tryFetchFromGitHub/Gitee 经 injectDateIntoChangelog 注入日期标题,
     * 这里直接作为 entry,不再外包 header,避免重复标题。
     */
    private suspend fun appendToFullChangelog(version: String, changelog: String) {
        if (changelog.isBlank()) return
        val entry = changelog
        val existing = cachedFullChangelog ?: changelogStorage.getChangelog() ?: ""
        // 检查是否已包含该版本(匹配 "## v$version 更新内容" 前缀,兼容带日期括号的情况)
        val versionHeaderPrefix = "## v$version 更新内容"
        if (existing.contains(versionHeaderPrefix)) return
        val updated = if (existing.isBlank()) entry else "$entry\n\n---\n\n$existing"
        cachedFullChangelog = updated
        changelogStorage.saveChangelog(updated)
    }

    /**
     * 从公开仓库获取 APK 下载链接 + SHA-256 校验值。
     *
     * 选择策略：在所有 .apk 附件中优先按命名规则匹配（TraktoSearch-*.apk），
     * 若有多个匹配则在匹配集中取最后一个（后上传的排在后面）。
     *
     * SHA-256 来源：release body 中的 `SHA-256: <hex>` 行（由 release skill 发布时写入）。
     * 缺失时返回空字符串，客户端跳过校验（向后兼容旧 release）。
     */
    private suspend fun fetchDownloadAsset(version: String): Pair<String, String> {
        return try {
            val releases = giteeApi.getLatestRelease(RELEASE_REPO_OWNER, RELEASE_REPO)
            val release = releases.firstOrNull() ?: return "" to ""

            val apkAssets = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            if (apkAssets.isEmpty()) return "" to ""

            // 优先 TraktoSearch-*.apk 命名的附件；候选中取最后一个（后上传的排在后面）
            val namedCandidates = apkAssets.filter {
                it.name.startsWith("TraktoSearch-", ignoreCase = true)
            }
            val apkAsset = (namedCandidates.ifEmpty { apkAssets })
                .lastOrNull()

            val apkUrl = apkAsset?.browser_download_url ?: ""
            val sha256 = parseSha256FromBody(release.body)
            apkUrl to sha256
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch download URL from release repo", e)
            "" to ""
        }
    }

    /** 从 release body 解析 SHA-256（格式：`SHA-256: <64-hex>`，大小写不敏感） */
    private fun parseSha256FromBody(body: String): String {
        if (body.isBlank()) return ""
        val regex = Regex("""(?i)sha-256:\s*([a-f0-9]{64})""")
        return regex.find(body)?.groupValues?.getOrNull(1)?.lowercase()?.trim().orEmpty()
    }

    private suspend fun tryFetchFromGitHub(): UpdateInfo? {
        return try {
            val release = gitHubApi.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
            UpdateInfo(
                latestVersion = release.tag_name.removePrefix("v"),
                downloadUrl = "",
                changelog = injectDateIntoChangelog(release.tag_name, release.body, release.created_at),
                fileSize = 0,
                hasUpdate = false
            )
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
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
                changelog = injectDateIntoChangelog(release.tag_name, release.body, release.created_at),
                fileSize = 0,
                hasUpdate = false
            )
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
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
