package com.tracktosearch

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.tracktosearch.data.local.DefaultTabStorage
import com.tracktosearch.data.local.GuestModeStorage
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.auth.AuthCheckScheduler
import com.tracktosearch.data.remote.trakt.TraktAuthManager
import com.tracktosearch.data.remote.trakt.TraktConnectionCheckResult
import com.tracktosearch.data.remote.trakt.TraktConnectionState
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.data.util.StartupTrace
import com.tracktosearch.ui.component.CrashReportDialogHost
import com.tracktosearch.ui.navigation.AppNavigation
import com.tracktosearch.ui.navigation.NotificationNavigator
import com.tracktosearch.ui.navigation.NotificationTarget
import com.tracktosearch.ui.navigation.Routes
import com.tracktosearch.ui.navigation.SearchNavigator
import com.tracktosearch.ui.theme.TraktoSearchTheme
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.ScrollToTopProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

// OAuth 回调结果在 MainActivity 与 LoginViewModel 之间共享
// 使用 StateFlow 替代轮询，避免 LoginScreen 每 300ms 检查
object OAuthCallback {
    private val _pendingCodeFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val pendingCodeFlow: kotlinx.coroutines.flow.StateFlow<String?> = _pendingCodeFlow

    private val _authDeniedFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val authDeniedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _authDeniedFlow

    // 暴露当前待处理的 OAuth code
    val pendingCode: String? get() = _pendingCodeFlow.value
    val authDenied: Boolean get() = _authDeniedFlow.value

    fun setPendingCode(code: String?) { _pendingCodeFlow.value = code }
    fun setAuthDenied(denied: Boolean) { _authDeniedFlow.value = denied }

    fun clear() {
        _pendingCodeFlow.value = null
        _authDeniedFlow.value = false
    }
}

/** 负责在 MainActivity 与 AppNavigation 之间传递深链路 */
object DeepLinkNavigator {
    data class NavigateToDetail(
        val type: String,
        val traktId: Int,
        val tmdbId: Int,
        val title: String
    )

    private val _pendingNavigation = kotlinx.coroutines.flow.MutableStateFlow<NavigateToDetail?>(null)
    val pendingNavigation: kotlinx.coroutines.flow.StateFlow<NavigateToDetail?> = _pendingNavigation

    fun navigateToDetail(type: String, traktId: Int, tmdbId: Int, title: String) {
        _pendingNavigation.value = NavigateToDetail(type, traktId, tmdbId, title)
    }

