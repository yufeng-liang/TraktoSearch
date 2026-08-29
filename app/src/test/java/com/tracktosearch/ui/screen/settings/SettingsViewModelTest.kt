package com.tracktosearch.ui.screen.settings

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.CrashLogStorage
import com.tracktosearch.data.local.DefaultTabStorage
import com.tracktosearch.data.local.DetailSectionStorage
import com.tracktosearch.data.local.DiscoverSectionStorage
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.LastConsistencyCheckStorage
import com.tracktosearch.data.local.NotificationStorage
import com.tracktosearch.data.local.PanHubConfigStorage
import com.tracktosearch.data.local.SearchSourceStorage
import com.tracktosearch.data.local.SharedTransitionStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.local.db.OfflineCacheManager
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.notification.NotificationScheduler
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.dto.DoubanHotData
import com.tracktosearch.data.remote.panhub.PanHubConfig
import com.tracktosearch.data.repository.CloudPersonalSyncManager
import com.tracktosearch.data.repository.ConsistencyCheckResult
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.DoubanBatchRemovalManager
import com.tracktosearch.data.repository.DoubanSyncProgress
import com.tracktosearch.data.repository.DoubanTraktStatusConsistencyChecker
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.UpdateRepository
import com.tracktosearch.data.util.CrashLogUploader
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.test.MainDispatcherRule
import com.tracktosearch.ui.theme.GlassVariant
import com.tracktosearch.ui.theme.VisualEffectMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * SettingsViewModel 单元测试。
 *
 * 聚焦于简单的委托方法和同步方法验证：
 * - setThemeMode / setDefaultTab / setPansouEnabled 委托到对应 Storage
 * - setNotificationEnabled 启用/禁用时调度/取消周期检查
 * - dismissUpdateDialog / clearMessage 状态重置
 * - clearDoubanCredentials / isCheckRunning / cancelConsistencyCheck 同步委托
 * - setCrashLogEnabled 开启时按待传日志情况触发/跳过上传
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // 26 个 mock 依赖
    private lateinit var themeStorage: ThemeStorage
    private lateinit var searchSourceStorage: SearchSourceStorage
    private lateinit var notificationStorage: NotificationStorage
    private lateinit var notificationScheduler: NotificationScheduler
    private lateinit var crashLogStorage: CrashLogStorage
    private lateinit var crashLogUploader: CrashLogUploader
    private lateinit var languageStorage: LanguageStorage
    private lateinit var traktRepository: TraktRepository
    private lateinit var tmdbRepository: TmdbRepository
    private lateinit var updateRepository: UpdateRepository
    private lateinit var offlineCacheManager: OfflineCacheManager
    private lateinit var doubanHotCache: PersistentTtlCache<DoubanHotData>
    private lateinit var doubanDetailCache: PersistentTtlCache<DoubanDetailCacheEntry>
    private lateinit var discoverSectionStorage: DiscoverSectionStorage
    private lateinit var detailSectionStorage: DetailSectionStorage
    private lateinit var panHubConfigStorage: PanHubConfigStorage
    private lateinit var defaultTabStorage: DefaultTabStorage
    private lateinit var doubanAuthStorage: DoubanAuthStorage
    private lateinit var doubanRepository: DoubanRepository
    private lateinit var cloudPersonalSyncManager: CloudPersonalSyncManager
    private lateinit var doubanSyncMetaStorage: DoubanSyncMetaStorage
    private lateinit var cloudFailureSyncMetaStorage: com.tracktosearch.data.local.CloudFailureSyncMetaStorage
    private lateinit var lastConsistencyCheckStorage: LastConsistencyCheckStorage
    private lateinit var statusConsistencyChecker: DoubanTraktStatusConsistencyChecker
    private lateinit var doubanSyncManager: DoubanSyncManager
    private lateinit var doubanBatchRemovalManager: DoubanBatchRemovalManager
    private lateinit var sharedTransitionStorage: SharedTransitionStorage
    private lateinit var doubanSyncedItemDao: DoubanSyncedItemDao
    private lateinit var sessionModeManager: SessionModeManager
    private lateinit var aiTasteStorage: com.tracktosearch.data.local.AiTasteStorage
    private lateinit var context: Context

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        // 创建所有 mock（relaxed = true，suspend 方法默认返回 Unit）
        themeStorage = mockk(relaxed = true)
        searchSourceStorage = mockk(relaxed = true)
        notificationStorage = mockk(relaxed = true)
        notificationScheduler = mockk(relaxed = true)
        crashLogStorage = mockk(relaxed = true)
        crashLogUploader = mockk(relaxed = true)
        languageStorage = mockk(relaxed = true)
        traktRepository = mockk(relaxed = true)
        tmdbRepository = mockk(relaxed = true)
        updateRepository = mockk(relaxed = true)
        offlineCacheManager = mockk(relaxed = true)
        doubanHotCache = mockk(relaxed = true)
        doubanDetailCache = mockk(relaxed = true)
        discoverSectionStorage = mockk(relaxed = true)
        detailSectionStorage = mockk(relaxed = true)
        panHubConfigStorage = mockk(relaxed = true)
        defaultTabStorage = mockk(relaxed = true)
        doubanAuthStorage = mockk(relaxed = true)
        doubanRepository = mockk(relaxed = true)
        cloudPersonalSyncManager = mockk(relaxed = true)
        doubanSyncMetaStorage = mockk(relaxed = true)
        cloudFailureSyncMetaStorage = mockk(relaxed = true)
        lastConsistencyCheckStorage = mockk(relaxed = true)
        statusConsistencyChecker = mockk(relaxed = true)
        doubanSyncManager = mockk(relaxed = true)
        doubanBatchRemovalManager = mockk(relaxed = true)
        sharedTransitionStorage = mockk(relaxed = true)
        doubanSyncedItemDao = mockk(relaxed = true)
        sessionModeManager = mockk(relaxed = true)
        aiTasteStorage = mockk(relaxed = true)
        context = RuntimeEnvironment.getApplication()

        // 构造时直接赋值的 StateFlow 属性必须在 ViewModel 构造前 stub
        every { themeStorage.themeMode } returns MutableStateFlow("system")
        every { themeStorage.accentColor } returns MutableStateFlow(null)
        every { themeStorage.glassVariant } returns MutableStateFlow(GlassVariant.CLEAR)
        every { defaultTabStorage.defaultTab } returns MutableStateFlow(0)
        every { languageStorage.language } returns MutableStateFlow("zh-CN")
        every { searchSourceStorage.pansouEnabled } returns MutableStateFlow(false)
        every { searchSourceStorage.panhubEnabled } returns MutableStateFlow(false)
        every { searchSourceStorage.zresoEnabled } returns MutableStateFlow(false)
        every { notificationStorage.enabled } returns MutableStateFlow(false)
        every { notificationStorage.releaseReminderEnabled } returns MutableStateFlow(false)
        every { notificationStorage.newSeasonReminderEnabled } returns MutableStateFlow(false)
        every { panHubConfigStorage.config } returns MutableStateFlow(PanHubConfig())
        every { doubanAuthStorage.isLoggedIn } returns MutableStateFlow(false)
        every { doubanAuthStorage.doubanProfile } returns MutableStateFlow(null)
        every { discoverSectionStorage.sectionConfigs } returns MutableStateFlow(emptyList())
        every { detailSectionStorage.sectionConfigs } returns MutableStateFlow(emptyList())
        every { statusConsistencyChecker.checkProgress } returns MutableStateFlow(ConsistencyCheckResult())
        every { doubanSyncManager.progress } returns MutableStateFlow(DoubanSyncProgress())
        every { aiTasteStorage.tasteUploadEnabled } returns MutableStateFlow(true)

        // init 块的 refreshCacheInfo() 调用的方法 stub
        every { offlineCacheManager.getDataStoreSizeBytes() } returns 0L
        every { offlineCacheManager.getImageCacheSizeBytes() } returns 0L
        every { offlineCacheManager.getHttpCacheSizeBytes() } returns 0L
        every { offlineCacheManager.getDatabaseSizeBytes() } returns 0L
        every { tmdbRepository.mediaDataCaches } returns emptyList()
        every { traktRepository.mediaDataCaches } returns emptyList()
        coEvery { doubanHotCache.getSizeBytes() } returns 0L

        viewModel = SettingsViewModel(
            themeStorage = themeStorage,
            searchSourceStorage = searchSourceStorage,
            notificationStorage = notificationStorage,
            notificationScheduler = notificationScheduler,
            crashLogStorage = crashLogStorage,
            crashLogUploader = crashLogUploader,
            languageStorage = languageStorage,
            traktRepository = traktRepository,
            tmdbRepository = tmdbRepository,
            updateRepository = updateRepository,
            offlineCacheManager = offlineCacheManager,
            doubanHotCache = doubanHotCache,
            doubanDetailCache = doubanDetailCache,
            discoverSectionStorage = discoverSectionStorage,
            detailSectionStorage = detailSectionStorage,
            panHubConfigStorage = panHubConfigStorage,
            defaultTabStorage = defaultTabStorage,
            doubanAuthStorage = doubanAuthStorage,
            doubanRepository = doubanRepository,
            cloudPersonalSyncManager = cloudPersonalSyncManager,
            doubanSyncMetaStorage = doubanSyncMetaStorage,
            cloudFailureSyncMetaStorage = cloudFailureSyncMetaStorage,
            lastConsistencyCheckStorage = lastConsistencyCheckStorage,
            statusConsistencyChecker = statusConsistencyChecker,
            doubanSyncManager = doubanSyncManager,
            doubanBatchRemovalManager = doubanBatchRemovalManager,
            sharedTransitionStorage = sharedTransitionStorage,
            doubanSyncedItemDao = doubanSyncedItemDao,
            sessionModeManager = sessionModeManager,
            imageTrafficStorage = mockk(relaxed = true),
            aiTasteStorage = aiTasteStorage,
            statisticsSnapshotStore = mockk(relaxed = true),
            context = context
        )
    }

    // ==================== 委托方法测试 ====================

    /**
     * 测试点1：setThemeMode 委托到 themeStorage.setThemeMode
     */
    @Test
    fun `setThemeMode 调用themeStorage的setThemeMode`() = runTest {
        viewModel.setThemeMode("dark")
        advanceUntilIdle()

        coVerify { themeStorage.setThemeMode("dark") }
    }

    @Test
    fun setVisualEffectSelectionWritesModeAndVariantTogether() = runTest {
        viewModel.setVisualEffectSelection(VisualEffectMode.GLASS, GlassVariant.FOCUSED)
        advanceUntilIdle()

        coVerify {
            themeStorage.setVisualEffectSelection(
                VisualEffectMode.GLASS,
                GlassVariant.FOCUSED
            )
        }
    }

    /**
     * 测试点2：setDefaultTab 委托到 defaultTabStorage.setDefaultTab
     */
    @Test
    fun `setDefaultTab 调用defaultTabStorage的setDefaultTab`() = runTest {
        viewModel.setDefaultTab(2)
        advanceUntilIdle()

        coVerify { defaultTabStorage.setDefaultTab(2) }
    }

    /**
     * 测试点3：setPansouEnabled 委托到 searchSourceStorage.setPansouEnabled
     */
    @Test
    fun `setPansouEnabled 调用searchSourceStorage的setPansouEnabled`() = runTest {
        viewModel.setPansouEnabled(true)
        advanceUntilIdle()

        coVerify { searchSourceStorage.setPansouEnabled(true) }
    }

    /**
     * 测试点4：setNotificationEnabled(true) 启用通知时调度周期检查
     */
    @Test
    fun `setNotificationEnabled 启用通知时调度周期检查`() = runTest {
        viewModel.setNotificationEnabled(true)
        advanceUntilIdle()

        coVerify { notificationStorage.setEnabled(true) }
        verify { notificationScheduler.schedulePeriodicCheck() }
    }

    /**
     * 测试点5：setNotificationEnabled(false) 禁用通知时取消周期检查
     */
    @Test
    fun `setNotificationEnabled 禁用通知时取消周期检查`() = runTest {
        viewModel.setNotificationEnabled(false)
        advanceUntilIdle()

        coVerify { notificationStorage.setEnabled(false) }
        verify { notificationScheduler.cancelPeriodicCheck() }
    }

    // ==================== 同步方法测试 ====================

    /**
     * 测试点6：dismissUpdateDialog 关闭更新弹窗
     */
    @Test
    fun `dismissUpdateDialog 关闭更新弹窗`() {
        viewModel.dismissUpdateDialog()

        assertThat(viewModel.showUpdateDialog.value).isFalse()
    }

    /**
     * 测试点7：clearDoubanCredentials 委托到 doubanAuthStorage.clearCredentials
     */
    @Test
    fun `clearDoubanCredentials 调用doubanAuthStorage的clearCredentials`() {
        viewModel.clearDoubanCredentials()

        verify { doubanAuthStorage.clearCredentials() }
    }

    /**
     * 测试点8：isCheckRunning 返回 statusConsistencyChecker.isRunning() 的值
     */
    @Test
    fun `isCheckRunning 返回statusConsistencyChecker的isRunning`() {
        // 返回 true
        every { statusConsistencyChecker.isRunning() } returns true
        assertThat(viewModel.isCheckRunning()).isTrue()

        // 返回 false
        every { statusConsistencyChecker.isRunning() } returns false
        assertThat(viewModel.isCheckRunning()).isFalse()
    }

    /**
     * 测试点9：cancelConsistencyCheck 委托到 statusConsistencyChecker.cancel
     */
    @Test
    fun `cancelConsistencyCheck 调用statusConsistencyChecker的cancel`() {
        viewModel.cancelConsistencyCheck()

        verify { statusConsistencyChecker.cancel() }
    }

    /**
     * 测试点10：clearMessage 清除消息（message 置为 null）
     */
    @Test
    fun `clearMessage 清除消息`() {
        viewModel.clearMessage()

        assertThat(viewModel.exportImportState.value.message).isNull()
    }

    // ==================== 崩溃日志上报开关测试 ====================

    /**
     * 测试点11：setCrashLogEnabled(true) 开启且有待传日志时触发上传
     */
    @Test
    fun `setCrashLogEnabled 开启且有待传日志时触发上传`() = runTest {
        every { crashLogUploader.hasPendingLogs() } returns true
        coEvery { crashLogUploader.uploadPendingLogs() } returns true

        viewModel.setCrashLogEnabled(true)
        advanceUntilIdle()

        coVerify { crashLogStorage.setEnabled(true) }
        coVerify { crashLogUploader.uploadPendingLogs() }
    }

    /**
     * 测试点12：setCrashLogEnabled(true) 开启但无待传日志时不触发上传
     */
    @Test
    fun `setCrashLogEnabled 开启但无待传日志时不触发上传`() = runTest {
        // hasPendingLogs() 默认（relaxed mock）返回 false，无需额外 stub
        viewModel.setCrashLogEnabled(true)
        advanceUntilIdle()

        coVerify { crashLogStorage.setEnabled(true) }
        coVerify(exactly = 0) { crashLogUploader.uploadPendingLogs() }
    }
}
