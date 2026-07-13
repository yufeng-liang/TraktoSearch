package com.tracktosearch.ui.screen.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.tracktosearch.BuildConfig
import com.tracktosearch.R
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.repository.ImportResult
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.StickyHeaderChangelogContent
import com.tracktosearch.ui.component.UpdateDialog
import com.tracktosearch.ui.screen.douban.CloudSyncEvent
import com.tracktosearch.ui.screen.douban.DoubanRetryDialog
import com.tracktosearch.ui.screen.douban.DoubanRetryViewModel
import com.tracktosearch.ui.screen.douban.DoubanSyncModePickerDialog
import com.tracktosearch.ui.theme.appSwitchColors
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.performHaptic
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class, kotlinx.coroutines.FlowPreview::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SettingsScreen(
    onLogout: () -> Unit = {},
    isLoggedIn: Boolean = true,
    onHelpClick: () -> Unit = {},
    onRestartOnboarding: () -> Unit = {},
    onDoubanResync: () -> Unit = {},
    onDoubanFailures: () -> Unit = {},
    onNavigateToDoubanLogin: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
    onStatisticsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val currentTheme by viewModel.themeMode.collectAsStateWithLifecycle()
    val currentAccent by viewModel.accentColor.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.language.collectAsStateWithLifecycle()
    val currentDefaultTab by viewModel.defaultTab.collectAsStateWithLifecycle()
    val pansouEnabled by viewModel.pansouEnabled.collectAsStateWithLifecycle()
    // 共享元素转场动画开关:读 AppNavigation 顶层 collect 的值(App 启动即开始收集,
    // 进设置页时已稳定,避免 SettingsViewModel 延迟构造导致的初始 false→true 跳变)
    val sharedTransitionEnabled = LocalSharedTransitionEnabled.current
    // 共享元素转场 scope（帮助与说明入口 → 帮助页标题栏配对）
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

    // 豆瓣重试入口:检测 douban_sync_failures 表是否有数据
    // 「重新同步豆瓣」按钮点击时,有失败则弹重试选择对话框,无失败直接走增量同步
    val doubanRetryViewModel: DoubanRetryViewModel = hiltViewModel()
    val doubanRetryState by doubanRetryViewModel.retryState.collectAsStateWithLifecycle()
    val cloudSyncLoading by doubanRetryViewModel.cloudSyncLoading.collectAsStateWithLifecycle()
    val cloudSyncEvent by doubanRetryViewModel.cloudSyncEvent.collectAsStateWithLifecycle()
    // 豆瓣登录态:「重新同步豆瓣」点击前预检,未登录弹确认框引导登录
    val doubanLoggedIn by viewModel.doubanLoggedIn.collectAsStateWithLifecycle()
    // 增量同步冷却期状态(跨设备同步显示)
    val cooldownStatus by viewModel.cooldownStatus.collectAsStateWithLifecycle()
    val consistencyCheckState by viewModel.checkProgress.collectAsStateWithLifecycle()
    val isDoubanSyncRunning by viewModel.isDoubanSyncRunning.collectAsStateWithLifecycle()
    var showConsistencyDialog by remember { mutableStateOf(false) }
    // 状态一致性检查二次确认弹窗（显示上次检查时间，确认后才执行检查）
    var showConsistencyConfirm by remember { mutableStateOf(false) }
    var lastCheckTimeText by remember { mutableStateOf<String?>(null) }
    var showDoubanLoginPrompt by remember { mutableStateOf(false) }
    var showDoubanRetryDialog by remember { mutableStateOf(false) }
    var showSyncModePicker by remember { mutableStateOf(false) }
    // 冷却期内点击增量同步时的引导对话框
    var showCooldownGuidance by remember { mutableStateOf(false) }
    var pendingCooldownMode by remember { mutableStateOf<com.tracktosearch.data.repository.SyncMode?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // snackbarHostState 在 importFailuresLauncher 之前声明,供 launcher 回调内使用
    val snackbarHostState = remember { SnackbarHostState() }

    val importDoneTemplate = stringResource(R.string.snackbar_import_done_json)
    val invalidFormatMsg = stringResource(R.string.error_invalid_json_format)
    val emptyMsg = stringResource(R.string.error_empty_csv)
    val parseFailedTemplate = stringResource(R.string.error_parse_failed)
    // 豆瓣失败项 JSON 导入 launcher:选文件 → importToRoom → 跳转查看页
    // 用于跨设备查看:A 导出失败数据 → B 导入后在查看页显示
    val importFailuresLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                // 根据 ImportResult 显示不同 Snackbar;成功时刷新统计并跳转查看页
                when (val result = doubanRetryViewModel.doubanFailureExporter.importToRoom(context, uri)) {
                    is ImportResult.Success -> {
                        doubanRetryViewModel.refreshRetryState()
                        snackbarHostState.showSnackbar(
                            importDoneTemplate.format(result.count)
                        )
                        onDoubanFailures()
                    }
                    is ImportResult.InvalidFormat -> {
                        snackbarHostState.showSnackbar(invalidFormatMsg)
                    }
                    is ImportResult.Empty -> {
                        snackbarHostState.showSnackbar(emptyMsg)
                    }
                    is ImportResult.Error -> {
                        snackbarHostState.showSnackbar(
                            parseFailedTemplate.format(result.message)
                        )
                    }
                }
            }
        }
    }

    // 仅首次进入组合时刷新缓存信息和豆瓣失败项统计
    // 不使用 LifecycleResumeEffect：导航到帮助页再返回时生命周期 STARTED→RESUMED 会重新触发，
    // 导致缓存大小等数据更新引起 LazyColumn 项高度微调，滚动位置偏移
    // 用 rememberSaveable 标记确保仅真正首次组合才刷新；重新组合(跨页面导航)后标记已存，避免重复刷新导致列表高度变化
    // 注意:不在此处刷新冷却期状态。冷却期数据(跨设备 lastFullSyncAt)仅在以下时机请求 gitee:
    // 1. 豆瓣登录后拉取云端数据时一并刷新 meta
    // 2. 用户点击「重新同步豆瓣」弹出模式选择对话框前刷新一次
    val cacheRefreshed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!cacheRefreshed) {
            viewModel.refreshCacheInfo()
            doubanRetryViewModel.refreshRetryState()
            // 仅从本地读取冷却期状态(不发网络请求),云端 meta 已在豆瓣登录后刷新
            viewModel.loadCooldownStatusFromLocal()
        }
    }
    // 账户资料加载：从账户 item 内上提，避免 item 滑出/滑入时重复触发网络请求
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            viewModel.loadUserProfile()
            viewModel.loadDoubanProfile()
        }
    }
    // 豆瓣登录态变化时刷新失败项状态:登录后若云端失败数据已下载合并到本地,
    // 返回设置页时 doubanLoggedIn 从 false→true 触发刷新,「查看同步失败项」入口卡片及时显示
    // 冷却期仅从本地读取(豆瓣登录后已刷新云端 meta 到本地)
    LaunchedEffect(doubanLoggedIn) {
        doubanRetryViewModel.refreshRetryState()
        viewModel.loadCooldownStatusFromLocal()
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
    var showDoubanLogoutDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearCategoryDialog by remember { mutableStateOf(false) }
    var pendingClearCategory by remember { mutableStateOf<SettingsViewModel.CacheCategory?>(null) }
    var showDiscoverSectionsDialog by remember { mutableStateOf(false) }
    var showDetailSectionsDialog by remember { mutableStateOf(false) }
    var showEditCustomSource by remember { mutableStateOf<CustomSearchSource?>(null) }
    var showDeleteCustomSource by remember { mutableStateOf<CustomSearchSource?>(null) }

    LaunchedEffect(exportImportState.message) {
        exportImportState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // 云端同步事件 → Snackbar 反馈(一次性,显示后清空)
    // DownloadSuccess 改为弹窗提示(提供跳转失败页按钮)
    var showDownloadSuccessDialog by remember { mutableStateOf(false) }
    var downloadedCount by remember { mutableStateOf(0) }
    val cloudSyncNoLogin = stringResource(R.string.cloud_sync_no_login)
    val cloudSyncNoLocalFailures = stringResource(R.string.cloud_sync_no_local_failures)
    val cloudSyncUploadSuccess = stringResource(R.string.cloud_sync_upload_success)
    val cloudSyncUploadFailed = stringResource(R.string.cloud_sync_upload_failed)
    val cloudSyncDownloadEmpty = stringResource(R.string.cloud_sync_download_empty)
    val cloudSyncDownloadLocalNewer = stringResource(R.string.cloud_sync_download_local_newer)
    val cloudSyncDownloadFailed = stringResource(R.string.cloud_sync_download_failed)
    LaunchedEffect(cloudSyncEvent) {
        val event = cloudSyncEvent ?: return@LaunchedEffect
        when (event) {
            is CloudSyncEvent.DownloadSuccess -> {
                downloadedCount = event.count
                showDownloadSuccessDialog = true
            }
            else -> {
                val msg = when (event) {
                    is CloudSyncEvent.NotLoggedIn -> cloudSyncNoLogin
                    is CloudSyncEvent.NoLocalFailures -> cloudSyncNoLocalFailures
                    is CloudSyncEvent.UploadSuccess -> cloudSyncUploadSuccess
                    is CloudSyncEvent.UploadFailed -> cloudSyncUploadFailed
                    is CloudSyncEvent.CloudEmpty -> cloudSyncDownloadEmpty
                    is CloudSyncEvent.LocalNewer -> cloudSyncDownloadLocalNewer
                    is CloudSyncEvent.DownloadFailed -> cloudSyncDownloadFailed
                    is CloudSyncEvent.DownloadSuccess -> "" // 已上面处理
                }
                snackbarHostState.showSnackbar(msg)
            }
        }
        doubanRetryViewModel.clearCloudSyncEvent()
    }

    // 下载成功弹窗:提示用户可进入查看同步失败项页面查看
    if (showDownloadSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadSuccessDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.dialog_download_success_title)) },
            text = { Text(stringResource(R.string.dialog_download_success_message, downloadedCount)) },
            confirmButton = {
                TextButton(onClick = {
                    showDownloadSuccessDialog = false
                    onDoubanFailures()
                }) {
                    Text(stringResource(R.string.dialog_download_success_view))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadSuccessDialog = false }) {
                    Text(stringResource(R.string.dialog_download_success_dismiss))
                }
            }
        )
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportData(it) }
    }

    val importImdbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importFromImdb(it) }
    }

    val openUrl: (String) -> Unit = { url ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    val showUpdateDialog by viewModel.showUpdateDialog.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsStateWithLifecycle()
    val latestVersion by viewModel.latestVersion.collectAsStateWithLifecycle()

    // LazyListState 由 NavGraph backstack 自然 remember,返回设置页时位置自动恢复,无需手动持久化
    val settingsListState = rememberLazyListState()
    val settingsHazeState = remember { HazeState() }
    val scrollToTopProvider = LocalScrollToTopProvider.current
    val settingsCoroutineScope = rememberCoroutineScope()
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
            // 观看统计（第一位，独占整行卡片，无类目 Header）
            // sharedBounds 与 StatisticsScreen 头部配对,实现卡片↔页面展开/收起转场
            item(key = "statistics_entry") {
                val statisticsEntryModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
                    with(sharedTransitionScope) {
                        Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "settings-statistics-entry"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                } else { Modifier }
                StatisticsCard(
                    modifier = statisticsEntryModifier,
                    onClick = onStatisticsClick
                )
            }

            // 外观
            item(key = "group_appearance") {
                SettingsGroupCard(title = stringResource(R.string.settings_appearance)) {
                    val themeName = when (currentTheme) {
                        ThemeStorage.MODE_DARK -> stringResource(R.string.theme_dark)
                        ThemeStorage.MODE_LIGHT -> stringResource(R.string.theme_light)
                        else -> stringResource(R.string.theme_system)
                    }
                    val accentName = currentAccent?.let { stringResource(it.labelResId) }
                        ?: stringResource(R.string.settings_accent_dynamic)
                    val languageName = when (currentLanguage) {
                        LanguageStorage.LANGUAGE_CHINESE -> stringResource(R.string.language_chinese)
                        LanguageStorage.LANGUAGE_ENGLISH -> stringResource(R.string.language_english)
                        LanguageStorage.LANGUAGE_JAPANESE -> stringResource(R.string.language_japanese)
                        LanguageStorage.LANGUAGE_KOREAN -> stringResource(R.string.language_korean)
                        else -> stringResource(R.string.language_system)
                    }
                    val tabName = when (currentDefaultTab) {
                        0 -> stringResource(R.string.tab_search)
                        1 -> stringResource(R.string.tab_discover)
                        else -> stringResource(R.string.tab_me)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingsCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.DarkMode,
                            title = stringResource(R.string.settings_theme),
                            subtitle = themeName,
                            mergeTitleAndSubtitle = true,
                            onClick = { showThemeDialog = true },
                            containerColor = Color.Transparent
                        )
                        SettingsCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Palette,
                            title = stringResource(R.string.settings_accent_color),
                            subtitle = accentName,
                            mergeTitleAndSubtitle = true,
                            onClick = { showAccentColorDialog = true },
                            containerColor = Color.Transparent
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingsCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Language,
                            title = stringResource(R.string.settings_language),
                            subtitle = languageName,
                            mergeTitleAndSubtitle = true,
                            onClick = { showLanguageDialog = true },
                            containerColor = Color.Transparent
                        )
                        SettingsCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Home,
                            title = stringResource(R.string.settings_default_tab),
                            subtitle = tabName,
                            mergeTitleAndSubtitle = true,
                            onClick = { showDefaultTabDialog = true },
                            containerColor = Color.Transparent
                        )
                    }
                    // 共享元素转场动画开关(默认关闭):关闭时所有页面间转场降级为 NavHost 默认过渡
                    SharedTransitionSwitchCard(
                        enabled = sharedTransitionEnabled,
                        onToggle = { viewModel.setSharedTransitionEnabled(it) },
                        containerColor = Color.Transparent
                    )
                }
            }

            // 搜索源
            item(key = "group_search") {
                SettingsGroupCard(title = stringResource(R.string.settings_search)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SearchSourceCard(
                            modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                            name = "PanSou",
                            checked = pansouEnabled,
                            onCheckedChange = { viewModel.setPansouEnabled(it) },
                            containerColor = Color.Transparent
                        )
                        SearchSourceCard(
                            modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                            name = "Panhub",
                            checked = panhubEnabled,
                            onCheckedChange = { viewModel.setPanhubEnabled(it) },
                            onConfigClick = { showPanHubConfigDialog = true },
                            configContentDescription = stringResource(R.string.settings_panhub_config),
                            containerColor = Color.Transparent
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SearchSourceCard(
                            modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                            name = "Zreso",
                            checked = zresoEnabled,
                            onCheckedChange = { viewModel.setZresoEnabled(it) },
                            containerColor = Color.Transparent
                        )
                        SearchSourceAddCard(
                            modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                            title = stringResource(R.string.settings_add_source),
                            onClick = {
                                showEditCustomSource = CustomSearchSource(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = "",
                                    baseUrl = "",
                                    apiPath = "api/search",
                                    keywordParam = "kw",
                                    cloudTypesParam = "cloud_types",
                                    cloudTypesValue = "quark,baidu,aliyun,xunlei,uc,115",
                                    srcParam = "src",
                                    srcValue = "all"
                                )
                            },
                            containerColor = Color.Transparent
                        )
                    }
                    // 自定义搜索源列表项
                    customSources.forEachIndexed { index, source ->
                        val testResult = testResults[source.id]
                        CustomSearchSourceItem(
                            source = source,
                            testResult = testResult,
                            onToggle = { viewModel.setCustomSourceEnabled(source.id, it) },
                            onEdit = { showEditCustomSource = source },
                            onDelete = { showDeleteCustomSource = source },
                            onTest = { viewModel.testCustomSource(source) }
                        )
                        if (index < customSources.size - 1) GroupDivider()
                    }
                }
            }

            // 通知提醒（仅登录用户可见，通知依赖 Trakt 想看列表）
            if (isLoggedIn) {
                item(key = "group_notification") {
                    SettingsGroupCard(title = stringResource(R.string.settings_notification)) {
                        NotificationItem(
                            viewModel = viewModel,
                            containerColor = Color.Transparent
                        )
                    }
                }
            }

            // 自定义板块（合并原「发现页栏目」+「自定义详情页」）
            item(key = "group_custom") {
                SettingsGroupCard(title = stringResource(R.string.settings_custom_section)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingsCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Explore,
                            title = stringResource(R.string.settings_discover_page),
                            onClick = { showDiscoverSectionsDialog = true },
                            containerColor = Color.Transparent
                        )
                        SettingsCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Movie,
                            title = stringResource(R.string.settings_detail_page),
                            onClick = { showDetailSectionsDialog = true },
                            containerColor = Color.Transparent
                        )
                    }
                }
            }

            // 数据管理（仅登录用户可见，依赖 Trakt API）
            if (isLoggedIn) {
                item(key = "group_data") {
                    SettingsGroupCard(title = stringResource(R.string.settings_data_management)) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (exportImportState.isExporting) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }

                            val dataItems = buildList<@Composable () -> Unit> {
                                add {
                                    DataFlowGridItem(
                                        onExport = {
                                            if (!exportImportState.isExporting) {
                                                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(java.util.Date())
                                                exportJsonLauncher.launch("trakt-export-$timestamp.json")
                                            }
                                        },
                                        onImportImdb = {
                                            if (!exportImportState.isImporting) {
                                                importImdbLauncher.launch("text/*")
                                            }
                                        },
                                        onUploadCloud = { if (!cloudSyncLoading) doubanRetryViewModel.uploadToCloud() },
                                        onDownloadCloud = { if (!cloudSyncLoading) doubanRetryViewModel.downloadFromCloud() },
                                        containerColor = Color.Transparent
                                    )
                                }
                                add {
                                    SettingsItemCard(
                                        icon = Icons.Rounded.Sync,
                                        title = stringResource(R.string.settings_douban_resync),
                                        subtitle = stringResource(R.string.settings_douban_resync_desc),
                                        onClick = {
                                            // 重新同步:先检查豆瓣登录态,未登录弹确认框引导登录
                                            if (doubanLoggedIn) {
                                                // 弹出模式选择前刷新冷却期状态(确保跨设备 lastFullSyncAt 最新)
                                                scope.launch { viewModel.refreshCooldownStatus() }
                                                showSyncModePicker = true
                                            } else {
                                                showDoubanLoginPrompt = true
                                            }
                                        },
                                        trailing = {
                                            // 冷却期状态标签(右对齐):从未同步不显示,冷却中显示剩余天数,可同步显示可同步
                                            cooldownStatus?.let { status ->
                                                if (!status.neverSynced) {
                                                    Surface(
                                                        shape = MaterialTheme.shapes.small,
                                                        color = if (status.isCoolingDown)
                                                            MaterialTheme.colorScheme.tertiaryContainer
                                                        else MaterialTheme.colorScheme.secondaryContainer
                                                    ) {
                                                        Text(
                                                            text = if (status.isCoolingDown)
                                                                stringResource(R.string.cooldown_remaining_days, status.remainingDays)
                                                            else stringResource(R.string.cooldown_available),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = if (status.isCoolingDown)
                                                                MaterialTheme.colorScheme.onTertiaryContainer
                                                            else MaterialTheme.colorScheme.onSecondaryContainer,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        containerColor = Color.Transparent
                                    )
                                }
                                if (doubanRetryState.hasFailures) {
                                    add {
                                        SettingsItemCard(
                                            icon = Icons.Rounded.Replay,
                                            title = stringResource(R.string.settings_douban_retry_failures),
                                            subtitle = stringResource(R.string.douban_retry_subtitle, doubanRetryState.totalFailures),
                                            onClick = {
                                                // 重试失败项:弹重试选择对话框
                                                showDoubanRetryDialog = true
                                            },
                                            containerColor = Color.Transparent
                                        )
                                    }
                                    add {
                                        SettingsItemCard(
                                            icon = Icons.Rounded.BrokenImage,
                                            title = stringResource(R.string.settings_douban_view_failures),
                                            subtitle = stringResource(
                                                R.string.settings_douban_view_failures_desc,
                                                doubanRetryState.totalFailures
                                            ),
                                            onClick = { onDoubanFailures() },
                                            containerColor = Color.Transparent
                                        )
                                    }
                                }
                                // 豆瓣同步进行中时隐藏手动检查入口（同步后自动检查）
                                if (!isDoubanSyncRunning) {
                                    add {
                                        SettingsItemCard(
                                            icon = Icons.Rounded.SyncAlt,
                                            title = stringResource(R.string.settings_douban_status_consistency),
                                            subtitle = consistencyCheckState?.let { result ->
                                                if (result.isComplete) {
                                                    stringResource(
                                                        R.string.settings_douban_status_consistency_done,
                                                        result.conflictsFound,
                                                        result.traktUpdated,
                                                        result.doubanUpdated
                                                    )
                                                } else if (result.isRunning) {
                                                    stringResource(R.string.settings_douban_status_consistency_checking)
                                                } else {
                                                    stringResource(R.string.settings_douban_status_consistency_desc)
                                                }
                                            } ?: stringResource(R.string.settings_douban_status_consistency_desc),
                                            onClick = {
                                                // 检查已运行时直接弹窗恢复进度；未运行时先弹二次确认
                                                if (viewModel.isCheckRunning()) {
                                                    showConsistencyDialog = true
                                                } else {
                                                    // 异步读取上次检查时间并格式化，然后弹二次确认
                                                    scope.launch {
                                                        val lastMs = viewModel.getLastConsistencyCheckAt()
                                                        lastCheckTimeText = formatLastCheckTime(lastMs, context)
                                                        showConsistencyConfirm = true
                                                    }
                                                }
                                            },
                                            containerColor = Color.Transparent
                                        )
                                    }
                                }
                            }
                            dataItems.forEachIndexed { index, item ->
                                item()
                                if (index < dataItems.size - 1) GroupDivider()
                            }

                            // 云端同步进行中:显示进度条
                            if (cloudSyncLoading) {
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
                                        text = stringResource(R.string.cloud_sync_in_progress),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            exportImportState.syncProgress?.let { progress ->
                                if (exportImportState.isImporting) {
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
                }
            }

            // 账户：已登录用户显示 Trakt+豆瓣账号信息；访客显示"登录 Trakt"入口
            item(key = "group_account") {
                SettingsGroupCard(title = stringResource(R.string.settings_account)) {
                    if (isLoggedIn) {
                        AccountItem(
                            viewModel = viewModel,
                            onTraktLogout = { showLogoutDialog = true },
                            onDoubanLogin = { onNavigateToDoubanLogin() },
                            onDoubanLogout = { showDoubanLogoutDialog = true },
                            containerColor = Color.Transparent
                        )
                    } else {
                        // 访客模式：显示登录 Trakt 入口（豆瓣导入需先登录 Trakt）
                        GuestLoginItem(
                            onNavigateToLogin = onNavigateToLogin,
                            containerColor = Color.Transparent
                        )
                    }
                }
            }

            // 缓存管理（倒数第二）：概览行 + 点击展开 5 个类目
            item(key = "group_storage") {
                SettingsGroupCard(title = stringResource(R.string.settings_storage)) {
                    CacheManagementSectionItem(
                        viewModel = viewModel,
                        onClearCategory = { category ->
                            pendingClearCategory = category
                            showClearCategoryDialog = true
                        },
                        onClearAll = { showClearCacheDialog = true },
                        containerColor = Color.Transparent
                    )
                }
            }

            // 关于
            item(key = "group_about") {
                SettingsGroupCard(title = stringResource(R.string.settings_about)) {
                    AboutItem(
                        hasUpdate = updateInfo?.hasUpdate == true,
                        latestVersion = latestVersion,
                        isCheckingUpdate = isCheckingUpdate,
                        onVersionClick = { viewModel.checkUpdate() },
                        onChangelogClick = {
                            viewModel.loadChangelog()
                            showChangelogDialog = true
                        },
                        onHelpClick = onHelpClick,
                        onRestartOnboarding = onRestartOnboarding,
                        containerColor = Color.Transparent
                    )
                    GroupDivider()
                    SettingsItemCard(
                        icon = Icons.Rounded.Code,
                        title = stringResource(R.string.settings_source_repo),
                        subtitle = "yufeng-liang/TrackToSearch-release",
                        onClick = { openUrl("https://gitee.com/yufeng-liang/TrackToSearch-release") },
                        containerColor = Color.Transparent
                    )
                }
            }
        }
            // Haze 模糊标题栏（含状态栏）
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
                    viewModel.clearUserProfile()
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

    // 豆瓣登出二次确认对话框(登出后用 Snackbar 提供"重新登录"入口)
    if (showDoubanLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showDoubanLogoutDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.settings_account_douban)) },
            text = { Text(stringResource(R.string.settings_logout_confirm)) },
            confirmButton = {
                    val doubanLogoutDone = stringResource(R.string.settings_douban_logout_done)
                    val doubanSyncRelogin = stringResource(R.string.douban_sync_relogin)
                    TextButton(onClick = {
                        showDoubanLogoutDialog = false
                        viewModel.clearDoubanCredentials()
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = doubanLogoutDone,
                                actionLabel = doubanSyncRelogin,
                                duration = androidx.compose.material3.SnackbarDuration.Long
                            )
                            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                onNavigateToDoubanLogin()
                            }
                        }
                    }) {
                    Text(stringResource(R.string.settings_logout_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDoubanLogoutDialog = false }) {
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
            text = {
                Column {
                    Text(stringResource(R.string.settings_clear_cache_confirm))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.settings_cache_clear_all_impact),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
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

    // 单项缓存清除确认对话框
    if (showClearCategoryDialog && pendingClearCategory != null) {
        AlertDialog(
            onDismissRequest = {
                showClearCategoryDialog = false
                pendingClearCategory = null
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.settings_cache_clear_category_confirm)) },
            text = {
                val cat = pendingClearCategory!!
                val labelRes = when (cat) {
                    SettingsViewModel.CacheCategory.IMAGE -> R.string.settings_cache_category_image
                    SettingsViewModel.CacheCategory.MEDIA_DATA -> R.string.settings_cache_category_media_data
                    SettingsViewModel.CacheCategory.ID_MAPPING -> R.string.settings_cache_category_id_mapping
                    SettingsViewModel.CacheCategory.HTTP -> R.string.settings_cache_category_http
                    SettingsViewModel.CacheCategory.DATABASE -> R.string.settings_cache_category_database
                }
                val impactRes = when (cat) {
                    SettingsViewModel.CacheCategory.IMAGE -> R.string.settings_cache_category_image_impact
                    SettingsViewModel.CacheCategory.MEDIA_DATA -> R.string.settings_cache_category_media_data_impact
                    SettingsViewModel.CacheCategory.ID_MAPPING -> R.string.settings_cache_category_id_mapping_impact
                    SettingsViewModel.CacheCategory.HTTP -> R.string.settings_cache_category_http_impact
                    SettingsViewModel.CacheCategory.DATABASE -> R.string.settings_cache_category_database_impact
                }
                Column {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(impactRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cat = pendingClearCategory
                    showClearCategoryDialog = false
                    pendingClearCategory = null
                    cat?.let { viewModel.clearCategory(it) }
                }) {
                    Text(stringResource(R.string.settings_cache_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClearCategoryDialog = false
                    pendingClearCategory = null
                }) {
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
            enabled = panhubEnabled,
            onEnabledChange = { viewModel.setPanhubEnabled(it) },
            onConcurrencyChange = { viewModel.setPanHubConcurrency(it) },
            onTimeoutMsChange = { viewModel.setPanHubTimeoutMs(it) },
            onEnabledPluginsChange = { viewModel.setPanHubEnabledPlugins(it) },
            onEnabledChannelsChange = { viewModel.setPanHubEnabledChannels(it) },
            onDismiss = { showPanHubConfigDialog = false }
        )
    }

    // 豆瓣重试入口:有失败项时弹选择对话框(重试上次失败/导入 JSON/导出失败记录)
    if (showDoubanRetryDialog) {
        DoubanRetryDialog(
            onDismiss = { showDoubanRetryDialog = false },
            onRetryLocal = { selectedReasons ->
                showDoubanRetryDialog = false
                scope.launch {
                    val started = doubanRetryViewModel.startRetryFromLocal(selectedReasons)
                    if (started) {
                        // 重试已启动,跳转到豆瓣同步进度对话框页(沿用原导航)
                        onDoubanResync()
                    }
                }
            },
            onRetryFromJson = {
                // JSON 导入:启动文件选择器,选中后 importToRoom + 跳转查看页
                showDoubanRetryDialog = false
                importFailuresLauncher.launch(arrayOf("application/json"))
            },
            onExportFailures = {
                showDoubanRetryDialog = false
                scope.launch {
                    val uri = doubanRetryViewModel.doubanFailureExporter.exportFromLocal(context)
                    if (uri != null) {
                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(shareIntent, "分享失败项 JSON"))
                    }
                }
            },
            viewModel = doubanRetryViewModel
        )
    }

    // 豆瓣重新同步模式选择:点「重新同步豆瓣」时弹模式选择对话框(A/B/C)
    if (showSyncModePicker) {
        DoubanSyncModePickerDialog(
            syncedCount = 0,
            cooldownStatus = cooldownStatus,
            onDismiss = { showSyncModePicker = false },
            onModeSelected = { mode ->
                showSyncModePicker = false
                // 冷却期内选择增量同步(模式 A/B)时弹引导:跳过或强制同步
                val isCooling = cooldownStatus?.isCoolingDown == true
                if (isCooling && mode != com.tracktosearch.data.repository.SyncMode.FULL_REWRITE) {
                    pendingCooldownMode = mode
                    showCooldownGuidance = true
                } else {
                    scope.launch {
                        doubanRetryViewModel.doubanSyncManager.startSync(mode)
                        onDoubanResync()
                    }
                }
            }
        )
    }

    // 冷却期内选择增量同步:弹引导对话框,提供「跳过」和「强制同步」
    if (showCooldownGuidance) {
        AlertDialog(
            onDismissRequest = {
                showCooldownGuidance = false
                pendingCooldownMode = null
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.cooldown_guidance_title)) },
            text = { Text(stringResource(R.string.cooldown_guidance_message)) },
            confirmButton = {
                TextButton(onClick = {
                    val mode = pendingCooldownMode
                    showCooldownGuidance = false
                    pendingCooldownMode = null
                    if (mode != null) {
                        scope.launch {
                            // 强制同步:forceCrawl=true 跳过 7 天冷却
                            doubanRetryViewModel.doubanSyncManager.startSync(mode, forceCrawl = true)
                            onDoubanResync()
                        }
                    }
                }) { Text(stringResource(R.string.cooldown_force_sync)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCooldownGuidance = false
                    pendingCooldownMode = null
                }) { Text(stringResource(R.string.cooldown_skip)) }
            }
        )
    }

    // 状态一致性检查二次确认弹窗（显示上次检查时间，确认后才执行检查）
    if (showConsistencyConfirm) {
        AlertDialog(
            onDismissRequest = { showConsistencyConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.consistency_check_confirm_title)) },
            text = {
                Column {
                    val lastText = lastCheckTimeText
                        ?: stringResource(R.string.consistency_check_confirm_never)
                    Text(
                        text = stringResource(R.string.consistency_check_confirm_last, lastText),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.consistency_check_confirm_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showConsistencyConfirm = false
                    viewModel.startManualConsistencyCheck()
                    showConsistencyDialog = true
                }) {
                    Text(stringResource(R.string.consistency_check_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConsistencyConfirm = false }) {
                    Text(stringResource(R.string.douban_retry_cancel))
                }
            }
        )
    }

    // 状态一致性检查进度弹窗
    if (showConsistencyDialog) {
        ConsistencyCheckDialog(
            onDismiss = {
                val p = consistencyCheckState
                // 检查运行中不允许通过点击外部关闭（需点「转后台」或「取消」）
                if (!p.isRunning) showConsistencyDialog = false
            },
            onBackground = { showConsistencyDialog = false }
        )
    }

    // 未登录豆瓣时点「重新同步豆瓣」:弹确认框引导前往登录
    if (showDoubanLoginPrompt) {
        AlertDialog(
            onDismissRequest = { showDoubanLoginPrompt = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.settings_douban_not_logged_in_title)) },
            text = { Text(stringResource(R.string.settings_douban_not_logged_in_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDoubanLoginPrompt = false
                    onNavigateToDoubanLogin()
                }) { Text(stringResource(R.string.settings_douban_not_logged_in_login)) }
            },
            dismissButton = {
                TextButton(onClick = { showDoubanLoginPrompt = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * 共享元素转场动画开关卡片(外观分组下,独占一行)。
 * 开关状态收集局部化到本函数,切换时只重组本卡片。
 */
@Composable
private fun SharedTransitionSwitchCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val view = LocalView.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_shared_transition),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.settings_shared_transition_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { value ->
                    view.performHaptic(HapticType.CLICK)
                    onToggle(value)
                },
                colors = appSwitchColors()
            )
        }
    }
}

