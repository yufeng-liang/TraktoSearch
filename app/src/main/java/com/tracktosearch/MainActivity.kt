package com.tracktosearch

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import com.tracktosearch.data.remote.douban.DoubanHotApiService
import com.tracktosearch.data.remote.douban.dto.DoubanHotData
import com.tracktosearch.data.remote.douban.dto.DoubanHotItem
import com.tracktosearch.data.remote.trakt.TraktAuthManager
import com.tracktosearch.data.util.CrashLogUploader
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.push.JPushHelper
import com.tracktosearch.ui.navigation.AppNavigation
import com.tracktosearch.ui.navigation.Routes
import com.tracktosearch.ui.theme.TraktToSearchTheme
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.ScrollToTopProvider
import com.tracktosearch.ui.util.showToast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        private const val MIN_SYSTEM_SPLASH_DURATION_MS = 1350L
        // 系统 Splash 的最短展示时间
        // 已登录且激活时，Splash 会等待首页预取完成
    }

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var traktAuthManager: TraktAuthManager

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
    lateinit var doubanHotApi: DoubanHotApiService

    @Inject
    lateinit var sharedDoubanHotCache: PersistentTtlCache<DoubanHotData>

    @Inject
    lateinit var sharedTransitionStorage: com.tracktosearch.data.local.SharedTransitionStorage

    // 提供滚动到顶部能力
    private val scrollToTopProvider = ScrollToTopProvider()
    private var authInitializationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        var isReady by mutableStateOf(false)
        // 通过 Compose 状态控制 Splash 的结束条件
        val splashScreen = installSplashScreen()
        // 使用 mutableStateOf 让 Compose 感知 Splash 加载状态
        splashScreen.setKeepOnScreenCondition { !isReady }
        super.onCreate(savedInstanceState)
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
        var isTraktConnected by mutableStateOf(false)

        authInitializationJob = lifecycleScope.launch {
            val splashStartTime = System.currentTimeMillis()
            authManager.initialize()
            val authState = authManager.authState.value
            val isAuthorized = authState == AuthState.AUTHORIZED || authState == AuthState.OFFLINE
            // 判断 Trakt 授权状态
            // 已授权后检查 Trakt 连接状态
            isTraktConnected = isAuthorized && traktRepository.checkTraktConnection()
            startDest = when {
                !isAuthorized -> Routes.LOGIN
                isTraktConnected -> Routes.MAIN
                else -> Routes.LOGIN
            }
            // 根据 Trakt 连接状态选择默认标签页
            initialTab = if (isTraktConnected) {
                defaultTabStorage.defaultTab.first()
            } else {
                0
            }

            val language = languageStorage.language.first()
            applyLanguage(language)

            // 预加载首页所需数据
            sharedTransitionStorage.preloadAndGetValue()

            // 已登录且激活时，在后台启动发现页新片榜和口碑榜预取；它们不阻塞 Splash 退出。
            if (isTraktConnected) {
                this@MainActivity.lifecycleScope.launch {
                    runCatching { prefetchDoubanHotCategory("douban-movie") }
                }
                this@MainActivity.lifecycleScope.launch {
                    runCatching { prefetchDoubanHotCategory("douban-weekly") }
                }
            }

            if (isTraktConnected) {
                // Trakt 想看列表是进入主界面的必要数据，只有它完成后才结束 Splash。
                runCatching { traktRepository.getMovieWatchlist(page = 1, limit = 200) }
            } else {
                // 未登录或未激活时，保证 Splash 至少展示指定时长
                val elapsed = System.currentTimeMillis() - splashStartTime
                val remaining = MIN_SYSTEM_SPLASH_DURATION_MS - elapsed
                if (remaining > 0) delay(remaining)
            }

            isReady = true
        }

        handleIntent(intent)
        // 在后台线程检查崩溃日志并上传
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            checkCrashAndPrompt()
        }

        // 配置状态栏点击滚动到顶部
        setupStatusBarTapListener()

        setContent {
            val themeMode by themeStorage.themeMode.collectAsStateWithLifecycle()
            val accentColor by themeStorage.accentColor.collectAsStateWithLifecycle()
            TraktToSearchTheme(themeMode = themeMode, accentColor = accentColor) {
                CompositionLocalProvider(LocalScrollToTopProvider provides scrollToTopProvider) {
                // Splash 完成后展示主导航
                if (isReady) {
                    var currentDestination by remember { mutableStateOf(startDest) }
                    val authStateHolder = remember {
                        com.tracktosearch.ui.navigation.AuthStateHolder(authManager, traktAuthManager)
                    }
                    AppNavigation(
                        startDestination = currentDestination,
                        initialTab = initialTab,
                        initialTraktLoggedIn = isTraktConnected,
                        authStateHolder = authStateHolder,
                        onLoginSuccess = {
                            currentDestination = Routes.MAIN
                        }
                    )
                }

                } // CompositionLocalProvider
            }
        }
    }

    /** 预热发现页新片榜/口碑榜的共享缓存，进入页面后由 DiscoverViewModel 直接复用。 */
    private suspend fun prefetchDoubanHotCategory(categoryId: String) {
        val cacheKey = "${categoryId}_1_10_v2"
        sharedDoubanHotCache.awaitLoaded()
        sharedDoubanHotCache.getOrAwait(cacheKey) {
            val response = when (categoryId) {
                "douban-movie" -> doubanHotApi.getChart()
                "douban-weekly" -> doubanHotApi.getWeekly()
                else -> error("Unsupported Douban hot category: $categoryId")
            }
            DoubanHotData(
                items = response.data.map { item ->
                    val ratingText = if (item.rating.isNotBlank() && item.rating != "暂无评分") {
                        "【${item.rating}】"
                    } else {
                        ""
                    }
                    DoubanHotItem(
                        id = item.id.hashCode(),
                        title = "$ratingText${item.title}",
                        cover = item.poster,
                        desc = item.ratingCount,
                        rating = item.rating,
                        url = item.url,
                        tmdbId = item.tmdbId
                    )
                },
                total = response.total
            )
        }
    }

    override fun onResume() {
        super.onResume()
        JPushHelper.onResume(this)
        lifecycleScope.launch {
            authInitializationJob?.join()
            if (authManager.authState.value != AuthState.UNAUTHORIZED) {
                authManager.check()
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

    override fun onPause() {
        super.onPause()
        JPushHelper.onPause(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
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

    private suspend fun checkCrashAndPrompt() {
        val crashCount = CrashHandler.getAndResetCrashCount(this)
        if (crashCount < 1) return

        // 等待崩溃日志上传结果，最多 8 秒
        val uploadOk = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            CrashLogUploader.uploadResult.await()
        } ?: false

        if (uploadOk) {
            CrashHandler.clearCrashLogs(this)
            return
        }

        val logs = CrashHandler.getCrashLogs(this)
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.crash_dialog_title))
                .setMessage(getString(R.string.crash_dialog_message, crashCount))
                .setPositiveButton(getString(R.string.crash_dialog_send)) { _: DialogInterface, _: Int ->
                    sendCrashEmail(logs)
                    CrashHandler.clearCrashLogs(this)
                }
                .setNegativeButton(getString(R.string.crash_dialog_cancel)) { dialog: DialogInterface, _: Int ->
                    dialog.dismiss()
                    CrashHandler.clearCrashLogs(this)
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun sendCrashEmail(logs: String) {
        val subject = getString(R.string.crash_email_subject)
        val body = if (logs.isNotEmpty()) {
            getString(R.string.crash_email_body) + logs
        } else {
            getString(R.string.crash_email_no_log)
        }

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:1577865546@qq.com")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("Crash Log", body)
            )
            showToast(getString(R.string.crash_toast_copied), Toast.LENGTH_LONG)
        }
    }


}
