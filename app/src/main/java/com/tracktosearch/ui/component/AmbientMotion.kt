package com.tracktosearch.ui.component

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 背景动效的「有人在操作」状态。
 *
 * 背景是 mirage 的 shader，时间由 rememberShaderTime 每帧 withFrameNanos 累加进一个
 * MutableFloatState，着色器的 uniform 又在绘制阶段读它，于是只要 speed != 0 就每帧让绘制失效、
 * 整个窗口满帧重绘：实测静止在四个 tab 上分别是 120/76/67/103 fps，把背景动效关掉则 10s 内 0 帧。
 *
 * preferredFrameRate(30f) 压不住：它只是对显示刷新率的投票，会被其它没设偏好的图层（玻璃消费者
 * 因光晕层重录而重绘）盖掉，实测帧间隔始终 8.33ms（120Hz）。所以改为在源头停帧——
 * speed = 0 时 rememberShaderTime 直接退出帧循环，时间停在当前累加值（不跳变），开销归零。
 */
@Stable
class AmbientMotionState internal constructor(
    private val scope: CoroutineScope,
    private val idleDelayMillis: Long
) {
    /** 是否让背景继续流动。 */
    var active: Boolean by mutableStateOf(true)
        private set

    private var lastPingUptime = SystemClock.uptimeMillis()
    private var watchdog: Job? = null

    /**
     * 指针事件回调。每次 MOVE 都会调到，所以这里只更新普通字段，
     * 仅在状态真的翻转时写 snapshot state，避免拖动过程中反复触发重组。
     */
    fun ping() {
        lastPingUptime = SystemClock.uptimeMillis()
        if (!active) active = true
        if (watchdog?.isActive != true) startWatchdog()
    }

    private fun startWatchdog() {
        watchdog = scope.launch {
            while (true) {
                val idle = SystemClock.uptimeMillis() - lastPingUptime
                if (idle >= idleDelayMillis) {
                    active = false
                    break
                }
                delay(idleDelayMillis - idle)
            }
        }
    }

    /**
     * 立即停帧，直到下一次 [ping]。用于详情进入/返回转场结束等“刚完成大量工作、暂时无人操作”
     * 的时刻：背景 shader 只要还在跑就是整窗满帧重绘，立刻停掉可避免转场后页面组合/加载窗口
     * 继续白烧 GPU；下一次触摸 [ping] 会恢复流动并重启空闲看门狗。
     */
    fun pause() {
        watchdog?.cancel()
        watchdog = null
        lastPingUptime = 0L
        if (active) active = false
    }
}

/**
 * @param idleDelayMillis 最后一个指针事件之后再等多久停帧。默认 3s：一次滑动的惯性滚动通常在
 * 1~2s 内结束，等 3s 可以保证不会在惯性还没走完时把背景冻住。
 */
@Composable
fun rememberAmbientMotionState(idleDelayMillis: Long = 3000L): AmbientMotionState {
    val scope = rememberCoroutineScope()
    val state = remember(scope, idleDelayMillis) { AmbientMotionState(scope, idleDelayMillis) }
    // 启动即开始计时：没人碰也会在 idleDelay 后停下
    LaunchedEffect(state) { state.ping() }
    return state
}

/**
 * 「有人在操作」信号的下发通道，给需要跟着停的组件用（背景光晕、[GlassLuminanceProbe] 的读回）。
 *
 * 默认恒为 true：没有提供者的页面（详情页等）保持各自的退避策略，不受影响。
 * 提供时务必传一个 remember 住的 lambda——这是 static local，每次组合换新实例会让整棵子树失效。
 */
val LocalAmbientMotionActive: ProvidableCompositionLocal<() -> Boolean> =
    staticCompositionLocalOf { { true } }

/** 挂在页面根节点：用 Initial pass 抢在子节点之前看到所有指针事件，且全程不消费。 */
fun Modifier.ambientMotionPing(state: AmbientMotionState): Modifier =
    pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                state.ping()
            }
        }
    }