/**
 * 通知提醒卡片（仅登录用户可见）。
 * 从 LazyColumn item 抽取为独立函数：3 个开关状态收集局部化到本函数，
 * 开关变化只重组本函数，不波及 LazyColumn 其他 item。
 */
@Composable
private fun NotificationItem(
    viewModel: SettingsViewModel,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val notificationEnabled by viewModel.notificationEnabled.collectAsStateWithLifecycle()
    val releaseEnabled by viewModel.releaseReminderEnabled.collectAsStateWithLifecycle()
    val newSeasonEnabled by viewModel.newSeasonReminderEnabled.collectAsStateWithLifecycle()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.setNotificationEnabled(false)
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = containerColor
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            val view = LocalView.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_notification_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_notification_enabled_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
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
                    },
                    colors = appSwitchColors()
                )
            }
            AnimatedVisibility(visible = notificationEnabled) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(28.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_notification_release),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_notification_release_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = releaseEnabled,
                            onCheckedChange = { view.performHaptic(HapticType.CLICK); viewModel.setReleaseReminderEnabled(it) },
                            colors = appSwitchColors()
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(28.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_notification_new_season),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_notification_new_season_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = newSeasonEnabled,
                            onCheckedChange = { view.performHaptic(HapticType.CLICK); viewModel.setNewSeasonReminderEnabled(it) },
                            colors = appSwitchColors()
                        )
                    }
                }
            }
        }
    }
}

