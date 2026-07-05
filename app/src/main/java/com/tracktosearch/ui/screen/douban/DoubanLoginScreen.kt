package com.tracktosearch.ui.screen.douban

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.repository.DoubanSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * 豆瓣登录页 ViewModel：
 * - 监听 WebView 抓到的 dbcl2 cookie，解析出 userId
 * - 登录成功后保存凭据并自动触发一次同步
 * - 暴露同步进度，供 UI 显示 DoubanSyncDialog
 */
@HiltViewModel
class DoubanLoginViewModel @Inject constructor(
    val doubanAuthStorage: DoubanAuthStorage,
    val doubanSyncManager: DoubanSyncManager
) : ViewModel() {

    val progress = doubanSyncManager.progress

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess

    fun onLoginSuccess(userId: String, cookie: String) {
        doubanAuthStorage.saveCredentials(userId, cookie)
        _loginSuccess.value = true
        // 首次登录自动触发同步（Application scope，不依赖 ViewModel 生命周期）
        doubanSyncManager.startSync()
    }

    /**
     * 已登录豆瓣时直接触发同步（从设置页「重新导入」场景）。
     * 默认 forceOverwrite=false：跳过已同步条目，支持断点续传。
     * @param forceOverwrite true=强制重新同步所有条目
     */
    fun triggerSync(forceOverwrite: Boolean = false) {
        doubanSyncManager.startSync(forceOverwrite = forceOverwrite)
    }

    /**
     * Cookie 过期后重置：清除旧凭证 + 重置同步进度，让 UI 回到 WebView 登录页。
     */
    fun resetForRelogin() {
        doubanAuthStorage.clearCredentials()
        _loginSuccess.value = false
        doubanSyncManager.resetProgress()
    }
}

/**
 * 豆瓣 WebView 登录页：
 * - 进入时请求通知权限（用于同步转后台时显示进度通知）
 * - 加载 https://accounts.douban.com/passport/login 直接登录页
 * - 显示安心说明（可展开查看数据流向、存储策略等详情）
 * - 监听 WebView 抓取 dbcl2 cookie，登录成功后显示 Snackbar 并自动触发同步
 * - 同步进行中/完成时显示 DoubanSyncDialog
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoubanLoginScreen(
    onBack: () -> Unit,
    viewModel: DoubanLoginViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isLoggedIn by viewModel.doubanAuthStorage.isLoggedIn.collectAsStateWithLifecycle()
    val loginSuccess by viewModel.loginSuccess.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    // 标记本次会话是否已触发同步（区分「本次触发」与「上次同步遗留的 isComplete」）
    var syncTriggered by remember { mutableStateOf(false) }

    // Snackbar 状态：登录成功时显示提示
    val snackbarHostState = remember { SnackbarHostState() }
    val successMessage = stringResource(R.string.douban_login_success_snackbar)

    // 通知权限请求 launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> /* 无论授权与否都不阻塞，用户可选不授权（只是没通知栏进度） */ }

    // 进入页面时请求通知权限（Android 13+）
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // 已登录豆瓣 → 自动触发同步（仅一次）
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn && !syncTriggered && !progress.isRunning) {
            syncTriggered = true
            viewModel.triggerSync()
        }
    }

    // WebView 登录成功 → 标记同步已触发 + 显示 Snackbar
    LaunchedEffect(loginSuccess) {
        if (loginSuccess) {
            syncTriggered = true
            snackbarHostState.showSnackbar(
                message = successMessage,
                duration = androidx.compose.material3.SnackbarDuration.Short
            )
        }
    }

    // 同步进行中或已完成（且本次会话触发）→ 显示同步对话框
    if (syncTriggered && (progress.isRunning || progress.isComplete)) {
        DoubanSyncDialog(
            onDismiss = {
                // Cookie 过期：重置到登录页（不退出）；正常完成：退出
                if (progress.cookieExpired) {
                    viewModel.resetForRelogin()
                    syncTriggered = false
                } else if (!progress.isRunning) {
                    onBack()
                }
            },
            onBackground = {
                // 「转后台」:仅隐藏弹窗,同步在 Application scope 继续运行,留在当前页让用户继续操作
                syncTriggered = false
            },
            onRelogin = {
                viewModel.resetForRelogin()
                syncTriggered = false
            }
        )
        return
    }

    // WebView 加载状态：null=空闲，"loading"=加载中，其他字符串=错误信息
    var loadState by remember { mutableStateOf<String?>(null) }
    // 安心说明展开状态
    var privacyExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.douban_login_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 安心说明卡片
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.douban_login_privacy_short),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { privacyExpanded = !privacyExpanded },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Icon(
                        if (privacyExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(
                        text = stringResource(
                            if (privacyExpanded) R.string.douban_login_privacy_collapse
                            else R.string.douban_login_privacy_expand
                        ),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                AnimatedVisibility(visible = privacyExpanded) {
                    Column {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = stringResource(R.string.douban_login_privacy_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // 显式设置 LayoutParams，避免默认 wrap_content 导致高度为 0 白屏
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                // 模拟常规 Chrome 移动端 UA，避免被豆瓣识别为爬虫返回验证页
                                userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                loadWithOverviewMode = true
                                useWideViewPort = true
                            }
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            // WebChromeClient 必须设置，否则部分 JS 交互、加载进度回调不工作
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    // 豆瓣登录跳转 accounts.douban.com 时会用 http:// 明文，
                                    // App 禁用 cleartext 流量会报 ERR_CLEARTEXT_NOT_PERMITTED，
                                    // 这里强制升级为 https://
                                    if (url.startsWith("http://")) {
                                        view?.loadUrl(url.replaceFirst("http://", "https://"))
                                        return true
                                    }
                                    return false
                                }
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    loadState = "loading"
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    loadState = null
                                    val cookie = CookieManager.getInstance().getCookie("https://movie.douban.com")
                                    if (cookie != null) {
                                        // 从 dbcl2 cookie 解析 userId
                                        val dbcl2 = cookie.split(";")
                                            .map { it.trim() }
                                            .firstOrNull { it.startsWith("dbcl2=") }
                                        if (dbcl2 != null) {
                                            val value = dbcl2.removePrefix("dbcl2=")
                                            // dbcl2="userid:xxxxx" 格式，取冒号前
                                            val userId = value.trim('"').split(":").firstOrNull()
                                            if (!userId.isNullOrEmpty()) {
                                                viewModel.onLoginSuccess(userId, cookie)
                                            }
                                        }
                                    }
                                }
                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    // 主框架加载失败时显示错误信息
                                    if (request?.isForMainFrame == true) {
                                        loadState = error?.description?.toString() ?: "加载失败"
                                    }
                                }
                            }
                            // 直接加载豆瓣登录页，省去用户在首页找登录入口
                            loadUrl("https://accounts.douban.com/passport/login")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // 加载中/错误提示覆盖层
                val state = loadState
                when (state) {
                    "loading" -> androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 32.dp)
                            .align(Alignment.TopCenter)
                    )
                    null -> {}
                    else -> Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.TopCenter)
                    ) {
                        Text(
                            text = state,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
