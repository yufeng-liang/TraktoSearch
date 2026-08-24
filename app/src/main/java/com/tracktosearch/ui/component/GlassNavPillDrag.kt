package com.tracktosearch.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** 位置动画的可见性阈值：单位是"tab 序号"，所以要比默认值小得多。 */
private const val PillVisibilityThreshold = 0.001f

/** 官方 catalog 的水滴按压尺寸比：56dp 的药丸按住时涨到 78dp。 */
const val NavPillPressedScale = 78f / 56f

/**
 * 底栏选中水滴的「按住拖动」状态，对齐官方 catalog LiquidBottomTabs 的 DampedDragAnimation：
 * - 按下即进入按压态，没有滑动阈值，水滴立刻跟手
 * - 位置用临界阻尼弹簧追手指，松手吸附到最近的 tab 并回报选中项
 * - 拖动同时给整块面板一个上限受控的橡皮筋位移
 * - 按压缩放与速度挤压交给 drawBackdrop 的 layerBlock，采样到的背景不会跟着拉伸
 */
@Stable
class NavPillDragState internal constructor(
    private val scope: CoroutineScope,
    private val tabCount: Int,
    initialIndex: Int,
    private val pressedScale: Float,
    private val tabWidthPx: () -> Float,
    private val panelWidthPx: () -> Float,
    private val maxPanelOffsetPx: () -> Float,
    private val onIndexSettled: (Int) -> Unit
) {
    private val valueSpec = spring<Float>(1f, 1000f, PillVisibilityThreshold)
    private val velocitySpec = spring<Float>(0.5f, 300f, PillVisibilityThreshold * 10f)
    private val pressSpec = spring<Float>(1f, 1000f, 0.001f)
    private val scaleXSpec = spring<Float>(0.6f, 250f, 0.001f)
    private val scaleYSpec = spring<Float>(0.7f, 250f, 0.001f)
    private val panelSpec = spring<Float>(1f, 300f, 0.5f)

    private val valueAnimation = Animatable(initialIndex.toFloat(), PillVisibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(1f, 0.001f)
    private val scaleYAnimation = Animatable(1f, 0.001f)
    private val panelDragAnimation = Animatable(0f, 0.5f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    /** 按压同时喂给面板：面板整体放大沿用既有的 interactionSource 通路，不再另开参数。 */
    val interactionSource = MutableInteractionSource()
    private var pressInteraction: PressInteraction.Press? = null

    /** 水滴当前位置，单位是浮点 tab 序号。 */
    val value: Float get() = valueAnimation.value

    val pressProgress: Float get() = pressAnimation.value

    /** 面板橡皮筋位移（px）：拖动越界越多位移越接近上限。 */
    val panelOffsetPx: Float
        get() {
            val width = panelWidthPx()
            if (width <= 0f) return 0f
            val fraction = (panelDragAnimation.value / width).coerceIn(-1f, 1f)
            return maxPanelOffsetPx() * sign(fraction) * EaseOut.transform(abs(fraction))
        }

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { press() },
            onDragEnd = { settle() },
            onDragCancel = { settle() }
        ) { _, dragAmount ->
            val tabWidth = tabWidthPx()
            if (tabWidth > 0f) {
                updateValue(valueAnimation.targetValue + dragAmount.x / tabWidth)
            }
            scope.launch { panelDragAnimation.snapTo(panelDragAnimation.value + dragAmount.x) }
        }
    }

    /** 按压缩放叠速度挤压，供 drawBackdrop 的 layerBlock 调用。 */
    fun applyPillScale(layerScope: GraphicsLayerScope) {
        var scaleX = scaleXAnimation.value
        var scaleY = scaleYAnimation.value
        val velocity = velocityAnimation.value / 10f
        scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
        scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
        layerScope.scaleX = scaleX
        layerScope.scaleY = scaleY
    }

    /** 选中项由外部（点击 tab、Pager 同步）改变时，水滴弹到新位置并给一次按压回弹。 */
    internal fun syncTo(index: Int) {
        animateToValue(index.coerceIn(0, tabCount - 1).toFloat())
    }

    private fun settle() {
        val index = valueAnimation.targetValue.roundToInt().coerceIn(0, tabCount - 1)
        onIndexSettled(index)
        animateToValue(index.toFloat())
        scope.launch { panelDragAnimation.animateTo(0f, panelSpec) }
    }

    private fun press() {
        velocityTracker.resetTracking()
        emitPress()
        scope.launch {
            launch { pressAnimation.animateTo(1f, pressSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYSpec) }
        }
    }

    /** 先等位置基本落位再收按压态，避免水滴还在飞就先缩回去。 */
    private fun release() {
        scope.launch {
            withFrameNanos {}
            if (valueAnimation.value != valueAnimation.targetValue) {
                val threshold = (tabCount - 1).coerceAtLeast(1) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            emitRelease()
            launch { pressAnimation.animateTo(0f, pressSpec) }
            launch { scaleXAnimation.animateTo(1f, scaleXSpec) }
            launch { scaleYAnimation.animateTo(1f, scaleYSpec) }
        }
    }

    private fun updateValue(target: Float) {
        val coerced = target.coerceIn(0f, (tabCount - 1).toFloat())
        scope.launch {
            valueAnimation.animateTo(coerced, valueSpec) { updateVelocity() }
        }
    }

    private fun animateToValue(target: Float) {
        scope.launch {
            mutatorMutex.mutate {
                press()
                launch { valueAnimation.animateTo(target, valueSpec) }
                if (velocityAnimation.value != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocitySpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            System.currentTimeMillis(),
            Offset(valueAnimation.value, 0f)
        )
        val target = velocityTracker.calculateVelocity().x / (tabCount - 1).coerceAtLeast(1)
        scope.launch { velocityAnimation.animateTo(target, velocitySpec) }
    }

    private fun emitPress() {
        val previous = pressInteraction
        val interaction = PressInteraction.Press(Offset.Zero)
        pressInteraction = interaction
        scope.launch {
            // 先按下新的再释放旧的，避免按压栈空一帧导致面板缩放抖一下
            interactionSource.emit(interaction)
            if (previous != null) interactionSource.emit(PressInteraction.Release(previous))
        }
    }

    private fun emitRelease() {
        val interaction = pressInteraction ?: return
        pressInteraction = null
        scope.launch { interactionSource.emit(PressInteraction.Release(interaction)) }
    }
}

@Composable
fun rememberNavPillDragState(
    tabCount: Int,
    selectedIndex: Int,
    tabWidthPx: () -> Float,
    panelWidthPx: () -> Float,
    maxPanelOffsetPx: () -> Float,
    pressedScale: Float = NavPillPressedScale,
    onIndexSettled: (Int) -> Unit
): NavPillDragState {
    val scope = rememberCoroutineScope()
    val currentTabWidth by rememberUpdatedState(tabWidthPx)
    val currentPanelWidth by rememberUpdatedState(panelWidthPx)
    val currentMaxOffset by rememberUpdatedState(maxPanelOffsetPx)
    val currentSettled by rememberUpdatedState(onIndexSettled)
    val currentIndex by rememberUpdatedState(selectedIndex)
    val state = remember(scope, tabCount, pressedScale) {
        NavPillDragState(
            scope = scope,
            tabCount = tabCount,
            initialIndex = currentIndex,
            pressedScale = pressedScale,
            tabWidthPx = { currentTabWidth() },
            panelWidthPx = { currentPanelWidth() },
            maxPanelOffsetPx = { currentMaxOffset() },
            onIndexSettled = { currentSettled(it) }
        )
    }
    // drop(1)：跳过首次组合，避免启动瞬间凭空放一次按压回弹
    LaunchedEffect(state) {
        snapshotFlow { currentIndex }
            .drop(1)
            .collect { state.syncTo(it) }
    }
    return state
}

/**
 * 面板按压高光：对齐官方 catalog 的 InteractiveHighlight——按住时整块面板轻微提亮，
 * 并在水滴中心叠一层径向高光。官方用 RuntimeShader 做径向衰减（半径内一半处满强度、
 * 到半径归零），这里用等价的 radialGradient 实现，不依赖运行时 shader 支持。
 *
 * 画在玻璃之上、图标之下。挂在被录进 tab 图标层的 Row 上，水滴折射时能一并采到。
 */
fun Modifier.navPanelPressGlow(
    progress: () -> Float,
    centerX: () -> Float
): Modifier = drawBehind {
    val pressProgress = progress().coerceIn(0f, 1f)
    if (pressProgress <= 0f) return@drawBehind
    drawRect(Color.White.copy(alpha = 0.08f * pressProgress), blendMode = BlendMode.Plus)
    val radius = size.minDimension * 1.5f
    drawRect(
        brush = Brush.radialGradient(
            0.5f to Color.White.copy(alpha = 0.15f * pressProgress),
            1f to Color.Transparent,
            center = Offset(centerX().coerceIn(0f, size.width), size.height / 2f),
            radius = radius
        ),
        blendMode = BlendMode.Plus
    )
}

/**
 * 官方 catalog 的 inspectDragGestures：按下即回调、全程不消费事件、没有滑动阈值。
 * 用它而不是 detectDragGestures，才能做到"按住就跟手"。
 */
private suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val down = awaitFirstDown(requireUnconsumed = false)
        onDragStart(down)
        onDrag(initialDown, Offset.Zero)
        val upEvent = awaitDrag(initialDown.id) { onDrag(it, it.positionChange()) }
        if (upEvent == null) onDragCancel() else onDragEnd(upEvent)
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDrag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit
): PointerInputChange? {
    if (currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true) return null
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed } ?: return dragEvent
            pointer = otherDown.id
        } else if (dragEvent.previousPosition != dragEvent.position) {
            return dragEvent
        }
    }
}

