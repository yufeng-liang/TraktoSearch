package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.local.ChangelogStorage
import com.tracktosearch.data.local.ChangelogStorage.CachedUpdateInfo
import com.tracktosearch.data.remote.update.GitHubRelease
import com.tracktosearch.data.remote.update.GitHubUpdateApiService
import com.tracktosearch.data.remote.update.GiteeAsset
import com.tracktosearch.data.remote.update.GiteeRelease
import com.tracktosearch.data.remote.update.GiteeUpdateApiService
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UpdateRepository 单元测试。
 *
 * 覆盖点：
 * - checkForUpdate：缓存命中/过期/force、GitHub→Gitee 降级链路、hasUpdate 重算、版本号比较
 * - fetchDownloadUrl：多 APK 选择策略、无 APK 场景
 * - sanitizeChangelog：乱码（问号占比 >30%）清理
 * - injectDateIntoChangelog：ISO 日期注入、空日期不崩溃
 * - fetchChangelog：内存缓存命中/未命中
 * - fetchAllChangelogs：磁盘缓存命中、GitHub 成功、GitHub 失败降级 Gitee
 *
 * 使用 Robolectric：BuildConfig.VERSION_NAME + android.util.Log。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UpdateRepositoryTest {

    private val gitHubApi = mockk<GitHubUpdateApiService>(relaxed = true)
    private val giteeApi = mockk<GiteeUpdateApiService>(relaxed = true)
    private val changelogStorage = mockk<ChangelogStorage>(relaxed = true)
    private lateinit var repository: UpdateRepository

    @Before
    fun setUp() {
        // 清除前序测试的 stub 和调用记录，确保 coVerify(exactly = 0) 不受干扰
        clearMocks(gitHubApi, giteeApi, changelogStorage)
        // 每个测试创建新的实例，避免 cachedChangelog/cachedFullChangelog/cachedLatestVersion 状态泄漏
        repository = UpdateRepository(gitHubApi, giteeApi, changelogStorage)
    }

    // ============================================================
    // 辅助函数
    // ============================================================

    /** 构造 GitHubRelease mock 对象 */
    private fun buildGitHubRelease(
        tagName: String = "v99.0.0",
        body: String = "更新内容",
        createdAt: String = "2024-01-15T00:00:00Z"
    ): GitHubRelease = GitHubRelease(
        tag_name = tagName,
        body = body,
        created_at = createdAt
    )

    /** 构造 GiteeRelease mock 对象 */
    private fun buildGiteeRelease(
        tagName: String = "v99.0.0",
        body: String = "更新内容",
        createdAt: String = "2024-01-15T00:00:00Z",
        assets: List<GiteeAsset> = emptyList()
    ): GiteeRelease = GiteeRelease(
        tag_name = tagName,
        body = body,
        created_at = createdAt,
        assets = assets
    )

    /** 构造 CachedUpdateInfo */
    private fun buildCachedUpdateInfo(
        latestVersion: String = "99.0.0",
        changelog: String = "缓存的更新日志",
        hasUpdate: Boolean = true,
        downloadUrl: String = "https://example.com/cached.apk"
    ): CachedUpdateInfo = CachedUpdateInfo(
        latestVersion = latestVersion,
        changelog = changelog,
        hasUpdate = hasUpdate,
        downloadUrl = downloadUrl
    )

    // ============================================================
    // checkForUpdate 缓存逻辑测试
    // ============================================================

    @Test
    fun checkForUpdate_非force且2小时内缓存命中_returns缓存结果且不调用网络() = runTest {
        val now = System.currentTimeMillis()
        // 1小时前检查过，在2小时有效期内
        coEvery { changelogStorage.getLastCheckTimestamp() } returns now - 3600_000
        coEvery { changelogStorage.getCachedUpdateInfo() } returns buildCachedUpdateInfo(
            latestVersion = "99.0.0",
            changelog = "缓存日志",
            hasUpdate = true,
            downloadUrl = "https://example.com/cached.apk"
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.latestVersion).isEqualTo("99.0.0")
        assertThat(result.changelog).isEqualTo("缓存日志")
        assertThat(result.downloadUrl).isEqualTo("https://example.com/cached.apk")
        // 验证不调用网络
        coVerify(exactly = 0) { gitHubApi.getLatestRelease(any(), any()) }
        coVerify(exactly = 0) { giteeApi.getLatestRelease(any(), any()) }
    }

    @Test
    fun checkForUpdate_非force但缓存过期_调用GitHubApi() = runTest {
        val now = System.currentTimeMillis()
        // 3小时前检查过，超过2小时有效期
        coEvery { changelogStorage.getLastCheckTimestamp() } returns now - 3 * 3600_000
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease()

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        coVerify(exactly = 1) { gitHubApi.getLatestRelease(any(), any()) }
    }

    @Test
    fun checkForUpdate_force为true_跳过缓存调用GitHubApi() = runTest {
        val now = System.currentTimeMillis()
        // 缓存有效，但 force=true 应跳过缓存
        coEvery { changelogStorage.getLastCheckTimestamp() } returns now
        coEvery { changelogStorage.getCachedUpdateInfo() } returns buildCachedUpdateInfo()
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease()

        val result = repository.checkForUpdate(force = true)

        assertThat(result).isNotNull()
        coVerify(exactly = 1) { gitHubApi.getLatestRelease(any(), any()) }
    }

    // ============================================================
    // checkForUpdate 降级链路测试
    // ============================================================

    @Test
    fun checkForUpdate_GitHub成功且有新版本_returnsHasUpdateTrue并获取下载链接() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease(
            tagName = "v99.0.0",
            body = "新版本更新内容"
        )
        // mock 公开仓库返回 APK 下载链接
        coEvery {
            giteeApi.getLatestRelease("yufeng-liang", "TrackToSearch-release")
        } returns listOf(
            buildGiteeRelease(
                tagName = "v99.0.0",
                assets = listOf(
                    GiteeAsset(
                        name = "TraktoSearch-v99.0.0.apk",
                        browser_download_url = "https://gitee.com/download/v99.0.0.apk"
                    )
                )
            )
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isTrue()
        assertThat(result.latestVersion).isEqualTo("99.0.0")
        assertThat(result.downloadUrl).isEqualTo("https://gitee.com/download/v99.0.0.apk")
        // 验证调用了公开仓库获取下载链接
        coVerify { giteeApi.getLatestRelease("yufeng-liang", "TrackToSearch-release") }
    }

    @Test
    fun checkForUpdate_GitHub成功但版本相同_returnsHasUpdateFalse() = runTest {
        val currentVersion = BuildConfig.VERSION_NAME.removePrefix("v")
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease(
            tagName = "v$currentVersion",  // 当前版本
            body = "当前版本内容"
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isFalse()
        assertThat(result.latestVersion).isEqualTo(currentVersion)
        // 版本相同时不调用 fetchDownloadUrl
        coVerify(exactly = 0) {
            giteeApi.getLatestRelease("yufeng-liang", "TrackToSearch-release")
        }
    }

    @Test
    fun checkForUpdate_GitHub失败_降级Gitee成功() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { gitHubApi.getLatestRelease(any(), any()) } throws RuntimeException("GitHub 网络错误")
        // Gitee 主仓库返回新版本
        coEvery {
            giteeApi.getLatestRelease("yufeng-liang", "TrackToSearch")
        } returns listOf(
            buildGiteeRelease(tagName = "v99.0.0", body = "Gitee 更新内容")
        )
        // fetchDownloadUrl 调用公开仓库
        coEvery {
            giteeApi.getLatestRelease("yufeng-liang", "TrackToSearch-release")
        } returns listOf(
            buildGiteeRelease(
                tagName = "v99.0.0",
                assets = listOf(
                    GiteeAsset(
                        name = "TraktoSearch-v99.0.0.apk",
                        browser_download_url = "https://gitee.com/v99.apk"
                    )
                )
            )
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isTrue()
        assertThat(result.latestVersion).isEqualTo("99.0.0")
        assertThat(result.changelog).contains("Gitee 更新内容")
    }

    @Test
    fun checkForUpdate_GitHub和Gitee都失败_returnsNull() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { gitHubApi.getLatestRelease(any(), any()) } throws RuntimeException("GitHub 错误")
        coEvery { giteeApi.getLatestRelease(any(), any()) } throws RuntimeException("Gitee 错误")

        val result = repository.checkForUpdate()

        assertThat(result).isNull()
    }

    // ============================================================
    // checkForUpdate 缓存 hasUpdate 重算测试
    // ============================================================

    @Test
    fun checkForUpdate_缓存命中时用当前版本重算hasUpdate() = runTest {
        val currentVersion = BuildConfig.VERSION_NAME.removePrefix("v")
        val now = System.currentTimeMillis()
        // 缓存的 latestVersion 等于当前版本，但缓存中 hasUpdate=true
        coEvery { changelogStorage.getLastCheckTimestamp() } returns now - 3600_000
        coEvery { changelogStorage.getCachedUpdateInfo() } returns buildCachedUpdateInfo(
            latestVersion = currentVersion,  // 等于当前版本
            changelog = "缓存日志",
            hasUpdate = true,  // 缓存中 hasUpdate=true
            downloadUrl = "https://example.com/old.apk"
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        // 重算后 hasUpdate 应为 false（版本相同）
        assertThat(result!!.hasUpdate).isFalse()
        assertThat(result.latestVersion).isEqualTo(currentVersion)
        // 验证不调用网络
        coVerify(exactly = 0) { gitHubApi.getLatestRelease(any(), any()) }
    }

    // ============================================================
    // isNewerVersion 矩阵测试（通过 checkForUpdate 间接覆盖）
    // ============================================================

    @Test
    fun checkForUpdate_3段版本号比较_GitHub返回更新版本_returnsHasUpdateTrue() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease(
            tagName = "v99.0.0"  // 3段，明显大于当前版本
        )
        coEvery {
            giteeApi.getLatestRelease("yufeng-liang", "TrackToSearch-release")
        } returns emptyList()

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isTrue()
    }

    @Test
    fun checkForUpdate_4段vs3段版本号比较_有更新() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease(
            tagName = "v99.0.0.1"  // 4段版本号
        )
        coEvery {
            giteeApi.getLatestRelease("yufeng-liang", "TrackToSearch-release")
        } returns emptyList()

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isTrue()
    }

    @Test
    fun checkForUpdate_旧版本_returnsHasUpdateFalse() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease(
            tagName = "v0.0.1"  // 比当前版本小
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isFalse()
        assertThat(result.latestVersion).isEqualTo("0.0.1")
    }

    // ============================================================
    // fetchDownloadUrl 测试（通过 checkForUpdate 间接覆盖）
    // ============================================================

    @Test
    fun fetchDownloadUrl_多APK取最后一个TraktoSearch命名的() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease(tagName = "v99.0.0")
        // 公开仓库返回多个 APK，含多个 TraktoSearch-*.apk
        coEvery {
            giteeApi.getLatestRelease("yufeng-liang", "TrackToSearch-release")
        } returns listOf(
            buildGiteeRelease(
                tagName = "v99.0.0",
                assets = listOf(
                    GiteeAsset(name = "other.apk", browser_download_url = "https://example.com/other.apk"),
                    GiteeAsset(name = "TraktoSearch-v98.0.0.apk", browser_download_url = "https://example.com/v98.apk"),
                    GiteeAsset(name = "TraktoSearch-v99.0.0.apk", browser_download_url = "https://example.com/v99.apk")
                )
            )
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isTrue()
        // 应取最后一个 TraktoSearch-*.apk 命名的
        assertThat(result.downloadUrl).isEqualTo("https://example.com/v99.apk")
    }

    @Test
    fun fetchDownloadUrl_无APK_返回空字符串() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease(tagName = "v99.0.0")
        // 公开仓库返回不含 APK 的 release
        coEvery {
            giteeApi.getLatestRelease("yufeng-liang", "TrackToSearch-release")
        } returns listOf(
            buildGiteeRelease(
                tagName = "v99.0.0",
                assets = listOf(
                    GiteeAsset(name = "readme.txt", browser_download_url = "https://example.com/readme.txt")
                )
            )
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isTrue()
        assertThat(result.downloadUrl).isEmpty()
    }

    // ============================================================
    // sanitizeChangelog 测试（通过 checkForUpdate 间接覆盖）
    // ============================================================

    @Test
    fun checkForUpdate_changelog含大量问号_乱码被清理() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        // body 含 >30% 问号，应被 sanitizeChangelog 清理为空
        // 35个问号 + 5个字母 = 40字符，问号占比 87.5% > 30%
        val garbledBody = "???????????????????????????????????update"
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease(
            tagName = "v99.0.0",
            body = garbledBody,
            createdAt = "2024-01-15T00:00:00Z"
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        // sanitizeChangelog 返回空，injectDateIntoChangelog 补一个 header
        // 返回的 changelog 不应包含原始的乱码内容
        assertThat(result!!.changelog).doesNotContain("?????")
        // 应包含 header
        assertThat(result.changelog).contains("## v99.0.0 更新内容")
    }

    // ============================================================
    // injectDateIntoChangelog 测试（通过 checkForUpdate 间接覆盖）
    // ============================================================

    @Test
    fun checkForUpdate_changelog注入日期_ISO格式CreatedAt() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease(
            tagName = "v99.0.0",
            body = "## v99.0.0 更新内容\n- 新功能",
            createdAt = "2024-01-15T00:00:00Z"
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.changelog).contains("2024-01-15")
        // 日期应注入到标题行末尾
        assertThat(result.changelog).contains("## v99.0.0 更新内容（2024-01-15）")
    }

    @Test
    fun checkForUpdate_changelog注入日期_空CreatedAt不崩溃() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease(
            tagName = "v99.0.0",
            body = "## v99.0.0 更新内容\n- 新功能",
            createdAt = ""  // 空日期
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        // 空日期时不追加日期括号，返回原文
        assertThat(result!!.changelog).isEqualTo("## v99.0.0 更新内容\n- 新功能")
    }

    // ============================================================
    // fetchChangelog 测试
    // ============================================================

    @Test
    fun fetchChangelog_有内存缓存_直接返回() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease(
            tagName = "v99.0.0",
            body = "更新日志内容"
        )

        // 第一次调用：走网络，填充 cachedChangelog
        val firstResult = repository.fetchChangelog()
        assertThat(firstResult).isNotEmpty()

        // 第二次调用：应直接返回内存缓存
        val secondResult = repository.fetchChangelog()
        assertThat(secondResult).isEqualTo(firstResult)

        // 验证 gitHubApi 只被调用一次（第二次走内存缓存）
        coVerify(exactly = 1) { gitHubApi.getLatestRelease(any(), any()) }
    }

    @Test
    fun fetchChangelog_无缓存_调用CheckForUpdate() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease(
            tagName = "v99.0.0",
            body = "更新日志内容"
        )

        val result = repository.fetchChangelog()

        // 返回的 changelog 经过 injectDateIntoChangelog 处理
        assertThat(result).isNotEmpty()
        assertThat(result).contains("更新日志内容")
        coVerify(exactly = 1) { gitHubApi.getLatestRelease(any(), any()) }
    }

    // ============================================================
    // fetchAllChangelogs 测试
    // ============================================================

    @Test
    fun fetchAllChangelogs_磁盘缓存命中_返回磁盘缓存() = runTest {
        val diskChangelog = "## v1.0.0 更新内容\n- 功能1\n\n---\n\n## v0.9.0 更新内容\n- 功能0"
        coEvery { changelogStorage.getChangelog() } returns diskChangelog

        val result = repository.fetchAllChangelogs()

        assertThat(result).isEqualTo(diskChangelog)
        coVerify(exactly = 0) { gitHubApi.getAllReleases(any(), any()) }
        coVerify(exactly = 0) { giteeApi.getAllReleases(any(), any(), any()) }
    }

    @Test
    fun fetchAllChangelogs_无缓存_GitHub成功返回所有版本日志() = runTest {
        coEvery { changelogStorage.getChangelog() } returns null
        coEvery { gitHubApi.getAllReleases(any(), any()) } returns listOf(
            GitHubRelease(tag_name = "v1.0.0", body = "v1.0.0 更新内容", created_at = "2024-01-15T00:00:00Z"),
            GitHubRelease(tag_name = "v0.9.0", body = "v0.9.0 更新内容", created_at = "2024-01-10T00:00:00Z")
        )

        val result = repository.fetchAllChangelogs()

        assertThat(result).contains("v1.0.0 更新内容")
        assertThat(result).contains("v0.9.0 更新内容")
        assertThat(result).contains("2024-01-15")
        assertThat(result).contains("2024-01-10")
        coVerify(exactly = 0) { giteeApi.getAllReleases(any(), any(), any()) }
    }

    @Test
    fun fetchAllChangelogs_GitHub失败降级Gitee() = runTest {
        coEvery { changelogStorage.getChangelog() } returns null
        coEvery { gitHubApi.getAllReleases(any(), any()) } throws RuntimeException("GitHub 网络错误")
        coEvery { giteeApi.getAllReleases(any(), any(), any()) } returns listOf(
            GiteeRelease(tag_name = "v1.0.0", body = "Gitee v1.0.0 更新内容", created_at = "2024-01-15T00:00:00Z")
        )

        val result = repository.fetchAllChangelogs()

        assertThat(result).contains("Gitee v1.0.0 更新内容")
        assertThat(result).contains("2024-01-15")
    }
}
