package com.tracktosearch

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
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

/**
 * 开屏日签开关在系统 splash 里的那份快照。
 *
 * 系统 splash 的图标在窗口创建那一刻就定了，比任何 DataStore 都早，所以这一档必须在
 * Application 里（Hilt 依赖可用、且早于 Activity）读出来存下，Activity 再来取。
 * 默认「开」——它是产品默认值，读盘失败时不该改变观感。
 */
object SplashStartup {
    @Volatile
    var quoteEnabled: Boolean = true
}

/** 负责在 MainActivity 与 AppNavigation 之间传递深链路 */
object DeepLinkNavigator {    data class NavigateToDetail(
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

        /**
         * 主界面导航树推迟组合的量，单位毫秒。
         *
         * 两帧出头：一帧给系统 splash 的 pre-draw 条件放行（它在 App 首帧画完那一刻散场），
         * 一帧给 Compose 提交新的组合。与场记板动画那个 720 无关，这里只是让日签先占住画面。
         */
        private const val NAV_DEFER_MS = 32L

        /**
         * 系统 splash 最多被按在屏幕上多久，单位毫秒。
         *
         * 只在启动链挂死这种极端情况下生效：正常路径日签或主界面几百毫秒内就绪，
         * 这个上限永远不会到。它保证的是「任何情况下都能到界面」。
         */
        private const val SPLASH_MAX_HOLD_MS = 10_000L
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
    /** 启动网络就绪门槛（完整初始化/校验，含网络请求），见 onCreate 第二段 B。 */
    private var authInitializationJob: Job? = null
    /** 启动本地就绪门槛（只读本地会话与缓存，无网络请求），组合门只等它。 */
    private var authLocalJob: Job? = null
    /**
     * 主界面导航树摆好了没有——布局完成，被放行的这一帧画出来就是主界面。
     *
     * 系统 splash 的第二道放行条件（见 onCreate 里的 setKeepOnScreenCondition）。这里要的是
     * 「布局完成」，不是「开始组合」：keep 条件在 pre-draw 判定，而 pre-draw 在同一帧的
     * measure/layout 之后，所以布局完成的这一刻放行，这一帧的内容就是主界面本身；改成在组合
     * 开始时放行，那一帧只有窗口底色——关掉日签的场合就是「合板动画 → 暖纸空窗 → 主界面」。
     *
     * 同样也不能「等它真的画出来」：keep 条件为真时 core-splashscreen 的 pre-draw 返回 false，
     * 整帧绘制被取消（ViewRootImpl 连带跳过 OnDrawListener 派发），画这个动作压根不会发生，
     * 于是那个信号永远等不到。标记点用 Modifier.onPlaced：它由 MeasureAndLayoutDelegate 在
     * 布局末尾派发，持屏期间照跑，位置正好落在放行判定之前。
     */
    private var navPlaced = false

    /** 主界面导航树的组合门：日签先上屏、导航后一帧跟上，见 onCreate 第四段。 */
    private var navComposed by mutableStateOf(false)

    private var initialResumeHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTrace.mark("activity.onCreate.enter")
        // splash 图标两档：开屏日签开着时用静态版（数据一就绪就散场，合板动画演不完，
        // 那半截动画只会像卡住），关着时保留合板动画（那种情况下系统 splash 要停到 App 就绪，
        // 动画正好把那段时间用掉）。主题必须在 super.onCreate 之前换掉，见 chooseSplashTheme。
        setTheme(chooseSplashTheme())
        var isReady by mutableStateOf(false)
        // 日签层的门：当天那条台词（连同它的海报）到手了才开。
        // 它要的就这么多：台词与海报一到手，这一页就是完整的，主界面在它背后继续加载。
        // 拿不到就是整层不上屏——开关关着、海报没就绪都算，所以这个标志同时决定系统 splash
        // 要不要等主界面（见下面 setKeepOnScreenCondition），不能只表示「这一段跑完了」。
        var stampReady by mutableStateOf(false)
        // 通过 Compose 状态控制 Splash 的结束条件
        val splashScreen = installSplashScreen()
        StartupTrace.mark("activity.splash_installed")
        // 退场也自己收。平台的默认退场会把图标淡出，那两百多毫秒里屏幕上只剩暖纸底和品牌条
        // ——一扇空窗，用户报的正是这个。这里直接撤窗：放行条件保证这一刻内容早就画好了
        // （有日签等日签数据，没日签等主界面布局完成），所以撤窗露出来的就是内容本身，
        // 图标到内容是一次干净的切换，中间不留任何一帧只有底色的画面。
        // 代价是合板动画不再有退场那一拍（平台只在默认退场里播它，而默认退场就是上面那个
        // 淡出）：两档现在都停在图标本身，要动画就得连着淡出一起要，二选一。
        splashScreen.setOnExitAnimationListener { provider ->
            StartupTrace.mark("startup.splash_exit")
            provider.remove()
        }
        // 系统 splash 让位的条件，三种时刻：
        //   日签这一页有内容可上屏（stampReady）→ 让位给日签，App 其余工作继续在它背后跑；
        //   主界面真的摆好了（navPlaced）→ 没有日签接场的场合（开关关着、海报没就绪）必须等到
        //     这一刻，且只等到这一刻：不能写成 isReady，也不能写成「导航开始组合」或
        //     「主题铺好了」，那些时刻主界面都还没布局完，放行只会在屏幕上留一段暖纸空窗；
        //   最短在场时长（MIN_SYSTEM_SPLASH_DURATION_MS）→ 热启动时主界面几百毫秒就摆好了，
        //     没有这一条场记板会一闪而过。
        // 最后那 10 秒是防呆：启动链任何一环挂死也不该把人永久困在场记板上。
        // 最短时长必须写在这里而不是去 delay 就绪的那条链（踩过）：那样会把主界面的组合
        // 一起推后，白白多等一个最短时长的整段。
        val splashHeldFrom = System.currentTimeMillis()
        splashScreen.setKeepOnScreenCondition {
            val heldMs = System.currentTimeMillis() - splashHeldFrom
            when {
                heldMs >= SPLASH_MAX_HOLD_MS -> false
                stampReady -> false
                heldMs < MIN_SYSTEM_SPLASH_DURATION_MS -> true
                else -> !navPlaced
            }
        }
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

        var startDest by mutableStateOf(Routes.LOGIN)
        var initialTab by mutableStateOf(0)
        // 开屏台词。它在「日签数据就绪」之前准备好，日签上屏与系统 splash 散场都等这个状态。
        var splashQuote by mutableStateOf<com.tracktosearch.ui.screen.splash.SplashQuoteUi?>(null)
        var splashQuoteDone by mutableStateOf(false)
        val opensSearchFromWidget = SearchNavigator.isOpenSearchIntent(intent)
        val notificationOpensWatchlist = intent?.getStringExtra("navigate_to") in setOf(
            "douban_sync",
            "consistency_check"
        )

        // 第一段：日签自己的数据。这一段是「日签上屏」的关键路径，只做四件事——
        // 定语言、读开关、选当天那条台词、解海报。选中台词还要看海报在不在，
        // 所以语言必须排在选台词之前：海报按语言分文件，语言没定就选会拿到另一档的图。
        // 任何一步不成立就拿不到 quote，日签整层跳过，绝不会显示到一半或占位。
        //
        // 这一段的耗时完全在本地磁盘上（两个 DataStore + 海报解码），
        // 与网络无关；auth 检查和会话态都不在里面，它们挪去了第二段。
        val stampJob = lifecycleScope.launch {
            StartupTrace.mark("startup.stamp.enter")
            val language = StartupTrace.measure("local.language") {
                languageStorage.language.first()
            }
            applyLanguage(language)
            val splashQuoteEnabled = StartupTrace.measure("local.splash_quote") {
                splashQuoteStorage.preloadAndGetValue()
            }
            if (splashQuoteEnabled) {
                splashQuote = StartupTrace.measure("splash_quote.load") {
                    splashQuoteLoader.load(language)
                }
            }
            // 只有真的拿到台词（连同海报）才算日签能上屏。开关关着、或者海报没就绪，
            // 这一层整层跳过，这面旗子就一直是 false——系统 splash 于是改成等主界面摆好
            // （见 setKeepOnScreenCondition），那段等待才不会落到一块暖纸空窗上。
            stampReady = splashQuote != null
            StartupTrace.mark("startup.stamp_ready", "quote=${splashQuote != null}")
        }

        // 第二段：App 的就绪门槛。日签已经压在最上面，这里做的都是它背后的事。
        // 拆成两段：A 段只读本地会话与本地缓存定起点路由/默认标签页（无网络请求），
        // 组合门只等它，首屏不再被最长 2s 的网关校验拖住；B 段在日签背后完成完整
        // 初始化（token 轮换/网关校验），真实连接态随后由网络校验收敛进 SessionModeManager。
        // A 段跑完置 isReady，日签的跳过提示与退场都以它为条件：用户按下跳过时下面必须是能用的界面。
        authLocalJob = lifecycleScope.launch {
            StartupTrace.mark("startup.enter")
            var isAuthorized = false
            var cachedTraktProfile: com.tracktosearch.data.remote.trakt.dto.TraktUserProfileResponse? = null
            try {
                StartupTrace.measure("auth.local") {
                    isAuthorized = authManager.initializeLocalOnly()
                }
                // 已授权后检查 Trakt 本地资料缓存（纯磁盘读取）
                cachedTraktProfile = if (isAuthorized) {
                    StartupTrace.measure("trakt.profile.cache") {
                        traktRepository.getCachedUserProfile()
                    }
                } else {
                    StartupTrace.mark("trakt.profile.cache.skipped", "reason=not_authorized")
                    null
                }
                // 同步初始 Trakt 连接态到 SessionModeManager（AppNavigation 据此派生 isLoggedIn/isDoubanMode）；
                // 真实连接态由 B 段完成后的网络校验收敛
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
            } catch (e: Exception) {
                // 起点路由的兜底是 isReady 的 finally：哪怕上面整段挂掉，也要放行到界面，
                // 不能让用户永远停在日签上。起点保持初值（登录页），用户可以重新走一遍激活。
                StartupTrace.mark("startup.local_gate.failed", "err=${e.javaClass.simpleName}")
            }
            // 默认标签页：进主页第一帧就要用，赶不上就会先落在「想看」再跳走
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
            isReady = true
            StartupTrace.mark("startup.ready")
        }

        // 第二段 B：网络就绪门槛。完整初始化（token 轮换/网关校验）在日签背后继续跑，
        // 结果由 AuthManager 状态收敛驱动 UI；已激活设备在状态收敛到撤销/过期前仍留在主界面，
        // AppNavigation 的 authState 守卫负责把撤销/过期用户兜底回登录页。
        authInitializationJob = lifecycleScope.launch {
            authLocalJob?.join()
            try {
                StartupTrace.measure("auth.network") {
                    authManager.initializeForStartup()
                }
            } catch (e: Exception) {
                StartupTrace.mark("startup.network_gate.failed", "err=${e.javaClass.simpleName}")
            }
        }

        // 第三段：日签背后继续跑的启动工作。它们都不参与首屏——想看列表由 WatchlistScreen
        // 进入后自行加载，海报预取与连接校验都属于「以后」的事，不该和日签抢主线程与带宽。
        // 必须在 isReady 之前就位的只有语言与默认标签页两件，见上面两段。
        lifecycleScope.launch {
            authInitializationJob?.join()
            val authState = authManager.authState.value
            val isAuthorized = authState.hasGatewayAccess()
            // 触感档位：一次 DataStore 读，必须落在用户能点之前 ——
            // 之后任何一次点击都可能发触感，而 modeState 的初值是「跟随系统」，
            // 预加载完成前用户选的「关闭」还没生效。
            // 这里只读档位，不解析 AppHaptics：那条链要探设备能力（IPC + 厂商反射），
            // 按 HapticModule 的规矩得由第一个真正用它的调用方在后台线程预热。
            try {
                StartupTrace.measure("local.haptic_mode") {
                    hapticStorage.preloadAndGetValue()
                }
            } catch (e: Exception) {
                StartupTrace.mark("local.haptic_mode.failed", "err=${e.javaClass.simpleName}")
            }
            if (isAuthorized) {
                // 想看列表由 WatchlistScreen 进入后自行加载，避免网络请求阻塞 Splash。
                StartupTrace.mark("trakt.watchlist.startup_skipped", "reason=load_on_page")
            } else {
                StartupTrace.mark("trakt.watchlist.startup_skipped", "reason=not_authorized")
            }
            this@MainActivity.lifecycleScope.launch(Dispatchers.IO) {
                if (authState == AuthState.AUTHORIZED) {
                    authCheckScheduler.schedulePreflight(authManager.getNextCheckAt())
                } else {
                    authCheckScheduler.cancelPreflight()
                }
            }
            // 海报预取：这活儿是为了「以后每天都有画面」，不该和启动阶段的首屏请求抢带宽。
            // 未来 7 天先备齐，整池由 SplashPosterWorker 在不计费网络下补完。
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
            // 拿到台词的那条路不在这里写：台词层真的演过之后由 onSplashQuoteShown 写。
            // checkIn 认首写，这里抢先写下去，之后台词层再补写同一天会被忽略，
            // 日历里就留下一张用户没读过的卡片。
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
                            if (traktRepository.getCachedUserProfile() != null) {
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
                            TraktConnectionCheckResult.UNKNOWN -> "unknown_preserved_cache=${
                                traktRepository.getCachedUserProfile() != null
                            }"
                        }
                    )
                }
            } else {
                StartupTrace.mark("trakt.profile.skipped", "reason=not_authorized")
            }
        }

        // 第四段：主界面导航树的组合门。
        //
        // 实测（小米 14 Pro，debug 包）首次组合把整棵导航树建出来的代价约 700-800ms：
        // 日签数据 750ms 就绪，首帧却要等到 1.5s 之后，中间这段全花在「还没人看得见的主界面」上。
        // 这一层本来就要在日签背后继续加载，所以把它的组合推迟到日签首帧之后：
        // 系统 splash 一散场屏幕上就已经是完整的一页日签，而不是一个白屏接着主界面。
        //
        // 关掉开屏日签时这一层就是「第一屏」本身，系统的 keep 条件于是在等它摆好（见上面
        // setKeepOnScreenCondition + navPlaced）：合板动画散场那一帧，主界面已经布局完成。
        //
        // 推迟的量是两帧。这里必须用时间而不是 withFrameNanos：lifecycleScope 的上下文里
        // 没有 Compose 的 MonotonicFrameClock，直接调会抛 IllegalStateException 把 App 打死
        // （踩过一次）。16ms 一帧的余量给系统 splash 的 pre-draw 放行加一次组合提交。
        // 用户碰不到这段空窗：跳过提示要等 isReady，而 isReady 之后才开始这段推迟。
        lifecycleScope.launch {
            stampJob.join()
            authLocalJob?.join()
            delay(NAV_DEFER_MS)
            navComposed = true
            StartupTrace.mark("startup.nav_visible")
        }

        handleIntent(intent)

        // 配置状态栏点击滚动到顶部
        setupStatusBarTapListener()

        StartupTrace.mark("activity.set_content.begin")
        setContent {
            val themeMode by themeStorage.themeMode.collectAsStateWithLifecycle()
            val accentColor by themeStorage.accentColor.collectAsStateWithLifecycle()
            val selectedCustomAccentArgb by themeStorage.selectedCustomAccentArgb.collectAsStateWithLifecycle()
            val visualEffectMode by themeStorage.visualEffectMode.collectAsStateWithLifecycle()
            val glassVariant by themeStorage.glassVariant.collectAsStateWithLifecycle()
            TraktoSearchTheme(
                themeMode = themeMode,
                accentColor = accentColor,
                customAccentArgb = selectedCustomAccentArgb,
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
                // Splash 完成后展示主导航（navComposed 推迟两帧，见上面第四段的说明）
                if (navComposed) {
                    var currentDestination by remember { mutableStateOf(startDest) }
                    val authStateHolder = remember {
                        com.tracktosearch.ui.navigation.AuthStateHolder(
                            authManager,
                            traktAuthManager,
                            traktRepository
                        )
                    }
                    // navPlaced 是系统 splash 的第二道放行条件：布局走完才算「主界面在了」。
                    // onPlaced 在布局阶段末尾回调（见 navPlaced 的说明），而放行判定在随后
                    // 同一帧的 pre-draw——所以被放行的那一帧画出来就已经是主界面。
                    Box(modifier = Modifier.onPlaced {
                        if (!navPlaced) {
                            navPlaced = true
                            StartupTrace.mark("startup.nav_placed")
                        }
                    }) {
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
                }

                // 崩溃上报对话框（授权/上传中/失败重试，状态驱动）
                CrashReportDialogHost(
                    crashLogStorage = crashLogStorage,
                    crashLogUploader = crashLogUploader,
                )

                // 开屏台词层：压在最上面，等它自己散场或被点掉。
                // 门是「日签有内容可上屏」（stampReady）而不是 isReady——台词与海报一备齐就压上来，
                // 主界面在它背后继续加载，用户等 App 启动的时间因此花在有内容的一页纸上。
                // 系统 splash 也是等它（见上面 setKeepOnScreenCondition）：日签一亮，场记板就散场，
                // 两者接的是同一块暖纸色。
                //
                // contentReady 是「App 已就绪」：跳过提示浮出、整层收点击、以及肯不肯退场都看它。
                // 就绪之前轻触不响应，退出后必须马上有一个能用的界面接住。
                val quote = splashQuote
                if (stampReady && quote != null && !splashQuoteDone) {
                    com.tracktosearch.ui.screen.splash.SplashQuoteOverlay(
                        quote = quote,
                        contentReady = isReady,
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

    /**
     * 系统 splash 用哪一档图标，只看开屏日签开不开。
     *
     * 开着：用静态版（[R.style.Theme_App_Starting_SplashQuote]，图标不带动画）。
     * 日签数据一就绪系统 splash 就散场，而合板动画那时候通常还没演完——
     * 半截动画比一张静帧更像卡住，而这段等待本来就该交给日签。
     *
     * 关着：留原来的合板动画（[R.style.Theme_App_Starting]）。这种场合系统 splash 要一直停到
     * App 就绪（没有任何日签在底下接住它），720ms 的动画正好把那段时间用掉。
     *
     * 必须在 super.onCreate 之前调用：主题定了窗口图标，晚于窗口创建就没有这一档了。
     */
    private fun chooseSplashTheme(): Int = if (SplashStartup.quoteEnabled) {
        R.style.Theme_App_Starting_SplashQuote
    } else {
        R.style.Theme_App_Starting
    }

    private fun applyLanguage(language: String) {        val locales = when (language) {
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
