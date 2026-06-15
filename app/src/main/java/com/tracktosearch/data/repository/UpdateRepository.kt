package com.tracktosearch.data.repository

import android.util.Log
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.remote.update.GitHubUpdateApiService
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
        // 公开仓库，专门存放 release APK
        private const val RELEASE_REPO_OWNER = "yufeng-liang"
        private const val RELEASE_REPO = "TrackToSearch-release"
    }

    suspend fun checkForUpdate(): UpdateInfo? {
        val currentVersion = BuildConfig.VERSION_NAME

        // 优先尝试 GitHub（版本检测）
        val gitHubResult = tryFetchFromGitHub()
        if (gitHubResult != null) {
            if (isNewerVersion(gitHubResult.latestVersion, currentVersion)) {
                val downloadUrl = fetchDownloadUrl(gitHubResult.latestVersion)
                if (downloadUrl.isEmpty()) return null
                return gitHubResult.copy(downloadUrl = downloadUrl)
            }
            return null
        }

        // GitHub 请求失败，降级到 Gitee
        val giteeResult = tryFetchFromGitee()
        if (giteeResult != null && isNewerVersion(giteeResult.latestVersion, currentVersion)) {
            val downloadUrl = fetchDownloadUrl(giteeResult.latestVersion)
            if (downloadUrl.isEmpty()) return null
            return giteeResult.copy(downloadUrl = downloadUrl)
        }

        return null
    }

    /** 从公开仓库获取 APK 下载链接 */
    private suspend fun fetchDownloadUrl(version: String): String {
        return try {
            val releases = giteeApi.getLatestRelease(RELEASE_REPO_OWNER, RELEASE_REPO)
            val release = releases.firstOrNull() ?: return ""
            val apkAsset = release.assets.find { it.name.endsWith(".apk", ignoreCase = true) }
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
