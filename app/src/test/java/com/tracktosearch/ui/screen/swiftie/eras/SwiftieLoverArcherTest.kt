package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Test

class SwiftieLoverArcherTest {
    private val pageSize = Size(width = 1080f, height = 2400f)
    private val cardBounds = Rect(left = 120f, top = 900f, right = 960f, bottom = 1900f)

    @Test
    fun aimUsesTheRealNockToHeartDirection() {
        val aim = swiftieLoverAim(cardBounds, pageSize)

        assertThat(abs(aim.unit.x)).isGreaterThan(0.01f)
        assertThat(aim.unit.y).isLessThan(-0.50f)
        assertThat(aim.angle).isWithin(0.001f).of(kotlin.math.atan2(aim.unit.y, aim.unit.x))
    }

    @Test
    fun flightTipRemainsOnOneStraightLine() {
        val geometry = swiftieLoverArrowGeometry(cardBounds, pageSize)
        val line = geometry.targetTip - geometry.launchTip
        val lineLength = hypot(line.x, line.y)
        var previousProjection = 0f

        for (step in 0..4) {
            val t = step / 4f
            val tip = geometry.launchTip + line * t
            val cross = (tip - geometry.launchTip).x * line.y -
                (tip - geometry.launchTip).y * line.x
            val projection = (tip - geometry.launchTip).x * line.x +
                (tip - geometry.launchTip).y * line.y

            val perpendicularDistance = abs(cross) / lineLength
            assertThat(perpendicularDistance).isWithin(0.01f).of(0f)
            assertThat(projection - previousProjection).isAtLeast(-0.01f)
            previousProjection = projection
        }

        assertThat(geometry.targetTip.x).isWithin(0.01f).of(
            geometry.launchTip.x + line.x
        )
        assertThat(geometry.targetTip.y).isWithin(0.01f).of(
            geometry.launchTip.y + line.y
        )
    }

    @Test
    fun launchTipMatchesTheArrowMountedOnTheBow() {
        val box = swiftiePropBox(cardBounds.size)
        val geometry = swiftieLoverArrowGeometry(cardBounds, pageSize)
        val localStart = swiftieLoverArrowStart(box, geometry.unit)
        val rootStart = cardBounds.topLeft + localStart

        assertThat(rootStart.x).isWithin(0.01f).of(geometry.launchTip.x)
        assertThat(rootStart.y).isWithin(0.01f).of(geometry.launchTip.y)
    }

    @Test
    fun targetTipMatchesTheArrowMountedInTheHeart() {
        val box = swiftiePropBox(cardBounds.size)
        val geometry = swiftieLoverArrowGeometry(cardBounds, pageSize)
        val heartTip = swiftieLoverStuckTip(
            heart = swiftieLoverHeartBox(pageSize),
            length = swiftieLoverStuckLength(box),
            aimUnit = geometry.unit
        )

        assertThat(heartTip.x).isWithin(0.01f).of(geometry.targetTip.x)
        assertThat(heartTip.y).isWithin(0.01f).of(geometry.targetTip.y)

        val heartCenter = swiftieLoverHeartBox(pageSize).center
        val targetOffset = hypot(
            geometry.targetTip.x - heartCenter.x,
            geometry.targetTip.y - heartCenter.y
        )
        assertThat(targetOffset).isWithin(0.01f).of(
            swiftieLoverStuckLength(box) * 0.10f
        )
    }

    @Test
    fun shaftStopsAtHeartTailAndStuckArrowKeepsBaseLength() {
        val box = swiftiePropBox(cardBounds.size)

        val shaftHeadFraction = swiftieLoverArrowShaftHeadFraction(0f)
        assertThat(shaftHeadFraction).isGreaterThan(0.10f)
        assertThat(shaftHeadFraction).isLessThan(0.20f)
        assertThat(swiftieLoverArrowShaftHeadFraction(0.49f))
            .isWithin(0.001f).of(0.49f)
        assertThat(swiftieLoverStuckLength(box))
            .isWithin(0.001f).of(swiftieLoverArrowLength(box))
    }

    @Test
    fun invalidMeasurementUsesAStableNonVerticalFallback() {
        val aim = swiftieLoverAim(Rect.Zero, Size.Zero)

        assertThat(aim.unit).isEqualTo(LOVER_FALLBACK_AIM_UNIT)
        assertThat(aim.angle).isEqualTo(LOVER_FALLBACK_AIM_ANGLE)
        assertThat(aim.unit).isNotEqualTo(Offset(0f, -1f))
    }
}
