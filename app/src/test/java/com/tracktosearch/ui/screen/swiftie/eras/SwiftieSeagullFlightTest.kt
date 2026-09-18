package com.tracktosearch.ui.screen.swiftie.eras

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

class SwiftieSeagullFlightTest {
    @Test
    fun 下压更快且翼尖上下幅度完整() {
        assertThat(seagullWingStroke(0f)).isWithin(0.0001f).of(-1f)
        assertThat(seagullWingStroke(0.38f)).isWithin(0.0001f).of(1f)
        assertThat(seagullWingStroke(1f)).isWithin(0.0001f).of(-1f)
        assertThat(seagullWingStroke(0.19f)).isWithin(0.0001f).of(0f)
        assertThat(seagullWingStroke(0.69f)).isWithin(0.0001f).of(0f)
    }

    @Test
    fun 振翅之间有稳定滑翔且尺寸不随拍翼改变() {
        val bird = seagullFlights(false).last()
        var active = 0
        var gliding = 0
        for (i in 0..1200) {
            val pose = updateSeagullPose(bird, i / 1200f, 0.4f, 900f, 2000f)
            val size = pose.scale
            if (pose.effort > 0.9f) active++
            if (pose.effort == 0f) {
                gliding++
                assertThat(pose.shoulder).isWithin(0.0001f).of(0f)
                assertThat(pose.wingTip).isWithin(0.0001f).of(0f)
                assertThat(pose.fold).isWithin(0.0001f).of(0f)
            }
            assertThat(size).isWithin(0.0001f).of(updateSeagullPose(bird, 0f, 0.4f, 900f, 2000f).scale)
        }
        assertThat(active).isGreaterThan(300)
        assertThat(gliding).isGreaterThan(400)
    }

    @Test
    fun 相位绕回时位置和翼形连续() {
        for (bird in seagullFlights(false)) {
            val before = updateSeagullPose(bird, 0.999999f, 0.999999f, 900f, 2000f)
            val values = floatArrayOf(before.x, before.y, before.shoulder, before.wingTip, before.fold)
            val after = updateSeagullPose(bird, 0f, 0f, 900f, 2000f)
            val next = floatArrayOf(after.x, after.y, after.shoulder, after.wingTip, after.fold)
            for (i in values.indices) assertThat(next[i]).isWithin(0.001f).of(values[i])
        }
    }

    @Test
    fun 每趟接缝完全在画面外且不可见() {
        for (bird in seagullFlights(false)) {
            val phase = (1f - bird.offset) / bird.rounds
            for (delta in floatArrayOf(-0.00001f, 0.00001f)) {
                val pose = updateSeagullPose(bird, 0.3f, phase + delta, 900f, 2000f)
                assertThat(pose.alpha).isLessThan(0.001f)
                assertThat(pose.x < -0.20f || pose.x > 1.20f).isTrue()
            }
        }
    }

    @Test
    fun 轨迹不倒退且朝向跟随爬升下降() {
        for (bird in seagullFlights(false)) {
            for (i in 1 until 999) {
                val t = i / 1000f
                val tp = (1f + t - bird.offset) / bird.rounds
                val pose = updateSeagullPose(bird, 0.2f, tp, 900f, 2000f)
                val x = pose.x
                val y = pose.y
                val angle = pose.headingDegrees
                val next = updateSeagullPose(bird, 0.2f, tp + 0.00001f, 900f, 2000f)
                assertThat((next.x - x) * bird.direction).isGreaterThan(0f)
                if (abs(next.y - y) > 0.000001f) {
                    assertThat(angle * bird.direction * (next.y - y)).isGreaterThan(0f)
                }
                assertThat(abs(angle)).isLessThan(18f)
                assertThat(y >= 0.12f && y <= 0.40f).isTrue()
            }
        }
    }

    @Test
    fun 明信片旁的两只海鸥从右上方错层向左飞() {
        val birds = seagullFlights(false).take(2)
        val startTravelPhase = 33_720f % 30_000f / 30_000f
        val startY = mutableListOf<Float>()
        for (bird in birds) {
            val start = updateSeagullPose(bird, 0f, startTravelPhase, 900f, 2000f)
            val x = start.x
            startY += start.y
            assertThat(x).isGreaterThan(0.80f)
            assertThat(start.y >= 0.12f && start.y <= 0.23f).isTrue()
            assertThat(bird.direction).isEqualTo(-1f)
            val later = updateSeagullPose(bird, 0.1f, startTravelPhase + 1f / 30f, 900f, 2000f)
            assertThat(later.x).isLessThan(x)
        }
        assertThat(abs(startY[1] - startY[0])).isGreaterThan(0.055f)
    }

    @Test
    fun 低配仍有远近两只且姿态对象复用() {
        val birds = seagullFlights(true)
        assertThat(birds).hasSize(2)
        assertThat(birds.first().halfSpan).isLessThan(birds.last().halfSpan)
        val bird = birds.last()
        assertThat(updateSeagullPose(bird, 0f, 0f, 900f, 2000f))
            .isSameInstanceAs(updateSeagullPose(bird, 0.5f, 0.5f, 900f, 2000f))
    }
}
