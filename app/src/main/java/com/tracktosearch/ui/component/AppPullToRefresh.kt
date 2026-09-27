package com.tracktosearch.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.haptic.rememberAppHaptics

/**
 * 下拉刷新状态。实现原样取自「我的」页那套调好的手感，抽出来供其他页面复用：
 *
 * - 唯一真源是一个 float 状态 [offset]，拖动阶段由 NestedScrollConnection 直接写入，
 *   指示器与内容都只在绘制阶段读取它，下拉全程零重组
 * - 越过阈值的瞬间给一次触感，而不是每帧都给
 * - 松手后停在 hold 位等数据，数据落地由调用方调 [finishRefresh] 收起，
 *   避免刷新态一闪而过
 */
@Stable
class AppPullToRefreshState internal constructor(
    val thresholdPx: Float,
    private val holdPx: Float,
    private val maxPx: Float,
    private val onThresholdArmed: () -> Unit,
    private val onRefresh: () -> Unit
) {
    /** 当前下拉偏移（像素）。只在绘制阶段读，避免触发重组。 */
    val offset: MutableFloatState = mutableFloatStateOf(0f)

    var isRefreshing: Boolean by mutableStateOf(false)
        private set

    /** 已越过阈值：用于只在跨越瞬间给一次触感。 */
    private var armed = false

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // 反向滑动优先把下拉偏移收回去，再交给列表滚动
            val current = offset.floatValue
            if (current > 0f && available.y < 0f) {
                val consumed = minOf(-available.y, current)
                offset.floatValue = current - consumed
                return Offset(0f, -consumed)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // 列表滚到顶部后，继续下拉产生弹性偏移（带阻尼）
            if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            if (isRefreshing) return Offset.Zero
            val current = offset.floatValue
            val damped = available.y * 0.5f / (1f + current / thresholdPx)
            val next = (current + damped).coerceAtMost(maxPx)
            offset.floatValue = next
            if (next >= thresholdPx && !armed) {
                armed = true
                onThresholdArmed()
            } else if (next < thresholdPx && armed) {
                armed = false
            }
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            armed = false
            val current = offset.floatValue
            if (current <= 0f) return Velocity.Zero
            if (current >= thresholdPx && !isRefreshing) {
                isRefreshing = true
                onRefresh()
                // 停在 hold 位等数据；收起由调用方在数据落地后调 finishRefresh
                animate(current, holdPx, animationSpec = spring(0.9f, 900f)) { value, _ ->
                    offset.floatValue = value
                }
            } else {
                animate(current, 0f, animationSpec = spring(0.62f, 380f)) { value, _ ->
                    offset.floatValue = value
                }
            }
            // 松手时把向下的甩动速度吃掉：否则剩余速度会继续传给列表，在已到顶时触发系统默认
            // overscroll 拉伸（下拉未达阈值弹回后页面又被拉伸一下）。向上的速度照常放行给列表滚动。
            return if (available.y > 0f) Velocity(0f, available.y) else Velocity.Zero
        }
    }

    /** 数据落地后收起指示器。未在刷新中时是空操作。 */
    suspend fun finishRefresh() {
        if (!isRefreshing) return
        animate(offset.floatValue, 0f, animationSpec = spring(0.7f, 420f)) { value, _ ->
            offset.floatValue = value
        }
        isRefreshing = false
    }
}

/**
 * @param thresholdDp 触发刷新的下拉距离
 * @param holdDp 刷新中指示器停留的位置
 * @param maxDp 下拉偏移上限，避免一直下拉把内容拖到屏幕中部
 */
@Composable
fun rememberAppPullToRefreshState(
    thresholdDp: Dp = 80.dp,
    holdDp: Dp = 56.dp,
    maxDp: Dp = 132.dp,
    onRefresh: () -> Unit
): AppPullToRefreshState {
    val density = LocalDensity.current
    val haptics = rememberAppHaptics()
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    return remember(density, thresholdDp, holdDp, maxDp) {
        AppPullToRefreshState(
            thresholdPx = with(density) { thresholdDp.toPx() },
            holdPx = with(density) { holdDp.toPx() },
            maxPx = with(density) { maxDp.toPx() },
            onThresholdArmed = { haptics.thresholdArmed() },
            onRefresh = { currentOnRefresh() }
        )
    }
}

/**
 * 下拉刷新指示器，锚在列表内容顶部。位移/透明度/进度只在绘制阶段读取，下拉过程不产生重组。
 *
 * @param contentTop 指示器锚点距容器顶部的距离（一般是吸顶栏高度）
 */
@Composable
fun AppPullToRefreshIndicator(
    state: AppPullToRefreshState,
    contentTop: Dp,
    modifier: Modifier = Modifier
) {
    val spinning = state.isRefreshing
    val spin = if (spinning) {
        rememberInfiniteTransition(label = "pull_refresh_spin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
            label = "pull_refresh_spin_angle"
        )
    } else {
        null
    }
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val activeColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    val thresholdPx = state.thresholdPx
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = contentTop),
        contentAlignment = Alignment.TopCenter
    ) {
        Canvas(
            modifier = Modifier
                .size(28.dp)
                // 参数名避开 GraphicsLayerScope.translationY（同名会遮蔽作用域属性导致无法赋值）
                .graphicsLayer {
                    val offsetY = state.offset.floatValue
                    val progress = (offsetY / thresholdPx).coerceIn(0f, 1f)
                    translationY = offsetY * 0.5f - size.height * 0.5f
                    // 起手一小段保持不可见，避免在半透明顶栏后面透出来
                    alpha = (offsetY / (thresholdPx * 0.55f)).coerceIn(0f, 1f)
                    val scale = 0.72f + 0.28f * progress
                    scaleX = scale
                    scaleY = scale
                    rotationZ = spin?.value ?: (progress * 300f)
                }
        ) {
            val progress = (state.offset.floatValue / thresholdPx).coerceIn(0f, 1f)
            val stroke = 2.6.dp.toPx()
            val arcTopLeft = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val style = Stroke(width = stroke, cap = StrokeCap.Round)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = style
            )
            drawArc(
                color = lerp(idleColor, activeColor, progress),
                startAngle = -90f,
                sweepAngle = if (spin != null) 100f else 320f * progress,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = style
            )
        }
    }
}
