package com.tracktosearch.ui.screen.swiftie.eras

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

/**
 * Midnights 面钟上弦的角度。
 *
 * 这一组守的是三件容易改坏的事：**传动比**（分针走多少、时针就得按 1:12 跟多少）、
 * **两个端点**（起手 2:00、落位严格 3:00），以及**落位那一下回吸只往回走一下**
 * （不把分针带着倒转、也不偏离到能看出错位）。
 */
class SwiftieMidnightClockTest {

    /** 一个小时的格数差 —— 也就是齿轮比。写死 12 是有意的：改实现里的比值这里要一起疼。 */
    private val gearRatio = 12f

    /** 主程结束的时刻（起转 + 走针）。 */
    private val windEnd = MIDNIGHT_WIND_START_MS + MIDNIGHT_WIND_MS.toLong()

    /** 回吸走完之后。 */
    private val settled = windEnd + MIDNIGHT_SETTLE_MS.toLong()

    /** 起手时刻。 */
    private val windStart = MIDNIGHT_WIND_START_MS

    /** 落位的分针角度：两针各差 30°，分针因此正好一整圈。 */
    private val minuteEnd = 30f * gearRatio

    @Test
    fun 起手是整点二点() {
        // 负数是换张淡变期：incoming 那张舞台以 0.5 的 alpha 已经露着，
        // 指针得有个真姿态，不能凭空长出来
        for (eraMs in longArrayOf(-1L, 0L, windStart)) {
            val (hour, minute) = midnightHandAngles(eraMs)

            assertThat(minute).isWithin(1e-4f).of(0f)
            assertThat(midnightClockAngleOf(hour)).isWithin(1e-4f).of(60f)
        }
    }

    @Test
    fun 落位是整点三点() {
        val (hour, minute) = midnightHandAngles(settled)

        assertThat(midnightClockAngleOf(hour)).isWithin(0.001f).of(90f)
        assertThat(minute).isWithin(0.001f).of(minuteEnd)
    }

    @Test
    fun 落位之后不再动() {
        // 卡片在这之后还要静止近三秒（主程放慢到 4500ms 后余量变薄），这期间两根针一丝都不许漂
        val late = midnightHandAngles(settled + 4_000L)

        assertThat(late.first).isEqualTo(midnightHandAngles(settled).first)
        assertThat(late.second).isEqualTo(midnightHandAngles(settled).second)
        assertThat(midnightHandAngles(100_000L)).isEqualTo(late)
    }

    @Test
    fun 分针正好走一圈() {
        // 起手到落位是整整一个钟头，刚性传动下分针不可能多转或少转
        val (hourStart, minuteStart) = midnightHandAngles(0L)
        val (hourEnd, minuteEndActual) = midnightHandAngles(settled)

        assertThat(minuteEndActual - minuteStart).isWithin(0.001f).of(360f)
        assertThat(hourEnd - hourStart).isWithin(0.001f).of(30f)
        assertThat((hourEnd - hourStart) * gearRatio).isWithin(0.001f).of(minuteEnd)
    }

    @Test
    fun 走针期间时针严格按十二分之一跟() {
        // 在 0.1 / 0.25 / 0.5 / 0.9 各采一帧。**分针的期望值从缓动曲线自己算回来** ——
        // 照抄实现里的中间量等于把实现抄进测试，curve 换一条都测不出来
        for (t in floatArrayOf(0.1f, 0.25f, 0.5f, 0.9f)) {
            val eraMs = windStart + (MIDNIGHT_WIND_MS * t).toLong()
            val (hour, minute) = midnightHandAngles(eraMs)
            // 缓动是 `1 - cos(π/2 · t)`：t=0 处为 0、t=1 处为 1，整体乘在行程上
            val expectedMinute = minuteEnd * (1f - cos(PI.toFloat() / 2f * t))

            assertThat(minute).isWithin(0.01f).of(expectedMinute)
            assertThat(minute / gearRatio).isWithin(0.01f).of(hour - 60f)
        }
    }

    @Test
    fun 回吸是往回一顿再回位() {
        val (hourEnd, minuteAtEnd) = midnightHandAngles(settled)
        val (hourMid, minuteMid) = midnightHandAngles(windEnd + (MIDNIGHT_SETTLE_MS / 2f).toLong())

        // sin 包络，所以回吸窗口的首尾都严格为 0
        assertThat(midnightHandAngles(windEnd).second).isWithin(0.001f).of(minuteEnd)
        assertThat(minuteAtEnd).isWithin(0.001f).of(minuteEnd)
        // 中段**比终点小** = 倒着退了一截（不是继续往前走）
        val back = minuteEnd - minuteMid
        assertThat(back).isGreaterThan(0f)
        // 峰值就落在窗口中点：sin(π/2) = 1
        assertThat(back).isWithin(0.001f).of(MIDNIGHT_SETTLE_DEG)
        assertThat(hourEnd - hourMid).isWithin(0.001f).of(MIDNIGHT_SETTLE_DEG)
        // 0.6 格：量得出来，但不足以让分针明显离开 12 点那一格
        assertThat(MIDNIGHT_SETTLE_DEG).isLessThan(360f / 60f)
    }

    @Test
    fun 角度换算绕回一圈仍读同一个整点() {
        // 分针转过两整圈，表盘上还是落在 12
        assertThat(midnightClockAngleOf(minuteEnd)).isWithin(0.001f).of(0f)
        assertThat(midnightClockAngleOf(90f + 720f)).isWithin(0.001f).of(90f)
        // 负数角度也要绕到正的一圈里
        assertThat(midnightClockAngleOf(-90f)).isWithin(0.001f).of(270f)
    }

    @Test
    fun 走针方向与整点刻度同向() {
        // 角度基准是「0° 在 12 点、顺时针为正」。表盘整点刻度的屏幕方向是
        // (cos(角度 - 90°), sin(角度 - 90°))（见 drawMidnightClock 里那一圈刻度），
        // 指针终点用的是同一条式子 —— 这里把这层对应关系钉住：
        // 角度每 +90°，指针就顺时针挪过四分之一圈
        val directions = listOf(0f, 90f, 180f, 270f).map { angleDeg ->
            val a = (angleDeg - 90f) / 180f * PI.toFloat()
            cos(a) to sin(a)
        }

        assertThat(directions[0].second).isWithin(1e-5f).of(-1f)  // 12 点：正上
        assertThat(directions[1].first).isWithin(1e-5f).of(1f)    // 3 点：正右
        assertThat(directions[2].second).isWithin(1e-5f).of(1f)   // 6 点：正下
        assertThat(directions[3].first).isWithin(1e-5f).of(-1f)   // 9 点：正左
    }
}
