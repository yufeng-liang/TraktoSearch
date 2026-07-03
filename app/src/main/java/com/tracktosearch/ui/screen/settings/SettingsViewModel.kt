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
import com.tracktosearch.data.local.DiscoverSectionConfig
import com.tracktosearch.data.local.DetailSectionConfig
import com.tracktosearch.data.local.DetailSectionStorage
import com.tracktosearch.data.local.DiscoverSectionStorage
import com.tracktosearch.data.local.NotificationStorage
import com.tracktosearch.data.local.PanHubConfigStorage
import com.tracktosearch.data.local.SearchSourceStorage
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.local.db.OfflineCacheManager
import com.tracktosearch.data.notification.NotificationScheduler
import com.tracktosearch.data.remote.custom.CustomSearchService
import com.tracktosearch.data.remote.trakt.dto.TraktUserProfileResponse
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.UpdateInfo
import com.tracktosearch.data.repository.UpdateRepository
import com.tracktosearch.data.remote.panhub.PanHubConfig
import com.tracktosearch.data.util.DataExportImport
import com.tracktosearch.data.util.ExportItem
import com.tracktosearch.data.util.ImportItem
import androidx.compose.runtime.Immutable
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

enum class ExportFormat { JSON, CSV }

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
    private val updateRepository: UpdateRepository,
    private val offlineCacheManager: OfflineCacheManager,
    private val discoverSectionStorage: DiscoverSectionStorage,
    private val detailSectionStorage: DetailSectionStorage,
    private val customSearchSourceStorage: CustomSearchSourceStorage,
    private val customSearchService: CustomSearchService,
    private val panHubConfigStorage: PanHubConfigStorage,
    private val defaultTabStorage: DefaultTabStorage,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // 用 runBlocking 同步预加载 DataStore 真实值作为 stateIn 初始值，
    // 避免初次进入设置页时开关先用硬编码默认值渲染、真实值到达后跳变
    private val initialThemeMode: String = runBlocking { themeStorage.themeMode.first() }
    val themeMode: StateFlow<String> = themeStorage.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialThemeMode)

    private val initialAccentColor: com.tracktosearch.ui.theme.MonetAccent? = runBlocking { themeStorage.accentColor.first() }
    val accentColor: StateFlow<com.tracktosearch.ui.theme.MonetAccent?> = themeStorage.accentColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialAccentColor)

    private val initialDefaultTab: Int = runBlocking { defaultTabStorage.defaultTab.first() }
    val defaultTab: StateFlow<Int> = defaultTabStorage.defaultTab
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialDefaultTab)

    private val initialLanguage: String = runBlocking { languageStorage.language.first() }
    val language: StateFlow<String> = languageStorage.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialLanguage)

    private val initialPansouEnabled: Boolean = runBlocking { searchSourceStorage.pansouEnabled.first() }
    val pansouEnabled: StateFlow<Boolean> = searchSourceStorage.pansouEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialPansouEnabled)

    private val initialPanhubEnabled: Boolean = runBlocking { searchSourceStorage.panhubEnabled.first() }
    val panhubEnabled: StateFlow<Boolean> = searchSourceStorage.panhubEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialPanhubEnabled)

    private val initialZresoEnabled: Boolean = runBlocking { searchSourceStorage.zresoEnabled.first() }
    val zresoEnabled: StateFlow<Boolean> = searchSourceStorage.zresoEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialZresoEnabled)

    private val initialNotificationEnabled: Boolean = runBlocking { notificationStorage.enabled.first() }
    val notificationEnabled: StateFlow<Boolean> = notificationStorage.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialNotificationEnabled)

    private val initialReleaseReminderEnabled: Boolean = runBlocking { notificationStorage.releaseReminderEnabled.first() }
    val releaseReminderEnabled: StateFlow<Boolean> = notificationStorage.releaseReminderEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialReleaseReminderEnabled)

    private val initialNewSeasonReminderEnabled: Boolean = runBlocking { notificationStorage.newSeasonReminderEnabled.first() }
    val newSeasonReminderEnabled: StateFlow<Boolean> = notificationStorage.newSeasonReminderEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialNewSeasonReminderEnabled)

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

    // ========== PanHub 配置 ==========

    private val initialPanHubConfig: PanHubConfig = runBlocking { panHubConfigStorage.config.first() }
    val panHubConfig: StateFlow<PanHubConfig> = panHubConfigStorage.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialPanHubConfig)

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

    private val initialCustomSources: List<CustomSearchSource> = runBlocking { customSearchSourceStorage.sources.first() }
    val customSources: StateFlow<List<CustomSearchSource>> = customSearchSourceStorage.sources
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            initialCustomSources
        )

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

    fun exportData(uri: Uri, format: ExportFormat) {
        viewModelScope.launch {
            _exportImportState.value = _exportImportState.value.copy(
                isExporting = true, message = null
            )
            try {
                val watchlistMovies = fetchAllMovieWatchlist()
                val watchlistShows = fetchAllShowWatchlist()
                val historyMovies = fetchAllMovieHistory()
                val historyShows = fetchAllShowHistory()

                val content = when (format) {
                    ExportFormat.JSON -> DataExportImport.exportToJson(
                        watchlistMovies = watchlistMovies.map { it.toExportItem() },
                        watchlistShows = watchlistShows.map { it.toExportItem() },
                        historyMovies = historyMovies.map { it.toExportItem() },
                        historyShows = historyShows.map { it.toExportItem() }
                    )
                    ExportFormat.CSV -> DataExportImport.exportToCsv(
                        movies = watchlistMovies.map { it.toExportItem() },
                        shows = watchlistShows.map { it.toExportItem() }
                    )
                }

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

    fun importFromLetterboxd(uri: Uri) {
        viewModelScope.launch {
            _exportImportState.value = _exportImportState.value.copy(
                isImporting = true, message = null, syncProgress = null, syncSuccess = 0, syncFailed = 0
            )
            try {
                val csvContent = readUriContent(uri)
                val items = DataExportImport.parseLetterboxdCsv(csvContent)
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
                        val searchResult = traktRepository.searchMovies(item.title, page = 1, limit = 1)
                        val traktId = searchResult.getOrNull()?.first?.firstOrNull()?.movie?.ids?.trakt
                        if (traktId != null && traktId > 0) {
                            traktRepository.markAsWatched(traktId, MediaType.MOVIE)
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
                    message = context.getString(R.string.snackbar_import_done_letterboxd, success, failed)
                )
            } catch (e: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    isImporting = false,
                    message = context.getString(R.string.snackbar_import_failed)
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
                val items = DataExportImport.parseImdbCsv(csvContent)
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
                    message = context.getString(R.string.snackbar_import_failed)
                )
            }
        }
    }

    fun importFromJson(uri: Uri) {
        viewModelScope.launch {
            _exportImportState.value = _exportImportState.value.copy(
                isImporting = true, message = null, syncProgress = null, syncSuccess = 0, syncFailed = 0
            )
            try {
                val jsonContent = readUriContent(uri)
                val exportData = DataExportImport.parseAppJson(jsonContent)

                // Collect all items to process: watchlist first, then history
                // markAsWatched auto-removes from watchlist, so add watchlist first
                // Triple: (ExportItem, MediaType, isHistory)
                val allItems = mutableListOf<Triple<ExportItem, MediaType, Boolean>>()
                exportData.watchlistMovies.forEach { allItems.add(Triple(it, MediaType.MOVIE, false)) }
                exportData.watchlistShows.forEach { allItems.add(Triple(it, MediaType.SHOW, false)) }
                exportData.historyMovies.forEach { allItems.add(Triple(it, MediaType.MOVIE, true)) }
                exportData.historyShows.forEach { allItems.add(Triple(it, MediaType.SHOW, true)) }

                if (allItems.isEmpty()) {
                    _exportImportState.value = _exportImportState.value.copy(
                        isImporting = false,
                        message = context.getString(R.string.snackbar_import_no_data)
                    )
                    return@launch
                }

                var success = 0
                var failed = 0
                val total = allItems.size

                allItems.forEachIndexed { index, (item, mediaType, isHistory) ->
                    _exportImportState.value = _exportImportState.value.copy(
                        syncProgress = context.getString(R.string.snackbar_sync_progress, index + 1, total, item.title)
                    )
                    try {
                        val traktId = if (item.tmdbId != null && item.tmdbId > 0) {
                            // Use TMDB ID search for precise matching
                            val searchResult = traktRepository.searchByTmdb(item.tmdbId, mediaType)
                            searchResult.getOrNull()?.firstOrNull()?.let { result ->
                                when (mediaType) {
                                    MediaType.MOVIE -> result.movie?.ids?.trakt
                                    MediaType.SHOW -> result.show?.ids?.trakt
                                    MediaType.PERSON -> null
                                    MediaType.DISK -> null
                                }
                            }
                        } else {
                            // Fallback to text search
                            val searchResult = when (mediaType) {
                                MediaType.MOVIE -> traktRepository.searchMovies(item.title, page = 1, limit = 1)
                                MediaType.SHOW -> traktRepository.searchShows(item.title, page = 1, limit = 1)
                                MediaType.PERSON -> Result.failure(Exception("Person not supported"))
                                MediaType.DISK -> Result.failure(Exception("DISK not supported"))
                            }
                            searchResult.getOrNull()?.first?.firstOrNull()?.let { result ->
                                when (mediaType) {
                                    MediaType.MOVIE -> result.movie?.ids?.trakt
                                    MediaType.SHOW -> result.show?.ids?.trakt
                                    MediaType.PERSON -> null
                                    MediaType.DISK -> null
                                }
                            }
                        }

                        if (traktId != null && traktId > 0) {
                            if (isHistory) {
                                traktRepository.markAsWatched(traktId, mediaType)
                            } else {
                                traktRepository.addToWatchlist(traktId, mediaType)
                            }
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
                    syncProgress = null,
                    syncSuccess = success,
                    syncFailed = failed,
                    message = context.getString(R.string.snackbar_import_done_json, success, failed)
                )
            } catch (e: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    isImporting = false,
                    message = context.getString(R.string.snackbar_import_failed)
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

    // ========== 缓存信息 ==========

    private val _cacheInfo = MutableStateFlow("0 B")
    val cacheInfo: StateFlow<String> = _cacheInfo.asStateFlow()

    // ========== 用户资料 ==========

    private val _userProfile = MutableStateFlow<TraktUserProfileResponse?>(null)
    val userProfile: StateFlow<TraktUserProfileResponse?> = _userProfile.asStateFlow()

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
                val sizeBytes = offlineCacheManager.getCacheSizeBytes()
                _cacheInfo.value = formatFileSize(sizeBytes)
            } catch (_: Exception) {
                _cacheInfo.value = "0 B"
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

    private val initialDiscoverSections: List<DiscoverSectionConfig> = runBlocking { discoverSectionStorage.sectionConfigs.first() }
    val discoverSections: StateFlow<List<DiscoverSectionConfig>> = discoverSectionStorage.sectionConfigs
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            initialDiscoverSections
        )

    fun setSectionVisible(id: String, visible: Boolean) {
        viewModelScope.launch { discoverSectionStorage.setSectionVisible(id, visible) }
    }

    fun setSectionOrder(orderedIds: List<String>) {
        viewModelScope.launch { discoverSectionStorage.setSectionOrder(orderedIds) }
    }

    // ========== 详情页模块设置 ==========

    private val initialDetailSections: List<DetailSectionConfig> = runBlocking { detailSectionStorage.sectionConfigs.first() }
    val detailSections: StateFlow<List<DetailSectionConfig>> = detailSectionStorage.sectionConfigs
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            initialDetailSections
        )

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
                val info = updateRepository.checkForUpdate()
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
