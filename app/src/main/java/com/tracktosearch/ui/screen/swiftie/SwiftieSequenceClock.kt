package com.tracktosearch.ui.screen.swiftie

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis

/**
 * 序列的十个段落。所有渲染分支都从这里派生，段落自己不计时。
 *
 * **声明顺序 = 时间顺序**，`compareTo` 的段落门控靠它。倒滑与绽放排在终局之后
 * （见 [SwiftieTimeline] 的类注释：配乐末尾唱 Lover）。
 *
 * **没有 BRACELET**：手链与签名同场 —— 手链在签名写到一半时从两侧滚进来
 * （见 [SwiftieTimeline.BRACELET_ENTRY_MS]），不是独立的一段，账本上也没有它的边界。
 */
enum class SwiftieSequencePhase {
    SOLVE, DIFFUSION, ERAS_INTRO, ERAS_CARDS, SIGNATURE, FINAL_HOLD,
    REWIND, LOVER_BLOOM, FADE_OUT, DONE
}

fun swiftiePhaseAt(elapsedMs: Long): SwiftieSequencePhase = when {
    elapsedMs < SwiftieTimeline.DIFFUSION_START -> SwiftieSequencePhase.SOLVE
    elapsedMs < SwiftieTimeline.ERAS_INTRO_START -> SwiftieSequencePhase.DIFFUSION
    elapsedMs < SwiftieTimeline.ERAS_CARDS_START -> SwiftieSequencePhase.ERAS_INTRO
    elapsedMs < SwiftieTimeline.SIGNATURE_START -> SwiftieSequencePhase.ERAS_CARDS
    elapsedMs < SwiftieTimeline.FINAL_HOLD_START -> SwiftieSequencePhase.SIGNATURE
    elapsedMs < SwiftieTimeline.REWIND_START -> SwiftieSequencePhase.FINAL_HOLD
    elapsedMs < SwiftieTimeline.LOVER_BLOOM_START -> SwiftieSequencePhase.REWIND
    elapsedMs < SwiftieTimeline.FADE_OUT_START -> SwiftieSequencePhase.LOVER_BLOOM
    elapsedMs < SwiftieTimeline.TOTAL_MS -> SwiftieSequencePhase.FADE_OUT
    else -> SwiftieSequencePhase.DONE
}

/**
 * 整条序列唯一的时间来源。UI 只读 [elapsedMs]，不自己数帧。
 *
 * 不用 `MediaPlayer.currentPosition` 当时钟：那个值在低端机上跳变，
 * 会让播放头一顿一顿（Spec §5 约束 4）。
 */
@Stable
class SwiftieSequenceClock {

    var elapsedMs: Long by mutableLongStateOf(0L)
        private set

    /** 按住屏幕暂停。配乐要一起暂停，由调用方联动。 */
    var paused: Boolean by mutableStateOf(false)

    /** 用户碰过播放头之后取消自动续播、转全手动（Spec §6.1）。 */
    var userSeeked: Boolean by mutableStateOf(false)
        private set

    /**
     * 每次 [seekTo] 递增，[advance] 不动它。
     *
     * 配乐靠这个值判断「时钟被拨过了，播放头要跟着挪」。少了它，
     * 「跳过」或拖播放头之后配乐还留在原处，[SwiftieTimeline] 钉死的
     * 那两个点（1:58 起倒滑、2:03 收绽放）就全部失效。
     */
    var seekEpoch: Int by mutableIntStateOf(0)
        private set

    val finished: Boolean get() = elapsedMs >= SwiftieTimeline.TOTAL_MS

    fun advance(deltaMs: Long) {
        if (paused) return
        val step = deltaMs.coerceIn(0L, MAX_FRAME_DELTA_MS)
        elapsedMs = (elapsedMs + step).coerceAtMost(SwiftieTimeline.TOTAL_MS)
    }

    fun seekTo(ms: Long) {
        elapsedMs = ms.coerceIn(0L, SwiftieTimeline.TOTAL_MS)
        seekEpoch++
    }

    /** 拖动播放头吸附到第 [index] 张卡片的起点。 */
    fun seekToEra(index: Int) {
        userSeeked = true
        seekTo(SwiftieTimeline.eraStartMs(index.coerceIn(0, SwiftieTimeline.ERA_TRACK_COUNTS.lastIndex)))
    }

    /** 「跳过」跳到定格合影，而不是直接关页面 —— 签名与手链仍然看得到。 */
    fun skipToFinalHold() {
        seekTo(SwiftieTimeline.FINAL_HOLD_START)
    }

    companion object {
        /** 单帧最大推进量。一次掉帧不该把时间轴瞬移出去。 */
        const val MAX_FRAME_DELTA_MS: Long = 100L
    }
}

/**
 * 把 [SwiftieSequenceClock] 挂到帧时钟上。
 *
 * @param running false 时不推进（用于「减少动效」直接给终态，或页面还没进入序列）
 */
@Composable
fun rememberSwiftieSequenceClock(running: Boolean): SwiftieSequenceClock {
    val clock = remember { SwiftieSequenceClock() }
    LaunchedEffect(running, clock) {
        if (!running) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (!clock.finished) {
            withFrameMillis { now ->
                clock.advance(now - last)
                last = now
            }
        }
    }
    return clock
}
