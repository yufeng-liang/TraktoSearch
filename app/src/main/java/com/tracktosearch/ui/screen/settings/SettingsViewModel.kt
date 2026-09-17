package com.tracktosearch.ui.screen.settings

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.BuildConfig
import com.tracktosearch.R
import com.tracktosearch.data.local.DefaultTabStorage
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.CloudFailureSyncMetaStorage
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.LastConsistencyCheckStorage
import com.tracktosearch.data.local.CooldownStatus
import com.tracktosearch.data.local.ImageTrafficStats
import com.tracktosearch.data.local.ImageTrafficStorage
import com.tracktosearch.data.local.DiscoverSectionConfig
import com.tracktosearch.data.local.DetailSectionConfig
import com.tracktosearch.data.local.DetailSectionStorage
import com.tracktosearch.data.local.DiscoverSectionStorage
import com.tracktosearch.data.local.HapticStorage
import com.tracktosearch.data.local.NotificationStorage
import com.tracktosearch.data.local.PanHubConfigStorage
import com.tracktosearch.data.local.SearchSourceStorage
import com.tracktosearch.data.local.SwiftieEggStorage
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.local.db.OfflineCacheManager
import com.tracktosearch.data.notification.NotificationScheduler
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.ui.haptic.HapticCapabilities
import com.tracktosearch.ui.haptic.HapticMode
import com.tracktosearch.ui.haptic.HapticOutcome
import com.tracktosearch.ui.haptic.HapticSystemState
import com.tracktosearch.ui.haptic.systemHapticFeedbackEnabled
import com.tracktosearch.ui.theme.GlassVariant
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.dto.DoubanHotData
import com.tracktosearch.data.remote.trakt.dto.TraktUserProfileResponse
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem
import com.tracktosearch.data.repository.CloudPersonalSyncManager
import com.tracktosearch.data.repository.ConsistencyCheckResult
import com.tracktosearch.data.repository.DoubanBatchRemovalManager
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.DoubanTraktStatusConsistencyChecker
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * 导出/导入/清缓存/检查更新等操作的一次性反馈消息(机器码)。
 * VM 不拼接本地化文案，UI 组合期按 resId+args 用 stringResource 渲染。
 *
 * [outcome] 把「这条消息是好消息还是坏消息」挂在消息本身上，界面那一处 snackbar 顺手
 * 就能发对应的触感。刻意**不给默认值**：本 VM 没有统一漏斗，17 处构造散在六个方法里，
 * 有默认值就一定会漏；没有默认值时新增一处构造编译器就会拦住，逼着当场表态。
 */
@Immutable
sealed interface ExportMessage {
    val resId: Int
    val args: List<Any> get() = emptyList()

    /**
     * 这条消息对应的操作结果，null 表示不该发触感。
     *
     * 只有进度类消息该给 null（[ExportImportState.syncProgress] 逐条刷新，一条一记就成了
     * 连震）。结果类消息一律给出方向 —— 判据是「用户还得再动一次手吗」，
     * 所以部分成功（导入有失败项）算 [HapticOutcome.FAILURE]。
     */
    val outcome: HapticOutcome?

    /** 无参数消息(成功/失败/提示类) */
    @Immutable
    data class Plain(
        override val resId: Int,
        override val outcome: HapticOutcome?,
    ) : ExportMessage

    /** 带格式化参数消息(args 顺序与资源占位符一一对应) */
    @Immutable
    data class Formatted(
        override val resId: Int,
        override val args: List<Any>,
        override val outcome: HapticOutcome?,
    ) : ExportMessage
}

