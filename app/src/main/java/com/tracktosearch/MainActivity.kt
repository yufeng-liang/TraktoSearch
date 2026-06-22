package com.tracktosearch

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.tracktosearch.ui.util.showToast
import android.widget.Toast
import android.app.AlertDialog
import android.content.DialogInterface
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.tracktosearch.data.local.GuestModeStorage
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.ui.navigation.Routes
import com.tracktosearch.ui.navigation.AppNavigation
import com.tracktosearch.ui.theme.TraktToSearchTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

// 全局共享的 OAuth 结果，供 MainActivity 传递给 LoginViewModel
object OAuthCallback {
    @Volatile
    var pendingCode: String? = null
    @Volatile
    var authDenied: Boolean = false
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var tokenStorage: TokenStorage

    @Inject
    lateinit var guestModeStorage: GuestModeStorage

    @Inject
    lateinit var themeStorage: ThemeStorage

    @Inject
    lateinit var languageStorage: LanguageStorage

    override fun onCreate(savedInstanceState: Bundle?) {
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
            initialTab = if (isValid) 2 else 0

            val language = languageStorage.language.first()
            applyLanguage(language)

            isReady = true
        }

        handleIntent(intent)
        checkCrashAndPrompt()

        setContent {
            val themeMode by themeStorage.themeMode.collectAsState(initial = "system")
            TraktToSearchTheme(themeMode = themeMode) {
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
                    // 自定义开屏页
                    val context = LocalContext.current
                    val launcherBitmap = remember {
                        android.graphics.BitmapFactory.decodeResource(
                            context.resources, R.mipmap.ic_launcher
                        )?.asImageBitmap()
                    }
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            if (launcherBitmap != null) {
                                Image(
                                    bitmap = launcherBitmap,
                                    contentDescription = "App Icon",
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "v${getAppVersion(context)}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(80.dp))
                        }
                    }
                }
            }
        }
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
                .setTitle("应用异常提醒")
                .setMessage("检测到应用近期发生了 $crashCount 次异常退出，是否将错误日志发送给开发者以帮助修复问题？")
                .setPositiveButton("发送日志") { _: DialogInterface, _: Int ->
                    sendCrashEmail(logs)
                    CrashHandler.clearCrashLogs(this)
                }
                .setNegativeButton("暂不发送") { dialog: DialogInterface, _: Int ->
                    dialog.dismiss()
                    CrashHandler.clearCrashLogs(this)
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun sendCrashEmail(logs: String) {
        val subject = "TrackToSearch 错误日志"
        val body = if (logs.isNotEmpty()) {
            "以下是应用崩溃的错误日志：\n\n$logs"
        } else {
            "应用发生了异常退出，但未找到详细的错误日志。"
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
            showToast("日志已复制到剪贴板，请手动发送至 1577865546@qq.com", Toast.LENGTH_LONG)
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
