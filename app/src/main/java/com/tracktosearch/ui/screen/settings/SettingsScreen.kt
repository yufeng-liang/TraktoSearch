package com.tracktosearch.ui.screen.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.DoubanLogo
import com.tracktosearch.ui.component.TraktLogo
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.hasListScrolled
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.BuildConfig
import com.tracktosearch.R
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.UpdateDialog
import com.tracktosearch.ui.screen.feedback.FeedbackViewModel
import com.tracktosearch.ui.screen.douban.DoubanSyncDialog
import com.tracktosearch.ui.screen.douban.DoubanSyncModePickerDialog
import com.tracktosearch.ui.screen.douban.DoubanSyncViewModel
import com.tracktosearch.ui.theme.appSwitchColors
import com.tracktosearch.ui.theme.GlassVariant
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.performHaptic
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private enum class ConsistencyCheckBlocker {
    DOUBAN_LOGIN,
    TRAKT_LOGIN,
    DOUBAN_MODE,
    DOUBAN_SYNC_RUNNING,
    CHECK_RUNNING
}
private fun resolveConsistencyCheckBlocker(
    doubanLoggedIn: Boolean,
    traktConnected: Boolean,
    doubanMode: Boolean,
    doubanSyncRunning: Boolean,
    checkRunning: Boolean
): ConsistencyCheckBlocker? = when {
    !doubanLoggedIn -> ConsistencyCheckBlocker.DOUBAN_LOGIN
    !traktConnected -> ConsistencyCheckBlocker.TRAKT_LOGIN
    doubanMode -> ConsistencyCheckBlocker.DOUBAN_MODE
    doubanSyncRunning -> ConsistencyCheckBlocker.DOUBAN_SYNC_RUNNING
    checkRunning -> ConsistencyCheckBlocker.CHECK_RUNNING
    else -> null
}
@OptIn(
    ExperimentalMaterial3Api::class,
    kotlinx.coroutines.FlowPreview::class,
    ExperimentalSharedTransitionApi::class,
)
private const val SHOW_VISUAL_EFFECT_ENTRY = true

