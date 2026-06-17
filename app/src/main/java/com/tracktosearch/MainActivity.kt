package com.tracktosearch

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.app.AlertDialog
import android.content.DialogInterface
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.tracktosearch.data.local.GuestModeStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.ui.navigation.Routes
import com.tracktosearch.ui.navigation.AppNavigation
import com.tracktosearch.ui.theme.TraktToSearchTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

// 全局共享的 OAuth 结果，供 MainActivity 传递给 LoginViewModel
object OAuthCallback {
    @Volatile
    var pendingCode: String? = null
    @Volatile
    var authDenied: Boolean = false
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var tokenStorage: TokenStorage

    @Inject
    lateinit var guestModeStorage: GuestModeStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 小米/ HyperOS 完全沉浸式适配（参考 dev.mi.com 全面屏手势提示线适配说明）
        // 顺序很重要：先设置布局标志，再设置颜色，最后关闭对比度增强
        val decorView = window.decorView
        // 使用旧 API 确保兼容小米 HyperOS（参考小米文档方式2）
        @Suppress("DEPRECATION")
        decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        // 状态栏和导航栏背景完全透明
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // 隐藏导航栏分隔线
        @Suppress("DEPRECATION")
        window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
        // 关闭系统自动加的导航栏对比度遮罩（这是小米设备上小白条不透明的主因）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        var isReady by mutableStateOf(false)
        var startDest by mutableStateOf(Routes.LOGIN)
        var initialTab by mutableStateOf(0) // 0=搜索, 1=我的

        splashScreen.setKeepOnScreenCondition { !isReady }

        lifecycleScope.launch {
            val isValid = tokenStorage.isTokenValid()
            val isGuest = guestModeStorage.isGuestMode.first()
            startDest = when {
                isValid -> Routes.MAIN
                isGuest -> Routes.MAIN
                else -> Routes.LOGIN
            }
            initialTab = if (isValid) 1 else 0
            isReady = true
        }

        // 处理 OAuth 回调
        handleIntent(intent)

        // 检测崩溃次数，>=2 次时提醒用户分享日志
        checkCrashAndPrompt()

        setContent {
            TraktToSearchTheme {
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
                    Log.d("MainActivity", "OAuth denied: $error")
                    OAuthCallback.authDenied = true
                } else {
                    val code = uri.getQueryParameter("code")
                    if (!code.isNullOrEmpty()) {
                        Log.d("MainActivity", "Received OAuth callback with code")
                        OAuthCallback.pendingCode = code
                    }
                }
            }
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
            // 没有邮件客户端，复制到剪贴板
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("Crash Log", body)
            )
            Toast.makeText(this, "日志已复制到剪贴板，请手动发送至 1577865546@qq.com", Toast.LENGTH_LONG).show()
        }
    }
}
