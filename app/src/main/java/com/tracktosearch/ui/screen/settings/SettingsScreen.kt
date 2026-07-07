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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.HelpOutline
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
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.text.SimpleDateFormat
import java.util.Locale

/** 返回设置页后,SharedTransitionLayout/HorizontalPager 重激活期间会产生幽灵 scroll。延迟打开写入窗口,覆盖 SharedTransition 动画时长 + pager layout 稳定时间。 */
private const val SCROLL_GATE_DELAY_MS = 800L

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
    onStatisticsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val currentTheme by viewModel.themeMode.collectAsStateWithLifecycle()
    val currentAccent by viewModel.accentColor.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.language.collectAsStateWithLifecycle()
    val currentDefaultTab by viewModel.defaultTab.collectAsStateWithLifecycle()
    val pansouEnabled by viewModel.pansouEnabled.collectAsStateWithLifecycle()
    // 共享元素转场 scope（帮助与说明入口 → 帮助页标题栏配对）
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

    // 豆瓣重试入口:检测 douban_sync_failures 表是否有数据
    // 「重新同步豆瓣」按钮点击时,有失败则弹重试选择对话框,无失败直接走增量同步
    val doubanRetryViewModel: DoubanRetryViewModel = hiltViewModel()
    val doubanRetryState by doubanRetryViewModel.retryState.collectAsStateWithLifecycle()
    val cloudSyncLoading by doubanRetryViewModel.cloudSyncLoading.collectAsStateWithLifecycle()
    val cloudSyncEvent by doubanRetryViewModel.cloudSyncEvent.collectAsStateWithLifecycle()
    var showDoubanRetryDialog by remember { mutableStateOf(false) }
    var showSyncModePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // snackbarHostState 在 importFailuresLauncher 之前声明,供 launcher 回调内使用
    val snackbarHostState = remember { SnackbarHostState() }

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
                            context.getString(R.string.snackbar_import_done_json, result.count)
                        )
                        onDoubanFailures()
                    }
                    is ImportResult.InvalidFormat -> {
                        snackbarHostState.showSnackbar(context.getString(R.string.error_invalid_json_format))
                    }
                    is ImportResult.Empty -> {
                        snackbarHostState.showSnackbar(context.getString(R.string.error_empty_csv))
                    }
                    is ImportResult.Error -> {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.error_parse_failed, result.message)
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
    val cacheRefreshed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!cacheRefreshed) {
            viewModel.refreshCacheInfo()
            doubanRetryViewModel.refreshRetryState()
        }
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
    LaunchedEffect(cloudSyncEvent) {
        val event = cloudSyncEvent ?: return@LaunchedEffect
        when (event) {
            is CloudSyncEvent.DownloadSuccess -> {
                downloadedCount = event.count
                showDownloadSuccessDialog = true
            }
            else -> {
                val msg = when (event) {
                    is CloudSyncEvent.NotLoggedIn -> context.getString(R.string.cloud_sync_no_login)
                    is CloudSyncEvent.NoLocalFailures -> context.getString(R.string.cloud_sync_no_local_failures)
                    is CloudSyncEvent.UploadSuccess -> context.getString(R.string.cloud_sync_upload_success)
                    is CloudSyncEvent.UploadFailed -> context.getString(R.string.cloud_sync_upload_failed)
                    is CloudSyncEvent.CloudEmpty -> context.getString(R.string.cloud_sync_download_empty)
                    is CloudSyncEvent.DownloadFailed -> context.getString(R.string.cloud_sync_download_failed)
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

    // 用 rememberSaveable 持久化滚动位置(跨页面导航可靠恢复)
    // 显式保存 index+offset 比 LazyListState.Saver 在 HorizontalPager + Nav 跨页场景下更稳定
    val savedFirstVisibleItemIndex = rememberSaveable { mutableIntStateOf(0) }
    val savedFirstVisibleItemScrollOffset = rememberSaveable { mutableIntStateOf(0) }
    android.util.Log.d("SettingsScroll", "restore: index=${savedFirstVisibleItemIndex.intValue} offset=${savedFirstVisibleItemScrollOffset.intValue}")
    val settingsListState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedFirstVisibleItemIndex.intValue,
        initialFirstVisibleItemScrollOffset = savedFirstVisibleItemScrollOffset.intValue
    )
    // scroll gate:返回设置页后,SharedTransitionLayout/HorizontalPager 重激活期间会产生
    // "幽灵 scroll"(layout 修正导致 position 跳变)。延迟打开写入窗口,窗口期内丢弃 snapshotFlow 收集到的位置
    var scrollGateOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(SCROLL_GATE_DELAY_MS)
        scrollGateOpen = true
        android.util.Log.d("SettingsScroll", "gate opened, state index=${settingsListState.firstVisibleItemIndex} offset=${settingsListState.firstVisibleItemScrollOffset}")
    }
    // 返回设置页后 force restore:SharedTransitionLayout/HorizontalPager 重激活期间
    // settingsListState 被外部拉到错误 index。等到 layout 有 items 后立即 scrollToItem 恢复
    // 不等固定延迟,越早恢复用户越少看到错误位置
    val targetRestoreIndex = savedFirstVisibleItemIndex.intValue
    val targetRestoreOffset = savedFirstVisibleItemScrollOffset.intValue
    // 记录已执行过初次跨页恢复的 saveIdx,恢复后不再重入(避免用户新 scroll 后被旧 save 值覆盖)
    val restoreMark = rememberSaveable { mutableIntStateOf(0) }
    if ((targetRestoreIndex > 0 || targetRestoreOffset > 0) && restoreMark.intValue != targetRestoreIndex) {
        LaunchedEffect(targetRestoreIndex, targetRestoreOffset) {
            // 轮询直到 layout 完成,最多 20 次 * 50ms = 1s
            repeat(20) {
                if (settingsListState.layoutInfo.totalItemsCount > 0) {
                    val maxIndex = (settingsListState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                    val safeIndex = targetRestoreIndex.coerceAtMost(maxIndex)
                    try {
                        settingsListState.scrollToItem(safeIndex, targetRestoreOffset)
                        android.util.Log.d("SettingsScroll", "early restore: index=$safeIndex offset=$targetRestoreOffset")
                        scrollGateOpen = true
                        restoreMark.intValue = targetRestoreIndex // 标记已恢复
                    } catch (_: Exception) {
                        android.util.Log.d("SettingsScroll", "early restore failed")
                    }
                    return@LaunchedEffect
                }
                kotlinx.coroutines.delay(50)
            }
        }
    }
    // 滚动时持续保存最新位置(保证实时性)
    LaunchedEffect(settingsListState) {
        snapshotFlow {
            settingsListState.firstVisibleItemIndex to settingsListState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            if (scrollGateOpen) {
                savedFirstVisibleItemIndex.intValue = index
                savedFirstVisibleItemScrollOffset.intValue = offset
            }
            android.util.Log.d("SettingsScroll", "snapshotFlow: index=$index offset=$offset gate=$scrollGateOpen")
        }
    }
    // dispose 时同步再保存一次最终 scroll 值
    // snapshotFlow 的 collect 在 dispose 时会丢弃最后一个 in-flight emit,导致最终 scroll 值丢失
    // onDispose 同步读取 settingsListState 当前值,这是离开时用户看到的最终位置
    DisposableEffect(settingsListState) {
        onDispose {
            android.util.Log.d("SettingsScroll", "onDispose: index=${settingsListState.firstVisibleItemIndex} offset=${settingsListState.firstVisibleItemScrollOffset}")
            savedFirstVisibleItemIndex.intValue = settingsListState.firstVisibleItemIndex
            savedFirstVisibleItemScrollOffset.intValue = settingsListState.firstVisibleItemScrollOffset
        }
    }
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
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 65.dp + statusBarHeight,
                    bottom = 80.dp
                )
        ) {
            // 观看统计（第一位，独占整行卡片，无类目 Header）
            // sharedBounds 与 StatisticsScreen 头部配对,实现卡片↔页面展开/收起转场
            item {
                val statisticsEntryModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "settings-statistics-entry"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                } else { Modifier }
                StatisticsCard(modifier = statisticsEntryModifier, onClick = onStatisticsClick)
            }

            // 外观
            item { SettingsSectionHeader(stringResource(R.string.settings_appearance)) }
            item {
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
                    SettingsCard(modifier = Modifier.weight(1f), icon = Icons.Default.DarkMode, title = stringResource(R.string.settings_theme), subtitle = themeName, mergeTitleAndSubtitle = true, onClick = { showThemeDialog = true })
                    SettingsCard(modifier = Modifier.weight(1f), icon = Icons.Default.Palette, title = stringResource(R.string.settings_accent_color), subtitle = accentName, mergeTitleAndSubtitle = true, onClick = { showAccentColorDialog = true })
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsCard(modifier = Modifier.weight(1f), icon = Icons.Default.Language, title = stringResource(R.string.settings_language), subtitle = languageName, mergeTitleAndSubtitle = true, onClick = { showLanguageDialog = true })
                    SettingsCard(modifier = Modifier.weight(1f), icon = Icons.Default.Home, title = stringResource(R.string.settings_default_tab), subtitle = tabName, mergeTitleAndSubtitle = true, onClick = { showDefaultTabDialog = true })
                }
            }

            // 搜索源
            item { SettingsSectionHeader(stringResource(R.string.settings_search)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SearchSourceCard(modifier = Modifier.weight(1f).heightIn(min = 60.dp), name = "PanSou", checked = pansouEnabled, onCheckedChange = { viewModel.setPansouEnabled(it) })
                    SearchSourceCard(modifier = Modifier.weight(1f).heightIn(min = 60.dp), name = "Panhub", checked = panhubEnabled, onCheckedChange = { viewModel.setPanhubEnabled(it) }, onConfigClick = { showPanHubConfigDialog = true }, configContentDescription = stringResource(R.string.settings_panhub_config))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SearchSourceCard(modifier = Modifier.weight(1f).heightIn(min = 60.dp), name = "Zreso", checked = zresoEnabled, onCheckedChange = { viewModel.setZresoEnabled(it) })
                    SearchSourceAddCard(modifier = Modifier.weight(1f).heightIn(min = 60.dp), title = stringResource(R.string.settings_add_source), onClick = {
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
                    })
                }
            }

            // 自定义搜索源列表项（已添加的自定义源仍按原列表项展示）
            items(customSources.size, key = { customSources[it].id }) { index ->
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

            // 通知提醒（仅登录用户可见，通知依赖 Trakt 想看列表）
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
                    // 独占一行的卡片：标题 + 小字描述 + 开关；开关打开后展开为三行
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            val view = LocalView.current
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 通知图标：与设置页其他卡片风格一致,左侧带图标
                                Icon(
                                    Icons.Default.Notifications,
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
                            // 启用通知后，展开「上映提醒」和「新季提醒」两行（整体仍为一个卡片）
                            AnimatedVisibility(visible = notificationEnabled) {
                                Column {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    val releaseEnabled by viewModel.releaseReminderEnabled.collectAsStateWithLifecycle()
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 占位:Icon(20.dp) + Spacer(8.dp) 与「启用通知」文字起点对齐
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
                                    val newSeasonEnabled by viewModel.newSeasonReminderEnabled.collectAsStateWithLifecycle()
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 占位:Icon(20.dp) + Spacer(8.dp) 与「启用通知」文字起点对齐
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
            }

            // 自定义板块（合并原「发现页栏目」+「自定义详情页」，删除原两个类目 Header）
            item { SettingsSectionHeader(stringResource(R.string.settings_custom_section)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsCard(modifier = Modifier.weight(1f), icon = Icons.Default.Explore, title = stringResource(R.string.settings_discover_page), onClick = { showDiscoverSectionsDialog = true })
                    SettingsCard(modifier = Modifier.weight(1f), icon = Icons.Default.Movie, title = stringResource(R.string.settings_detail_page), onClick = { showDetailSectionsDialog = true })
                }
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
                    // 数据流通入口:2x2 圆角卡片(导出标记数据 / 从 IMDb 导入 / 上传云端 / 从云端拉取)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DataFlowCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.FileUpload,
                            title = stringResource(R.string.settings_export_marks_data),
                            onClick = {
                                if (!exportImportState.isExporting) {
                                    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(java.util.Date())
                                    exportJsonLauncher.launch("trakt-export-$timestamp.json")
                                }
                            }
                        )
                        DataFlowCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.FileDownload,
                            title = stringResource(R.string.settings_import_imdb),
                            onClick = {
                                if (!exportImportState.isImporting) {
                                    importImdbLauncher.launch("text/*")
                                }
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DataFlowCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.CloudUpload,
                            title = stringResource(R.string.settings_douban_upload_cloud),
                            onClick = { if (!cloudSyncLoading) doubanRetryViewModel.uploadToCloud() }
                        )
                        DataFlowCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.CloudDownload,
                            title = stringResource(R.string.settings_douban_download_cloud),
                            onClick = { if (!cloudSyncLoading) doubanRetryViewModel.downloadFromCloud() }
                        )
                    }
                }
                item {
                    SettingsItemCard(
                        icon = Icons.Default.FileDownload,
                        title = stringResource(R.string.settings_douban_resync),
                        subtitle = stringResource(R.string.settings_douban_resync_desc),
                        onClick = {
                            // 重新同步:弹模式选择对话框(A/B/C)
                            showSyncModePicker = true
                        }
                    )
                }
                item {
                    if (doubanRetryState.hasFailures) {
                        SettingsItemCard(
                            icon = Icons.Default.Replay,
                            title = stringResource(R.string.settings_douban_retry_failures),
                            subtitle = stringResource(R.string.douban_retry_subtitle, doubanRetryState.totalFailures),
                            onClick = {
                                // 重试失败项:弹重试选择对话框
                                showDoubanRetryDialog = true
                            }
                        )
                    }
                }
                item {
                    // 「查看同步失败项」入口:仅在有失败项时显示,卡片独占一行
                    if (doubanRetryState.hasFailures) {
                        SettingsItemCard(
                            icon = Icons.Default.BrokenImage,
                            title = stringResource(R.string.settings_douban_view_failures),
                            subtitle = stringResource(
                                R.string.settings_douban_view_failures_desc,
                                doubanRetryState.totalFailures
                            ),
                            onClick = { onDoubanFailures() }
                        )
                    }
                }
                // 云端同步进行中:显示进度条
                if (cloudSyncLoading) {
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
                                text = stringResource(R.string.cloud_sync_in_progress),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 版本卡片：标题 + 版本号合并显示「版本 - vX.X.X」一行；点击 = 检查更新
                    // 检查中：图标位置变 CircularProgressIndicator
                    // 有新版本：小字变主题色显示「新版本 vXXX」（XXX 为新版本号）
                    // 无新版本/检查失败：小字原色显示当前版本号
                    val hasUpdate = updateInfo?.hasUpdate == true
                    val latestVer = latestVersion
                    val versionSubtitle = if (hasUpdate && latestVer != null) {
                        stringResource(R.string.settings_new_version, latestVer)
                    } else {
                        "v${BuildConfig.VERSION_NAME}"
                    }
                    val versionSubtitleColor = if (hasUpdate) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant
                    SettingsCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.settings_version),
                        subtitle = versionSubtitle,
                        mergeTitleAndSubtitle = true,
                        loadingIcon = isCheckingUpdate,
                        subtitleColor = versionSubtitleColor,
                        onClick = { viewModel.checkUpdate() }
                    )
                    // 更新日志卡片：无小字
                    SettingsCard(modifier = Modifier.weight(1f), icon = Icons.Default.NewReleases, title = stringResource(R.string.settings_changelog), onClick = {
                        viewModel.loadChangelog()
                        showChangelogDialog = true
                    })
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 帮助与说明卡片
                    SettingsCard(modifier = Modifier.weight(1f), icon = Icons.Outlined.HelpOutline, title = stringResource(R.string.settings_help), onClick = { onHelpClick() })
                    // 新手引导卡片
                    SettingsCard(modifier = Modifier.weight(1f), icon = Icons.Default.AutoAwesome, title = stringResource(R.string.settings_restart_onboarding), onClick = { onRestartOnboarding() })
                }
            }
            // 源代码仓库（卡片独占一行，保留小字）
            item {
                SettingsItemCard(
                    icon = Icons.Default.Code,
                    title = stringResource(R.string.settings_source_repo),
                    subtitle = "yufeng-liang/TrackToSearch-release",
                    onClick = { openUrl("https://gitee.com/yufeng-liang/TrackToSearch-release") }
                )
            }

            // 缓存管理（倒数第二）：概览行 + 点击展开 5 个类目
            item { SettingsSectionHeader(stringResource(R.string.settings_storage)) }
            item {
                val breakdown by viewModel.cacheBreakdown.collectAsStateWithLifecycle()
                CacheManagementItem(
                    breakdown = breakdown,
                    onClearCategory = { category ->
                        pendingClearCategory = category
                        showClearCategoryDialog = true
                    },
                    onClearAll = { showClearCacheDialog = true }
                )
            }

            // 账户（仅登录用户可见）：Trakt + 豆瓣共用一个卡片，分两行
            if (isLoggedIn) {
                item { SettingsSectionHeader(stringResource(R.string.settings_account)) }
                item {
                    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
                    val doubanLoggedIn by viewModel.doubanLoggedIn.collectAsStateWithLifecycle()
                    val doubanProfile by viewModel.doubanProfile.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) {
                        viewModel.loadUserProfile()
                        viewModel.loadDoubanProfile()
                    }
                    // 一个卡片包住 Trakt + 豆瓣两行
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // 第一行：Trakt（标签 + 头像 + 名称 + 登出）
                            AccountRow(
                                accountLabel = stringResource(R.string.settings_account_trakt_label),
                                avatarUrl = userProfile?.images?.avatar?.full ?: "",
                                primaryName = userProfile?.username,
                                secondaryName = null,
                                showAvatar = true,
                                isVip = userProfile?.vip == true,
                                onLogout = { showLogoutDialog = true }
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            // 第二行：豆瓣
                            // - 已登录：头像 + 昵称 + ID 小字 + 登出按钮
                            // - 未登录/登出后：去掉头像和名称,显示登录用途说明 + 登录按钮
                            val doubanCreds = doubanProfile
                            if (doubanLoggedIn) {
                                AccountRow(
                                    accountLabel = stringResource(R.string.settings_account_douban),
                                    avatarUrl = doubanCreds?.avatarUrl ?: "",
                                    primaryName = doubanCreds?.nickname ?: doubanCreds?.userId,
                                    secondaryName = doubanCreds?.userId?.let { "ID: $it" },
                                    showAvatar = true,
                                    isVip = false,
                                    onLogout = { showDoubanLogoutDialog = true }
                                )
                            } else {
                                DoubanLoginPromptRow(
                                    onLogin = { onNavigateToDoubanLogin() }
                                )
                            }
                        }
                    }
                }
            }
        }
            // 普通标题栏（含状态栏）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
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
                TextButton(onClick = {
                    showDoubanLogoutDialog = false
                    viewModel.clearDoubanCredentials()
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = context.getString(R.string.settings_douban_logout_done),
                            actionLabel = context.getString(R.string.douban_sync_relogin),
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
            onDismiss = { showSyncModePicker = false },
            onModeSelected = { mode ->
                showSyncModePicker = false
                scope.launch {
                    doubanRetryViewModel.doubanSyncManager.startSync(mode)
                    onDoubanResync()
                }
            }
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

/**
 * 设置项卡片：独占一行的圆角卡片（图标 + 标题 + 小字 + 右箭头）。
 * 用于把原列表项样式的 SettingsItem 改为卡片样式，保留小字描述。
 */
@Composable
private fun SettingsItemCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val view = LocalView.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { view.performHaptic(HapticType.CLICK); onClick() }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
            onCheckedChange = { view.performHaptic(HapticType.CLICK); onCheckedChange(it) },
            colors = appSwitchColors()
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
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = stringResource(R.string.settings_panhub_config),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { view.performHaptic(HapticType.CLICK); onEnabledChange(it) },
                colors = appSwitchColors()
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
            onCheckedChange = { view.performHaptic(HapticType.CLICK); onCheckedChange(it) },
            colors = appSwitchColors()
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
                                Box(
                                    modifier = Modifier.width(72.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(accent.labelResId),
                                        modifier = Modifier.width(72.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
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
        Box(modifier = Modifier.widthIn(min = 80.dp, max = 140.dp).height(36.dp), contentAlignment = Alignment.Center) {
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
                    Text(stringResource(R.string.settings_check_update), maxLines = 1)
                }
            }
        }
    }
}

/**
 * 缓存管理项：初始只显示总概览，点击展开 5 个类目分项大小与清除按钮。
 *
 * - 概览行：图标 + 「缓存管理」标题 + 总大小 + 右侧展开箭头
 * - 展开后：5 个 CacheCategoryRow（图片/影视数据/ID 映射/HTTP/数据库），每个独立清除
 * - 底部：「全部清除」按钮
 */
@Composable
fun CacheManagementItem(
    breakdown: SettingsViewModel.CacheBreakdown,
    onClearCategory: (SettingsViewModel.CacheCategory) -> Unit,
    onClearAll: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    // 卡片样式：独占一行的圆角卡片
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 概览行（整卡可点击展开/收起）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_cache_management),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.settings_cache_total, formatFileSize(breakdown.totalBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }
            }

            // 展开后：保留 图片/影视数据/HTTP 三个类目（去掉 ID 映射、离线数据库）
            AnimatedVisibility(visible = expanded) {
                Column {
                    CacheCategoryRow(
                        icon = Icons.Default.Image,
                        labelRes = R.string.settings_cache_category_image,
                        descRes = R.string.settings_cache_category_image_desc,
                        sizeBytes = breakdown.imageBytes,
                        onClear = { onClearCategory(SettingsViewModel.CacheCategory.IMAGE) }
                    )
                    CacheCategoryRow(
                        icon = Icons.Default.Movie,
                        labelRes = R.string.settings_cache_category_media_data,
                        descRes = R.string.settings_cache_category_media_data_desc,
                        sizeBytes = breakdown.mediaDataBytes,
                        onClear = { onClearCategory(SettingsViewModel.CacheCategory.MEDIA_DATA) }
                    )
                    CacheCategoryRow(
                        icon = Icons.Default.CloudDownload,
                        labelRes = R.string.settings_cache_category_http,
                        descRes = R.string.settings_cache_category_http_desc,
                        sizeBytes = breakdown.httpBytes,
                        onClear = { onClearCategory(SettingsViewModel.CacheCategory.HTTP) }
                    )
                    // 全部清除按钮
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(onClick = onClearAll) {
                            Text(stringResource(R.string.settings_cache_clear_all))
                        }
                    }
                }
            }
        }
    }
}

