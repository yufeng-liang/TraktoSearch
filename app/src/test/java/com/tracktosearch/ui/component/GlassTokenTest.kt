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

    @Test
    fun loginSurfaceUsesItsOwnRoleInsteadOfTopBarOrCircularControl() {
        val login = glassToken(GlassSurfaceRole.LoginSurface, GlassVariant.CLEAR, false)
        val topBar = glassToken(GlassSurfaceRole.TopBar, GlassVariant.CLEAR, false)
        val circularControl = glassToken(
            GlassSurfaceRole.CircularControl,
            GlassVariant.CLEAR,
            false
        )
        val detailAction = glassToken(GlassSurfaceRole.DetailAction, GlassVariant.CLEAR, false)
        val searchField = glassToken(GlassSurfaceRole.SearchField, GlassVariant.CLEAR, false)

        assertThat(login.tintAlpha).isGreaterThan(topBar.tintAlpha)
        assertThat(login.edgeSoftness).isGreaterThan(topBar.edgeSoftness)
        assertThat(login.surfaceProfile).isNotEqualTo(circularControl.surfaceProfile)
        assertThat(detailAction.surfaceProfile).isNotEqualTo(searchField.surfaceProfile)
    }

    @Test
    fun lowerCallingAlphaRemainsLowerAfterGlassTokenIsApplied() {
        val token = glassToken(GlassSurfaceRole.DetailAction, GlassVariant.CLEAR, false)

        val enabledAlpha = resolveGlassTintAlpha(0.72f, token.tintAlpha)
        val disabledAlpha = resolveGlassTintAlpha(0.24f, token.tintAlpha)

        assertThat(disabledAlpha).isLessThan(enabledAlpha)
    }

    @Test
    fun glassTokenStillModulatesCallingTintAlpha() {
        val clear = glassToken(GlassSurfaceRole.DetailAction, GlassVariant.CLEAR, false)
        val focused = glassToken(GlassSurfaceRole.DetailAction, GlassVariant.FOCUSED, false)

        val clearAlpha = resolveGlassTintAlpha(0.72f, clear.tintAlpha)
        val focusedAlpha = resolveGlassTintAlpha(0.72f, focused.tintAlpha)

        assertThat(clearAlpha).isLessThan(focusedAlpha)
        assertThat(clearAlpha).isWithin(0.0001f).of(0.72f * clear.tintAlpha)
    }
}
