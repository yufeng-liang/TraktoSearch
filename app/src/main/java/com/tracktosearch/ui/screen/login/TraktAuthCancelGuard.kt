package com.tracktosearch.ui.screen.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** 授权取消守卫的宽限时长：覆盖「deep link 先于 ON_RESUME 到达、回调收集器推进状态」的正常时序 */
private const val TRAKT_AUTH_CANCEL_GRACE_MS = 1_500L

/**
 * Trakt 浏览器授权取消守卫。
 *
 * 用户在 CustomTabs 授权页按返回取消时不会产生任何回调，loginState 会永久停留在
 * AUTHORIZING 锁死全部按钮。守卫监听生命周期：打开浏览器会使宿主 Activity ON_STOP，
 * 返回前台（ON_RESUME）后经过短暂宽限仍未收到授权回调则重置为 IDLE。
 *
 * 宽限期用于规避 ON_RESUME 与成功回调的竞态：deep link 在 onNewIntent（先于 ON_RESUME）
 * 写入 OAuthCallback，回到前台后回调收集器会在宽限窗口内把状态推进到 CONNECTING，
 * 此时守卫不再重置，正常授权流程不受影响。
 */
@Composable
internal fun TraktAuthCancelGuard(
    loginState: LoginState,
    onCanceled: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentLoginState by rememberUpdatedState(loginState)
    val currentOnCanceled by rememberUpdatedState(onCanceled)
    // 曾在 AUTHORIZING 期间离开前台：区分「从浏览器返回」与通知栏下拉等不离开前台的 ON_RESUME 抖动
    var leftForegroundWhileAuthorizing by remember { mutableStateOf(false) }
    var resumeEpoch by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP ->
                    if (currentLoginState == LoginState.AUTHORIZING) leftForegroundWhileAuthorizing = true
                Lifecycle.Event.ON_RESUME ->
                    if (leftForegroundWhileAuthorizing) resumeEpoch++
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumeEpoch) {
        if (resumeEpoch == 0) return@LaunchedEffect
        delay(TRAKT_AUTH_CANCEL_GRACE_MS)
        // 宽限结束后仍是 AUTHORIZING 才算取消：期间收到回调会先推进到 CONNECTING/SUCCESS
        if (currentLoginState == LoginState.AUTHORIZING) {
            leftForegroundWhileAuthorizing = false
            currentOnCanceled()
        }
    }
}