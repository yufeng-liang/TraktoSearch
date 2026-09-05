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
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.auth.AuthCheckScheduler
import com.tracktosearch.data.auth.hasGatewayAccess
import com.tracktosearch.data.remote.trakt.TraktAuthManager
import com.tracktosearch.data.remote.trakt.TraktConnectionCheckResult
import com.tracktosearch.data.remote.trakt.TraktConnectionState
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.data.util.StartupTrace
import com.tracktosearch.ui.component.CrashReportDialogHost
import com.tracktosearch.ui.haptic.AppHaptics
import com.tracktosearch.ui.haptic.LocalAppHaptics
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
import javax.inject.Provider

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
    lateinit var themeStorage: ThemeStorage

    @Inject
    lateinit var languageStorage: LanguageStorage

    @Inject
    lateinit var defaultTabStorage: DefaultTabStorage

    @Inject
    lateinit var traktRepository: com.tracktosearch.data.repository.TraktRepository

    @Inject
    lateinit var sharedTransitionStorage: com.tracktosearch.data.local.SharedTransitionStorage

    // 触感三档：必须在任何界面能发出触感之前读到磁盘首值，否则用户选的「关闭」在启动那段窗口不生效
    @Inject
    lateinit var hapticStorage: com.tracktosearch.data.local.HapticStorage

    @Inject
    lateinit var splashQuoteStorage: com.tracktosearch.data.local.SplashQuoteStorage

    @Inject
    lateinit var splashQuoteLoader: com.tracktosearch.ui.screen.splash.SplashQuoteLoader

    @Inject
    lateinit var splashQuoteRepository: com.tracktosearch.data.repository.SplashQuoteRepository

    @Inject
    lateinit var dailyStampRepository: com.tracktosearch.data.repository.DailyStampRepository

    @Inject
    lateinit var crashLogUploader: com.tracktosearch.data.util.CrashLogUploader
    @Inject
    lateinit var crashLogStorage: com.tracktosearch.data.local.CrashLogStorage

    // 统一会话模式管理器：trakt 连接态/豆瓣登录态合一，
    // AppNavigation 通过其 StateFlow 派生 isLoggedIn / isDoubanMode 等 UI 状态
    @Inject
    lateinit var sessionModeManager: SessionModeManager

    // 触感引擎的惰性入口，往组合树里 provide 用（见 setContent 里的 LocalAppHaptics）。
    // 必须是 Provider：直接注入 AppHaptics 会把整套能力探测同步跑在 Activity 的注入点上，
    // 那正是主线程。真正的 get() 由 TraktSearchApp 的启动预热在后台线程先做掉。
    @Inject
    lateinit var appHapticsProvider: Provider<AppHaptics>

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
        // 开屏台词：必须在 isReady 置位之前准备完毕，否则系统场记板已经散场、
        // 台词层才刚开始加载，中间会露出一帧主界面。
        var splashQuote by mutableStateOf<com.tracktosearch.ui.screen.splash.SplashQuoteUi?>(null)
        var splashQuoteDone by mutableStateOf(false)
        // 台词层的门：只有导航真的落到主页才放它盖上来。登录页和引导页同样是 Splash 之后的第一屏，
        // 台词压在上面等于把用户按在一个动不了的登录页上等四秒。
        // 冷启动就落主页时下面会先把门开着，不等 AppNavigation 的 onEnterMain 回调——
        // 那条回调要晚一帧到，中间那一帧会从散场的场记板底下露出主界面。
        var atMainDestination by mutableStateOf(false)
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
            val isAuthorized = authState.hasGatewayAccess()
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
            // 访客也是“已激活网关、未登录 Trakt/豆瓣”的会话模式，不能绕过激活态单独进入主页。
            // 平台登录状态由 SessionModeManager 派生；这里只负责守住 App 激活边界。
            startDest = if (isAuthorized) Routes.MAIN else Routes.LOGIN
            // 起点就是主页时门一开始就开着，台词层与系统场记板严丝合缝地接上
            atMainDestination = startDest == Routes.MAIN
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

            // 触感档位：一次 DataStore 读，必须落在 isReady 之前 ——
            // 之后任何一次点击都可能发触感，而 modeState 的初值是「跟随系统」，
            // 预加载完成前用户选的「关闭」还没生效。
            // 这里只读档位，不解析 AppHaptics：那条链要探设备能力（IPC + 厂商反射），
            // 按 HapticModule 的规矩得由第一个真正用它的调用方在后台线程预热。
            StartupTrace.measure("local.haptic_mode") {
                hapticStorage.preloadAndGetValue()
            }

            // 开屏台词：读开关 + 选当天那条 + 解海报，全部在后台线程做完才放行 Splash。
            // 台词库和海报都在本地，正常只花几十毫秒；任何一步不成立就拿不到 quote，
            // 台词层整层跳过，绝不会显示到一半或占位。
            val splashQuoteEnabled = StartupTrace.measure("local.splash_quote") {
                splashQuoteStorage.preloadAndGetValue()
            }
            if (splashQuoteEnabled) {
                splashQuote = StartupTrace.measure("splash_quote.load") {
                    splashQuoteLoader.load(language)
                }
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
            // 海报预取放在 Splash 之后：这活儿是为了「以后每天都有画面」，
            // 不该和启动阶段的首屏请求抢带宽。未来 7 天先备齐，整池由
            // SplashPosterWorker 在不计费网络下补完。
            this@MainActivity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    splashQuoteRepository.prefetchUpcoming()
                } catch (e: Exception) {
                    // 预取失败无副作用：下次启动或 Worker 会再补
                }
            }
            // 日签：这里只负责「台词层压根不会出现」那种情况的兜底——开关关着、或者海报没就绪
            // 整层跳过。传 null 让仓库按日期回算，那天照样算来过，日历上不该空一格。
            //
            // 拿到台词的那条路不在这里写：台词层现在可能延后到进主页之后才出现，用户完全可能
            // 停在登录页就退出，一眼没看见那句话。checkIn 认首写，这里抢先写下去，之后台词层
            // 真的演过再补写同一天会被忽略，日历里就留下一张用户没读过的卡片。改成由覆盖层的
            // onSplashQuoteShown 触发，见 setContent 里的调用点。
            if (splashQuote == null) {
                this@MainActivity.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        dailyStampRepository.checkIn(null)
                    } catch (e: Exception) {
                        // 签到失败不影响任何已有功能：下次启动或跨天回到前台会再写
                    }
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
            val customAccentArgb by themeStorage.customAccentArgb.collectAsStateWithLifecycle()
            val visualEffectMode by themeStorage.visualEffectMode.collectAsStateWithLifecycle()
            val glassVariant by themeStorage.glassVariant.collectAsStateWithLifecycle()
            TraktoSearchTheme(
                themeMode = themeMode,
                accentColor = accentColor,
                customAccentArgb = customAccentArgb,
                visualEffectMode = visualEffectMode,
                glassVariant = glassVariant
            ) {
                CompositionLocalProvider(
                    LocalScrollToTopProvider provides scrollToTopProvider,
                    // 递 Provider 而不是 AppHaptics 实例：解析它是阻塞的，交给
                    // TraktSearchApp 的启动预热在后台线程做掉。位置必须在这里而不是
                    // AppNavigation 内部 —— CrashReportDialogHost 与 SplashQuoteOverlay
                    // 是 AppNavigation 的兄弟节点，也要能读到。
                    LocalAppHaptics provides appHapticsProvider,
                ) {
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
                        },
                        onEnterMain = { atMainDestination = true }
                    )
                }

                // 崩溃上报对话框（授权/上传中/失败重试，状态驱动）
                CrashReportDialogHost(
                    crashLogStorage = crashLogStorage,
                    crashLogUploader = crashLogUploader,
                )

                // 开屏台词层：压在最上面，等它自己散场或被点掉。
                // 只有 isReady 之后才组合——早一帧组合，动画就会在系统场记板背后白跑。
                // 再加一道 atMainDestination：登录页/引导页也可能是场记板之后的第一屏，
                // 台词不该盖在上面，得等导航真的落到主页。
                val quote = splashQuote
                if (isReady && quote != null && !splashQuoteDone && atMainDestination) {
                    com.tracktosearch.ui.screen.splash.SplashQuoteOverlay(
                        quote = quote,
                        // 冷启动直落主页时这一层是接着场记板往下演，同一块画面不该有淡入；
                        // 先过登录页的那条路上它是后盖到已经画好的主界面上，必须淡进来。
                        continuesSystemSplash = startDest == Routes.MAIN,
                        // 台词真的开演了才落「已展示」与当天日签，写的都是屏幕上这一条。
                        // 约定只回调一次；两处存储本身也按首次写入幂等，重复调用无副作用。
                        onSplashQuoteShown = {
                            this@MainActivity.lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    splashQuoteRepository.markShown(quote.quoteId)
                                } catch (e: Exception) {
                                    // 展示标记失败时下次启动再展示一次，比提前吞掉首次体验更安全
                                }
                                try {
                                    dailyStampRepository.checkIn(quote.quoteId)
                                } catch (e: Exception) {
                                    // 签到失败不影响已有功能：下次启动或跨天回到前台会再写
                                }
                            }
                        },
                        // 散场这一刻才是取色最准的时机：动画演完了，CPU 空出来了，而
                        // 用户走到日签卡片或详情页至少还要几次点击。prefetchUpcoming 里
                        // 那次是按「台词层最长时长」估的兜底，台词层现在可能延后到进主页
                        // 之后才出现，估的那个点会落在动画中间。主色已缓存时这一次只是
                        // 一次查询，两条路重复调没有代价。
                        onFinished = {
                            splashQuoteDone = true
                            this@MainActivity.lifecycleScope.launch(Dispatchers.IO) {
                                splashQuoteRepository.warmPosterColor(quote.quoteId)
                            }
                        }
                    )
                }

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
            // 日签的第二个入口：App 挂在后台过了一夜，第二天点回来不会走冷启动，
            // 只靠 onCreate 那次签到的话这一天就漏了。checkIn 自己会先查当天有没有，
            // 每次回到前台多一次主键查询，代价可以忽略。
            if (!isInitialResume) {
                withContext(Dispatchers.IO) {
                    try {
                        dailyStampRepository.checkIn()
                    } catch (e: Exception) {
                        // 签到失败不影响任何已有功能：下次回到前台会再写
                    }
                }
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
