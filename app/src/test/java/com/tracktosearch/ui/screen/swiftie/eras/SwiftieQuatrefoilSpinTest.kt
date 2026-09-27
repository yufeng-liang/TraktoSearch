package com.tracktosearch.ui.screen.swiftie.eras

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import org.junit.Test

/**
 * Speak Now 拱心四叶饰的自转角度。
 *
 * 这一笔只在 draw 阶段用：写错了不会编译失败，只会「转得太快」或者「段起点跳一下」。
 * 所以把三件事钉死 —— 待机与段首都是 0°、随时间单调增（顺时针）、速率是 90°/20s。
 */
class SwiftieQuatrefoilSpinTest {

    @Test
    fun 待机与段首都停在零度() {
        // 换张那 500ms 里 `SwiftieEraBackdropLayer` 给 incoming 的是 -1L，本段自己的
        // 第一帧是 0 —— 两处都必须落到 0°，接缝上才不会跳
        assertThat(quatrefoilSpinDeg(-1L)).isEqualTo(0f)
        assertThat(quatrefoilSpinDeg(0L)).isEqualTo(0f)
    }

    @Test
    fun 顺时针且匀速() {
        // 20s 走 90°，一整圈 80s
        assertThat(quatrefoilSpinDeg(20_000L)).isWithin(0.001f).of(90f)
        assertThat(quatrefoilSpinDeg(80_000L)).isWithin(0.001f).of(360f)
        // 角度只增不减 = 顺时针（rotate 的正角在 y 轴朝下的坐标系里就是顺时针）
        var previous = quatrefoilSpinDeg(0L)
        for (ms in 100L..10_000L step 100L) {
            val angle = quatrefoilSpinDeg(ms)
            assertThat(angle).isGreaterThan(previous)
            previous = angle
        }
    }

    @Test
    fun 整段之内只转过小半个瓣() {
        // 「缓慢」的判据：Speak Now 一整段转过 20°–45° —— 看得见在动，又不抢那扇尖拱窗
        val index = SwiftieErasData.ALL.indexOfFirst { it.name == "Speak Now" }
        val segmentMs = SwiftieTimeline.cardDurationMs(index, SwiftieTimeline.ERA_TRACK_COUNTS[index])
        val angle = quatrefoilSpinDeg(segmentMs)
        assertThat(angle).isGreaterThan(20f)
        assertThat(angle).isLessThan(45f)
    }
}
