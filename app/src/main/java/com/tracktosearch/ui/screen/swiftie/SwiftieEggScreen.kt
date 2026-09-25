package com.tracktosearch.ui.screen.swiftie

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.tracktosearch.R
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.screen.swiftie.eras.LOVER_HIT_MS
import com.tracktosearch.ui.screen.swiftie.eras.LOVER_SHOT_MS
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieEraBackdropLayer
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieEraParticleLayer
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieEraStage
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieErasData
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieErasStage
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieLoverArrowFlight
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieRedLeafFallLayer
import com.tracktosearch.ui.screen.swiftie.eras.redLeafFallActiveAt
import com.tracktosearch.ui.screen.swiftie.eras.swiftieLoverAim
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay

/** 键盘的最大宽度（Spec §2.3：平板与折叠屏展开态居中，不跟着屏宽长）。 */
private val KEYPAD_MAX_WIDTH = 480.dp

/**
 * 海报版式的最大宽度。
 *
 * 天空是位图、照样铺满全屏（`ContentScale.Crop`），受这个上限约束的只有**排字用的
 * 那张海报**：平板上算式与手写体不跟着屏宽长，否则一条算式横跨 900dp。
 */
private val POSTER_MAX_WIDTH = 560.dp

/** 算式底边与键盘托盘之间至少留这么多。 */
private val EQUATION_GAP = 16.dp

/** `Her lucky number.` 的常驻预留高度。只动 alpha，浮出时不挤动键盘。 */
private val LUCKY_BAND = 34.dp

/**
 * 底部音频提示带的基准高度（系统字号 100% 时）。
 *
 * 两行 12sp 约 28dp，加上提示自带的上下各 8dp 内边距共 44dp。四档译文里最长的是
 * **英文默认档**（约 276dp @12sp），窄屏上会折两行。
 *
 * **恒定预留**，不管提示当前是否显示 —— 用户中途插上耳机时提示会消失，
 * 若这块位子跟着让出来，键盘就会往下塌一截、算式也跟着重新排版跳一下。
 * 实际用值按 `fontScale` 放大，见 `SwiftieQuizStage`。
 */
private val AUDIO_HINT_BAND_BASE = 44.dp

/** 预留带的上限：再大就该让算式缩，而不是继续吃版面。 */
private val AUDIO_HINT_BAND_MAX = 96.dp

/** 答错摇晃时长，与 [SwiftiePoster] 的 keyframes 对齐。 */
private const val WRONG_SHAKE_MS = 300L

/**
 * 答对之后、序列时钟起跑之前的前奏。
 *
 * 这 1500ms **不在 [SwiftieTimeline] 的账本里**：配乐与后面每一段动画的对位都是按
 * `TOTAL_MS = 125_998` 排的，往里插一段就会把整条序列往后推、和配乐错开。所以时钟在
 * 这段里根本还没起跑 —— 键盘退场、算式走回原图的位置、手写体写出来，全都发生在 T0
 * 之前。顺带也给 `SwiftieMeshPreheat` 多 1500ms 去编译 AGSL。
 */
private const val PREROLL_MS = 1500L

/** 键盘下滑淡出：T+0 起 500ms。它先腾地方，算式才有处可去。 */
private const val PREROLL_KEYPAD_MS = 500f

/** 算式归位：T+250 起 600ms。和键盘退场重叠 250ms，两段读成一个动作。 */
private const val PREROLL_SETTLE_AT = 250f
private const val PREROLL_SETTLE_MS = 600f

/** 手写体落笔：T+550，写完正好落在 1450（[SCRIPT_TOTAL_MS] = 900）。 */
private const val PREROLL_SCRIPT_AT = 550f

/** 「减少动效」下把写完的整幅海报停这么久，再切静态终态。 */
private const val REDUCED_HOLD_MS = 1200L

/**
 * 浮出控件无操作后自动收起的时长。
 *
 * 2s 是「看得清、不等人」的档：控件只是按暂停/跳过前的一瞥，再点屏幕随时能叫回来。
 * 暂停态下这个计时**不启动**（见调用处）—— 藏掉唯一的「继续」等于把人困在定格里。
 */
private const val CONTROLS_AUTO_HIDE_MS = 2_000L

/**
 * 「跳过」在 T3000 之后才进这组控件 —— 前 3 秒先让惊喜落地，别一上来就劝人走。
 * 重看纪念页时立刻可用。
 */
private const val SKIP_OFFERED_AT_MS = 3_000L

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
 * @param previewStartMs 仅 `eggpreview` 变体用：非 null 就跳过题面直接进序列，
 *   并把时钟拨到这一刻。截图迭代要能直接看第 9 张卡片或雪景球的第 4 拍，
 *   而不是每次从头等 60 秒
 * @param previewPaused 仅 `eggpreview` 变体用：时钟停住，截到的是稳定的一帧
 * @param modifier 调用方用它给整页设 `zIndex` —— 全屏彩蛋必须压在离线横幅之上
 */
