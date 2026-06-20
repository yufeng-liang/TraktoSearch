package com.tracktosearch.ui.screen.settings

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.CustomSearchSourceStorage
import com.tracktosearch.data.local.DiscoverSectionConfig
import com.tracktosearch.data.local.DiscoverSectionStorage
import com.tracktosearch.data.local.NotificationStorage
import com.tracktosearch.data.local.SearchSourceStorage
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.local.db.OfflineCacheManager
import com.tracktosearch.data.notification.NotificationScheduler
import com.tracktosearch.data.remote.custom.CustomSearchService
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.UpdateInfo
import com.tracktosearch.data.repository.UpdateRepository
import com.tracktosearch.data.util.DataExportImport
import com.tracktosearch.data.util.ExportItem
import com.tracktosearch.data.util.ImportItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ExportFormat { JSON, CSV }

data class ExportImportState(
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val message: String? = null,
    val importedItems: List<ImportItem> = emptyList()
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
    private val customSearchSourceStorage: CustomSearchSourceStorage,
    private val customSearchService: CustomSearchService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val themeMode: StateFlow<String> = themeStorage.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeStorage.MODE_SYSTEM)

    val language: StateFlow<String> = languageStorage.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LanguageStorage.LANGUAGE_SYSTEM)

    val pansouEnabled: StateFlow<Boolean> = searchSourceStorage.pansouEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val panhubEnabled: StateFlow<Boolean> = searchSourceStorage.panhubEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val zresoEnabled: StateFlow<Boolean> = searchSourceStorage.zresoEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val notificationEnabled: StateFlow<Boolean> = notificationStorage.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val releaseReminderEnabled: StateFlow<Boolean> = notificationStorage.releaseReminderEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val newSeasonReminderEnabled: StateFlow<Boolean> = notificationStorage.newSeasonReminderEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _exportImportState = MutableStateFlow(ExportImportState())
    val exportImportState: StateFlow<ExportImportState> = _exportImportState.asStateFlow()

    fun setThemeMode(mode: String) {
        viewModelScope.launch { themeStorage.setThemeMode(mode) }
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

    // ========== 自定义搜索源 ==========

    val customSources: StateFlow<List<CustomSearchSource>> = customSearchSourceStorage.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                        TestResultState(source.id, isTesting = false, success = true, message = "测试成功，解析到 ${result.count} 条结果")
                    } else {
                        TestResultState(source.id, isTesting = false, success = true, message = "连接成功，但未解析到结果")
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
                    message = "导出成功：想看 ${watchlistMovies.size} 部电影、${watchlistShows.size} 部电视剧，已看 ${historyMovies.size} 部电影、${historyShows.size} 部电视剧"
                )
            } catch (e: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    isExporting = false,
                    message = "导出失败：${e.message ?: "未知错误"}"
                )
            }
        }
    }

    fun importFromLetterboxd(uri: Uri) {
        viewModelScope.launch {
            _exportImportState.value = _exportImportState.value.copy(
                isImporting = true, message = null
            )
            try {
                val csvContent = readUriContent(uri)
                val items = DataExportImport.parseLetterboxdCsv(csvContent)
                _exportImportState.value = _exportImportState.value.copy(
                    isImporting = false,
                    importedItems = items,
                    message = "从 Letterboxd 导入 ${items.size} 条记录"
                )
            } catch (e: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    isImporting = false,
                    message = "导入失败：${e.message ?: "未知错误"}"
                )
            }
        }
    }

    fun importFromImdb(uri: Uri) {
        viewModelScope.launch {
            _exportImportState.value = _exportImportState.value.copy(
                isImporting = true, message = null
            )
            try {
                val csvContent = readUriContent(uri)
                val items = DataExportImport.parseImdbCsv(csvContent)
                _exportImportState.value = _exportImportState.value.copy(
                    isImporting = false,
                    importedItems = items,
                    message = "从 IMDb 导入 ${items.size} 条记录"
                )
            } catch (e: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    isImporting = false,
                    message = "导入失败：${e.message ?: "未知错误"}"
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
                    message = "缓存已清除"
                )
            } catch (e: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    message = "清除缓存失败：${e.message ?: "未知错误"}"
                )
            }
        }
    }

    // ========== 缓存信息 ==========

    private val _cacheInfo = MutableStateFlow("0 B")
    val cacheInfo: StateFlow<String> = _cacheInfo.asStateFlow()

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

    private val defaultSectionConfigs = DiscoverSectionStorage.ALL_SECTION_IDS.mapIndexed { index, id ->
        DiscoverSectionConfig(id = id, visible = true, order = index)
    }
    val discoverSections: StateFlow<List<DiscoverSectionConfig>> = discoverSectionStorage.sectionConfigs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), defaultSectionConfigs)

    fun setSectionVisible(id: String, visible: Boolean) {
        viewModelScope.launch { discoverSectionStorage.setSectionVisible(id, visible) }
    }

    fun moveSectionUp(id: String) {
        viewModelScope.launch { discoverSectionStorage.moveUp(id) }
    }

    fun moveSectionDown(id: String) {
        viewModelScope.launch { discoverSectionStorage.moveDown(id) }
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
                        message = "已是最新版本"
                    )
                } else {
                    _latestVersion.value = BuildConfig.VERSION_NAME
                    _exportImportState.value = _exportImportState.value.copy(
                        message = "检查更新失败，请稍后重试"
                    )
                }
            } catch (e: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    message = "检查更新失败：${e.message ?: "未知错误"}"
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
