package com.tracktosearch.data.repository

import android.util.Log
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.remote.update.GitHubAsset
import com.tracktosearch.data.remote.update.GitHubUpdateApiService
import com.tracktosearch.data.remote.update.GiteeAsset
import com.tracktosearch.data.remote.update.GiteeUpdateApiService
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
    private val giteeApi: GiteeUpdateApiService
) {
    companion object {
        private const val TAG = "UpdateRepository"
        private const val GITHUB_OWNER = "yufeng-liang"
        private const val GITHUB_REPO = "TrackToSearch"
        private const val GITEE_OWNER = "yufeng-liang"
        private const val GITEE_REPO = "TrackToSearch"
    }

    suspend fun checkForUpdate(): UpdateInfo? {
        val currentVersion = BuildConfig.VERSION_NAME

        // 优先尝试 GitHub（版本检测 + APK 下载链接）
        val gitHubResult = tryFetchFromGitHub()
        if (gitHubResult != null) {
            if (isNewerVersion(gitHubResult.latestVersion, currentVersion)) {
                return gitHubResult.takeIf { it.downloadUrl.isNotEmpty() }
            }
            return null
        }

        // GitHub 请求失败，降级到 Gitee
        val giteeResult = tryFetchFromGitee()
        if (giteeResult != null && isNewerVersion(giteeResult.latestVersion, currentVersion)) {
            return giteeResult.takeIf { it.downloadUrl.isNotEmpty() }
        }

        return null
    }

    /**
     * 从 release 的 assets 中选择 APK 下载链接：
     * 优先匹配 TraktToSearch-*.apk，候选中取最小体积，避免选到损坏文件。
     */
    private fun pickGitHubApkUrl(assets: List<GitHubAsset>): Pair<String, Long> {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return "" to 0L
        val namedCandidates = apkAssets.filter {
            it.name.startsWith("TraktToSearch-", ignoreCase = true)
        }
        val asset = (namedCandidates.ifEmpty { apkAssets }).minByOrNull { it.size }
        return (asset?.browser_download_url ?: "") to (asset?.size ?: 0L)
    }

    private fun pickGiteeApkUrl(assets: List<GiteeAsset>): Pair<String, Long> {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return "" to 0L
        val namedCandidates = apkAssets.filter {
            it.name.startsWith("TraktToSearch-", ignoreCase = true)
        }
        val asset = (namedCandidates.ifEmpty { apkAssets }).minByOrNull { it.size }
        return (asset?.browser_download_url ?: "") to (asset?.size ?: 0L)
    }

    private suspend fun tryFetchFromGitHub(): UpdateInfo? {
        return try {
            val release = gitHubApi.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
            val (downloadUrl, fileSize) = pickGitHubApkUrl(release.assets)
            UpdateInfo(
                latestVersion = release.tag_name.removePrefix("v"),
                downloadUrl = downloadUrl,
                changelog = sanitizeChangelog(release.body),
                fileSize = fileSize,
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
            val (downloadUrl, fileSize) = pickGiteeApkUrl(release.assets)
            UpdateInfo(
                latestVersion = release.tag_name.removePrefix("v"),
                downloadUrl = downloadUrl,
                changelog = sanitizeChangelog(release.body),
                fileSize = fileSize,
                hasUpdate = false
            )
        } catch (e: Exception) {
            Log.w(TAG, "Gitee update check failed", e)
            null
        }
    }

    /**
     * 清理 changelog：检测乱码（大量问号等）并返回友好文本。
     * 同时将 `•` 开头的项目符号替换为 `-`，统一列表标记便于解析。
     */
    private fun sanitizeChangelog(raw: String): String {
        if (raw.isBlank()) return ""
        val questionMarkCount = raw.count { it == '?' }
        val totalLength = raw.length
        if (totalLength > 0 && questionMarkCount.toFloat() / totalLength > 0.3f) {
            return ""
        }
        return raw.replace(Regex("^(\\s*)•\\s*", RegexOption.MULTILINE), "$1- ")
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
