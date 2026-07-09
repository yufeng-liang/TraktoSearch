package com.tracktosearch.ui.screen.settings

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.BuildConfig
import com.tracktosearch.R
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.CustomSearchSourceStorage
import com.tracktosearch.data.local.DefaultTabStorage
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.CooldownStatus
import com.tracktosearch.data.local.DiscoverSectionConfig
import com.tracktosearch.data.local.DetailSectionConfig
import com.tracktosearch.data.local.DetailSectionStorage
import com.tracktosearch.data.local.DiscoverSectionStorage
import com.tracktosearch.data.local.NotificationStorage
import com.tracktosearch.data.local.PanHubConfigStorage
import com.tracktosearch.data.local.SearchSourceStorage
import com.tracktosearch.data.local.SharedTransitionStorage
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.local.db.OfflineCacheManager
import com.tracktosearch.data.notification.NotificationScheduler
import com.tracktosearch.data.remote.custom.CustomSearchService
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.dto.DoubanHotData
import com.tracktosearch.data.remote.trakt.dto.TraktUserProfileResponse
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem
import com.tracktosearch.data.repository.CloudPersonalSyncManager
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.UpdateInfo
import com.tracktosearch.data.repository.UpdateRepository
import com.tracktosearch.data.remote.panhub.PanHubConfig
import com.tracktosearch.data.util.DataExportImport
import com.tracktosearch.data.util.ExportItem
import com.tracktosearch.data.util.ParseResult
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.data.util.ImportItem
import androidx.compose.runtime.Immutable
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class ExportImportState(
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val message: String? = null,
    val importedItems: List<ImportItem> = emptyList(),
    val syncProgress: String? = null,  // e.g. "正在同步 3/50..."
    val syncSuccess: Int = 0,
    val syncFailed: Int = 0
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themeStorage: ThemeStorage,
    private val searchSourceStorage: SearchSourceStorage,
    private val notificationStorage: NotificationStorage,
    private val notificationScheduler: NotificationScheduler,
    private val languageStorage: LanguageStorage,
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository,
    private val updateRepository: UpdateRepository,
    private val offlineCacheManager: OfflineCacheManager,
    private val doubanHotCache: PersistentTtlCache<DoubanHotData>,
    private val doubanDetailCache: PersistentTtlCache<DoubanDetailCacheEntry>,
    private val discoverSectionStorage: DiscoverSectionStorage,
    private val detailSectionStorage: DetailSectionStorage,
    private val customSearchSourceStorage: CustomSearchSourceStorage,
    private val customSearchService: CustomSearchService,
    private val panHubConfigStorage: PanHubConfigStorage,
    private val defaultTabStorage: DefaultTabStorage,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanRepository: DoubanRepository,
    private val cloudPersonalSyncManager: CloudPersonalSyncManager,
    private val doubanSyncMetaStorage: DoubanSyncMetaStorage,
    private val sharedTransitionStorage: SharedTransitionStorage,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // Storage 已在 init 中预加载 DataStore 首值到 StateFlow,直接暴露无需 stateIn,消除默认值跳变
    val themeMode: StateFlow<String> = themeStorage.themeMode

    val accentColor: StateFlow<com.tracktosearch.ui.theme.MonetAccent?> = themeStorage.accentColor

    val defaultTab: StateFlow<Int> = defaultTabStorage.defaultTab

    val language: StateFlow<String> = languageStorage.language

    val pansouEnabled: StateFlow<Boolean> = searchSourceStorage.pansouEnabled

    val panhubEnabled: StateFlow<Boolean> = searchSourceStorage.panhubEnabled

    val zresoEnabled: StateFlow<Boolean> = searchSourceStorage.zresoEnabled

    val notificationEnabled: StateFlow<Boolean> = notificationStorage.enabled

    val releaseReminderEnabled: StateFlow<Boolean> = notificationStorage.releaseReminderEnabled

    val newSeasonReminderEnabled: StateFlow<Boolean> = notificationStorage.newSeasonReminderEnabled

    private val _exportImportState = MutableStateFlow(ExportImportState())
    val exportImportState: StateFlow<ExportImportState> = _exportImportState.asStateFlow()

    fun setThemeMode(mode: String) {
        viewModelScope.launch { themeStorage.setThemeMode(mode) }
    }

    fun setAccentColor(accent: com.tracktosearch.ui.theme.MonetAccent?) {
        viewModelScope.launch { themeStorage.setAccentColor(accent) }
    }

    fun setDefaultTab(tab: Int) {
        viewModelScope.launch { defaultTabStorage.setDefaultTab(tab) }
    }

    fun setLanguage(language: String) {
        // 立即应用语言（同步调用，确保 recreate() 时配置已更新）
        val locales = when (language) {
            LanguageStorage.LANGUAGE_CHINESE -> LocaleListCompat.forLanguageTags("zh-CN")
            LanguageStorage.LANGUAGE_ENGLISH -> LocaleListCompat.forLanguageTags("en")
            LanguageStorage.LANGUAGE_JAPANESE -> LocaleListCompat.forLanguageTags("ja")
            LanguageStorage.LANGUAGE_KOREAN -> LocaleListCompat.forLanguageTags("ko")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
        // 持久化存储
        viewModelScope.launch {
            languageStorage.setLanguage(language)
        }
    }

    fun setPansouEnabled(enabled: Boolean) {
        viewModelScope.launch { searchSourceStorage.setPansouEnabled(enabled) }
    }

    fun setPanhubEnabled(enabled: Boolean) {
        viewModelScope.launch { searchSourceStorage.setPanhubEnabled(enabled) }
    }

    fun setZresoEnabled(enabled: Boolean) {
        viewModelScope.launch { searchSourceStorage.setZresoEnabled(enabled) }
    }

    fun setSharedTransitionEnabled(enabled: Boolean) {
        viewModelScope.launch { sharedTransitionStorage.setEnabled(enabled) }
    }

    // ========== PanHub 配置 ==========

    val panHubConfig: StateFlow<PanHubConfig> = panHubConfigStorage.config

    fun setPanHubConcurrency(value: Int) {
        viewModelScope.launch { panHubConfigStorage.setConcurrency(value) }
    }

    fun setPanHubTimeoutMs(value: Int) {
        viewModelScope.launch { panHubConfigStorage.setTimeoutMs(value) }
    }

    fun setPanHubEnabledPlugins(pluginIds: Set<String>) {
        viewModelScope.launch { panHubConfigStorage.setEnabledPlugins(pluginIds) }
    }

    fun setPanHubEnabledChannels(channelIds: Set<String>) {
        viewModelScope.launch { panHubConfigStorage.setEnabledChannels(channelIds) }
    }

    // ========== 自定义搜索源 ==========

    val customSources: StateFlow<List<CustomSearchSource>> = customSearchSourceStorage.sources

    fun addCustomSource(source: CustomSearchSource) {
        viewModelScope.launch { customSearchSourceStorage.addSource(source) }
    }

    fun updateCustomSource(source: CustomSearchSource) {
        viewModelScope.launch { customSearchSourceStorage.updateSource(source) }
    }

    fun deleteCustomSource(id: String) {
        viewModelScope.launch { customSearchSourceStorage.deleteSource(id) }
    }

    fun setCustomSourceEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { customSearchSourceStorage.setEnabled(id, enabled) }
    }

    // 测试结果状态
    @Immutable
    data class TestResultState(
        val sourceId: String,
        val isTesting: Boolean = false,
        val success: Boolean? = null,
        val message: String? = null
    )

    private val _testResults = MutableStateFlow<Map<String, TestResultState>>(emptyMap())
    val testResults: StateFlow<Map<String, TestResultState>> = _testResults.asStateFlow()

    fun testCustomSource(source: CustomSearchSource) {
        viewModelScope.launch {
            _testResults.value = _testResults.value + (source.id to TestResultState(source.id, isTesting = true))
            val result = customSearchService.testSource(source)
            val state = when (result) {
                is CustomSearchService.TestResult.Success -> {
                    if (result.count > 0) {
                        TestResultState(source.id, isTesting = false, success = true, message = context.getString(R.string.snackbar_test_success, result.count))
                    } else {
                        TestResultState(source.id, isTesting = false, success = true, message = context.getString(R.string.snackbar_test_empty))
                    }
                }
                is CustomSearchService.TestResult.Error -> {
                    TestResultState(source.id, isTesting = false, success = false, message = result.message)
                }
            }
            _testResults.value = _testResults.value + (source.id to state)
        }
    }

    fun clearTestResult(sourceId: String) {
        _testResults.value = _testResults.value - sourceId
    }

    fun setNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            notificationStorage.setEnabled(enabled)
            if (enabled) {
                notificationScheduler.schedulePeriodicCheck()
            } else {
                notificationScheduler.cancelPeriodicCheck()
            }
        }
    }

    fun setReleaseReminderEnabled(enabled: Boolean) {
        viewModelScope.launch { notificationStorage.setReleaseReminderEnabled(enabled) }
    }

    fun setNewSeasonReminderEnabled(enabled: Boolean) {
        viewModelScope.launch { notificationStorage.setNewSeasonReminderEnabled(enabled) }
    }

    fun exportData(uri: Uri) {
        viewModelScope.launch {
            _exportImportState.value = _exportImportState.value.copy(
                isExporting = true, message = null
            )
            try {
                val watchlistMovies = fetchAllMovieWatchlist()
                val watchlistShows = fetchAllShowWatchlist()
                val historyMovies = fetchAllMovieHistory()
                val historyShows = fetchAllShowHistory()

                val content = DataExportImport.exportToJson(
                    watchlistMovies = watchlistMovies.map { it.toExportItem() },
                    watchlistShows = watchlistShows.map { it.toExportItem() },
                    historyMovies = historyMovies.map { it.toExportItem() },
                    historyShows = historyShows.map { it.toExportItem() }
                )

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray(Charsets.UTF_8))
                }

                _exportImportState.value = _exportImportState.value.copy(
                    isExporting = false,
                    message = context.getString(R.string.snackbar_export_success)
                )
            } catch (e: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    isExporting = false,
                    message = context.getString(R.string.snackbar_export_failed)
                )
            }
        }
    }

    fun importFromImdb(uri: Uri) {
        viewModelScope.launch {
            _exportImportState.value = _exportImportState.value.copy(
                isImporting = true, message = null, syncProgress = null, syncSuccess = 0, syncFailed = 0
            )
            try {
                val csvContent = readUriContent(uri)
                // 严格校验:根据 ParseResult 分支处理
                val items = when (val result = DataExportImport.parseImdbCsv(csvContent)) {
                    is ParseResult.Success -> result.items
                    is ParseResult.MissingRequiredColumns -> {
                        _exportImportState.value = _exportImportState.value.copy(
                            isImporting = false,
                            message = context.getString(R.string.error_not_imdb_csv)
                        )
                        return@launch
                    }
                    is ParseResult.Empty -> {
                        _exportImportState.value = _exportImportState.value.copy(
                            isImporting = false,
                            message = context.getString(R.string.error_empty_csv)
                        )
                        return@launch
                    }
                    is ParseResult.Error -> {
                        _exportImportState.value = _exportImportState.value.copy(
                            isImporting = false,
                            message = context.getString(R.string.error_parse_failed, result.message)
                        )
                        return@launch
                    }
                }
                if (items.isEmpty()) {
                    _exportImportState.value = _exportImportState.value.copy(
                        isImporting = false,
                        message = context.getString(R.string.snackbar_import_no_data)
                    )
                    return@launch
                }

                var success = 0
                var failed = 0
                val total = items.size

                items.forEachIndexed { index, item ->
                    _exportImportState.value = _exportImportState.value.copy(
                        syncProgress = context.getString(R.string.snackbar_sync_progress, index + 1, total, item.title)
                    )
                    try {
                        val mediaType = when (item.mediaType) {
                            "show" -> MediaType.SHOW
                            "movie" -> MediaType.MOVIE
                            else -> MediaType.MOVIE  // default to movie if unknown
                        }
                        val searchResult = when (mediaType) {
                            MediaType.MOVIE -> traktRepository.searchMovies(item.title, page = 1, limit = 1)
                            MediaType.SHOW -> traktRepository.searchShows(item.title, page = 1, limit = 1)
                            MediaType.PERSON -> Result.failure(Exception("Person not supported"))
                            MediaType.DISK -> Result.failure(Exception("DISK not supported"))
                        }
                        val traktId = searchResult.getOrNull()?.first?.firstOrNull()?.let { result ->
                            when (mediaType) {
                                MediaType.MOVIE -> result.movie?.ids?.trakt
                                MediaType.SHOW -> result.show?.ids?.trakt
                                MediaType.PERSON -> null
                                MediaType.DISK -> null
                            }
                        }
                        if (traktId != null && traktId > 0) {
                            // addToWatchlist 内部已调用 addToWatchlistCache,
                            // WatchlistWatchedIds 全局缓存会同步新增该 traktId,
                            // 切回 Watchlist 页 MovieCard 的想看标记可立即命中缓存。
                            traktRepository.addToWatchlist(traktId, mediaType)
                            success++
                        } else {
                            failed++
                        }
                    } catch (_: Exception) {
                        failed++
                    }
                    if (index < total - 1 && index % 5 == 4) delay(200)
                }

                _exportImportState.value = _exportImportState.value.copy(
                    isImporting = false,
                    importedItems = items,
                    syncProgress = null,
                    syncSuccess = success,
                    syncFailed = failed,
                    message = context.getString(R.string.snackbar_import_done_imdb, success, failed)
                )
            } catch (e: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    isImporting = false,
                    message = context.getString(R.string.error_parse_failed, e.message ?: "")
                )
            }
        }
    }

    fun clearMessage() {
        _exportImportState.value = _exportImportState.value.copy(message = null)
    }

    fun clearCache() {
        viewModelScope.launch {
            try {
                offlineCacheManager.clearAll()
                // 同时清除所有持久化缓存（DataStore 按 key 前缀删除）
                tmdbRepository.persistentCaches.forEach { it.clearAll() }
                traktRepository.persistentCaches.forEach { it.clearAll() }
                doubanHotCache.clearAll()
                doubanDetailCache.clearAll()
                refreshCacheInfo()
                _exportImportState.value = _exportImportState.value.copy(
                    message = context.getString(R.string.snackbar_cache_cleared)
                )
            } catch (e: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    message = context.getString(R.string.snackbar_cache_clear_failed)
                )
            }
        }
    }

    // ========== 缓存管理（分项显示与清除） ==========

    /** 缓存类目 */
    enum class CacheCategory {
        IMAGE,       // 图片缓存（Coil 磁盘）
        MEDIA_DATA,  // 影视数据缓存（TMDB 详情/演职员 + Trakt 趋势/列表 + 豆瓣热榜）
        ID_MAPPING,  // ID 映射缓存（TMDB↔Trakt、IMDb↔Trakt、豆瓣→IMDb）
        HTTP,        // HTTP 缓存（OkHttp 响应）
        DATABASE     // 离线数据库（Room DB）
    }

    /** 缓存分项明细 */
    @Immutable
    data class CacheBreakdown(
        val imageBytes: Long = 0L,
        val mediaDataBytes: Long = 0L,
        val idMappingBytes: Long = 0L,
        val httpBytes: Long = 0L,
        val databaseBytes: Long = 0L
    ) {
        val totalBytes: Long get() = imageBytes + mediaDataBytes + idMappingBytes + httpBytes + databaseBytes
    }

    private val _cacheBreakdown = MutableStateFlow(CacheBreakdown())
    val cacheBreakdown: StateFlow<CacheBreakdown> = _cacheBreakdown.asStateFlow()

    /** 旧 API：兼容只显示总大小的调用方 */
    val cacheInfo: StateFlow<String> = _cacheBreakdown
        .map { formatFileSize(it.totalBytes) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0 B")

    /** 按类目清除缓存 */
    fun clearCategory(category: CacheCategory) {
        viewModelScope.launch {
            try {
                when (category) {
                    CacheCategory.IMAGE -> offlineCacheManager.clearImageCache()
                    CacheCategory.HTTP -> offlineCacheManager.clearHttpCache()
                    CacheCategory.DATABASE -> offlineCacheManager.clearDatabase()
                    CacheCategory.MEDIA_DATA -> {
                        // 清除影视数据类的所有 PersistentTtlCache（按 key 前缀删 DataStore）
                        tmdbRepository.mediaDataCaches.forEach { it.clearAll() }
                        traktRepository.mediaDataCaches.forEach { it.clearAll() }
                        doubanHotCache.clearAll()
                    }
                    CacheCategory.ID_MAPPING -> {
                        traktRepository.idMappingCaches.forEach { it.clearAll() }
                        doubanDetailCache.clearAll()
                    }
                }
                refreshCacheInfo()
                _exportImportState.value = _exportImportState.value.copy(
                    message = context.getString(R.string.snackbar_cache_cleared)
                )
            } catch (e: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    message = context.getString(R.string.snackbar_cache_clear_failed)
                )
            }
        }
    }

    // ========== 用户资料 ==========

    private val _userProfile = MutableStateFlow<TraktUserProfileResponse?>(null)
    val userProfile: StateFlow<TraktUserProfileResponse?> = _userProfile.asStateFlow()

    // ========== 豆瓣登录态（账户区展示用） ==========
    val doubanLoggedIn: StateFlow<Boolean> = doubanAuthStorage.isLoggedIn

    // 豆瓣 userId：登录态变化时从加密存储读取
    val doubanUserId: StateFlow<String?> = doubanAuthStorage.isLoggedIn
        .map { loggedIn -> if (loggedIn) doubanAuthStorage.getCredentials()?.userId else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // 豆瓣用户资料（头像/昵称）：从加密存储恢复，登录后异步抓取
    val doubanProfile: StateFlow<com.tracktosearch.data.local.DoubanUserProfile?> = doubanAuthStorage.doubanProfile

    // ========== 增量同步冷却期状态(跨设备同步显示) ==========
    private val _cooldownStatus = MutableStateFlow<CooldownStatus?>(null)
    val cooldownStatus: StateFlow<CooldownStatus?> = _cooldownStatus.asStateFlow()

    /**
     * 刷新冷却期状态:先轻量拉云端 sync_meta 合并到本地(确保跨设备 lastFullSyncAt 准确),
     * 再读本地冷却状态。用户点击「重新同步豆瓣」弹出模式选择对话框前调用。
     */
    fun refreshCooldownStatus() {
        viewModelScope.launch {
            // 豆瓣未登录时不显示冷却状态
            if (!doubanAuthStorage.isLoggedIn.value) {
                _cooldownStatus.value = null
                return@launch
            }
            // 先尝试合并云端 meta(失败也继续,用本地值兜底)
            runCatching { cloudPersonalSyncManager.refreshMetaOnly() }
            _cooldownStatus.value = doubanSyncMetaStorage.getCooldownStatus()
        }
    }

    /**
     * 仅从本地读取冷却期状态(不发网络请求)。
     * 供设置页首次进入时显示冷却期标签,云端 meta 已在豆瓣登录后/上次同步时刷新。
     */
    fun loadCooldownStatusFromLocal() {
        viewModelScope.launch {
            if (!doubanAuthStorage.isLoggedIn.value) {
                _cooldownStatus.value = null
                return@launch
            }
            _cooldownStatus.value = doubanSyncMetaStorage.getCooldownStatus()
        }
    }

    /** 清除豆瓣凭据（退出登录） */
    fun clearDoubanCredentials() {
        doubanAuthStorage.clearCredentials()
    }

    /**
     * 异步抓取豆瓣用户主页，刷新头像/昵称并持久化。
     * - 已登录但 profile 为空时触发（设置页打开或登录成功后调用）
     * - 抓取失败静默忽略，UI 仍显示 ID 兜底
     */
    fun loadDoubanProfile() {
        if (!doubanAuthStorage.isLoggedIn.value) return
        // 已有 profile 不重复抓取（除非强制刷新）
        if (doubanAuthStorage.doubanProfile.value != null) return
        viewModelScope.launch {
            val creds = doubanAuthStorage.getCredentials() ?: return@launch
            val profile = try {
                doubanRepository.fetchUserProfile(creds.userId, creds.cookie)
            } catch (_: Exception) { null }
            if (profile != null) {
                doubanAuthStorage.saveUserProfile(profile.nickname, profile.avatarUrl)
            }
        }
    }

    fun loadUserProfile() {
        if (_userProfile.value != null) return
        viewModelScope.launch {
            traktRepository.getUserProfile()
                .onSuccess { _userProfile.value = it }
        }
    }

    fun clearUserProfile() {
        _userProfile.value = null
        viewModelScope.launch {
            traktRepository.clearUserProfileCache()
            traktRepository.clearWatchlistWatchedCache()
        }
    }

    init {
        refreshCacheInfo()
    }

    fun refreshCacheInfo() {
        viewModelScope.launch {
            try {
                // DataStore 文件大小（含影视数据 + ID 映射 + 豆瓣详情，共用同一个目录）
                val dataStoreTotal = offlineCacheManager.getDataStoreSizeBytes()
                // 按 PersistentTtlCache.getSizeBytes() 比例拆分影视数据 vs ID 映射
                val mediaDataBytes = (
                    tmdbRepository.mediaDataCaches.sumOf { it.getSizeBytes() } +
                        traktRepository.mediaDataCaches.sumOf { it.getSizeBytes() } +
                        doubanHotCache.getSizeBytes()
                    ).coerceAtMost(dataStoreTotal)
                val idMappingBytes = (dataStoreTotal - mediaDataBytes).coerceAtLeast(0L)
                _cacheBreakdown.value = CacheBreakdown(
                    imageBytes = offlineCacheManager.getImageCacheSizeBytes(),
                    mediaDataBytes = mediaDataBytes,
                    idMappingBytes = idMappingBytes,
                    httpBytes = offlineCacheManager.getHttpCacheSizeBytes(),
                    databaseBytes = offlineCacheManager.getDatabaseSizeBytes()
                )
            } catch (_: Exception) {
                _cacheBreakdown.value = CacheBreakdown()
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return if (unitIndex == 0) "${bytes} B"
               else String.format("%.1f %s", size, units[unitIndex])
    }

    // ========== 发现页栏目设置 ==========

    val discoverSections: StateFlow<List<DiscoverSectionConfig>> = discoverSectionStorage.sectionConfigs

    fun setSectionVisible(id: String, visible: Boolean) {
        viewModelScope.launch { discoverSectionStorage.setSectionVisible(id, visible) }
    }

    fun setSectionOrder(orderedIds: List<String>) {
        viewModelScope.launch { discoverSectionStorage.setSectionOrder(orderedIds) }
    }

    // ========== 详情页模块设置 ==========

    val detailSections: StateFlow<List<DetailSectionConfig>> = detailSectionStorage.sectionConfigs

    fun setDetailSectionVisible(id: String, visible: Boolean) {
        viewModelScope.launch { detailSectionStorage.setSectionVisible(id, visible) }
    }

    // ========== 更新日志 ==========

    private val _changelog = MutableStateFlow<String?>(null)
    val changelog: StateFlow<String?> = _changelog.asStateFlow()

    private val _isLoadingChangelog = MutableStateFlow(false)
    val isLoadingChangelog: StateFlow<Boolean> = _isLoadingChangelog.asStateFlow()

    fun loadChangelog() {
        if (_isLoadingChangelog.value) return
        viewModelScope.launch {
            _isLoadingChangelog.value = true
            try {
                _changelog.value = updateRepository.fetchAllChangelogs()
            } catch (_: Exception) {
                if (_changelog.value == null) _changelog.value = ""
            } finally {
                _isLoadingChangelog.value = false
            }
        }
    }

    // ========== 版本检查 ==========

    private val _latestVersion = MutableStateFlow<String?>(null)
    val latestVersion: StateFlow<String?> = _latestVersion.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    // 是否在设置页弹出更新弹窗
    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }

    fun checkUpdate() {
        if (_isCheckingUpdate.value) return
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            try {
                // 手动检查强制走网络，绕过 24 小时缓存
                val info = updateRepository.checkForUpdate(force = true)
                if (info != null && info.hasUpdate) {
                    _latestVersion.value = info.latestVersion
                    _updateInfo.value = info
                    _changelog.value = info.changelog
                    _showUpdateDialog.value = true
                } else if (info != null) {
                    _latestVersion.value = info.latestVersion
                    _changelog.value = info.changelog
                    _exportImportState.value = _exportImportState.value.copy(
                        message = context.getString(R.string.snackbar_already_latest)
                    )
                } else {
                    _latestVersion.value = BuildConfig.VERSION_NAME
                    _exportImportState.value = _exportImportState.value.copy(
                        message = context.getString(R.string.snackbar_check_update_failed)
                    )
                }
            } catch (e: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    message = context.getString(R.string.snackbar_check_update_failed)
                )
            } finally {
                _isCheckingUpdate.value = false
            }
        }
    }

    private suspend fun fetchAllMovieWatchlist(): List<TraktWatchlistMovieItem> {
        val all = mutableListOf<TraktWatchlistMovieItem>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            traktRepository.getMovieWatchlist(page = page, limit = 200)
                .onSuccess { (items, tp) ->
                    all.addAll(items)
                    totalPages = tp
                }.onFailure { throw it }
            page++
        }
        return all
    }

    private suspend fun fetchAllShowWatchlist(): List<TraktWatchlistShowItem> {
        val all = mutableListOf<TraktWatchlistShowItem>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            traktRepository.getShowWatchlist(page = page, limit = 200)
                .onSuccess { (items, tp) ->
                    all.addAll(items)
                    totalPages = tp
                }.onFailure { throw it }
            page++
        }
        return all
    }

    private suspend fun fetchAllMovieHistory(): List<TraktWatchlistMovieItem> {
        val all = mutableListOf<TraktWatchlistMovieItem>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            traktRepository.getMovieHistory(page = page, limit = 200)
                .onSuccess { (items, tp) ->
                    all.addAll(items)
                    totalPages = tp
                }.onFailure { throw it }
            page++
        }
        return all
    }

    private suspend fun fetchAllShowHistory(): List<TraktWatchlistShowItem> {
        val all = mutableListOf<TraktWatchlistShowItem>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            traktRepository.getShowHistory(page = page, limit = 200)
                .onSuccess { (items, tp) ->
                    all.addAll(items)
                    totalPages = tp
                }.onFailure { throw it }
            page++
        }
        return all
    }

    private fun readUriContent(uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw Exception("无法读取文件")
    }

    private fun TraktWatchlistMovieItem.toExportItem() = ExportItem(
        title = movie.title,
        tmdbId = movie.ids.tmdb.takeIf { it > 0 },
        imdbId = movie.ids.imdb.ifEmpty { null },
        watchedAt = listed_at.ifEmpty { null }
    )

    private fun TraktWatchlistShowItem.toExportItem() = ExportItem(
        title = show.title,
        tmdbId = show.ids.tmdb.takeIf { it > 0 },
        imdbId = show.ids.imdb.ifEmpty { null },
        watchedAt = listed_at.ifEmpty { null }
    )
}