@Composable
fun SwiftieEggScreen(
    visible: Boolean,
    onDismiss: (solved: Boolean) -> Unit,
    onCommitUnlock: () -> Unit,
    replay: Boolean = false,
    previewStartMs: Long? = null,
    previewPaused: Boolean = false,
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
            replay = replay,
            previewStartMs = previewStartMs,
            previewPaused = previewPaused
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
 * 换张交叉淡变的时长。
 *
 * 刻意等于「卡片回落 400ms + 段间停顿 100ms」—— 那 500ms 里旧卡片正在收、新卡片还没长出，
 * 背景换色藏在这个窗口里就看不见接缝。**总时长一毫秒不动**，配乐钉死的两个点不受影响
 * （`SwiftieTimelineTest` 守着账本）。
 */
private const val BACKDROP_CROSSFADE_MS: Float = 500f

/** 背景 L1 大主体的循环相位周期。与卡片母题的 3.6s 同步，两层的呼吸不会各走各的。 */
private const val BACKDROP_CYCLE_MS: Long = 3_600L

/**
 * L2 飘落物的节拍相位周期：翻面 / 扑翼 / 闪烁 / 自转的快慢都由它定。
 * 行程（进出画面、横向游走）不跟它走，另见 [PARTICLE_TRAVEL_CYCLE_MS]。
 *
 * **刻意不跟 L1 共用 3.6s。** 那 3.6s 是「呼吸」的节奏（灯在明暗、雾在起伏），
 * 这层的闪烁翻面跟它对齐就会整层一起脉动；12s 也让两层的合拍周期变成 36s，
 * 长过任何一张卡片的停留，所以看不出「又对上了」。节拍档位（`beat` / `twist`）
 * 按这个周期调过，改了它们就失真。
 */
private const val PARTICLE_CYCLE_MS: Long = 12_000L

/**
 * L2 飘落物的行程周期。12s 的 2.5 倍 —— 同一档 `rounds` 摊到更长的周期上，
 * 飘落速度就是原来的 1/2.5。`rounds` 是整数，travelPhase 绕回 0f 时
 * `travelPhase * rounds` 的小数部分不变，位置仍然连续无缝。
 */
private const val PARTICLE_TRAVEL_CYCLE_MS: Long = 30_000L

/** 终局那一环的自转周期。30s 一圈，18.6s 的段落只转过 0.6 圈，慢到读作呼吸。 */
private const val FINALE_RING_CYCLE_MS: Long = 30_000L

/** 落在哪一张卡片上。卡片段之外夹到首张 / Lover。 */
private fun eraSlotAt(elapsedMs: Long): Int =
    SwiftieTimeline.eraIndexAt(elapsedMs) ?: if (elapsedMs < SwiftieTimeline.ERAS_CARDS_START) {
        0
    } else {
        SwiftieErasData.LOVER_INDEX
    }

/**
 * Lover 那一段的总时长。终局期间背景钉在 Lover，段内时钟就用这个末帧值 ——
 * 彩虹上那颗心因此保持「已被射中」的静态（见 `backdropEraElapsed`）。
 */
private val LOVER_ERA_MS: Long = SwiftieTimeline.cardDurationMs(
    SwiftieErasData.LOVER_INDEX,
    SwiftieTimeline.ERA_TRACK_COUNTS[SwiftieErasData.LOVER_INDEX]
)

/**
 * 换张交叉淡变进度。0f = 还没开始换，1f = 已经全是新的那张。
 *
 * 最后一张（Showgirl）不参与 —— 它后面接的是终局那一环，不是第 13 张专辑。
 */
private fun backdropCrossfadeAt(elapsedMs: Long): Float {
    val index = SwiftieTimeline.eraIndexAt(elapsedMs) ?: return 0f
    if (index == SwiftieTimeline.ERA_TRACK_COUNTS.lastIndex) return 0f
    val end = SwiftieTimeline.eraStartMs(index) +
        SwiftieTimeline.cardDurationMs(index, SwiftieTimeline.ERA_TRACK_COUNTS[index])
    val from = end - BACKDROP_CROSSFADE_MS.toLong()
    return ((elapsedMs - from).toFloat() / BACKDROP_CROSSFADE_MS).coerceIn(0f, 1f)
}

/**
 * 当前该用哪一档系统栏图标 —— `null` 表示底是浅色（水彩天空或终局那一环），两处都用深色图标。
 *
 * 图标在**交叉淡变的中点**翻，不是在卡片边界翻：那一刻两层各 50%，
 * 从 1989 的淡蓝换到 reputation 的纯黑时底色正好是中灰，两种图标都还勉强可读，
 * 翻转最不显眼。
 */
private fun barStageAt(elapsedMs: Long): SwiftieEraStage? = when {
    elapsedMs < SwiftieTimeline.ERAS_INTRO_START -> null
    elapsedMs < SwiftieTimeline.ERAS_CARDS_END -> {
        val slot = eraSlotAt(elapsedMs)
        val advanced = if (backdropCrossfadeAt(elapsedMs) >= 0.5f) slot + 1 else slot
        SwiftieErasData.STAGE[advanced.coerceAtMost(SwiftieErasData.STAGE.lastIndex)]
    }
    elapsedMs < SwiftieTimeline.REWIND_START -> null
    else -> SwiftieErasData.STAGE[SwiftieErasData.LOVER_INDEX]
}

/**
 * 序列与静态终态期间按当前专辑的底色定系统栏图标明暗。
 *
 * 这里原来写死「深色图标」—— 那时底是一张固定的浅色水彩天空。背景重构之后每张专辑
 * 自己一套三档底色：12 张里 reputation（`#111111`）与 Midnights（`#1B2A5B`）**顶部就是深色**，
 * 深色图标压在上面根本看不见；而末档底色是深色的有 10 张，导航栏必须独立判。
 *
 * 两个布尔量都**手填在 `SwiftieErasData.STAGE` 里，不算相对亮度** —— 算出来的值会在
 * 换张那 500ms 的交叉淡变里来回跨过阈值，图标就一路闪。
 *
 * 必须是 SideEffect，不能只在下面的 DisposableEffect 里写一次。
 *
 * Compose 的 apply 阶段先派发 RememberObserver（DisposableEffect / LaunchedEffect）
 * 再跑 SideEffect，而 `Theme.kt` 正是在 SideEffect 里按 darkTheme 设这两个值 ——
 * 写在 DisposableEffect 里必然被它盖掉。SideEffect 之间按组合顺序执行，彩蛋是主题的
 * 子树，所以同一帧里我们后写、我们赢。
 *
 * @param darkStatusBarIcons 状态栏图标用深色（顶部底色是浅的）
 * @param darkNavBarIcons 导航栏图标用深色（底部底色是浅的）
 */
@Composable
private fun DynamicSystemBarIconsWhile(
    active: Boolean,
    darkStatusBarIcons: Boolean,
    darkNavBarIcons: Boolean
) {
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

    if (active) {
        SideEffect {
            // 平台命名是反的：isAppearanceLight*Bars = true 表示「浅色外观」，
            // 也就是**深色图标**。这里的参数名按图标本身命名，映射就是恒等
            controller.isAppearanceLightStatusBars = darkStatusBarIcons
            controller.isAppearanceLightNavigationBars = darkNavBarIcons
        }
    }
}

@Composable
private fun SwiftieEggContent(
    onDismiss: (solved: Boolean) -> Unit,
    onCommitUnlock: () -> Unit,
    replay: Boolean,
    previewStartMs: Long?,
    previewPaused: Boolean
) {
    // 只为了纪念页手链上的昵称珠。在彩蛋内部读取，调用方不必为了纪念页扩散用户状态。
    val nicknameViewModel: SwiftieNicknameViewModel = hiltViewModel()
    val nickname by nicknameViewModel.nickname.collectAsStateWithLifecycle()

    // 预览变体直接给已解答态 —— 截图迭代不该每次都先答一遍 X + 87 = 100
    var quiz by remember {
        mutableStateOf(
            if (previewStartMs != null) {
                SwiftieQuizState(phase = SwiftieQuizPhase.SOLVED)
            } else {
                SwiftieQuizState()
            }
        )
    }
    // Unspecified 而不是 Zero：SwiftieDiffusion 用 isSpecified 判「键盘还没上报坐标」，
    // 给 Zero 会被当成一个真坐标，扩散就从左上角开始而不是回退到屏幕中心
    var submitCenter by remember { mutableStateOf(Offset.Unspecified) }
    // 走应用自己的四层触感引擎，不用 Compose 的 LocalHapticFeedback：后者是第三条通道，
    // 既走不到厂商预置效果，也不受设置页那个三档开关管 —— 用户选了「关闭」，彩蛋照样震
    val haptics = rememberAppHaptics()
    val reducedMotion = rememberReducedMotion()
    // 背景三层要按它降档：L0/L1 静态、L2 粒子减半但仍动。
    // 整块定格看起来像卡死，留一层飘落物就还活着
    val lowRam = rememberIsLowRamDevice()

    /**
     * 前奏进度（ms）。答对之后线性推到 [PREROLL_MS]，序列时钟这段时间里一动不动。
     * 预览变体直接给已解答态，前奏一毫秒不等 —— 它要的是立刻跳到指定时刻截图
     */
    val preroll = remember { Animatable(0f) }
    var prerollDone by remember { mutableStateOf(previewStartMs != null) }
    LaunchedEffect(quiz.solved, reducedMotion, previewStartMs) {
        if (previewStartMs != null || !quiz.solved || reducedMotion) return@LaunchedEffect
        preroll.animateTo(
            targetValue = PREROLL_MS.toFloat(),
            animationSpec = tween(durationMillis = PREROLL_MS.toInt(), easing = LinearEasing)
        )
        prerollDone = true
    }
    // 只在 draw 阶段被读：没答对是 0；减少动效与预览直接给终值（那两条路径不放前奏动画）
    val prerollMs: () -> Float = {
        when {
            !quiz.solved -> 0f
            reducedMotion || previewStartMs != null -> PREROLL_MS.toFloat()
            else -> preroll.value
        }
    }

    val sequenceRunning = quiz.solved && !reducedMotion && prerollDone
    val clock = rememberSwiftieSequenceClock(running = sequenceRunning)

    // 暂停有四个来源，必须分开记：点按钮暂停要再点一次才走，拖动定格要点「继续」，
    // 焦点被抢走要等焦点回来，预览定格从头到尾不动 —— 混成一个布尔值就会互相清掉
    var userPaused by remember { mutableStateOf(false) }
    var seekFrozen by remember { mutableStateOf(false) }
    var focusPaused by remember { mutableStateOf(false) }

    /**
     * 焦点被永久抢走之后就不再要配乐了。
     *
     * 永久丢失（`AUDIOFOCUS_LOSS`）按系统契约不保证再补发 `GAIN`，
     * 继续挂在 [focusPaused] 上等于把整条序列永久钉在暂停态 ——
     * 「继续」只清 [userPaused] 与 [seekFrozen]，解不开它。
     */
    var audioGivenUp by remember { mutableStateOf(false) }
    val framePaused = userPaused || seekFrozen || focusPaused || previewPaused
    LaunchedEffect(framePaused) { clock.paused = framePaused }

    // ---- 序列触感编排（设计文档「彩蛋编排」）----
    //
    // 走 Provider<AppHaptics> 而不是上面那个 rememberAppHaptics()：编排要 playEnvelope
    // 与 stopOngoing，而且谱子里按乐句排好的密集 tick 不能被 ComposeHaptics 那道
    // 40ms 节流吞掉。题面那三记（键盘 / 答对 / 答错）仍走 ComposeHaptics —— 它们是
    // 交互反馈，节流对它们是对的
    val conductor = rememberSwiftieHapticConductor(reducedMotion)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()

    // 四条闸门与 SwiftieMusic 同源：点按钮暂停 / 拖轴定格 / 焦点临时丢失 / 预览定格都
    // 汇进 framePaused，ON_STOP（息屏、切后台）看生命周期，AUDIOFOCUS_LOSS 看
    // audioGivenUp。序列没在跑（题面阶段、静态终态）时整条静音
    val hapticsMuted = !sequenceRunning ||
        framePaused ||
        audioGivenUp ||
        !lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val mutedNow = rememberUpdatedState(hapticsMuted)

    LaunchedEffect(conductor, clock) {
        // snapshotFlow 而不是再挂一条 withFrameMillis：帧时钟上已经有时钟自己那条循环，
        // 两条 withFrameMillis 的先后不保证，触感会稳定落后一帧。
        // 值没变就不发射 —— 暂停时 elapsedMs 不动，这里一次都不跑
        snapshotFlow { clock.elapsedMs }.collect { elapsed ->
            conductor.onFrame(
                elapsedMs = elapsed,
                seekEpoch = clock.seekEpoch,
                muted = mutedNow.value
            )
        }
    }

    // 闸门关上、以及整页销毁（用户在包络播放中途按 ✕）时把在飞的波形停掉。
    // tier 3 与 tier 1 都有停止通道，所以这两处真的停得住，见 conductor.stopOngoing 的说明。
    // 加 sequenceRunning 这道条件是为了不在挂载那一帧白调一次 —— 那时什么都还没响
    LaunchedEffect(hapticsMuted, sequenceRunning) {
        if (sequenceRunning && hapticsMuted) conductor.stopOngoing()
    }
    DisposableEffect(conductor) { onDispose { conductor.stopOngoing() } }


    /**
     * 浮出控件的可见性与它的保活计数。
     *
     * 需求方明确去掉了「点一下就暂停」和「按住暂停」：现在点屏幕只是**把控件叫出来**，
     * 序列继续放，用户选了按钮才动作。[controlsTick] 每次触摸自增，用来重置自动收起的计时 ——
     * 少了它，`LaunchedEffect(controlsVisible)` 在已经可见时再点一下不会重新计时。
     */
    var controlsVisible by remember { mutableStateOf(false) }
    var controlsTick by remember { mutableIntStateOf(0) }

    // 预览变体：进来就把时钟拨到指定时刻。seekTo 会递增 seekEpoch，
    // 配乐（如果在放）跟着挪，不会和画面错开
    LaunchedEffect(previewStartMs) {
        if (previewStartMs != null) clock.seekTo(previewStartMs)
    }

    // 无操作 2s 自动收起。**暂停态不收** —— 藏掉唯一的出口等于把人困在定格里
    LaunchedEffect(controlsVisible, controlsTick, framePaused) {
        if (!controlsVisible || framePaused) return@LaunchedEffect
        delay(CONTROLS_AUTO_HIDE_MS)
        controlsVisible = false
    }

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

    /**
     * 系统栏图标那两档。翻在交叉淡变中点，整段一共翻不到 20 次。
     *
     * 走 `derivedStateOf` 是必须的：直接把 `barStageAt(clock.elapsedMs)` 写进组合体
     * 等于每帧重组整个彩蛋子树。
     */
    val barStage by remember { derivedStateOf { barStageAt(clock.elapsedMs) } }

    /** 背景三层从扩散那一刻挂上，一直留到最后一帧 —— 终局那 18.6s Eras 舞台会卸载，背景不能跟着走。 */
    val backdropMounted by remember {
        derivedStateOf { clock.elapsedMs >= SwiftieTimeline.DIFFUSION_START }
    }

    /** 「跳过」是否进这组控件。收尾 8s 只留暂停：跳过会倒拨时钟，Lover 与配乐当场错开。 */
    val skipOffered by remember {
        derivedStateOf {
            clock.elapsedMs < SwiftieTimeline.SIGNATURE_START &&
                (replay || clock.elapsedMs >= SKIP_OFFERED_AT_MS)
        }
    }

    // 换张交叉淡变的三个入参。全是 lambda：只在 draw 阶段读，每帧只失效绘制
    val backdropOutgoing: () -> Int = { eraSlotAt(clock.elapsedMs) }
    val backdropIncoming: () -> Int = {
        val slot = eraSlotAt(clock.elapsedMs)
        if (backdropCrossfadeAt(clock.elapsedMs) > 0f) {
            (slot + 1).coerceAtMost(SwiftieErasData.STAGE.lastIndex)
        } else {
            slot
        }
    }
    val backdropCrossfade: () -> Float = { backdropCrossfadeAt(clock.elapsedMs) }
    val backdropPhase: () -> Float = {
        clock.elapsedMs.mod(BACKDROP_CYCLE_MS).toFloat() / BACKDROP_CYCLE_MS
    }

    /**
     * 本段已过多少毫秒。**四处读它**：TTPD 那台打字机（敲字 / 滑架步进 / 出纸要与卡片
     * 对上拍）、reputation 那条蛇、Lover 彩虹上那颗心（命中之后才带箭），以及 Midnights
     * 面钟上弦的两根指针。
     * [backdropPhase] 是一条 3.6s 的循环锯齿，问不出「第几拍」。
     *
     * intro 期间返回 -1：背景那时是第 1 张，没人读它。换张交叉淡变那 500ms 里 incoming
     * 的段还没开始，`SwiftieEraBackdropLayer` 会**另外**给它 -1，纸不会早出。
     */
    val backdropEraElapsed: () -> Long = {
        val elapsed = clock.elapsedMs
        val index = SwiftieTimeline.eraIndexAt(elapsed)
        when {
            index != null -> elapsed - SwiftieTimeline.eraStartMs(index)
            // 终局那 18.6s 背景钉在 Lover（见 eraSlotAt）。这里若也给 -1，插在彩虹那颗心上
            // 的箭会在倒滑起点当场消失 —— 而收尾整段讲的正是「回到 Lover」。
            // 给 Lover 段的末帧：命中与那一下回弹都早已走完，心是带箭的静态
            elapsed >= SwiftieTimeline.ERAS_CARDS_START -> LOVER_ERA_MS
            else -> -1L
        }
    }

    /**
     * 当前卡片在根坐标里的边框（px）。[Rect.Zero] = 还没量到，读它的两处各有兜底比例。
     *
     * **两张卡片回报到同一个 state**：同一时刻只挂着一张，而读它的两处都按段判人 ——
     * TTPD 那台打字机要边框（不只是上缘）把滚筒上那截立纸对齐纸宽，平板上卡片封顶 480dp
     * 居中，只按屏宽画的立纸会比出来的纸宽出一大截；Lover 那一箭要从卡片里的弓起飞，
     * 起点必须是那张卡片的道具框，不是屏幕上的某个比例。
     *
     * 只在 draw lambda 里读，所以写它不会引起重组 —— 但**必须**是 snapshot state，
     * 普通 var 写完那一帧背景不会重画。
     */
    var heroCardBounds by remember { mutableStateOf(Rect.Zero) }

    /** 根 Box 的真实像素尺寸，与 [heroCardBounds] 和背景画布共用同一个根坐标系。 */
    var rootSize by remember { mutableStateOf(Size.Zero) }

    /**
     * Lover 那一箭是否在飞。
     *
     * 一整支序列只有这 900ms 需要页面最上层那一层。`derivedStateOf` 只在布尔翻转时
     * 通知依赖方（一段里两次），所以剩下的 95 秒连那个全屏节点都不存在 ——
     * 常挂着的话每帧都要为它重录一次全屏 display list，而它 99% 的时间只是立刻 return。
     */
    val loverArrowFlying by remember {
        derivedStateOf {
            val elapsed = clock.elapsedMs
            SwiftieTimeline.eraIndexAt(elapsed) == SwiftieErasData.LOVER_INDEX &&
                elapsed - SwiftieTimeline.eraStartMs(SwiftieErasData.LOVER_INDEX) in
                LOVER_SHOT_MS..LOVER_HIT_MS
        }
    }
    /**
     * Red 的落叶层是否挂着。
     *
     * 与 [loverArrowFlying] 同一个理由：全屏节点常挂着，每帧都要为它重录一次
     * display list，而它 12 张里只在 Red 那 9.4s 有东西要画。
     */
    val redLeafFallOn by remember {
        derivedStateOf { redLeafFallActiveAt(clock.elapsedMs) }
    }
    val particlePhase: () -> Float = {
        clock.elapsedMs.mod(PARTICLE_CYCLE_MS).toFloat() / PARTICLE_CYCLE_MS
    }
    val particleTravelPhase: () -> Float = {
        clock.elapsedMs.mod(PARTICLE_TRAVEL_CYCLE_MS).toFloat() / PARTICLE_TRAVEL_CYCLE_MS
    }

    /**
     * 专辑背景的不透明度：intro 里自水彩天空之上淡入，终局那 18.6s 让位给环，倒滑时换回来。
     *
     * 换回来的窗口刻意也用 [BACKDROP_CROSSFADE_MS] —— 倒滑总共 1500ms，
     * 500ms 换完还剩 1000ms 看播放头飞回 Lover。
     */
    val albumBackdropAlpha: () -> Float = {
        val elapsed = clock.elapsedMs
        when {
            elapsed < SwiftieTimeline.ERAS_INTRO_START -> 0f
            elapsed < SwiftieTimeline.ERAS_CARDS_START ->
                ((elapsed - SwiftieTimeline.ERAS_INTRO_START).toFloat() /
                    SwiftieTimeline.ERAS_INTRO_MS).coerceIn(0f, 1f)
            elapsed < SwiftieTimeline.ERAS_CARDS_END -> 1f
            elapsed < SwiftieTimeline.REWIND_START ->
                1f - ((elapsed - SwiftieTimeline.ERAS_CARDS_END).toFloat() /
                    BACKDROP_CROSSFADE_MS).coerceIn(0f, 1f)
            else -> ((elapsed - SwiftieTimeline.REWIND_START).toFloat() /
                BACKDROP_CROSSFADE_MS).coerceIn(0f, 1f)
        }
    }

    /** 终局那一环：签名段起淡入，倒滑段起淡出。与 [albumBackdropAlpha] 互补。 */
    val finaleRingAlpha: () -> Float = {
        val elapsed = clock.elapsedMs
        when {
            elapsed < SwiftieTimeline.ERAS_CARDS_END -> 0f
            elapsed < SwiftieTimeline.REWIND_START ->
                ((elapsed - SwiftieTimeline.ERAS_CARDS_END).toFloat() /
                    BACKDROP_CROSSFADE_MS).coerceIn(0f, 1f)
            else -> 1f - ((elapsed - SwiftieTimeline.REWIND_START).toFloat() /
                BACKDROP_CROSSFADE_MS).coerceIn(0f, 1f)
        }
    }
    val finaleRingPhase: () -> Float = {
        clock.elapsedMs.mod(FINALE_RING_CYCLE_MS).toFloat() / FINALE_RING_CYCLE_MS
    }

    // 水晶球出场前把 Lover 背景里的房子、栅栏和爱心箭收走：倒滑这一段半淡完，
    // 球在 LOVER_BLOOM_START 接手那正好全收，球页只剩彩虹云海垫着。
    // 只影响 Lover 场景里这三组笔触，彩虹/云/雾带照旧
    val loverHouseFade: () -> Float = {
        1f - ((clock.elapsedMs - SwiftieTimeline.REWIND_START).toFloat() /
            SwiftieTimeline.REWIND_MS).coerceIn(0f, 1f)
    }

    // 答对之后底是逐张换色的专辑背景：浅底要深色图标，reputation 与 Midnights 要浅色图标。
    // barStage 为 null 表示底是水彩天空或终局那一环，两处都用深色
    DynamicSystemBarIconsWhile(
        active = quiz.solved,
        darkStatusBarIcons = barStage?.darkStatusBarIcons ?: true,
        darkNavBarIcons = barStage?.darkBottomInk ?: true
    )

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

    // 「减少动效」不跑序列：立刻落地主题，把写完的海报停 1200ms 让人看完，再给静态终态
    val staticFinale = quiz.solved && reducedMotion
    var staticHoldDone by remember { mutableStateOf(false) }
    LaunchedEffect(staticFinale) {
        if (!staticFinale) return@LaunchedEffect
        commitUnlock()
        delay(REDUCED_HOLD_MS)
        staticHoldDone = true
    }

    // T1100 那一帧提交三写入。derivedStateOf 保证只翻转一次，所以只会调一次
    LaunchedEffect(themeCommitted) {
        if (themeCommitted) commitUnlock()
    }

    LaunchedEffect(phase) {
        if (phase == SwiftieSequencePhase.DONE) onDismiss(true)
    }

    // ✕ 只在出题页保留，答对之后退出靠返回手势。这条接住 ✕ 原来的语义：
    // T1100 之后/静态路径退出都算已通关（兜底提交解锁），出题期的返回仍由外层
    // BackHandler 接（放弃、不消耗解题机会）—— 本条 enabled 更晚注册，答对后优先生效
    BackHandler(enabled = quiz.solved) { onDismiss(themeCommitted || staticFinale) }

    SwiftieMusic(
        enabled = sequenceRunning && !audioGivenUp,
        paused = framePaused,
        // 时钟是唯一时间来源：重建播放器与响应「跳过」/ 拖播放头都按它对位。
        // 钳在配乐自己那一毫秒上：尾巴那 `TAIL_FADE_MS` 是球化开的时间不是配乐的时间，
        // 不钳的话那一段里息屏再回前台会 seek 过音频末尾
        positionMs = { clock.elapsedMs.coerceAtMost(SwiftieTimeline.TOTAL_MS) },
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

    /**
     * 点屏幕任意处**把控件叫出来**；控件已在屏上时，点在**空白处**把它们收回去。
     *
     * 走 Main pass 且不消费事件，两个后果都是要的：
     * 1. 控件本身、播放头照样点得到 —— 它们在更深的子节点上，Main pass 上先于本节点收到。
     *    整段手势里只要出现过已消费的事件（点在按钮上、拖过播放头）就不算「再点一下」，
     *    收起只认从头到尾没被任何子控件碰过的那次点击。
     * 2. 拖播放头也会顺带把控件叫出来（叫出与收起是两件事：叫出只看按下，收起看整段手势）。
     *
     * 必须放在 [consumeStrayTouches] **之后**（更内层）：Main pass 上更内层先看到事件，
     * 要赶在它把空白触摸吞掉之前读到抬起时的消费状态，否则空白点击和按钮点击无法区分。
     */
    val controlsTapGesture = if (sequenceRunning) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                val startedVisible = controlsVisible
                controlsVisible = true
                controlsTick++
                var consumedByChild = false
                var event: PointerEvent
                do {
                    event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.changes.any { it.isConsumed }) consumedByChild = true
                } while (event.changes.any { it.pressed })
                if (startedVisible && !consumedByChild) controlsVisible = false
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = Size(it.width.toFloat(), it.height.toFloat()) }
            // T125998–127198：整层淡出，露出已经在运动的星云背景（Spec §5）。
            // 起点就是配乐的最后一帧，于是球是「音乐停了才化开」，化 1.2 秒收场；
            // 绽放收束到这里之间那 2998ms 它满亮站着。必须插在 background 之前 ——
            // 写在之后只淡出子内容、底色仍然挡着星云
            .graphicsLayer {
                alpha = 1f - ((clock.elapsedMs - SwiftieTimeline.FADE_OUT_START).toFloat() /
                    SwiftieTimeline.FADE_OUT_MS).coerceIn(0f, 1f)
            }
            .background(MaterialTheme.colorScheme.surface)
            // 全屏页必须自己吞掉落在空白处的触摸，否则会穿到下层 MainScreen 的
            // 悬浮底栏上去 —— 题面阶段点键盘下缘那条带就能把 Pager 切到别的 Tab
            .consumeStrayTouches()
            .then(controlsTapGesture)
    ) {
        // 最底层：答对那一帧就挂上几乎透明的 mesh，把 AGSL 编译付在确认窗口里。
        // 静态终态不需要预热，也没有扩散
        if (quiz.solved && !staticFinale) {
            SwiftieMeshPreheat(motionActive = meshMotionActive)
        }

        // 出题页要一直挂到序列接手：前奏那 1500ms 里键盘退场、算式归位、手写体写出来，
        // 全都发生在这一层上。减少动效路径没有序列，所以挂到静态终态接手为止
        if (quizMounted && !staticHoldDone) {
            SwiftieQuizStage(
                quiz = quiz,
                showAudioHint = !reducedMotion,
                prerollMs = prerollMs,
                onQuizChange = { quiz = it },
                // 答对之后冻住：键盘正在下滑退场，再收它上报的坐标会把扩散原点拖出屏幕
                onSubmitCenter = { if (!quiz.solved) submitCenter = it },
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

        // 页面背景三层，压在水彩天空之上、所有内容之下。
        //
        // **必须挂在这一层而不是 SwiftieErasStage 里** —— 终局那 15.99s（签名 + 手链 / 定格）
        // Eras 舞台整层卸载，背景却要一直在；放进去就会在卡片段收尾那一帧整屏闪回水彩天空。
        if (backdropMounted && !staticFinale) {
            SwiftieEraBackdropLayer(
                outgoing = backdropOutgoing,
                incoming = backdropIncoming,
                crossfade = backdropCrossfade,
                phase = backdropPhase,
                eraElapsedMs = backdropEraElapsed,
                cardBounds = { heroCardBounds },
                lowRam = lowRam,
                loverHouseFade = loverHouseFade,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = albumBackdropAlpha() }
            )
            // 终局那一环：12 张主色化开，语义上是「12 个时代汇成这一个签名」
            SwiftieFinaleBackdrop(
                progress = finaleRingAlpha,
                phase = finaleRingPhase,
                modifier = Modifier.fillMaxSize()
            )
            // L2 飘落物压在大主体之上、卡片之下。Speak Now、reputation 与 Red 三张
            // 在这层什么都不画 —— 前两张的背景自己在动，Red 的秋叶挂在卡片之上
            // （见下方 `SwiftieRedLeafFallLayer`）。
            // 节拍走 12s（PARTICLE_CYCLE_MS），行程走 30s（PARTICLE_TRAVEL_CYCLE_MS，
            // 即慢 2.5 倍），都不跟 L1 的呼吸同拍
            SwiftieEraParticleLayer(
                outgoing = backdropOutgoing,
                incoming = backdropIncoming,
                crossfade = backdropCrossfade,
                phase = particlePhase,
                travelPhase = particleTravelPhase,
                lowRam = lowRam,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = albumBackdropAlpha() }
            )
        }

        // Eras 舞台分两段挂载：开场轴线 + 12 张卡片，然后整层卸载让位给终局；
        // 配乐唱到 Lover 时再回来做倒滑与雪景球，收在最后一帧。
        // FADE_OUT 也必须留着 —— 那 2998ms 淡出的主体正是那只雪景球，
        // 漏掉它就会在 T123000（淡出还没开始的那一帧）把满屏的球整块硬切掉
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
                onCardBoundsChange = { heroCardBounds = it },
                loverAimAngle = { swiftieLoverAim(heroCardBounds, rootSize).angle },
                modifier = Modifier.graphicsLayer { alpha = erasAlpha() }
            )
            // Lover 那一箭在飞的 900ms：**必须挂在卡片之上**。弓在卡片里、心在背景里，
            // 而箭要从卡片飞到背景上 —— 途中它得压在卡片上面，否则一离弦就钻到卡片背后
            // 消失了。命中那一帧本层卸载，背景那一层同一帧接手画插住的那支箭
            if (loverArrowFlying) {
                SwiftieLoverArrowFlight(
                    elapsedMs = backdropEraElapsed,
                    cardBounds = { heroCardBounds },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = erasAlpha() }
                )
            }
            // Red 的落叶同样**挂在卡片之上**（与 L2 那层飘落物唯一的区别就在这儿）：
            // 这一张要演「枝上的叶松手 → 落过曲目表」，压在卡片背后就只剩半程。
            // 淡出那 500ms 跟着换张权重收，不然后一张已经全亮了这里还满屏叶
            if (redLeafFallOn) {
                SwiftieRedLeafFallLayer(
                    eraElapsedMs = backdropEraElapsed,
                    lowRam = lowRam,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = erasAlpha() * (1f - backdropCrossfade()) }
                )
            }
        }

        // 终局挂到倒滑段末尾：交叉淡变在 T118500 走完，REWIND 到 T119500，
        // 留 1000ms 余量。再往后 alpha 恒为 0，继续挂着只是白重录签名那两层
        if (phase >= SwiftieSequencePhase.SIGNATURE && phase <= SwiftieSequencePhase.REWIND) {
            SwiftieFinaleStage(
                elapsedMs = { clock.elapsedMs },
                nickname = nickname,
                modifier = Modifier.graphicsLayer { alpha = finaleAlpha() }
            )
        }

        if (staticFinale && staticHoldDone) {
            SwiftieStaticFinale(nickname = nickname)
        }

        // 暂停 / 跳过合并成这一组浮出控件。点屏幕叫出来、再点空白处收起，无操作 2s 自动收，
        // 暂停态转常驻（自动收不跑，手动收可以）。
        //
        // 「继续」要**同时**清掉 userPaused 与 seekFrozen：拖过播放头之后两者都可能为真，
        // 只清一个的话按下去画面不动，读起来就是按钮坏了。focusPaused 不清 ——
        // 那是别的应用占着音频焦点，用户在本页按什么都不该抢回来
        if (sequenceRunning) {
            SwiftieSequenceControls(
                visible = controlsVisible,
                paused = framePaused,
                skipEnabled = skipOffered,
                onTogglePause = {
                    if (framePaused) {
                        userPaused = false
                        seekFrozen = false
                    } else {
                        userPaused = true
                    }
                    controlsTick++
                },
                onSkip = {
                    clock.skipToFinalHold()
                    userPaused = false
                    seekFrozen = false
                    controlsVisible = false
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 92.dp)
            )
        }

        // ✕ 只在出题页保留（那时序列没开始，没有暂停/跳过可代替退出）；答对之后的播放
        // 与定格期不再常驻叉号，退出交给返回手势 —— 下面的 BackHandler 接住 ✕ 原来的语义。
        //
        // T1100 之前按 ✕ 算放弃（不消耗解题机会），之后算已通关。
        // 静态路径里时钟从不推进，themeCommitted 永远是 false，所以要或上 staticFinale
        //
        // 图标色跟着**专辑背景**走，两档都写死颜色。
        //
        // 不能用 colorScheme.onSurface：那是跟着 app / 系统深色设置走的量，而这一页整屏
        // 都被专辑背景铺满，主题的表面色一寸都没露出来。第七轮两批截图正好跨过了系统
        // 深色主题的定时切换点（20:55 那批 vs 21:12 那批）：同一个 Red（顶部 #FBE3E3 浅粉），
        // 前一批 ✕ 量到 (24,28,31)，后一批量到 (224,227,230) —— 深色主题一开，
        // onSurface 翻成近白，10 张浅顶专辑的 ✕ 全部白底白字。
        //
        // 复用状态栏图标的那个极性量 —— ✕ 就贴在状态栏下面，两者判断的是同一片底色：
        // reputation（顶部 #3A3A3A）与 Midnights（#2A3A6B）这两张深顶的用白，其余用深墨
        if (!quiz.solved) {
            val closeTint = if (barStage?.darkStatusBarIcons != false) {
                Color(0xFF1F1B18)
            } else {
                Color.White
            }
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
                    tint = closeTint
                )
            }
        }
    }
}