    fun consume() { _pendingNavigation.value = null }
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val MIN_SYSTEM_SPLASH_DURATION_MS = 720L
        // 与系统场记板关闭动画保持一致，避免无业务原因延长 Splash。
    }

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var traktAuthManager: TraktAuthManager

    @Inject
    lateinit var authCheckScheduler: AuthCheckScheduler

    @Inject
    lateinit var guestModeStorage: GuestModeStorage

    @Inject
    lateinit var themeStorage: ThemeStorage

    @Inject
    lateinit var languageStorage: LanguageStorage

    @Inject
    lateinit var defaultTabStorage: DefaultTabStorage

    @Inject
    lateinit var traktRepository: com.tracktosearch.data.repository.TraktRepository

    @Inject
    lateinit var sharedTransitionStorage: com.tracktosearch.data.local.SharedTransitionStorage

    @Inject
    lateinit var crashLogUploader: com.tracktosearch.data.util.CrashLogUploader
    @Inject
    lateinit var crashLogStorage: com.tracktosearch.data.local.CrashLogStorage

    // 统一会话模式管理器：trakt 连接态/豆瓣登录态合一，
    // AppNavigation 通过其 StateFlow 派生 isLoggedIn / isDoubanMode 等 UI 状态
    @Inject
    lateinit var sessionModeManager: SessionModeManager

    // 提供滚动到顶部能力
    private val scrollToTopProvider = ScrollToTopProvider()
    private var authInitializationJob: Job? = null
    @Volatile
    private var startupContentReadyForDraw = false
    private var startupFirstContentDrawLogged = false
    private var initialResumeHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTrace.mark("activity.onCreate.enter")
        var isReady by mutableStateOf(false)
        // 通过 Compose 状态控制 Splash 的结束条件
        val splashScreen = installSplashScreen()
        StartupTrace.mark("activity.splash_installed")
        // 使用 mutableStateOf 让 Compose 感知 Splash 加载状态
        splashScreen.setKeepOnScreenCondition { !isReady }
        super.onCreate(savedInstanceState)
        StartupTrace.mark("activity.super_onCreate.complete")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        installStartupFirstDrawTrace()

        var startDest by mutableStateOf(Routes.LOGIN)
        var initialTab by mutableStateOf(0)
        val opensSearchFromWidget = SearchNavigator.isOpenSearchIntent(intent)
        val notificationOpensWatchlist = intent?.getStringExtra("navigate_to") in setOf(
            "douban_sync",
            "consistency_check"
        )
        authInitializationJob = lifecycleScope.launch {
            StartupTrace.mark("startup.enter")
            val splashStartTime = System.currentTimeMillis()
            StartupTrace.measure("auth.initialize") {
                authManager.initializeForStartup()
            }
            val authState = authManager.authState.value
            val isAuthorized = authState == AuthState.AUTHORIZED || authState == AuthState.OFFLINE
            // 读取访客模式持久化状态：访客模式用户重启 App 后应直接进主页，不被送回登录页
            val isGuestMode = StartupTrace.measure("local.guest_mode") {
                guestModeStorage.isGuestMode.first()
            }
            // 判断 Trakt 授权状态
            // 已授权后检查 Trakt 连接状态
            val cachedTraktProfile = if (isAuthorized) {
                StartupTrace.measure("trakt.profile.cache") {
                    traktRepository.getCachedUserProfile()
                }
            } else {
                StartupTrace.mark("trakt.profile.cache.skipped", "reason=not_authorized")
                null
            }
            // 同步初始 Trakt 连接态到 SessionModeManager（AppNavigation 据此派生 isLoggedIn/isDoubanMode）
            sessionModeManager.setTraktConnectionState(
                when {
                    !isAuthorized -> TraktConnectionState.DISCONNECTED
                    cachedTraktProfile != null -> TraktConnectionState.CONNECTED
                    else -> TraktConnectionState.CHECKING
                }
            )
            startDest = when {
                isAuthorized -> Routes.MAIN
                isGuestMode -> Routes.MAIN  // 访客模式：直接进主页，跨重启保留
                else -> Routes.LOGIN
            }
            // 根据 Trakt 连接状态选择默认标签页
            initialTab = if (opensSearchFromWidget) {
                0
            } else if (notificationOpensWatchlist) {
                2
            } else if (isAuthorized) {
                StartupTrace.measure("local.default_tab") {
                    defaultTabStorage.defaultTab.first()
                }
            } else {
                0
            }

            val language = StartupTrace.measure("local.language") {
                languageStorage.language.first()
            }
            applyLanguage(language)

            // 只读取首页启动所需的本地设置；影视/榜单缓存由当前页面首次使用时按需加载。
            StartupTrace.measure("local.shared_transition") {
                sharedTransitionStorage.preloadAndGetValue()
            }

            if (isAuthorized) {
                // 想看列表由 WatchlistScreen 进入后自行加载，避免网络请求阻塞 Splash。
                StartupTrace.mark("trakt.watchlist.startup_skipped", "reason=load_on_page")
            } else {
                StartupTrace.mark("trakt.watchlist.startup_skipped", "reason=not_authorized")
                // 未登录或未激活时，保证 Splash 至少展示指定时长
                val elapsed = System.currentTimeMillis() - splashStartTime
                val remaining = MIN_SYSTEM_SPLASH_DURATION_MS - elapsed
                if (remaining > 0) delay(remaining)
            }

            isReady = true
            startupContentReadyForDraw = true
            StartupTrace.mark("startup.ready")
            this@MainActivity.lifecycleScope.launch(Dispatchers.IO) {
                if (authState == AuthState.AUTHORIZED) {
                    authCheckScheduler.schedulePreflight(authManager.getNextCheckAt())
                } else {
                    authCheckScheduler.cancelPreflight()
                }
            }
            if (isAuthorized) {
                this@MainActivity.lifecycleScope.launch {
                    val checkResult = StartupTrace.measure("trakt.profile") {
                        traktRepository.checkTraktConnectionResult()
                    }
                    // 网络校验完成后写入 SessionModeManager，驱动 UI 切换到 TRAKT 模式或留在 GUEST/DOUBAN
                    val nextState = when (checkResult) {
                        TraktConnectionCheckResult.CONNECTED -> TraktConnectionState.CONNECTED
                        TraktConnectionCheckResult.DISCONNECTED -> TraktConnectionState.DISCONNECTED
                        TraktConnectionCheckResult.UNKNOWN -> {
                            if (cachedTraktProfile != null) {
                                TraktConnectionState.CONNECTED
                            } else {
                                TraktConnectionState.DISCONNECTED
                            }
                        }
                    }
                    sessionModeManager.setTraktConnectionState(nextState)
                    StartupTrace.mark(
                        "trakt.connection.state",
                        when (checkResult) {
                            TraktConnectionCheckResult.CONNECTED -> "connected"
                            TraktConnectionCheckResult.DISCONNECTED -> "disconnected"
                            TraktConnectionCheckResult.UNKNOWN -> "unknown_preserved_cache=${cachedTraktProfile != null}"
                        }
                    )
                }
            } else {
                StartupTrace.mark("trakt.profile.skipped", "reason=not_authorized")
            }
        }

        handleIntent(intent)

        // 配置状态栏点击滚动到顶部
        setupStatusBarTapListener()

        StartupTrace.mark("activity.set_content.begin")
        setContent {
            val themeMode by themeStorage.themeMode.collectAsStateWithLifecycle()
            val accentColor by themeStorage.accentColor.collectAsStateWithLifecycle()
            val visualEffectMode by themeStorage.visualEffectMode.collectAsStateWithLifecycle()
            TraktoSearchTheme(
                themeMode = themeMode,
                accentColor = accentColor,
                visualEffectMode = visualEffectMode
            ) {
                CompositionLocalProvider(LocalScrollToTopProvider provides scrollToTopProvider) {
                // Splash 完成后展示主导航
                if (isReady) {
                    var currentDestination by remember { mutableStateOf(startDest) }
                    val authStateHolder = remember {
                        com.tracktosearch.ui.navigation.AuthStateHolder(
                            authManager,
                            traktAuthManager,
                            traktRepository
                        )
                    }
                    AppNavigation(
                        startDestination = currentDestination,
                        initialTab = initialTab,
                        authStateHolder = authStateHolder,
                        sessionModeManager = sessionModeManager,
                        onLoginSuccess = {
                            currentDestination = Routes.MAIN
                        }
                    )
                }

                // 崩溃上报对话框（授权/上传中/失败重试，状态驱动）
                CrashReportDialogHost(
                    crashLogStorage = crashLogStorage,
                    crashLogUploader = crashLogUploader,
                )

                } // CompositionLocalProvider
            }
        }
        StartupTrace.mark("activity.set_content.complete")
    }

    override fun onResume() {
        super.onResume()
        StartupTrace.mark("activity.onResume.enter")
        val isInitialResume = !initialResumeHandled
        initialResumeHandled = true
        lifecycleScope.launch {
            authInitializationJob?.join()
            if (isInitialResume) {
                StartupTrace.mark("auth.resume_check.skipped", "reason=initial_resume")
            } else if (authManager.authState.value != AuthState.UNAUTHORIZED) {
                StartupTrace.measure("auth.resume_check") {
                    authManager.check()
                }
                withContext(Dispatchers.IO) {
                    if (authManager.authState.value == AuthState.AUTHORIZED) {
                        authCheckScheduler.schedulePreflight(authManager.getNextCheckAt())
                    } else if (authManager.authState.value != AuthState.AUTHORIZED) {
                        authCheckScheduler.cancelPreflight()
                    }
                }
            } else {
                StartupTrace.mark("auth.resume_check.skipped", "reason=unauthorized")
            }
        }
    }

    private fun installStartupFirstDrawTrace() {
        val observer = window.decorView.viewTreeObserver
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (!startupContentReadyForDraw || startupFirstContentDrawLogged) return
                startupFirstContentDrawLogged = true
                StartupTrace.mark("activity.first_content_draw")
                if (observer.isAlive) observer.removeOnDrawListener(this)
            }
        }
        observer.addOnDrawListener(listener)
    }

    private var statusBarHeight: Int = 0

    private fun setupStatusBarTapListener() {
        statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android")
            .let { if (it > 0) resources.getDimensionPixelSize(it) else 0 }
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_UP && statusBarHeight > 0) {
            if (event.rawY <= statusBarHeight) {
                scrollToTopProvider.scrollToTop()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (SearchNavigator.isOpenSearchIntent(intent)) {
            SearchNavigator.request()
        }
        // 处理 OAuth 回调
        intent?.data?.let { uri ->
            if (uri.scheme == "tracktosearch" && uri.host == "oauth") {
                val error = uri.getQueryParameter("error")
                if (!error.isNullOrEmpty()) {
                    OAuthCallback.setAuthDenied(true)
                } else {
                    val code = uri.getQueryParameter("code")
                    if (!code.isNullOrEmpty()) {
                        OAuthCallback.setPendingCode(code)
                    }
                }
                return
            }
        }
        // 处理通知深链路
        if (intent?.getStringExtra("navigate_to") == "detail") {
            val type = intent.getStringExtra("type") ?: return
            val traktId = intent.getIntExtra("traktId", 0)
            val tmdbId = intent.getIntExtra("tmdbId", 0)
            val title = intent.getStringExtra("title") ?: ""
            if (traktId > 0) {
                DeepLinkNavigator.navigateToDetail(type, traktId, tmdbId, title)
            }
            return
        }
        when (intent?.getStringExtra("navigate_to")) {
            "douban_sync" -> NotificationNavigator.request(NotificationTarget.DOUBAN_SYNC)
            "consistency_check" -> NotificationNavigator.request(NotificationTarget.CONSISTENCY_CHECK)
        }
    }

    private fun applyLanguage(language: String) {
        val locales = when (language) {
            LanguageStorage.LANGUAGE_CHINESE -> LocaleListCompat.forLanguageTags("zh-CN")
            LanguageStorage.LANGUAGE_ENGLISH -> LocaleListCompat.forLanguageTags("en")
            LanguageStorage.LANGUAGE_JAPANESE -> LocaleListCompat.forLanguageTags("ja")
            LanguageStorage.LANGUAGE_KOREAN -> LocaleListCompat.forLanguageTags("ko")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
        // 应用 AppCompatDelegate 的语言设置
        // 同步更新 Compose 使用的 Configuration locale
        if (locales.isEmpty) {
            // 系统未提供应用语言时，回退到默认 locale
            val config = resources.configuration
            config.setLocale(Locale.getDefault())
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        } else {
            val tag = locales.get(0)?.toLanguageTag() ?: return
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = resources.configuration
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        }
    }

}
