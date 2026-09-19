package com.tracktosearch.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tracktosearch.BuildConfig
import com.tracktosearch.DeepLinkNavigator
import com.tracktosearch.OAuthCallback
import com.tracktosearch.R
import com.tracktosearch.data.local.OnboardingStorage
import com.tracktosearch.data.ai.AiRecommendation
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.auth.hasGatewayAccess
import com.tracktosearch.data.util.ConnectivityObserver
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.ShareCodec
import com.tracktosearch.data.remote.trakt.TraktAuthManager
import com.tracktosearch.data.remote.trakt.TraktConnectionState
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.SyncMode
import com.tracktosearch.data.repository.WatchlistMediaType
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.BackdropProvider
import com.tracktosearch.ui.component.UpdateDialog
import com.tracktosearch.ui.haptic.PopupShowEffect
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.screen.crashlog.CrashLogDetailScreen
import com.tracktosearch.ui.screen.detail.DetailScreen
import com.tracktosearch.ui.screen.discoverfilter.DiscoverFilterScreen
import com.tracktosearch.ui.screen.feedback.FeedbackScreen
import com.tracktosearch.ui.screen.feedback.NewFeedbackScreen
import com.tracktosearch.ui.screen.feedback.FeedbackDetailScreen
import com.tracktosearch.ui.screen.feedback.FeedbackViewModel
import com.tracktosearch.ui.screen.messages.MessagesScreen
import com.tracktosearch.ui.screen.douban.DoubanItemDetailScreen
import com.tracktosearch.ui.screen.douban.DoubanLoginScreen
import com.tracktosearch.ui.screen.douban.DoubanSpiderTestScreen
import com.tracktosearch.ui.screen.pilot.GlassEnginePilotScreen
import com.tracktosearch.ui.screen.searchsource.EditorMode
import com.tracktosearch.ui.screen.searchsource.ImportSourceDialog
import com.tracktosearch.ui.screen.searchsource.SearchSourceEditorScreen
import com.tracktosearch.ui.screen.searchsource.SearchSourceEditorViewModel
import com.tracktosearch.ui.screen.searchsource.SearchSourcesScreen
import com.tracktosearch.ui.screen.searchsource.SearchSourcesViewModel
import com.tracktosearch.ui.screen.douban.DoubanSyncViewModel
import com.tracktosearch.ui.screen.help.HelpScreen
import com.tracktosearch.ui.screen.help.HelpSections
import com.tracktosearch.ui.screen.opensource.OpenSourceScreen
import com.tracktosearch.ui.screen.privacy.PrivacyScreen
import com.tracktosearch.ui.screen.listdetail.TraktListDetailScreen
import com.tracktosearch.ui.screen.login.ActivationLoginScreen
import com.tracktosearch.ui.screen.main.ConnectivityObserverEntryPoint
import com.tracktosearch.ui.screen.main.MainScreen
import com.tracktosearch.ui.screen.markrecord.MarkRecordScreen
import com.tracktosearch.ui.screen.person.PersonScreen
import com.tracktosearch.ui.screen.search.SearchScreen
import com.tracktosearch.ui.screen.ai.AI_DOUBAN_NAV_PREFIX
import com.tracktosearch.ui.screen.statistics.StatisticsScreen
import com.tracktosearch.ui.screen.traktsearch.TraktSearchScreen
import com.tracktosearch.ui.screen.watchlist.WatchlistViewModel
import com.tracktosearch.ui.theme.LocalMainColorScheme
import com.tracktosearch.ui.theme.VintagePaperPage
import com.tracktosearch.ui.theme.floatingDialogColor
import com.tracktosearch.data.util.CurrentPageHolder
import com.tracktosearch.data.util.StartupTrace
import com.tracktosearch.data.util.UserActionTracker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import javax.inject.Inject

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DefaultTabEntryPoint {
    fun defaultTabStorage(): com.tracktosearch.data.local.DefaultTabStorage
}

/** EntryPoint 用于在 AppNavigation 读取豆瓣登录态（计算豆瓣独立模式） */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DoubanAuthStorageEntryPoint {
    fun doubanAuthStorage(): DoubanAuthStorage
}

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val DETAIL = "detail/{type}/{traktId}/{tmdbId}/{title}/{imdbId}/{traktRating}?inWatchlist={inWatchlist}&isWatched={isWatched}&doubanId={doubanId}&posterUrl={posterUrl}&year={year}"
    const val SEARCH = "search/{keyword}"
    const val PERSON = "person/{personId}/{personName}/{profileUrl}"
    const val STATISTICS = "statistics"
    const val DAILY_STAMP = "dailyStamp"
    const val TRAKT_SEARCH = "traktSearch/{type}/{query}"
    // section 是可选参数：不带参数导航用 helpRoute()，功能页跳对应段用 helpRoute(HelpSections.X)
    const val HELP = "help?section={section}"
    const val OPEN_SOURCE = "openSource"
    /**
     * 榜单详情页。
     *
     * morph 表示这次进入是否有可配对的源侧卡片：发现页「社区热门列表」那几张卡片会做容器变形，
     * 而「查看全部」弹窗里的同名条目在 ModalBottomSheet 自己的窗口里，配不上共享元素。
     * 带进路由是因为两条路的来源 route 都是 main，导航层只能靠这个参数区分要不要叠页面淡入。
     * 用路径段而不是 query：转场 lambda 里要靠它做判断，路径段一定会被解析出来。
     */
    const val LIST_DETAIL = "listDetail/{listId}/{listName}/{morph}"

    /**
     * 影视筛选页。
     *
     * entry 是入口标识：`card` 表示从发现页底部的入口卡片进来，那条路要做容器变形；`icon` 是右上角
     * 漏斗图标，它与筛选页顶栏没有共同内容，不参与配对。带进路由是因为导航层要据此决定叠不叠
     * 页面级淡入淡出，而这件事只有 NavHost 能做，页面内部的状态它读不到。
     */
    const val DISCOVER_FILTER = "discoverFilter/{entry}"
    const val DOUBAN_LOGIN = "doubanLogin"
    const val DOUBAN_ITEM_DETAIL = "doubanItemDetail/{doubanId}"
    const val DOUBAN_SPIDER_TEST = "doubanSpiderTest"
    const val GLASS_PILOT = "glassPilot"
    const val MARK_RECORDS = "markRecords"
    const val FEEDBACK = "feedback"
    const val FEEDBACK_DETAIL = "feedbackDetail/{feedbackId}?replyId={replyId}"
    const val NEW_FEEDBACK = "newFeedback"
    const val CRASH_LOG_DETAIL = "crashLogDetail/{recordId}"
    const val MESSAGES = "messages"
    const val SEARCH_SOURCES = "searchSources"
    const val SEARCH_SOURCE_EDITOR = "searchSourceEditor/{mode}/{payload}"
    const val PRIVACY = "privacy"

    /** 每日台词：开屏台词开关 + 日签日历，设置页外观分组的二级页 */
    const val SPLASH_QUOTE = "splashQuote"

    /**
     * 走「卡片长成整页」容器变形的目的地，按 route 的首个路径段登记。
     *
     * 这些页面自己用共享边界完成进出，NavHost 不该再给对面那一页叠页面级淡入淡出。两层淡化叠在
     * 一起时两页同时半透明：返回那一下，正在收缩的整页与刚淡入的列表页互相穿透，看起来像列表
     * 卡片里残留了上一页的内容。进入方向同理，只是被不透明的目标页盖住了才没露出来。
     *
     * 存路径段而不是完整 route 模式串：带参数的 route 一改参数（多一段路径、多一个 query）
     * 集合就悄悄失配，而失配的表现只是转场观感退回去，编译和运行都不报错。
     */
    val ContainerMorphRouteIds = setOf(
        "listDetail",
        "discoverFilter",
    )

    /**
     * 设置页子树的 route 首个路径段。
     *
     * 设置层级是一路向下的普通子页，不用共享容器变形；这一组统一走短距离水平滑动 + 淡入淡出，
     * 既让用户感知到前进/返回方向，又避免全宽滑动与 haze、Mesh 背景叠加造成掉帧。
     */
    val SettingsSubtreeRouteIds = setOf(
        "statistics",
        "dailyStamp",
        "markRecords",
        "help",
        "openSource",
        "privacy",
        "splashQuote",
        "feedback",
        "newFeedback",
        "feedbackDetail",
        "crashLogDetail",
        "messages",
        "searchSources",
        "searchSourceEditor",
        "glassPilot",
    )

    /** 取 route 的首个路径段，用于与 [ContainerMorphRouteIds] 比对。 */
    fun routeId(route: String?): String? =
        route?.substringBefore('?')?.substringBefore('/')?.takeIf { it.isNotBlank() }

    /** 当前 route 是否属于设置页子树。 */
    fun isSettingsSubtreeRoute(route: String?): Boolean =
        routeId(route) in SettingsSubtreeRouteIds

    fun helpRoute(section: String? = null): String =
        if (section == null) "help" else "help?section=$section"

    fun searchSourceEditorRoute(mode: String, payload: String = ""): String =
        "searchSourceEditor/$mode/${android.net.Uri.encode(payload.ifBlank { " " }, "")}"

    fun feedbackDetailRoute(feedbackId: String, replyId: String? = null): String =
        "feedbackDetail/$feedbackId?replyId=${replyId ?: ""}"

    fun crashLogDetailRoute(recordId: String): String = "crashLogDetail/$recordId"

    fun doubanItemDetailRoute(doubanId: String): String = "doubanItemDetail/$doubanId"

    /** morph 见 [LIST_DETAIL]：只有发现页那几张列表卡片点进来时为 true。 */
    fun listDetailRoute(listId: Int, listName: String, morph: Boolean = false): String {
        // 空名要占住路径段，否则拼出 `listDetail/1//true`，连续斜杠匹配不上任何 destination。
        // 与 searchSourceEditorRoute 同一处理：空白填一个空格，Uri.encode 后是 `%20`。
        val encodedName = android.net.Uri.encode(listName.ifBlank { " " }, "")
        return "listDetail/$listId/$encodedName/$morph"
    }

    /** entry 见 [DISCOVER_FILTER]，取 `card` 或 `icon`。 */
    fun discoverFilterRoute(entry: String): String = "discoverFilter/$entry"

    fun traktSearchRoute(type: String, query: String): String {
        val encodedQuery = android.net.Uri.encode(query, "")
        return "traktSearch/$type/$encodedQuery"
    }

    /**
     * 详情页路由。
     *
     * posterUrl / year 是「首帧种子」：调用方（列表卡片）已经知道海报和年份时一并带上，
     * 详情页第一帧就能把它们渲染出来，不必等 TMDB 富化回来才从空白弹入。
     * 两者都可省略，省略时详情页退回同步 peek TMDB 内存缓存。
     */
    fun detailRoute(type: String, traktId: Int, tmdbId: Int, title: String, imdbId: String = "", traktRating: Double = 0.0, inWatchlist: Boolean = false, isWatched: Boolean = false, doubanId: String? = null, posterUrl: String? = null, year: Int? = null): String {
        val encodedTitle = android.net.Uri.encode(title, "")
        val encodedImdbId = android.net.Uri.encode(imdbId, "")
        val encodedDoubanId = doubanId?.let { android.net.Uri.encode(it, "") }
        val encodedPosterUrl = posterUrl?.takeIf { it.isNotBlank() }
            ?.let { android.net.Uri.encode(it, "") }
        var route = "detail/$type/$traktId/$tmdbId/$encodedTitle/$encodedImdbId/$traktRating"
        if (inWatchlist || isWatched || encodedDoubanId != null || encodedPosterUrl != null || year != null) {
            val params = mutableListOf<String>()
            if (inWatchlist) params.add("inWatchlist=true")
            if (isWatched) params.add("isWatched=true")
            encodedDoubanId?.let { params.add("doubanId=$it") }
            encodedPosterUrl?.let { params.add("posterUrl=$it") }
            year?.let { params.add("year=$it") }
            route += "?${params.joinToString("&")}"
        }
        return route
    }

    fun searchRoute(keyword: String): String {
        val encodedKeyword = android.net.Uri.encode(keyword, "")
        return "search/$encodedKeyword"
    }

    fun personRoute(personId: Int, personName: String, profileUrl: String): String {
        val encodedName = android.net.Uri.encode(personName, "")
        val encodedProfileUrl = android.net.Uri.encode(profileUrl, "")
        return "person/$personId/$encodedName/$encodedProfileUrl"
    }
}

