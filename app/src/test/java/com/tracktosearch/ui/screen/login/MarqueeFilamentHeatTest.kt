package com.tracktosearch.ui.screen.login

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 跑马灯灯泡的明暗模型护栏。
 *
 * 这几条盯的是「像白炽灯还是像 LED」这一个区别：灯丝快亮慢灭，亮度连续。
 * 只要有人把 [filamentHeat] 改回线性插值或对称余光，下面几条里至少一条会红 ——
 * 而在装机截图上，线性和指数衰减在一颗 10dp 的灯泡上肉眼分不出来，
 * 分得出的是尾巴末端断不断崖，那正是这里验的东西。
 */
class MarqueeFilamentHeatTest {

    /** 头灯刚到的那一瞬灯丝还是冷的，走完升温段才满亮 —— 有热惯性才不像方波。 */
    @Test
    fun 灯丝不是瞬间到最亮() {
        assertThat(filamentHeat(0f)).isEqualTo(0f)
        assertThat(filamentHeat(BULB_RISE_BULBS)).isWithin(TOLERANCE).of(1f)
    }

    /** 升温远快于降温。这个不对称是白炽灯观感的全部来源，不是可调的手感参数。 */
    @Test
    fun 升温比降温快得多() {
        val riseToHalf = firstDistanceWhere(from = 0f) { it >= 0.5f }
        val fallToHalf = firstDistanceWhere(from = BULB_RISE_BULBS) { it <= 0.5f } - BULB_RISE_BULBS
        assertWithMessage("升温到半亮 $riseToHalf 颗，降温到半亮 $fallToHalf 颗，不够不对称")
            .that(fallToHalf).isGreaterThan(riseToHalf * 4f)
    }

    /** 过了峰值只许一路变暗。中间回升就是尾巴上多出一次闪，比不衰减更难看。 */
    @Test
    fun 过峰值后单调变暗() {
        var previous = 1f
        var distance = BULB_RISE_BULBS
        while (distance <= TAIL_SCAN_BULBS) {
            val heat = filamentHeat(distance)
            assertWithMessage("在 $distance 颗处回升：$previous 到 $heat")
                .that(heat).isAtMost(previous + TOLERANCE)
            previous = heat
            distance += SCAN_STEP
        }
    }

    /**
     * 尾巴不许断崖。
     *
     * 逐点扫过整条尾巴，任何相邻两点的落差都要小 —— 这一条才是「暗下去是逐渐暗下去」
     * 的机器可读版本。线性衰减在归零那一点的落差同样是小的，但它归零之后
     * 后面全是 0；指数衰减压根不归零，所以下面还验了尾巴末端仍有余温。
     */
    @Test
    fun 尾巴逐渐冷掉不断崖() {
        var distance = 0f
        var previous = filamentHeat(0f)
        while (distance <= TAIL_SCAN_BULBS) {
            distance += SCAN_STEP
            val heat = filamentHeat(distance)
            assertWithMessage("在 $distance 颗处落差 ${previous - heat} 过大")
                .that(previous - heat).isLessThan(MAX_STEP_DROP)
            previous = heat
        }
    }

    /** 衰减到看不见，但不是数学上的零：指数曲线没有终点，尾巴末端不存在「最后一帧」。 */
    @Test
    fun 尾巴末端还剩一点余温() {
        assertThat(filamentHeat(TAIL_SCAN_BULBS)).isGreaterThan(0f)
        assertThat(filamentHeat(TAIL_SCAN_BULBS)).isLessThan(0.02f)
    }

    /** 头灯还没走到的灯泡传进来是负距离，必须全黑，不能被 exp 算成大于 1 的亮度。 */
    @Test
    fun 还没亮过的灯泡是全黑的() {
        assertThat(filamentHeat(-0.1f)).isEqualTo(0f)
        assertThat(filamentHeat(-8f)).isEqualTo(0f)
    }

    private fun firstDistanceWhere(from: Float, predicate: (Float) -> Boolean): Float {
        var distance = from
        while (distance <= TAIL_SCAN_BULBS) {
            if (predicate(filamentHeat(distance))) return distance
            distance += SCAN_STEP
        }
        throw AssertionError("扫到 $TAIL_SCAN_BULBS 颗都没满足条件")
    }

    private companion object {
        const val TOLERANCE = 1e-4f

        /** 扫描步长，单位「颗」。比升温段短一个量级，升温段里也能取到好几个采样点。 */
        const val SCAN_STEP = 0.02f

        /** 尾巴扫多长。[BULB_TAIL_BULBS] 是实际空转距离，这里多扫一点留余量。 */
        const val TAIL_SCAN_BULBS = 7f

        /**
         * 相邻采样点允许的最大落差。指数段刚过峰值时最陡，
         * 斜率 1/[BULB_DECAY_BULBS] 乘 [SCAN_STEP] 约 0.0125，留四倍余量。
         */
        const val MAX_STEP_DROP = 0.05f
    }
}
