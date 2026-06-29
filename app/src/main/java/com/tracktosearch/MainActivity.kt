package com.tracktosearch

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.tracktosearch.ui.util.showToast
import android.widget.Toast
import android.app.AlertDialog
import android.content.DialogInterface
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.tracktosearch.data.local.GuestModeStorage
import com.tracktosearch.data.local.DefaultTabStorage
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.push.JPushHelper
import com.tracktosearch.ui.navigation.Routes
import com.tracktosearch.ui.navigation.AppNavigation
import com.tracktosearch.ui.theme.TraktToSearchTheme
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.ScrollToTopProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.pow
import java.util.Locale
import javax.inject.Inject

// 全局共享的 OAuth 结果，供 MainActivity 传递给 LoginViewModel
object OAuthCallback {
    @Volatile
    var pendingCode: String? = null
    @Volatile
    var authDenied: Boolean = false

    fun clear() {
        pendingCode = null
        authDenied = false
    }
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val MIN_SPLASH_DURATION_MS = 1200L
    }

    @Inject
    lateinit var tokenStorage: TokenStorage

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
    lateinit var tmdbRepository: com.tracktosearch.data.repository.TmdbRepository

    // 全局 scrollToTop 提供者
    private val scrollToTopProvider = ScrollToTopProvider()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashStartTime = System.currentTimeMillis()
        // 安装 SplashScreen，处理系统默认启动页到自定义 splash 的平滑过渡
        val splashScreen = installSplashScreen()
        // 让系统 splash 保持显示直到自定义 splash 渲染完成，避免切换闪烁
        var keepSplashOnScreen = true
        splashScreen.setKeepOnScreenCondition { keepSplashOnScreen }
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

        var isReady by mutableStateOf(false)
        var startDest by mutableStateOf(Routes.LOGIN)
        var initialTab by mutableStateOf(0)

        lifecycleScope.launch {
            val isValid = tokenStorage.isTokenValid()
            val isGuest = guestModeStorage.isGuestMode.first()
            startDest = when {
                isValid -> Routes.MAIN
                isGuest -> Routes.MAIN
                else -> Routes.LOGIN
            }
            // 已登录时读取用户设置的默认启动页，未登录时使用搜索页（0）
            initialTab = if (isValid || isGuest) {
                defaultTabStorage.defaultTab.first()
            } else {
                0
            }

            val language = languageStorage.language.first()
            applyLanguage(language)

            // Splash delay 期间并行预取默认首页数据
            if (isValid) {
                launch { runCatching { traktRepository.getMovieWatchlist(page = 1, limit = 50) } }
            }
            launch { runCatching { tmdbRepository.getPopularMovies() } }
            launch { runCatching { tmdbRepository.getUpcomingMovies() } }

            // 确保自定义 splash 最短显示时长，品牌展示充分
            val elapsed = System.currentTimeMillis() - splashStartTime
            if (elapsed < MIN_SPLASH_DURATION_MS) {
                delay(MIN_SPLASH_DURATION_MS - elapsed)
            }

            isReady = true
            // 自定义 splash 已渲染完成，解除系统 splash 的保持状态
            keepSplashOnScreen = false
        }

        handleIntent(intent)
        checkCrashAndPrompt()

        // 监听状态栏点击，触发 scrollToTop
        setupStatusBarTapListener()

        setContent {
            val themeMode by themeStorage.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            val accentColor by themeStorage.accentColor.collectAsStateWithLifecycle(initialValue = null)
            TraktToSearchTheme(themeMode = themeMode, accentColor = accentColor) {
                CompositionLocalProvider(LocalScrollToTopProvider provides scrollToTopProvider) {
                if (isReady) {
                    var currentDestination by remember { mutableStateOf(startDest) }
                    val authStateHolder = remember {
                        com.tracktosearch.ui.navigation.AuthStateHolder(tokenStorage)
                    }
                    AppNavigation(
                        startDestination = currentDestination,
                        initialTab = initialTab,
                        authStateHolder = authStateHolder,
                        onLoginSuccess = {
                            currentDestination = Routes.MAIN
                        },
                        onLogout = {
                            lifecycleScope.launch {
                                tokenStorage.clearTokens()
                                guestModeStorage.setGuestMode(false)
                                currentDestination = Routes.LOGIN
                            }
                        }
                    )
                } else {
                    // 自定义开屏页 + 优雅渐入动画
                    val iconScale = remember { Animatable(0.8f) }
                    val iconAlpha = remember { Animatable(0f) }
                    val titleAlpha = remember { Animatable(0f) }
                    val titleOffset = remember { Animatable(20f) }
                    val sloganAlpha = remember { Animatable(0f) }
                    val versionAlpha = remember { Animatable(0f) }

                    LaunchedEffect(Unit) {
                        // 图标：0-600ms 缩放+淡入
                        launch {
                            iconAlpha.animateTo(1f, tween(600, easing = Easing { it * it * it }))
                        }
                        launch {
                            iconScale.animateTo(1f, tween(700, easing = Easing { 1f - (1f - it).pow(3) }))
                        }
                        // App 名：300-800ms 淡入+上移
                        launch {
                            delay(300)
                            titleAlpha.animateTo(1f, tween(500))
                        }
                        launch {
                            delay(300)
                            titleOffset.animateTo(0f, tween(500, easing = Easing { 1f - (1f - it).pow(3) }))
                        }
                        // Slogan：500-900ms 淡入
                        launch {
                            delay(500)
                            sloganAlpha.animateTo(1f, tween(400))
                        }
                        // 版本号：700-1100ms 淡入
                        launch {
                            delay(700)
                            versionAlpha.animateTo(1f, tween(400))
                        }
                    }

                    val splashStartColor: Color
                    val splashEndColor: Color
                    if (accentColor != null) {
                        val darkTheme = themeMode == "dark" || (themeMode == "system" && isSystemInDarkTheme())
                        val seed = if (darkTheme) accentColor!!.dark else accentColor!!.light
                        splashStartColor = seed
                        splashEndColor = seed.copy(alpha = 0.7f)
                    } else {
                        splashStartColor = Color(0xFF4B88E6)
                        splashEndColor = Color(0xFF6C63FF)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(splashStartColor, splashEndColor),
                                    start = Offset.Zero,
                                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                                )
                            )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 80.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            val context = LocalContext.current
                            val launcherBitmap = remember {
                                android.graphics.BitmapFactory.decodeResource(
                                    context.resources, R.drawable.ic_search_cloud
                                )?.asImageBitmap()
                            }
                            if (launcherBitmap != null) {
                                Image(
                                    bitmap = launcherBitmap,
                                    contentDescription = "App Icon",
                                    modifier = Modifier
                                        .size(120.dp)
                                        .scale(iconScale.value)
                                        .alpha(iconAlpha.value)
                                        .clip(RoundedCornerShape(24.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "TraktToSearch",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                letterSpacing = 0.5.sp,
                                modifier = Modifier
                                    .alpha(titleAlpha.value)
                                    .offset(y = titleOffset.value.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.splash_slogan),
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.alpha(sloganAlpha.value)
                            )
                        }
                        // 版本号在底部
                        Text(
                            text = "v${getAppVersion(LocalContext.current)}",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp)
                                .alpha(versionAlpha.value)
                        )
                    }
                }
                } // CompositionLocalProvider
            }
        }
    }

    override fun onResume() {
        super.onResume()
        JPushHelper.onResume(this)
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
        intent?.data?.let { uri ->
            if (uri.scheme == "tracktosearch" && uri.host == "oauth") {
                val error = uri.getQueryParameter("error")
                if (!error.isNullOrEmpty()) {
                    OAuthCallback.authDenied = true
                } else {
                    val code = uri.getQueryParameter("code")
                    if (!code.isNullOrEmpty()) {
                        OAuthCallback.pendingCode = code
                    }
                }
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
        // ComponentActivity 不会自动处理 AppCompatDelegate 的 locale 变更，
        // 需手动更新 Configuration 以使 Compose 读取到正确的 locale
        if (locales.isEmpty) {
            // 跟随系统：清除自定义 locale
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

    private fun checkCrashAndPrompt() {
        val crashCount = CrashHandler.getAndResetCrashCount(this)
        if (crashCount >= 2) {
            val logs = CrashHandler.getCrashLogs(this)
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

    private fun getAppVersion(context: android.content.Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }
}