/**
 * 数据流通 2x2 卡片（导出 / 导入 IMDb / 上传云端 / 下载云端）。
 * 抽取为独立函数，避免 item lambda 捕获过多外部状态。
 */
@Composable
private fun DataFlowGridItem(
    onExport: () -> Unit,
    onImportImdb: () -> Unit,
    onUploadCloud: () -> Unit,
    onDownloadCloud: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DataFlowCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.FileUpload,
            title = stringResource(R.string.settings_export_marks_data),
            onClick = onExport,
            containerColor = containerColor
        )
        DataFlowCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.FileDownload,
            title = stringResource(R.string.settings_import_imdb),
            onClick = onImportImdb,
            containerColor = containerColor
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DataFlowCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.CloudUpload,
            title = stringResource(R.string.settings_douban_upload_cloud),
            onClick = onUploadCloud,
            containerColor = containerColor
        )
        DataFlowCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.CloudDownload,
            title = stringResource(R.string.settings_douban_download_cloud),
            onClick = onDownloadCloud,
            containerColor = containerColor
        )
    }
}

/**
 * 关于区块（版本/更新日志/帮助/新手引导 2x2 卡片）。
 * 抽取为独立函数，更新状态变化只重组本函数。
 */
@Composable
private fun AboutItem(
    hasUpdate: Boolean,
    latestVersion: String?,
    isCheckingUpdate: Boolean,
    onVersionClick: () -> Unit,
    onChangelogClick: () -> Unit,
    onHelpClick: () -> Unit,
    onRestartOnboarding: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val versionSubtitle = if (hasUpdate && latestVersion != null) {
        stringResource(R.string.settings_new_version, latestVersion)
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }
    val versionSubtitleColor = if (hasUpdate) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.Info,
            title = stringResource(R.string.settings_version),
            subtitle = versionSubtitle,
            mergeTitleAndSubtitle = true,
            loadingIcon = isCheckingUpdate,
            subtitleColor = versionSubtitleColor,
            onClick = onVersionClick,
            containerColor = containerColor
        )
        SettingsCard(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Rounded.EventNote,
            title = stringResource(R.string.settings_changelog),
            onClick = onChangelogClick,
            containerColor = containerColor
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsCard(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Rounded.HelpOutline,
            title = stringResource(R.string.settings_help),
            onClick = onHelpClick,
            containerColor = containerColor
        )
        SettingsCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.School,
            title = stringResource(R.string.settings_restart_onboarding),
            onClick = onRestartOnboarding,
            containerColor = containerColor
        )
    }
}

