package com.tracktosearch.ui.screen.swiftie

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieErasStage
import kotlinx.coroutines.delay

/** 灯箱与键盘的共同最大宽度（Spec §2.3：平板与折叠屏展开态 480dp 居中）。 */
private val CONTENT_MAX_WIDTH = 480.dp

/** 灯箱与键盘之间的间距。 */
private val CONTENT_GAP = 20.dp

/** 答错摇晃时长，与 [SwiftieBillboard] 的 keyframes 对齐。 */
private const val WRONG_SHAKE_MS = 300L

/**
 * 终局交还给 Lover 收尾的交叉淡变窗口。
 *
 * 配乐 1:58 唱到 Lover，此时签名与手链已经放完，两层在这 500ms 里对调（见
 * [SwiftieTimeline] 的类注释）。倒滑总共 1500ms，淡变走完还剩 1000ms 看播放头飞回去。
 */
private const val FINALE_HANDOFF_MS = 500f

/**
 * 霉粉彩蛋全屏页：1:1 灯箱复刻 + 自绘数字键盘，答对后驱动整条 120s 序列。
 *
 * @param onDismiss solved = true 表示答对通关；false 表示用户主动关闭（不消耗解题机会）
 * @param onCommitUnlock 主题接管回调，由内容体在扩散铺满全屏那一帧调用
 * @param replay 已解锁后重看纪念页：「跳过」立即可用，不再等 3s 淡入（Spec §3.3）
 */
@Composable
fun SwiftieEggScreen(
    visible: Boolean,
    onDismiss: (solved: Boolean) -> Unit,
    onCommitUnlock: () -> Unit,
    replay: Boolean = false
) {
    BackHandler(enabled = visible) { onDismiss(false) }
    LockPortraitWhile(visible)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        // 内容随 AnimatedVisibility 一起挂载/销毁，所以每次打开都是新的一道题
        SwiftieEggContent(
            onDismiss = onDismiss,
            onCommitUnlock = onCommitUnlock,
            replay = replay
        )
    }
}

/** 彩蛋页锁竖屏（Spec §2.3）。离场时还原成进入前的值，不写死 `UNSPECIFIED`。 */
@Composable
private fun LockPortraitWhile(active: Boolean) {
    val activity = LocalActivity.current
    DisposableEffect(active, activity) {
        if (!active || activity == null) return@DisposableEffect onDispose { }
        val previous = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity.requestedOrientation = previous }
    }
}

