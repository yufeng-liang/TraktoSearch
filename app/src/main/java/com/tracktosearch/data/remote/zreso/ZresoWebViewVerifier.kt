package com.tracktosearch.data.remote.zreso

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * zreso 人机验证（Cap v2 hashwx）自动完成器。
 *
 * zreso 的 /api/search 在配额窗口耗尽后会返回 429 TURNSTILE_REQUIRED。该验证的放行状态
 * 绑定在出口 IP 上：只要同一台设备通过一次 Cap 挑战，随后的普通 HTTP 请求就能正常取数。
 *
 * Cap 的 hashwx 协议依赖 WebAssembly，纯 Kotlin 无法在合理成本内复刻，因此这里用不可见的
 * WebView 加载验证页，并注入脚本模拟 pointer/mouse 事件自动点击「确认你是真人」，
 * 挑战通过后把结果返回给调用方，由调用方重试 /api/search。
 */
@Singleton
class ZresoWebViewVerifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val mutex = Mutex()

    /** 最近一次验证成功的时间：短时间内的并发调用直接复用结果，避免重复跑挑战。 */
    @Volatile
    private var lastVerifiedAt = 0L

    /** 自动完成一次 zreso 人机验证；成功返回 true。 */
    suspend fun ensureVerified(): Boolean = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            if (System.currentTimeMillis() - lastVerifiedAt < REUSE_WINDOW_MS) {
                return@withLock true
            }
            val verified = awaitVerification()
            if (verified) lastVerifiedAt = System.currentTimeMillis()
            verified
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun awaitVerification(): Boolean = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        lateinit var webView: WebView

        fun finish(result: Boolean) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            runCatching {
                webView.stopLoading()
                webView.webViewClient = WebViewClient()
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView.destroy()
            }
            if (continuation.isActive) continuation.resume(result)
        }

        val timeoutRunnable = Runnable {
            Log.w(TAG, "zreso 人机验证超时")
            finish(false)
        }

        webView = WebView(context).apply {
            // WebView 不挂到窗口时默认尺寸为 0，Cap 组件的 getBoundingClientRect 会全为 0，
            // 导致合成点击的坐标落在 (0,0)。这里主动 measure/layout 出一个可见视口。
            val metrics = context.resources.displayMetrics
            measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, metrics.widthPixels, metrics.heightPixels)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = CHROME_UA
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    val target = url ?: return
                    // 挑战页的 onSuccess 会 location.replace 到 redirect（/search?q=...）
                    if (target.contains("zreso.cn") && !target.contains("turnstile/challenge")) {
                        finish(true)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val target = url ?: return
                    if (target.contains("turnstile/challenge")) {
                        view?.evaluateJavascript(AUTO_CLICK_JS, null)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        Log.w(TAG, "zreso 验证页加载失败: ${error?.errorCode} ${error?.description}")
                        finish(false)
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    val status = errorResponse?.statusCode ?: 0
                    if (request?.isForMainFrame == true && status >= 400) {
                        Log.w(TAG, "zreso 验证页 HTTP $status")
                        finish(false)
                    }
                }
            }
            loadUrl(CHALLENGE_URL)
        }

        handler.postDelayed(timeoutRunnable, VERIFY_TIMEOUT_MS)
        continuation.invokeOnCancellation {
            handler.post { finish(false) }
        }
    }

    private companion object {
        private const val TAG = "ZresoWebViewVerifier"
        private const val CHALLENGE_URL =
            "https://www.zreso.cn/turnstile/challenge?redirect=%2Fsearch"
        private const val VERIFY_TIMEOUT_MS = 45_000L

        /** 验证成功后 30 秒内的重复请求直接复用验证结果 */
        private const val REUSE_WINDOW_MS = 30_000L
        private const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        /**
         * 在验证页里找到 cap-widget 的 shadow DOM，对「确认你是真人」触发器连续派发
         * pointerdown → mousedown → pointerup → mouseup → click（与真机点击事件序列一致）。
         * Cap 组件对完整 pointer/mouse 序列有响应，单个 click() 不够。
         */
        private val AUTO_CLICK_JS = """
            (function() {
              if (window.__zresoAutoClickStarted) return 'already';
              window.__zresoAutoClickStarted = true;
              var attempts = 0;
              var lastClick = 0;
              function deepQuery(root, selector) {
                try {
                  var found = root.querySelector(selector);
                  if (found) return found;
                  var all = root.querySelectorAll('*');
                  for (var i = 0; i < all.length; i++) {
                    if (all[i].shadowRoot) {
                      var inner = deepQuery(all[i].shadowRoot, selector);
                      if (inner) return inner;
                    }
                  }
                } catch (e) {}
                return null;
              }
              function fire(el) {
                var rect = el.getBoundingClientRect();
                var x = rect.left + rect.width / 2;
                var y = rect.top + rect.height / 2;
                var opts = { bubbles: true, composed: true, cancelable: true, clientX: x, clientY: y, view: window };
                var sequence = ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'];
                for (var i = 0; i < sequence.length; i++) {
                  var name = sequence[i];
                  try {
                    var Ev = (name.indexOf('pointer') === 0 && typeof PointerEvent !== 'undefined') ? PointerEvent : MouseEvent;
                    el.dispatchEvent(new Ev(name, opts));
                  } catch (e) {}
                }
                try { el.click(); } catch (e) {}
              }
              var timer = setInterval(function() {
                attempts++;
                if (attempts > 80) { clearInterval(timer); return; }
                var host = document.querySelector('cap-widget');
                if (!host) {
                  // 组件消失通常意味着挑战已通过、页面正在跳转
                  if (attempts > 5) clearInterval(timer);
                  return;
                }
                var root = host.shadowRoot;
                if (!root) return;
                var trigger = deepQuery(root, '.captcha-trigger')
                  || deepQuery(root, '.captcha')
                  || deepQuery(root, 'input[type=checkbox]')
                  || deepQuery(root, 'button');
                if (!trigger) return;
                var now = Date.now();
                if (now - lastClick < 3000) return;
                lastClick = now;
                fire(trigger);
              }, 700);
              return 'started';
            })()
        """.trimIndent()
    }
}
