package com.tracktosearch.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import android.net.Uri
import android.widget.Toast
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.remote.trakt.TraktAuthManager
import com.tracktosearch.data.remote.trakt.TraktConnectionState
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.SyncMode
import com.tracktosearch.data.repository.WatchlistMediaType
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.UpdateDialog
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
import com.tracktosearch.ui.screen.douban.DoubanSyncViewModel
import com.tracktosearch.ui.screen.help.HelpScreen
import com.tracktosearch.ui.screen.listdetail.TraktListDetailScreen
import com.tracktosearch.ui.screen.login.ActivationLoginScreen
import com.tracktosearch.ui.screen.main.MainScreen
import com.tracktosearch.ui.screen.markrecord.MarkRecordScreen
import com.tracktosearch.ui.screen.person.PersonScreen
import com.tracktosearch.ui.screen.search.SearchScreen
import com.tracktosearch.ui.screen.ai.AI_DOUBAN_NAV_PREFIX
import com.tracktosearch.ui.screen.statistics.StatisticsScreen
import com.tracktosearch.ui.screen.traktsearch.TraktSearchScreen
import com.tracktosearch.ui.screen.watchlist.WatchlistViewModel
import com.tracktosearch.data.util.CurrentPageHolder
import com.tracktosearch.data.util.UserActionTracker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DefaultTabEntryPoint {
    fun defaultTabStorage(): com.tracktosearch.data.local.DefaultTabStorage
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SharedTransitionEntryPoint {
    fun sharedTransitionStorage(): com.tracktosearch.data.local.SharedTransitionStorage
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GuestModeEntryPoint {
    fun guestModeStorage(): com.tracktosearch.data.local.GuestModeStorage
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
    const val DETAIL = "detail/{type}/{traktId}/{tmdbId}/{title}/{imdbId}/{traktRating}?inWatchlist={inWatchlist}&isWatched={isWatched}&doubanId={doubanId}"
    const val SEARCH = "search/{keyword}"
    const val PERSON = "person/{personId}/{personName}/{profileUrl}"
    const val STATISTICS = "statistics"
    const val TRAKT_SEARCH = "traktSearch/{type}/{query}"
    const val HELP = "help"
    const val LIST_DETAIL = "listDetail/{listId}/{listName}"
    const val DISCOVER_FILTER = "discoverFilter"
    const val DOUBAN_LOGIN = "doubanLogin"
    const val DOUBAN_ITEM_DETAIL = "doubanItemDetail/{doubanId}"
    const val DOUBAN_SPIDER_TEST = "doubanSpiderTest"
    const val MARK_RECORDS = "markRecords"
    const val FEEDBACK = "feedback"
    const val FEEDBACK_DETAIL = "feedbackDetail/{feedbackId}?replyId={replyId}"
    const val NEW_FEEDBACK = "newFeedback"
    const val CRASH_LOG_DETAIL = "crashLogDetail/{recordId}"
    const val MESSAGES = "messages"

    fun feedbackDetailRoute(feedbackId: String, replyId: String? = null): String =
        "feedbackDetail/$feedbackId?replyId=${replyId ?: ""}"

    fun crashLogDetailRoute(recordId: String): String = "crashLogDetail/$recordId"

    fun doubanItemDetailRoute(doubanId: String): String = "doubanItemDetail/$doubanId"

    fun listDetailRoute(listId: Int, listName: String): String {
        val encodedName = java.net.URLEncoder.encode(listName, "UTF-8")
        return "listDetail/$listId/$encodedName"
    }

    fun traktSearchRoute(type: String, query: String): String {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        return "traktSearch/$type/$encodedQuery"
    }

    fun detailRoute(type: String, traktId: Int, tmdbId: Int, title: String, imdbId: String = "", traktRating: Double = 0.0, inWatchlist: Boolean = false, isWatched: Boolean = false, doubanId: String? = null): String {
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val encodedImdbId = java.net.URLEncoder.encode(imdbId, "UTF-8")
        val encodedDoubanId = doubanId?.let { java.net.URLEncoder.encode(it, "UTF-8") }
        var route = "detail/$type/$traktId/$tmdbId/$encodedTitle/$encodedImdbId/$traktRating"
        if (inWatchlist || isWatched || encodedDoubanId != null) {
            val params = mutableListOf<String>()
            if (inWatchlist) params.add("inWatchlist=true")
            if (isWatched) params.add("isWatched=true")
            encodedDoubanId?.let { params.add("doubanId=$it") }
            route += "?${params.joinToString("&")}"
        }
        return route
    }

    fun searchRoute(keyword: String): String {
        val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
        return "search/$encodedKeyword"
    }

    fun personRoute(personId: Int, personName: String, profileUrl: String): String {
        val encodedName = java.net.URLEncoder.encode(personName, "UTF-8")
        val encodedProfileUrl = java.net.URLEncoder.encode(profileUrl, "UTF-8")
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

@OptIn(ExperimentalSharedTransitionApi::class)
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
    onLoginSuccess: () -> Unit = {}
) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val context = LocalContext.current
    val loginFailedMessage = stringResource(R.string.login_failed)
    val loginDeniedMessage = stringResource(R.string.login_denied)
    val authOfflineMessage = stringResource(R.string.auth_offline_mode)
    val syncAlreadyRunningMessage = stringResource(R.string.sync_already_running)
    // 页面变化追踪（错误日志上下文）
    navController.addOnDestinationChangedListener { _, destination, _ ->
        val route = destination.route ?: ""
        CurrentPageHolder.currentRoute = route
        val pageName = simplifyRouteName(route)
        CurrentPageHolder.currentPageName = pageName
        UserActionTracker.record("nav", "to_page", pageName)
    }
    var currentStartDest by remember { mutableStateOf(startDestination) }
    // 读取默认启动页设置
    val defaultTabStorage = EntryPointAccessors.fromApplication(context, DefaultTabEntryPoint::class.java).defaultTabStorage()
    val storedDefaultTab by defaultTabStorage.defaultTab.collectAsStateWithLifecycle()
    // 共享元素转场动画开关：StateFlow 在 MainActivity 预加载后已持有磁盘真实值，collectAsStateWithLifecycle 无需 initialValue，首次组合即为真实值
    val sharedTransitionStorage = EntryPointAccessors.fromApplication(context, SharedTransitionEntryPoint::class.java).sharedTransitionStorage()
    val sharedTransitionEnabled by sharedTransitionStorage.enabledState.collectAsStateWithLifecycle()
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
    var directTraktLoginActive by rememberSaveable { mutableStateOf(false) }

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
                            Toast.makeText(context, loginFailedMessage, Toast.LENGTH_SHORT).show()
                        }
                    }
            }
            launch {
                OAuthCallback.authDeniedFlow
                    .filter { it }
                    .collect {
                        OAuthCallback.setAuthDenied(false)
                        directTraktLoginActive = false
                        Toast.makeText(context, loginDeniedMessage, Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, loginFailedMessage, Toast.LENGTH_SHORT).show()
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
    // 豆瓣独立模式：trakt 未连 + 豆瓣已登录
    val isDoubanMode = isDoubanLoggedIn && !isTraktConnected
    // MainScreen 的 isLoggedIn：trakt 已连 OR 豆瓣已登录（非 GUEST 模式才显示 WatchlistScreen）
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

    LaunchedEffect(currentAuthState) {
        if (currentAuthState == AuthState.OFFLINE) {
            Toast.makeText(context, authOfflineMessage, Toast.LENGTH_LONG).show()
        }
        if ((currentAuthState == AuthState.UNAUTHORIZED || currentAuthState == AuthState.EXPIRED) &&
            navController.currentDestination?.route == Routes.MAIN
        ) {
            currentStartDest = Routes.LOGIN
            navController.navigate(Routes.LOGIN) {
                popUpTo(Routes.MAIN) { inclusive = true }
            }
        }
    }

    // 监听通知深链路导航指令（上映/新季通知点击后跳转详情页）
    // 直接读 StateFlow.value 避免 Compose 状态捕获问题
    LaunchedEffect(Unit) {
        DeepLinkNavigator.pendingNavigation.collect { target ->
            if (target != null && (authStateHolder.authState.value == AuthState.AUTHORIZED || authStateHolder.authState.value == AuthState.OFFLINE)) {
                navController.navigate(
                    Routes.detailRoute(target.type, target.traktId, target.tmdbId, target.title)
                )
                DeepLinkNavigator.consume()
            }
        }
    }

    SharedTransitionLayout {
        CompositionLocalProvider(
            LocalSharedTransitionScope provides this@SharedTransitionLayout,
            com.tracktosearch.ui.component.LocalSharedTransitionEnabled provides sharedTransitionEnabled
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
            NavHost(
                navController = navController,
                startDestination = currentStartDest
            ) {
                composable(Routes.LOGIN) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val fromGuestMode = navController.previousBackStackEntry?.destination?.route == Routes.MAIN
                        ActivationLoginScreen(
                            redirectToBrowser = fromGuestMode,
                            expired = currentAuthState == AuthState.EXPIRED,
                            onLoginSuccess = {
                                // Trakt 登录成功：写入 SessionModeManager，由其驱动 UI 切换到 TRAKT 模式
                                sessionModeManager.setTraktConnectionState(TraktConnectionState.CONNECTED)
                                onLoginSuccess()
                                // 登录成功后清除访客模式标记
                                val guestModeStorage = EntryPointAccessors.fromApplication(context, GuestModeEntryPoint::class.java).guestModeStorage()
                                scope.launch { guestModeStorage.setGuestMode(false) }
                                // 新用户（未完成新手引导）登录后默认进搜索页(0)，避免我的页无谓加载 watchlist
                                // 老用户默认进我的页(2)查看 watchlist
                                scope.launch {
                                    if (SearchNavigator.pending.value) {
                                        mainInitialTab = 0
                                    } else {
                                        val onboardingCompleted = OnboardingStorage(context).isCompleted.first()
                                        mainInitialTab = if (onboardingCompleted) 2 else 0
                                    }
                                    loginTabOverride = true  // 阻止 storedDefaultTab 覆盖登录意图
                                    currentStartDest = Routes.MAIN
                                    navController.navigate(Routes.MAIN) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            },
                            onDoubanLogin = {
                                // 豆瓣登录入口：跳转到 DoubanLoginScreen，
                                // 登录成功后由 DOUBAN_LOGIN composable 的 onLoginSuccess 处理进入主页
                                navController.navigate(Routes.DOUBAN_LOGIN)
                            },
                            onGuestMode = {
                                // 持久化访客模式状态，跨 App 重启保留
                                val guestModeStorage = EntryPointAccessors.fromApplication(context, GuestModeEntryPoint::class.java).guestModeStorage()
                                scope.launch { guestModeStorage.setGuestMode(true) }
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
                        LaunchedEffect(Unit) {
                            if (!rollbackChecked) {
                                rollbackChecked = true
                                rollbackCount = doubanSyncManager.getRollbackCount()
                            }
                        }
                        if (rollbackCount > 0) {
                            com.tracktosearch.ui.screen.douban.DoubanRollbackDialog(
                                rollbackCount = rollbackCount,
                                onDismiss = { rollbackCount = 0 },
                                onRestore = {
                                    rollbackCount = 0
                                    scope.launch { doubanSyncManager.restoreRollback() }
                                },
                                onDiscard = {
                                    rollbackCount = 0
                                    scope.launch { doubanSyncManager.discardRollback() }
                                }
                            )
                        }

                        var pendingCount by rememberSaveable { mutableIntStateOf(0) }
                        var pendingChecked by rememberSaveable { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            if (!pendingChecked) {
                                pendingChecked = true
                                pendingCount = doubanSyncManager.getPendingItemsCount()
                            }
                        }
                        if (pendingCount > 0) {
                            com.tracktosearch.ui.screen.douban.DoubanPendingItemsDialog(
                                pendingCount = pendingCount,
                                onDismiss = {
                                    // 用户点取消:清空数据库 pending items,避免下次启动再次弹窗
                                    // pending items 只是已爬到但未处理的列表数据,丢弃不影响已同步标记
                                    pendingCount = 0
                                    scope.launch { doubanSyncManager.clearPendingItems() }
                                },
                                onContinue = {
                                    // 继续同步:走 startResume,跳过列表爬取
                                    // 留在 MainScreen，Watchlist 横幅会显示进度
                                    pendingCount = 0
                                    val started = doubanSyncManager.startResume()
                                    if (!started) {
                                        Toast.makeText(context, syncAlreadyRunningMessage, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onFullSync = {
                                    // 完整同步:清空 pending items,走 FULL_REWRITE
                                    // 留在 MainScreen，Watchlist 横幅会显示进度
                                    pendingCount = 0
                                    scope.launch {
                                        doubanSyncManager.clearPendingItems()
                                        val started = doubanSyncManager.startSync(SyncMode.FULL_REWRITE)
                                        if (!started) {
                                            Toast.makeText(context, syncAlreadyRunningMessage, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }

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
                                            title = item.title,
                                            imdbId = item.imdbId,
                                            traktRating = item.traktRating,
                                            inWatchlist = inWatchlist,
                                            isWatched = isWatched,
                                            doubanId = doubanId
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
                            onMarkRecordsClick = {
                                navController.navigate(Routes.MARK_RECORDS)
                            },
                            onTraktSearch = { type, query ->
                                navController.navigate(Routes.traktSearchRoute(type, query))
                            },
                            onPersonClick = { tmdbId, name, profileUrl, avatarColor ->
                                navController.navigate(Routes.personRoute(tmdbId, name, profileUrl ?: ""))
                            },
                            onListClick = { listId, listName ->
                                navController.navigate(Routes.listDetailRoute(listId, listName))
                            },
                            onLogout = {
                                // Trakt 退出登录：只清除 Trakt 连接状态，不清除网关激活令牌，
                                // 也不跳转到激活/登录页——用户仍处于已激活或访客模式，留在设置页即可。
                                sessionModeManager.setTraktConnectionState(TraktConnectionState.DISCONNECTED)
                                scope.launch { authStateHolder.disconnectTrakt() }
                                // 若豆瓣也未登录，用户实际进入访客状态，同步 isGuestMode 标记
                                // 避免网关后续被撤销时重启 App 因 isGuestMode=false 而进入登录页（应进入主页访客模式）
                                val guestModeStorage = EntryPointAccessors.fromApplication(context, GuestModeEntryPoint::class.java).guestModeStorage()
                                scope.launch {
                                    val doubanLoggedIn = doubanAuthStorage.isLoggedIn.value
                                    if (!doubanLoggedIn) {
                                        guestModeStorage.setGuestMode(true)
                                    }
                                }
                                // 不修改 currentStartDest，不导航；MainScreen 的「我的」tab 会自动显示登录提示
                            },
                            onHelpClick = {
                                navController.navigate(Routes.HELP)
                            },
                            onRestartOnboarding = {
                                // 重置引导标记，重新显示新手引导
                                scope.launch {
                                    OnboardingStorage(context).setCompleted(false)
                                }
                            },
                            onFilterDiscoverClick = {
                                navController.navigate(Routes.DISCOVER_FILTER)
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
                            onFeedbackClick = {
                                navController.navigate(Routes.FEEDBACK)
                            },
                            onMessagesClick = {
                                navController.navigate(Routes.MESSAGES)
                            }
                        )
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
                         navArgument("doubanId") { type = NavType.StringType; defaultValue = "" }
                    ),
                    // 详情页转场:用纯 fade 替代默认的 fadeIn+slideIn,去掉水平偏移
                    // slide 每帧都需重新计算详情页所有子节点的水平位置(转场期间放大开销)。
                    // 时长 220ms 与默认对齐,避免与共享元素 spring 动画违和;
                    // 共享元素(海报)由 SharedTransitionLayout 独立接管,不依赖 NavHost 的 slide。
                    enterTransition = { fadeIn(animationSpec = tween(220)) },
                    exitTransition = { fadeOut(animationSpec = tween(220)) },
                    popEnterTransition = { fadeIn(animationSpec = tween(220)) },
                    popExitTransition = { fadeOut(animationSpec = tween(220)) }
                ) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val type = backStackEntry.arguments?.getString("type") ?: "movie"
                        val traktId = backStackEntry.arguments?.getInt("traktId") ?: 0
                        val tmdbId = backStackEntry.arguments?.getInt("tmdbId") ?: 0
                        val title = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("title") ?: "", "UTF-8")
                        val imdbId = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("imdbId") ?: "", "UTF-8")
                        val traktRating = backStackEntry.arguments?.getFloat("traktRating")?.toDouble() ?: 0.0
                         val inWatchlist = backStackEntry.arguments?.getBoolean("inWatchlist") ?: false
                         val isWatched = backStackEntry.arguments?.getBoolean("isWatched") ?: false
                         val doubanId = backStackEntry.arguments?.getString("doubanId")?.takeIf { it.isNotBlank() }

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
                            imdbId = imdbId,
                            traktRating = traktRating,
                             initialInWatchlist = inWatchlist,
                             initialIsWatched = isWatched,
                             doubanId = doubanId,
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
                            onNavigateToLogin = {
                                navController.navigate(Routes.LOGIN) {
                                    popUpTo(Routes.MAIN) { inclusive = false }
                                }
                            }
                        )
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
                            }
                        )
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
                        val personId = backStackEntry.arguments?.getInt("personId") ?: 0
                        val personName = java.net.URLDecoder.decode(
                            backStackEntry.arguments?.getString("personName") ?: "", "UTF-8"
                        )
                        val profileUrl = java.net.URLDecoder.decode(
                            backStackEntry.arguments?.getString("profileUrl") ?: "", "UTF-8"
                        ).takeIf { it.isNotEmpty() }
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

                composable(
                    route = Routes.LIST_DETAIL,
                    arguments = listOf(
                        navArgument("listId") { type = NavType.IntType },
                        navArgument("listName") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val listId = backStackEntry.arguments?.getInt("listId") ?: 0
                        val listName = java.net.URLDecoder.decode(
                            backStackEntry.arguments?.getString("listName") ?: "", "UTF-8"
                        )
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

                composable(Routes.STATISTICS) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        StatisticsScreen(
                            onBack = { navController.popBackStack() }
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
                        val query = java.net.URLDecoder.decode(
                            backStackEntry.arguments?.getString("query") ?: "", "UTF-8"
                        )
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
                            }
                        )
                    }
                }

                composable(Routes.HELP) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        HelpScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                composable(Routes.DISCOVER_FILTER) { backStackEntry ->
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
                        // 识别来源：从 ActivationLoginScreen 进入时，登录成功应直达主页（豆瓣独立模式）；
                        // 从 MainScreen 进入时，沿用 onBack 返回上一页即可
                        val previousRoute = navController.previousBackStackEntry?.destination?.route
                        val fromActivationLogin = previousRoute == Routes.LOGIN
                        DoubanLoginScreen(
                            onBack = { navController.popBackStack() },
                            onLoginSuccess = if (fromActivationLogin) {
                            {
                                // 来自激活登录页：豆瓣登录成功后进入主页（豆瓣独立模式）
                                // 复用 Trakt 登录的 onboarding/默认 tab 选择逻辑
                                // 登录成功后清除访客模式标记，避免网关撤销后状态不一致
                                val guestModeStorage = EntryPointAccessors.fromApplication(context, GuestModeEntryPoint::class.java).guestModeStorage()
                                scope.launch {
                                    guestModeStorage.setGuestMode(false)
                                    if (SearchNavigator.pending.value) {
                                        mainInitialTab = 0
                                    } else {
                                        val onboardingCompleted = OnboardingStorage(context).isCompleted.first()
                                        mainInitialTab = if (onboardingCompleted) 2 else 0
                                    }
                                    loginTabOverride = true  // 阻止 storedDefaultTab 覆盖登录意图
                                    currentStartDest = Routes.MAIN
                                    navController.navigate(Routes.MAIN) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            }
                        } else null
                        )
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

                // 豆瓣爬取测试页仅在 DEBUG 构建注册,避免 release 暴露调试入口
                if (BuildConfig.DEBUG) {
                    composable(Routes.DOUBAN_SPIDER_TEST) {
                        DoubanSpiderTestScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                composable(Routes.MARK_RECORDS) { backStackEntry ->
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
                    CrashLogDetailScreen(
                        recordId = recordId,
                        onBack = { navController.popBackStack() }
                    )
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

            // 版本更新检查：App 启动后延迟 0.8 秒检查，仅一次（登录页和主界面都适用）
            // 延迟 0.8s：等首屏渲染稳定后再发起网络请求，避免与 UI 抢资源导致卡顿
            val updateCheckViewModel: UpdateCheckViewModel = hiltViewModel()
            val updateRepository = updateCheckViewModel.updateRepository
            var updateInfo by remember { mutableStateOf<com.tracktosearch.data.repository.UpdateInfo?>(null) }
            var updateChecked by rememberSaveable { mutableStateOf(false) }
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
            updateInfo?.let { info ->
                if (info.hasUpdate) {
                    UpdateDialog(
                        updateInfo = info,
                        onDismiss = { updateInfo = null }
                    )
                }
            }
            } // Box
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