@Composable
private fun SwiftieEggContent(
    onDismiss: (solved: Boolean) -> Unit,
    onCommitUnlock: () -> Unit,
    replay: Boolean
) {
    var quiz by remember { mutableStateOf(SwiftieQuizState()) }
    var submitCenter by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()

    val sequenceRunning = quiz.solved && !reducedMotion
    val clock = rememberSwiftieSequenceClock(running = sequenceRunning)

    // 暂停有三个来源，必须分开记：按住松手就恢复，拖动定格要点「继续」，
    // 焦点被抢走要等焦点回来 —— 混成一个布尔值就会互相清掉
    var holdPaused by remember { mutableStateOf(false) }
    var seekFrozen by remember { mutableStateOf(false) }
    var focusPaused by remember { mutableStateOf(false) }
    val framePaused = holdPaused || seekFrozen || focusPaused
    LaunchedEffect(framePaused) { clock.paused = framePaused }

    // 每帧变化的量只在 draw lambda 里读；组合里只读这些「翻转一次」的派生量
    val phase by remember { derivedStateOf { swiftiePhaseAt(clock.elapsedMs) } }
    val themeCommitted by remember {
        derivedStateOf { clock.elapsedMs >= SwiftieTimeline.THEME_COMMIT_AT }
    }
    val quizMounted by remember {
        derivedStateOf { clock.elapsedMs < SwiftieTimeline.ERAS_INTRO_START }
    }
    // 扩散铺满之后天空就定住了，用它把逐帧的时钟读断开（见下方 SwiftieDiffusion 处的注释）
    val diffusionDone by remember {
        derivedStateOf {
            clock.elapsedMs >= SwiftieTimeline.DIFFUSION_START + SwiftieTimeline.DIFFUSION_MS
        }
    }
    // T0–1100 与 T105950 之后开着，中间 105s 关掉（Spec §5 约束 2、3）
    val meshMotionActive: () -> Boolean = {
        clock.elapsedMs < SwiftieTimeline.THEME_COMMIT_AT ||
            clock.elapsedMs >= SwiftieTimeline.MOTION_PREHEAT_AT
    }

    // T118000 起 500ms 交叉淡变：签名与手链化开，轴与 Lover 卡片浮回来。
    // 两个 lambda 都只在 draw 阶段读时钟，所以每帧只失效绘制、不重组
    val finaleAlpha: () -> Float = {
        1f - ((clock.elapsedMs - SwiftieTimeline.REWIND_START).toFloat() / FINALE_HANDOFF_MS)
            .coerceIn(0f, 1f)
    }
    // 前半段（12 张卡片）必须恒为 1f —— 直接套上面那道斜坡会因为差值为负而把整段压成 0
    val erasAlpha: () -> Float = {
        if (clock.elapsedMs < SwiftieTimeline.SIGNATURE_START) {
            1f
        } else {
            ((clock.elapsedMs - SwiftieTimeline.REWIND_START).toFloat() / FINALE_HANDOFF_MS)
                .coerceIn(0f, 1f)
        }
    }

    // 答错：Reject 触觉 + 摇晃走完后自动清空
    LaunchedEffect(quiz.wrongCount) {
        if (quiz.wrongCount == 0) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        delay(WRONG_SHAKE_MS)
        quiz = quiz.clearWrong()
    }

    LaunchedEffect(quiz.solved) {
        if (quiz.solved) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    // 「减少动效」不跑序列：立刻落地主题，然后给静态终态，等用户自己按 ✕
    val staticFinale = quiz.solved && reducedMotion
    LaunchedEffect(staticFinale) {
        if (staticFinale) onCommitUnlock()
    }

    // T1100 那一帧提交三写入。derivedStateOf 保证只翻转一次，所以只会调一次
    LaunchedEffect(themeCommitted) {
        if (themeCommitted) onCommitUnlock()
    }

    LaunchedEffect(phase) {
        if (phase == SwiftieSequencePhase.DONE) onDismiss(true)
    }

    SwiftieMusic(
        enabled = sequenceRunning,
        paused = framePaused,
        // 别的应用抢走焦点：画面与音乐一起停，焦点回来时自己接着走。
        // 这里必须双向处理 —— 只处理丢失的话，一条系统提示音就能永久冻住整段序列
        onFocusChange = { hasFocus -> focusPaused = !hasFocus }
    )

    // 按住屏幕任意处暂停，松手继续（Spec §6.1）。走 Initial pass 不消费事件，
    // 所以 ✕ 与「跳过」照样点得到
    val pauseGesture = if (sequenceRunning) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                holdPaused = true
                var event: PointerEvent
                do {
                    event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
                holdPaused = false
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // T123000–125000：整层淡出，露出已经在运动的星云背景（Spec §5）。
            // 必须插在 background 之前 —— 写在之后只淡出子内容、底色仍然挡着星云
            .graphicsLayer {
                alpha = 1f - ((clock.elapsedMs - SwiftieTimeline.FADE_OUT_START).toFloat() /
                    SwiftieTimeline.FADE_OUT_MS).coerceIn(0f, 1f)
            }
            .background(MaterialTheme.colorScheme.surface)
            .then(pauseGesture)
    ) {
        // 最底层：答对那一帧就挂上几乎透明的 mesh，把 AGSL 编译付在确认窗口里。
        // 静态终态不需要预热，也没有扩散
        if (quiz.solved && !staticFinale) {
            SwiftieMeshPreheat(motionActive = meshMotionActive)
        }

        if (quizMounted && !staticFinale) {
            SwiftieQuizStage(
                quiz = quiz,
                onQuizChange = { quiz = it },
                onSubmitCenter = { submitCenter = it },
                onKeyHaptic = { haptics.performHapticFeedback(HapticFeedbackType.VirtualKey) }
            )
        }

        if (quiz.solved && !staticFinale) {
            SwiftieDiffusion(
                origin = { submitCenter },
                // 铺满之后返回常量 1f，且**不再读时钟** —— 这一层要在屏上待满 120s，
                // 继续每帧读 elapsedMs 会让整块全屏水彩天空跟着每帧失效重绘。
                // diffusionDone 是 derivedStateOf，只翻转一次，之后就不再通知依赖方
                progress = {
                    if (diffusionDone) {
                        1f
                    } else {
                        ((clock.elapsedMs - SwiftieTimeline.DIFFUSION_START).toFloat() /
                            SwiftieTimeline.DIFFUSION_MS).coerceIn(0f, 1f)
                    }
                }
            )
        }

        // Eras 舞台分两段挂载：开场轴线 + 12 张卡片，然后整层卸载让位给终局；
        // 配乐唱到 Lover 时再回来做倒滑与绽放，收在最后一帧
        if (phase == SwiftieSequencePhase.ERAS_INTRO ||
            phase == SwiftieSequencePhase.ERAS_CARDS ||
            phase == SwiftieSequencePhase.REWIND ||
            phase == SwiftieSequencePhase.LOVER_BLOOM
        ) {
            SwiftieErasStage(
                clock = clock,
                frozen = seekFrozen,
                onFrozenChange = { seekFrozen = it },
                replay = replay,
                modifier = Modifier.graphicsLayer { alpha = erasAlpha() }
            )
        }

        // 终局一直挂到最后：倒滑那 500ms 里淡出，之后 alpha 已经是 0，不再出帧
        if (phase >= SwiftieSequencePhase.SIGNATURE && phase != SwiftieSequencePhase.DONE) {
            SwiftieFinaleStage(
                elapsedMs = { clock.elapsedMs },
                modifier = Modifier.graphicsLayer { alpha = finaleAlpha() }
            )
        }

        if (staticFinale) {
            SwiftieStaticFinale()
        }

        // T1100 之前按 ✕ 算放弃（不消耗解题机会），之后算已通关。
        // 静态路径里时钟从不推进，themeCommitted 永远是 false，所以要或上 staticFinale
        IconButton(
            onClick = { onDismiss(themeCommitted || staticFinale) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.common_close),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 题面版面（灯箱 + 键盘）。状态由外部持有，本函数只负责摆位与转发回调。 */
@Composable
private fun SwiftieQuizStage(
    quiz: SwiftieQuizState,
    onQuizChange: (SwiftieQuizState) -> Unit,
    onSubmitCenter: (Offset) -> Unit,
    onKeyHaptic: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        // 版面总高 = 灯箱(1×宽) + 间距 + 键盘(≈0.833×宽 + 13dp) ⇒ 1.833×宽 + 33dp
        val fitByHeight = (maxHeight - CONTENT_GAP - 13.dp) / 1.833f
        val contentWidth = minOf(maxWidth - 40.dp, CONTENT_MAX_WIDTH, fitByHeight)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SwiftieBillboard(state = quiz, modifier = Modifier.width(contentWidth))
            Spacer(modifier = Modifier.height(CONTENT_GAP))
            SwiftieKeypad(
                canSubmit = quiz.canSubmit,
                enabled = !quiz.solved,
                onDigit = { digit ->
                    onKeyHaptic()
                    onQuizChange(quiz.append(digit))
                },
                onBackspace = {
                    onKeyHaptic()
                    onQuizChange(quiz.backspace())
                },
                onSubmit = { onQuizChange(quiz.submit()) },
                onSubmitCenter = onSubmitCenter,
                modifier = Modifier.width(contentWidth)
            )
        }
    }
}



