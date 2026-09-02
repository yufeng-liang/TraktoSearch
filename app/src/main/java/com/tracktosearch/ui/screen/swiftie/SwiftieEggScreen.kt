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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.tracktosearch.R
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieErasStage
import kotlinx.coroutines.delay

/** 灯箱与键盘的共同最大宽度（Spec §2.3：平板与折叠屏展开态 480dp 居中）。 */
private val CONTENT_MAX_WIDTH = 480.dp

/** 灯箱与键盘之间的间距。 */
private val CONTENT_GAP = 20.dp

/**
 * 底部音频提示带的基准高度（系统字号 100% 时）。
 *
 * 两行 12sp 约 28dp，加上提示自带的上下各 8dp 内边距共 44dp。四档译文里最长的是
 * **英文默认档**（约 276dp @12sp），窄屏上会折两行。
 *
 * **恒定预留**，不管提示当前是否显示 —— 用户中途插上耳机时提示会消失，
 * 若这块位子跟着让出来，灯箱就会在题面上重新排版跳一下。
 * 实际用值按 `fontScale` 放大，见 `SwiftieQuizStage`。
 */
private val AUDIO_HINT_BAND_BASE = 44.dp

/** 预留带的上限：再大就该让灯箱缩，而不是继续吃版面。 */
private val AUDIO_HINT_BAND_MAX = 96.dp

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
 * @param modifier 调用方用它给整页设 `zIndex` —— 全屏彩蛋必须压在离线横幅之上
 */
@Composable
fun SwiftieEggScreen(
    visible: Boolean,
    onDismiss: (solved: Boolean) -> Unit,
    onCommitUnlock: () -> Unit,
    replay: Boolean = false,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = visible) { onDismiss(false) }
    LockPortraitWhile(visible)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
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

/**
 * 序列播放期间保持亮屏。
 *
 * 只覆盖序列这 126s，不覆盖题面 —— 题面本来就在收触摸（用户在按键盘），
 * 系统超时是对的；而序列一旦开始就零触摸输入，撞上默认 30s 息屏会直接烂在
 * 第 4 张卡片附近。静态终态也不覆盖：那是一张静止画面，正常超时即可。
 */
@Composable
private fun KeepScreenOnWhile(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active, view) {
        if (!active) return@DisposableEffect onDispose { }
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
}

/**
 * 吞掉落在本层空白处的触摸。
 *
 * Compose 的命中测试会把同一个 `Box` 里所有被命中的兄弟分支都收进来，
 * 上层没消费的事件照样会派发给下层兄弟。彩蛋是全屏页，不吞就会漏给
 * `MainScreen` 的悬浮底栏与 Pager。在 Main pass 上消费即可 ——
 * 键盘按键是更深的子节点，Main pass 上先于本节点收到事件；
 * 「按住暂停」走 Initial pass，也在本节点之前。
 */
