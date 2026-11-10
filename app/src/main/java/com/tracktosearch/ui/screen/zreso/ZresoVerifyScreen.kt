package com.tracktosearch.ui.screen.zreso

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.tracktosearch.R
import com.tracktosearch.data.remote.zreso.ZresoWebViewVerifier
import com.tracktosearch.ui.component.AppAlertDialog
import com.tracktosearch.ui.component.DialogAction
import com.tracktosearch.ui.component.SubPageTopBar
import com.tracktosearch.ui.haptic.rememberAppHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

/** Zreso 手动验证页：用应用内 WebView 完成 Cap 人机验证。 */
@HiltViewModel
class ZresoVerifyViewModel @Inject constructor(
    private val verifier: ZresoWebViewVerifier
) : ViewModel() {
    fun onManualSuccess() = verifier.markManualSuccess()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ZresoVerifyScreen(
    onClose: () -> Unit,
    viewModel: ZresoVerifyViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val haptics = rememberAppHaptics()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loadKey by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    var showCloseConfirm by remember { mutableStateOf(false) }

    fun requestClose() {
        if (finished) {
            onClose()
        } else {
            haptics.tap()
            showCloseConfirm = true
        }
    }

    fun markSuccess() {
        if (finished) return
        finished = true
        haptics.confirm()
        viewModel.onManualSuccess()
        onClose()
    }

    // 120 秒超时：挑战页可能一直停在人机验证，不无限等待
    LaunchedEffect(loadKey, finished) {
        if (!finished) {
            delay(120_000)
            if (!finished) {
                hasError = true
                // 超时后停止加载，避免后台 WebView 继续跑脚本/占内存
                webViewRef?.stopLoading()
            }
        }
    }

    BackHandler { requestClose() }

    Scaffold(
        topBar = {
            SubPageTopBar(
                title = stringResource(R.string.zreso_verify_page_title),
                onBack = { requestClose() }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = ZresoWebViewVerifier.CHROME_USER_AGENT
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                        }
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                if (url != null && ZresoWebViewVerifier.isVerificationSuccessUrl(url)) {
                                    markSuccess()
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (url != null && ZresoWebViewVerifier.isVerificationSuccessUrl(url)) {
                                    markSuccess()
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                // 只处理主框架错误，子资源失败不影响验证页面
                                if (request?.isForMainFrame == true) {
                                    hasError = true
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: android.webkit.WebResourceResponse?
                            ) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                                    hasError = true
                                }
                            }
                        }
                        loadUrl(ZresoWebViewVerifier.CHALLENGE_URL)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { webViewRef = it },
                onRelease = { webView ->
                    webView.webViewClient = WebViewClient()
                    webView.destroy()
                }
            )

            if (hasError) {
                // 全屏不透明遮罩：覆盖 WebView，挡住错误页内容并拦截触摸；
                // 窄屏下按钮改为纵向排列，避免三个 TextButton 横向溢出。
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        // 兜底消费点击，避免触摸穿透到底层 WebView
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.zreso_verify_error),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.zreso_verify_timeout),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        haptics.tap()
                        hasError = false
                        loadKey++
                        webViewRef?.reload()
                    }) { Text(stringResource(R.string.error_retry)) }
                    TextButton(onClick = {
                        haptics.tap()
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(ZresoWebViewVerifier.CHALLENGE_URL))
                        )
                    }) { Text(stringResource(R.string.zreso_verify_open_external)) }
                    TextButton(onClick = { requestClose() }) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            } else if (webViewRef == null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(24.dp)
                )
            }
        }
    }

    if (showCloseConfirm) {
        AppAlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = stringResource(R.string.zreso_verify_close_confirm_title),
            message = stringResource(R.string.zreso_verify_close_confirm_message),
            confirm = DialogAction(
                label = stringResource(R.string.common_close),
                onClick = {
                    showCloseConfirm = false
                    onClose()
                }
            ),
            dismiss = DialogAction(
                label = stringResource(R.string.common_cancel),
                onClick = { showCloseConfirm = false }
            )
        )
    }
}
