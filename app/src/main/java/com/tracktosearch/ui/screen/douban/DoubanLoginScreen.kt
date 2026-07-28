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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 豆瓣登录页 ViewModel：
 * - 监听 WebView 抓到的 dbcl2 cookie，解析出 userId
 * - 登录成功后保存凭据（不自动同步，由用户手动选择增量同步）
 * - 检测云端是否有该豆瓣账号的失败数据（用于跨设备查看）
 */
@HiltViewModel
class DoubanLoginViewModel @Inject constructor(
    val doubanAuthStorage: DoubanAuthStorage,
    val doubanSyncManager: DoubanSyncManager,
    val cloudFailureSyncManager: com.tracktosearch.data.repository.CloudFailureSyncManager,
    private val cloudPersonalSyncManager: com.tracktosearch.data.repository.CloudPersonalSyncManager,
    private val doubanRetryManager: com.tracktosearch.data.repository.DoubanRetryManager
) : ViewModel() {

    val progress = doubanSyncManager.progress

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess

    // 云端失败数据检测结果(null=未检测/检测失败,>0=云端有 N 条失败数据)
    private val _cloudFailureCount = MutableStateFlow<Int?>(null)
    val cloudFailureCount: StateFlow<Int?> = _cloudFailureCount

    fun onLoginSuccess(userId: String, cookie: String) {
        doubanAuthStorage.saveCredentials(userId, cookie)
        _loginSuccess.value = true
        // 不自动同步：由用户在设置页或 Watchlist 页手动选择增量同步
        // 增量同步会自动拉取云端进度，接续上次同步，避免全量爬取豆瓣
        // 检测云端是否有该豆瓣账号的失败数据(用于跨设备查看)
        checkCloudFailures()
        // 登录后刷新云端 sync_meta,确保跨设备冷却期(lastFullSyncAt)最新
        viewModelScope.launch { runCatching { cloudPersonalSyncManager.refreshMetaOnly() } }
    }

    /** 检测云端是否有当前豆瓣账号的失败数据 */
    private fun checkCloudFailures() {
        viewModelScope.launch {
            _cloudFailureCount.value = cloudFailureSyncManager.checkCloudFailures()
        }
    }

    /** 用户确认后下载云端失败数据并合并到本地 */
    fun downloadCloudFailures() {
        viewModelScope.launch {
            val result = cloudFailureSyncManager.downloadAndMerge()
            // 云端更新且替换成功 → 刷新失败项统计,让设置页/Watchlist 页显示最新数量
            if (result is com.tracktosearch.data.repository.DownloadResult.Success) {
                doubanRetryManager.refreshRetryState()
            }
            _cloudFailureCount.value = null
        }
    }

    /** 用户忽略云端数据 */
    fun dismissCloudFailures() {
        _cloudFailureCount.value = null
    }
}

/**
 * 豆瓣 WebView 登录页：
 * - 进入时请求通知权限（用于同步转后台时显示进度通知）
 * - 加载 https://accounts.douban.com/passport/login 直接登录页
 * - 显示安心说明（可展开查看数据流向、存储策略等详情）
 * - 监听 WebView 抓取 dbcl2 cookie，登录成功后显示 Snackbar 并自动返回
 * - 不自动同步：用户需手动在设置页或 Watchlist 页选择增量同步
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoubanLoginScreen(
    onBack: () -> Unit,
    viewModel: DoubanLoginViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val cloudDownloadedMsg = stringResource(R.string.cloud_failures_downloaded)
    val isLoggedIn by viewModel.doubanAuthStorage.isLoggedIn.collectAsStateWithLifecycle()
    val loginSuccess by viewModel.loginSuccess.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

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

    // WebView 登录成功 → 显示 Snackbar + 自动返回上一页
    // 不自动同步：用户需手动在设置页或 Watchlist 页选择增量同步
    LaunchedEffect(loginSuccess) {
        if (loginSuccess) {
            snackbarHostState.showSnackbar(
                message = successMessage,
                duration = androidx.compose.material3.SnackbarDuration.Short
            )
            onBack()
        }
    }

    // 云端失败数据检测弹窗:登录后发现云端有同豆瓣账号的失败数据,提示下载查看
    val cloudCount by viewModel.cloudFailureCount.collectAsStateWithLifecycle()
    cloudCount?.let { count ->
        if (count > 0) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissCloudFailures() },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                title = { Text(stringResource(R.string.cloud_failures_detected_title)) },
                text = {
                    Text(stringResource(R.string.cloud_failures_detected_desc, count))
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.downloadCloudFailures()
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = cloudDownloadedMsg,
                                duration = androidx.compose.material3.SnackbarDuration.Short
                            )
                        }
                    }) {
                        Text(stringResource(R.string.cloud_failures_download))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissCloudFailures() }) {
                        Text(stringResource(R.string.cloud_failures_dismiss))
                    }
                }
        )
        }
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
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
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