/**
 * 缓存管理 item：包裹 CacheManagementItem，breakdown 状态收集局部化。
 */
@Composable
private fun CacheManagementSectionItem(
    viewModel: SettingsViewModel,
    onClearCategory: (SettingsViewModel.CacheCategory) -> Unit,
    onClearAll: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val breakdown by viewModel.cacheBreakdown.collectAsStateWithLifecycle()
    CacheManagementItem(
        breakdown = breakdown,
        onClearCategory = onClearCategory,
        onClearAll = onClearAll,
        containerColor = containerColor
    )
}

/**
 * 账户区块（Trakt + 豆瓣共用一个卡片）。
 * 抽取为独立函数：3 个状态收集局部化，避免账户信息变化引起整个 LazyColumn 重组。
 * 注意：loadUserProfile/loadDoubanProfile 副作用已上提到 SettingsScreen 顶层，不在本函数内触发。
 */
@Composable
private fun AccountItem(
    viewModel: SettingsViewModel,
    onTraktLogout: () -> Unit,
    onDoubanLogin: () -> Unit,
    onDoubanLogout: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val doubanLoggedIn by viewModel.doubanLoggedIn.collectAsStateWithLifecycle()
    val doubanProfile by viewModel.doubanProfile.collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = containerColor
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AccountRow(
                accountLabel = stringResource(R.string.settings_account_trakt_label),
                avatarUrl = userProfile?.images?.avatar?.full ?: "",
                primaryName = userProfile?.username,
                secondaryName = null,
                showAvatar = true,
                isVip = userProfile?.vip == true,
                onLogout = onTraktLogout
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            val doubanCreds = doubanProfile
            if (doubanLoggedIn) {
                AccountRow(
                    accountLabel = stringResource(R.string.settings_account_douban),
                    avatarUrl = doubanCreds?.avatarUrl ?: "",
                    primaryName = doubanCreds?.nickname ?: doubanCreds?.userId,
                    secondaryName = doubanCreds?.userId?.let { "ID: $it" },
                    showAvatar = true,
                    isVip = false,
                    onLogout = onDoubanLogout
                )
            } else {
                DoubanLoginPromptRow(onLogin = onDoubanLogin)
            }
        }
    }
}

/**
 * 格式化上次状态一致性检查时间为相对时间字符串。
 *
 * - 0 或负数 → null（调用方用"从未检查过"兜底）
 * - < 1 分钟 → "刚刚"
 * - < 1 小时 → "X 分钟前"
 * - < 1 天 → "X 小时前"
 * - < 30 天 → "X 天前"
 * - >= 30 天 → "YYYY-MM-DD"
 */
private fun formatLastCheckTime(timestampMs: Long, context: android.content.Context): String? {
    if (timestampMs <= 0L) return null
    val diff = System.currentTimeMillis() - timestampMs
    val minutes = diff / (60 * 1000L)
    val hours = diff / (60 * 60 * 1000L)
    val days = diff / (24 * 60 * 60 * 1000L)

    return when {
        minutes < 1 -> context.getString(R.string.consistency_check_time_just_now)
        minutes < 60 -> context.getString(R.string.consistency_check_time_minutes_ago, minutes.toInt())
        hours < 24 -> context.getString(R.string.consistency_check_time_hours_ago, hours.toInt())
        days < 30 -> context.getString(R.string.consistency_check_time_days_ago, days.toInt())
        else -> {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            sdf.format(java.util.Date(timestampMs))
        }
    }
}
