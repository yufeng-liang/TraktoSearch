package com.tracktosearch.ui.screen.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.Window
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tracktosearch.R
import com.tracktosearch.ui.component.AppDialogActionRow
import com.tracktosearch.ui.component.AppFloatingDialog
import com.tracktosearch.ui.component.DialogAction
import com.tracktosearch.ui.haptic.rememberAppHaptics
import kotlinx.coroutines.delay

private data class TrailerFullscreen(
    val view: View,
    val callback: WebChromeClient.CustomViewCallback
)

@Composable
internal fun YouTubePlayerDialog(videoKey: String, videoTitle: String, onDismiss: () -> Unit) {
    var attempt by remember(videoKey) { mutableIntStateOf(0) }
    var fullscreen by remember(videoKey, attempt) { mutableStateOf<TrailerFullscreen?>(null) }
    // WebView 自己退出全屏时已回调 onCustomViewHidden，不能再补一次；用可变引用让清理阶段读到退出原因
    val webViewExited = remember(videoKey, attempt) { mutableStateOf(false) }
    val exitFullscreen: (Boolean) -> Unit = { byWebView ->
        webViewExited.value = byWebView
        fullscreen = null
    }
    val onBack: () -> Unit = { if (fullscreen != null) exitFullscreen(false) else onDismiss() }

    AppFloatingDialog(
        onDismissRequest = onBack,
        contentPadding = PaddingValues(0.dp),
        fullScreen = true
    ) {
        BackHandler(onBack = onBack)
        key(videoKey, attempt) {
            TrailerPlayerContent(
                videoKey = videoKey,
                videoTitle = videoTitle,
                fullscreen = fullscreen,
                webViewExited = webViewExited,
                onEnterFullscreen = { view, callback ->
                    if (fullscreen == null) {
                        webViewExited.value = false
                        fullscreen = TrailerFullscreen(view, callback)
                    } else {
                        callback.onCustomViewHidden()
                    }
                },
                // WebView 侧的退出（播放器自带的全屏按钮）
                onWebViewExitFullscreen = { exitFullscreen(true) },
                // 我方侧的退出（关闭按钮、返回键、播放出错）
                onCloseFullscreen = { exitFullscreen(false) },
                onRetry = { attempt++ },
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun TrailerPlayerContent(
    videoKey: String,
    videoTitle: String,
    fullscreen: TrailerFullscreen?,
    webViewExited: MutableState<Boolean>,
    onEnterFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onWebViewExitFullscreen: () -> Unit,
    onCloseFullscreen: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hostView = LocalView.current
    val haptics = rememberAppHaptics()
    var player by remember { mutableStateOf<YouTubeWebPlayer?>(null) }
    var ready by remember { mutableStateOf(false) }
    var errorMessage by remember {
        mutableStateOf(if (YouTubeEmbed.isValidVideoKey(videoKey)) null else R.string.trailer_unavailable)
    }

    // 打不开播放器时不能一直转圈：超时按「网络不可达」处理，用户可重试或改用浏览器。
    LaunchedEffect(Unit) {
        delay(20_000)
        if (!ready && errorMessage == null) errorMessage = R.string.trailer_network_failed
    }
    // 弹层打开期间 App 退到后台要停声，避免继续占用音频焦点。
    DisposableEffect(lifecycle, player) {
        val currentPlayer = player
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> currentPlayer?.setInForeground(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> currentPlayer?.setInForeground(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(fullscreen, hostView) {
        val window = findDialogWindow(hostView)
        val insets = window?.let { WindowCompat.getInsetsController(it, hostView) }
        if (fullscreen != null) {
            insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen != null) {
                (fullscreen.view.parent as? ViewGroup)?.removeView(fullscreen.view)
                if (!webViewExited.value) fullscreen.callback.onCustomViewHidden()
                insets?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Box(Modifier.fillMaxSize().testTag("trailer_player_dialog")) {
        // 全屏时整棵树保持挂载，只把 WebView 交给的全屏 View 移到上层容器，
        // 从视图树里摘掉 WebView 会让画面一放大就销毁重建。
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = videoTitle.ifBlank { stringResource(R.string.detail_video_trailer) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { haptics.lightTap(); onDismiss() }) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.detail_close))
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth()
                    .heightIn(min = 200.dp).aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp)).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (errorMessage == null) {
                    AndroidView(
                        factory = { ctx ->
                            YouTubeWebPlayer(
                                context = ctx,
                                onReady = { ready = true },
                                onError = { code ->
                                    onCloseFullscreen()
                                    errorMessage = YouTubeEmbed.errorMessage(code)
                                },
                                onEnterFullscreen = onEnterFullscreen,
                                onExitFullscreen = onWebViewExitFullscreen
                            ).apply {
                                setInForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                                loadVideo(videoKey)
                            }
                        },
                        update = { player = it },
                        onRelease = {
                            it.releasePlayer()
                            if (player === it) player = null
                        },
                        modifier = Modifier.fillMaxSize().testTag("trailer_web_player")
                    )
                    if (!ready) {
                        Column(
                            Modifier.fillMaxSize().background(Color.Black),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.trailer_loading), color = Color.White)
                        }
                    }
                } else {
                    val message = errorMessage
                    if (message != null) {
                        Text(
                            text = stringResource(message),
                            modifier = Modifier.padding(24.dp).testTag("trailer_player_error"),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TrailerNetworkNotice(Modifier.widthIn(max = 960.dp))
            Spacer(Modifier.height(12.dp))
            AppDialogActionRow(
                primary = if (errorMessage != null) DialogAction(
                    label = stringResource(R.string.trailer_retry), onClick = onRetry
                ) else null,
                // 内嵌播放失败或受限时的解法就是去浏览器，所以这条手动入口要一直留着。
                secondary = listOf(DialogAction(
                    label = stringResource(R.string.detail_video_open_browser),
                    onClick = {
                        player?.setInForeground(false)
                        try {
                            context.startActivity(Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.youtube.com/watch?v=${Uri.encode(videoKey)}")
                            ))
                        } catch (_: ActivityNotFoundException) {
                            player?.setInForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                            Toast.makeText(context, R.string.trailer_open_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                ))
            )
        }
        if (fullscreen != null) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        (fullscreen.view.parent as? ViewGroup)?.removeView(fullscreen.view)
                        addView(fullscreen.view, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                        ))
                    }
                },
                onRelease = { it.removeAllViews() },
                modifier = Modifier.fillMaxSize().testTag("trailer_fullscreen")
            )
            IconButton(
                onClick = { haptics.lightTap(); onCloseFullscreen() },
                modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp)
            ) {
                Icon(
                    Icons.Rounded.FullscreenExit,
                    stringResource(R.string.trailer_exit_fullscreen),
                    tint = Color.White
                )
            }
        }
    }
}

/** 全屏时把系统栏也藏掉要拿到承载弹层的 Window；不同 Compose 版本层级不同，逐级上溯更稳。 */
private fun findDialogWindow(view: View): Window? {
    var parent: ViewParent? = view.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = parent.parent
    }
    return null
}
