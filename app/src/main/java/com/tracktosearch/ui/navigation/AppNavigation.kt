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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tracktosearch.data.local.TokenStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.screen.detail.DetailScreen
import com.tracktosearch.ui.screen.login.LoginScreen
import com.tracktosearch.ui.screen.main.MainScreen
import com.tracktosearch.ui.screen.search.SearchScreen
import com.tracktosearch.ui.screen.webview.WebViewScreen
import javax.inject.Inject

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val DETAIL = "detail/{type}/{traktId}/{tmdbId}/{title}/{imdbId}/{traktRating}"
    const val SEARCH = "search/{keyword}"
    const val WEBVIEW = "webview/{url}/{title}"

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

                composable(Routes.MAIN) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val isLoggedIn by authStateHolder.isLoggedIn.collectAsState(initial = false)
                        MainScreen(
                            initialTab = initialTab,
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
                            onNavigateToLogin = {
                                navController.navigate(Routes.LOGIN) {
                                    popUpTo(Routes.MAIN) { inclusive = false }
                                }
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

                        DetailScreen(
                            traktId = traktId,
                            tmdbId = tmdbId,
                            title = title,
                            mediaType = if (type == "show") MediaType.SHOW else MediaType.MOVIE,
                            imdbId = imdbId,
                            traktRating = traktRating,
                            onBack = { navController.popBackStack() },
                            onPersonClick = { personId, personName ->
                                navController.navigate(Routes.webViewRoute(
                                    "https://www.themoviedb.org/person/$personId",
                                    personName
                                ))
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
                            onBack = { navController.popBackStack() }
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
                    WebViewScreen(
                        url = url,
                        title = title,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            } // Box
        }
    }
}