private fun Modifier.consumeStrayTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        var event: PointerEvent
        do {
            event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * 序列与静态终态期间强制**深色**系统栏图标。
 *
 * 那两段的底是满屏水彩天空（粉白 / 淡蓝 / 薰衣草，相对亮度 0.46–0.86）。深色模式下
 * `Theme.kt` 把状态栏图标设成浅色，白图标压在这张浅底上只有 1.2–2:1，时间和电量看不见。
 * 灯箱阶段不需要 —— 那时四周仍是 `surface`，浅色图标是对的。
 */
@Composable
private fun DarkSystemBarIconsWhile(active: Boolean) {
    val view = LocalView.current
    val window = LocalActivity.current?.window
    if (view.isInEditMode || window == null) return
    val controller = WindowCompat.getInsetsController(window, view)

    DisposableEffect(active) {
        val previousStatus = controller.isAppearanceLightStatusBars
        val previousNavigation = controller.isAppearanceLightNavigationBars
        onDispose {
            controller.isAppearanceLightStatusBars = previousStatus
            controller.isAppearanceLightNavigationBars = previousNavigation
        }
    }

    // 必须是 SideEffect，不能只在上面的 DisposableEffect 里写一次。
    //
    // Compose 的 apply 阶段先派发 RememberObserver（DisposableEffect / LaunchedEffect）
    // 再跑 SideEffect，而 `Theme.kt` 正是在 SideEffect 里按 darkTheme 设这两个值 ——
    // 写在 DisposableEffect 里必然被它盖掉。SideEffect 之间按组合顺序执行，彩蛋是主题的
    // 子树，所以同一帧里我们后写、我们赢。
    //
    // 唯一的缝：T1100 落主题那一下若只重组了主题、没重组彩蛋，图标会错几秒 ——
    // 序列的 phase 每几秒翻一次，彩蛋跟着重组就自己纠回来了
    if (active) {
        SideEffect {
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        }
    }
}

@Composable
private fun SwiftieEggContent(
    onDismiss: (solved: Boolean) -> Unit,
    onCommitUnlock: () -> Unit,
    replay: Boolean
) {
    var quiz by remember { mutableStateOf(SwiftieQuizState()) }
    // Unspecified 而不是 Zero：SwiftieDiffusion 用 isSpecified 判「键盘还没上报坐标」，
    // 给 Zero 会被当成一个真坐标，扩散就从左上角开始而不是回退到屏幕中心
    var submitCenter by remember { mutableStateOf(Offset.Unspecified) }
    // 走应用自己的四层触感引擎，不用 Compose 的 LocalHapticFeedback：后者是第三条通道，
    // 既走不到厂商预置效果，也不受设置页那个三档开关管 —— 用户选了「关闭」，彩蛋照样震
    val haptics = rememberAppHaptics()
    val reducedMotion = rememberReducedMotion()

    val sequenceRunning = quiz.solved && !reducedMotion
    val clock = rememberSwiftieSequenceClock(running = sequenceRunning)

    // 暂停有三个来源，必须分开记：按住松手就恢复，拖动定格要点「继续」，
    // 焦点被抢走要等焦点回来 —— 混成一个布尔值就会互相清掉
    var holdPaused by remember { mutableStateOf(false) }
    var seekFrozen by remember { mutableStateOf(false) }
    var focusPaused by remember { mutableStateOf(false) }

    /**
     * 焦点被永久抢走之后就不再要配乐了。
     *
     * 永久丢失（`AUDIOFOCUS_LOSS`）按系统契约不保证再补发 `GAIN`，
     * 继续挂在 [focusPaused] 上等于把整条序列永久钉在暂停态 ——
     * 「继续」只清 [seekFrozen]、按住暂停只翻 [holdPaused]，两者都解不开它。
     */
    var audioGivenUp by remember { mutableStateOf(false) }
    val framePaused = holdPaused || seekFrozen || focusPaused
    LaunchedEffect(framePaused) { clock.paused = framePaused }

    /**
     * 重看纪念页时**不再落主题**。
     *
     * `commitSwiftieUnlock` 里的强调色与网格预设不是幂等的：解锁之后用户完全可以
     * 在设置里换掉它们（星云选项就是给已解锁用户开的）。重播一次就把他的选择
     * 静默改回 RENOIR + NEBULA，是纯粹的数据损失。
     */
    val commitUnlock: () -> Unit = { if (!replay) onCommitUnlock() }

    // 126s 零触摸，不按住就会撞上系统息屏超时（默认 30s）——
    // 息屏会把 Activity 推进 ON_STOP，帧时钟随之停摆，整段序列烂在中途
    KeepScreenOnWhile(sequenceRunning)

    // 答对之后底是满屏浅色水彩天空，深色模式的浅色状态栏图标会看不见
    DarkSystemBarIconsWhile(quiz.solved)

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
    // T0–1100 与 T105950 之后开着，中间 105s 关掉（Spec §5 约束 2、3）。
    //
    // 必须过一道 derivedStateOf：这个 lambda 由 `AmbientMeshBackground` 在**组合体**里
    // 求值（`speedScale = if (... && motionActive())`），直接读 elapsedMs 等于把逐帧的
    // 时钟读进组合阶段，全屏 mesh 层会每帧重组一次、整条序列约 7600 次。
    // 派生成布尔量之后只翻转两次
    val meshMotionOn by remember {
        derivedStateOf {
            clock.elapsedMs < SwiftieTimeline.THEME_COMMIT_AT ||
                clock.elapsedMs >= SwiftieTimeline.MOTION_PREHEAT_AT
        }
    }
    val meshMotionActive: () -> Boolean = { meshMotionOn }

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
        haptics.reject()
        delay(WRONG_SHAKE_MS)
        quiz = quiz.clearWrong()
    }

    LaunchedEffect(quiz.solved) {
        if (quiz.solved) haptics.confirm()
    }

    // 「减少动效」不跑序列：立刻落地主题，然后给静态终态，等用户自己按 ✕
    val staticFinale = quiz.solved && reducedMotion
    LaunchedEffect(staticFinale) {
        if (staticFinale) commitUnlock()
    }

    // T1100 那一帧提交三写入。derivedStateOf 保证只翻转一次，所以只会调一次
    LaunchedEffect(themeCommitted) {
        if (themeCommitted) commitUnlock()
    }

    LaunchedEffect(phase) {
        if (phase == SwiftieSequencePhase.DONE) onDismiss(true)
    }

    SwiftieMusic(
        enabled = sequenceRunning && !audioGivenUp,
        paused = framePaused,
        // 时钟是唯一时间来源：重建播放器与响应「跳过」/ 拖播放头都按它对位
        positionMs = { clock.elapsedMs },
        seekEpoch = clock.seekEpoch,
        onFocusChange = { focus ->
            when (focus) {
                // 别的应用抢走焦点：画面与音乐一起停，焦点回来时自己接着走
                SwiftieAudioFocus.GAINED -> focusPaused = false
                SwiftieAudioFocus.LOST_TRANSIENT -> focusPaused = true
                // 永久丢失：放弃配乐，让动画静音走完，而不是永久冻住
                SwiftieAudioFocus.LOST_PERMANENT -> {
                    focusPaused = false
                    audioGivenUp = true
                }
            }
        }
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
            // T123000–125998：整层淡出，露出已经在运动的星云背景（Spec §5）。
            // 必须插在 background 之前 —— 写在之后只淡出子内容、底色仍然挡着星云
            .graphicsLayer {
                alpha = 1f - ((clock.elapsedMs - SwiftieTimeline.FADE_OUT_START).toFloat() /
                    SwiftieTimeline.FADE_OUT_MS).coerceIn(0f, 1f)
            }
            .background(MaterialTheme.colorScheme.surface)
            .then(pauseGesture)
            // 全屏页必须自己吞掉落在空白处的触摸，否则会穿到下层 MainScreen 的
            // 悬浮底栏上去 —— 题面阶段点键盘下缘那条带就能把 Pager 切到别的 Tab
            .consumeStrayTouches()
    ) {
        // 最底层：答对那一帧就挂上几乎透明的 mesh，把 AGSL 编译付在确认窗口里。
        // 静态终态不需要预热，也没有扩散
        if (quiz.solved && !staticFinale) {
            SwiftieMeshPreheat(motionActive = meshMotionActive)
        }

        if (quizMounted && !staticFinale) {
            SwiftieQuizStage(
                quiz = quiz,
                showAudioHint = !reducedMotion,
                onQuizChange = { quiz = it },
                onSubmitCenter = { submitCenter = it },
                // 数字键与退格连按会很快，用最轻的一档。提交键不走这条 —— 它按下去的结果
                // 要么答对要么答错，上面那两个 LaunchedEffect 会发 confirm() 或 reject()，
                // 再叠一记 lightTap 就是「轻一下 + 重一下」两记挤在一起
                onKeyHaptic = { haptics.lightTap() }
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
        // 配乐唱到 Lover 时再回来做倒滑与绽放，收在最后一帧。
        // FADE_OUT 也必须留着 —— 那 2998ms 淡出的主体正是绽放开的 Lover 卡片，
        // 漏掉它就会在 T123000（淡出还没开始的那一帧）把满屏的卡片整块硬切掉
        if (phase == SwiftieSequencePhase.ERAS_INTRO ||
            phase == SwiftieSequencePhase.ERAS_CARDS ||
            phase == SwiftieSequencePhase.REWIND ||
            phase == SwiftieSequencePhase.LOVER_BLOOM ||
            phase == SwiftieSequencePhase.FADE_OUT
        ) {
            SwiftieErasStage(
                clock = clock,
                frozen = seekFrozen,
                onFrozenChange = { seekFrozen = it },
                replay = replay,
                modifier = Modifier.graphicsLayer { alpha = erasAlpha() }
            )
        }

        // 终局挂到倒滑段末尾：交叉淡变在 T118500 走完，REWIND 到 T119500，
        // 留 1000ms 余量。再往后 alpha 恒为 0，继续挂着只是白重录签名那两层
        if (phase >= SwiftieSequencePhase.SIGNATURE && phase <= SwiftieSequencePhase.REWIND) {
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

/**
 * 题面版面（灯箱 + 键盘 + 音频提示）。状态由外部持有，本函数只负责摆位与转发回调。
 *
 * @param showAudioHint 「减少动效」路径根本不播配乐（`SwiftieMusic(enabled = false)`），
 *   那时候还劝人戴耳机就是骗人 —— 提示与实际播放必须一致
 */
@Composable
private fun SwiftieQuizStage(
    quiz: SwiftieQuizState,
    showAudioHint: Boolean,
    onQuizChange: (SwiftieQuizState) -> Unit,
    onSubmitCenter: (Offset) -> Unit,
    onKeyHaptic: () -> Unit
) {
    // 无条件调用，题面在屏上就一直跟踪：用户中途插耳机或调音量，提示要自己消失
    val audioAdvice = rememberSwiftieAudioAdvice()
    // 预留带要跟着系统字号长：两行 12sp 在 100% 下约 28dp，加上下 8dp 内边距就是 44dp，
    // 而字号档位能把它顶到两倍。写死 dp 会让提示压在键盘末行上
    val audioHintBand = (AUDIO_HINT_BAND_BASE * LocalDensity.current.fontScale)
        .coerceIn(AUDIO_HINT_BAND_BASE, AUDIO_HINT_BAND_MAX)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        // 版面总高 = 灯箱(1×宽) + 间距 + 键盘(≈0.833×宽 + 13dp) ⇒ 1.833×宽 + 33dp
        val fitByHeight = (maxHeight - audioHintBand - CONTENT_GAP - 13.dp) / 1.833f
        val contentWidth = minOf(maxWidth - 40.dp, CONTENT_MAX_WIDTH, fitByHeight)

        Column(
            modifier = Modifier.padding(bottom = audioHintBand),
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

        // 答对之后不再提示 —— 那时已经来不及去开声音了，序列马上就要起
        if (showAudioHint && !quiz.solved) {
            SwiftieAudioHint(
                advice = audioAdvice,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
    }
}



