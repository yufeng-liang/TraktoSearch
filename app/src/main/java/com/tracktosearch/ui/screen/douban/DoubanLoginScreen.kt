package com.tracktosearch.ui.screen.douban

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.ui.haptic.rememberAppHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "DoubanLogin"

/** 主框架加载失败时归类为"网络不可用"的 WebView 错误码，其余错误统一显示通用加载失败文案 */
private val NETWORK_ERROR_CODES = setOf(
    WebViewClient.ERROR_HOST_LOOKUP,      // DNS 解析失败（如 net::ERR_NAME_NOT_RESOLVED）
    WebViewClient.ERROR_CONNECT,          // 连接建立失败
    WebViewClient.ERROR_IO,               // 网络 IO 错误
    WebViewClient.ERROR_TIMEOUT,          // 连接超时
    WebViewClient.ERROR_TOO_MANY_REQUESTS // 请求过于频繁
)

/**
 * 豆瓣登录页 ViewModel：
 * - 监听 WebView 抓到的 dbcl2 cookie，解析出 userId
 * - 登录成功后保存凭据（不自动同步，由用户手动选择增量同步）
 */
@HiltViewModel
class DoubanLoginViewModel @Inject constructor(
    val doubanAuthStorage: DoubanAuthStorage,
    val doubanSyncManager: DoubanSyncManager,
    private val cloudPersonalSyncManager: com.tracktosearch.data.repository.CloudPersonalSyncManager
) : ViewModel() {

    val progress = doubanSyncManager.progress

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess

    fun onLoginSuccess(userId: String, cookie: String) {
        // EncryptedSharedPreferences 的 Tink 加密在 put 时同步执行（含 Keystore 密钥访问），
        // 而 WebView 回调在主线程，移到 IO 协程避免阻塞写盘；
        // 保存完成后再置登录态并刷新云端 meta，保证导航进入主页时凭据已就绪
        // （refreshMetaOnly 依赖凭据计算 userHash，sessionMode 依赖 isLoggedIn）
        viewModelScope.launch(Dispatchers.IO) {
            doubanAuthStorage.saveCredentials(userId, cookie)
            _loginSuccess.value = true
            // 不自动同步：由用户在设置页或 Watchlist 页手动选择增量同步
            // 增量同步会自动拉取云端进度，接续上次同步，避免全量爬取豆瓣
            // 登录后刷新云端 sync_meta,确保跨设备冷却期(lastFullSyncAt)最新
            runCatching { cloudPersonalSyncManager.refreshMetaOnly() }
        }
    }

}

/**
 * 豆瓣 WebView 登录页：
 * - 进入时请求通知权限（用于同步转后台时显示进度通知），不阻塞导航
 * - 加载 https://accounts.douban.com/passport/login 直接登录页
 * - 显示安心说明（可展开查看数据流向、存储策略等详情）
 * - 监听 WebView 抓取 dbcl2 cookie，登录成功后立即自动返回（不等待提示）
 * - 主框架加载失败时显示错误卡片（按错误类型映射文案）+ 重新加载按钮
 * - 不自动同步：用户需手动在设置页或 Watchlist 页选择增量同步
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoubanLoginScreen(
    onBack: () -> Unit,
    /**
     * 登录成功后的导航回调。若提供，则用此回调替代默认的 onBack。
     * 用途：从 ActivationLoginScreen 进入时，登录成功需直接进入 MainScreen
     * （豆瓣独立模式），而非返回激活登录页。
     */
    onLoginSuccess: (() -> Unit)? = null,
    viewModel: DoubanLoginViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val loginSuccess by viewModel.loginSuccess.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()

    // WebView 主框架加载失败时的兜底文案(预解析,避免在 WebViewClient 回调内硬编码中文)
    val loadFailedText = stringResource(R.string.douban_login_load_failed)
    // 网络类错误的文案(预解析,避免在 WebViewClient 回调内取资源)
    val networkErrorText = stringResource(R.string.error_network_unavailable)

    // 通知权限请求 launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> /* 无论授权与否都不阻塞，用户可选不授权（只是没通知栏进度） */ }

    // 登录成功后再请求通知权限（Android 13+），避免未登录就打扰用户
    // 弹权限与导航不互相阻塞：仅 loginSuccess 首次置真时触发一次，重复进入不重复弹
    LaunchedEffect(loginSuccess) {
        if (loginSuccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // WebView 登录成功 → 立即导航（不再等待 Snackbar 挂起约 4 秒，避免用户干等；
    // 且等待期间手动返回会造成二次导航）
    // 提供专用 onLoginSuccess 时调用之（如从激活登录页进入后直达主页），
    // 否则沿用 onBack 返回上一页
    // 不自动同步：用户需手动在设置页或 Watchlist 页选择增量同步
    LaunchedEffect(loginSuccess) {
        if (loginSuccess) {
            if (onLoginSuccess != null) onLoginSuccess() else onBack()
        }
    }

    // WebView 引用：供重新加载按钮调用 reload()，并配合 onRelease 销毁
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // WebView 加载状态：null=空闲，"loading"=加载中，其他字符串=已映射的错误文案（非原始 description）
    var loadState by remember { mutableStateOf<String?>(null) }
    // 安心说明展开状态
    var privacyExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.douban_login_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                    }
                }
            )
        },
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
                    onClick = {
                        haptics.toggle(!privacyExpanded)
                        privacyExpanded = !privacyExpanded
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Icon(
                        if (privacyExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
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
                                    // 主框架加载失败时显示错误卡片；原始 description（如
                                    // net::ERR_NAME_NOT_RESOLVED）只记日志不上 UI，按错误类型映射文案
                                    if (request?.isForMainFrame == true) {
                                        val code = error?.errorCode ?: WebViewClient.ERROR_UNKNOWN
                                        Log.w(TAG, "WebView 主框架加载失败: code=$code desc=${error?.description}")
                                        // 只在主框架上发：子资源失败（图片、埋点）不影响用户能不能登录。
                                        // cookie 解析那一路刻意不发 —— onPageFinished 每次翻页都会跑，
                                        // 「还没有 dbcl2」是登录前的常态而不是失败，挂在那里会一路乱震
                                        haptics.reject()
                                        loadState = if (code in NETWORK_ERROR_CODES) {
                                            networkErrorText
                                        } else {
                                            loadFailedText
                                        }
                                    }
                                }
                            }
                            // 直接加载豆瓣登录页，省去用户在首页找登录入口
                            loadUrl("https://accounts.douban.com/passport/login")
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    // 不传 onReset：视图不可复用，离开组合即触发 onRelease（官方语义，onRelease 在 UI 线程回调）
                    onRelease = { webView ->
                        // 离开页面必须 destroy，否则 WebView 泄漏；
                        // 销毁前置空 webViewClient，防止销毁过程中残留回调触发 onPageFinished
                        // 误判"登录成功"（把旧 Cookie 当新登录存回）
                        webView.webViewClient = WebViewClient()
                        webView.destroy()
                    },
                    // 持有 WebView 引用，供重新加载按钮调用 reload()
                    update = { webViewRef = it }
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
                            .align(Alignment.Center)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                haptics.tap()
                                // 重新加载当前页；onPageStarted 会把状态切回 loading
                                webViewRef?.reload()
                            }
                        ) {
                            Text(stringResource(R.string.error_retry))
                        }
                    }
                }
            }
        }
    }
}