private fun navigateAiRecommendation(
    navController: androidx.navigation.NavHostController,
    recommendation: AiRecommendation
) {
    val doubanId = recommendation.doubanId?.takeIf { it.isNotBlank() }
    if (doubanId != null && (recommendation.traktId ?: 0) <= 0 && (recommendation.tmdbId ?: 0) <= 0) {
        navController.navigate(Routes.doubanItemDetailRoute(doubanId))
        return
    }
    val type = if (recommendation.mediaType.lowercase() == "show") "show" else "movie"
    navController.navigate(
        Routes.detailRoute(
            type = type,
            traktId = recommendation.traktId ?: 0,
            tmdbId = recommendation.tmdbId ?: 0,
            title = recommendation.title,
            imdbId = recommendation.imdbId.orEmpty(),
            doubanId = doubanId
        )
    )
}

private fun navigateAiLegacyMediaClick(
    navController: androidx.navigation.NavHostController,
    type: String,
    traktId: Int,
    tmdbId: Int,
    title: String,
    imdbId: String,
    traktRating: Double,
    inWatchlist: Boolean = false,
    isWatched: Boolean = false
) {
    if (imdbId.startsWith(AI_DOUBAN_NAV_PREFIX)) {
        navController.navigate(Routes.doubanItemDetailRoute(imdbId.removePrefix(AI_DOUBAN_NAV_PREFIX)))
    } else {
        navController.navigate(Routes.detailRoute(type, traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched))
    }
}

/** 将详情页产生的标记变更沿导航返回链传递给主页。 */
private fun NavBackStackEntry.propagateMarkChangesTo(
    target: NavBackStackEntry?,
    watchlistChanged: Boolean = false,
    watchedChanged: Boolean = false,
    doubanWatchlistRefreshRequested: Boolean = false
) {
    val sourceWatchlistChanged = savedStateHandle.get<Boolean>("watchlist_changed") ?: false
    val sourceWatchedChanged = savedStateHandle.get<Boolean>("watched_changed") ?: false
    if (target != null) {
        val targetWatchlistChanged = target.savedStateHandle.get<Boolean>("watchlist_changed") ?: false
        val targetWatchedChanged = target.savedStateHandle.get<Boolean>("watched_changed") ?: false
        val targetDoubanWatchlistRefreshRequested =
            target.savedStateHandle.get<Boolean>("douban_watchlist_refresh_requested") ?: false
        target.savedStateHandle.set(
            "watchlist_changed",
            targetWatchlistChanged || sourceWatchlistChanged || watchlistChanged
        )
        target.savedStateHandle.set(
            "watched_changed",
            targetWatchedChanged || sourceWatchedChanged || watchedChanged
        )
        target.savedStateHandle.set(
            "douban_watchlist_refresh_requested",
            targetDoubanWatchlistRefreshRequested || doubanWatchlistRefreshRequested
        )
    }
    savedStateHandle["watchlist_changed"] = false
    savedStateHandle["watched_changed"] = false
    savedStateHandle["douban_watchlist_refresh_requested"] = false
}