/**
 * 出题页：整屏海报 + 浮在上面的玻璃键盘。
 *
 * 版面是**反着算**的：先按宽度推出键盘托盘有多高（键帽是 `aspectRatio(1.6f)`），加上
 * 两条提示带与安全区，得到「算式底边最低能到哪儿」，再交给 [swiftiePosterFit] 解出
 * 出题态要把算式抬多高、要不要缩。写死一个 `0.33H` 那种比例，在 640dp 高的屏上会把
 * 算式顶出画面。
 *
 * @param showAudioHint 「减少动效」路径根本不播配乐（`SwiftieMusic(enabled = false)`），
 *   那时候还劝人戴耳机就是骗人 —— 提示与实际播放必须一致
 * @param prerollMs 前奏进度（ms）。只在 draw 阶段读，组合期一次都不失效
 */
@Composable
private fun SwiftieQuizStage(
    quiz: SwiftieQuizState,
    showAudioHint: Boolean,
    prerollMs: () -> Float,
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
    // 键盘采海报这一层做模糊。API < 31 上 hazeBlur 不生效，键盘自己退到厚一档透明度
    val hazeState = remember { HazeState() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val insets = WindowInsets.safeDrawing
        val topInset = with(density) { insets.getTop(density).toDp() }
        val bottomInset = with(density) { insets.getBottom(density).toDp() }

        val keypadWidth = minOf(maxWidth - 40.dp, KEYPAD_MAX_WIDTH)
        val keypadHeight = swiftieKeypadHeight(keypadWidth)
        // 底部这一摞的总高。算式底边不能越过它
        val bottomStack =
            LUCKY_BAND + 8.dp + audioHintBand + 10.dp + keypadHeight + 12.dp + bottomInset
        val fit = swiftiePosterFit(
            screen = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) },
            equationBottomLimit = with(density) { (maxHeight - bottomStack - EQUATION_GAP).toPx() },
            // 顶边给状态栏与 ✕ 让位：✕ 是 36dp 底衬 + 8dp 内边距
            equationTopLimit = with(density) { (topInset + 52.dp).toPx() },
            maxTextWidth = with(density) { POSTER_MAX_WIDTH.toPx() }
        )

        // 三个 lambda 全部只在 draw 阶段求值
        val settle: () -> Float = {
            FastOutSlowInEasing.transform(
                ((prerollMs() - PREROLL_SETTLE_AT) / PREROLL_SETTLE_MS).coerceIn(0f, 1f)
            )
        }
        // 负数表示还没落笔，drawScript 会直接返回
        val scriptRevealMs: () -> Long = { (prerollMs() - PREROLL_SCRIPT_AT).toLong() }

        SwiftiePoster(
            state = quiz,
            fit = fit,
            settle = settle,
            scriptRevealMs = scriptRevealMs,
            modifier = Modifier.hazeSource(state = hazeState)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 12.dp)
                // 答对后整摞下滑出画并淡掉 —— 腾出来的正是算式归位要占的地方。
                // 两条提示不单独退场，跟着这一层走，否则会在同一瞬间硬切消失
                .graphicsLayer {
                    val exit = (prerollMs() / PREROLL_KEYPAD_MS).coerceIn(0f, 1f)
                    translationY = exit * (size.height + 24.dp.toPx())
                    alpha = 1f - exit
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.height(LUCKY_BAND), contentAlignment = Alignment.Center) {
                SwiftieLuckyHint(visible = quiz.showLuckyHint)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(modifier = Modifier.height(audioHintBand), contentAlignment = Alignment.Center) {
                if (showAudioHint) {
                    SwiftieAudioHint(advice = audioAdvice)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            SwiftieKeypad(
                canSubmit = quiz.canSubmit,
                enabled = !quiz.solved,
                hazeState = hazeState,
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
                modifier = Modifier.width(keypadWidth)
            )
        }
    }
}



