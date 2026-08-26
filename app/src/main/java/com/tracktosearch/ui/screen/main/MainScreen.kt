@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.screen.main

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import com.tracktosearch.data.util.ConnectivityObserver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.local.OnboardingStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.ai.AiRecommendation
import com.tracktosearch.ui.component.LocalIsCurrentTab
import com.tracktosearch.ui.component.AppVisualSurface
import com.tracktosearch.ui.component.GlassSurfaceRole
import com.tracktosearch.ui.component.GlassNavigationTabIndicator
import com.tracktosearch.ui.component.GlassTabIndicator
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.NeumorphicActiveTab
import com.tracktosearch.ui.component.OnboardingOverlay
import com.tracktosearch.ui.component.LocalAmbientMotionActive
import com.tracktosearch.ui.component.PageBackground
import com.tracktosearch.ui.component.LocalBackdrop
import com.tracktosearch.ui.component.LocalBackdropSourceEnabled
import com.tracktosearch.ui.component.VisualSurfaceKind
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.ambientMotionPing
import com.tracktosearch.ui.component.navPanelPressGlow
import com.tracktosearch.ui.component.rememberAmbientMotionState
import com.tracktosearch.ui.component.rememberGlassSelectionBounceScale
import com.tracktosearch.ui.component.rememberNavPillDragState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.MeshPreset
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.screen.discover.DiscoverScreen
import com.tracktosearch.ui.screen.feedback.FeedbackViewModel
import com.tracktosearch.ui.screen.search.SearchScreen
import com.tracktosearch.ui.screen.search.SearchSourceType
import com.tracktosearch.ui.screen.settings.AccentColorDialog
import com.tracktosearch.ui.screen.settings.SettingsScreen
import com.tracktosearch.ui.screen.traktsearch.TraktSearchScreen
import com.tracktosearch.ui.screen.traktsearch.TraktSearchViewModel
import com.tracktosearch.ui.screen.watchlist.MediaUiItem
import com.tracktosearch.ui.screen.watchlist.WatchlistScreen
import com.tracktosearch.ui.navigation.NotificationNavigator
import com.tracktosearch.ui.navigation.SearchNavigator
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.showToast
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** EntryPoint 用于在非 ViewModel 场景获取 TraktRepository（读取用户头像缓存） */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TraktRepositoryEntryPoint {
    fun traktRepository(): TraktRepository
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThemeStorageEntryPoint {
    fun themeStorage(): ThemeStorage
}

/** EntryPoint 用于在 MainScreen 读取豆瓣登录态/资料（豆瓣独立模式下显示头像） */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DoubanAuthStorageEntryPoint {
    fun doubanAuthStorage(): com.tracktosearch.data.local.DoubanAuthStorage
}

/** EntryPoint 用于在 MainScreen 读取网络状态（此前只有 OkHttp 拦截器层消费，UI 从不提示离线） */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ConnectivityObserverEntryPoint {
    fun connectivityObserver(): ConnectivityObserver
}

@Composable
fun MainScreen(
    initialTab: Int = 0,
    isLoggedIn: Boolean,
    /**
     * Trakt 是否已连接(独立于豆瓣登录态)。
     * 用于 AccountItem 精确判断 Trakt 行显示登录还是登出,避免被综合 isLoggedIn(= isTraktConnected || isDoubanLoggedIn) 误判。
     */
    isTraktConnected: Boolean = false,
    /**
     * 是否处于豆瓣独立模式（激活网关 + 已登录豆瓣 + 未连 trakt）。
     * 影响「我的」tab 头像来源：豆瓣模式下取 DoubanAuthStorage.doubanProfile.avatarUrl，
     * trakt 模式下沿用 TraktRepository.getUserProfile。
     */
    isDoubanMode: Boolean = false,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    // Watchlist 条目统一回调，保留豆瓣 ID 和媒体类型。
    onMediaItemClick: ((item: MediaUiItem, inWatchlist: Boolean, isWatched: Boolean) -> Unit)? = null,
    onSearchClick: (keyword: String) -> Unit,
    onNavigateToLogin: () -> Unit,
    onTraktLogin: () -> Unit = { onNavigateToLogin() },
    onStatisticsClick: () -> Unit,
    onMarkRecordsClick: () -> Unit = {},
    onTraktSearch: (type: String, query: String) -> Unit,
    onPersonClick: (tmdbId: Int, name: String, profileUrl: String?, avatarColor: Color?) -> Unit = { _, _, _, _ -> },
    onListClick: (listId: Int, listName: String) -> Unit = { _, _ -> },
    onLogout: () -> Unit,
    onHelpClick: () -> Unit,
    onRestartOnboarding: () -> Unit,
    onFilterDiscoverClick: () -> Unit = {},
    onDoubanResync: () -> Unit = {},
    onNavigateToDoubanLogin: () -> Unit = {},
    onSpiderTest: () -> Unit = {},
    onGlassPilot: () -> Unit = {},
    onFeedbackClick: () -> Unit = {},
    onMessagesClick: () -> Unit = {},
    onSearchSourcesClick: () -> Unit = {},
    onAiRecommendationClick: ((AiRecommendation) -> Unit)? = null
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    val feedbackViewModel: FeedbackViewModel = hiltViewModel()
    val unreadCount by feedbackViewModel.unreadCount.collectAsState()
    LaunchedEffect(Unit) { feedbackViewModel.fetchUnreadCount() }
    var searchSourceType by rememberSaveable { mutableStateOf(SearchSourceType.MOVIE) }
    var traktSearchQuery by rememberSaveable { mutableStateOf("") }
    var traktSearchType by rememberSaveable { mutableStateOf(SearchSourceType.MOVIE) }
    var showTraktSearch by rememberSaveable { mutableStateOf(false) }
    var aiSpriteCenterVisible by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = initialTab) { 4 }
    val aiSpriteCenterVisibleOnCurrentPage = pagerState.currentPage == 0 && aiSpriteCenterVisible
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 新手引导
    val onboardingStorage = remember { OnboardingStorage(context.applicationContext) }
    val onboardingCompleted by onboardingStorage.isCompleted.collectAsState(initial = true)
    val themeSelectionCompleted by onboardingStorage.isThemeSelectionCompleted.collectAsState(initial = true)
    val themeStorage = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ThemeStorageEntryPoint::class.java).themeStorage()
    }
    val currentAccent by themeStorage.accentColor.collectAsState()
    val currentCustomAccentArgb by themeStorage.customAccentArgb.collectAsState()
    val currentVisualEffectMode by themeStorage.visualEffectMode.collectAsState()
    val currentGlassVariant by themeStorage.glassVariant.collectAsState()
    val meshPreset by themeStorage.meshPreset.collectAsState()
    val meshEnabled by themeStorage.meshEnabled.collectAsState()
    var showAccentOnboarding by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }
    val tabRects = remember { mutableStateOf<List<Rect>>(emptyList()) }
    val connectivityObserver = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ConnectivityObserverEntryPoint::class.java
        ).connectivityObserver()
    }
    val density = LocalDensity.current

    // 用户头像：登录后从对应来源获取
    // - 豆瓣模式：从 DoubanAuthStorage.doubanProfile.avatarUrl 读取（StateFlow 直读，登录后即可拿到）
    // - trakt 模式：从 TraktRepository.getUserProfile() 获取（带永久缓存）
    // - GUEST 模式：不显示头像（isLoggedIn=false 时占位）
    val traktRepository = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            TraktRepositoryEntryPoint::class.java
        ).traktRepository()
    }
    val traktProfile by traktRepository.userProfile.collectAsState()
    val doubanAuthStorage = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, DoubanAuthStorageEntryPoint::class.java).doubanAuthStorage()
    }
    val doubanProfile by doubanAuthStorage.doubanProfile.collectAsState()
    LaunchedEffect(isLoggedIn, isDoubanMode) {
        if (isLoggedIn) {
            if (isDoubanMode) {
                // 豆瓣模式：avatarUrl 由 doubanProfile StateFlow 实时驱动（在 NavTabItem 中读取），
                // 这里不触发 Trakt 刷新。
            } else {
                // trakt 模式：从 TraktRepository 获取头像
                traktRepository.getUserProfile()
            }
        }
    }

    // 悬浮导航显隐状态(提前声明,供 LaunchedEffect(onboardingCompleted) 使用)
    var isFabVisible by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(onboardingCompleted, themeSelectionCompleted) {
        if (onboardingCompleted == false) {
            showAccentOnboarding = !themeSelectionCompleted
            showOnboarding = themeSelectionCompleted
            // 重置到搜索页，确保新手引导从搜索页开始
            // 同时强制显示底部导航(若设置页滚动时把它隐藏了)
            isFabVisible = 1f
            scope.launch {
                pagerState.scrollToPage(0)
                selectedTab = 0
            }
        } else if (onboardingCompleted == true && themeSelectionCompleted == false) {
            // 半完成状态卡死修复: onboarding 已完成但主题选择未完成(如进程被杀打断)
            // 仍需触发主题选择对话框,否则用户再也看不到入口
            showAccentOnboarding = true
            showOnboarding = false
        }
    }

    if (showAccentOnboarding) {
        AccentColorDialog(
            currentAccent = currentAccent,
            customAccentArgb = currentCustomAccentArgb,
            onCustomAccentSelected = { argb ->
                scope.launch { themeStorage.setCustomAccent(argb) }
            },
            onAccentSelected = { accent ->
                // 仅持久化色调选择，不关闭引导弹窗；由「完成」按钮统一推进
                scope.launch { themeStorage.setAccentColor(accent) }
            },
            currentMode = currentVisualEffectMode,
            currentVariant = currentGlassVariant,
            onVisualEffectSelected = { mode, variant ->
                // 仅持久化材质选择，不关闭引导弹窗，用户仍需选择强调色
                scope.launch { themeStorage.setVisualEffectSelection(mode, variant) }
            },
            currentMeshPreset = MeshPreset.fromStorage(meshPreset),
            currentMeshEnabled = meshEnabled,
            onMeshSelected = { preset ->
                scope.launch {
                    themeStorage.setMeshEnabled(preset != null)
                    if (preset != null) themeStorage.setMeshPreset(preset.name)
                }
            },
            onDismiss = {
                scope.launch {
                    onboardingStorage.setThemeSelectionCompleted(true)
                    showAccentOnboarding = false
                    showOnboarding = true
                }
            },
            dialogTitle = stringResource(R.string.onboarding_choose_theme)
        )
    }

    // Pager 滑动 → 同步 selectedTab
    LaunchedEffect(pagerState.currentPage) {
        selectedTab = pagerState.currentPage
        if (pagerState.currentPage != 0) {
            aiSpriteCenterVisible = false
        }
    }

    // 通知点击可能发生在 Activity 已经打开时，主动切到 Watchlist 页。
    LaunchedEffect(Unit) {
        NotificationNavigator.pendingTarget.collect { target ->
            if (target != null) {
                pagerState.animateScrollToPage(2)
                selectedTab = 2
            }
        }
    }

    // Widget 点击只保留一个待处理请求，进入主页后切到统一搜索页并消费。
    LaunchedEffect(Unit) {
        SearchNavigator.pending.collect { pending ->
            if (pending) {
                pagerState.scrollToPage(0)
                selectedTab = 0
                SearchNavigator.consume()
            }
        }
    }

    // 获取屏幕宽度用于导航栏宽度计算
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp

    // 双击返回退出
    var lastBackTime by remember { mutableLongStateOf(0L) }
    val view = LocalView.current
    val pressBackAgainText = stringResource(R.string.press_back_again)
    BackHandler(enabled = true) {
        val now = System.currentTimeMillis()
        if (now - lastBackTime < 2000) {
            (context as? android.app.Activity)?.finish()
        } else {
            lastBackTime = now
            context.showToast(pressBackAgainText)
        }
    }

    val fabOffset by animateDpAsState(
        targetValue = if (isFabVisible > 0.5f) 0.dp else 100.dp,
        animationSpec = tween(durationMillis = 200),
        label = "fabOffset"
    )
    // 进入/返回搜索结果页时重置底部导航为可见
    LaunchedEffect(showTraktSearch) {
        isFabVisible = 1f
        aiSpriteCenterVisible = false
    }
    LaunchedEffect(aiSpriteCenterVisible) {
        isFabVisible = 1f
    }

    // Backdrop 采样版本号：滚动时推进，驱动「源重新录制 + 底栏重采」两端同时刷新。
    // 只有消费者重绘是不够的：kyant 的源层（layerBackdrop）不会因子树滚动而重录，
    // 消费者再采到的仍是旧帧，表现为底栏里混着上一次滚动位置的画面。
    val contentSampleVersion = remember { mutableIntStateOf(0) }

    // 滚动期间冻结背景 shader：内容滚动 + 毛玻璃重算已经吃满一帧预算，再叠一层全屏
    // shader 重绘是纯亏，而滚动时眼睛在追内容，背景是否流动基本无感。
    // 复用 AmbientMotionState 的「最近有活动 + 超时归零」语义，idle 取 140ms：
    // 略大于一帧间隔，fling 期间会被持续 ping 住，滚动真正停下才恢复流动。
    // shader 时间是逐帧累加值，冻结只是停止累加，恢复时相位不跳变。
    val scrollMotion = rememberAmbientMotionState(idleDelayMillis = 140L)

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // 滚动即推进采样版本号（所有 tab 都要，早于下面的提前返回）
                if (available.y != 0f) {
                    contentSampleVersion.intValue++
                    scrollMotion.ping()
                }
                // 搜索页（tab 0）且非搜索结果页时始终显示底部导航
                if (pagerState.currentPage == 0 && !showTraktSearch) return androidx.compose.ui.geometry.Offset.Zero
                val delta = available.y
                if (delta < -10 && isFabVisible != 0f) {
                    isFabVisible = 0f
                } else if (delta > 10 && isFabVisible != 1f) {
                    isFabVisible = 1f
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    // Haze 毛玻璃状态
    val hazeState = remember { HazeState() }

    // Tab 数据
    val tabs = listOf(
        TabData(Icons.Rounded.Search, R.string.tab_search),
        TabData(Icons.Rounded.Explore, R.string.tab_discover),
        TabData(Icons.Rounded.Person, R.string.tab_me),
        TabData(Icons.Rounded.Settings, R.string.tab_settings)
    )
    val tabInteractionSources = remember(tabs.size) {
        List(tabs.size) { MutableInteractionSource() }
    }
    val pageBackdropColor = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.background,
        MaterialTheme.colorScheme.primary,
        0.05f
    )
    val isGlassMode = LocalVisualEffectMode.current == VisualEffectMode.GLASS
    // Glass 模式下才需要 backdrop 采样源；blur 模式走 hazeSource，注册 layer 是纯浪费。
    val glowAsBackdrop = isGlassMode && meshEnabled

    // 光晕单独一层：只录 PageBackground，子树内不含任何 drawBackdrop，因此可以安全地
    // 供页面内部的 Glass 控件采样而不会形成 RenderThread 递归。
    val glowBackdrop = rememberLayerBackdrop()

    // 主内容宿主与底栏保持兄弟关系。底栏要同时看到光晕和内容列表，但 shader 只渲染一次：
    // 这里直接把 glowBackdrop 已录好的 GraphicsLayer 贴进来，而不是让光晕再走一遍着色器。
    // 绘制顺序上光晕层是先兄弟，本帧已录完，读到的是当帧内容，不滞后。
    val mainContentBackdrop = rememberLayerBackdrop(
        onDraw = remember(pageBackdropColor, glowBackdrop, glowAsBackdrop) {
            {
                if (glowAsBackdrop) {
                    drawLayer(glowBackdrop.graphicsLayer)
                } else {
                    drawRect(pageBackdropColor)
                }
                drawContent()
            }
        }
    )

    // 底栏 tab 图标层：供选中水滴用 combinedBackdrop 合并采样，使按压折射同时作用于图标。
    val navTabsBackdrop = rememberLayerBackdrop()
    // 底栏面板玻璃成品层（模糊 + 填充，不含内容）。水滴采样它而不是原始页面：
    // 官方 catalog 用一份 alpha(0f) 的面板副本达成同样效果，这里改用 drawBackdrop 的
    // exportedBackdrop，少一次全量 blur。静止（按压进度 0、lens 为 0）时水滴贴出的像素与
    // 面板完全一致，因此看不出边界；直接采原始页面会在水滴里露出未模糊的清晰画面。
    val navPanelBackdrop = rememberLayerBackdrop()

    // kyant LayerBackdrop 没有内容版本状态（只有 layerCoordinates 是 MutableState）：
    // 消费者（底部导航）只在自身重绘时采样已录制的层，源重新录制不会通知消费者。
    // 于是首帧/切 tab 后导航采到的是空层，表现为初始透明，直到滑动改变 fabOffset 触发重绘才出模糊。
    //
    // 不能反过来「源绘制后推进版本号」：底栏本身是 hazeSource(zIndex=1)，底栏重绘会让页面内
    // haze 消费者失效 → 页面重绘 → 又推进版本号 → 死循环（实测空闲 246fps）。
    // 改为定时驱动的单向重采：进入/切换 tab 后 5Hz 推进 tick 约 6s，覆盖数据与海报陆续加载的窗口；
    // 窗口结束后不再产生帧，滚动本身也会让底栏重绘重采。
    // 背景动效停帧信号：mirage 的 shader 时间逐帧累加，跑着就等于整窗满帧重绘。
    // 无指针事件 3s 后停帧，一有触摸立刻恢复。底栏重采也复用这份信号。
    val ambientMotion = rememberAmbientMotionState()
    // 下发给页内组件（玻璃按钮的亮度探针）：static local 的值必须 remember 住，
    // 每次组合换一个新 lambda 会让整棵子树失效。
    val ambientMotionActive = remember(ambientMotion) { { ambientMotion.active } }

    var backdropResampleTick by remember { mutableIntStateOf(0) }
    // 切 tab / 首次进入后的兜底重采窗口：程序化换页与「回顶」按钮的 animateScrollToItem 都不经过
    // NestedScrollConnection，contentSampleVersion 不会推进，底栏会停在换页那一刻的采样上（表现为
    // 整条导航透明、底下文字清晰可见）。这段窗口负责把陆续加载进来的数据与海报刷进底栏采样。
    //
    // 窗口内改用「先密后疏」：前 1.5s 用 5Hz（换页直后布局与首屏图变化最密集），之后到 6s 用 2Hz。
    // 覆盖时长不变，但这段窗口产生的帧数从 30 降到 16。这不是省下几次采样的问题——GLASS 下每一次
    // 重采都要把整页内容再光栅化一遍（见下），而空闲时这些帧全是白烧：实测 GLASS 静置 20s 渲染
    // 30 帧、每帧 29-36ms，BLUR 静置是 0 帧。1.5s 后把间隔放宽到 500ms，对一层模糊背景的滞后
    // 完全看不出来。
    var resampleElapsedMs by remember { mutableIntStateOf(Int.MAX_VALUE) }
    LaunchedEffect(pagerState.currentPage) {
        resampleElapsedMs = 0
    }
    // 单一 ticker。
    //
    // 原先是两个各自 delay(200) 的 LaunchedEffect：一个跑「切 tab 后 6s」窗口，另一个在
    // ambientMotion.active（最后一次指针事件后 3s 内）期间无限循环。两个循环推进同一个 tick，
    // 合并成一个后行为不变、少一处相位漂移隐患。
    //
    // 关键成本（从 kyant backdrop 2.0.0 的 LayerBackdropNode.draw 字节码确认）：该节点每次 draw
    // 会先 drawContent() 画到屏幕，再 recordLayer() 把同样的内容录进 GraphicsLayer，录制尺寸取
    // DrawScope.size，即整页全屏，库没有留降分辨率或限区域的入口。所以 GLASS 相对 BLUR 的固定
    // 开销就是「每帧多一次全屏光栅化」，tick 每推进一次就买一次。能省的只有次数。
    LaunchedEffect(isGlassMode, ambientMotion) {
        if (!isGlassMode) return@LaunchedEffect
        while (true) {
            val dense = resampleElapsedMs < 1_500
            val step = if (dense) 200 else 500
            delay(step.toLong())
            val windowActive = resampleElapsedMs < 6_000
            if (windowActive) {
                resampleElapsedMs += step
            }
            if (windowActive || ambientMotion.active) {
                backdropResampleTick++
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 背景动效的停帧开关：Initial pass 抢先看到本页所有指针事件，不消费
                .ambientMotionPing(ambientMotion)
                .nestedScroll(nestedScrollConnection)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState, zIndex = 0f)
            ) {
                // 页面背景为彩色弥散光晕层：预设与开关由设置页持久化，经主题存储驱动。
                // preferredFrameRate 只是刷新率投票，实测压不住（会被玻璃消费者图层盖掉），
                // 真正的省电靠 ambientMotion：没人操作就把 shader 停帧。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (glowAsBackdrop) Modifier.layerBackdrop(glowBackdrop) else Modifier
                        )
                        .preferredFrameRate(30f)
                ) {
                    PageBackground(
                        modifier = Modifier.fillMaxSize(),
                        preset = MeshPreset.fromStorage(meshPreset),
                        enabled = meshEnabled,
                        // 无人操作 3s 后停帧（ambientMotion），滚动进行中也停帧（scrollMotion）。
                        motionActive = { ambientMotion.active && !scrollMotion.active },
                    )
                }

                // 主内容 backdrop 与页面级 backdrop 是两个独立实例：前者供底栏采样整页画面，
                // 后者供当前页的 Glass 控件采样内容列表。只开放当前页的 source，避免隐藏页
                // 同时写入共享 backdrop；各页面自身仍必须把 source 与 Glass overlay 分开。
                HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = false,
                        // 4 个 tab 页全部保持组合（2 = 当前页两侧各 2 页）：
                        // 远 Tab 切换（如 搜索↔设置）不再销毁/重建整页，消除切换瞬间的
                        // 200-300ms 组合尖峰；各页状态收集已下沉到 item/子 composable，
                        // 后台页的隐藏重组成本极低。滚动位置由 rememberSaveable 的
                        // grid/list state 跨销毁保留，此改动只省去重建不改变行为。
                        beyondViewportPageCount = 2,
                        modifier = Modifier
                            .fillMaxSize()
                            // 只有 Glass 模式的底栏才通过 backdropOverride 采样这一层。
                            // BLUR 模式底栏走 hazeSource + NeumorphicFrostedSurface，后者根本没有
                            // backdropOverride 入参，这份全屏离屏录制写了没人读——每帧纯浪费，
                            // 与上面 glowBackdrop / navTabsBackdrop 已有的 isGlassMode 门控同理。
                            // 读采样版本号/tick 让本节点 draw 失效也只为驱动 layerBackdrop 重录，
                            // 没有 layerBackdrop 时一并省掉。
                            .then(
                                if (isGlassMode) {
                                    Modifier
                                        .drawWithContent {
                                            @Suppress("UNUSED_EXPRESSION") contentSampleVersion.intValue
                                            @Suppress("UNUSED_EXPRESSION") backdropResampleTick
                                            drawContent()
                                        }
                                        .layerBackdrop(mainContentBackdrop)
                                } else {
                                    Modifier
                                }
                            )
                    ) { page ->
                        val isCurrentPage = page == pagerState.currentPage
                        // 只有当前可见 tab 的 MovieCard 参与 sharedElement 转场，避免 HorizontalPager 常驻的其他 tab 同 tmdbId 海报冲突
                        CompositionLocalProvider(
                            LocalBackdropSourceEnabled provides isCurrentPage,
                            LocalIsCurrentTab provides isCurrentPage,
                            LocalAmbientMotionActive provides ambientMotionActive,
                            // 页内 Glass（搜索框、热词 chip 等）改采样光晕层。原先落到 App 级
                            // BackdropProvider 的空 source，只能采到一块主题平色，看不到光晕。
                            // 光晕层不含 drawBackdrop，不会递归；不适用时保持原值不动。
                            LocalBackdrop provides
                                (if (glowAsBackdrop) glowBackdrop else LocalBackdrop.current)
                        ) {
                            // beyondViewportPageCount=2 让 4 个 tab 常驻组合，语义树里就同时挂着
                            // 4 页的全部节点。无障碍代理每帧要遍历整棵树并给全部节点重排遍历序
                            // （实测 getCurrentSemanticsNodes + setTraversalValues 合计占应用采样
                            // 11-17%），其中 3 页用户根本看不见。这里把非当前页的语义子树整体剪掉：
                            // 只影响无障碍暴露，不动组合/布局/绘制，切回该页时语义自然恢复。
                            Box(
                                modifier = if (isCurrentPage) {
                                    Modifier.fillMaxSize()
                                } else {
                                    Modifier
                                        .fillMaxSize()
                                        .clearAndSetSemantics {}
                                }
                            ) {
                            when (page) {
                    0 -> {
                        if (showTraktSearch) {
                            val mediaType = when (traktSearchType) {
                                SearchSourceType.MOVIE -> MediaType.MOVIE
                                SearchSourceType.SHOW -> MediaType.SHOW
                                SearchSourceType.PERSON -> MediaType.PERSON
                                SearchSourceType.DISK -> MediaType.DISK
                            }
                            key(traktSearchType, traktSearchQuery) {
                                val viewModel: TraktSearchViewModel = hiltViewModel()
                                BackHandler {
                                    showTraktSearch = false
                                }
                                Box(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    TraktSearchScreen(
                                        initialQuery = traktSearchQuery,
                                        type = mediaType,
                                        onBack = { showTraktSearch = false },
                                        onItemClick = { type, traktId, tmdbId, title, imdbId, traktRating ->
                                            when (type) {
                                                MediaType.MOVIE -> onMovieClick(traktId, tmdbId, title, imdbId, traktRating, false, false)
                                                MediaType.SHOW -> onShowClick(traktId, tmdbId, title, imdbId, traktRating, false, false)
                                                else -> {}
                                            }
                                        },
                                        onPersonClick = { tmdbId, name, profileUrl, avatarColor ->
                                            onPersonClick(tmdbId, name, profileUrl, avatarColor)
                                        },
                                        onNavigateToLogin = onNavigateToLogin,
                                        onRecommendationClick = onAiRecommendationClick,
                                        viewModel = viewModel,
                                        inlineMode = true,
                                        externallyControlledAiSpriteCenterVisible = aiSpriteCenterVisibleOnCurrentPage,
                                        onAiSpriteCenterVisibilityChanged = { visible ->
                                            aiSpriteCenterVisible = visible
                                        }
                                    )
                                }
                            }
                        } else {
                            SearchScreen(
                                initialKeyword = "",
                                onSearchClick = onSearchClick,
                                onTraktSearch = { type, query ->
                                    traktSearchType = type
                                    traktSearchQuery = query
                                    showTraktSearch = true
                                },
                                onSpiderTest = onSpiderTest,
                                onMovieClick = onMovieClick,
                                searchSourceType = searchSourceType,
                                onSearchSourceTypeChange = { searchSourceType = it },
                                modifier = Modifier.fillMaxSize(),
                                externallyControlledAiSpriteCenterVisible = aiSpriteCenterVisibleOnCurrentPage,
                                onAiSpriteCenterVisibilityChanged = { visible ->
                                    aiSpriteCenterVisible = visible
                                }
                            )
                        }
                    }
                    1 -> DiscoverScreen(
                        onMovieClick = onMovieClick,
                        onShowClick = onShowClick,
                        onListClick = onListClick,
                        onFilterDiscoverClick = onFilterDiscoverClick,
                        onDoubanLoginClick = onNavigateToDoubanLogin,
                        onTraktLoginClick = onTraktLogin,
                        modifier = Modifier.fillMaxSize()
                    )
                    2 -> {
                        WatchlistScreen(
                            onMovieClick = onMovieClick,
                            onShowClick = onShowClick,
                            onMediaItemClick = onMediaItemClick,
                            onSearchClick = onSearchClick,
                            onTraktSearch = onTraktSearch,
                            onDiscoverClick = { scope.launch { pagerState.scrollToPage(1) } },
                            onNavigateToDoubanLogin = onNavigateToDoubanLogin,
                            onNavigateToLogin = onNavigateToLogin,
                            onTraktLogin = onTraktLogin,
                            onStatisticsClick = onStatisticsClick,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    3 -> SettingsScreen(
                        onLogout = onLogout,
                        isLoggedIn = isLoggedIn,
                        // 传入 Trakt 连接态(独立于综合 isLoggedIn),供 AccountItem 精确判断 Trakt 行
                        isTraktConnected = isTraktConnected,
                        onHelpClick = onHelpClick,
                        onRestartOnboarding = onRestartOnboarding,
                        onDoubanResync = {
                            // 触发同步后切换到 Watchlist tab，让用户通过横幅查看进度
                            // 同时启动前台 Service 进入后台模式（通知栏显示进度）
                            // 同步弹窗在 Watchlist 页点击横幅打开，转后台时消除弹窗
                            com.tracktosearch.service.DoubanSyncService.start(context)
                            scope.launch { pagerState.scrollToPage(2) }
                        },
                        onNavigateToDoubanLogin = onNavigateToDoubanLogin,
                        onNavigateToLogin = onNavigateToLogin,
                        onTraktLogin = onTraktLogin,
                        onStatisticsClick = onStatisticsClick,
                        onMarkRecordsClick = onMarkRecordsClick,
                        onFeedbackClick = onFeedbackClick,
                        onMessagesClick = onMessagesClick,
                        onGlassPilot = onGlassPilot,
                        onSearchSourcesClick = onSearchSourcesClick,
                        modifier = Modifier.fillMaxSize()
                    )
                            }
                            } // Box：非当前页语义剪枝
                        } // CompositionLocalProvider
                    }
            }

            // 悬浮底部导航：Glass 只在外层采样，Blur 仍由 AppVisualSurface 分发到拟态实现。
            val navBarWidthFraction = 0.92f
            val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val navBarShape = RoundedCornerShape(31.dp)
            val isDark = isAppDarkTheme()
            val navigationScene = glassSceneForContent(
                contentCount = when (selectedTab) {
                    0 -> 34
                    1 -> 60
                    2 -> 72
                    else -> 24
                },
                readabilityDemand = when (selectedTab) {
                    0 -> 0.86f
                    1 -> 0.72f
                    2 -> 0.84f
                    else -> 0.88f
                },
                ambientColor = MaterialTheme.colorScheme.background,
                contentCapacity = 72
            )
            // 底栏几何：水滴位置/拖动换算都用这些 px 值，避免在绘制阶段再做 Dp 转换。
            val navDensity = LocalDensity.current
            val navTabWidthDp = (screenWidthDp.dp * navBarWidthFraction - 16.dp) / tabs.size
            val navTabWidthPx = with(navDensity) { navTabWidthDp.toPx() }
            val navRowPaddingPx = with(navDensity) { 8.dp.toPx() }
            val navBarWidthPx = with(navDensity) { (screenWidthDp.dp * navBarWidthFraction).toPx() }
            val navPanelMaxOffsetPx = with(navDensity) { 4.dp.toPx() }
            // 按住水滴可以左右拖动切 tab（对齐官方 catalog 的 DampedDragAnimation）：
            // 松手吸附到最近的 tab 并驱动 Pager。
            val navPillDrag = rememberNavPillDragState(
                tabCount = tabs.size,
                selectedIndex = selectedTab,
                tabWidthPx = { navTabWidthPx },
                panelWidthPx = { navBarWidthPx },
                maxPanelOffsetPx = { navPanelMaxOffsetPx },
                onIndexSettled = { index ->
                    if (selectedTab != index) {
                        view.performHaptic(HapticType.CLICK)
                        scope.launch { pagerState.scrollToPage(index) }
                    }
                }
            )
            if (isMainBottomNavigationVisible(pagerState.currentPage, aiSpriteCenterVisible)) {
                AppVisualSurface(
                    kind = VisualSurfaceKind.Glass,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 拖动水滴时整块面板给一个上限 4dp 的橡皮筋位移（官方 catalog 的 panelOffset）
                        .graphicsLayer { translationX = navPillDrag.panelOffsetPx }
                        .drawWithContent {
                            // 读取 tick 与采样版本号建立 draw 阶段快照依赖：源重录后本节点随之
                            // 重绘并重采 backdrop 层，修复初始透明与滚动后混入旧帧画面。
                            @Suppress("UNUSED_EXPRESSION") backdropResampleTick
                            @Suppress("UNUSED_EXPRESSION") contentSampleVersion.intValue
                            drawContent()
                        }
                        .padding(bottom = navBarHeight + 8.dp)
                        .offset(y = fabOffset)
                        .fillMaxWidth(navBarWidthFraction)
                        .height(62.dp)
                        .hazeSource(state = hazeState, zIndex = 1f),
                    // 让底部导航作为前景层，effect 明确采样 zIndex=0 的页面内容。
                    shape = navBarShape,
                    role = GlassSurfaceRole.BottomNavigation,
                    backgroundColor = if (LocalVisualEffectMode.current == VisualEffectMode.GLASS) {
                        // Glass 净填充 alpha 由 BottomNavigation token 决定（对齐官方 0.4），
                        // 这里必须传不透明色，否则会与 token 再乘一次导致填充过淡。
                        MaterialTheme.colorScheme.surface
                    } else {
                        Color.Transparent
                    },
                    borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.45f),
                    hazeState = hazeState,
                    // 与上面 layerBackdrop 的门控保持一致：BLUR 模式不录这一层，也就不该再传，
                    // 免得日后 blur 分支接上 backdropOverride 时读到一层没录过的空层。
                    backdropOverride = if (isGlassMode) mainContentBackdrop else null,
                    exportedBackdrop = if (isGlassMode) navPanelBackdrop else null,
                    interactionSource = navPillDrag.interactionSource,
                    // 底部导航自身作为 zIndex=1 的 source，effect 只采样 zIndex=0 的页面内容，
                    // 避免导航栏模糊自身导致重复模糊与无谓开销（Haze 最重的叠加场景）
                    sourceSelection = HazeSourceSelection.Behind.where { source -> source.zIndex < 1f },
                    scene = navigationScene
                ) {
                val rowPadding = 8.dp

                // 选中水滴采样「面板玻璃成品 + tab 图标层」：静止时与面板像素一致，
                // 按压时 lens 同时折射面板画面与图标（官方 catalog 的 combinedBackdrop 做法）。
                val navSelectionBackdrop = rememberCombinedBackdrop(navPanelBackdrop, navTabsBackdrop)
                // 水滴几何：位置由拖动状态的浮点 tab 序号驱动，写在 graphicsLayer 里只走绘制阶段。
                val pillModifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(navTabWidthDp)
                    .height(48.dp)
                    .padding(horizontal = 4.dp)

                // Blur 模式的拟态药丸是不透明填充，压在图标上会挡住图标，仍画在图标下方。
                if (!isGlassMode) {
                    val indicatorSelectionScale = rememberGlassSelectionBounceScale(selectedTab)
                    Box(
                        modifier = pillModifier
                            .graphicsLayer {
                                translationX = navRowPaddingPx + navTabWidthPx * navPillDrag.value
                            }
                            .scale(indicatorSelectionScale),
                        contentAlignment = Alignment.Center
                    ) {
                        NeumorphicActiveTab(
                            modifier = Modifier.fillMaxSize(),
                            isDark = isDark,
                            shape = RoundedCornerShape(24.dp)
                        )
                    }
                }

                // Tab 内容
                // 同时注册为 tab 图标层 source：Glass 模式下水滴画在图标上方，靠 combinedBackdrop
                // 把图标原位重绘出来（官方做法），按压折射时图标随之变形。该层只含图标行与按压高光、
                // 不含水滴，因此不构成自采样环。
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (isGlassMode) Modifier.layerBackdrop(navTabsBackdrop) else Modifier)
                        .then(
                            if (isGlassMode) {
                                Modifier.navPanelPressGlow(
                                    progress = { navPillDrag.pressProgress },
                                    centerX = {
                                        navRowPaddingPx + navTabWidthPx * (navPillDrag.value + 0.5f)
                                    }
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = rowPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val isSelected = selectedTab == index
                        // "我的"tab(index=2)登录后显示用户头像：
                        // - 豆瓣模式：doubanProfile.avatarUrl（StateFlow 实时）
                        // - trakt 模式：TraktRepository 缓存资料
                        val avatarUrl = if (index == 2 && isLoggedIn) {
                            if (isDoubanMode) {
                                doubanProfile?.avatarUrl
                            } else {
                                traktProfile?.images?.avatar?.full?.takeIf { it.isNotBlank() }
                            }
                        } else null
                        NavTabItem(
                            icon = tab.icon,
                            labelRes = tab.labelRes,
                            selected = isSelected,
                            weight = 1f,
                            avatarUrl = avatarUrl,
                            badgeCount = if (index == 3) unreadCount else 0,
                            interactionSource = tabInteractionSources[index],
                            onClick = {
                                if (selectedTab != index) {
                                    view.performHaptic(HapticType.CLICK)
                                    scope.launch { pagerState.scrollToPage(index) }
                                }
                            },
                            onPositioned = { rect ->
                                val current = tabRects.value.toMutableList()
                                while (current.size <= index) current.add(Rect.Zero)
                                current[index] = rect
                                tabRects.value = current
                            }
                        )
                    }
                }

                // 水滴与拖动手势层，始终画在最上：官方 catalog 也把它声明在图标行之后，
                // 这样按住/拖动的指针先落到水滴上（inspectDragGestures 不消费事件，
                // 点其他 tab 仍走各自的 clickable）。Glass 模式下水滴同时是可视层。
                Box(
                    modifier = pillModifier
                        .graphicsLayer {
                            translationX = navRowPaddingPx + navTabWidthPx * navPillDrag.value
                        }
                        .then(navPillDrag.modifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (isGlassMode) {
                        GlassNavigationTabIndicator(
                            backdrop = navSelectionBackdrop,
                            modifier = Modifier.fillMaxSize(),
                            isDark = isDark,
                            shape = RoundedCornerShape(24.dp),
                            // 按住/拖动时水滴按官方配方逐步给出折射/高光/阴影，
                            // 缩放与速度挤压写进 drawBackdrop 的 layerBlock，背景不跟着拉伸
                            pressProgress = { navPillDrag.pressProgress },
                            pillLayerBlock = { navPillDrag.applyPillScale(this) }
                        )
                    }
                }
                }
            }

            // 全局离线横幅：网络断开时给一条常驻提示，说明看到的是本地缓存。
            // 注意：这里只提示状态，不弹 Toast，避免与启动期的离线提示重复打扰。
            val networkStatus by connectivityObserver.status.collectAsState()
            val offlineBannerBottom = WindowInsets.navigationBars
                .asPaddingValues()
                .calculateBottomPadding() + 78.dp
            androidx.compose.animation.AnimatedVisibility(
                visible = networkStatus == ConnectivityObserver.NetworkStatus.OFFLINE,
                enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(4f)
            ) {
                // 贴在悬浮底栏上方的胶囊：顶部放会压住各 Tab 的大标题
                Row(
                    modifier = Modifier
                        .padding(bottom = offlineBannerBottom, start = 24.dp, end = 24.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.auth_offline_mode),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // 新手引导遮罩：搜索、发现、我的、设置四个 Tab 依次高亮。
            // 设置 Tab 补进引导：账号、未读消息、观看统计的入口都只在那一屏。
            val onboardingTabMap = listOf(0, 1, 2, 3)
            val onboardingRects = tabRects.value.take(4)
            val onboardingRectsReady = onboardingRects.size == 4 && onboardingRects.none { it == Rect.Zero }
            // Tab 位置量不到时原来直接不渲染引导，用户可能永远看不到、标记也不会重置。
            // 现在等一会儿再退化为无高亮的纯信息步骤，至少把四个 Tab 讲清楚。
            var onboardingMeasureTimedOut by remember { mutableStateOf(false) }
            LaunchedEffect(showOnboarding, onboardingRectsReady) {
                if (showOnboarding && !onboardingRectsReady) {
                    delay(1500)
                    onboardingMeasureTimedOut = true
                } else {
                    onboardingMeasureTimedOut = false
                }
            }
            if (showOnboarding && (onboardingRectsReady || onboardingMeasureTimedOut)) {
                OnboardingOverlay(
                    targetRects = if (onboardingRectsReady) onboardingRects else List(4) { Rect.Zero },
                    titles = listOf(
                        stringResource(R.string.onboarding_step1_title),
                        stringResource(R.string.onboarding_step2_title),
                        stringResource(R.string.onboarding_step3_title),
                        stringResource(R.string.onboarding_step4_title)
                    ),
                    descriptions = listOf(
                        stringResource(R.string.onboarding_step1_desc),
                        stringResource(R.string.onboarding_step2_desc),
                        stringResource(R.string.onboarding_step3_desc),
                        stringResource(R.string.onboarding_step4_desc)
                    ),
                    onComplete = {
                        showOnboarding = false
                        scope.launch { onboardingStorage.setCompleted(true) }
                        // 完成后跳转到搜索页
                        scope.launch {
                            pagerState.scrollToPage(0)
                            selectedTab = 0
                        }
                        // 这里原来会在 1.2s 后弹定位权限。那个权限只服务白云主题彩蛋，新用户此刻
                        // 对它零感知，而且「点完成会弹、点跳过不弹」本身就不一致。改为用户主动点
                        // 白云时再申请（CloudThemeManager.onCloudClicked 未授权时就会弹）。
                    },
                    onSkip = {
                        showOnboarding = false
                        scope.launch { onboardingStorage.setCompleted(true) }
                    },
                    onStepChanged = { step ->
                        val targetTab = onboardingTabMap.getOrNull(step)
                        if (targetTab != null && targetTab >= 0) {
                            scope.launch { pagerState.scrollToPage(targetTab) }
                            selectedTab = targetTab
                        }
                    }
                )
            }

        }
    }
}