class AuthStateHolder @Inject constructor(
    private val authManager: AuthManager,
    private val traktAuthManager: TraktAuthManager,
    private val traktRepository: com.tracktosearch.data.repository.TraktRepository
) {
    val authState: kotlinx.coroutines.flow.StateFlow<AuthState> = authManager.authState
    val isLoggedIn: kotlinx.coroutines.flow.Flow<Boolean> = authManager.authState
        .map { it == AuthState.AUTHORIZED || it == AuthState.OFFLINE }

    suspend fun disconnectTrakt() {
        try {
            traktAuthManager.disconnect()
        } finally {
            traktRepository.clearTraktAccountCaches()
        }
    }

    suspend fun buildTraktAuthorizationUrl(): Result<String> =
        traktAuthManager.buildAuthorizationUrl()

    suspend fun exchangeTraktCode(code: String): Result<Unit> {
        val result = traktAuthManager.exchangeCodeForToken(code)
        if (result.isSuccess) {
            traktRepository.clearTraktAccountCaches()
        }
        return result
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class)
@Composable
fun AppNavigation(
    startDestination: String,
    initialTab: Int = 0,
    authStateHolder: AuthStateHolder,
    /**
     * 统一会话模式管理器：trakt 连接态、豆瓣登录态、sessionMode 三态合一。
     * - UI 由 [SessionModeManager.traktConnectionState] / [SessionModeManager.traktConnected]
     *   / [SessionModeManager.sessionMode] 派生，不再依赖 Compose 入参传递 trakt 连接态。
     * - Trakt 网络校验结果由 MainActivity 调用 [SessionModeManager.setTraktConnectionState] 写入。
     */
    sessionModeManager: SessionModeManager,
    onLoginSuccess: () -> Unit = {},
    /**
     * 导航落到主页时回调。
     *
     * 判据只能是路由，不能挂在 [onLoginSuccess] 上：进主页有四条路，只有 Trakt 授权成功那条会调它。
     * 访客模式在 onGuestMode 里直接 navigate 到 MAIN，豆瓣独立模式登录成功走
     * navigateToMainAfterLogin，授权静默恢复后从登录页自动回主页走的也是同一个函数——
     * 这三条都不经过 onLoginSuccess。路由是四条路唯一的共同终点。
     *
     * 每次进入主页都会回调（从详情页返回也算），调用方按幂等处理。
     */
    onEnterMain: () -> Unit = {}
) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val context = LocalContext.current
    val loginFailedMessage = stringResource(R.string.login_failed)
    val loginDeniedMessage = stringResource(R.string.login_denied)
    val authOfflineMessage = stringResource(R.string.auth_offline_mode)
    val syncAlreadyRunningMessage = stringResource(R.string.sync_already_running)
    val importSuccessMessage = stringResource(R.string.import_success)
    /**
     * 导航层共享的 Snackbar 宿主：登录失败/授权被拒/离线/导入成功这些反馈原来各自弹 Toast，
     * 与详情页、设置页已有的 Snackbar 风格不一致，且 Toast 在系统里可被用户整体关闭。
     * 这里挂一个宿主给 NavHost 之外的导航级回调用，页面内的反馈仍由各页自己的宿主负责。
     */
    val appSnackbarHostState = remember { SnackbarHostState() }
    /**
     * 网络状态：MainScreen 的离线胶囊用的就是 auth_offline_mode 这同一句文案，断网时它已常驻显示，
     * 鉴权离线提示再说一遍属于重复打扰。只在网络可用、单纯是鉴权校验没通过时才提示。
     */
    val connectivityObserver = remember {
        EntryPointAccessors.fromApplication(context, ConnectivityObserverEntryPoint::class.java)
            .connectivityObserver()
    }
    // 页面变化追踪（错误日志上下文）
    // 必须放 DisposableEffect：直接写在组合体里会随每次重组重复注册且永不注销，
    // navController 强持有监听器，会话越长重复回调越多
    DisposableEffect(navController) {
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
            val route = destination.route ?: ""
            CurrentPageHolder.currentRoute = route
            val pageName = simplifyRouteName(route)
            CurrentPageHolder.currentPageName = pageName
            UserActionTracker.record("nav", "to_page", pageName)
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    var currentStartDest by remember { mutableStateOf(startDestination) }
    // 读取默认启动页设置
    val defaultTabStorage = EntryPointAccessors.fromApplication(context, DefaultTabEntryPoint::class.java).defaultTabStorage()
    val storedDefaultTab by defaultTabStorage.defaultTab.collectAsStateWithLifecycle()
    // 使用初始 tab（已由 MainActivity 根据登录状态和用户设置决定）
    var mainInitialTab by remember { mutableIntStateOf(initialTab) }
    // 标记登录成功后设置的 tab，避免被 storedDefaultTab 的异步加载覆盖
    // 场景: onLoginSuccess 设置 mainInitialTab=2(老用户进我的页) 后,
    // storedDefaultTab 从默认值 0 加载为磁盘真实值(如 1=发现页),LaunchedEffect 会覆盖登录意图
    var loginTabOverride by remember { mutableStateOf(false) }
    // Widget 启动请求优先进入搜索页，不能被用户保存的默认页覆盖。
    val searchRequested by SearchNavigator.pending.collectAsStateWithLifecycle()
    val widgetSearchInitialTabOverride = remember { SearchNavigator.pending.value }
    // 监听默认启动页设置变化
    LaunchedEffect(storedDefaultTab) {
        // 登录后设置的 tab 仅生效一次，不被 storedDefaultTab 覆盖
        if (!loginTabOverride && !widgetSearchInitialTabOverride) {
            mainInitialTab = storedDefaultTab
        }
    }
    val scope = rememberCoroutineScope()
    // 全应用级 snackbar（Trakt 授权失败/被拒、自动导入结果）配的结果类触感。
    // 这一层没有页面，触感只跟着 appSnackbarHostState 上那几条消息走
    val outcomeHaptics = rememberAppHaptics()
    var directTraktLoginActive by rememberSaveable { mutableStateOf(false) }

    // 预热 onboarding 完成标记：登录成功时需要它决定默认 tab。
    // 提前读取，避免 DataStore 冷读阻塞登录后导航（转圈消失 → 进入主界面之间 1-2s 停顿的根因）。
    val onboardingStorage = remember { OnboardingStorage(context) }
    var onboardingLoaded by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        StartupTrace.mark("login.onboarding.prewarm.begin")
        onboardingLoaded = onboardingStorage.isCompleted.first()
        StartupTrace.mark("login.onboarding.prewarm.end")
    }

    /**
     * 平台登录成功后进入主界面的统一逻辑（Trakt 登录与豆瓣独立模式共用）。
     * - 默认 tab：widget 搜索请求 → 搜索页(0)；老用户(已完成新手引导) → 我的页(2)；新用户 → 搜索页(0)
     * - onboarding 标记已由组合期预热，导航不再等待 DataStore 冷读
     */
    fun navigateToMainAfterLogin() {
        scope.launch {
            StartupTrace.mark("login.navigate.main.begin", "onboardingLoaded=$onboardingLoaded")
            mainInitialTab = when {
                SearchNavigator.pending.value -> 0
                onboardingLoaded != null -> if (onboardingLoaded == true) 2 else 0
                else -> {
                    // 极端情况：预热协程尚未完成（用户极快完成登录），DataStore 已在读取中，此读会立即返回
                    val completed = onboardingStorage.isCompleted.first()
                    if (completed) 2 else 0
                }
            }
            loginTabOverride = true  // 阻止 storedDefaultTab 覆盖登录意图
            currentStartDest = Routes.MAIN
            navController.navigate(Routes.MAIN) {
                popUpTo(0) { inclusive = true }
            }
            StartupTrace.mark("login.navigate.main.end", "tab=$mainInitialTab")
        }
    }

    LaunchedEffect(directTraktLoginActive) {
        if (!directTraktLoginActive) return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            launch {
                OAuthCallback.pendingCodeFlow
                    .filterNotNull()
                    .collect { code ->
                        OAuthCallback.setPendingCode(null)
                        val result = authStateHolder.exchangeTraktCode(code)
                        directTraktLoginActive = false
                        if (result.isSuccess) {
                            sessionModeManager.setTraktConnectionState(TraktConnectionState.CONNECTED)
                            onLoginSuccess()
                        } else {
                            // 用 scope 另起协程：showSnackbar 会挂起到消失，直接在 collect 里调用会卡住后续回调
                            outcomeHaptics.reject()
                            scope.launch { appSnackbarHostState.showSnackbar(loginFailedMessage) }
                        }
                    }
            }
            launch {
                OAuthCallback.authDeniedFlow
                    .filter { it }
                    .collect {
                        OAuthCallback.setAuthDenied(false)
                        directTraktLoginActive = false
                        // 用户在授权页按了「拒绝」。这条 snackbar 已经在报错，触感只是配合它
                        outcomeHaptics.reject()
                        scope.launch { appSnackbarHostState.showSnackbar(loginDeniedMessage) }
                    }
            }
        }
    }

    fun launchDirectTraktLogin() {
        if (directTraktLoginActive) return
        directTraktLoginActive = true
        scope.launch {
            authStateHolder.buildTraktAuthorizationUrl()
                .onSuccess { authUrl ->
                    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authUrl))
                }
                .onFailure {
                    directTraktLoginActive = false
                    outcomeHaptics.reject()
                    scope.launch { appSnackbarHostState.showSnackbar(loginFailedMessage) }
                }
        }
    }
    val currentAuthState by authStateHolder.authState.collectAsStateWithLifecycle()
    // Trakt 连接态：从 SessionModeManager 读取（MainActivity 在网络校验后写入）
    val traktConnectionState by sessionModeManager.traktConnectionState.collectAsStateWithLifecycle()
    val isTraktConnected by sessionModeManager.traktConnected.collectAsStateWithLifecycle()
    // 豆瓣登录态：用于判定豆瓣独立模式（激活网关 + 已登录豆瓣 + 未连 trakt）
    val doubanAuthStorage = remember {
        EntryPointAccessors.fromApplication(context, DoubanAuthStorageEntryPoint::class.java).doubanAuthStorage()
    }
    val isDoubanLoggedIn by doubanAuthStorage.isLoggedIn.collectAsStateWithLifecycle()
    // 豆瓣独立模式：统一由 SessionModeManager 判定（额外含网关授权态，授权撤销后立即退出该模式），
    // 不再本地重算双源判定；initialValue 沿用旧判定式，仅作流首次收集前的首帧占位
    val isDoubanMode by sessionModeManager.isDoubanMode.collectAsStateWithLifecycle(
        initialValue = isDoubanLoggedIn && !isTraktConnected
    )
    // MainScreen 的 isLoggedIn：trakt 已连 OR 豆瓣已登录（非 GUEST 模式才显示 WatchlistScreen）。
    // 刻意不从 sessionMode 收敛：收敛后会引入网关授权态，改变访客模式下 Watchlist 页的可见性
    val isLoggedIn = isTraktConnected || isDoubanLoggedIn

    // Widget 从详情等子页面触发时，先回到 MainScreen，再由 MainScreen 消费搜索请求。
    val currentRoute = currentBackStackEntry?.destination?.route
    LaunchedEffect(searchRequested, currentStartDest, currentRoute) {
        if (!searchRequested) return@LaunchedEffect

        val route = currentRoute ?: return@LaunchedEffect
        val hasMainAccess = currentStartDest == Routes.MAIN ||
            currentAuthState == AuthState.AUTHORIZED ||
            currentAuthState == AuthState.OFFLINE
        val isDoubanActivationLogin = route == Routes.DOUBAN_LOGIN &&
            navController.previousBackStackEntry?.destination?.route == Routes.LOGIN
        if (!hasMainAccess || route == Routes.MAIN ||
            route == Routes.LOGIN || isDoubanActivationLogin
        ) {
            return@LaunchedEffect
        }

        navController.navigate(Routes.MAIN) {
            popUpTo(Routes.MAIN) { inclusive = false }
            launchSingleTop = true
        }
    }

    // 开屏台词层的门在这里开：见 [onEnterMain] 的说明，路由是各条进主页路径唯一的共同终点。
    LaunchedEffect(currentRoute) {
        if (currentRoute == Routes.MAIN) onEnterMain()
    }

    LaunchedEffect(currentAuthState) {
        // 延迟确认：冷启动/后台校验的网络抖动会短暂置 OFFLINE 随后恢复 AUTHORIZED，
        // 只有状态稳定为 OFFLINE 才提示离线，避免网络正常时误报。
        if (currentAuthState == AuthState.OFFLINE) {
            delay(3_000)
            if (authStateHolder.authState.value == AuthState.OFFLINE &&
                connectivityObserver.status.value != ConnectivityObserver.NetworkStatus.OFFLINE
            ) {
                // 不用 scope.launch：留在本 effect 里，网络/鉴权状态一变 effect 取消，Snackbar 随之收起
                appSnackbarHostState.showSnackbar(
                    message = authOfflineMessage,
                    duration = SnackbarDuration.Long
                )
            }
        }
    }

    // 授权失效重定向：撤销可能发生在任意页面，原逻辑只在「状态变化瞬间恰好位于 MAIN」时跳登录页，
    // 用户在详情/搜索页被撤销后返回 MAIN 不再触发，会被困在无会话的主界面。
    // currentRoute 参与重跑：回到 MAIN 时重新校验，保持「在 MAIN 时立即跳」的原行为。
    // 仅「曾处于授权态（AUTHORIZED/OFFLINE）后失效」才标记踢出。
    // 访客同样依赖网关激活态，失效后必须回到激活登录页，不能以平台未登录为由绕过。
    var pendingInvalidationKick by remember { mutableStateOf(false) }
    var previousKickAuthState by remember { mutableStateOf(currentAuthState) }
    LaunchedEffect(currentAuthState, currentRoute) {
        val wasAuthorized = previousKickAuthState == AuthState.AUTHORIZED ||
            previousKickAuthState == AuthState.OFFLINE
        previousKickAuthState = currentAuthState
        if (wasAuthorized &&
            (currentAuthState == AuthState.UNAUTHORIZED || currentAuthState == AuthState.EXPIRED)
        ) {
            pendingInvalidationKick = true
        }
        if (currentAuthState == AuthState.AUTHORIZED || currentAuthState == AuthState.OFFLINE) {
            pendingInvalidationKick = false
        }
        if (pendingInvalidationKick && currentRoute == Routes.MAIN) {
            pendingInvalidationKick = false
            currentStartDest = Routes.LOGIN
            navController.navigate(Routes.LOGIN) {
                popUpTo(Routes.MAIN) { inclusive = true }
            }
        }
    }

    // 监听通知深链路导航指令（上映/新季通知点击后跳转详情页）
    // 直接读 StateFlow.value 避免 Compose 状态捕获问题
    // 合并 authState 作为触发条件：冷启动时 handleIntent 先写 pendingNavigation、
    // 授权校验后完成，若只 collect pendingNavigation，未授权时跳过会把值永久滞留
    // （StateFlow 不重发）——用户点通知毫无反应。combine 后授权就绪会带着滞留目标重发
    LaunchedEffect(Unit) {
        DeepLinkNavigator.pendingNavigation.combine(authStateHolder.authState) { target, authState ->
            target to authState
        }.collect { (target, authState) ->
            if (target != null && (authState == AuthState.AUTHORIZED || authState == AuthState.OFFLINE)) {
                navController.navigate(
                    Routes.detailRoute(target.type, target.traktId, target.tmdbId, target.title)
                )
                DeepLinkNavigator.consume()
            }
        }
    }

    // 语义根开一次 testTagsAsResourceId：它把 Modifier.testTag 的值写进无障碍树的 resource-id，
    // UiAutomator 宏基准才能用 By.res() 定位 Compose 节点。不开的话整棵树的 resource-id 都是空串，
    // 只能靠 contentDescription 与文案定位，那些值跟着语言和数据变，基准用例会不稳。
    // 挂在这里而不是 Activity 根：需要被定位的节点全都在 NavHost 之内，且这一层只加语义、不动布局。
    SharedTransitionLayout(modifier = Modifier.semantics { testTagsAsResourceId = true }) {
        CompositionLocalProvider(
            LocalSharedTransitionScope provides this@SharedTransitionLayout
        ) {
            BackdropProvider(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
            NavHost(
                navController = navController,
                startDestination = currentStartDest,
                // 全局默认转场：统一 220ms 纯 fade（返回 pop 为 300ms，见下）；设置页子树单独使用
                // 短距离水平滑动 + 淡入淡出，避免全宽滑动与 haze、Mesh 背景叠加导致掉帧。
                // 原默认 700ms fadeIn/fadeOut 转场期间新旧两页同时组合，各页全屏 hazeSource
                // 与多个 blur 节点同时渲染导致切换掉帧；纯 fade 无水平偏移，时长缩短为 220ms
                // 可减少转场重叠开销，且与详情页共享元素 spring 动画对齐，避免违和。
                enterTransition = {
                    if (Routes.isSettingsSubtreeRoute(targetState.destination.route)) {
                        fadeIn(animationSpec = tween(180)) +
                            slideInHorizontally(animationSpec = tween(220)) { fullWidth ->
                                fullWidth / 10
                            }
                    } else {
                        fadeIn(animationSpec = tween(220))
                    }
                },
                // 去容器变形页时不给来源页叠淡出：目标页第一帧就不透明地盖住它，这层全屏 alpha
                // 白画一遍。返回方向见下面的 popEnterTransition，那一侧是真的会看出问题。
                exitTransition = {
                    when {
                        Routes.routeId(targetState.destination.route) in Routes.ContainerMorphRouteIds ->
                            ExitTransition.None
                        Routes.isSettingsSubtreeRoute(targetState.destination.route) ->
                            fadeOut(animationSpec = tween(160)) +
                                slideOutHorizontally(animationSpec = tween(220)) { fullWidth ->
                                    -fullWidth / 20
                                }
                        else -> fadeOut(animationSpec = tween(220))
                    }
                },
                // 从容器变形页返回时不给目标页叠淡入：整页收回成卡片的动画由 SharedTransitionLayout
                // 接管，目标页应当立即完整可见。再叠 120ms 淡入就是两层半透明相叠，收缩中的整页
                // 与列表页互相穿透，观感上像列表卡片里残留着上一页的内容。
                // pop 的 300ms 不是观感偏好，是共享元素动画的播放窗口，别再往回收：
                // NavHost 的过渡进度（共享元素边界动画按进度 seek）由「两页进出时长」与
                // 「sizeTransform 时长」共同决定，实测两者必须同步放宽——任一侧留 120ms，
                // 进度窗口就塌回 120ms。窗口窄于目标页首帧耗时时（详情页从人物页返回，
                // 首帧实测 145~190ms：组合 + 多次测量），进度会在返回后的第二帧一步到 1，
                // 共享元素的 bounds spring 被直接 seek 到终点，用户看到的就是「没有回缩动画」。
                // 300ms 给首帧留约两倍余量。改小前先跑 build/qa/person-return 的往返录屏。
                popEnterTransition = {
                    when {
                        Routes.routeId(initialState.destination.route) in Routes.ContainerMorphRouteIds ->
                            EnterTransition.None
                        Routes.isSettingsSubtreeRoute(initialState.destination.route) ->
                            fadeIn(animationSpec = tween(180)) +
                                slideInHorizontally(animationSpec = tween(220)) { fullWidth ->
                                    -fullWidth / 10
                                }
                        else -> fadeIn(animationSpec = tween(300))
                    }
                },
                popExitTransition = {
                    if (Routes.isSettingsSubtreeRoute(initialState.destination.route)) {
                        fadeOut(animationSpec = tween(160)) +
                            slideOutHorizontally(animationSpec = tween(220)) { fullWidth ->
                                fullWidth / 10
                            }
                    } else {
                        fadeOut(animationSpec = tween(300))
                    }
                },
                // 尺寸动画本身无视觉效果（两页都全屏、尺寸不变），但它的时长同样卡着过渡进度，
                // 所以与 pop 一起放宽到 300ms（留 120ms 会让共享元素动画重新变回一帧到位）。
                // 也不能用默认的 StiffnessMediumLow 弹簧：尺寸动画要 2~3 秒才判停，
                // 转场状态在视觉结束后仍长时间 running，期间每帧重录含 Mesh 的背景层，白烧 GPU。
                sizeTransform = { SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween<IntSize>(300) }) }
            ) {
                composable(Routes.LOGIN) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        VintagePaperPage(keepPaperDialogs = true) {
                        ActivationLoginScreen(
                            expired = currentAuthState == AuthState.EXPIRED,
                            onLoginSuccess = {
                                // Trakt 登录成功：写入 SessionModeManager，由其驱动 UI 切换到 TRAKT 模式
                                sessionModeManager.setTraktConnectionState(TraktConnectionState.CONNECTED)
                                onLoginSuccess()
                                // 按 onboarding 状态决定默认 tab 并进入主页
                                navigateToMainAfterLogin()
                            },
                            onDoubanLogin = {
                                // 豆瓣登录入口：跳转到 DoubanLoginScreen，
                                // 登录成功后由 DOUBAN_LOGIN composable 的 onLoginSuccess 处理进入主页
                                navController.navigate(Routes.DOUBAN_LOGIN)
                            },
                            onGuestMode = {
                                // 访客是已激活后的平台未登录态；激活状态本身已由 AuthManager 持久化，
                                // 不再维护第二份 guest 标记，避免授权失效后仍绕过激活页进入主页。
                                // 直接读取 StateFlow 最新值，避免激活成功后的首帧仍捕获旧 Compose 状态而吞掉点击。
                                val latestAuthState = authStateHolder.authState.value
                                if (!latestAuthState.hasGatewayAccess()) {
                                    return@ActivationLoginScreen
                                }
                                if (SearchNavigator.pending.value) {
                                    mainInitialTab = 0
                                    loginTabOverride = true
                                }
                                currentStartDest = Routes.MAIN
                                navController.navigate(Routes.MAIN) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                        )
                        }
                    }
                }

                composable(Routes.MAIN) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        // 从详情页返回时，按变更类型分别刷新想看列表或已看历史
                        // 使用 backStackEntry.savedStateHandle 而非 navController.currentBackStackEntry
                        // 后者在导航过渡期间可能为 null 或指向错误的 entry
                        val savedState = backStackEntry.savedStateHandle
                        val watchlistChanged by savedState.getStateFlow("watchlist_changed", false).collectAsStateWithLifecycle()
                        val watchedChanged by savedState.getStateFlow("watched_changed", false).collectAsStateWithLifecycle()
                        val doubanWatchlistRefreshRequested by savedState
                            .getStateFlow("douban_watchlist_refresh_requested", false)
                            .collectAsStateWithLifecycle()
                        val watchlistViewModel: WatchlistViewModel = hiltViewModel()
                        LaunchedEffect(watchlistChanged) {
                            if (watchlistChanged) {
                                // 详情页的单条标记已由 TraktRepository 变更流同步到列表，返回时不再拉取完整 watchlist。
                                watchlistViewModel.onWatchlistTabVisible()
                                savedState.set("watchlist_changed", false)
                            }
                        }
                        LaunchedEffect(watchedChanged) {
                            if (watchedChanged) {
                                watchlistViewModel.refreshWatched()
                                savedState.set("watched_changed", false)
                            }
                        }
                        LaunchedEffect(doubanWatchlistRefreshRequested) {
                            if (doubanWatchlistRefreshRequested) {
                                // 豆瓣详情写回只更新本地 DAO，需复用 WatchlistViewModel 的本地重读入口。
                                watchlistViewModel.refreshWatchlist()
                                savedState.set("douban_watchlist_refresh_requested", false)
                            }
                        }

                        // 豆瓣同步续传检测:App 启动时检测是否有未处理完的 pending items
                        // 优先级:rollback > pending items
                        // - 有 rollback → 弹回滚恢复对话框(恢复标记/不恢复)
                        // - 有 pending items → 弹续传对话框(继续同步/完整同步)
                        // - 无 pending items → 不显示同步恢复对话框
                        val doubanSyncManager = hiltViewModel<DoubanSyncViewModel>().doubanSyncManager

                        // 回滚检测(最高优先级:用户标记数据安全)
                        var rollbackCount by rememberSaveable { mutableIntStateOf(0) }
                        var rollbackChecked by rememberSaveable { mutableStateOf(false) }
                        var rollbackDialogDismissed by rememberSaveable { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            if (!rollbackChecked) {
                                rollbackChecked = true
                                rollbackCount = doubanSyncManager.getRollbackCount()
                                rollbackDialogDismissed = false
                            }
                        }
                        // 启动时读到回滚数据自己弹出来的，这一帧之前没有任何按压
                        PopupShowEffect(rollbackCount > 0 && !rollbackDialogDismissed)
                        if (rollbackCount > 0 && !rollbackDialogDismissed) {
                            com.tracktosearch.ui.screen.douban.DoubanRollbackDialog(
                                rollbackCount = rollbackCount,
                                // 关闭只隐藏当前弹窗，保留 rollback 数据；pending 弹窗仍被阻断。
                                onDismiss = { rollbackDialogDismissed = true },
                                onRestore = {
                                    rollbackDialogDismissed = true
                                    scope.launch {
                                        doubanSyncManager.restoreRollback()
                                        rollbackCount = doubanSyncManager.getRollbackCount()
                                    }
                                },
                                onDiscard = {
                                    rollbackDialogDismissed = true
                                    scope.launch {
                                        doubanSyncManager.discardRollback()
                                        rollbackCount = doubanSyncManager.getRollbackCount()
                                    }
                                }
                            )
                        }

                        var pendingCount by rememberSaveable { mutableIntStateOf(0) }
                        var pendingChecked by rememberSaveable { mutableStateOf(false) }
                        var pendingDialogDismissed by rememberSaveable { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            if (!pendingChecked) {
                                pendingChecked = true
                                pendingCount = doubanSyncManager.getPendingItemsCount()
                                pendingDialogDismissed = false
                            }
                        }
                        // 同上：进页面就弹，用户没按任何东西
                        PopupShowEffect(
                            pendingCount > 0 && rollbackCount == 0 && !pendingDialogDismissed
                        )
                        if (pendingCount > 0 && rollbackCount == 0 && !pendingDialogDismissed) {
                            com.tracktosearch.ui.screen.douban.DoubanPendingItemsDialogWithDiscard(
                                pendingCount = pendingCount,
                                onDismiss = {
                                    // 关闭只代表稍后处理，保留 pending 供下次恢复。
                                    pendingDialogDismissed = true
                                },
                                onContinue = {
                                    // 继续同步:走 startResume,跳过列表爬取
                                    // 留在 MainScreen，Watchlist 横幅会显示进度
                                    pendingDialogDismissed = true
                                    pendingCount = 0
                                    val started = doubanSyncManager.startResume()
                                    if (!started) {
                                        Toast.makeText(context, syncAlreadyRunningMessage, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onFullSync = {
                                    // 完整同步:由同步任务在真正启动后清理 pending。
                                    // 留在 MainScreen，Watchlist 横幅会显示进度
                                    pendingDialogDismissed = true
                                    pendingCount = 0
                                    scope.launch {
                                        val started = doubanSyncManager.startSync(SyncMode.FULL_REWRITE)
                                        if (!started) {
                                            pendingCount = doubanSyncManager.getPendingItemsCount()
                                            pendingDialogDismissed = false
                                            Toast.makeText(context, syncAlreadyRunningMessage, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onDiscardPending = {
                                    scope.launch {
                                        doubanSyncManager.discardPendingItems()
                                        pendingCount = doubanSyncManager.getPendingItemsCount()
                                        pendingDialogDismissed = true
                                    }
                                }
                            )
                        }

                        MaterialTheme(
                            colorScheme = LocalMainColorScheme.current ?: MaterialTheme.colorScheme,
                        ) {
                            MainScreen(
                                initialTab = mainInitialTab,
                                isLoggedIn = isLoggedIn,
                                isTraktConnected = isTraktConnected,
                                isDoubanMode = isDoubanMode,
                                onMovieClick = { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                    navigateAiLegacyMediaClick(navController, "movie", traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                                },
                                onShowClick = { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                    navigateAiLegacyMediaClick(navController, "show", traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                                },
                                onMediaItemClick = { item, inWatchlist, isWatched ->
                                    val doubanId = item.doubanId
                                    if (shouldUseDoubanItemDetail(
                                            doubanId = doubanId,
                                            imdbId = item.imdbId,
                                            mediaType = item.mediaType,
                                            traktId = item.traktId,
                                            tmdbId = item.tmdbId
                                        )) {
                                        navController.navigate(Routes.doubanItemDetailRoute(requireNotNull(doubanId)))
                                    } else {
                                        // DetailScreen 目前只接受电影/剧集媒体类型；已知类型之外的豆瓣条目走豆瓣详情框架。
                                        val type = if (item.mediaType == WatchlistMediaType.SHOW) "show" else "movie"
                                        navController.navigate(
                                            Routes.detailRoute(
                                                type = type,
                                                traktId = item.traktId,
                                                tmdbId = item.tmdbId,
                                                title = item.displayTitle,
                                                imdbId = item.imdbId,
                                                traktRating = item.traktRating,
                                                inWatchlist = inWatchlist,
                                                isWatched = isWatched,
                                                doubanId = doubanId,
                                                // 卡片已经渲染过的海报和年份直接带给详情页当首帧种子：
                                                // 想看列表冷启动是从 Room 快照恢复的，此时 TMDB 内存缓存还是空的，
                                                // 详情页 peek 不到，只有靠这里传下去才能第一帧就有海报。
                                                posterUrl = item.posterUrl,
                                                year = item.year
                                            )
                                        )
                                    }
                                },
                                onSearchClick = { keyword ->
                                    // 调试入口:搜索框输入特定数字串进入豆瓣爬取测试页(仅 DEBUG 构建可用)
                                    if (BuildConfig.DEBUG && keyword.trim() == "13638719007") {
                                        navController.navigate(Routes.DOUBAN_SPIDER_TEST)
                                    } else {
                                        navController.navigate(Routes.searchRoute(keyword))
                                    }
                                },
                                onNavigateToLogin = {
                                    navController.navigate(Routes.LOGIN) {
                                        popUpTo(Routes.MAIN) { inclusive = false }
                                    }
                                },
                                onTraktLogin = { launchDirectTraktLogin() },
                                onStatisticsClick = {
                                    navController.navigate(Routes.STATISTICS)
                                },
                                onDailyStampClick = {
                                    navController.navigate(Routes.DAILY_STAMP)
                                },
                                onMarkRecordsClick = {
                                    navController.navigate(Routes.MARK_RECORDS)
                                },
                                onTraktSearch = { type, query ->
                                    navController.navigate(Routes.traktSearchRoute(type, query))
                                },
                                onAiRecommendationClick = { recommendation ->
                                    navigateAiRecommendation(navController, recommendation)
                                },
                                onPersonClick = { tmdbId, name, profileUrl, avatarColor ->
                                    navController.navigate(Routes.personRoute(tmdbId, name, profileUrl ?: ""))
                                },
                                onListClick = { listId, listName, morph ->
                                    navController.navigate(Routes.listDetailRoute(listId, listName, morph))
                                },
                                onLogout = {
                                    // Trakt 退出登录：只清除 Trakt 连接状态，不清除网关激活令牌，
                                    // 也不跳转到激活/登录页——用户仍处于已激活或访客模式，留在设置页即可。
                                    sessionModeManager.setTraktConnectionState(TraktConnectionState.DISCONNECTED)
                                    scope.launch { authStateHolder.disconnectTrakt() }
                                    // 不修改 currentStartDest，不导航；MainScreen 的「我的」tab 会自动显示登录提示
                                },
                                onHelpClick = {
                                    navController.navigate(Routes.helpRoute())
                                },
                                onOpenSourceClick = {
                                    navController.navigate(Routes.OPEN_SOURCE)
                                },
                                onFilterDiscoverClick = { entry ->
                                    navController.navigate(Routes.discoverFilterRoute(entry))
                                },
                                onDoubanResync = {
                                    // 不再导航到 DoubanLoginScreen
                                    // MainScreen 内部会切换到 Watchlist tab 显示同步横幅
                                },
                                onNavigateToDoubanLogin = {
                                    navController.navigate(Routes.DOUBAN_LOGIN)
                                },
                                onSpiderTest = {
                                    // 仅 DEBUG 构建允许进入豆瓣爬取测试页
                                    if (BuildConfig.DEBUG) {
                                        navController.navigate(Routes.DOUBAN_SPIDER_TEST)
                                    }
                                },
                                onGlassPilot = {
                                    // 仅 DEBUG 构建允许进入玻璃引擎试点页
                                    if (BuildConfig.DEBUG) {
                                        navController.navigate(Routes.GLASS_PILOT)
                                    }
                                },
                                onFeedbackClick = {
                                    navController.navigate(Routes.FEEDBACK)
                                },
                                onMessagesClick = {
                                    navController.navigate(Routes.MESSAGES)
                                },
                                onSearchSourcesClick = {
                                    navController.navigate(Routes.SEARCH_SOURCES)
                                },
                                onPrivacyClick = {
                                    navController.navigate(Routes.PRIVACY)
                                },
                                onSplashQuoteClick = {
                                    navController.navigate(Routes.SPLASH_QUOTE)
                                }
                            )

                        }
                    }
                }

                composable(
                    route = Routes.DETAIL,
                    arguments = listOf(
                        navArgument("type") { type = NavType.StringType },
                        navArgument("traktId") { type = NavType.IntType },
                        navArgument("tmdbId") { type = NavType.IntType },
                        navArgument("title") { type = NavType.StringType },
                        navArgument("imdbId") { type = NavType.StringType; defaultValue = "" },
                        navArgument("traktRating") { type = NavType.FloatType; defaultValue = 0.0f },
                        navArgument("inWatchlist") { type = NavType.BoolType; defaultValue = false },
                         navArgument("isWatched") { type = NavType.BoolType; defaultValue = false },
                         navArgument("doubanId") { type = NavType.StringType; defaultValue = "" },
                         navArgument("posterUrl") { type = NavType.StringType; defaultValue = "" },
                         navArgument("year") { type = NavType.IntType; defaultValue = 0 }
                    )
                ) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        VintagePaperPage {
                        val type = backStackEntry.arguments?.getString("type") ?: "movie"
                        val traktId = backStackEntry.arguments?.getInt("traktId") ?: 0
                        val tmdbId = backStackEntry.arguments?.getInt("tmdbId") ?: 0
                        val title = backStackEntry.arguments?.getString("title") ?: ""
                        val imdbId = backStackEntry.arguments?.getString("imdbId") ?: ""
                        val traktRating = backStackEntry.arguments?.getFloat("traktRating")?.toDouble() ?: 0.0
                         val inWatchlist = backStackEntry.arguments?.getBoolean("inWatchlist") ?: false
                         val isWatched = backStackEntry.arguments?.getBoolean("isWatched") ?: false
                         val doubanId = backStackEntry.arguments?.getString("doubanId")?.takeIf { it.isNotBlank() }
                         // 首帧种子：列表卡片已知的海报与年份，缺省时为空串/0
                         val seedPosterUrl = backStackEntry.arguments?.getString("posterUrl")
                             ?.takeIf { it.isNotBlank() }
                         val seedYear = backStackEntry.arguments?.getInt("year")?.takeIf { it > 0 }

                        // 用于在标记已看/想看后通知上级列表页刷新
                        // 同时检查从子详情页（推荐跳转）传递回来的变更标记
                        val previousEntry = navController.previousBackStackEntry
                        fun goBack(watchlistChanged: Boolean, watchedChanged: Boolean) {
                            backStackEntry.propagateMarkChangesTo(previousEntry, watchlistChanged, watchedChanged)
                            navController.popBackStack()
                        }

                        DetailScreen(
                            traktId = traktId,
                            tmdbId = tmdbId,
                            title = title,
                            mediaType = if (type == "show") MediaType.SHOW else MediaType.MOVIE,
                            year = seedYear,
                            imdbId = imdbId,
                            traktRating = traktRating,
                             initialInWatchlist = inWatchlist,
                             initialIsWatched = isWatched,
                             doubanId = doubanId,
                             seedPosterUrl = seedPosterUrl,
                            onBack = { wlChanged, wChanged -> goBack(wlChanged, wChanged) },
                            onPersonClick = { personId, personName, profileUrl, avatarColor ->
                                navController.navigate(Routes.personRoute(personId, personName, profileUrl ?: ""))
                            },
                            onMovieClick = { traktId, tmdbId, title, imdbId, traktRating ->
                                navController.navigate(Routes.detailRoute("movie", traktId, tmdbId, title, imdbId, traktRating))
                            },
                            onShowClick = { traktId, tmdbId, title, imdbId, traktRating ->
                                navController.navigate(Routes.detailRoute("show", traktId, tmdbId, title, imdbId, traktRating))
                            },
                            onTraktLogin = { launchDirectTraktLogin() },
                            onDoubanLogin = { navController.navigate(Routes.DOUBAN_LOGIN) }
                        )
                        }
                    }
                }

                composable(
                    route = Routes.SEARCH,
                    arguments = listOf(
                        navArgument("keyword") {
                            type = NavType.StringType
                            defaultValue = ""
                        }
                    )
                ) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        VintagePaperPage {
                        val keyword = backStackEntry.arguments?.getString("keyword") ?: ""
                        SearchScreen(
                            initialKeyword = keyword,
                            onBack = {
                                backStackEntry.propagateMarkChangesTo(navController.previousBackStackEntry)
                                navController.popBackStack()
                            },
                            onMovieClick = { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                navigateAiLegacyMediaClick(navController, "movie", traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                            },
                            onShowClick = { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                navigateAiLegacyMediaClick(navController, "show", traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                            },
                            onNavigateToLogin = {
                                navController.navigate(Routes.LOGIN) {
                                    popUpTo(Routes.MAIN) { inclusive = false }
                                }
                            },
                            onRecommendationClick = { recommendation ->
                                navigateAiRecommendation(navController, recommendation)
                            },
                            // AI 锐评弹窗「去设置」：独立搜索路由不在主界面内，
                            // 先登记目标页签再回主界面，由 MainScreen 收集后切到设置页
                            onOpenSettings = {
                                MainTabNavigator.requestTab(3)
                                navController.navigate(Routes.MAIN) { launchSingleTop = true }
                            }
                        )
                        }
                    }
                }

                composable(
                    route = Routes.PERSON,
                    arguments = listOf(
                        navArgument("personId") { type = NavType.IntType },
                        navArgument("personName") { type = NavType.StringType; defaultValue = "" },
                        navArgument("profileUrl") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        VintagePaperPage {
                        val personId = backStackEntry.arguments?.getInt("personId") ?: 0
                        val personName = backStackEntry.arguments?.getString("personName") ?: ""
                        val profileUrl = backStackEntry.arguments?.getString("profileUrl")?.takeIf { it.isNotEmpty() }
                        PersonScreen(
                            personId = personId,
                            personName = personName,
                            profileUrl = profileUrl,
                            avatarColor = null,
                            onBack = {
                                backStackEntry.propagateMarkChangesTo(navController.previousBackStackEntry)
                                navController.popBackStack()
                            },
                            onMovieClick = { traktId, tmdbId, title, imdbId, traktRating ->
                                navController.navigate(Routes.detailRoute("movie", traktId, tmdbId, title, imdbId, traktRating))
                            },
                            onShowClick = { traktId, tmdbId, title, imdbId, traktRating ->
                                navController.navigate(Routes.detailRoute("show", traktId, tmdbId, title, imdbId, traktRating))
                            }
                        )
                        }
                    }
                }

                composable(
                    route = Routes.LIST_DETAIL,
                    arguments = listOf(
                        navArgument("listId") { type = NavType.IntType },
                        navArgument("listName") { type = NavType.StringType; defaultValue = "" },
                        // morph 是路径段，路由拼出来时一定带值；defaultValue 只是解析失败时的兜底，
                        // 按「无源侧卡片」处理，退回常规淡入，好过毫无动画地直接出现
                        navArgument("morph") { type = NavType.BoolType; defaultValue = false }
                    ),
                    // 只有发现页列表卡片那条路做容器变形（morph == true，见 Routes.LIST_DETAIL）：
                    // 卡片长成整页已经把页面显示出来了，再叠 NavHost 淡入就是同一页淡两次。
                    // 「查看全部」弹窗那条路配不上共享元素，必须保留常规淡入。
                    enterTransition = {
                        if (targetState.arguments?.getBoolean("morph") == true)
                            EnterTransition.None
                        else null
                    },
                    // 返回同理：收回成卡片时整页若还跟着淡出，收缩中的页面会半透明地透出发现页
                    popExitTransition = {
                        if (initialState.arguments?.getBoolean("morph") == true)
                            ExitTransition.None
                        else null
                    }
                ) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        VintagePaperPage {
                        // listId 与 listName 由 TraktListDetailViewModel 自己从 SavedStateHandle 取，
                        // 这里不再重复读一遍。
                        TraktListDetailScreen(
                            onBack = {
                                backStackEntry.propagateMarkChangesTo(navController.previousBackStackEntry)
                                navController.popBackStack()
                            },
                            onMovieClick = { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                navController.navigate(Routes.detailRoute("movie", traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched))
                            },
                            onShowClick = { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                navController.navigate(Routes.detailRoute("show", traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched))
                            }
                        )
                        }
                    }
                }

                composable(
                    route = Routes.STATISTICS
                ) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        StatisticsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                composable(Routes.DAILY_STAMP) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        com.tracktosearch.ui.screen.dailystamp.DailyStampScreen(
                            onBack = { navController.popBackStack() },
                            // mediaType 由台词库给（movie/show），两个命名空间的 tmdbId 各自编号；
                            // traktId 传 0，详情页按 tmdbId 自己去查
                            onQuoteClick = { tmdbId, mediaType, title, year, posterUrl ->
                                navController.navigate(
                                    Routes.detailRoute(
                                        type = mediaType,
                                        traktId = 0,
                                        tmdbId = tmdbId,
                                        title = title,
                                        posterUrl = posterUrl,
                                        year = year,
                                    )
                                )
                            }
                        )
                    }
                }

                composable(
                    route = Routes.TRAKT_SEARCH,
                    arguments = listOf(
                        navArgument("type") { type = NavType.StringType },
                        navArgument("query") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val typeStr = backStackEntry.arguments?.getString("type") ?: "movie"
                        val query = backStackEntry.arguments?.getString("query") ?: ""
                        val mediaType = when (typeStr) {
                            "show" -> MediaType.SHOW
                            "person" -> MediaType.PERSON
                            else -> MediaType.MOVIE
                        }

                        // 从详情页返回时透传标记变更，继续返回主页时触发列表刷新
                        TraktSearchScreen(
                            initialQuery = query,
                            type = mediaType,
                            onBack = {
                                backStackEntry.propagateMarkChangesTo(navController.previousBackStackEntry)
                                navController.popBackStack()
                            },
                            onItemClick = { type, traktId, tmdbId, title, imdbId, traktRating ->
                                val routeType = when (type) {
                                    MediaType.SHOW -> "show"
                                    else -> "movie"
                                }
                                navController.navigate(Routes.detailRoute(routeType, traktId, tmdbId, title, imdbId, traktRating))
                            },
                            onPersonClick = { tmdbId, name, profileUrl, avatarColor ->
                                navController.navigate(Routes.personRoute(tmdbId, name, profileUrl ?: ""))
                            },
                            onRecommendationClick = { recommendation ->
                                navigateAiRecommendation(navController, recommendation)
                            },
                            onNavigateToLogin = {
                                navController.navigate(Routes.LOGIN) {
                                    popUpTo(Routes.MAIN) { inclusive = false }
                                }
                            },
                            // AI 锐评弹窗「去设置」：独立统一搜索路由不在主界面内，
                            // 先登记目标页签再回主界面，由 MainScreen 收集后切到设置页
                            onOpenSettings = {
                                MainTabNavigator.requestTab(3)
                                navController.navigate(Routes.MAIN) { launchSingleTop = true }
                            }
                        )
                    }
                }

                composable(
                    route = Routes.HELP,
                    arguments = listOf(
                        navArgument("section") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        HelpScreen(
                            onBack = { navController.popBackStack() },
                            initialSection = backStackEntry.arguments?.getString("section")
                        )
                    }
                }

                composable(Routes.OPEN_SOURCE) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        OpenSourceScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                composable(Routes.PRIVACY) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        PrivacyScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                composable(Routes.SPLASH_QUOTE) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        VintagePaperPage {
                        com.tracktosearch.ui.screen.dailystamp.DailyStampScreen(
                            onBack = { navController.popBackStack() },
                            // mediaType 由台词库给（movie/show），两个命名空间的 tmdbId 各自编号；
                            // traktId 传 0，详情页按 tmdbId 自己去查
                            onQuoteClick = { tmdbId, mediaType, title, year, posterUrl ->
                                navController.navigate(
                                    Routes.detailRoute(
                                        type = mediaType,
                                        traktId = 0,
                                        tmdbId = tmdbId,
                                        title = title,
                                        posterUrl = posterUrl,
                                        year = year,
                                    )
                                )
                            }
                        )
                        }
                    }
                }

                composable(
                    route = Routes.DISCOVER_FILTER,
                    arguments = listOf(
                        // entry 是路径段，路由拼出来时一定带值；defaultValue 只是解析失败时的兜底，
                        // 按「不配对」处理，退回常规淡入，好过毫无动画地直接出现
                        navArgument("entry") { type = NavType.StringType; defaultValue = "icon" }
                    ),
                    // 只有底部入口卡片那条路做容器变形（entry == "card"，见 Routes.DISCOVER_FILTER）：
                    // 卡片长成整页已经把页面显示出来了，再叠 NavHost 淡入就是同一页淡两次。
                    // 漏斗图标入口不配对，必须保留常规淡入，否则页面会毫无动画地直接出现。
                    enterTransition = {
                        if (targetState.arguments?.getString("entry") == "card")
                            EnterTransition.None
                        else null
                    },
                    // 返回同理：收回成卡片时整页若还跟着淡出，收缩中的页面会半透明地透出发现页
                    popExitTransition = {
                        if (initialState.arguments?.getString("entry") == "card")
                            ExitTransition.None
                        else null
                    }
                ) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        DiscoverFilterScreen(
                            onBack = {
                                backStackEntry.propagateMarkChangesTo(navController.previousBackStackEntry)
                                navController.popBackStack()
                            },
                            onMovieClick = { tmdbId, title ->
                                val routeType = "movie"
                                navController.navigate(Routes.detailRoute(routeType, 0, tmdbId, title))
                            },
                            onShowClick = { tmdbId, title ->
                                val routeType = "show"
                                navController.navigate(Routes.detailRoute(routeType, 0, tmdbId, title))
                            }
                        )
                    }
                }

                composable(Routes.DOUBAN_LOGIN) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        VintagePaperPage(keepPaperDialogs = true) {
                        // 识别来源：从 ActivationLoginScreen 进入时，登录成功应直达主页（豆瓣独立模式）；
                        // 从 MainScreen 进入时，沿用 onBack 返回上一页即可
                        val previousRoute = navController.previousBackStackEntry?.destination?.route
                        val fromActivationLogin = previousRoute == Routes.LOGIN
                        DoubanLoginScreen(
                            onBack = { navController.popBackStack() },
                            onLoginSuccess = if (fromActivationLogin) {
                            {
                                // 来自激活登录页：豆瓣登录成功后进入主页（豆瓣独立模式）
                                // 复用 Trakt 登录的 onboarding/默认 tab 选择逻辑（已预热，不阻塞导航）
                                navigateToMainAfterLogin()
                            }
                        } else null
                        )
                        }
                    }
                }

                composable(
                    route = Routes.DOUBAN_ITEM_DETAIL,
                    arguments = listOf(
                        navArgument("doubanId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val doubanId = backStackEntry.arguments?.getString("doubanId") ?: return@composable
                    // 提供共享转场作用域:豆瓣详情页海报源需要 LocalAnimatedVisibilityScope
                    // 才能附加 sharedBounds,与全屏 overlay 配对实现海报缩放转场
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        VintagePaperPage {
                        DoubanItemDetailScreen(
                            doubanId = doubanId,
                            onBack = { watchlistChanged, watchedChanged ->
                                backStackEntry.propagateMarkChangesTo(
                                    target = navController.previousBackStackEntry,
                                    watchlistChanged = watchlistChanged,
                                    watchedChanged = watchedChanged,
                                    doubanWatchlistRefreshRequested = watchlistChanged
                                )
                                navController.popBackStack()
                            },
                            onRetryStarted = {
                                // 重试启动后跳转到豆瓣同步进度对话框页(沿用现有导航)
                                // 实际上同步进度通过 DoubanSyncManager.progress StateFlow 暴露
                                // 这里返回上一页,同步进度由 DoubanSyncManager.progress 暴露
                                navController.popBackStack()
                            }
                        )
                        }
                    }
                }

                // 豆瓣爬取测试页仅在 DEBUG 构建注册,避免 release 暴露调试入口
                if (BuildConfig.DEBUG) {
                    composable(Routes.DOUBAN_SPIDER_TEST) {
                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                            DoubanSpiderTestScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }

                // 玻璃引擎试点页(对比 haze / backdrop)仅在 DEBUG 构建注册
                if (BuildConfig.DEBUG) {
                    composable(Routes.GLASS_PILOT) {
                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                            GlassEnginePilotScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }

                composable(
                    route = Routes.MARK_RECORDS
                ) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        MarkRecordScreen(
                            onBack = {
                                backStackEntry.propagateMarkChangesTo(navController.previousBackStackEntry)
                                navController.popBackStack()
                            },
                            onMovieClick = { traktId, tmdbId, title, imdbId, traktRating ->
                                navController.navigate(Routes.detailRoute("movie", traktId, tmdbId, title, imdbId, traktRating))
                            },
                            onShowClick = { traktId, tmdbId, title, imdbId, traktRating ->
                                navController.navigate(Routes.detailRoute("show", traktId, tmdbId, title, imdbId, traktRating))
                            }
                        )
                    }
                }

                composable(
                    route = Routes.SEARCH_SOURCES
                ) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        SearchSourcesScreen(
                            onBack = { navController.popBackStack() },
                            onHelpClick = {
                                navController.navigate(Routes.helpRoute(HelpSections.CUSTOM_SOURCE))
                            },
                            onAddFromTemplate = { templateId ->
                                navController.navigate(
                                    Routes.searchSourceEditorRoute(
                                        if (templateId.isBlank()) "blank" else "template",
                                        templateId
                                    )
                                )
                            },
                            onEditSource = { sourceId ->
                                navController.navigate(Routes.searchSourceEditorRoute("edit", sourceId))
                            }
                        )
                    }
                }

                composable(Routes.SEARCH_SOURCE_EDITOR) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val mode = backStackEntry.arguments?.getString("mode")
                        val payload = backStackEntry.arguments?.getString("payload").orEmpty()
                        val editorViewModel: SearchSourceEditorViewModel = hiltViewModel(backStackEntry)
                        LaunchedEffect(mode, payload) {
                            editorViewModel.initMode(
                                mode = when (mode) {
                                    "template" -> EditorMode.TEMPLATE
                                    "edit" -> EditorMode.EDIT
                                    "import" -> EditorMode.IMPORT
                                    else -> EditorMode.BLANK
                                },
                                templateId = payload,
                                sourceId = payload,
                                importText = payload
                            )
                        }
                        SearchSourceEditorScreen(
                            onBack = { navController.popBackStack() },
                            onSaved = { navController.popBackStack() }
                        )
                    }
                }

                composable(Routes.FEEDBACK) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val mainBackStackEntry = remember(backStackEntry) {
                            navController.getBackStackEntry(Routes.MAIN)
                        }
                        val sharedViewModel: FeedbackViewModel = hiltViewModel(
                            mainBackStackEntry
                        )
                        FeedbackScreen(
                            onBack = { navController.popBackStack() },
                            onNewFeedback = { navController.navigate(Routes.NEW_FEEDBACK) },
                            onFeedbackClick = { id -> navController.navigate(Routes.feedbackDetailRoute(id)) },
                            onCrashLogClick = { id ->
                                navController.navigate(Routes.crashLogDetailRoute(id))
                            },
                            viewModel = sharedViewModel
                        )
                    }
                }
                composable(Routes.NEW_FEEDBACK) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val mainBackStackEntry = remember(backStackEntry) {
                            navController.getBackStackEntry(Routes.MAIN)
                        }
                        val sharedViewModel: FeedbackViewModel = hiltViewModel(
                            mainBackStackEntry
                        )
                        NewFeedbackScreen(
                            onBack = { navController.popBackStack() },
                            onSuccess = {
                                sharedViewModel.loadList(refresh = true)
                                navController.popBackStack(Routes.FEEDBACK, inclusive = false)
                            },
                            viewModel = sharedViewModel
                        )
                    }
                }
                composable(
                    route = Routes.CRASH_LOG_DETAIL,
                    arguments = listOf(
                        navArgument("recordId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val recordId = backStackEntry.arguments?.getString("recordId") ?: return@composable
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        CrashLogDetailScreen(
                            recordId = recordId,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                composable(
                    route = Routes.FEEDBACK_DETAIL,
                    arguments = listOf(
                        navArgument("feedbackId") { type = NavType.StringType },
                        navArgument("replyId") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    val feedbackId = backStackEntry.arguments?.getString("feedbackId") ?: return@composable
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val replyId = backStackEntry.arguments?.getString("replyId")?.takeIf { it.isNotBlank() }
                        val mainBackStackEntry = remember(backStackEntry) {
                            navController.getBackStackEntry(Routes.MAIN)
                        }
                        val sharedViewModel: FeedbackViewModel = hiltViewModel(
                            mainBackStackEntry
                        )
                        FeedbackDetailScreen(
                            feedbackId = feedbackId,
                            replyId = replyId,
                            onBack = { navController.popBackStack() },
                            onNewFeedback = { navController.navigate(Routes.NEW_FEEDBACK) },
                            viewModel = sharedViewModel
                        )
                    }
                }
                composable(Routes.MESSAGES) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val mainBackStackEntry = remember(backStackEntry) {
                            navController.getBackStackEntry(Routes.MAIN)
                        }
                        val sharedViewModel: FeedbackViewModel = hiltViewModel(
                            mainBackStackEntry
                        )
                        MessagesScreen(
                            onBack = { navController.popBackStack() },
                            onMessageClick = { feedbackId, replyId ->
                                navController.navigate(Routes.feedbackDetailRoute(feedbackId, replyId))
                            },
                            viewModel = sharedViewModel
                        )
                    }
                }
            }

            // 版本更新检查：App 启动后延迟 0.8 秒检查，仅一次（登录页和主界面都适用）
            // 延迟 0.8s：等首屏渲染稳定后再发起网络请求，避免与 UI 抢资源导致卡顿
            val updateCheckViewModel: UpdateCheckViewModel = hiltViewModel()
            val updateRepository = updateCheckViewModel.updateRepository
            // 两个都用 remember（不用 saveable）：updateChecked 持久、updateInfo 不持久的话，
            // 配置变更重建后 updateChecked=true 而 updateInfo=null，检查被短路，
            // 本次会话永远不会再弹更新。都随重建归零，重建后重查一次即可。
            var updateInfo by remember { mutableStateOf<com.tracktosearch.data.repository.UpdateInfo?>(null) }
            var updateChecked by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(800)
                if (!updateChecked) {
                    updateChecked = true
                    try {
                        updateInfo = updateRepository.checkForUpdate()
                    } catch (_: Exception) {}
                }
            }
            // 更新弹窗（仅有新版本时才显示，覆盖在 NavHost 之上）
            // 启动 800 ms 后自动查更新，查到才弹 —— 网络回来的那一刻与任何手势都无关
            PopupShowEffect(updateInfo?.hasUpdate == true)
            updateInfo?.let { info ->
                if (info.hasUpdate) {
                    UpdateDialog(
                        updateInfo = info,
                        onDismiss = { updateInfo = null }
                    )
                }
            }
            // ---- 剪贴板自动导入：冷启动/回前台检测到分享配置时直接弹导入弹层 ----
            // 仅已授权/离线（主界面可用）时检测；按文本指纹去重（会话内 + 跨启动持久化忽略）；
            // 更新弹窗优先展示
            val autoImportVm: SearchSourcesViewModel = hiltViewModel()
            val lifecycleOwner = LocalLifecycleOwner.current
            val seenAutoImport = remember { mutableSetOf<String>() }
            var autoImportHit by remember { mutableStateOf<String?>(null) }
            var showAutoImport by remember { mutableStateOf(false) }
            var autoImportConflict by remember { mutableStateOf<CustomSearchSource?>(null) }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) {
                        scope.launch {
                            // 等认证状态就绪（冷启动时 authState 可能尚未加载完成），超时则不检测
                            val ready = withTimeoutOrNull(10_000) {
                                authStateHolder.authState.first {
                                    it == AuthState.AUTHORIZED || it == AuthState.OFFLINE
                                }
                            }
                            if (ready == null) return@launch
                            kotlinx.coroutines.delay(300)
                            if (showAutoImport || autoImportConflict != null || autoImportHit != null) return@launch
                            autoImportVm.awaitAutoImportLoaded()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                            if (text.isNullOrBlank()) return@launch
                            if (ShareCodec.decode(text.trim()) == null) return@launch
                            val fingerprint = text.trim()
                            if (!seenAutoImport.add(fingerprint)) return@launch
                            // 已取消/已导入过的指纹跨启动不再提示
                            if (autoImportVm.isAutoImportIgnored(fingerprint)) return@launch
                            autoImportHit = fingerprint
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            // 更新弹窗优先：等更新检查完成且更新弹窗关闭后再弹出导入弹层
            LaunchedEffect(autoImportHit, updateChecked, updateInfo) {
                val hit = autoImportHit ?: return@LaunchedEffect
                if (!updateChecked) return@LaunchedEffect
                if (updateInfo?.hasUpdate == true) return@LaunchedEffect
                showAutoImport = true
            }

            // 剪贴板里认出一份搜索源配置就弹，用户只是把 App 切到前台
            PopupShowEffect(showAutoImport)
            if (showAutoImport) {
                ImportSourceDialog(
                    onConfirm = { source ->
                        showAutoImport = false
                        val conflict = autoImportVm.findImportConflict(source)
                        if (conflict != null) {
                            autoImportConflict = source
                        } else {
                            autoImportHit?.let { autoImportVm.markAutoImportIgnored(it) }
                            autoImportHit = null
                            autoImportVm.importSource(source)
                            outcomeHaptics.confirm()
                            scope.launch { appSnackbarHostState.showSnackbar(importSuccessMessage) }
                        }
                    },
                    onDismiss = {
                        showAutoImport = false
                        autoImportHit?.let { autoImportVm.markAutoImportIgnored(it) }
                        autoImportHit = null
                    }
                )
            }

            autoImportConflict?.let { source ->
                AlertDialog(
                    onDismissRequest = {
                        autoImportConflict = null
                        autoImportHit?.let { autoImportVm.markAutoImportIgnored(it) }
                        autoImportHit = null
                    },
                    containerColor = floatingDialogColor(),
                    title = { Text(stringResource(R.string.search_sources_title)) },
                    text = { Text(stringResource(R.string.import_duplicate_warning)) },
                    confirmButton = {
                        TextButton(onClick = {
                            autoImportConflict = null
                            autoImportHit?.let { autoImportVm.markAutoImportIgnored(it) }
                            autoImportHit = null
                            autoImportVm.importSource(source, overwrite = true)
                            outcomeHaptics.confirm()
                            scope.launch { appSnackbarHostState.showSnackbar(importSuccessMessage) }
                        }) { Text(stringResource(R.string.import_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            autoImportConflict = null
                            autoImportHit?.let { autoImportVm.markAutoImportIgnored(it) }
                            autoImportHit = null
                        }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }
                )
            }

            // 导航级 Snackbar 宿主：盖在 NavHost 之上，登录页/主界面/子页面的导航级反馈共用一处。
            // 主界面有悬浮底栏，抬到底栏上方（与离线胶囊同一高度带），其余路由只避开系统导航栏。
            SnackbarHost(
                hostState = appSnackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f)
                    .padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            if (currentRoute == Routes.MAIN) 78.dp else 16.dp
                    )
            )
            } // Box
        } // BackdropProvider
        }
    }
}

private fun simplifyRouteName(route: String): String {
    return when {
        route.startsWith("detail/") -> {
            val type = route.removePrefix("detail/").substringBefore("/")
            "detail/$type"
        }
        route.startsWith("person/") -> "person"
        route.startsWith("search/") -> "search"
        route.startsWith("traktSearch/") -> "traktSearch"
        route.startsWith("listDetail/") -> "listDetail"
        route.startsWith("doubanItemDetail/") -> "doubanItemDetail"
        else -> route
    }
}
