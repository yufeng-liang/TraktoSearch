package com.tracktosearch.data.repository

import android.util.Log
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.remote.update.GitHubUpdateApiService
import com.tracktosearch.data.remote.update.GiteeUpdateApiService
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val giteeDownloadUrl: String,
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

        // 先尝试 GitHub
        val gitHubResult = tryFetchFromGitHub()
        if (gitHubResult != null && isNewerVersion(gitHubResult.latestVersion, currentVersion)) {
            // 同时尝试获取 Gitee 下载链接作为备用
            val giteeUrl = tryFetchGiteeDownloadUrl()
            return gitHubResult.copy(giteeDownloadUrl = giteeUrl)
        }

        // GitHub 失败或无更新，尝试 Gitee
        val giteeResult = tryFetchFromGitee()
        if (giteeResult != null && isNewerVersion(giteeResult.latestVersion, currentVersion)) {
            return giteeResult
        }

        return null
    }

    private suspend fun tryFetchFromGitHub(): UpdateInfo? {
        return try {
            val release = gitHubApi.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
            val apkAsset = release.assets.find {
                it.name.endsWith(".apk", ignoreCase = true)
            }
            if (apkAsset != null) {
                UpdateInfo(
                    latestVersion = release.tag_name.removePrefix("v"),
                    downloadUrl = apkAsset.browser_download_url,
                    giteeDownloadUrl = "",
                    changelog = release.body,
                    fileSize = apkAsset.size,
                    hasUpdate = false  // 由调用方比较
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "GitHub update check failed", e)
            null
        }
    }

    private suspend fun tryFetchFromGitee(): UpdateInfo? {
        return try {
            val releases = giteeApi.getLatestRelease(GITEE_OWNER, GITEE_REPO)
            val release = releases.firstOrNull() ?: return null
            val apkAsset = release.assets.find {
                it.name.endsWith(".apk", ignoreCase = true)
            }
            if (apkAsset != null) {
                UpdateInfo(
                    latestVersion = release.tag_name.removePrefix("v"),
                    downloadUrl = apkAsset.browser_download_url,
                    giteeDownloadUrl = apkAsset.browser_download_url,
                    changelog = release.body,
                    fileSize = apkAsset.size,
                    hasUpdate = false
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Gitee update check failed", e)
            null
        }
    }

    private suspend fun tryFetchGiteeDownloadUrl(): String {
        return try {
            val releases = giteeApi.getLatestRelease(GITEE_OWNER, GITEE_REPO)
            val release = releases.firstOrNull() ?: return ""
            val apkAsset = release.assets.find {
                it.name.endsWith(".apk", ignoreCase = true)
            }
            apkAsset?.browser_download_url ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 比较版本号，判断 latest 是否比 current 新
     * 格式: "1.9.0" vs "1.8.0"
     */
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
