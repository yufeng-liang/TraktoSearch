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
import com.tracktosearch.ui.screen.help.HelpScreen
import com.tracktosearch.ui.screen.login.LoginScreen
import com.tracktosearch.ui.screen.main.MainScreen
import com.tracktosearch.ui.screen.listdetail.TraktListDetailScreen
import com.tracktosearch.ui.screen.person.PersonScreen
import com.tracktosearch.ui.screen.search.SearchScreen
import com.tracktosearch.ui.screen.statistics.StatisticsScreen
import com.tracktosearch.ui.screen.traktsearch.TraktSearchScreen
import com.tracktosearch.ui.screen.webview.WebViewScreen
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
    const val WEBVIEW = "webview/{url}/{title}"
    const val PERSON = "person/{personId}/{personName}/{profileUrl}"
    const val STATISTICS = "statistics"
    const val TRAKT_SEARCH = "traktSearch/{type}/{query}"
    const val HELP = "help"
    const val LIST_DETAIL = "listDetail/{slug}/{listName}"

    fun listDetailRoute(slug: String, listName: String): String {
        val encodedSlug = java.net.URLEncoder.encode(slug, "UTF-8")
        val encodedName = java.net.URLEncoder.encode(listName, "UTF-8")
        return "listDetail/$encodedSlug/$encodedName"
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

    fun webViewRoute(url: String, title: String): String {
        val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        return "webview/$encodedUrl/$encodedTitle"
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
                                mainInitialTab = 2  // 登录成功后默认进入"我的"页
                                currentStartDest = Routes.MAIN
                                navController.navigate(Routes.MAIN) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            onGuestMode = {
                                currentStartDest = Routes.MAIN
                                navController.navigate(Routes.MAIN) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        )
                    }
                }

                composable(Routes.MAIN) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val isLoggedIn by authStateHolder.isLoggedIn.collectAsStateWithLifecycle(initialValue = false)

                        // 从详情页返回时，若标记已看则触发列表刷新
                        // 使用 backStackEntry.savedStateHandle 而非 navController.currentBackStackEntry
                        // 后者在导航过渡期间可能为 null 或指向错误的 entry
                        val savedState = backStackEntry.savedStateHandle
                        val watchlistChanged by savedState.getStateFlow("watchlist_changed", false).collectAsStateWithLifecycle()
                        val watchlistViewModel: WatchlistViewModel = hiltViewModel()
                        LaunchedEffect(watchlistChanged) {
                            if (watchlistChanged) {
                                watchlistViewModel.refreshIfLoaded()
                                savedState.set("watchlist_changed", false)
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
                            onOpenWebView = { url ->
                                navController.navigate(Routes.webViewRoute(url, "Trakt"))
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
                            onListClick = { slug, listName ->
                                navController.navigate(Routes.listDetailRoute(slug, listName))
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
                        fun goBack(changed: Boolean) {
                            val childChanged = backStackEntry.savedStateHandle.get<Boolean>("watchlist_changed") ?: false
                            previousEntry?.savedStateHandle?.set("watchlist_changed", changed || childChanged)
                            backStackEntry.savedStateHandle["watchlist_changed"] = false
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
                            onBack = { changed -> goBack(changed) },
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
                            onMovieClick = { traktId, tmdbId, title, imdbId, traktRating ->
                                navController.navigate(Routes.detailRoute("movie", traktId, tmdbId, title, imdbId, traktRating))
                            }
                        )
                    }
                }

                composable(
                    route = Routes.WEBVIEW,
                    arguments = listOf(
                        navArgument("url") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    val url = java.net.URLDecoder.decode(
                        backStackEntry.arguments?.getString("url") ?: "", "UTF-8"
                    )
                    val title = java.net.URLDecoder.decode(
                        backStackEntry.arguments?.getString("title") ?: "", "UTF-8"
                    )
                    // 从 WebView 返回时触发想看列表刷新（用户可能在 Trakt 网站上添加了新内容）
                    val previousEntry = navController.previousBackStackEntry
                    WebViewScreen(
                        url = url,
                        title = title,
                        onBack = {
                            previousEntry?.savedStateHandle?.set("watchlist_changed", true)
                            navController.popBackStack()
                        }
                    )
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
                        navArgument("slug") { type = NavType.StringType },
                        navArgument("listName") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val slug = java.net.URLDecoder.decode(
                            backStackEntry.arguments?.getString("slug") ?: "", "UTF-8"
                        )
                        val listName = java.net.URLDecoder.decode(
                            backStackEntry.arguments?.getString("listName") ?: "", "UTF-8"
                        )
                        TraktListDetailScreen(
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
            }
            } // Box
        }
    }
}
