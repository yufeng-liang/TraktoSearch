package com.tracktosearch.ui.screen.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
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
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Vibration
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tracktosearch.BuildConfig
import com.tracktosearch.R
import com.tracktosearch.data.local.CooldownStatus
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.ui.haptic.HapticModeSummary
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.PopupShowEffect
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.hapticModeSummary
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.haptic.semantic
import com.tracktosearch.ui.component.AppAlertDialog
import com.tracktosearch.ui.component.CloudThemeManager
import com.tracktosearch.ui.component.DialogAction
import com.tracktosearch.ui.component.DoubanLogo
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.GlassSurfaceRole
import com.tracktosearch.ui.component.LocalBackdrop
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.TopBarBackdropBlurRadius
import com.tracktosearch.ui.component.TopBarBackdropSourcePadding
import com.tracktosearch.ui.component.TraktLogo
import com.tracktosearch.ui.component.UpdateDialog
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.screen.douban.DoubanSyncDialog
import com.tracktosearch.ui.screen.douban.DoubanSyncModePickerDialog
import com.tracktosearch.ui.screen.douban.DoubanSyncViewModel
import com.tracktosearch.ui.screen.feedback.FeedbackViewModel
import com.tracktosearch.ui.theme.GlassBorderDarkSubtle
import com.tracktosearch.ui.theme.GlassFillDarkSubtle
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.MeshPreset
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.theme.appSwitchColors
import com.tracktosearch.ui.component.SettingsEntryCardCorner
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** EntryPoint 用于在设置页拿到 CloudThemeManager（关于页版本号连点拉起彩蛋题面） */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsCloudThemeProvider {
    fun cloudThemeManager(): CloudThemeManager
}

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

/** 文件大小格式化（B/KB/MB/GB），与缓存管理卡片一致 */
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