@Composable
fun SettingsScreen(
    onLogout: () -> Unit = {},
    isLoggedIn: Boolean = true,
    /**
     * Trakt 是否已连接(独立于综合 isLoggedIn)。
     * AccountItem 用此值判断 Trakt 行显示登录还是登出,避免豆瓣单独登录时被综合 isLoggedIn 误判为 Trakt 已登录。
     */
    isTraktConnected: Boolean = false,
    onHelpClick: () -> Unit = {},
    onRestartOnboarding: () -> Unit = {},
    onDoubanResync: () -> Unit = {},
    onNavigateToDoubanLogin: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
    // 直接发起 Trakt 授权（CustomTabs 打开授权页）；未提供时回退到导航激活登录页
    onTraktLogin: () -> Unit = onNavigateToLogin,
    onStatisticsClick: () -> Unit = {},
    onMarkRecordsClick: () -> Unit = {},
    onFeedbackClick: () -> Unit = {},
    onMessagesClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settingsHazeState = remember { HazeState() }
    val feedbackViewModel: FeedbackViewModel = hiltViewModel()
    val doubanSyncViewModel: DoubanSyncViewModel = hiltViewModel()
    val unreadCount by feedbackViewModel.unreadCount.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { feedbackViewModel.fetchUnreadCount() }
    val settingsHazeStyle = HazeMaterials.thin()
    val isDark = isAppDarkTheme()
    val view = LocalView.current
    val currentTheme by viewModel.themeMode.collectAsStateWithLifecycle()
    val currentAccent by viewModel.accentColor.collectAsStateWithLifecycle()
    val currentVisualEffectMode by viewModel.visualEffectMode.collectAsStateWithLifecycle()
    val currentGlassVariant by viewModel.glassVariant.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.language.collectAsStateWithLifecycle()
    val currentDefaultTab by viewModel.defaultTab.collectAsStateWithLifecycle()
    val isLoadingChangelog by viewModel.isLoadingChangelog.collectAsStateWithLifecycle()
    // 共享元素转场动画开关:读 AppNavigation 顶层 collect 的值(App 启动即开始收集,
    // 进设置页时已稳定,避免 SettingsViewModel 延迟构造导致的初始 false→true 跳变)
    val sharedTransitionEnabled = LocalSharedTransitionEnabled.current
    val crashLogEnabled by viewModel.crashLogEnabled.collectAsStateWithLifecycle()
    // 共享元素转场 scope（帮助与说明入口 → 帮助页标题栏配对）
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

    // 豆瓣登录态:「重新同步豆瓣」点击前预检,未登录弹确认框引导登录
    val doubanLoggedIn by viewModel.doubanLoggedIn.collectAsStateWithLifecycle()
    // 增量同步冷却期状态(跨设备同步显示)
    val cooldownStatus by viewModel.cooldownStatus.collectAsStateWithLifecycle()
    // 已同步条目数量:用于模式选择对话框"已同步 N 项"展示
    val syncedCount by viewModel.syncedCount.collectAsStateWithLifecycle()
    val consistencyCheckState by viewModel.checkProgress.collectAsStateWithLifecycle()
    val isDoubanSyncRunning by viewModel.isDoubanSyncRunning.collectAsStateWithLifecycle()
    // 豆瓣独立模式下隐藏手动一致性检查入口（豆瓣模式无 Trakt 可对比，检查无意义）
    val isDoubanMode by viewModel.isDoubanMode.collectAsStateWithLifecycle()
    var showConsistencyDialog by remember { mutableStateOf(false) }
    // 状态一致性检查二次确认弹窗（显示上次检查时间，确认后才执行检查）
    var showConsistencyConfirm by remember { mutableStateOf(false) }
    var lastCheckTimeText by remember { mutableStateOf<String?>(null) }
    var showDoubanLoginPrompt by remember { mutableStateOf(false) }
    var showTraktLoginPrompt by remember { mutableStateOf(false) }
    var consistencyCheckBlocker by remember { mutableStateOf<ConsistencyCheckBlocker?>(null) }
    var showSyncModePicker by remember { mutableStateOf(false) }
    var showSyncProgressDialog by remember { mutableStateOf(false) }
    // 冷却期内点击增量同步时的引导对话框
    var showCooldownGuidance by remember { mutableStateOf(false) }
    var pendingCooldownMode by remember { mutableStateOf<com.tracktosearch.data.repository.SyncMode?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Snackbar 用于导入状态和账户操作反馈。
    val snackbarHostState = remember { SnackbarHostState() }

    fun openConsistencyCheckRequest() {
        when (val blocker = resolveConsistencyCheckBlocker(
            doubanLoggedIn = doubanLoggedIn,
            traktConnected = isTraktConnected,
            doubanMode = isDoubanMode,
            doubanSyncRunning = isDoubanSyncRunning,
            checkRunning = viewModel.isCheckRunning()
        )) {
            null -> scope.launch {
                val lastMs = viewModel.getLastConsistencyCheckAt()
                lastCheckTimeText = formatLastCheckTime(lastMs, context)
                showConsistencyConfirm = true
            }
            ConsistencyCheckBlocker.DOUBAN_LOGIN -> showDoubanLoginPrompt = true
            ConsistencyCheckBlocker.TRAKT_LOGIN -> showTraktLoginPrompt = true
            ConsistencyCheckBlocker.CHECK_RUNNING -> showConsistencyDialog = true
            else -> consistencyCheckBlocker = blocker
        }
    }

    fun startConfirmedConsistencyCheck() {
        when (val blocker = resolveConsistencyCheckBlocker(
            doubanLoggedIn = doubanLoggedIn,
            traktConnected = isTraktConnected,
            doubanMode = isDoubanMode,
            doubanSyncRunning = isDoubanSyncRunning,
            checkRunning = viewModel.isCheckRunning()
        )) {
            null -> {
                showConsistencyConfirm = false
                viewModel.startManualConsistencyCheck()
                showConsistencyDialog = true
            }
            ConsistencyCheckBlocker.DOUBAN_LOGIN -> {
                showConsistencyConfirm = false
                showDoubanLoginPrompt = true
            }
            ConsistencyCheckBlocker.TRAKT_LOGIN -> {
                showConsistencyConfirm = false
                showTraktLoginPrompt = true
            }
            ConsistencyCheckBlocker.CHECK_RUNNING -> {
                showConsistencyConfirm = false
                showConsistencyDialog = true
            }
            else -> {
                showConsistencyConfirm = false
                consistencyCheckBlocker = blocker
            }
        }
    }

    // 仅首次进入组合时刷新缓存信息。
    // 不使用 LifecycleResumeEffect，避免返回设置页时列表高度和滚动位置发生微调。
    var cacheRefreshed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!cacheRefreshed) {
            cacheRefreshed = true
            viewModel.refreshCacheInfo()
            // 仅从本地读取冷却期状态，不发起网络请求。
            viewModel.loadCooldownStatusFromLocal()
        }
    }
    // 账户资料加载：从账户 item 内上提，避免 item 滑出/滑入时重复触发网络请求
    // Trakt profile 仅在 Trakt 已连接时加载,避免豆瓣单独登录时触发 401 失败
    // 豆瓣 profile 在综合登录态下加载(豆瓣登录或 Trakt 登录都可能需要)
    LaunchedEffect(isLoggedIn, isTraktConnected) {
        if (isTraktConnected) {
            viewModel.loadUserProfile()
        }
        if (isLoggedIn) {
            viewModel.loadDoubanProfile()
        }
    }
    // 首次豆瓣登录成功后自动弹出同步模式选择。
    var hasShownDoubanImportDialog by rememberSaveable { mutableStateOf(false) }
    // 记录上一次的 doubanLoggedIn 值，仅在实际登录跳变(false→true)时弹窗，
    // 避免从激活页登录后切到设置页首次组合时 doubanLoggedIn 已为 true 导致重复弹窗
    var previousDoubanLoggedIn by remember { mutableStateOf(doubanLoggedIn) }
    LaunchedEffect(doubanLoggedIn) {
        viewModel.loadCooldownStatusFromLocal()
        // 仅在设置页期间实际登录(false→true)时弹窗，首次组合时 doubanLoggedIn 已为 true 不弹
        if (!previousDoubanLoggedIn && doubanLoggedIn && !hasShownDoubanImportDialog) {
            hasShownDoubanImportDialog = true
            showSyncModePicker = true
        }
        previousDoubanLoggedIn = doubanLoggedIn
    }
    val exportImportState by viewModel.exportImportState.collectAsStateWithLifecycle()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAccentColorDialog by remember { mutableStateOf(false) }
    var showVisualEffectDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showDefaultTabDialog by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showDoubanLogoutDialog by remember { mutableStateOf(false) }
    // 退出豆瓣二次确认弹窗展示的本地标记条数（点击退出按钮时预查）
    var doubanLogoutCount by remember { mutableIntStateOf(0) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearCategoryDialog by remember { mutableStateOf(false) }
    var pendingClearCategory by remember { mutableStateOf<SettingsViewModel.CacheCategory?>(null) }
    var showDiscoverSectionsDialog by remember { mutableStateOf(false) }
    var showDetailSectionsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(exportImportState.message) {
        exportImportState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
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
    val settingsGlassScene = glassSceneForContent(
        contentCount = 16 + if (isLoggedIn) 4 else 0,
        readabilityDemand = if (unreadCount > 0) 0.82f else 0.68f,
        ambientColor = MaterialTheme.colorScheme.background,
        contentCapacity = 24,
        loadingCount = listOf(
            exportImportState.isExporting,
            exportImportState.isImporting,
            isDoubanSyncRunning,
            consistencyCheckState.isRunning,
            isCheckingUpdate,
            isLoadingChangelog
        ).count { it },
        loadingItemWeight = 2
    )

    // LazyListState 由 NavGraph backstack 自然 remember,返回设置页时位置自动恢复,无需手动持久化
    val settingsListState = rememberLazyListState()
    val settingsHasContentUnderTopBar by remember {
        derivedStateOf {
            hasListScrolled(
                firstVisibleItemIndex = settingsListState.layoutInfo.visibleItemsInfo.firstOrNull()?.index,
                firstVisibleItemScrollOffsetPx = settingsListState.firstVisibleItemScrollOffset
            )
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
        containerColor = Color.Transparent,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = 80.dp)
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
            // 观看统计（第一位，独占整行卡片，无类目 Header）—— 仅登录可见
            // sharedBounds 与 StatisticsScreen 头部配对,实现卡片↔页面展开/收起转场
            // 豆瓣独立模式: 统计数据来源是 Trakt watchlist/history,无 trakt token 时无意义,隐藏
            if (isLoggedIn && !isDoubanMode) {
                item(key = "statistics_entry") {
                    val statisticsEntryModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && sharedTransitionEnabled) {
                        with(sharedTransitionScope) {
                            Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "settings-statistics-entry"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    } else { Modifier }
                    StatisticsCard(
                        modifier = statisticsEntryModifier,
                        hazeState = settingsHazeState,
                        onClick = onStatisticsClick
                    )
                }
            }

            // 标记记录（仅 Trakt 登录可见，独占整行卡片）
            // 豆瓣独立模式: 标记记录页读取 Trakt history,无 trakt token,隐藏
            if (isLoggedIn && !isDoubanMode) {
                item(key = "mark_records_entry") {
                    MarkRecordsEntryCard(
                    onClick = onMarkRecordsClick,
                    hazeState = settingsHazeState
                )
                }
            }

            // 外观
            item(key = "group_appearance") {
                SettingsGroupCard(
                    title = stringResource(R.string.settings_appearance),
                    hazeState = settingsHazeState
                ) {
                    val themeName = when (currentTheme) {
                        ThemeStorage.MODE_DARK -> stringResource(R.string.theme_dark)
                        ThemeStorage.MODE_LIGHT -> stringResource(R.string.theme_light)
                        else -> stringResource(R.string.theme_system)
                    }
                    val accentName = currentAccent?.let { stringResource(it.labelResId) }
                        ?: stringResource(R.string.settings_accent_dynamic)
                    val visualEffectName = when (currentVisualEffectMode) {
                        VisualEffectMode.GLASS -> when (currentGlassVariant) {
                            GlassVariant.CLEAR -> stringResource(R.string.settings_visual_effect_glass_clear)
                            GlassVariant.FOCUSED -> stringResource(R.string.settings_visual_effect_glass_focused)
                        }
                        VisualEffectMode.BLUR -> stringResource(R.string.settings_visual_effect_blur)
                    }
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
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
                    if (SHOW_VISUAL_EFFECT_ENTRY) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SettingsCard(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Rounded.AutoAwesome,
                                title = stringResource(R.string.settings_visual_effect),
                                subtitle = visualEffectName,
                                mergeTitleAndSubtitle = true,
                                onClick = { showVisualEffectDialog = true },
                                containerColor = Color.Transparent
                            )
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
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

            // 搜索源（State 收集下沉到 SearchSourcesItem，开关切换/测试结果更新只重组本 item）
            item(key = "group_search") {
                SearchSourcesItem(viewModel = viewModel, hazeState = settingsHazeState)
            }

            // 通知提醒（仅 Trakt 登录用户可见，通知依赖 Trakt 想看列表推送）
            // 豆瓣独立模式: 无 trakt token,通知功能无法触发,隐藏入口
            if (isLoggedIn && !isDoubanMode) {
                item(key = "group_notification") {
                    SettingsGroupCard(
                        title = stringResource(R.string.settings_notification),
                        hazeState = settingsHazeState
                    ) {
                        NotificationItem(
                            viewModel = viewModel,
                            containerColor = Color.Transparent
                        )
                    }
                }
            }

            // 自定义板块（合并原「发现页栏目」+「自定义详情页」）
            item(key = "group_custom") {
                SettingsGroupCard(
                    title = stringResource(R.string.settings_custom_section),
                    hazeState = settingsHazeState
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
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
                    SettingsGroupCard(
                        title = stringResource(R.string.settings_data_management),
                        hazeState = settingsHazeState
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (exportImportState.isExporting) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }

                            // 数据流通卡片（导出 / 导入 IMDb）
                            // 豆瓣独立模式: 隐藏导出 JSON 和导入 IMDb（无 trakt token 无法读写 watchlist）
                            DataFlowGridItem(
                                onExport = if (isDoubanMode) null else {
                                    {
                                        if (!exportImportState.isExporting) {
                                            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(java.util.Date())
                                            exportJsonLauncher.launch("trakt-export-$timestamp.json")
                                        }
                                    }
                                },
                                onImportImdb = if (isDoubanMode) null else {
                                    {
                                        if (!exportImportState.isImporting) {
                                            importImdbLauncher.launch("text/*")
                                        }
                                    }
                                },
                                exportEnabled = !exportImportState.isExporting,
                                importEnabled = !exportImportState.isImporting,
                                containerColor = Color.Transparent
                            )

                            GroupDivider()
                            SettingsItemCard(
                                icon = Icons.Rounded.Sync,
                                title = stringResource(
                                    if (!doubanLoggedIn) R.string.settings_douban_sync
                                    else if (isDoubanSyncRunning) R.string.settings_douban_resync_running
                                    else if (cooldownStatus?.neverSynced != false) R.string.settings_douban_sync
                                    else R.string.settings_douban_resync
                                ),
                                subtitle = stringResource(
                                    if (isDoubanSyncRunning) R.string.settings_douban_resync_running_desc
                                    else R.string.settings_douban_resync_desc
                                ),
                                onClick = {
                                    if (doubanLoggedIn) {
                                        if (isDoubanSyncRunning) {
                                            showSyncProgressDialog = true
                                        } else {
                                            scope.launch { viewModel.refreshCooldownStatus() }
                                            viewModel.refreshSyncedCount()
                                            showSyncModePicker = true
                                        }
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
                            // 豆瓣同步进行中或豆瓣独立模式下隐藏手动检查入口
                            // 同步进行中：同步后自动检查无需重复入口
                            // 豆瓣独立模式：无 Trakt 可对比，一致性检查无意义
                            if (!isDoubanSyncRunning && !isDoubanMode) {
                                GroupDivider()
                                SettingsItemCard(
                                    icon = Icons.Rounded.SyncAlt,
                                    title = stringResource(R.string.settings_douban_status_consistency),
                                    subtitle = consistencyCheckState.let { result ->
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
                                    },
                                    onClick = { openConsistencyCheckRequest() },
                                trailing = {},
                                containerColor = Color.Transparent
                            )
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
                SettingsGroupCard(
                    title = stringResource(R.string.settings_account),
                    hazeState = settingsHazeState
                ) {
                    AccountItem(
                        viewModel = viewModel,
                        // 用 Trakt 连接态(而非综合 isLoggedIn)判断 Trakt 行,
                        // 避免豆瓣单独登录时 isLoggedIn=true 导致 Trakt 行误显示为已登录
                        isTraktLoggedIn = isTraktConnected,
                        onTraktLogin = onTraktLogin,
                        onTraktLogout = { showLogoutDialog = true },
                        onDoubanLogin = { onNavigateToDoubanLogin() },
                        onDoubanLogout = {
                            // 预查本地豆瓣标记条数，弹二次确认对话框
                            scope.launch {
                                doubanLogoutCount = viewModel.getDoubanSyncedItemCount()
                                showDoubanLogoutDialog = true
                            }
                        },
                        containerColor = Color.Transparent
                    )
                }
            }

            // 缓存管理（倒数第二）：概览行 + 点击展开 5 个类目
            item(key = "group_storage") {
                SettingsGroupCard(
                    title = stringResource(R.string.settings_storage),
                    hazeState = settingsHazeState
                ) {
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
                SettingsGroupCard(
                    title = stringResource(R.string.settings_about),
                    hazeState = settingsHazeState
                ) {
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
                        icon = Icons.Rounded.Feedback,
                        title = stringResource(R.string.settings_feedback),
                        subtitle = stringResource(R.string.settings_feedback_subtitle),
                        onClick = onFeedbackClick,
                        containerColor = Color.Transparent
                    )
                    GroupDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { view.performHaptic(HapticType.CLICK); viewModel.setCrashLogEnabled(!crashLogEnabled) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    color = settingsIconContainerColor(isDark),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.BugReport,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_crash_log_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.settings_crash_log_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = crashLogEnabled,
                            onCheckedChange = { view.performHaptic(HapticType.CLICK); viewModel.setCrashLogEnabled(it) },
                            colors = appSwitchColors()
                        )
                    }
                }
            }
        }
            // 毛玻璃吸顶标题栏（thin 模糊，与发现/我的页一致）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeTopBar(
                        state = settingsHazeState,
                        style = settingsHazeStyle,
                        blurRadius = 24.dp,
                        isContentUnderTopBar = settingsHasContentUnderTopBar,
                        scene = settingsGlassScene
                    )
            ) {
                Column {
                    Spacer(modifier = Modifier.statusBarsPadding())
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_title),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        NeumorphicIconButton(
                            onClick = onMessagesClick,
                            isDark = isDark,
                            lightBorderAlpha = 0.35f,
                            hazeState = settingsHazeState,
                            scene = settingsGlassScene
                        ) {
                            BadgedBox(badge = { if (unreadCount > 0) { Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) } } }) {
                                Icon(imageVector = Icons.Rounded.Email, contentDescription = stringResource(R.string.feedback_messages), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
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

    if (showVisualEffectDialog) {
        VisualEffectSelectionDialog(
            currentMode = currentVisualEffectMode,
            currentVariant = currentGlassVariant,
            onSelection = { mode, variant ->
                viewModel.setVisualEffectSelection(mode, variant)
                showVisualEffectDialog = false
            },
            onDismiss = { showVisualEffectDialog = false }
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

    // 豆瓣登出二次确认对话框(提示将清理本地 N 条标记，登出后用 Snackbar 提供"重新登录"入口)
    if (showDoubanLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showDoubanLogoutDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.douban_logout_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.douban_logout_confirm_message, doubanLogoutCount))
                    // 同步进行中时追加警告：退出会中断同步（clearDoubanCredentials 会取消进行中的任务）
                    if (isDoubanSyncRunning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.douban_logout_sync_in_progress_warning),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
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

    // 豆瓣重新同步模式选择:点「重新同步豆瓣」时弹模式选择对话框(A/B/C)
    if (showSyncModePicker) {
        DoubanSyncModePickerDialog(
            syncedCount = syncedCount,
            cooldownStatus = cooldownStatus,
            neverSynced = cooldownStatus?.neverSynced == true,
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
                        doubanSyncViewModel.doubanSyncManager.startSync(mode)
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
                            doubanSyncViewModel.doubanSyncManager.startSync(mode, forceCrawl = true)
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

    // 同步进行中时点卡片:弹出同步进度弹窗
    if (showSyncProgressDialog) {
        DoubanSyncDialog(
            onDismiss = {
                showSyncProgressDialog = false
                if (!doubanSyncViewModel.doubanSyncManager.isRunning()) {
                    doubanSyncViewModel.doubanSyncManager.resetProgress()
                }
            },
            onRelogin = {
                showSyncProgressDialog = false
                doubanSyncViewModel.doubanSyncManager.resetProgress()
                onNavigateToDoubanLogin()
            },
            onTraktLogin = {
                showSyncProgressDialog = false
                doubanSyncViewModel.doubanSyncManager.resetProgress()
                onTraktLogin()
            },
            viewModel = doubanSyncViewModel
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
                TextButton(onClick = { startConfirmedConsistencyCheck() }) {
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
                if (!p.isRunning) {
                    showConsistencyDialog = false
                    // 用户主动关闭结果弹窗 → 清除进度（结果常驻，手动关闭而非自动消失）
                    viewModel.clearConsistencyCheckResult()
                }
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

    if (showTraktLoginPrompt) {
        AlertDialog(
            onDismissRequest = { showTraktLoginPrompt = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.consistency_check_trakt_required_title)) },
            text = { Text(stringResource(R.string.consistency_check_trakt_required_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showTraktLoginPrompt = false
                    onTraktLogin()
                }) { Text(stringResource(R.string.consistency_check_trakt_required_login)) }
            },
            dismissButton = {
                TextButton(onClick = { showTraktLoginPrompt = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    consistencyCheckBlocker?.let { blocker ->
        AlertDialog(
            onDismissRequest = { consistencyCheckBlocker = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.consistency_check_blocked_title)) },
            text = {
                Text(
                    stringResource(
                        when (blocker) {
                            ConsistencyCheckBlocker.DOUBAN_MODE -> R.string.consistency_check_blocked_douban_mode
                            ConsistencyCheckBlocker.DOUBAN_SYNC_RUNNING -> R.string.consistency_check_blocked_sync
                            else -> R.string.consistency_check_already_running
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { consistencyCheckBlocker = null }) {
                    Text(stringResource(R.string.common_confirm))
                }
            }
        )
    }
}
/**
 * 搜索源分组 item：3 个内置源开关 + 自定义源列表 + 添加入口。
 * State 收集局部化到本函数，开关切换/测试结果/配置变更只重组本 item，不波及 LazyColumn 其他 item。
 */
@Composable
private fun SearchSourcesItem(
    viewModel: SettingsViewModel,
    hazeState: dev.chrisbanes.haze.HazeState? = null
) {
    val pansouEnabled by viewModel.pansouEnabled.collectAsStateWithLifecycle()
    val panhubEnabled by viewModel.panhubEnabled.collectAsStateWithLifecycle()
    val zresoEnabled by viewModel.zresoEnabled.collectAsStateWithLifecycle()
    val customSources by viewModel.customSources.collectAsStateWithLifecycle()
    val testResults by viewModel.testResults.collectAsStateWithLifecycle()
    val panHubConfig by viewModel.panHubConfig.collectAsStateWithLifecycle()

    var showPanHubConfigDialog by remember { mutableStateOf(false) }
    var showEditCustomSource by remember { mutableStateOf<CustomSearchSource?>(null) }
    var showDeleteCustomSource by remember { mutableStateOf<CustomSearchSource?>(null) }

    SettingsGroupCard(
        title = stringResource(R.string.settings_search),
        hazeState = hazeState
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
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
}

/**
 * 标记记录入口卡片（仅登录用户可见，独占整行）。
 * 与 StatisticsCard 风格保持一致，点击跳转标记记录页。
 */
@Composable
private fun MarkRecordsEntryCard(
    onClick: () -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState? = null
) {
    val view = LocalView.current
    val isDark = isAppDarkTheme()
    NeumorphicFrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable { view.performHaptic(HapticType.CLICK); onClick() },
        isDark = isDark,
        shape = RoundedCornerShape(20.dp),
        backgroundColor = if (isDark) Color.White.copy(alpha = 0.08f)
                          else Color.White.copy(alpha = 0.70f),
        borderColor = if (isDark) Color.White.copy(alpha = 0.10f)
                      else Color(0xFFE0E5EC).copy(alpha = 0.9f),
        elevation = 6.dp,
        blurRadius = 18.dp,
        hazeState = hazeState,
        hazeStyle = HazeMaterials.thin()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = settingsIconContainerColor(isDark),
                        shape = RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.mark_records_settings_entry),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.mark_records_settings_entry_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(22.dp)
            )
        }
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
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
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
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
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
    containerColor: Color = Color.Transparent
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
    val isDark = isAppDarkTheme()
    val view = LocalView.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (notificationEnabled) {
                        viewModel.setNotificationEnabled(false)
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        viewModel.setNotificationEnabled(true)
                    }
                }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = settingsIconContainerColor(isDark),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_notification_enabled),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_notification_enabled_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
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
                GroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { view.performHaptic(HapticType.CLICK); viewModel.setReleaseReminderEnabled(!releaseEnabled) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = settingsIconContainerColor(isDark),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_notification_release),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_notification_release_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = releaseEnabled,
                        onCheckedChange = { view.performHaptic(HapticType.CLICK); viewModel.setReleaseReminderEnabled(it) },
                        colors = appSwitchColors()
                    )
                }
                GroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { view.performHaptic(HapticType.CLICK); viewModel.setNewSeasonReminderEnabled(!newSeasonEnabled) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = settingsIconContainerColor(isDark),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.LiveTv,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_notification_new_season),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_notification_new_season_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
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

/**
 * 数据流通卡片（导出 / 导入 IMDb）。
 * 抽取为独立函数，避免 item lambda 捕获过多外部状态。
 *
 * @param exportEnabled 导出按钮是否启用(导出进行中应禁用)
 * @param importEnabled 导入按钮是否启用(导入进行中应禁用)
 * 豆瓣独立模式: [onImportImdb] 为 null 时隐藏导入 IMDb 卡片（无 trakt token 无法写入），
 * 导出卡片独占整行宽度。
 */
@Composable
private fun DataFlowGridItem(
    onExport: (() -> Unit)?,
    onImportImdb: (() -> Unit)?,
    exportEnabled: Boolean = true,
    importEnabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (onExport != null) {
            DataFlowCard(
                modifier = Modifier.weight(if (onImportImdb != null) 1f else 1f),
                icon = Icons.Rounded.FileUpload,
                title = stringResource(R.string.settings_export_marks_data),
                onClick = onExport,
                containerColor = containerColor,
                enabled = exportEnabled
            )
        }
        if (onImportImdb != null) {
            DataFlowCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.FileDownload,
                title = stringResource(R.string.settings_import_imdb),
                onClick = onImportImdb,
                containerColor = containerColor,
                enabled = importEnabled
            )
        }
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
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
    isTraktLoggedIn: Boolean,
    onTraktLogin: () -> Unit,
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
        color = containerColor,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (isTraktLoggedIn) {
                AccountRow(
                    brandLogo = {
                        TraktLogo(
                            contentDescription = stringResource(R.string.settings_account_trakt_label),
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    avatarUrl = userProfile?.images?.avatar?.full ?: "",
                    primaryName = userProfile?.username,
                    secondaryName = null,
                    showAvatar = true,
                    isVip = userProfile?.vip == true,
                    onLogout = onTraktLogout
                )
            } else {
                TraktLoginPromptRow(onLogin = onTraktLogin)
            }
            GroupDivider()
            val doubanCreds = doubanProfile
            if (doubanLoggedIn) {
                AccountRow(
                    brandLogo = {
                        DoubanLogo(
                            contentDescription = stringResource(R.string.settings_account_douban),
                            modifier = Modifier.size(24.dp)
                        )
                    },
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