private data class TabData(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val labelRes: Int
)

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavTabItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    selected: Boolean,
    weight: Float,
    onClick: () -> Unit,
    avatarUrl: String? = null,
    badgeCount: Int = 0,
    interactionSource: MutableInteractionSource,
    onPositioned: (Rect) -> Unit = {}
) {
    val context = LocalContext.current
    val visualEffectMode = LocalVisualEffectMode.current
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val selectedScale by animateFloatAsState(
        targetValue = when {
            visualEffectMode != VisualEffectMode.GLASS -> 1f
            isPressed -> 0.99f
            selected -> 1.015f
            isHovered || isFocused -> 1.01f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "navTabScale"
    )
    // 浅色模式下 primary 偏暗（Red700），选中态提亮饱和度与亮度，提升鲜亮感
    val selectedColor = if (selected) {
        val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        val base = MaterialTheme.colorScheme.primary
        if (isLight) {
            // RGB→HSL 手动转换，提亮饱和度与亮度后转回
            val r = base.red; val g = base.green; val b = base.blue
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            val l = (max + min) / 2f
            val s = if (max == min) 0f else {
                val d = max - min
                if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            }
            val h = when {
                max == min -> 0f
                max == r -> (((g - b) / (max - min)) + (if (g < b) 6f else 0f)) * 60f
                max == g -> (((b - r) / (max - min)) + 2f) * 60f
                else -> (((r - g) / (max - min)) + 4f) * 60f
            }
            // 提亮后的 HSL → RGB
            val newS = (s + 0.14f).coerceIn(0f, 1f)
            val newL = (l + 0.07f).coerceIn(0f, 1f)
            val c = (1f - kotlin.math.abs(2f * newL - 1f)) * newS
            val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
            val m = newL - c / 2f
            val (r1, g1, b1) = when {
                h < 60f -> Triple(c, x, 0f)
                h < 120f -> Triple(x, c, 0f)
                h < 180f -> Triple(0f, c, x)
                h < 240f -> Triple(0f, x, c)
                h < 300f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            Color(r1 + m, g1 + m, b1 + m, base.alpha)
        } else {
            base
        }
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val contrastHalo = if (visualEffectMode == VisualEffectMode.GLASS) {
        if (selectedColor.luminance() < 0.5f) {
            Color.White.copy(alpha = if (selected) 0.60f else 0.42f)
        } else {
            Color.Black.copy(alpha = if (selected) 0.58f else 0.40f)
        }
    } else {
        Color.Transparent
    }
    Column(
        modifier = Modifier
            .weight(weight)
            .fillMaxSize()
            .scale(selectedScale)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                onPositioned(bounds)
            }
            .clip(RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (avatarUrl != null) {
            // 登录后"我的"tab 显示用户头像
            AsyncImage(
                model = ImageRequest.Builder(context).data(avatarUrl).crossfade(true).build(),
                contentDescription = stringResource(labelRes),
                modifier = Modifier
                    .size(24.dp)
                    .offset(y = 0.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            BadgedBox(badge = { if (badgeCount > 0) { Badge { Text(if (badgeCount > 99) "99+" else badgeCount.toString()) } } }) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .offset(y = 0.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (contrastHalo.alpha > 0f) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = contrastHalo,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = stringResource(labelRes),
                        tint = selectedColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(0.dp))
        Text(
            text = stringResource(labelRes),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = selectedColor,
            style = MaterialTheme.typography.labelMedium.copy(
                shadow = if (contrastHalo.alpha > 0f) {
                    Shadow(
                        color = contrastHalo,
                        offset = Offset(0f, 1.5f),
                        blurRadius = 2.5f
                    )
                } else {
                    null
                }
            ),
            maxLines = 1
        )
    }
}


