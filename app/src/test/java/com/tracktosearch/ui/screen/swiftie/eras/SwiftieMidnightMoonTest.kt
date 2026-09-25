package com.tracktosearch.ui.screen.swiftie.eras

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwiftieMidnightMoonTest {
    // 月牙的主程比走针短一档，账本只认 MIDNIGHT_MOON_WIND_MS
    private val windEnd = MIDNIGHT_WIND_START_MS + MIDNIGHT_MOON_WIND_MS.toLong()
    private val settled = windEnd + MIDNIGHT_SETTLE_MS.toLong()

    /**
     * 月牙主程内的**比例**时刻。
     *
     * 阶段边界都写成主程的分数（抱枕 0.15..0.64、抱起 0.48..1.0、闭眼 0.62..0.92），
     * 取点若钉死绝对毫秒，主程一变这些点就全落到上一阶段里 —— 测试会红得毫无信息量。
     */
    private fun at(frac: Float) = MIDNIGHT_WIND_START_MS + (MIDNIGHT_MOON_WIND_MS * frac).toLong()

    @Test
    fun 换张和起转前保持睁眼且没有枕头() {
        for (time in longArrayOf(-1L, 0L, MIDNIGHT_WIND_START_MS)) {
            assertThat(midnightMoonPose(time)).isEqualTo(MidnightMoonPose(0f, 0f, 0f, 0f, 0f, 0f))
        }
    }

    @Test
    fun 先拿枕头再抱住最后闭眼() {
        val taking = midnightMoonPose(at(0.22f))
        assertThat(taking.pillowReveal).isGreaterThan(0f)
        assertThat(taking.pillowLift).isGreaterThan(0f)
        assertThat(taking.cuddle).isEqualTo(0f)
        assertThat(taking.eyeClose).isEqualTo(0f)

        val holding = midnightMoonPose(at(0.58f))
        assertThat(holding.pillowReveal).isEqualTo(1f)
        assertThat(holding.pillowLift).isGreaterThan(0.9f)
        assertThat(holding.cuddle).isGreaterThan(0f)
        assertThat(holding.eyeClose).isEqualTo(0f)

        val closing = midnightMoonPose(at(0.78f))
        assertThat(closing.pillowLift).isEqualTo(1f)
        assertThat(closing.cuddle).isGreaterThan(0.5f)
        assertThat(closing.eyeClose).isGreaterThan(0f)
    }

    @Test
    fun 月牙收束时已经抱好枕头入睡() {
        val pose = midnightMoonPose(settled)
        assertThat(pose.pillowReveal).isEqualTo(1f)
        assertThat(pose.pillowLift).isEqualTo(1f)
        assertThat(pose.cuddle).isEqualTo(1f)
        assertThat(pose.eyeClose).isEqualTo(1f)
        assertThat(pose.sleep).isEqualTo(1f)
        assertThat(pose.breath).isEqualTo(0f)
    }

    @Test
    fun 一次性动作不倒退也不越界() {
        var last = midnightMoonPose(-1L)
        // 扫过整个主程再往入睡后延伸一段：入睡后那几项必须钉死在 1，不能跟着呼吸漂
        for (time in 0L..(settled + 4_000L) step 13L) {
            val pose = midnightMoonPose(time)
            val values = listOf(pose.pillowReveal, pose.pillowLift, pose.cuddle, pose.eyeClose, pose.sleep)
            val previous = listOf(last.pillowReveal, last.pillowLift, last.cuddle, last.eyeClose, last.sleep)
            values.zip(previous).forEach { (value, before) ->
                assertThat(value).isAtLeast(before)
                assertThat(value).isAtLeast(0f)
                assertThat(value).isAtMost(1f)
            }
            last = pose
        }
    }

    @Test
    fun 入睡后只呼吸不重新拿枕头() {
        val rest = midnightMoonPose(settled)
        for (offset in longArrayOf(1L, 800L, 2_400L, 3_200L, 20_000L)) {
            val pose = midnightMoonPose(settled + offset)
            assertThat(pose.copy(breath = 0f)).isEqualTo(rest)
            assertThat(pose.breath).isAtLeast(-1f)
            assertThat(pose.breath).isAtMost(1f)
        }
        assertThat(midnightMoonPose(settled + 800L).breath).isWithin(0.001f).of(1f)
        assertThat(midnightMoonPose(settled + 2_400L).breath).isWithin(0.001f).of(-1f)
    }

    @Test
    fun 回拨能完整恢复之前姿态() {
        val before = midnightMoonPose(1_200L)
        midnightMoonPose(8_000L)
        assertThat(midnightMoonPose(1_200L)).isEqualTo(before)
        assertThat(midnightMoonPose(-1L)).isEqualTo(MidnightMoonPose(0f, 0f, 0f, 0f, 0f, 0f))
    }

    @Test
    fun 起手缺口正对时钟() {
        // 初始态 = 终态对齐姿态再顺时针偏 20°。返回值是**镜像前**的转角，
        // 调用方的 scale(-unit, unit) 会把方向翻回屏幕上的顺时针，所以这里多一项负号
        val look = 47f
        assertThat(midnightMoonRotationDeg(look, 0f))
            .isWithin(1e-4f).of(-MIDNIGHT_MOON_INITIAL_EXTRA_TURN_DEG - look)
    }

    @Test
    fun 抱枕终态缺口对齐时钟() {
        // 终态严格对齐钟心，整个抱枕过程只回转初始多出的 20°
        val look = 47f
        assertThat(midnightMoonRotationDeg(look, 1f)).isWithin(1e-4f).of(-look)
        assertThat(midnightMoonRotationDeg(look, 0f) - midnightMoonRotationDeg(look, 1f))
            .isWithin(1e-4f).of(-MIDNIGHT_MOON_INITIAL_EXTRA_TURN_DEG)
        assertThat(MIDNIGHT_MOON_INITIAL_EXTRA_TURN_DEG).isWithin(0.001f).of(20f)
    }

    @Test
    fun 回转随抱枕进度单调且不越界() {
        var last = midnightMoonRotationDeg(47f, 0f)
        for (step in 0..20) {
            val value = midnightMoonRotationDeg(47f, step / 20f)
            // 从初始的顺时针偏转单调回收到终态对齐（镜像前的角度因此单调递增）
            assertThat(value).isAtLeast(last - 1e-4f)
            last = value
        }
        // 进度超出定义域也不许继续转
        assertThat(midnightMoonRotationDeg(47f, 5f)).isWithin(1e-4f).of(midnightMoonRotationDeg(47f, 1f))
        assertThat(midnightMoonRotationDeg(47f, -3f)).isWithin(1e-4f).of(midnightMoonRotationDeg(47f, 0f))
    }
}
