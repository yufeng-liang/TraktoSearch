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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.repository.UpdateRepository
import com.tracktosearch.ui.screen.watchlist.WatchlistViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.UpdateDialog
import com.tracktosearch.ui.screen.detail.DetailScreen
import com.tracktosearch.ui.screen.login.LoginScreen
import com.tracktosearch.ui.screen.main.MainScreen
import com.tracktosearch.ui.screen.person.PersonScreen
import com.tracktosearch.ui.screen.search.SearchScreen
import com.tracktosearch.ui.screen.statistics.StatisticsScreen
import com.tracktosearch.ui.screen.webview.WebViewScreen
import javax.inject.Inject

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val DETAIL = "detail/{type}/{traktId}/{tmdbId}/{title}/{imdbId}/{traktRating}"
    const val SEARCH = "search/{keyword}"
    const val WEBVIEW = "webview/{url}/{title}"
    const val PERSON = "person/{personId}/{personName}"
    const val STATISTICS = "statistics"

    fun detailRoute(type: String, traktId: Int, tmdbId: Int, title: String, imdbId: String = "", traktRating: Double = 0.0): String {
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val encodedImdbId = java.net.URLEncoder.encode(imdbId, "UTF-8")
        return "detail/$type/$traktId/$tmdbId/$encodedTitle/$encodedImdbId/$traktRating"
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

    fun personRoute(personId: Int, personName: String): String {
        val encodedName = java.net.URLEncoder.encode(personName, "UTF-8")
        return "person/$personId/$encodedName"
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
    var currentStartDest by remember { mutableStateOf(startDestination) }
    // 登录成功后默认进入"我的"页
    var mainInitialTab by remember { mutableIntStateOf(initialTab) }

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
                        val isLoggedIn by authStateHolder.isLoggedIn.collectAsState(initial = false)

                        // 从详情页返回时，若标记已看则触发列表刷新
                        // 使用 backStackEntry.savedStateHandle 而非 navController.currentBackStackEntry
                        // 后者在导航过渡期间可能为 null 或指向错误的 entry
                        val savedState = backStackEntry.savedStateHandle
                        val watchlistChanged by savedState.getStateFlow("watchlist_changed", false).collectAsState()
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
                        val uiState by watchlistViewModel.uiState.collectAsState()
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
                            onMovieClick = { traktId, tmdbId, title, imdbId, traktRating ->
                                navController.navigate(Routes.detailRoute("movie", traktId, tmdbId, title, imdbId, traktRating))
                            },
                            onShowClick = { traktId, tmdbId, title, imdbId, traktRating ->
                                navController.navigate(Routes.detailRoute("show", traktId, tmdbId, title, imdbId, traktRating))
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
                            onLogout = {
                                onLogout()
                                currentStartDest = Routes.LOGIN
                                navController.navigate(Routes.LOGIN) {
                                    popUpTo(0) { inclusive = true }
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
                        navArgument("traktRating") { type = NavType.FloatType; defaultValue = 0.0f }
                    )
                ) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val type = backStackEntry.arguments?.getString("type") ?: "movie"
                        val traktId = backStackEntry.arguments?.getInt("traktId") ?: 0
                        val tmdbId = backStackEntry.arguments?.getInt("tmdbId") ?: 0
                        val title = backStackEntry.arguments?.getString("title") ?: ""
                        val imdbId = backStackEntry.arguments?.getString("imdbId") ?: ""
                        val traktRating = backStackEntry.arguments?.getFloat("traktRating")?.toDouble() ?: 0.0

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
                            onBack = { changed -> goBack(changed) },
                            onPersonClick = { personId, personName ->
                                navController.navigate(Routes.personRoute(personId, personName))
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
                        navArgument("personName") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val personId = backStackEntry.arguments?.getInt("personId") ?: 0
                        val personName = java.net.URLDecoder.decode(
                            backStackEntry.arguments?.getString("personName") ?: "", "UTF-8"
                        )
                        PersonScreen(
                            personId = personId,
                            personName = personName,
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
            }
            } // Box
        }
    }
}
