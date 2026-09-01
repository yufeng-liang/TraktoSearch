package com.tracktosearch.ui.screen.swiftie

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import kotlin.math.cos
import kotlin.math.sin

private const val EQUATION_TAIL = " + 87 = 100"
private const val EQUATION_EQUALS = " = "
private const val UNKNOWN = "X"
private const val QUESTION = "?"
private const val TITLE_1 = "Congrats"
private const val TITLE_2 = "on"
private const val TITLE_3 = "Forever!"

/**
 * 连错 3 次后浮出的小字。
 *
 * 和 [TITLE_1]…[TITLE_3] 一样**刻意不本地化** —— 灯箱是一张英文实景的复刻，这几行字
 * 是画面的一部分，不是界面文案。而且它用 `SwiftieFonts.Script`（Pacifico 按这几个
 * 英文字子集化，只剩 70 个字形），换成中日韩会整行豆腐块。
 *
 * 视障用户拿不到这条视觉提示，所以它另有一份本地化的无障碍文案
 * `R.string.swiftie_quiz_a11y_hint`，由灯箱的 `contentDescription` 带出去。
 */
private const val LUCKY_HINT = "Her lucky number."

/** `?` 位预留宽度用的隐形锚：输入最多两位，按最宽的两位数字占位。 */
private const val SLOT_WIDEST = "00"

/**
 * 1:1 灯箱复刻。算式在上、三行标题在下，配色**不随深色模式变化**（它是一张图，Spec §2.3）。
 *
 * 水彩视差的时钟由这里驱动再喂给 [SwiftieWatercolorSky]，Phase C 的扩散段换成自己的时钟
 * 就能复用同一张背景。
 */
@Composable
fun SwiftieBillboard(
    state: SwiftieQuizState,
    modifier: Modifier = Modifier
) {
    val emptyLabel = stringResource(R.string.swiftie_quiz_a11y_empty)
    val question = stringResource(R.string.swiftie_quiz_a11y, state.input.ifEmpty { emptyLabel })
    // 连错 3 次浮出的 Her lucky number. 是纯视觉的，这里补一份本地化文案带给 TalkBack
    val hint = stringResource(R.string.swiftie_quiz_a11y_hint)
    val a11y = if (state.showLuckyHint) "$question $hint" else question

    // 答错摇晃：wrongCount 每 +1 摇一遍，300ms 三个来回后回零
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state.wrongCount) {
        if (state.wrongCount == 0) return@LaunchedEffect
        shake.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 300
                0f at 0
                -1f at 50
                1f at 110
                -0.7f at 170
                0.5f at 230
                0f at 300
            }
        )
    }

    // 答对后下行整行淡出。用 alpha 而不是 AnimatedVisibility：
    // 后者移除节点会让上行往下塌，正好撞在准备扩散的那一帧
    val bottomAlpha = remember { Animatable(1f) }
    LaunchedEffect(state.solved) {
        if (state.solved) bottomAlpha.animateTo(0f, tween(durationMillis = 400))
    }

    val skyTransition = rememberInfiniteTransition(label = "swiftieSky")
    // 18s 一圈：视差存在感要低于闪粉
    val skyPhase = skyTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "skyPhase"
    )

    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer { translationX = shake.value * 10.dp.toPx() }
            .clip(RoundedCornerShape(20.dp))
            .semantics(mergeDescendants = true) { contentDescription = a11y }
    ) {
        // 字号按灯箱宽度算，刻意不跟系统 fontScale ——
        // 灯箱是 aspectRatio(1f) 的定宽方框，字号跟着 fontScale 长就会顶出去被裁掉。
        // 裸 .sp 达不到这个目的（.sp 就是跟 fontScale 走的那个单位），必须 Dp.toSp() 把它除掉
        val equationSize = with(LocalDensity.current) { (maxWidth * 0.112f).toSp() }
        val titleSize = with(LocalDensity.current) { (maxWidth * 0.170f).toSp() }
        val hintSize = with(LocalDensity.current) { (maxWidth * 0.042f).toSp() }

        SwiftieWatercolorSky(
            modifier = Modifier.matchParentSize(),
            progress = { skyPhase.value }
        )
        SwiftieGlitterHearts(modifier = Modifier.matchParentSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.55f))
            EquationTopRow(state = state, fontSize = equationSize)
            Spacer(modifier = Modifier.height(2.dp))
            EquationBottomRow(
                state = state,
                fontSize = equationSize,
                modifier = Modifier.graphicsLayer { alpha = bottomAlpha.value }
            )
            Spacer(modifier = Modifier.weight(1.0f))
            BillboardTitle(fontSize = titleSize)
            Spacer(modifier = Modifier.weight(0.45f))
            LuckyHint(visible = state.showLuckyHint, fontSize = hintSize)
        }
    }
}

/** 上行：`X + 87 = 100`。答对后 `X` 换成闪粉 `13`，定格成与参考图一致的单行。 */
@Composable
private fun EquationTopRow(
    state: SwiftieQuizState,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Crossfade(
            targetState = state.solved,
            animationSpec = tween(durationMillis = 400),
            label = "unknownToThirteen"
        ) { solved ->
            if (solved) {
                SwiftieGlitterText(text = SwiftieEggController.ANSWER.toString(), fontSize = fontSize)
            } else {
                SwiftieMarkerText(
                    text = UNKNOWN,
                    fontSize = fontSize,
                    color = SwiftiePalette.RoyalBlue
                )
            }
        }
        SwiftieGlitterText(text = EQUATION_TAIL, fontSize = fontSize)
    }
}