@OptIn(
    ExperimentalMaterial3Api::class,
    kotlinx.coroutines.FlowPreview::class,
)
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
    onOpenSourceClick: () -> Unit = {},
    onDoubanResync: () -> Unit = {},
    onNavigateToDoubanLogin: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
    // 直接发起 Trakt 授权（CustomTabs 打开授权页）；未提供时回退到导航激活登录页
    onTraktLogin: () -> Unit = onNavigateToLogin,
    onStatisticsClick: () -> Unit = {},
    onMarkRecordsClick: () -> Unit = {},
    onFeedbackClick: () -> Unit = {},
    onMessagesClick: () -> Unit = {},
    onSearchSourcesClick: () -> Unit = {},
    onPrivacyClick: () -> Unit = {},
    onSplashQuoteClick: () -> Unit = {},
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
    val currentTheme by viewModel.themeMode.collectAsStateWithLifecycle()
    val currentAccent by viewModel.accentColor.collectAsStateWithLifecycle()
    val customAccentColors by viewModel.customAccentColors.collectAsStateWithLifecycle()
    val selectedCustomAccentArgb by viewModel.selectedCustomAccentArgb.collectAsStateWithLifecycle()
    val currentVisualEffectMode by viewModel.visualEffectMode.collectAsStateWithLifecycle()
    val currentGlassVariant by viewModel.glassVariant.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.language.collectAsStateWithLifecycle()
    val currentDefaultTab by viewModel.defaultTab.collectAsStateWithLifecycle()
    val isLoadingChangelog by viewModel.isLoadingChangelog.collectAsStateWithLifecycle()
    // 豆瓣登录态:「重新同步豆瓣」点击前预检,未登录弹确认框引导登录
    val doubanLoggedIn by viewModel.doubanLoggedIn.collectAsStateWithLifecycle()
    // 增量同步冷却期状态(跨设备同步显示)
    val cooldownStatus by viewModel.cooldownStatus.collectAsStateWithLifecycle()
    // 已同步条目数量:用于模式选择对话框"已同步 N 项"展示
    val syncedCount by viewModel.syncedCount.collectAsStateWithLifecycle()
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
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAccentColorDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showDefaultTabDialog by remember { mutableStateOf(false) }
    var showHapticDialog by remember { mutableStateOf(false) }
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
    // 背景光晕：预设 + 开关（用于"色调与材质"弹窗内同材质的背景光晕菜单）
    val currentMeshPreset by viewModel.meshPreset.collectAsStateWithLifecycle()
    val currentMeshEnabled by viewModel.meshEnabled.collectAsStateWithLifecycle()
    // 霉粉彩蛋解锁位：未解锁时背景光晕列表里整项不出现「星云」
    val swiftieUnlocked by viewModel.swiftieUnlocked.collectAsStateWithLifecycle()
    // 关于页长按版本号拉起霉粉彩蛋题面：题面显隐挂在 CloudThemeManager 上
    val cloudThemeManager = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SettingsCloudThemeProvider::class.java
        ).cloudThemeManager()
    }

    val openUrl: (String) -> Unit = { url ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // 触感：系统总开关与有无马达在每次回到前台重读 —— 用户看到「系统已关闭触感」
    // 大概就会去系统设置打开再切回来，那正是这句提示该消失的时刻。
    LifecycleResumeEffect(Unit) {
        viewModel.refreshHapticSystemState()
        onPauseOrDispose { }
    }

    // 跳系统「声音与振动」页。ACTION_SOUND_SETTINGS 是 API 1 的公开常量，
    // 但个别 ROM 拆过这个页面，拿不到就静默放弃 —— 提示文案本身已经把原因说清楚了，
    // 弹一句「打不开」只是再添一层噪音。
    val openSystemSoundSettings: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    val showUpdateDialog by viewModel.showUpdateDialog.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsStateWithLifecycle()
    // 玻璃场景只需要"是否正在导入/导出"布尔值：用 distinctUntilChanged 折叠导入进度的
    // 高频 tick（完整 exportImportState 已下沉到 DataManagementGroupItem，避免顶层整页重组）
    val isImportExportActive by viewModel.exportImportState
        .map { it.isExporting || it.isImporting }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = false)
    // 一致性检查同理：顶层只观察 isRunning 翻转，逐条进度 tick 只在 group_data item 内部重组
    val isConsistencyCheckRunning by viewModel.checkProgress
        .map { it.isRunning }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = false)
    // 导出/导入/清缓存等操作的 Snackbar 反馈：仅观察 message 字段，避免随进度 tick 整页重组；
    // message 为机器码(资源 ID+参数)，组合期转本地化文案
    val exportImportMessage by viewModel.exportImportState
        .map { it.message }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = null as ExportMessage?)
    val exportMessageText = exportImportMessage
        ?.let { stringResource(it.resId, *it.args.toTypedArray()) }
    // 这一处 snackbar 是本页所有导出/导入/清缓存/检查更新结果的唯一出口，触感就挂在这里，
    // 不额外开第二条事件流；方向由 ExportMessage.outcome 给出，17 处构造点各自表过态
    val outcomeHaptics = rememberAppHaptics()
    LaunchedEffect(exportImportMessage) {
        // exportMessageText 与 exportImportMessage 同生同灭：前者就是后者渲染出来的文案
        val text = exportMessageText ?: return@LaunchedEffect
        // 先震后弹：showSnackbar 会挂起到提示消失，放在它后面就得等用户看完才震到手上。
        // 进度类消息的 outcome 是 null，逐条刷新不发触感
        exportImportMessage?.outcome?.let { outcomeHaptics.perform(it.semantic()) }
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }
    // 玻璃场景只依赖加载/登录等低频状态，包 remember 避免无关重组时重算；
    // 背景色作为 key 之一，主题切换时场景随之一并刷新（remember 块内不能调用
    // @Composable getter，先在外部取值再传入）
    val sceneBackground = MaterialTheme.colorScheme.background
    val settingsGlassScene = remember(
        isLoggedIn,
        unreadCount,
        isImportExportActive,
        isDoubanSyncRunning,
        isConsistencyCheckRunning,
        isCheckingUpdate,
        isLoadingChangelog,
        sceneBackground
    ) {
        glassSceneForContent(
            contentCount = 16 + if (isLoggedIn) 4 else 0,
            readabilityDemand = if (unreadCount > 0) 0.82f else 0.68f,
            ambientColor = sceneBackground,
            contentCapacity = 24,
            loadingCount = listOf(
                isImportExportActive,
                isDoubanSyncRunning,
                isConsistencyCheckRunning,
                isCheckingUpdate,
                isLoadingChangelog
            ).count { it },
            loadingItemWeight = 2
        )
    }

    // LazyListState 由 NavGraph backstack 自然 remember,返回设置页时位置自动恢复,无需手动持久化
    val settingsListState = rememberLazyListState()
    // 顶栏采样独立的列表 source，不能与列表内 Glass 卡片共用同一个 source。
    // 底垫先画页面环境层（MainScreen 光晕/页面背景），再叠列表内容：顶栏按钮采本 backdrop 时，
    // 正后方有内容折射内容、没有则折射页面渐变，而非平色白。
    val settingsBackdropBackground = MaterialTheme.colorScheme.background
    val settingsAmbientBackdropLayer = (LocalBackdrop.current as? LayerBackdrop)?.graphicsLayer
    // Glass 模式才需要录制内容 backdrop 层；BLUR 模式无人消费，见下方 layerBackdrop 门控。
    val isSettingsGlassMode = LocalVisualEffectMode.current == VisualEffectMode.GLASS
    val settingsContentBackdrop = rememberLayerBackdrop(
        onDraw = remember(settingsBackdropBackground, settingsAmbientBackdropLayer) {
            {
                if (settingsAmbientBackdropLayer != null) drawLayer(settingsAmbientBackdropLayer)
                else drawRect(settingsBackdropBackground)
                drawContent()
            }
        }
    )
    val settingsHasContentUnderTopBar by remember {
        derivedStateOf {
            hasListScrolled(
                firstVisibleItemIndex = settingsListState.firstVisibleItemIndex,
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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            // Backdrop source 在窗口外各保留一个顶栏 blur 半径；边缘卷积采样背景而非裁切/重复首列内容。
            Box(
                modifier = Modifier
                    // requiredWidth 会将超出父约束的 source 自动居中，左右各保留 blur 采样余量。
                    .requiredWidth(maxWidth + TopBarBackdropSourcePadding * 2)
                    .fillMaxHeight()
                    // 只有 Glass 模式的顶栏才通过 backdropOverride / LocalBackdrop 采样这一层。
                    // BLUR 模式顶栏走 hazeSource + NeumorphicFrostedSurface（无 backdropOverride 入参），
                    // 这份全屏离屏录制写了没人读，每帧纯浪费。
                    .then(
                        if (isSettingsGlassMode) {
                            Modifier.layerBackdrop(settingsContentBackdrop)
                        } else {
                            Modifier
                        }
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = TopBarBackdropSourcePadding)
                ) {
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
            // 观看统计（第一位，独占整行卡片，无类目 Header）—— Trakt 已连接或豆瓣独立模式可见
            // 豆瓣独立模式: StatisticsViewModel 支持基于豆瓣本地同步数据的统计，与 Trakt 统计同等可用
            if (isLoggedIn && (isDoubanMode || isTraktConnected)) {
                item(key = "statistics_entry") {
                    StatisticsCard(
                        modifier = Modifier,
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
                        modifier = Modifier,
                        onClick = onMarkRecordsClick,
                        hazeState = settingsHazeState
                    )
                }
            }

            // 外观（主题/语言/默认标签页当前值在 AppearanceGroupItem 内部收集，切换只重组本 item）
            item(key = "group_appearance") {
                AppearanceGroupItem(
                    viewModel = viewModel,
                    hazeState = settingsHazeState,
                    onThemeClick = { showThemeDialog = true },
                    onAccentColorClick = { showAccentColorDialog = true },
                    onLanguageClick = { showLanguageDialog = true },
                    onDefaultTabClick = { showDefaultTabDialog = true },
                    onHapticClick = { showHapticDialog = true },
                    onSplashQuoteClick = onSplashQuoteClick
                )
            }

            // 搜索源（独立管理页入口，与标记记录入口卡片同构）
            item(key = "search_sources_entry") {
                SearchSourcesEntryCard(
                    modifier = Modifier,
                    onClick = onSearchSourcesClick,
                    hazeState = settingsHazeState
                )
            }

            // 通知提醒（仅 Trakt 登录用户可见，通知依赖 Trakt 想看列表推送）
            // 豆瓣独立模式: 无 trakt token,通知功能无法触发,隐藏入口
            if (isLoggedIn) {
                item(key = "group_notification") {
                    SettingsGroupCard(
                        title = stringResource(R.string.settings_notification),
                        hazeState = settingsHazeState
                    ) {
                        if (isDoubanMode) {
                            // 豆瓣独立模式没有 trakt token，通知无法触发。原来整组直接消失，
                            // 界面不给消失原因，用户以为功能没了；现在保留位置并说明前提。
                            Text(
                                text = stringResource(R.string.settings_notification_requires_trakt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            )
                        } else {
                            NotificationItem(
                                viewModel = viewModel,
                                containerColor = Color.Transparent
                            )
                        }
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

            // 数据管理（仅登录用户可见，依赖 Trakt API）。
            // 导出/导入状态与一致性检查进度在 DataManagementGroupItem 内部收集，
            // 导入逐条进度 / 检查进度 tick 只重组本 item，不波及 LazyColumn 其他 item
            if (isLoggedIn) {
                item(key = "group_data") {
                    DataManagementGroupItem(
                        viewModel = viewModel,
                        hazeState = settingsHazeState,
                        isDoubanMode = isDoubanMode,
                        doubanLoggedIn = doubanLoggedIn,
                        cooldownStatus = cooldownStatus,
                        isDoubanSyncRunning = isDoubanSyncRunning,
                        onSyncClick = {
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
                        onConsistencyCheckClick = { openConsistencyCheckRequest() }
                    )
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

            // 数据与隐私：独立管理页入口（AI taste/崩溃上报开关与隐私说明迁入隐私页，与搜索源入口卡片同构）
            item(key = "privacy_entry") {
                PrivacyEntryCard(
                    onClick = onPrivacyClick,
                    hazeState = settingsHazeState
                )
            }

            // 关于（更新信息/最新版本在 AboutGroupItem 内部收集，检查更新只重组本 item）
            item(key = "group_about") {
                AboutGroupItem(
                    viewModel = viewModel,
                    hazeState = settingsHazeState,
                    isCheckingUpdate = isCheckingUpdate,
                    onVersionClick = {
                        // 单击版本号只查更新；彩蛋入口改为长按触发，不再连点。
                        viewModel.checkUpdate()
                    },
                    // 长按版本号拉起霉粉彩蛋题面，与检查更新互不干扰。
                    onVersionLongClick = {
                        cloudThemeManager.openSwiftieEgg()
                    },
                    onChangelogClick = {
                        viewModel.loadChangelog()
                        showChangelogDialog = true
                    },
                    onHelpClick = onHelpClick,
                    onOpenSourceClick = onOpenSourceClick,
                    onFeedbackClick = onFeedbackClick
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
                        blurRadius = TopBarBackdropBlurRadius,
                        isContentUnderTopBar = settingsHasContentUnderTopBar,
                        // 与 layerBackdrop 门控保持一致：BLUR 模式没录这一层，就不该再传。
                        backdropOverride = if (isSettingsGlassMode) settingsContentBackdrop else null,
                        scene = settingsGlassScene
                    )
                    // 拦截点击：顶栏覆盖可滚动列表，不消费会让点击穿透到下方列表项
                    .clickable(enabled = false, onClick = {})
            ) {
                Column {
                    Spacer(modifier = Modifier.statusBarsPadding())
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
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
                        // 顶栏按钮采本页 content backdrop（光晕底垫+列表）：静止折射页面渐变、
                        // 列表滚到栏下时折射内容，避免只采到页面粉色 mesh 或平色白。
                        CompositionLocalProvider(LocalBackdrop provides settingsContentBackdrop) {
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
            onAccentSelected = { viewModel.setAccentColor(it) },
            customAccentColors = customAccentColors,
            selectedCustomAccentArgb = selectedCustomAccentArgb,
            onCustomAccentSelected = { viewModel.setSelectedCustomAccent(it) },
            onCustomAccentAdd = { viewModel.addCustomAccent(it) },
            onCustomAccentUpdate = { old, new -> viewModel.updateCustomAccent(old, new) },
            onCustomAccentRemove = { viewModel.removeCustomAccent(it) },
            currentMode = currentVisualEffectMode,
            currentVariant = currentGlassVariant,
            onVisualEffectSelected = { mode, variant ->
                viewModel.setVisualEffectSelection(mode, variant)
            },
            currentMeshPreset = MeshPreset.fromStorage(currentMeshPreset),
            currentMeshEnabled = currentMeshEnabled,
            onMeshSelected = { preset ->
                viewModel.setMeshEnabled(preset != null)
                if (preset != null) viewModel.setMeshPreset(preset.name)
            },
            swiftieUnlocked = swiftieUnlocked,
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

    // 触感档位对话框：档位来自 DataStore，设备限制来自 resume 时重读的系统状态
    if (showHapticDialog) {
        val currentHapticMode by viewModel.hapticMode.collectAsStateWithLifecycle()
        val hapticSystemState by viewModel.hapticSystemState.collectAsStateWithLifecycle()
        HapticModeSelectionDialog(
            currentMode = currentHapticMode,
            systemState = hapticSystemState,
            onModeSelected = {
                viewModel.setHapticMode(it)
                showHapticDialog = false
            },
            onOpenSystemSettings = openSystemSoundSettings,
            onDismiss = { showHapticDialog = false }
        )
    }

    if (showChangelogDialog) {
        ChangelogDialog(
            viewModel = viewModel,
            onDismiss = { showChangelogDialog = false }
        )
    }

    if (showLogoutDialog) {
        // Snackbar 回执文案在 onClick 里还要用，提前到调用点取
        val traktLogoutDone = stringResource(R.string.settings_logout_button)
        val reloginLabel = stringResource(R.string.settings_account_reconnect)
        AppAlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = stringResource(R.string.settings_account),
            // 主句 + 副句形态：副句降为 supportMessage
            message = stringResource(R.string.settings_logout_confirm),
            supportMessage = stringResource(R.string.settings_logout_confirm_impact),
            confirm = DialogAction(
                label = stringResource(R.string.settings_logout_button),
                onClick = {
                    showLogoutDialog = false
                    viewModel.clearUserProfile()
                    onLogout()
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = traktLogoutDone,
                            actionLabel = reloginLabel,
                            duration = androidx.compose.material3.SnackbarDuration.Long
                        )
                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            onTraktLogin()
                        }
                    }
                }
            ),
            dismiss = DialogAction(
                label = stringResource(android.R.string.cancel),
                onClick = { showLogoutDialog = false }
            )
        )
    }

    // 豆瓣登出二次确认对话框(提示将清理本地 N 条标记，登出后用 Snackbar 提供"重新登录"入口)
    if (showDoubanLogoutDialog) {
        // Snackbar 回执文案在 onClick 里还要用，提前到调用点取
        val doubanLogoutDone = stringResource(R.string.settings_douban_logout_done)
        val doubanSyncRelogin = stringResource(R.string.douban_sync_relogin)
        AppAlertDialog(
            onDismissRequest = { showDoubanLogoutDialog = false },
            title = stringResource(R.string.douban_logout_confirm_title),
            message = stringResource(R.string.douban_logout_confirm_message, doubanLogoutCount),
            // 同步进行中时追加警告：退出会中断同步（clearDoubanCredentials 会取消进行中的任务）；
            // 警告是 error 色而非副句的 onSurfaceVariant，保留自绘 content 槽
            content = {
                if (isDoubanSyncRunning) {
                    Text(
                        text = stringResource(R.string.douban_logout_sync_in_progress_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirm = DialogAction(
                label = stringResource(R.string.settings_logout_button),
                onClick = {
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
                }
            ),
            dismiss = DialogAction(
                label = stringResource(android.R.string.cancel),
                onClick = { showDoubanLogoutDialog = false }
            )
        )
    }

    if (showClearCacheDialog) {
        AppAlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = stringResource(R.string.settings_cache),
            // 主句 + 副句形态：副句降为 supportMessage
            message = stringResource(R.string.settings_clear_cache_confirm),
            supportMessage = stringResource(R.string.settings_cache_clear_all_impact),
            confirm = DialogAction(
                label = stringResource(R.string.settings_cache_clear),
                onClick = {
                    showClearCacheDialog = false
                    viewModel.clearCache()
                }
            ),
            dismiss = DialogAction(
                label = stringResource(android.R.string.cancel),
                onClick = { showClearCacheDialog = false }
            )
        )
    }

    // 单项缓存清除确认对话框
    if (showClearCategoryDialog && pendingClearCategory != null) {
        AppAlertDialog(
            onDismissRequest = {
                showClearCategoryDialog = false
                pendingClearCategory = null
            },
            title = stringResource(R.string.settings_cache_clear_category_confirm),
            // 主句需按分类现场算资源，主句+副句两行留在 content 槽自绘
            content = {
                val cat = pendingClearCategory!!
                val labelRes = when (cat) {
                    SettingsViewModel.CacheCategory.IMAGE -> R.string.settings_cache_category_image
                    SettingsViewModel.CacheCategory.MEDIA_DATA -> R.string.settings_cache_category_media_data
                    SettingsViewModel.CacheCategory.HTTP -> R.string.settings_cache_category_http
                }
                val impactRes = when (cat) {
                    SettingsViewModel.CacheCategory.IMAGE -> R.string.settings_cache_category_image_impact
                    SettingsViewModel.CacheCategory.MEDIA_DATA -> R.string.settings_cache_category_media_data_impact
                    SettingsViewModel.CacheCategory.HTTP -> R.string.settings_cache_category_http_impact
                }
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
            },
            confirm = DialogAction(
                label = stringResource(R.string.settings_cache_clear),
                onClick = {
                    val cat = pendingClearCategory
                    showClearCategoryDialog = false
                    pendingClearCategory = null
                    cat?.let { viewModel.clearCategory(it) }
                }
            ),
            dismiss = DialogAction(
                label = stringResource(android.R.string.cancel),
                onClick = {
                    showClearCategoryDialog = false
                    pendingClearCategory = null
                }
            )
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
    // 按「检查更新」那一下已经发过 tap()，但弹窗是网络回来之后才出现的 ——
    // 中间隔着一次请求，早就不是同一帧了，所以这一记不算重复
    PopupShowEffect(showUpdateDialog && updateInfo != null)
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
            isDoubanOnly = isDoubanMode,
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
        AppAlertDialog(
            onDismissRequest = {
                showCooldownGuidance = false
                pendingCooldownMode = null
            },
            title = stringResource(R.string.cooldown_guidance_title),
            message = stringResource(R.string.cooldown_guidance_message),
            confirm = DialogAction(
                label = stringResource(R.string.cooldown_force_sync),
                onClick = {
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
                }
            ),
            // 「跳过」在这个对话框里就是「稍后」，取轻一档（触感由按钮行组件负责）
            dismiss = DialogAction(
                label = stringResource(R.string.cooldown_skip),
                onClick = {
                    showCooldownGuidance = false
                    pendingCooldownMode = null
                }
            )
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
        AppAlertDialog(
            onDismissRequest = { showConsistencyConfirm = false },
            title = stringResource(R.string.consistency_check_confirm_title),
            // 主句需按「上次检查时间」现场拼资源，主句+副句留在 content 槽自绘
            content = {
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
            },
            confirm = DialogAction(
                label = stringResource(R.string.consistency_check_confirm_button),
                onClick = { startConfirmedConsistencyCheck() }
            ),
            dismiss = DialogAction(
                label = stringResource(R.string.douban_retry_cancel),
                onClick = { showConsistencyConfirm = false }
            )
        )
    }

    // 状态一致性检查进度弹窗
    if (showConsistencyDialog) {
        ConsistencyCheckDialog(
            // 对话框内部已保证仅在检查未运行时才回调 onDismiss（onDismissRequest 守卫 +
            // 完成按钮仅在 isComplete 出现），这里无需再读顶层进度状态
            onDismiss = {
                showConsistencyDialog = false
                // 用户主动关闭结果弹窗 → 清除进度（结果常驻，手动关闭而非自动消失）
                viewModel.clearConsistencyCheckResult()
            },
            onBackground = { showConsistencyDialog = false },
            onLogin = {
                showConsistencyDialog = false
                onNavigateToDoubanLogin()
            },
            onRetry = { viewModel.startManualConsistencyCheck() },
            onBackgroundUnavailable = {
                Toast.makeText(
                    context,
                    context.getString(R.string.douban_sync_background_unavailable),
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    // 未登录豆瓣时点「重新同步豆瓣」:弹确认框引导前往登录
    if (showDoubanLoginPrompt) {
        AppAlertDialog(
            onDismissRequest = { showDoubanLoginPrompt = false },
            title = stringResource(R.string.settings_douban_not_logged_in_title),
            message = stringResource(R.string.settings_douban_not_logged_in_message),
            confirm = DialogAction(
                label = stringResource(R.string.settings_douban_not_logged_in_login),
                onClick = {
                    showDoubanLoginPrompt = false
                    onNavigateToDoubanLogin()
                }
            ),
            dismiss = DialogAction(
                label = stringResource(R.string.common_cancel),
                onClick = { showDoubanLoginPrompt = false }
            )
        )
    }

    if (showTraktLoginPrompt) {
        AppAlertDialog(
            onDismissRequest = { showTraktLoginPrompt = false },
            title = stringResource(R.string.consistency_check_trakt_required_title),
            message = stringResource(R.string.consistency_check_trakt_required_message),
            confirm = DialogAction(
                label = stringResource(R.string.consistency_check_trakt_required_login),
                onClick = {
                    showTraktLoginPrompt = false
                    onTraktLogin()
                }
            ),
            dismiss = DialogAction(
                label = stringResource(R.string.common_cancel),
                onClick = { showTraktLoginPrompt = false }
            )
        )
    }

    consistencyCheckBlocker?.let { blocker ->
        AppAlertDialog(
            onDismissRequest = { consistencyCheckBlocker = null },
            title = stringResource(R.string.consistency_check_blocked_title),
            message = stringResource(
                when (blocker) {
                    ConsistencyCheckBlocker.DOUBAN_MODE -> R.string.consistency_check_blocked_douban_mode
                    ConsistencyCheckBlocker.DOUBAN_SYNC_RUNNING -> R.string.consistency_check_blocked_sync
                    else -> R.string.consistency_check_already_running
                }
            ),
            confirm = DialogAction(
                label = stringResource(R.string.common_confirm),
                onClick = { consistencyCheckBlocker = null }
            )
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
    modifier: Modifier = Modifier,
    hazeState: dev.chrisbanes.haze.HazeState? = null
) {
    val isDark = isAppDarkTheme()
    // BLUR 模式列表卡片不再各自开一层离屏做真模糊，改用更实的填充；GLASS 模式不变。
    val realBlur = LocalVisualEffectMode.current == VisualEffectMode.GLASS
    NeumorphicFrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            // 调用方的修饰符挂在外边距之内，量到的是卡片可见区域而不是整行宽度。
            .then(modifier)
            .clip(RoundedCornerShape(SettingsEntryCardCorner))
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { onClick() },
        isDark = isDark,
        shape = RoundedCornerShape(SettingsEntryCardCorner),
        backgroundColor = if (realBlur) {
            if (isDark) GlassFillDarkSubtle else Color.White.copy(alpha = 0.70f)
        } else {
            if (isDark) MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
            else Color.White.copy(alpha = 0.82f)
        },
        borderColor = if (isDark) GlassBorderDarkSubtle
                      else Color(0xFFE0E5EC).copy(alpha = 0.9f),
        elevation = 6.dp,
        blurRadius = 18.dp,
        hazeState = if (realBlur) hazeState else null,
        hazeStyle = HazeMaterials.thin(),
        glassRole = GlassSurfaceRole.Card
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
 * 搜索源入口卡片（独立管理页入口）。
 * 与 MarkRecordsEntryCard 风格保持一致，点击跳转搜索源管理页。
 */
@Composable
private fun SearchSourcesEntryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: dev.chrisbanes.haze.HazeState? = null
) {
    val isDark = isAppDarkTheme()
    // BLUR 模式列表卡片不再各自开一层离屏做真模糊，改用更实的填充；GLASS 模式不变。
    val realBlur = LocalVisualEffectMode.current == VisualEffectMode.GLASS
    NeumorphicFrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            // 调用方的修饰符挂在外边距之内，量到的是卡片可见区域而不是整行宽度。
            .then(modifier)
            .clip(RoundedCornerShape(SettingsEntryCardCorner))
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { onClick() },
        isDark = isDark,
        shape = RoundedCornerShape(SettingsEntryCardCorner),
        backgroundColor = if (realBlur) {
            if (isDark) GlassFillDarkSubtle else Color.White.copy(alpha = 0.70f)
        } else {
            if (isDark) MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
            else Color.White.copy(alpha = 0.82f)
        },
        borderColor = if (isDark) GlassBorderDarkSubtle
                      else Color(0xFFE0E5EC).copy(alpha = 0.9f),
        elevation = 6.dp,
        blurRadius = 18.dp,
        hazeState = if (realBlur) hazeState else null,
        hazeStyle = HazeMaterials.thin(),
        glassRole = GlassSurfaceRole.Card
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
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.search_sources_manage_entry_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.search_sources_manage_entry_subtitle),
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
 * 数据与隐私入口卡片（独立管理页入口）。
 * 与 SearchSourcesEntryCard 风格保持一致，点击跳转「数据与隐私」页。
 */
@Composable
private fun PrivacyEntryCard(
    onClick: () -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState? = null
) {
    val isDark = isAppDarkTheme()
    // BLUR 模式列表卡片不再各自开一层离屏做真模糊，改用更实的填充；GLASS 模式不变。
    val realBlur = LocalVisualEffectMode.current == VisualEffectMode.GLASS
    NeumorphicFrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { onClick() },
        isDark = isDark,
        shape = RoundedCornerShape(20.dp),
        backgroundColor = if (realBlur) {
            if (isDark) GlassFillDarkSubtle else Color.White.copy(alpha = 0.70f)
        } else {
            if (isDark) MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
            else Color.White.copy(alpha = 0.82f)
        },
        borderColor = if (isDark) GlassBorderDarkSubtle
                      else Color(0xFFE0E5EC).copy(alpha = 0.9f),
        elevation = 6.dp,
        blurRadius = 18.dp,
        hazeState = if (realBlur) hazeState else null,
        hazeStyle = HazeMaterials.thin(),
        glassRole = GlassSurfaceRole.Card
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
                    imageVector = Icons.Rounded.PrivacyTip,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.privacy_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.privacy_entry_subtitle),
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
 * 开屏每日台词入口卡片（外观分组下，独占一行）。
 *
 * 从开关改成入口：开屏那一句和日签是同一件事的两面，凑成一个二级页才说得清，
 * 见 DailyStampScreen。这里不再显示开关状态——状态在二级页顶栏右侧，点进去即可调整。
 */
@Composable
private fun SplashQuoteEntryCard(
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    SettingsItemCard(
        icon = Icons.Rounded.FormatQuote,
        title = stringResource(R.string.settings_splash_quote),
        subtitle = stringResource(R.string.settings_splash_quote_subtitle),
        // 这一记归 SettingsItemCard 自己的 hapticClickable；开关本身搬去了
        // ui/screen/splashquote 那一页，触感跟着开关走
        onClick = onClick,
        containerColor = containerColor
    )
}

/**
 * 触感反馈档位卡片（外观分组下，独占一行）。
 *
 * 档位与设备状态都在本函数内部收集：切换只重组本卡片。
 *
 * 副标题不是固定的说明文案，而是 [hapticModeSummary] 算出来的那一句 ——
 * 没马达或系统总开关关着时，回显「增强」是在骗人。
 *
 * 本卡片没有 trailing 开关，也不自己发触感：
 * [SettingsItemCard] 的 `hapticClickable` 已经发过一记，外面再发一次就是同一语义背靠背两下
 * （此前带开关的入口卡片正是这个毛病：行点那一记留在 [SettingsItemCard]，
 * 开关卡片只在 `Switch` 的 `onCheckedChange` 上发 toggle）。
 */
@Composable
private fun HapticModeCard(
    viewModel: SettingsViewModel,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val mode by viewModel.hapticMode.collectAsStateWithLifecycle()
    val systemState by viewModel.hapticSystemState.collectAsStateWithLifecycle()
    SettingsItemCard(
        icon = Icons.Rounded.Vibration,
        title = stringResource(R.string.settings_haptic),
        subtitle = hapticSummaryText(hapticModeSummary(mode, systemState)),
        onClick = onClick,
        containerColor = containerColor
    )
}

/** 触感副标题：六种互斥情况各一句，映射本身穷举、不写 else */
@Composable
private fun hapticSummaryText(summary: HapticModeSummary): String = when (summary) {
    HapticModeSummary.NO_VIBRATOR -> stringResource(R.string.settings_haptic_no_vibrator)
    HapticModeSummary.SYSTEM_DISABLED -> stringResource(R.string.settings_haptic_system_disabled)
    HapticModeSummary.FOLLOW_SYSTEM -> stringResource(R.string.settings_haptic_follow_system)
    HapticModeSummary.LIGHT -> stringResource(R.string.settings_haptic_light)
    HapticModeSummary.OFF -> stringResource(R.string.settings_haptic_off)
    HapticModeSummary.BOOST -> stringResource(R.string.settings_haptic_boost)
}

/**
 * 外观分组 item：主题/语言/默认标签页的当前值在本函数内部收集，
 * 值变化（或顶层任意重组）只影响本 item，不波及 LazyColumn 其他 item。
 * 顶层仍保留同名收集供主题/语言/默认页三个对话框使用（低频变化，双收集无碍）。
 */
@Composable
private fun AppearanceGroupItem(
    viewModel: SettingsViewModel,
    hazeState: HazeState?,
    onThemeClick: () -> Unit,
    onAccentColorClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onDefaultTabClick: () -> Unit,
    onHapticClick: () -> Unit,
    onSplashQuoteClick: () -> Unit
) {
    val currentTheme by viewModel.themeMode.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.language.collectAsStateWithLifecycle()
    val currentDefaultTab by viewModel.defaultTab.collectAsStateWithLifecycle()
    SettingsGroupCard(
        title = stringResource(R.string.settings_appearance),
        hazeState = hazeState
    ) {
        val themeName = when (currentTheme) {
            ThemeStorage.MODE_DARK -> stringResource(R.string.theme_dark)
            ThemeStorage.MODE_LIGHT -> stringResource(R.string.theme_light)
            else -> stringResource(R.string.theme_system)
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
                onClick = onThemeClick,
                containerColor = Color.Transparent
            )
            SettingsCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Palette,
                title = stringResource(R.string.settings_accent_color),
                mergeTitleAndSubtitle = true,
                onClick = onAccentColorClick,
                containerColor = Color.Transparent
            )
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
                onClick = onLanguageClick,
                containerColor = Color.Transparent
            )
            SettingsCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Home,
                title = stringResource(R.string.settings_default_tab),
                subtitle = tabName,
                mergeTitleAndSubtitle = true,
                onClick = onDefaultTabClick,
                containerColor = Color.Transparent
            )
        }
        // 开屏每日台词入口：开关与日签日历都在二级页里
        SplashQuoteEntryCard(
            onClick = onSplashQuoteClick,
            containerColor = Color.Transparent
        )
        // 触感反馈三档(默认跟随系统):点开选档位，副标题回显当前档或环境限制
        HapticModeCard(
            viewModel = viewModel,
            onClick = onHapticClick,
            containerColor = Color.Transparent
        )
    }
}

/**
 * 数据管理分组 item（仅登录用户可见）。
 * 导出/导入状态与一致性检查进度在本函数内部收集：导入逐条进度 / 检查进度的高频
 * tick 只重组本 item，不再随顶层收集波及整个 LazyColumn。
 * 回调（同步入口/一致性检查入口）与低频展示状态由顶层传入；捕获顶层低频状态的
 * lambda 只在对应值变化时才会重建，item 内部高频重组期间始终复用同一实例。
 */
@Composable
private fun DataManagementGroupItem(
    viewModel: SettingsViewModel,
    hazeState: HazeState?,
    isDoubanMode: Boolean,
    doubanLoggedIn: Boolean,
    cooldownStatus: CooldownStatus?,
    isDoubanSyncRunning: Boolean,
    onSyncClick: () -> Unit,
    onConsistencyCheckClick: () -> Unit
) {
    val exportImportState by viewModel.exportImportState.collectAsStateWithLifecycle()
    val consistencyCheckState by viewModel.checkProgress.collectAsStateWithLifecycle()
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
    SettingsGroupCard(
        title = stringResource(R.string.settings_data_management),
        hazeState = hazeState
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
                onClick = onSyncClick,
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
                    onClick = onConsistencyCheckClick,
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
                            // syncProgress 为机器码,组合期转本地化文案
                            text = stringResource(progress.resId, *progress.args.toTypedArray()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 关于分组 item：更新信息/最新版本在本函数内部收集，
 * 检查更新状态变化只重组本 item。顶层保留 updateInfo/isCheckingUpdate
 * 收集供 UpdateDialog 与吸顶栏玻璃场景使用（低频，双收集无碍）。
 */
@Composable
private fun AboutGroupItem(
    viewModel: SettingsViewModel,
    hazeState: HazeState?,
    isCheckingUpdate: Boolean,
    onVersionClick: () -> Unit,
    onVersionLongClick: () -> Unit,
    onChangelogClick: () -> Unit,
    onHelpClick: () -> Unit,
    onOpenSourceClick: () -> Unit,
    onFeedbackClick: () -> Unit
) {
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val latestVersion by viewModel.latestVersion.collectAsStateWithLifecycle()
    SettingsGroupCard(
        title = stringResource(R.string.settings_about),
        hazeState = hazeState
    ) {
        AboutItem(
            hasUpdate = updateInfo?.hasUpdate == true,
            latestVersion = latestVersion,
            isCheckingUpdate = isCheckingUpdate,
            onVersionClick = onVersionClick,
            onVersionLongClick = onVersionLongClick,
            onChangelogClick = onChangelogClick,
            onHelpClick = onHelpClick,
            onOpenSourceClick = onOpenSourceClick,
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
    val haptics = rememberAppHaptics()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hapticClickable(
                    // 带勾选态的行：语义按点完之后的新状态定，与下面两行提醒开关同一写法
                    semantic = if (notificationEnabled) HapticSemantic.TOGGLE_OFF
                               else HapticSemantic.TOGGLE_ON
                ) {
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
                    .size(48.dp)
                    .background(
                        color = settingsIconContainerColor(isDark),
                        shape = RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
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
                // 直接拨开关是另一个手势面（与行点一次只命中一个），按新状态发 toggle
                onCheckedChange = { enabled ->
                    haptics.toggle(enabled)
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
                        .hapticClickable(
                            // 带勾选态的行：语义按点完之后的新状态定
                            semantic = if (releaseEnabled) HapticSemantic.TOGGLE_OFF
                                       else HapticSemantic.TOGGLE_ON
                        ) { viewModel.setReleaseReminderEnabled(!releaseEnabled) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
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
                            Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
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
                        onCheckedChange = { haptics.toggle(it); viewModel.setReleaseReminderEnabled(it) },
                        colors = appSwitchColors()
                    )
                }
                GroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .hapticClickable(
                            // 带勾选态的行：语义按点完之后的新状态定
                            semantic = if (newSeasonEnabled) HapticSemantic.TOGGLE_OFF
                                       else HapticSemantic.TOGGLE_ON
                        ) { viewModel.setNewSeasonReminderEnabled(!newSeasonEnabled) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
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
                            Icons.Rounded.LiveTv,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
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
                        onCheckedChange = { haptics.toggle(it); viewModel.setNewSeasonReminderEnabled(it) },
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
 * 关于区块（版本/更新日志/帮助/开源相关 2x2 卡片）。
 * 抽取为独立函数，更新状态变化只重组本函数。
 */
@Composable
private fun AboutItem(
    hasUpdate: Boolean,
    latestVersion: String?,
    isCheckingUpdate: Boolean,
    onVersionClick: () -> Unit,
    onVersionLongClick: () -> Unit,
    onChangelogClick: () -> Unit,
    onHelpClick: () -> Unit,
    onOpenSourceClick: () -> Unit,
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
            onLongClick = onVersionLongClick,
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
            icon = Icons.Rounded.Code,
            title = stringResource(R.string.opensource_title),
            onClick = onOpenSourceClick,
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
    // 第三态：登录过但凭据已失效。以前只有「已登录 / 未登录」两态，失效时账号卡与正常已登录无差别
    val traktConnectionInvalid by viewModel.traktConnectionInvalid.collectAsStateWithLifecycle()
    val doubanCookieInvalid by viewModel.doubanCookieInvalid.collectAsStateWithLifecycle()
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
            } else if (traktConnectionInvalid) {
                AccountExpiredRow(
                    brandLogo = {
                        TraktLogo(
                            contentDescription = stringResource(R.string.settings_account_trakt_label),
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    primaryName = userProfile?.username,
                    onReconnect = onTraktLogin,
                    onLogout = onTraktLogout
                )
            } else {
                TraktLoginPromptRow(onLogin = onTraktLogin)
            }
            GroupDivider()
            val doubanCreds = doubanProfile
            if (doubanLoggedIn && doubanCookieInvalid) {
                AccountExpiredRow(
                    brandLogo = {
                        DoubanLogo(
                            contentDescription = stringResource(R.string.settings_account_douban),
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    primaryName = doubanCreds?.nickname ?: doubanCreds?.userId,
                    onReconnect = onDoubanLogin,
                    onLogout = onDoubanLogout
                )
            } else if (doubanLoggedIn) {
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
