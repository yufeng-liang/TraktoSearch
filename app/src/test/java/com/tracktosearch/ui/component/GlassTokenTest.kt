package com.tracktosearch.ui.component

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.theme.GlassVariant
import org.junit.Test

class GlassTokenTest {

    @Test
    fun focusedHasStrongerTopBarTokenThanClear() {
        val clear = glassToken(GlassSurfaceRole.TopBar, GlassVariant.CLEAR, false)
        val focused = glassToken(GlassSurfaceRole.TopBar, GlassVariant.FOCUSED, false)

        assertThat(clear.tintAlpha).isLessThan(focused.tintAlpha)
        assertThat(clear.specularIntensity).isLessThan(focused.specularIntensity)
        assertThat(clear.ambientResponse).isLessThan(focused.ambientResponse)
    }

    @Test
    fun allFormalRolesDisableChromaticAberration() {
        GlassSurfaceRole.entries.forEach { role ->
            GlassVariant.entries.forEach { variant ->
                assertThat(glassToken(role, variant, false).chromaticAberrationStrength)
                    .isEqualTo(0f)
            }
        }
    }

    @Test
    fun pressScaleStaysWithinHazeGlassBounds() {
        val pressScale = glassToken(
            GlassSurfaceRole.CircularControl,
            GlassVariant.FOCUSED,
            false
        ).pressScale

        assertThat(pressScale).isAtLeast(0.98f)
        assertThat(pressScale).isAtMost(1f)
    }
}
