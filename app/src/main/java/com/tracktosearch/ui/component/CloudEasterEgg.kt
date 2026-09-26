package com.tracktosearch.ui.component

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticCombinedClickable
import com.tracktosearch.ui.screen.swiftie.rememberReducedMotion

/**
 * 白云彩蛋组件 — 替换搜索页的静态白云图标
 *
 * 平时显示天气/节日主题 Lottie 动画，点击触发彩蛋回调。
 *
 * **每次可见只完整播一次**，播完定格在 [CloudTheme.freezeProgress]。
 * 「可见」这件事靠组合生命周期拿到：pager 的 `beyondViewportPageCount = 0` 让每次切回
 * Tab 都是全新组合，搜索框激活时外层 AnimatedVisibility 淡出完会把子树整个卸载，
 * 所以两种「重新看见」都会重新走到这里。重播的节流位不在组合里，见
 * [CloudThemeManager.claimWeatherPlay]。
 *
 * 非当前 Tab 时定格第一帧：切换过程中 pager 会短暂同时组合两页，那时既不该起播
 * （会把 8 秒窗口白占掉）也不该跟着画。
 */
@Composable
fun CloudEasterEgg(
    themeManager: CloudThemeManager,
    size: Dp = 182.dp,
    modifier: Modifier = Modifier,
    // 新手引导未完成时白云点击不做任何分派，计数也不累加（Spec §3.3）
    onboardingCompleted: Boolean = true,
    onCloudClicked: () -> Unit = { themeManager.onCloudClicked(onboardingCompleted) },
    onLongClick: (() -> Unit)? = null
) {
    val theme by themeManager.currentTheme.collectAsStateWithLifecycle()
    val isNightAlternate by themeManager.isNightAlternate.collectAsStateWithLifecycle()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // 当前 Tab 才起播，非当前 Tab 定格第一帧
    val isCurrentTab = LocalIsCurrentTab.current
    val reducedMotion = rememberReducedMotion()

    // 按下缩放反馈
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = tween(100),
        label = "cloud_press_scale"
    )

    // 获取当前应显示的主题
    val displayTheme = themeManager.getCurrentDisplayTheme()

    // hapticCombinedClickable 是 @Composable，局部 fun 装不下（局部 fun 不能标 @Composable），
    // 所以改成在 composable 体内直接算好的 Modifier 值
    val cloudGesture = Modifier.hapticCombinedClickable(
        interactionSource = interactionSource,
        indication = null,
        semantic = HapticSemantic.LIGHT_TAP,
        onClick = onCloudClicked,
        onLongClick = onLongClick
    )

    // 换主题必须以全新状态起播：animatable 的 progress 停在上一轮末态，而定格值是按
    // 主题查的，复用就会在新 composition 到位的那一帧画错画面。
    key(displayTheme) {
        val composition by rememberLottieComposition(
            LottieCompositionSpec.RawRes(displayTheme.rawRes)
        )
        val animatable = rememberLottieAnimatable()
        // 「本轮已了结」：要么播完了一次，要么闸门不给播。起底 false —— 真播过之前
        // 不能预先了结，否则起手就是定格帧、动画整轮在背后跑完。
        var settled by remember { mutableStateOf(false) }

        LaunchedEffect(isCurrentTab, composition) {
            if (settled || !isCurrentTab || composition == null) return@LaunchedEffect
            // 盖章排在 animate 之前：这一句会挂到播完才返回，先播后盖就等于
            // 整轮播放期间闸门还开着。
            if (themeManager.claimWeatherPlay(SystemClock.uptimeMillis(), reducedMotion)) {
                animatable.animate(
                    composition = composition,
                    iterations = 1,
                    initialProgress = 0f,
                )
            }
            settled = true
        }

        LottieAnimation(
            composition = composition,
            progress = {
                when {
                    !isCurrentTab || reducedMotion -> 0f
                    settled -> displayTheme.freezeProgress
                    else -> animatable.progress
                }
            },
            modifier = modifier
                .size(size)
                .scale(scale)
                .then(cloudGesture)
        )
    }
}