@Immutable
data class ExportImportState(
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    /** 操作结果反馈(机器码,UI 组合期转本地化文案) */
    val message: ExportMessage? = null,
    val importedItems: List<ImportItem> = emptyList(),
    /** IMDb 导入逐条进度(机器码,UI 组合期转本地化文案) */
    val syncProgress: ExportMessage? = null,
    val syncSuccess: Int = 0,
    val syncFailed: Int = 0
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themeStorage: ThemeStorage,
    private val searchSourceStorage: SearchSourceStorage,
    private val notificationStorage: NotificationStorage,
    private val notificationScheduler: NotificationScheduler,
    private val crashLogStorage: com.tracktosearch.data.local.CrashLogStorage,
    private val crashLogUploader: com.tracktosearch.data.util.CrashLogUploader,
    private val languageStorage: LanguageStorage,
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository,
    private val updateRepository: UpdateRepository,
    private val offlineCacheManager: OfflineCacheManager,
    private val doubanHotCache: PersistentTtlCache<DoubanHotData>,
    private val doubanDetailCache: PersistentTtlCache<DoubanDetailCacheEntry>,
    private val discoverSectionStorage: DiscoverSectionStorage,
    private val detailSectionStorage: DetailSectionStorage,
    private val panHubConfigStorage: PanHubConfigStorage,
    private val defaultTabStorage: DefaultTabStorage,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanRepository: DoubanRepository,
    private val cloudPersonalSyncManager: CloudPersonalSyncManager,
    private val doubanSyncMetaStorage: DoubanSyncMetaStorage,
    private val cloudFailureSyncMetaStorage: CloudFailureSyncMetaStorage,
    private val lastConsistencyCheckStorage: LastConsistencyCheckStorage,
    private val statusConsistencyChecker: DoubanTraktStatusConsistencyChecker,
    private val doubanSyncManager: DoubanSyncManager,
    private val doubanBatchRemovalManager: DoubanBatchRemovalManager,
    private val hapticStorage: HapticStorage,
    private val splashQuoteStorage: com.tracktosearch.data.local.SplashQuoteStorage,
    private val doubanSyncedItemDao: DoubanSyncedItemDao,
    private val sessionModeManager: SessionModeManager,
    private val imageTrafficStorage: ImageTrafficStorage,
    private val aiTasteStorage: com.tracktosearch.data.local.AiTasteStorage,
    private val statisticsSnapshotStore: com.tracktosearch.data.local.StatisticsSnapshotStore,
    private val swiftieEggStorage: SwiftieEggStorage,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // Storage 已在 init 中预加载 DataStore 首值到 StateFlow,直接暴露无需 stateIn,消除默认值跳变
    val themeMode: StateFlow<String> = themeStorage.themeMode

    val accentColor: StateFlow<com.tracktosearch.ui.theme.MonetAccent?> = themeStorage.accentColor

    /** 自定义色调收藏列表（上限 8），与选中值分属两个 Flow，避免整表重组 */
    val customAccentColors: StateFlow<List<Long>> = themeStorage.customAccentColors

    val selectedCustomAccentArgb: StateFlow<Long?> = themeStorage.selectedCustomAccentArgb

    /** 旧单值视图（迁移期保留，UI 重画完成后删除） */
    @Deprecated("改用 customAccentColors / selectedCustomAccentArgb")
    val customAccentArgb: StateFlow<Long?> = themeStorage.customAccentArgb

    val visualEffectMode: StateFlow<VisualEffectMode> = themeStorage.visualEffectMode

    val glassVariant: StateFlow<GlassVariant> = themeStorage.glassVariant

    /** 开屏台词开关：Storage 启动时已预加载磁盘首值，直接暴露不会有默认值跳变 */
    val splashQuoteEnabled: StateFlow<Boolean> = splashQuoteStorage.enabledState

    /** 触感三档（跟随系统 / 关闭 / 增强）：同样已在启动时预加载，无默认值跳变 */
    val hapticMode: StateFlow<HapticMode> = hapticStorage.modeState

    private val _hapticSystemState = MutableStateFlow(HapticSystemState.OPTIMISTIC)

    /**
     * 触感的设备侧前提：系统总开关 + 有无马达。
     *
     * 与 [hapticMode] 分开是因为这两项不是应用状态而是环境状态，应用改不了、也不该缓存过夜 ——
     * 用户随时可能切到系统设置里把触感关掉再回来。重读时机见 [refreshHapticSystemState]。
     */
    val hapticSystemState: StateFlow<HapticSystemState> = _hapticSystemState.asStateFlow()

    // 主页面背景彩色弥散光晕
    val meshPreset: StateFlow<String> = themeStorage.meshPreset
    val meshEnabled: StateFlow<Boolean> = themeStorage.meshEnabled

    /** 霉粉彩蛋解锁位：未解锁时「背景光晕」列表里整项不出现「星云」 */
    val swiftieUnlocked: StateFlow<Boolean> = swiftieEggStorage.unlocked

    val defaultTab: StateFlow<Int> = defaultTabStorage.defaultTab

    val language: StateFlow<String> = languageStorage.language

    val pansouEnabled: StateFlow<Boolean> = searchSourceStorage.pansouEnabled

    val panhubEnabled: StateFlow<Boolean> = searchSourceStorage.panhubEnabled

    val zresoEnabled: StateFlow<Boolean> = searchSourceStorage.zresoEnabled

    val notificationEnabled: StateFlow<Boolean> = notificationStorage.enabled

    val releaseReminderEnabled: StateFlow<Boolean> = notificationStorage.releaseReminderEnabled

    val newSeasonReminderEnabled: StateFlow<Boolean> = notificationStorage.newSeasonReminderEnabled

    /** 崩溃日志上报开关（默认关闭，需用户授权） */
    val crashLogEnabled: StateFlow<Boolean> = crashLogStorage.enabled

    /**
     * 「锐评我的看单」数据上传开关（默认开启）。
     * 存储层是冷 Flow，这里 stateIn 起来给设置页开关绑定；首值落地前用默认 true 占位。
     */
    val aiTasteEnabled: StateFlow<Boolean> = aiTasteStorage.tasteUploadEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _exportImportState = MutableStateFlow(ExportImportState())
    val exportImportState: StateFlow<ExportImportState> = _exportImportState.asStateFlow()

    fun setThemeMode(mode: String) {
        viewModelScope.launch { themeStorage.setThemeMode(mode) }
    }

    fun setAccentColor(accent: com.tracktosearch.ui.theme.MonetAccent?) {
        viewModelScope.launch { themeStorage.setAccentColor(accent) }
    }

    @Deprecated("改用 addCustomAccent / removeCustomAccent / setSelectedCustomAccent")
    fun setCustomAccent(argb: Long?) {
        viewModelScope.launch { themeStorage.setCustomAccent(argb) }
    }

    fun addCustomAccent(argb: Long) {
        viewModelScope.launch { themeStorage.addCustomAccent(argb) }
    }

    fun removeCustomAccent(argb: Long) {
        viewModelScope.launch { themeStorage.removeCustomAccent(argb) }
    }

    fun updateCustomAccent(old: Long, new: Long) {
        viewModelScope.launch { themeStorage.updateCustomAccent(old, new) }
    }

    fun setSelectedCustomAccent(argb: Long?) {
        viewModelScope.launch { themeStorage.setSelectedCustomAccent(argb) }
    }

    fun clearCustomAccents() {
        viewModelScope.launch { themeStorage.clearCustomAccents() }
    }

    fun setVisualEffectMode(mode: VisualEffectMode) {
        viewModelScope.launch { themeStorage.setVisualEffectMode(mode) }
    }

    fun setVisualEffectSelection(mode: VisualEffectMode, variant: GlassVariant) {
        viewModelScope.launch {
            themeStorage.setVisualEffectSelection(mode, variant)
        }
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

    fun setSplashQuoteEnabled(enabled: Boolean) {
        viewModelScope.launch { splashQuoteStorage.setEnabled(enabled) }
    }

    /** 写触感档位。落盘同时 Storage 会同步更新 modeState，引擎与设置页一起生效 */
    fun setHapticMode(mode: HapticMode) {
        viewModelScope.launch { hapticStorage.setMode(mode) }
    }

    /**
     * 重读系统总开关与有无马达。设置页进入时与每次回到前台各调一次。
     *
     * 挂在 resume 而不是只读一次：用户看到「系统已关闭触感」这句话之后，多半就会去系统设置
     * 把它打开再切回来 —— 那正是这句话必须消失的时刻。
     *
     * 两次读都过 binder，所以丢到 IO；读失败时 [systemHapticFeedbackEnabled] 与
     * `deviceHasVibrator` 各自兜底（按可用 / 无马达算），这里不再补 try。
     */
    fun refreshHapticSystemState() {
        viewModelScope.launch {
            _hapticSystemState.value = withContext(Dispatchers.IO) {
                HapticSystemState(
                    systemHapticEnabled = systemHapticFeedbackEnabled(context),
                    hasVibrator = HapticCapabilities.deviceHasVibrator(context),
                )
            }
        }
    }

    fun setMeshPreset(preset: String) {
        viewModelScope.launch { themeStorage.setMeshPreset(preset) }
    }

    fun setMeshEnabled(enabled: Boolean) {
        viewModelScope.launch { themeStorage.setMeshEnabled(enabled) }
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

    fun setCrashLogEnabled(enabled: Boolean) {
        viewModelScope.launch {
            crashLogStorage.setEnabled(enabled)
            // 开启且有待传日志：立即触发上传（成功/失败反馈由主界面 CrashReportDialogHost 统一处理，避免双提示）
            if (enabled && crashLogUploader.hasPendingLogs()) {
                crashLogUploader.uploadPendingLogs()
            }
        }
    }

    /** 「锐评我的看单」数据上传开关（关闭后功能项保留，点击时引导回本页开启） */
    fun setAiTasteEnabled(enabled: Boolean) {
        viewModelScope.launch { aiTasteStorage.setTasteUploadEnabled(enabled) }
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

                val content = DataExportImport.exportToJsonWithSignature(
                    watchlistMovies = watchlistMovies.map { it.toExportItem() },
                    watchlistShows = watchlistShows.map { it.toExportItem() },
                    historyMovies = historyMovies.map { it.toExportItem() },
                    historyShows = historyShows.map { it.toExportItem() }
                )

                withContext(Dispatchers.IO) {
                    val outputStream = context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Cannot open output stream for URI: $uri")
                    outputStream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                }

                _exportImportState.value = _exportImportState.value.copy(
                    isExporting = false,
                    message = ExportMessage.Plain(R.string.snackbar_export_success, outcome = HapticOutcome.SUCCESS)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    isExporting = false,
                    message = ExportMessage.Plain(R.string.snackbar_export_failed, outcome = HapticOutcome.FAILURE)
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
                val csvContent = withContext(Dispatchers.IO) { readUriContent(uri) }
                // 严格校验:根据 ParseResult 分支处理
                val items = when (val result = DataExportImport.parseImdbCsv(csvContent)) {
                    is ParseResult.Success -> result.items
                    is ParseResult.MissingRequiredColumns -> {
                        _exportImportState.value = _exportImportState.value.copy(
                            isImporting = false,
                            message = ExportMessage.Plain(R.string.error_not_imdb_csv, outcome = HapticOutcome.FAILURE)
                        )
                        return@launch
                    }
                    is ParseResult.Empty -> {
                        _exportImportState.value = _exportImportState.value.copy(
                            isImporting = false,
                            message = ExportMessage.Plain(R.string.error_empty_csv, outcome = HapticOutcome.FAILURE)
                        )
                        return@launch
                    }
                    is ParseResult.Error -> {
                        _exportImportState.value = _exportImportState.value.copy(
                            isImporting = false,
                            message = ExportMessage.Formatted(R.string.error_parse_failed, listOf(result.message), outcome = HapticOutcome.FAILURE)
                        )
                        return@launch
                    }
                }
                if (items.isEmpty()) {
                    _exportImportState.value = _exportImportState.value.copy(
                        isImporting = false,
                        message = ExportMessage.Plain(R.string.snackbar_import_no_data, outcome = HapticOutcome.FAILURE)
                    )
                    return@launch
                }

                var success = 0
                var failed = 0
                val total = items.size

                items.forEachIndexed { index, item ->
                    _exportImportState.value = _exportImportState.value.copy(
                        syncProgress = ExportMessage.Formatted(
                            R.string.snackbar_sync_progress,
                            listOf(index + 1, total, item.title),
                            // 逐条刷新，一条一记就成了连震；进度不是结果
                            outcome = null,
                        )
                    )
                    try {
                        val mediaType = when (item.mediaType) {
                            "show" -> MediaType.SHOW
                            "movie" -> MediaType.MOVIE
                            // parseImdbCsv 已过滤 podcastSeries 等非影视类型,null 视为未知跳过避免误识别
                            else -> null
                        }
                        if (mediaType == null) {
                            failed++
                            return@forEachIndexed
                        }
                        val searchResult = when (mediaType) {
                            MediaType.MOVIE -> traktRepository.searchMovies(item.title, page = 1, limit = 1)
                            MediaType.SHOW -> traktRepository.searchShows(item.title, page = 1, limit = 1)
                            else -> Result.failure(Exception("Unsupported media type: $mediaType"))
                        }
                        val traktId = searchResult.getOrNull()?.first?.firstOrNull()?.let { result ->
                            when (mediaType) {
                                MediaType.MOVIE -> result.movie?.ids?.trakt
                                MediaType.SHOW -> result.show?.ids?.trakt
                                else -> null
                            }
                        }
                        if (traktId != null && traktId > 0) {
                            // addToWatchlist 内部已调用 addToWatchlistCache,
                            // WatchlistWatchedIds 全局缓存会同步新增该 traktId,
                            // 切回 Watchlist 页 MovieCard 的想看标记可立即命中缓存。
                            // 注意:IMDb CSV 的 Created 列已解析到 item.watchedAt,但当前 addToWatchlist
                            // API 不支持传入标记时间,IMDb 的标记时间会丢失。如需保留时间应改用
                            // addToHistory 或带时间参数的 API(暂未实现,有意丢弃)。
                            traktRepository.addToWatchlist(traktId, mediaType)
                            success++
                        } else {
                            failed++
                        }
                    } catch (e: CancellationException) {
                        throw e
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
                    message = ExportMessage.Formatted(
                        R.string.snackbar_import_done_imdb,
                        listOf(success, failed),
                        // 部分成功算失败：还有条目需要用户再来一次
                        outcome = if (failed > 0) HapticOutcome.FAILURE else HapticOutcome.SUCCESS,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    isImporting = false,
                    message = ExportMessage.Formatted(R.string.error_parse_failed, listOf(e.message ?: ""), outcome = HapticOutcome.FAILURE)
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
                statisticsSnapshotStore.clear()
                refreshCacheInfo()
                _exportImportState.value = _exportImportState.value.copy(
                    message = ExportMessage.Plain(R.string.snackbar_cache_cleared, outcome = HapticOutcome.SUCCESS)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    message = ExportMessage.Plain(R.string.snackbar_cache_clear_failed, outcome = HapticOutcome.FAILURE)
                )
            }
        }
    }

    // ========== 缓存管理（分项显示与清除） ==========

    /** 缓存类目 */
    /**
     * 可单独清除的缓存类目。
     *
     * 只保留界面上真正渲染的三项。原来还有 ID_MAPPING 与 DATABASE 两个枚举值，界面从没有
     * 对应入口，但确认弹窗仍为它们准备了标题与后果文案，属于不可达的死分支。
     * 需要整体清掉时走 clearCache()。
     */
    enum class CacheCategory {
        IMAGE,       // 图片缓存（Coil 磁盘）
        MEDIA_DATA,  // 影视数据缓存（TMDB 详情/演职员 + Trakt 趋势/列表 + 豆瓣热榜）
        HTTP         // 网络请求缓存（OkHttp 响应）
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

    // ========== 图片下载流量统计 ==========
    val imageTraffic: StateFlow<ImageTrafficStats> = imageTrafficStorage.stats

    fun clearImageTraffic() {
        imageTrafficStorage.clear()
        // 成功反馈走与清缓存同一条 Snackbar 通道，之前重置后界面只有数字变化
        _exportImportState.value = _exportImportState.value.copy(
            message = ExportMessage.Plain(R.string.snackbar_image_traffic_reset, outcome = HapticOutcome.SUCCESS)
        )
    }

    /** 按类目清除缓存 */
    fun clearCategory(category: CacheCategory) {
        viewModelScope.launch {
            try {
                when (category) {
                    CacheCategory.IMAGE -> offlineCacheManager.clearImageCache()
                    CacheCategory.HTTP -> offlineCacheManager.clearHttpCache()
                    CacheCategory.MEDIA_DATA -> {
                        // 清除影视数据类的所有 PersistentTtlCache（按 key 前缀删 DataStore）
                        tmdbRepository.mediaDataCaches.forEach { it.clearAll() }
                        traktRepository.mediaDataCaches.forEach { it.clearAll() }
                        doubanHotCache.clearAll()
                        // 统计页快照也是影视数据，单独一个 DataStore 文件，不在 PersistentTtlCache 列表里
                        statisticsSnapshotStore.clear()
                    }
                }
                refreshCacheInfo()
                _exportImportState.value = _exportImportState.value.copy(
                    message = ExportMessage.Plain(R.string.snackbar_cache_cleared, outcome = HapticOutcome.SUCCESS)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    message = ExportMessage.Plain(R.string.snackbar_cache_clear_failed, outcome = HapticOutcome.FAILURE)
                )
            }
        }
    }

    // ========== 用户资料 ==========

    val userProfile: StateFlow<TraktUserProfileResponse?> =
        traktRepository.userProfile

    // ========== 豆瓣登录态（账户区展示用） ==========
    val doubanLoggedIn: StateFlow<Boolean> = doubanAuthStorage.isLoggedIn

    /**
     * Trakt 连接失效：本地还留着上次登录成功的用户资料，但当前连接检查判定为断开。
     * 用于让账号卡区分「从未登录」与「登录过但连接已失效」。
     */
    val traktConnectionInvalid: StateFlow<Boolean> = combine(
        sessionModeManager.traktConnected,
        traktRepository.userProfile
    ) { connected, profile -> !connected && profile != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 豆瓣 Cookie 失效：登录态还在，但豆瓣已不认这份 Cookie */
    val doubanCookieInvalid: StateFlow<Boolean> = doubanAuthStorage.cookieInvalid

    // 豆瓣用户资料（头像/昵称）：从加密存储恢复，登录后异步抓取
    val doubanProfile: StateFlow<com.tracktosearch.data.local.DoubanUserProfile?> = doubanAuthStorage.doubanProfile

    // ========== 增量同步冷却期状态(跨设备同步显示) ==========
    private val _cooldownStatus = MutableStateFlow<CooldownStatus?>(null)
    val cooldownStatus: StateFlow<CooldownStatus?> = _cooldownStatus.asStateFlow()

    // ========== 已同步条目数量(用于「重新同步豆瓣」模式选择对话框展示"已同步 N 项") ==========
    private val _syncedCount = MutableStateFlow(0)
    val syncedCount: StateFlow<Int> = _syncedCount.asStateFlow()

    /**
     * 刷新已同步条目数量(用户点击「重新同步豆瓣」弹出模式选择对话框前调用)。
     */
    fun refreshSyncedCount() {
        viewModelScope.launch {
            if (!doubanAuthStorage.isLoggedIn.value) {
                _syncedCount.value = 0
                return@launch
            }
            _syncedCount.value = doubanSyncManager.getSyncedCount()
        }
    }

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

    // ===== 状态一致性检查 =====

    /** 暴露检查进度 StateFlow（供 UI 实时观察） */
    val checkProgress: StateFlow<ConsistencyCheckResult> = statusConsistencyChecker.checkProgress

    /** 豆瓣同步是否正在运行（同步进行中隐藏手动检查入口，因同步后自动检查） */
    val isDoubanSyncRunning: StateFlow<Boolean> = doubanSyncManager.progress
        .map { it.isRunning }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    /** 是否处于豆瓣独立模式（未连 Trakt + 已登录豆瓣）。豆瓣模式下隐藏手动一致性检查入口 */
    val isDoubanMode: StateFlow<Boolean> = sessionModeManager.isDoubanMode
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    /** 检查是否在运行中 */
    fun isCheckRunning(): Boolean = statusConsistencyChecker.isRunning()

    /** 获取上次状态一致性检查时间戳（包括同步后自动检查和手动检查），0=从未检查 */
    suspend fun getLastConsistencyCheckAt(): Long = lastConsistencyCheckStorage.getLastCheckAt()

    /** 手动触发：爬豆瓣列表拿最新状态后对比（分钟级，进度通过 checkProgress 暴露） */
    fun startManualConsistencyCheck(): Boolean =
        statusConsistencyChecker.checkAndUnifyWithCrawl()

    /** 取消正在进行的检查 */
    fun cancelConsistencyCheck() {
        statusConsistencyChecker.cancel()
    }

    /** 用户手动关闭一致性检查结果弹窗后调用：清除进度，避免旧 isComplete 残留 */
    fun clearConsistencyCheckResult() {
        statusConsistencyChecker.resetProgress()
    }

    /** 清除豆瓣凭据（退出登录）。先取消进行中的同步/批量移除任务，再清理本地数据 */
    fun clearDoubanCredentials() {
        // 先取消进行中的豆瓣同步和批量移除任务（它们在 Application scope 跑，不依赖 ViewModel 生命周期）
        // 避免登出后任务继续用已清除的 cookie 跑导致 401 失败污染进度流
        doubanSyncManager.cancel()
        doubanBatchRemovalManager.cancel()
        doubanAuthStorage.clearCredentials()
        viewModelScope.launch {
            // 清理本地豆瓣同步标记表（douban_synced_items），避免换账号后旧数据残留
            runCatching { doubanSyncedItemDao.clearAll() }
            // 清除失败数据云端同步版本记录（与账号绑定，换账号后不应残留导致误判）
            runCatching { cloudFailureSyncMetaStorage.clear() }
        }
    }

    /** 获取本地豆瓣标记条数（退出登录二次确认弹窗展示用） */
    suspend fun getDoubanSyncedItemCount(): Int = doubanSyncedItemDao.count()

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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { null }
            if (profile != null) {
                doubanAuthStorage.saveUserProfile(profile.nickname, profile.avatarUrl)
            }
        }
    }

    fun loadUserProfile() {
        viewModelScope.launch {
            traktRepository.getUserProfile()
        }
    }

    fun clearUserProfile() {
        viewModelScope.launch {
            traktRepository.clearTraktAccountCaches()
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
                // 按 PersistentTtlCache.getSizeBytes() 比例拆分影视数据 vs ID 映射。
                // 统计页快照在独立 DataStore 文件，不计入 dataStoreTotal，单独相加。
                val mediaDataBytes = (
                    tmdbRepository.mediaDataCaches.sumOf { it.getSizeBytes() } +
                        traktRepository.mediaDataCaches.sumOf { it.getSizeBytes() } +
                        doubanHotCache.getSizeBytes()
                    ).coerceAtMost(dataStoreTotal) + statisticsSnapshotStore.sizeBytes()
                val idMappingBytes = (dataStoreTotal - mediaDataBytes).coerceAtLeast(0L)
                _cacheBreakdown.value = CacheBreakdown(
                    imageBytes = offlineCacheManager.getImageCacheSizeBytes(),
                    mediaDataBytes = mediaDataBytes,
                    idMappingBytes = idMappingBytes,
                    httpBytes = offlineCacheManager.getHttpCacheSizeBytes(),
                    databaseBytes = offlineCacheManager.getDatabaseSizeBytes()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _cacheBreakdown.value = CacheBreakdown()
            }
        }
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
            } catch (e: CancellationException) {
                throw e
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
                        message = ExportMessage.Plain(R.string.snackbar_already_latest, outcome = HapticOutcome.SUCCESS)
                    )
                } else {
                    _latestVersion.value = BuildConfig.VERSION_NAME
                    _exportImportState.value = _exportImportState.value.copy(
                        message = ExportMessage.Plain(R.string.snackbar_check_update_failed, outcome = HapticOutcome.FAILURE)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _exportImportState.value = _exportImportState.value.copy(
                    message = ExportMessage.Plain(R.string.snackbar_check_update_failed, outcome = HapticOutcome.FAILURE)
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

    private suspend fun readUriContent(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw Exception(context.getString(R.string.error_read_file_failed))
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