/** 缓存类目行：图标 + 名称 + 描述 + 大小 + 清除按钮 */
@Composable
private fun CacheCategoryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    descRes: Int,
    sizeBytes: Long,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = formatFileSize(sizeBytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        OutlinedButton(
            onClick = onClear,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
        ) {
            Text(stringResource(R.string.settings_cache_clear))
        }
    }
}

/** 文件大小格式化（B/KB/MB/GB） */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${size.toInt()} ${units[unitIndex]}"
    else String.format("%.1f %s", size, units[unitIndex])
}

/**
 * 账户行：左标签 + 头像 + 主名称（含可选 ID 小字）+ 登出按钮。
 * 用于账户卡片中 Trakt / 豆瓣两行，统一行结构。
 *
 * - avatarUrl 为空且 showAvatar=true 时显示占位图标（未登录或加载失败）
 * - primaryName 为 null 时显示骨架占位（等待加载）
 * - secondaryName 非空时在主名称下方以小字显示（豆瓣 ID 行用）
 */
@Composable
private fun AccountRow(
    accountLabel: String,
    avatarUrl: String,
    primaryName: String?,
    secondaryName: String?,
    showAvatar: Boolean,
    isVip: Boolean,
    onLogout: () -> Unit
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 账户标签：固定宽度让 Trakt/豆瓣两行的头像起点对齐
        Text(
            text = accountLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        // 头像：仅在 showAvatar=true 时展示（豆瓣未登录时无头像）
        if (showAvatar) {
            SubcomposeAsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentScale = ContentScale.Crop,
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
            Spacer(modifier = Modifier.width(12.dp))
        } else {
            // 未登录豆瓣：用占位图标保持视觉对齐
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        // 主名称 + 可选副名称（豆瓣 ID 小字）
        Column(modifier = Modifier.weight(1f)) {
            if (primaryName == null) {
                // 名称骨架
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surface)
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = primaryName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isVip) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Text(
                                text = "VIP",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
            if (secondaryName != null) {
                Text(
                    text = secondaryName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 右侧登出按钮（固定高度确保两行按钮大小一致）
        Button(
            onClick = {
                view.performHaptic(HapticType.HEAVY_CLICK)
                onLogout()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(36.dp)
        ) {
            Text(stringResource(R.string.settings_logout_button))
        }
    }
}

/**
 * 豆瓣未登录行：标签 + 登录用途说明文字 + 登录按钮。
 * 用于账户卡片中豆瓣未登录或登出后的状态,与 AccountRow 保持行结构一致(标签宽度对齐)。
 */
@Composable
private fun DoubanLoginPromptRow(
    onLogin: () -> Unit
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 账户标签：与 AccountRow 固定 40dp 宽度对齐
        Text(
            text = stringResource(R.string.settings_account_douban),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        // 登录用途说明文字
        Text(
            text = stringResource(R.string.settings_douban_login_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        // 登录按钮(与 AccountRow 登出按钮同高,保持视觉对齐)
        Button(
            onClick = {
                view.performHaptic(HapticType.HEAVY_CLICK)
                onLogin()
            },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(36.dp)
        ) {
            Text(stringResource(R.string.settings_account_douban_login))
        }
    }
}

/** 退出登录项：文字"退出登录" + 右侧红色"登出"按钮 */
@Composable
fun LogoutItem(onClick: () -> Unit) {
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
            onCheckedChange = { view.performHaptic(HapticType.CLICK); onToggle(it) },
            colors = appSwitchColors()
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
        "douban-nowplaying" -> stringResource(R.string.discover_douban_nowplaying)
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
                            },
                colors = appSwitchColors()
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
            Switch(
                checked = source.enabled,
                onCheckedChange = { view.performHaptic(HapticType.CLICK); onToggle(it) },
                colors = appSwitchColors()
            )
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

/**
 * 数据流通卡片：圆角居中布局（图标 + 标题），用于 2x2 网格入口
 */
@Composable
private fun DataFlowCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

/**
 * 设置卡片：圆角居中布局（图标 + 标题 + 可选小字），用于 2x2 / 2x1 网格入口。
 * iconTintColor 用于根据状态（如启用/禁用）变化以提供视觉反馈。
 *
 * - mergeTitleAndSubtitle：true 时合并显示「标题 - 小字」为一行；无小字则只显示标题
 * - loadingIcon：true 时图标位置用 CircularProgressIndicator 替代（用于版本检查等待态）
 * - subtitleColor：小字颜色（merge 模式下整个合并文本使用此颜色）
 */
@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    mergeTitleAndSubtitle: Boolean = false,
    iconTintColor: Color = MaterialTheme.colorScheme.primary,
    loadingIcon: Boolean = false,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    val view = LocalView.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { view.performHaptic(HapticType.CLICK); onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (loadingIcon) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = iconTintColor
                )
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = iconTintColor)
            }
            if (mergeTitleAndSubtitle) {
                // 合并显示「标题 - 小字」一行；小字为空时只显示标题，不显示破折号
                val displayText = if (!subtitle.isNullOrEmpty()) {
                    "$title - $subtitle"
                } else {
                    title
                }
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = subtitleColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = subtitleColor,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 搜索源卡片：用于 2x2 网格（PanSou/Panhub/Zreso）。
 * 左侧搜索源名字 + 右侧开关；名字颜色随开关状态变化（开启=主题色，关闭=原色）。
 * 可选配置按钮（Panhub 用齿轮图标）位于中间。
 */
@Composable
private fun SearchSourceCard(
    modifier: Modifier = Modifier,
    name: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onConfigClick: (() -> Unit)? = null,
    configContentDescription: String? = null
) {
    val view = LocalView.current
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (checked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (onConfigClick != null) {
                IconButton(
                    onClick = { view.performHaptic(HapticType.CLICK); onConfigClick() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = configContentDescription,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }
            Switch(
                checked = checked,
                onCheckedChange = { view.performHaptic(HapticType.CLICK); onCheckedChange(it) },
                colors = appSwitchColors()
            )
        }
    }
}

/**
 * 搜索源「添加」卡片：与 [SearchSourceCard] 同高度对齐,内部 Column 居中布局。
 * 左侧 Add 图标 + 标题垂直堆叠,与其他卡片有开关按钮撑大的视觉感保持一致。
 */
@Composable
private fun SearchSourceAddCard(
    modifier: Modifier = Modifier,
    title: String,
    onClick: () -> Unit
) {
    val view = LocalView.current
    Surface(
        modifier = modifier
            .fillMaxSize()
            .clickable { view.performHaptic(HapticType.CLICK); onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 观看统计卡片：横跨整宽的单卡片（图标 + 标题 + 描述小字 + 右箭头）。
 * 作为设置页第一位置，无类目 Header。
 */
@Composable
private fun StatisticsCard(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val view = LocalView.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { view.performHaptic(HapticType.CLICK); onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.BarChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_view_statistics),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.settings_view_statistics_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

