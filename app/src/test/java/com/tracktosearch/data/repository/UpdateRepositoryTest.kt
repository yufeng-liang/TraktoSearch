package com.tracktosearch.data.repository

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.local.ChangelogStorage
import com.tracktosearch.data.local.ChangelogStorage.CachedUpdateInfo
import com.tracktosearch.data.remote.update.GitHubAsset
import com.tracktosearch.data.remote.update.GitHubRelease
import com.tracktosearch.data.remote.update.HistoryEntry
import com.tracktosearch.data.remote.update.UpdateHistory
import com.tracktosearch.data.remote.update.GitHubUpdateApiService
import com.tracktosearch.data.remote.update.ManifestRelease
import com.tracktosearch.data.remote.update.UpdateManifest
import com.tracktosearch.data.remote.update.UpdateManifestApiService
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UpdateRepository 单元测试。
 *
 * 主源是 CI 写在 R2 上的发版清单（manifest/latest.json），GitHub Release 只作降级；
 * 覆盖点：
 * - checkForUpdate：清单命中/字段缺失/失效、force、2 小时缓存、hasUpdate 重算、版本比较
 * - 直链拼接：清单里的相对路径必须落到 UPDATE_BASE_URL 同一个站点上
 * - sha256 与体积：清单带来的值必须穿过缓存回来（过去只存 4 个字段，缓存命中即丢校验）
 * - 降级：清单失败回 GitHub 取版本与日志；两者都失败返回 null
 * - changelog：日期注入、乱码清理
 * - fetchChangelog / fetchAllChangelogs 的缓存层级
 *
 * 使用 Robolectric：BuildConfig.VERSION_NAME + android.util.Log。
 *
 * application 必须指定成裸 Application：默认会用 AndroidManifest 里的 TraktSearchApp，
 * 而它注入 Hilt 单例图时会走到 DatabaseModule 的 SQLiteDatabase.loadLibs()，
 * 本机没有桌面版 sqlcipher 原生库（app/src/test/jniLibs 不存在），于是 25 个用例
 * 全部以 UnsatisfiedLinkError 收场。本测试的三个依赖都是 mock，不需要真实 Application 图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UpdateRepositoryTest {

    private val gitHubApi = mockk<GitHubUpdateApiService>(relaxed = true)
    private val manifestApi = mockk<UpdateManifestApiService>(relaxed = true)
    private val changelogStorage = mockk<ChangelogStorage>(relaxed = true)
    private lateinit var repository: UpdateRepository

    /** 64 位十六进制的假摘要，够长且格式合法 */
    private val fakeSha = "a" + "0".repeat(63)

    @Before
    fun setUp() {
        clearMocks(gitHubApi, manifestApi, changelogStorage)
        // 默认让「全量历史清单」为空 —— 空视为不可用，使只关心 checkForUpdate 的用例
        // 走既有的 GitHub 降级语义，不必每个测试都显式打桩。
        coEvery { manifestApi.getHistory() } returns UpdateHistory()
        // 每个测试新建实例，避免 cachedChangelog/cachedFullChangelog/cachedLatestVersion 泄漏
        repository = UpdateRepository(gitHubApi, manifestApi, changelogStorage)
    }

    // ============================================================
    // 辅助构造
    // ============================================================

    private fun buildManifest(
        versionName: String = "99.0.0",
        fileName: String = "TraktoSearch-v99.0.0.apk",
        url: String = "/dl/TraktoSearch-v99.0.0.apk",
        sha256: String = fakeSha,
        size: Long = 38627497L,
        releaseDate: String = "2024-01-15",
        changelog: String = "## v99.0.0 更新内容\n- 清单里的新功能"
    ): UpdateManifest = UpdateManifest(
        schema = 1,
        latest = ManifestRelease(
            versionName = versionName,
            versionCode = 65,
            fileName = fileName,
            url = url,
            sha256 = sha256,
            size = size,
            releaseDate = releaseDate,
            minSdk = 26,
            changelog = mapOf("zh-CN" to changelog)
        )
    )

    private fun buildGitHubRelease(
        tagName: String = "v99.0.0",
        body: String = "更新内容",
        createdAt: String = "2024-01-15T00:00:00Z",
        assets: List<GitHubAsset> = emptyList()
    ): GitHubRelease = GitHubRelease(
        tag_name = tagName,
        body = body,
        created_at = createdAt,
        assets = assets
    )

    private fun buildCachedUpdateInfo(
        latestVersion: String = "99.0.0",
        changelog: String = "缓存的更新日志",
        hasUpdate: Boolean = true,
        downloadUrl: String = "https://tracktosearch.pages.dev/dl/TraktoSearch-v99.0.0.apk",
        sha256: String = fakeSha,
        fileSize: Long = 38627497L
    ): CachedUpdateInfo = CachedUpdateInfo(
        latestVersion = latestVersion,
        changelog = changelog,
        hasUpdate = hasUpdate,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        fileSize = fileSize
    )

    /** 清单与 GitHub 都失败时用于避免 relaxed mock 返回空对象干扰 */
    private fun stubManifestFailure() {
        coEvery { manifestApi.getLatest() } throws RuntimeException("清单不可达")
    }

    // ============================================================
    // 清单主源
    // ============================================================

    @Test
    fun checkForUpdate_清单命中_绝对直链加sha256加体积() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { manifestApi.getLatest() } returns buildManifest()

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isTrue()
        assertThat(result.latestVersion).isEqualTo("99.0.0")
        // 清单里是相对路径，必须落在同一个站点根上
        assertThat(result.downloadUrl)
            .isEqualTo(BuildConfig.UPDATE_BASE_URL.trimEnd('/') + "/dl/TraktoSearch-v99.0.0.apk")
        assertThat(result.sha256).isEqualTo(fakeSha)
        assertThat(result.fileSize).isEqualTo(38627497L)
        // 主源成功就不该再请求 GitHub
        coVerify(exactly = 0) { gitHubApi.getLatestRelease(any(), any()) }
    }

    @Test
    fun checkForUpdate_清单缺sha256_仍可下载且校验值为空() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { manifestApi.getLatest() } returns buildManifest(sha256 = "")

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isTrue()
        assertThat(result.downloadUrl).isNotEmpty()
        assertThat(result.sha256).isEmpty()
    }

    @Test
    fun checkForUpdate_清单版本等于当前_hasUpdateFalse() = runTest {
        val current = BuildConfig.VERSION_NAME.removePrefix("v")
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { manifestApi.getLatest() } returns buildManifest(versionName = current)

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isFalse()
        assertThat(result.latestVersion).isEqualTo(current)
    }

    @Test
    fun checkForUpdate_清单字段不全_视为失败并走降级() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        // url 为空的清单不可用于下载，应判为无效
        coEvery { manifestApi.getLatest() } returns buildManifest(url = "")
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease()

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.latestVersion).isEqualTo("99.0.0")
        coVerify(exactly = 1) { gitHubApi.getLatestRelease(any(), any()) }
    }

    @Test
    fun checkForUpdate_清单latest为空_视为失败并走降级() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { manifestApi.getLatest() } returns UpdateManifest(schema = 1, latest = null)
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease()

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isTrue()
    }

    // ============================================================
    // 降级与失败
    // ============================================================

    @Test
    fun checkForUpdate_清单失败_降级GitHub取版本与日志() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        stubManifestFailure()
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease(
            tagName = "v99.0.0",
            body = "GitHub 兜底更新内容"
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isTrue()
        assertThat(result.latestVersion).isEqualTo("99.0.0")
        assertThat(result.changelog).contains("GitHub 兜底更新内容")
        // 降级路径没有可用的 APK 来源，直链留空由 UI 走「打开下载页」
        assertThat(result.downloadUrl).isEmpty()
    }

    @Test
    fun checkForUpdate_清单与GitHub都失败_returnsNull() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        stubManifestFailure()
        coEvery { gitHubApi.getLatestRelease(any(), any()) } throws RuntimeException("GitHub 错误")

        assertThat(repository.checkForUpdate()).isNull()
    }

    @Test
    fun checkForUpdate_降级GitHub带APK附件_也返回直链与体积() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        stubManifestFailure()
        coEvery { gitHubApi.getLatestRelease(any(), any()) } returns buildGitHubRelease(
            assets = listOf(
                GitHubAsset(name = "notes.bin", browser_download_url = "https://example.com/notes.bin"),
                GitHubAsset(
                    name = "TraktoSearch-v98.0.0.apk",
                    browser_download_url = "https://github.com/dl/v98.apk",
                    size = 100L
                ),
                GitHubAsset(
                    name = "TraktoSearch-v99.0.0.apk",
                    browser_download_url = "https://github.com/dl/v99.apk",
                    size = 38000000L
                )
            )
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        // 同名前缀取最后一个，与清单的命名约定一致
        assertThat(result!!.downloadUrl).isEqualTo("https://github.com/dl/v99.apk")
        assertThat(result.fileSize).isEqualTo(38000000L)
        // GitHub 侧拿不到摘要，留空表示跳过校验，不能伪造一个
        assertThat(result.sha256).isEmpty()
    }

    // ============================================================
    // 缓存层级
    // ============================================================

    @Test
    fun checkForUpdate_两小时内缓存命中_不发网络且带回落盘sha256() = runTest {
        val now = System.currentTimeMillis()
        coEvery { changelogStorage.getLastCheckTimestamp() } returns now - 3600_000
        coEvery { changelogStorage.getCachedUpdateInfo() } returns buildCachedUpdateInfo(
            sha256 = fakeSha,
            fileSize = 38627497L
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.latestVersion).isEqualTo("99.0.0")
        // 关键回归：sha256 与体积必须穿过缓存回来，否则下载完的校验会被静默跳过
        assertThat(result.sha256).isEqualTo(fakeSha)
        assertThat(result.fileSize).isEqualTo(38627497L)
        coVerify(exactly = 0) { manifestApi.getLatest() }
        coVerify(exactly = 0) { gitHubApi.getLatestRelease(any(), any()) }
    }

    @Test
    fun checkForUpdate_缓存命中时用当前版本重算hasUpdate() = runTest {
        val current = BuildConfig.VERSION_NAME.removePrefix("v")
        coEvery { changelogStorage.getLastCheckTimestamp() } returns System.currentTimeMillis() - 3600_000
        coEvery { changelogStorage.getCachedUpdateInfo() } returns buildCachedUpdateInfo(
            latestVersion = current,
            hasUpdate = true
        )

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isFalse()
        assertThat(result.latestVersion).isEqualTo(current)
        coVerify(exactly = 0) { manifestApi.getLatest() }
    }

    @Test
    fun checkForUpdate_缓存过期_重新请求清单() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns System.currentTimeMillis() - 3 * 3600_000
        coEvery { manifestApi.getLatest() } returns buildManifest()

        assertThat(repository.checkForUpdate()).isNotNull()
        coVerify(exactly = 1) { manifestApi.getLatest() }
    }

    @Test
    fun checkForUpdate_force跳过缓存() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns System.currentTimeMillis()
        coEvery { changelogStorage.getCachedUpdateInfo() } returns buildCachedUpdateInfo()
        coEvery { manifestApi.getLatest() } returns buildManifest()

        assertThat(repository.checkForUpdate(force = true)).isNotNull()
        coVerify(exactly = 1) { manifestApi.getLatest() }
    }

    @Test
    fun checkForUpdate_网络成功_落盘时带上sha256与体积() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { manifestApi.getLatest() } returns buildManifest()

        repository.checkForUpdate()

        coVerify {
            changelogStorage.saveCachedUpdateInfo(
                latestVersion = "99.0.0",
                changelog = any(),
                hasUpdate = true,
                downloadUrl = any(),
                sha256 = fakeSha,
                fileSize = 38627497L
            )
        }
    }

    // ============================================================
    // 版本号比较
    // ============================================================

    @Test
    fun checkForUpdate_四段版本号比三段新_判定有更新() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { manifestApi.getLatest() } returns buildManifest(versionName = "99.0.0.1")

        assertThat(repository.checkForUpdate()!!.hasUpdate).isTrue()
    }

    @Test
    fun checkForUpdate_清单版本更旧_判定无更新() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { manifestApi.getLatest() } returns buildManifest(versionName = "0.0.1")

        val result = repository.checkForUpdate()

        assertThat(result).isNotNull()
        assertThat(result!!.hasUpdate).isFalse()
        assertThat(result.latestVersion).isEqualTo("0.0.1")
    }

    // ============================================================
    // changelog 处理
    // ============================================================

    @Test
    fun checkForUpdate_清单日期注入标题行末尾() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { manifestApi.getLatest() } returns buildManifest(releaseDate = "2024-01-15")

        assertThat(repository.checkForUpdate()!!.changelog)
            .contains("## v99.0.0 更新内容（2024-01-15）")
    }

    @Test
    fun checkForUpdate_清单无日期_原标题保持不变() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { manifestApi.getLatest() } returns buildManifest(releaseDate = "")

        assertThat(repository.checkForUpdate()!!.changelog)
            .isEqualTo("## v99.0.0 更新内容\n- 清单里的新功能")
    }

    @Test
    fun checkForUpdate_清单无标题_补外层header() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { manifestApi.getLatest() } returns buildManifest(changelog = "- 只有一条改动")

        val changelog = repository.checkForUpdate()!!.changelog

        assertThat(changelog).startsWith("## v99.0.0 更新内容")
        assertThat(changelog).contains("- 只有一条改动")
    }

    @Test
    fun checkForUpdate_清单缺中文日志_回落其他语言() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery {
            manifestApi.getLatest()
        } returns buildManifest().let { m ->
            m.copy(latest = m.latest!!.copy(changelog = mapOf("en" to "- english only")))
        }

        assertThat(repository.checkForUpdate()!!.changelog).contains("english only")
    }

    @Test
    fun checkForUpdate_乱码日志被清理() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        // 35 个问号 + 5 个字母，问号占比 87.5% > 30%
        coEvery {
            manifestApi.getLatest()
        } returns buildManifest(changelog = "???????????????????????????????????update")

        val changelog = repository.checkForUpdate()!!.changelog

        assertThat(changelog).doesNotContain("?????")
        assertThat(changelog).contains("## v99.0.0 更新内容")
    }

    // ============================================================
    // fetchChangelog / fetchAllChangelogs
    // ============================================================

    @Test
    fun fetchChangelog_内存缓存命中_不再请求网络() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { manifestApi.getLatest() } returns buildManifest()

        val first = repository.fetchChangelog()
        val second = repository.fetchChangelog()

        assertThat(first).isNotEmpty()
        assertThat(second).isEqualTo(first)
        coVerify(exactly = 1) { manifestApi.getLatest() }
    }

    @Test
    fun fetchAllChangelogs_磁盘缓存完整_不发网络() = runTest {
        val disk = "## v1.0.0 更新内容\n- 功能1"
        coEvery { changelogStorage.getChangelog() } returns disk
        coEvery { changelogStorage.isFullChangelogComplete() } returns true

        assertThat(repository.fetchAllChangelogs()).isEqualTo(disk)
        coVerify(exactly = 0) { gitHubApi.getAllReleases(any(), any()) }
        coVerify(exactly = 0) { manifestApi.getHistory() }
    }

    @Test
    fun fetchAllChangelogs_历史清单命中_按序渲染且不请求GitHub() = runTest {
        coEvery { changelogStorage.getChangelog() } returns null
        coEvery { manifestApi.getHistory() } returns UpdateHistory(
            schema = 1,
            releases = listOf(
                HistoryEntry(
                    versionName = "3.6.0",
                    tagName = "v3.6.0",
                    releaseDate = "2026-07-28",
                    changelog = mapOf("zh-CN" to "## v3.6.0 更新内容\n- 反馈系统")
                ),
                HistoryEntry(
                    versionName = "3.5.0",
                    tagName = "",
                    releaseDate = "2026-07-26",
                    changelog = mapOf("zh-CN" to "- 只有正文没有标题")
                )
            )
        )

        val result = repository.fetchAllChangelogs()

        assertThat(result).contains("## v3.6.0 更新内容（2026-07-28）")
        // 无标题的条目补 header；tagName 缺失时由 versionName 推出
        assertThat(result).contains("## v3.5.0 更新内容（2026-07-26）")
        // 顺序沿用清单（新→旧），客户端不重排
        assertThat(result.indexOf("v3.6.0")).isLessThan(result.indexOf("v3.5.0"))
        coVerify(exactly = 0) { gitHubApi.getAllReleases(any(), any()) }
    }

    @Test
    fun fetchAllChangelogs_无缓存_取GitHub全部版本并注入日期() = runTest {
        coEvery { changelogStorage.getChangelog() } returns null
        coEvery { gitHubApi.getAllReleases(any(), any()) } returns listOf(
            GitHubRelease("v1.0.0", "v1.0.0 更新内容", "2024-01-15T00:00:00Z"),
            GitHubRelease("v0.9.0", "v0.9.0 更新内容", "2024-01-10T00:00:00Z")
        )

        val result = repository.fetchAllChangelogs()

        assertThat(result).contains("v1.0.0 更新内容")
        assertThat(result).contains("v0.9.0 更新内容")
        assertThat(result).contains("2024-01-15")
        assertThat(result).contains("2024-01-10")
    }

    @Test
    fun fetchAllChangelogs_历史清单与GitHub都失败_返回空() = runTest {
        coEvery { changelogStorage.getChangelog() } returns null
        coEvery { manifestApi.getHistory() } throws RuntimeException("清单不可达")
        coEvery { gitHubApi.getAllReleases(any(), any()) } throws RuntimeException("GitHub 错误")

        assertThat(repository.fetchAllChangelogs()).isEmpty()
    }

    // ============================================================
    // 全量日志缓存的完整性（预热追加不能冒充完整缓存）
    // ============================================================

    @Test
    fun fetchAllChangelogs_磁盘缓存被单条预热占位_重拉完整清单并标记完成() = runTest {
        // 旧版本留下的污染状态：追加预热写过一条，正文非空但并不是完整历史
        coEvery { changelogStorage.getChangelog() } returns "## v99.0.0 更新内容\n- 只有这一条"
        coEvery { changelogStorage.isFullChangelogComplete() } returns false
        coEvery { manifestApi.getHistory() } returns UpdateHistory(
            schema = 1,
            releases = listOf(
                HistoryEntry("3.6.0", "v3.6.0", "2026-07-28", mapOf("zh-CN" to "- 反馈系统")),
                HistoryEntry("3.5.0", "v3.5.0", "2026-07-26", mapOf("zh-CN" to "- Trakt 搜索"))
            )
        )

        val result = repository.fetchAllChangelogs()

        assertThat(result).contains("## v3.6.0 更新内容")
        assertThat(result).contains("## v3.5.0 更新内容")
        assertThat(result).doesNotContain("只有这一条")
        coVerify(exactly = 1) { manifestApi.getHistory() }
        coVerify(exactly = 1) { changelogStorage.saveCompleteChangelog(any()) }
        coVerify(exactly = 0) { changelogStorage.saveChangelog(any()) }
    }

    @Test
    fun checkForUpdate_有更新但全量日志还没缓存_不凭空建缓存() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { changelogStorage.getChangelog() } returns null
        coEvery { manifestApi.getLatest() } returns buildManifest()

        val result = repository.checkForUpdate()

        assertThat(result!!.hasUpdate).isTrue()
        // 预热追加只该往已有完整缓存里插条目；从空缓存建一份会让设置页误以为「已完整」，
        // 从此永远只显示这一个版本
        coVerify(exactly = 0) { changelogStorage.saveChangelog(any()) }
        coVerify(exactly = 0) { changelogStorage.saveCompleteChangelog(any()) }
    }

    @Test
    fun checkForUpdate_已有完整缓存_预热把新版本追加到开头() = runTest {
        coEvery { changelogStorage.getLastCheckTimestamp() } returns 0L
        coEvery { changelogStorage.getChangelog() } returns "## v3.5.0 更新内容\n- 旧条目"
        coEvery { manifestApi.getLatest() } returns buildManifest()

        assertThat(repository.checkForUpdate()!!.hasUpdate).isTrue()

        val saved = slot<String>()
        coVerify(exactly = 1) { changelogStorage.saveChangelog(capture(saved)) }
        assertThat(saved.captured).contains("## v99.0.0 更新内容")
        assertThat(saved.captured).contains("## v3.5.0 更新内容")
        assertThat(saved.captured.indexOf("v99.0.0")).isLessThan(saved.captured.indexOf("v3.5.0"))
        // 追加不升级完整性标记，也不该走完整拉取的写入口
        coVerify(exactly = 0) { changelogStorage.saveCompleteChangelog(any()) }
    }
}