/** 下行：`X = ?`。`?` 位实时填入输入。 */
@Composable
private fun EquationBottomRow(
    state: SwiftieQuizState,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        SwiftieMarkerText(text = UNKNOWN, fontSize = fontSize, color = SwiftiePalette.RoyalBlue)
        SwiftieGlitterText(text = EQUATION_EQUALS, fontSize = fontSize)
        AnswerSlot(state = state, fontSize = fontSize)
    }
}

/**
 * `?` 位。空着显示 `?`；落字时弹一下并爆一次亮点；答错时整组变洋红描边（Spec §4.3）。
 *
 * 弹跳与亮点只在**输入变长**时放。原先按 `state.input` / `input.length` 触发，
 * 删除键也会算成落字 —— 退一位反而弹一下、爆一圈亮点，读起来像又填进去一个数字。
 */
@Composable
private fun AnswerSlot(
    state: SwiftieQuizState,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val wrong = state.phase == SwiftieQuizPhase.WRONG
    val strokeWidth = with(LocalDensity.current) { 2.dp.toPx() }
    val widestLayout = rememberMarkerLayout(SLOT_WIDEST, fontSize)
    val slotWidth = with(LocalDensity.current) { widestLayout.size.width.toDp() }

    // 只数「落进来的字」：变长才 +1，退格与清空不动它。
    // 计数写在 LaunchedEffect 里而不是组合体里，避免组合期写 state 触发多余的重组
    var typedCount by remember { mutableIntStateOf(0) }
    var previousLength by remember { mutableIntStateOf(0) }

    // 落字弹一下：新字符出现时从 1.25 回落到 1.0
    val pop = remember { Animatable(1f) }
    LaunchedEffect(state.input) {
        val grew = state.input.length > previousLength
        previousLength = state.input.length
        if (!grew) {
            if (state.input.isEmpty()) pop.snapTo(1f)
            return@LaunchedEffect
        }
        typedCount++
        pop.snapTo(1.25f)
        pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 900f))
    }

    Box(
        // 预留两位数字的宽度。少了它，`?` → `1` → `13` 每一步宽度都变，
        // 整行 `X = ?` 会跟着重新居中、左右跳
        modifier = modifier.widthIn(min = slotWidth),
        contentAlignment = Alignment.Center
    ) {
        when {
            state.input.isEmpty() ->
                SwiftieGlitterText(text = QUESTION, fontSize = fontSize)

            wrong -> SwiftieMarkerText(
                text = state.input,
                fontSize = fontSize,
                color = SwiftiePalette.Glitter,
                drawStyle = Stroke(width = strokeWidth)
            )

            else -> SwiftieGlitterText(
                text = state.input,
                fontSize = fontSize,
                modifier = Modifier.graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }
            )
        }
        SparkBurst(trigger = typedCount, enabled = !wrong)
    }
}

/** 落字爆开的一圈白亮点。[trigger] 变化就放一次，450ms 内扩散并淡完。 */
@Composable
private fun BoxScope.SparkBurst(trigger: Int, enabled: Boolean) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger, enabled) {
        if (trigger <= 0 || !enabled) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 450, easing = LinearEasing))
    }
    val angles = remember { List(10) { it * 36f } }
    Spacer(
        modifier = Modifier
            .matchParentSize()
            .drawWithCache {
                onDrawBehind {
                    val p = progress.value
                    if (p >= 1f) return@onDrawBehind
                    val ring = size.minDimension * (0.25f + p * 0.75f)
                    angles.forEach { degrees ->
                        val radians = degrees * (Math.PI.toFloat() / 180f)
                        drawCircle(
                            color = Color.White,
                            radius = size.minDimension * 0.05f * (1f - p),
                            center = center + Offset(cos(radians) * ring, sin(radians) * ring),
                            alpha = 1f - p
                        )
                    }
                }
            }
    )
}

/**
 * 下方三行宝蓝花体。Pacifico 自带右倾，**不要**再叠 `rotationZ`（Spec §1 描述的是字体斜度）。
 */
@Composable
private fun BillboardTitle(fontSize: TextUnit, modifier: Modifier = Modifier) {
    // 0.86em：Pacifico 升降部很大，默认行距会把三行拉散
    val style = TextStyle(
        fontFamily = SwiftieFonts.Script,
        fontSize = fontSize,
        lineHeight = fontSize * 0.86f,
        color = SwiftiePalette.RoyalBlue,
        textAlign = TextAlign.Center
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = TITLE_1, style = style, modifier = Modifier.fillMaxWidth())
        Text(text = TITLE_2, style = style, modifier = Modifier.fillMaxWidth())
        Text(text = TITLE_3, style = style, modifier = Modifier.fillMaxWidth())
    }
}

/** 连错 3 次后浮出的小字。空间常驻、只动 alpha，出现时不挤动标题。 */
@Composable
private fun LuckyHint(visible: Boolean, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val hintAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "luckyHintAlpha"
    )
    Text(
        text = LUCKY_HINT,
        style = TextStyle(
            fontFamily = SwiftieFonts.Script,
            fontSize = fontSize,
            color = SwiftiePalette.RoyalBlue,
            textAlign = TextAlign.Center
        ),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = hintAlpha }
    )
}



