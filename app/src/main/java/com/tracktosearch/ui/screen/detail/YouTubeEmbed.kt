package com.tracktosearch.ui.screen.detail

import androidx.annotation.StringRes
import com.tracktosearch.R
import java.net.URI
import java.util.Locale

/** 只接收 YouTube 视频 ID，绝不把远端提供的 URL 或标题拼进可执行脚本。 */
internal object YouTubeEmbed {
    const val BRIDGE_NAME = "TrailerBridge"
    private val videoKeyPattern = Regex("[A-Za-z0-9_-]{11}")

    fun isValidVideoKey(key: String): Boolean = videoKeyPattern.matches(key)

    fun baseUrl(applicationId: String): String {
        require(Regex("[A-Za-z][A-Za-z0-9_.]*").matches(applicationId))
        return "https://${applicationId.lowercase(Locale.ROOT)}/"
    }

    fun html(videoKey: String, applicationId: String, languageTag: String): String {
        require(isValidVideoKey(videoKey))
        val origin = baseUrl(applicationId).removeSuffix("/")
        val language = languageTag.takeIf { Regex("[A-Za-z0-9-]+").matches(it) } ?: "en"
        // loadDataWithBaseURL 与 origin 一致，按官方要求用应用 ID 标识客户端，避免 153 错误。
        return """
            <!doctype html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="referrer" content="strict-origin-when-cross-origin">
            <style>html,body,#player{margin:0;width:100%;height:100%;background:#000;overflow:hidden}iframe{border:0}</style>
            </head><body><div id="player"></div>
            <script>
            var player;
            function onYouTubeIframeAPIReady() {
                player = new YT.Player('player', {
                    width: '100%', height: '100%', videoId: '$videoKey',
                    playerVars: {autoplay:0, controls:1, playsinline:1, fs:1, rel:0, origin:'$origin', hl:'$language'},
                    events: {
                        onReady: function() { $BRIDGE_NAME.onReady(); },
                        onError: function(event) { $BRIDGE_NAME.onError(event.data); },
                        onStateChange: function(event) { $BRIDGE_NAME.onStateChange(event.data); }
                    }
                });
            }
            </script>
            <script src="https://www.youtube.com/iframe_api" onerror="$BRIDGE_NAME.onError(-1)"></script>
            </body></html>
        """.trimIndent()
    }

    /** 顶层和外部协议一律拦截；外部打开只能来自原生界面的明确点击。 */
    fun allowsNavigation(url: String, isMainFrame: Boolean): Boolean {
        if (isMainFrame) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && (
            host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
            )
    }

    @StringRes
    fun errorMessage(code: Int): Int = when (code) {
        -1 -> R.string.trailer_network_failed
        2, 100 -> R.string.trailer_unavailable
        101, 150 -> R.string.trailer_embed_disabled
        153 -> R.string.trailer_identity_failed
        else -> R.string.trailer_playback_failed
    }
}
