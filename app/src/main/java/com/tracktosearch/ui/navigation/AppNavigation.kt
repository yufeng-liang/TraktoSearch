package com.tracktosearch.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.data.local.DefaultTabStorage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.snapshotFlow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.OnboardingStorage
import com.tracktosearch.data.repository.UpdateRepository
import com.tracktosearch.ui.screen.watchlist.WatchlistViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.UpdateDialog
import com.tracktosearch.ui.screen.detail.DetailScreen
import com.tracktosearch.ui.screen.douban.DoubanFailuresScreen
import com.tracktosearch.ui.screen.douban.DoubanItemDetailScreen
import com.tracktosearch.ui.screen.douban.DoubanLoginScreen
import com.tracktosearch.ui.screen.douban.DoubanSyncDialog
import com.tracktosearch.ui.screen.douban.DoubanSyncViewModel
import com.tracktosearch.ui.screen.help.HelpScreen
import com.tracktosearch.ui.screen.discoverfilter.DiscoverFilterScreen
import com.tracktosearch.ui.screen.login.LoginScreen
import com.tracktosearch.ui.screen.main.MainScreen
import com.tracktosearch.ui.screen.listdetail.TraktListDetailScreen
import com.tracktosearch.ui.screen.person.PersonScreen
import com.tracktosearch.ui.screen.search.SearchScreen
import com.tracktosearch.ui.screen.statistics.StatisticsScreen
import com.tracktosearch.ui.screen.traktsearch.TraktSearchScreen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DefaultTabEntryPoint {
    fun defaultTabStorage(): com.tracktosearch.data.local.DefaultTabStorage
}

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val DETAIL = "detail/{type}/{traktId}/{tmdbId}/{title}/{imdbId}/{traktRating}?inWatchlist={inWatchlist}&isWatched={isWatched}"
    const val SEARCH = "search/{keyword}"
    const val PERSON = "person/{personId}/{personName}/{profileUrl}"
    const val STATISTICS = "statistics"
    const val TRAKT_SEARCH = "traktSearch/{type}/{query}"
    const val HELP = "help"
    const val LIST_DETAIL = "listDetail/{listId}/{listName}"
    const val DISCOVER_FILTER = "discoverFilter"
    const val DOUBAN_LOGIN = "doubanLogin"
    const val DOUBAN_FAILURES = "doubanFailures"
    const val DOUBAN_ITEM_DETAIL = "doubanItemDetail/{doubanId}"

    fun doubanItemDetailRoute(doubanId: String): String = "doubanItemDetail/$doubanId"

    fun listDetailRoute(listId: Int, listName: String): String {
        val encodedName = java.net.URLEncoder.encode(listName, "UTF-8")
        return "listDetail/$listId/$encodedName"
    }

    fun traktSearchRoute(type: String, query: String): String {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        return "traktSearch/$type/$encodedQuery"
    }

    fun detailRoute(type: String, traktId: Int, tmdbId: Int, title: String, imdbId: String = "", traktRating: Double = 0.0, inWatchlist: Boolean = false, isWatched: Boolean = false): String {
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val encodedImdbId = java.net.URLEncoder.encode(imdbId, "UTF-8")
        var route = "detail/$type/$traktId/$tmdbId/$encodedTitle/$encodedImdbId/$traktRating"
        if (inWatchlist || isWatched) {
            val params = mutableListOf<String>()
            if (inWatchlist) params.add("inWatchlist=true")
            if (isWatched) params.add("isWatched=true")
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

class AuthStateHolder @Inject constructor(
    private val tokenStorage: TokenStorage
) {
    val isLoggedIn: Flow<Boolean> = tokenStorage.accessToken.map { token ->
        !token.isNullOrEmpty()
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavigation(
    startDestination: String,
    initialTab: Int = 0,
    authStateHolder: AuthStateHolder,
    onLoginSuccess: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    var currentStartDest by remember { mutableStateOf(startDestination) }
    // 读取默认启动页设置
    val defaultTabStorage = EntryPointAccessors.fromApplication(context, DefaultTabEntryPoint::class.java).defaultTabStorage()
    val storedDefaultTab by defaultTabStorage.defaultTab.collectAsStateWithLifecycle(initialValue = DefaultTabStorage.DEFAULT_TAB_SEARCH)
    // 使用初始 tab（已由 MainActivity 根据登录状态和用户设置决定）
    var mainInitialTab by remember { mutableIntStateOf(initialTab) }
    // 监听默认启动页设置变化
    LaunchedEffect(storedDefaultTab) {
        mainInitialTab = storedDefaultTab
    }
    val scope = rememberCoroutineScope()

    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this@SharedTransitionLayout) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            NavHost(
                navController = navController,
                startDestination = currentStartDest
            ) {
                composable(Routes.LOGIN) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val fromGuestMode = navController.previousBackStackEntry?.destination?.route == Routes.MAIN
                        LoginScreen(
                            redirectToBrowser = fromGuestMode,
                            onLoginSuccess = {
                                onLoginSuccess()
                                // 新用户（未完成新手引导）登录后默认进搜索页(0)，避免我的页无谓加载 watchlist
                                // 老用户默认进我的页(2)查看 watchlist
                                scope.launch {
                                    val onboardingCompleted = OnboardingStorage(context).isCompleted.first()
                                    mainInitialTab = if (onboardingCompleted) 2 else 0
                                    currentStartDest = Routes.MAIN
                                    navController.navigate(Routes.MAIN) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            },
                            onGuestMode = {
                                currentStartDest = Routes.MAIN
                                navController.navigate(Routes.MAIN) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            onDoubanImport = {
                                navController.navigate(Routes.DOUBAN_LOGIN)
                            }
                        )
                    }
                }

                composable(Routes.MAIN) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val isLoggedIn by authStateHolder.isLoggedIn.collectAsStateWithLifecycle(initialValue = false)

                        // 从详情页返回时，按变更类型分别刷新想看列表或已看历史
                        // 使用 backStackEntry.savedStateHandle 而非 navController.currentBackStackEntry
                        // 后者在导航过渡期间可能为 null 或指向错误的 entry
                        val savedState = backStackEntry.savedStateHandle
                        val watchlistChanged by savedState.getStateFlow("watchlist_changed", false).collectAsStateWithLifecycle()
                        val watchedChanged by savedState.getStateFlow("watched_changed", false).collectAsStateWithLifecycle()
                        val watchlistViewModel: WatchlistViewModel = hiltViewModel()
                        LaunchedEffect(watchlistChanged) {
                            if (watchlistChanged) {
                                watchlistViewModel.refreshWatchlist()
                                savedState.set("watchlist_changed", false)
                            }
                        }
                        LaunchedEffect(watchedChanged) {
                            if (watchedChanged) {
                                watchlistViewModel.refreshWatched()
                                savedState.set("watched_changed", false)
                            }
                        }

                        // 版本更新检查：首页加载完成后检查，仅一次
                        // 已登录：等想看列表加载完成；未登录：立即检查
                        val updateRepository: UpdateRepository = hiltViewModel<UpdateCheckViewModel>().updateRepository
                        var updateInfo by remember { mutableStateOf<com.tracktosearch.data.repository.UpdateInfo?>(null) }
                        var updateChecked by rememberSaveable { mutableStateOf(false) }
                        val uiState by watchlistViewModel.uiState.collectAsStateWithLifecycle()
                        // 用 snapshotFlow 监听条件，避免 LaunchedEffect key 变化导致协程取消
                        LaunchedEffect(Unit) {
                            snapshotFlow {
                                if (isLoggedIn) {
                                    uiState.moviesLoaded && uiState.showsLoaded
                                } else {
                                    true
                                }
                            }.first { it }
                            if (!updateChecked) {
                                updateChecked = true
                                try {
                                    updateInfo = updateRepository.checkForUpdate()
                                } catch (_: Exception) {}
                            }
                        }

                        // 更新弹窗（仅有新版本时才显示）
                        updateInfo?.let { info ->
                            if (info.hasUpdate) {
                                UpdateDialog(
                                    updateInfo = info,
                                    onDismiss = { updateInfo = null }
                                )
                            }
                        }

                        // 豆瓣同步续传检测:App 启动时检测是否有未处理完的 pending items
                        // 优先级:pending items 优先于 failures 重试
                        // - 有 pending items → 弹续传对话框(继续同步/完整同步)
                        // - 无 pending items 但有 failures → 弹失败重试对话框(由 SettingsScreen 处理)
                        val doubanSyncManager = hiltViewModel<DoubanSyncViewModel>().doubanSyncManager
                        var pendingCount by remember { mutableStateOf(0) }
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
                                onDismiss = { pendingCount = 0 },
                                onContinue = {
                                    // 继续同步:走 startResume,跳过列表爬取
                                    pendingCount = 0
                                    navController.navigate(Routes.DOUBAN_LOGIN)
                                    doubanSyncManager.startResume()
                                },
                                onFullSync = {
                                    // 完整同步:清空 pending items,走 startSync
                                    pendingCount = 0
                                    scope.launch {
                                        doubanSyncManager.clearPendingItems()
                                    }
                                    navController.navigate(Routes.DOUBAN_LOGIN)
                                }
                            )
                        }

                        MainScreen(
                            initialTab = mainInitialTab,
                            isLoggedIn = isLoggedIn,
                            onMovieClick = { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                navController.navigate(Routes.detailRoute("movie", traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched))
                            },
                            onShowClick = { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                navController.navigate(Routes.detailRoute("show", traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched))
                            },
                            onSearchClick = { keyword ->
                                navController.navigate(Routes.searchRoute(keyword))
                            },
                            onNavigateToLogin = {
                                navController.navigate(Routes.LOGIN) {
                                    popUpTo(Routes.MAIN) { inclusive = false }
                                }
                            },
                            onStatisticsClick = {
                                navController.navigate(Routes.STATISTICS)
                            },
                            onTraktSearch = { type, query ->
                                navController.navigate(Routes.traktSearchRoute(type, query))
                            },
                            onPersonClick = { tmdbId, name, profileUrl ->
                                navController.navigate(Routes.personRoute(tmdbId, name, profileUrl))
                            },
                            onListClick = { listId, listName ->
                                navController.navigate(Routes.listDetailRoute(listId, listName))
                            },
                            onLogout = {
                                onLogout()
                                currentStartDest = Routes.LOGIN
                                navController.navigate(Routes.LOGIN) {
                                    popUpTo(0) { inclusive = true }
                                }
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
                                navController.navigate(Routes.DOUBAN_LOGIN)
                            },
                            onDoubanFailures = {
                                navController.navigate(Routes.DOUBAN_FAILURES)
                            },
                            onNavigateToDoubanLogin = {
                                navController.navigate(Routes.DOUBAN_LOGIN)
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
                        navArgument("isWatched") { type = NavType.BoolType; defaultValue = false }
                    )
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

                        // 用于在标记已看/想看后通知上级列表页刷新
                        // 同时检查从子详情页（推荐跳转）传递回来的变更标记
                        val previousEntry = navController.previousBackStackEntry
                        fun goBack(watchlistChanged: Boolean, watchedChanged: Boolean) {
                            val childWatchlistChanged = backStackEntry.savedStateHandle.get<Boolean>("watchlist_changed") ?: false
                            val childWatchedChanged = backStackEntry.savedStateHandle.get<Boolean>("watched_changed") ?: false
                            previousEntry?.savedStateHandle?.set("watchlist_changed", watchlistChanged || childWatchlistChanged)
                            previousEntry?.savedStateHandle?.set("watched_changed", watchedChanged || childWatchedChanged)
                            backStackEntry.savedStateHandle["watchlist_changed"] = false
                            backStackEntry.savedStateHandle["watched_changed"] = false
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
                            onBack = { wlChanged, wChanged -> goBack(wlChanged, wChanged) },
                            onPersonClick = { personId, personName, profileUrl ->
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
                            onBack = { navController.popBackStack() },
                            onMovieClick = { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                navController.navigate(Routes.detailRoute("movie", traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched))
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
                            onBack = { navController.popBackStack() },
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
                            onBack = { navController.popBackStack() },
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

                        // 从详情页返回时通知想看列表刷新（Trakt搜索页本身不修改想看列表，不需要触发刷新）
                        val previousEntry = navController.previousBackStackEntry
                        TraktSearchScreen(
                            initialQuery = query,
                            type = mediaType,
                            onBack = {
                                navController.popBackStack()
                            },
                            onItemClick = { type, traktId, tmdbId, title, imdbId, traktRating ->
                                val routeType = when (type) {
                                    MediaType.SHOW -> "show"
                                    else -> "movie"
                                }
                                navController.navigate(Routes.detailRoute(routeType, traktId, tmdbId, title, imdbId, traktRating))
                            },
                            onPersonClick = { tmdbId, name, profileUrl ->
                                navController.navigate(Routes.personRoute(tmdbId, name, profileUrl))
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

                composable(Routes.DISCOVER_FILTER) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        DiscoverFilterScreen(
                            onBack = { navController.popBackStack() },
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
                        DoubanLoginScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                composable(Routes.DOUBAN_FAILURES) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        DoubanFailuresScreen(
                            onBack = { navController.popBackStack() },
                            onItemClick = { doubanId ->
                                navController.navigate(Routes.doubanItemDetailRoute(doubanId))
                            }
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
                    DoubanItemDetailScreen(
                        doubanId = doubanId,
                        onBack = { navController.popBackStack() },
                        onRetryStarted = {
                            // 重试启动后跳转到豆瓣同步进度对话框页(沿用现有导航)
                            // 实际上同步进度通过 DoubanSyncManager.progress StateFlow 暴露
                            // 这里仅 popUp 到失败项查看页,让用户返回查看进度
                            navController.popBackStack()
                        }
                    )
                }
            }
            } // Box
        }
    }
}
