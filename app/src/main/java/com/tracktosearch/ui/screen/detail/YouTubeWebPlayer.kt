package com.tracktosearch.ui.screen.detail

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/** 官方 iframe 的最小宿主；桥接只上报播放状态，不暴露导航、文件或账户能力。 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class YouTubeWebPlayer(
    context: Context,
    private val onReady: () -> Unit,
    private val onError: (Int) -> Unit,
    onEnterFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onExitFullscreen: () -> Unit
) : WebView(context) {
    private var released = false
    private var foreground = false

    init {
        setBackgroundColor(Color.BLACK)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 只有用户点开播放弹层才创建 WebView；是否实际开播仍由前台生命周期把关。
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !YouTubeEmbed.allowsNavigation(request.url.toString(), request.isForMainFrame)

            private fun isPlayerDocument(request: WebResourceRequest): Boolean =
                request.isForMainFrame || (
                    request.url.path?.startsWith("/embed/") == true &&
                        YouTubeEmbed.allowsNavigation(request.url.toString(), false)
                    )

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!released && isPlayerDocument(request)) onError(-1)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (!released && isPlayerDocument(request)) onError(-1)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!released) onError(5)
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (released) callback.onCustomViewHidden() else onEnterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                if (!released) onExitFullscreen()
            }
        }
        // 桥接方法名与构造参数同名会导致推断递归（onReady()/onError() 指向自己），
        // 先各取一份局部引用，语义也不再含糊。
        val readyCallback = onReady
        val errorCallback = onError
        addJavascriptInterface(object {
            @JavascriptInterface
            fun onReady() {
                dispatch {
                    readyCallback()
                    if (foreground) evaluateJavascript("if(window.player){player.playVideo();}", null)
                }
            }

            @JavascriptInterface
            fun onError(code: Int) {
                dispatch { errorCallback(code) }
            }

            @JavascriptInterface
            fun onStateChange(state: Int) {
                dispatch {
                    keepScreenOn = state == 1 && foreground
                    // 页面刚就绪或延迟回调到达时，后台状态同样不允许出声。
                    if (state == 1 && !foreground) pausePlayback()
                }
            }
        }, YouTubeEmbed.BRIDGE_NAME)
    }

    fun loadVideo(videoKey: String) {
        val language = context.resources.configuration.locales[0].toLanguageTag()
        loadDataWithBaseURL(
            YouTubeEmbed.baseUrl(context.packageName),
            YouTubeEmbed.html(videoKey, context.packageName, language),
            "text/html", "UTF-8", null
        )
    }

    fun setInForeground(value: Boolean) {
        if (released) return
        foreground = value
        if (value) {
            // 回到前台不擅自恢复播放，由用户决定是否继续。
            onResume()
        } else {
            pausePlayback()
            onPause()
        }
    }

    private fun pausePlayback() {
        keepScreenOn = false
        evaluateJavascript("if(window.player && player.pauseVideo){player.pauseVideo();}", null)
    }

    private fun dispatch(action: () -> Unit) {
        // JavascriptInterface 在 WebView 的桥接线程调用，只在存活的主线程宿主中改 Compose 状态。
        post { if (!released) action() }
    }

    fun releasePlayer() {
        if (released) return
        setInForeground(false)
        released = true
        stopLoading()
        removeJavascriptInterface(YouTubeEmbed.BRIDGE_NAME)
        webChromeClient = null
        webViewClient = WebViewClient()
        loadUrl("about:blank")
        removeAllViews()
        destroy()
    }
}
