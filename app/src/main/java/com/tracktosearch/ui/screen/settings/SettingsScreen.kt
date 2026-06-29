package com.tracktosearch.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Build
import com.tracktosearch.BuildConfig
import com.tracktosearch.R
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.ui.component.StickyHeaderChangelogContent
import com.tracktosearch.ui.component.UpdateDialog
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import java.text.SimpleDateFormat
import java.util.Locale
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun SettingsScreen(
    onLogout: () -> Unit = {},
    isLoggedIn: Boolean = true,
    onHelpClick: () -> Unit = {},
    onRestartOnboarding: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val currentTheme by viewModel.themeMode.collectAsStateWithLifecycle()
    val currentAccent by viewModel.accentColor.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.language.collectAsStateWithLifecycle()
    val currentDefaultTab by viewModel.defaultTab.collectAsStateWithLifecycle()
    val pansouEnabled by viewModel.pansouEnabled.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refreshCacheInfo()
        onPauseOrDispose { }
    }
    val panhubEnabled by viewModel.panhubEnabled.collectAsStateWithLifecycle()
    val zresoEnabled by viewModel.zresoEnabled.collectAsStateWithLifecycle()
    val exportImportState by viewModel.exportImportState.collectAsStateWithLifecycle()
    val customSources by viewModel.customSources.collectAsStateWithLifecycle()
    val testResults by viewModel.testResults.collectAsStateWithLifecycle()
    val panHubConfig by viewModel.panHubConfig.collectAsStateWithLifecycle()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAccentColorDialog by remember { mutableStateOf(false) }
    var showPanHubConfigDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showDefaultTabDialog by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showDiscoverSectionsDialog by remember { mutableStateOf(false) }
    var showDetailSectionsDialog by remember { mutableStateOf(false) }
    var showEditCustomSource by remember { mutableStateOf<CustomSearchSource?>(null) }
    var showDeleteCustomSource by remember { mutableStateOf<CustomSearchSource?>(null) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(exportImportState.message) {
        exportImportState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportData(it, ExportFormat.JSON) }
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let { viewModel.exportData(it, ExportFormat.CSV) }
    }

    val importLetterboxdLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importFromLetterboxd(it) }
    }

    val importImdbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importFromImdb(it) }
    }

    val importJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importFromJson(it) }
    }

    val openUrl: (String) -> Unit = { url ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    val showUpdateDialog by viewModel.showUpdateDialog.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()

    val settingsHazeState = remember { HazeState() }
    val savedScrollIndex = rememberSaveable { mutableIntStateOf(0) }
    val savedScrollOffset = rememberSaveable { mutableIntStateOf(0) }
    val settingsListState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollIndex.intValue,
        initialFirstVisibleItemScrollOffset = savedScrollOffset.intValue
    )
    val scrollToTopProvider = LocalScrollToTopProvider.current
    val settingsCoroutineScope = rememberCoroutineScope()
    LaunchedEffect(settingsListState) {
        snapshotFlow {
            settingsListState.firstVisibleItemIndex to settingsListState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            savedScrollIndex.intValue = index
            savedScrollOffset.intValue = offset
        }
    }
    DisposableEffect(Unit) {
        scrollToTopProvider.register {
            settingsCoroutineScope.launch {
                settingsListState.animateScrollToItem(0)
            }
        }
        onDispose {
            scrollToTopProvider.unregister()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = 80.dp) // 避免被底部导航遮挡
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            LazyColumn(
                state = settingsListState,
                modifier = modifier
                    .fillMaxSize()
                    .hazeSource(state = settingsHazeState),
                contentPadding = PaddingValues(
                    top = 65.dp + statusBarHeight,
                    bottom = 80.dp
                )
        ) {
            // 主题设置
            item { SettingsSectionHeader(stringResource(R.string.settings_appearance)) }
            item {
                val themeName = when (currentTheme) {
                    ThemeStorage.MODE_DARK -> stringResource(R.string.theme_dark)
                    ThemeStorage.MODE_LIGHT -> stringResource(R.string.theme_light)
                    else -> stringResource(R.string.theme_system)
                }
                SettingsItem(
                    icon = Icons.Default.DarkMode,
                    title = stringResource(R.string.settings_theme),
                    subtitle = themeName,
                    onClick = { showThemeDialog = true }
                )
            }
            item {
                val accentName = currentAccent?.label ?: stringResource(R.string.settings_accent_dynamic)
                SettingsItem(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.settings_accent_color),
                    subtitle = accentName,
                    onClick = { showAccentColorDialog = true }
                )
            }
            item {
                val languageName = when (currentLanguage) {
                    LanguageStorage.LANGUAGE_CHINESE -> stringResource(R.string.language_chinese)
                    LanguageStorage.LANGUAGE_ENGLISH -> stringResource(R.string.language_english)
                    LanguageStorage.LANGUAGE_JAPANESE -> stringResource(R.string.language_japanese)
                    LanguageStorage.LANGUAGE_KOREAN -> stringResource(R.string.language_korean)
                    else -> stringResource(R.string.language_system)
                }
                SettingsItem(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.settings_language),
                    subtitle = languageName,
                    onClick = { showLanguageDialog = true }
                )
            }
            // 默认启动页
            item {
                val tabName = when (currentDefaultTab) {
                    0 -> stringResource(R.string.tab_search)
                    1 -> stringResource(R.string.tab_discover)
                    else -> stringResource(R.string.tab_me)
                }
                SettingsItem(
                    icon = Icons.Default.Home,
                    title = stringResource(R.string.settings_default_tab),
                    subtitle = tabName,
                    onClick = { showDefaultTabDialog = true }
                )
            }

            // 搜索源管理
            item { SettingsSectionHeader(stringResource(R.string.settings_search)) }
            item { SearchSourceItem("PanSou", pansouEnabled) { viewModel.setPansouEnabled(it) } }
            item { PanHubSettingsItem(
                enabled = panhubEnabled,
                onEnabledChange = { viewModel.setPanhubEnabled(it) },
                onConfigClick = { showPanHubConfigDialog = true }
            ) }
            item { SearchSourceItem("Zreso", zresoEnabled) { viewModel.setZresoEnabled(it) } }

            // 自定义搜索源
            items(customSources.size) { index ->
                val source = customSources[index]
                val testResult = testResults[source.id]
                CustomSearchSourceItem(
                    source = source,
                    testResult = testResult,
                    onToggle = { viewModel.setCustomSourceEnabled(source.id, it) },
                    onEdit = { showEditCustomSource = source },
                    onDelete = { showDeleteCustomSource = source },
                    onTest = { viewModel.testCustomSource(source) }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Add,
                    title = stringResource(R.string.settings_add_custom_source),
                    subtitle = stringResource(R.string.settings_add_custom_source_desc),
                    onClick = { showEditCustomSource = CustomSearchSource(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "",
                        baseUrl = "",
                        apiPath = "api/search",
                        keywordParam = "kw",
                        cloudTypesParam = "cloud_types",
                        cloudTypesValue = "quark,baidu,aliyun,xunlei,uc,115",
                        srcParam = "src",
                        srcValue = "all"
                    ) }
                )
            }

            // 通知设置（仅登录用户可见，通知依赖 Trakt 想看列表）
            if (isLoggedIn) {
                item { SettingsSectionHeader(stringResource(R.string.settings_notification)) }
                item {
                    val notificationEnabled by viewModel.notificationEnabled.collectAsStateWithLifecycle()
                    val context = LocalContext.current
                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (!granted) {
                            viewModel.setNotificationEnabled(false)
                        }
                    }
                    SwitchSettingsItem(
                        title = stringResource(R.string.settings_notification_enabled),
                        subtitle = stringResource(R.string.settings_notification_enabled_desc),
                        checked = notificationEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                                viewModel.setNotificationEnabled(true)
                            } else {
                                viewModel.setNotificationEnabled(false)
                            }
                        }
                    )
                }
                item {
                    val releaseEnabled by viewModel.releaseReminderEnabled.collectAsStateWithLifecycle()
                    val notificationEnabled by viewModel.notificationEnabled.collectAsStateWithLifecycle()
                    SwitchSettingsItem(
                        title = stringResource(R.string.settings_notification_release),
                        subtitle = stringResource(R.string.settings_notification_release_desc),
                        checked = releaseEnabled,
                        enabled = notificationEnabled,
                        onCheckedChange = { viewModel.setReleaseReminderEnabled(it) }
                    )
                }
                item {
                    val newSeasonEnabled by viewModel.newSeasonReminderEnabled.collectAsStateWithLifecycle()
                    val notificationEnabled by viewModel.notificationEnabled.collectAsStateWithLifecycle()
                    SwitchSettingsItem(
                        title = stringResource(R.string.settings_notification_new_season),
                        subtitle = stringResource(R.string.settings_notification_new_season_desc),
                        checked = newSeasonEnabled,
                        enabled = notificationEnabled,
                        onCheckedChange = { viewModel.setNewSeasonReminderEnabled(it) }
                    )
                }
            }

            // 发现页栏目
            item { SettingsSectionHeader(stringResource(R.string.settings_discover_sections)) }
            item {
                SettingsItem(
                    icon = Icons.Default.Explore,
                    title = stringResource(R.string.settings_discover_sections),
                    subtitle = stringResource(R.string.settings_discover_sections_desc),
                    onClick = { showDiscoverSectionsDialog = true }
                )
            }

            // 详情页模块
            item { SettingsSectionHeader(stringResource(R.string.settings_detail_sections)) }
            item {
                SettingsItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.settings_detail_sections),
                    subtitle = stringResource(R.string.settings_detail_sections_desc),
                    onClick = { showDetailSectionsDialog = true }
                )
            }

            // 数据管理（仅登录用户可见，依赖 Trakt API）
            if (isLoggedIn) {
                item { SettingsSectionHeader(stringResource(R.string.settings_data_management)) }
                if (exportImportState.isExporting) {
                    item {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
                item {
                    SettingsItem(
                        icon = Icons.Default.FileUpload,
                        title = stringResource(R.string.settings_export_json),
                        subtitle = stringResource(R.string.settings_export_json_desc),
                        onClick = {
                            if (!exportImportState.isExporting) {
                                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(java.util.Date())
                                exportJsonLauncher.launch("trakt-export-$timestamp.json")
                            }
                        }
                    )
                }
                item {
                    SettingsItem(
                        icon = Icons.Default.FileUpload,
                        title = stringResource(R.string.settings_export_csv),
                        subtitle = stringResource(R.string.settings_export_csv_desc),
                        onClick = {
                            if (!exportImportState.isExporting) {
                                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(java.util.Date())
                                exportCsvLauncher.launch("trakt-export-$timestamp.csv")
                            }
                        }
                    )
                }
                item {
                    SettingsItem(
                        icon = Icons.Default.FileDownload,
                        title = stringResource(R.string.settings_import_letterboxd),
                        subtitle = stringResource(R.string.settings_import_letterboxd_desc),
                        onClick = {
                            if (!exportImportState.isImporting) {
                                importLetterboxdLauncher.launch("text/*")
                            }
                        }
                    )
                }
                item {
                    SettingsItem(
                        icon = Icons.Default.FileDownload,
                        title = stringResource(R.string.settings_import_imdb),
                        subtitle = stringResource(R.string.settings_import_imdb_desc),
                        onClick = {
                            if (!exportImportState.isImporting) {
                                importImdbLauncher.launch("text/*")
                            }
                        }
                    )
                }
                item {
                    SettingsItem(
                        icon = Icons.Default.FileDownload,
                        title = stringResource(R.string.settings_import_json),
                        subtitle = stringResource(R.string.settings_import_json_desc),
                        onClick = {
                            if (!exportImportState.isImporting) {
                                importJsonLauncher.launch("application/json")
                            }
                        }
                    )
                }
                exportImportState.syncProgress?.let { progress ->
                    if (exportImportState.isImporting) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = progress,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 关于
            item { SettingsSectionHeader(stringResource(R.string.settings_about)) }
            item {
                val latestVersion by viewModel.latestVersion.collectAsStateWithLifecycle()
                val isChecking by viewModel.isCheckingUpdate.collectAsStateWithLifecycle()
                val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
                VersionItem(
                    localVersion = BuildConfig.VERSION_NAME,
                    latestVersion = latestVersion,
                    isChecking = isChecking,
                    hasUpdate = updateInfo?.hasUpdate == true,
                    onCheckUpdate = { viewModel.checkUpdate() }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.settings_changelog),
                    subtitle = "",
                    onClick = {
                        viewModel.loadChangelog()
                        showChangelogDialog = true
                    }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.settings_help),
                    subtitle = "",
                    onClick = { onHelpClick() }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    title = stringResource(R.string.settings_restart_onboarding),
                    subtitle = "",
                    onClick = { onRestartOnboarding() }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Code,
                    title = stringResource(R.string.settings_source_repo),
                    subtitle = "yufeng-liang/TrackToSearch-release",
                    onClick = { openUrl("https://gitee.com/yufeng-liang/TrackToSearch-release") }
                )
            }

            // 缓存管理（倒数第二）
            item { SettingsSectionHeader(stringResource(R.string.settings_storage)) }
            item {
                val cacheInfo by viewModel.cacheInfo.collectAsStateWithLifecycle()
                CacheItem(
                    sizeText = cacheInfo,
                    onClear = { showClearCacheDialog = true }
                )
            }

            // 账户（仅登录用户可见）
            if (isLoggedIn) {
                item { SettingsSectionHeader(stringResource(R.string.settings_account)) }
                item {
                    LogoutItem(onClick = { showLogoutDialog = true })
                }
            }
        }
            // Haze模糊渐变TopAppBar（含状态栏）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeEffect(
                        state = settingsHazeState,
                        style = HazeMaterials.thin()
                    )
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.50f))
                    .clickable(enabled = false, onClick = {})
            ) {
                Spacer(modifier = Modifier.statusBarsPadding())
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentTheme = currentTheme,
            onThemeSelected = { viewModel.setThemeMode(it); showThemeDialog = false },
            onDismiss = { showThemeDialog = false }
        )
    }
    if (showAccentColorDialog) {
        AccentColorDialog(
            currentAccent = currentAccent,
            onAccentSelected = { viewModel.setAccentColor(it); showAccentColorDialog = false },
            onDismiss = { showAccentColorDialog = false }
        )
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = currentLanguage,
            onLanguageSelected = {
                viewModel.setLanguage(it)
                showLanguageDialog = false
                // 重建 Activity 以应用语言变更（ComponentActivity 不自动处理 AppCompatDelegate 的 locale 变更）
                (context as? android.app.Activity)?.recreate()
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showDefaultTabDialog) {
        DefaultTabSelectionDialog(
            currentTab = currentDefaultTab,
            onTabSelected = {
                viewModel.setDefaultTab(it)
                showDefaultTabDialog = false
            },
            onDismiss = { showDefaultTabDialog = false }
        )
    }

    if (showChangelogDialog) {
        ChangelogDialog(
            viewModel = viewModel,
            onDismiss = { showChangelogDialog = false }
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.settings_account)) },
            text = { Text(stringResource(R.string.settings_logout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    onLogout()
                }) {
                    Text(stringResource(R.string.settings_logout_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.settings_cache)) },
            text = { Text(stringResource(R.string.settings_clear_cache_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheDialog = false
                    viewModel.clearCache()
                }) {
                    Text(stringResource(R.string.settings_cache_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showDiscoverSectionsDialog) {
        DiscoverSectionsDialog(
            viewModel = viewModel,
            onDismiss = { showDiscoverSectionsDialog = false }
        )
    }

    if (showDetailSectionsDialog) {
        DetailSectionsDialog(
            viewModel = viewModel,
            onDismiss = { showDetailSectionsDialog = false }
        )
    }

    // 检测到新版本时弹出更新弹窗（复用首页 UpdateDialog）
    if (showUpdateDialog && updateInfo != null) {
        UpdateDialog(
            updateInfo = updateInfo!!,
            onDismiss = { viewModel.dismissUpdateDialog() }
        )
    }

    // 自定义搜索源编辑弹窗
    showEditCustomSource?.let { source ->
        CustomSourceEditDialog(
            source = source,
            isNew = customSources.none { it.id == source.id },
            onSave = {
                if (customSources.none { s -> s.id == it.id }) {
                    viewModel.addCustomSource(it)
                } else {
                    viewModel.updateCustomSource(it)
                }
                showEditCustomSource = null
            },
            onDismiss = { showEditCustomSource = null }
        )
    }

    // 删除确认弹窗
    showDeleteCustomSource?.let { source ->
        AlertDialog(
            onDismissRequest = { showDeleteCustomSource = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.settings_delete_source)) },
            text = { Text(stringResource(R.string.settings_delete_source_confirm, source.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCustomSource(source.id)
                    showDeleteCustomSource = null
                }) {
                    Text(stringResource(R.string.cd_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteCustomSource = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // PanHub 配置弹窗
    if (showPanHubConfigDialog) {
        PanHubConfigDialog(
            config = panHubConfig,
            onConcurrencyChange = { viewModel.setPanHubConcurrency(it) },
            onTimeoutMsChange = { viewModel.setPanHubTimeoutMs(it) },
            onEnabledPluginsChange = { viewModel.setPanHubEnabledPlugins(it) },
            onEnabledChannelsChange = { viewModel.setPanHubEnabledChannels(it) },
            onDismiss = { showPanHubConfigDialog = false }
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onClick != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SearchSourceItem(
    name: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = { view.performHaptic(HapticType.CLICK); onCheckedChange(it) }
        )
    }
}

@Composable
fun PanHubSettingsItem(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onConfigClick: () -> Unit
) {
    val view = LocalView.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PanHub",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    view.performHaptic(HapticType.CLICK)
                    onConfigClick()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = stringResource(R.string.settings_panhub_config),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { view.performHaptic(HapticType.CLICK); onEnabledChange(it) }
            )
        }
        Text(
            text = stringResource(R.string.settings_panhub_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
fun SwitchSettingsItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { view.performHaptic(HapticType.CLICK); onCheckedChange(it) }
        )
    }
}

/** 主题选择对话框 */
@Composable
private fun ThemeSelectionDialog(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column {
                ThemeOptionRow(
                    label = stringResource(R.string.theme_system),
                    selected = currentTheme == ThemeStorage.MODE_SYSTEM,
                    onClick = { onThemeSelected(ThemeStorage.MODE_SYSTEM) }
                )
                ThemeOptionRow(
                    label = stringResource(R.string.theme_dark),
                    selected = currentTheme == ThemeStorage.MODE_DARK,
                    onClick = { onThemeSelected(ThemeStorage.MODE_DARK) }
                )
                ThemeOptionRow(
                    label = stringResource(R.string.theme_light),
                    selected = currentTheme == ThemeStorage.MODE_LIGHT,
                    onClick = { onThemeSelected(ThemeStorage.MODE_LIGHT) }
                )
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ThemeOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { view.performHaptic(HapticType.TICK); onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = { view.performHaptic(HapticType.TICK); onClick() }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}

/** 主题色选择对话框 */
@Composable
private fun AccentColorDialog(
    currentAccent: com.tracktosearch.ui.theme.MonetAccent?,
    onAccentSelected: (com.tracktosearch.ui.theme.MonetAccent?) -> Unit,
    onDismiss: () -> Unit
) {
    val view = LocalView.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.settings_accent_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 动态壁纸取色选项（带渐变色块）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { view.performHaptic(HapticType.TICK); onAccentSelected(null) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = currentAccent == null, onClick = { view.performHaptic(HapticType.TICK); onAccentSelected(null) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                                    colors = listOf(
                                        Color(0xFF7B68AE), Color(0xFFE8915A), Color(0xFF5A8F6B),
                                        Color(0xFF6B7FA0), Color(0xFFC4A94D), Color(0xFFD4748A),
                                        Color(0xFF4A7FB5), Color(0xFF7B68AE)
                                    )
                                )
                            )
                            .then(
                                if (currentAccent == null)
                                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentAccent == null) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(stringResource(R.string.settings_accent_dynamic))
                }
                // 莫奈/印象派色块网格
                val accents = com.tracktosearch.ui.theme.MonetAccent.entries
                val rows = accents.chunked(4)
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { accent ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { view.performHaptic(HapticType.TICK); onAccentSelected(accent) }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(accent.light)
                                        .then(
                                            if (currentAccent == accent)
                                                Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                            else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (currentAccent == accent) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(accent.label, fontSize = 11.sp)
                            }
                        }
                        // 补齐空位
                        repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/** 语言选择对话框 */
@Composable
private fun LanguageSelectionDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column {
                LanguageOptionRow(
                    label = stringResource(R.string.language_system),
                    selected = currentLanguage == LanguageStorage.LANGUAGE_SYSTEM,
                    onClick = {
                        onLanguageSelected(LanguageStorage.LANGUAGE_SYSTEM)
                        onDismiss()
                    }
                )
                LanguageOptionRow(
                    label = stringResource(R.string.language_chinese),
                    selected = currentLanguage == LanguageStorage.LANGUAGE_CHINESE,
                    onClick = {
                        onLanguageSelected(LanguageStorage.LANGUAGE_CHINESE)
                        onDismiss()
                    }
                )
                LanguageOptionRow(
                    label = stringResource(R.string.language_english),
                    selected = currentLanguage == LanguageStorage.LANGUAGE_ENGLISH,
                    onClick = {
                        onLanguageSelected(LanguageStorage.LANGUAGE_ENGLISH)
                        onDismiss()
                    }
                )
                LanguageOptionRow(
                    label = stringResource(R.string.language_japanese),
                    selected = currentLanguage == LanguageStorage.LANGUAGE_JAPANESE,
                    onClick = {
                        onLanguageSelected(LanguageStorage.LANGUAGE_JAPANESE)
                        onDismiss()
                    }
                )
                LanguageOptionRow(
                    label = stringResource(R.string.language_korean),
                    selected = currentLanguage == LanguageStorage.LANGUAGE_KOREAN,
                    onClick = {
                        onLanguageSelected(LanguageStorage.LANGUAGE_KOREAN)
                        onDismiss()
                    }
                )
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun LanguageOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { view.performHaptic(HapticType.TICK); onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = { view.performHaptic(HapticType.TICK); onClick() }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}

/** 版本项：显示本地版本 + 最新版本状态 + 检查更新按钮（固定高度防跳动） */
@Composable
fun VersionItem(
    localVersion: String,
    latestVersion: String?,
    isChecking: Boolean,
    hasUpdate: Boolean,
    onCheckUpdate: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_version),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            // 版本副标题：有新版本用主题色显示"最新版本 xxx"，无新版本显示"已是最新版本xxx"
            val subtitle = if (latestVersion != null) {
                if (hasUpdate) {
                    stringResource(R.string.settings_latest_version, latestVersion)
                } else {
                    stringResource(R.string.settings_already_latest, latestVersion)
                }
            } else {
                "v$localVersion"
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (hasUpdate) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 按钮区域固定宽度，防止 loading 态高度变化
        Box(modifier = Modifier.widthIn(min = 80.dp, max = 120.dp).height(36.dp), contentAlignment = Alignment.Center) {
            if (isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                OutlinedButton(
                    onClick = { view.performHaptic(HapticType.CLICK); onCheckUpdate() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text(stringResource(R.string.settings_check_update))
                }
            }
        }
    }
}

/** 缓存项：显示"缓存"标题 + 空间占用大小 + 右侧"清除"按钮 */
@Composable
fun CacheItem(
    sizeText: String,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Storage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_cache),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = sizeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(
            onClick = onClear,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
        ) {
            Text(stringResource(R.string.settings_cache_clear))
        }
    }
}

/** 退出登录项：文字"退出登录" + 右侧红色"登出"按钮 */
@Composable
fun LogoutItem(onClick: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Logout,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = stringResource(R.string.watchlist_logout),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = { view.performHaptic(HapticType.HEAVY_CLICK); onClick() },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
        ) {
            Text(stringResource(R.string.settings_logout_button))
        }
    }
}

/** 更新日志对话框：从仓库获取 md 渲染显示 */
@Composable
fun ChangelogDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    val changelog by viewModel.changelog.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingChangelog.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.settings_changelog)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                when {
                    isLoading && changelog == null -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                    changelog != null && changelog!!.isNotBlank() -> {
                        StickyHeaderChangelogContent(text = changelog!!)
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.settings_no_changelog),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

/** 发现页栏目设置对话框：显示/隐藏开关 + 拖动排序 */
@Composable
fun DiscoverSectionsDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    val sections by viewModel.discoverSections.collectAsStateWithLifecycle()
    var reorderedSections by remember { mutableStateOf(sections) }

    LaunchedEffect(sections) {
        reorderedSections = sections
    }

    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        reorderedSections = reorderedSections.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        viewModel.setSectionOrder(reorderedSections.map { it.id })
        true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Column {
                Text(stringResource(R.string.settings_discover_sections))
                Text(
                    text = stringResource(R.string.settings_discover_sections_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        text = {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                items(reorderedSections, key = { it.id }) { section ->
                    ReorderableItem(
                        state = reorderableLazyListState,
                        key = section.id
                    ) { isDragging ->
                        DiscoverSectionRow(
                            name = getSectionDisplayName(section.id),
                            visible = section.visible,
                            onToggle = { viewModel.setSectionVisible(section.id, it) },
                            dragHandleModifier = Modifier.draggableHandle(),
                            isDragging = isDragging
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

@Composable
private fun DiscoverSectionRow(
    name: String,
    visible: Boolean,
    onToggle: (Boolean) -> Unit,
    dragHandleModifier: Modifier,
    isDragging: Boolean
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(
                color = if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.DragIndicator,
            contentDescription = stringResource(R.string.settings_drag_to_reorder),
            modifier = dragHandleModifier.padding(8.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(
            checked = visible,
            onCheckedChange = { view.performHaptic(HapticType.CLICK); onToggle(it) }
        )
    }
}

/** 栏目 ID 转为显示名称 */
@Composable
private fun getSectionDisplayName(id: String): String {
    return when (id) {
        "douban-movie" -> stringResource(R.string.discover_douban_new_movies)
        "douban-weekly" -> stringResource(R.string.discover_douban_weekly)
        "douban-top250" -> stringResource(R.string.discover_douban_top250)
        "douban-us-box" -> stringResource(R.string.discover_douban_us_box)
        "tmdb-popular" -> stringResource(R.string.discover_trending)
        "tmdb-upcoming" -> stringResource(R.string.discover_upcoming)
        "trakt-trending-movies" -> stringResource(R.string.discover_trakt_trending_movies)
        "trakt-trending-shows" -> stringResource(R.string.discover_trakt_trending_shows)
        "trakt-anticipated" -> stringResource(R.string.discover_trakt_anticipated)
        "trakt-recommendations" -> stringResource(R.string.discover_recommended)
        "trakt-show-recommendations" -> stringResource(R.string.discover_trakt_recommendations_shows)
        "trakt-lists" -> stringResource(R.string.discover_trending_lists)
        else -> id
    }
}

/** 详情页模块设置对话框：仅显示/隐藏开关（无拖动排序） */
@Composable
fun DetailSectionsDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    val sections by viewModel.detailSections.collectAsStateWithLifecycle()
    val view = LocalView.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Text(stringResource(R.string.settings_detail_sections))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                sections.forEach { section ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = getDetailSectionDisplayName(section.id),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = section.visible,
                            onCheckedChange = {
                                view.performHaptic(HapticType.CLICK)
                                viewModel.setDetailSectionVisible(section.id, it)
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

/** 详情页模块 ID 转为显示名称 */
@Composable
private fun getDetailSectionDisplayName(id: String): String {
    return when (id) {
        "cast" -> stringResource(R.string.detail_cast_crew)
        "videos-images" -> stringResource(R.string.detail_videos_section)
        "overview" -> stringResource(R.string.detail_overview_label)
        "my-rating" -> stringResource(R.string.detail_your_rating)
        "comments" -> stringResource(R.string.detail_comments)
        "recommendations" -> stringResource(R.string.detail_recommendations_title)
        else -> id
    }
}

/** 自定义搜索源列表项 */
@Composable
fun CustomSearchSourceItem(
    source: CustomSearchSource,
    testResult: SettingsViewModel.TestResultState?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name.ifBlank { stringResource(R.string.settings_source_unnamed) },
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = source.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onTest, enabled = source.enabled) {
                if (testResult?.isTesting == true) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.settings_source_test), style = MaterialTheme.typography.labelSmall)
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cd_edit), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete), modifier = Modifier.size(20.dp))
            }
            Switch(checked = source.enabled, onCheckedChange = { view.performHaptic(HapticType.CLICK); onToggle(it) })
        }
        // 测试结果
        testResult?.message?.let { msg ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    testResult.success == true -> MaterialTheme.colorScheme.primary
                    testResult.success == false -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** 自定义搜索源编辑弹窗 */
@Composable
fun CustomSourceEditDialog(
    source: CustomSearchSource,
    isNew: Boolean,
    onSave: (CustomSearchSource) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    var name by remember { mutableStateOf(source.name) }
    var baseUrl by remember { mutableStateOf(source.baseUrl) }
    var apiPath by remember { mutableStateOf(source.apiPath) }
    var keywordParam by remember { mutableStateOf(source.keywordParam) }
    var cloudTypesParam by remember { mutableStateOf(source.cloudTypesParam ?: "") }
    var cloudTypesValue by remember { mutableStateOf(source.cloudTypesValue ?: "") }
    var srcParam by remember { mutableStateOf(source.srcParam ?: "") }
    var srcValue by remember { mutableStateOf(source.srcValue ?: "") }
    var parseMode by remember { mutableStateOf(source.parseMode) }
    var listPath by remember { mutableStateOf(source.listPath ?: "") }
    var namePath by remember { mutableStateOf(source.namePath ?: "") }
    var urlPath by remember { mutableStateOf(source.urlPath ?: "") }
    var diskTypePath by remember { mutableStateOf(source.diskTypePath ?: "") }
    var datePath by remember { mutableStateOf(source.datePath ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(if (isNew) stringResource(R.string.settings_add_source) else stringResource(R.string.settings_edit_source)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_source_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.settings_source_base_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiPath,
                    onValueChange = { apiPath = it },
                    label = { Text(stringResource(R.string.settings_source_api_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = keywordParam,
                    onValueChange = { keywordParam = it },
                    label = { Text(stringResource(R.string.settings_source_keyword_param)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cloudTypesParam,
                    onValueChange = { cloudTypesParam = it },
                    label = { Text(stringResource(R.string.settings_source_disk_param)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cloudTypesValue,
                    onValueChange = { cloudTypesValue = it },
                    label = { Text(stringResource(R.string.settings_source_disk_param_value)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = srcParam,
                    onValueChange = { srcParam = it },
                    label = { Text(stringResource(R.string.settings_source_src_param)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = srcValue,
                    onValueChange = { srcValue = it },
                    label = { Text(stringResource(R.string.settings_source_src_value)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.settings_source_parse_mode), style = MaterialTheme.typography.bodySmall)
                val parseModes = listOf(
                    "pansou_template" to stringResource(R.string.settings_source_parse_mode_pansou),
                    "zreso_template" to stringResource(R.string.settings_source_parse_mode_zreso),
                    "custom" to stringResource(R.string.settings_source_parse_mode_custom)
                )
                parseModes.chunked(2).forEach { rowModes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowModes.forEach { (mode, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { view.performHaptic(HapticType.TICK); parseMode = mode }
                        ) {
                            RadioButton(
                                selected = parseMode == mode,
                                onClick = { view.performHaptic(HapticType.TICK); parseMode = mode }
                            )
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                        // 奇数行补齐占位
                        if (rowModes.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                // 自定义 JSONPath 字段
                if (parseMode == "custom") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.settings_source_jsonpath_title), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = listPath,
                        onValueChange = { listPath = it },
                        label = { Text(stringResource(R.string.settings_source_jsonpath_list)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = namePath,
                        onValueChange = { namePath = it },
                        label = { Text(stringResource(R.string.settings_source_jsonpath_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = urlPath,
                        onValueChange = { urlPath = it },
                        label = { Text(stringResource(R.string.settings_source_jsonpath_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = diskTypePath,
                        onValueChange = { diskTypePath = it },
                        label = { Text(stringResource(R.string.settings_source_jsonpath_disk)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = datePath,
                        onValueChange = { datePath = it },
                        label = { Text(stringResource(R.string.settings_source_jsonpath_date)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(source.copy(
                        name = name.trim(),
                        baseUrl = baseUrl.trim(),
                        apiPath = apiPath.trim(),
                        keywordParam = keywordParam.trim(),
                        cloudTypesParam = cloudTypesParam.trim().ifBlank { null },
                        cloudTypesValue = cloudTypesValue.trim().ifBlank { null },
                        srcParam = srcParam.trim().ifBlank { null },
                        srcValue = srcValue.trim().ifBlank { null },
                        parseMode = parseMode,
                        listPath = listPath.trim().ifBlank { null },
                        namePath = namePath.trim().ifBlank { null },
                        urlPath = urlPath.trim().ifBlank { null },
                        diskTypePath = diskTypePath.trim().ifBlank { null },
                        datePath = datePath.trim().ifBlank { null }
                    ))
                },
                enabled = name.isNotBlank() && baseUrl.isNotBlank()
            ) {
                Text(stringResource(R.string.settings_source_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun DefaultTabSelectionDialog(
    currentTab: Int,
    onTabSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val view = LocalView.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.settings_default_tab)) },
        text = {
            Column {
                listOf(
                    0 to stringResource(R.string.tab_search),
                    1 to stringResource(R.string.tab_discover),
                    2 to stringResource(R.string.tab_me)
                ).forEach { (tabIndex, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { view.performHaptic(HapticType.TICK); onTabSelected(tabIndex) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentTab == tabIndex,
                            onClick = { view.performHaptic(HapticType.TICK); onTabSelected(tabIndex) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

