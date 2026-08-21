package com.tracktosearch.ui.component

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.ui.theme.GlassVariant
import com.tracktosearch.ui.theme.VisualEffectMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Test

class GlassTokenTest {

    @Test
    fun glassModeUsesIndependentSurfaceTreatment() {
        assertThat(surfaceTreatmentFor(VisualEffectMode.GLASS))
            .isEqualTo(SurfaceTreatment.GLASS)
        assertThat(surfaceTreatmentFor(VisualEffectMode.BLUR))
            .isEqualTo(SurfaceTreatment.NEUMORPHIC)
    }

    @Test
    fun glassModeDisablesNeumorphicDecorations() {
        assertThat(usesNeumorphicDecoration(VisualEffectMode.GLASS)).isFalse()
        assertThat(usesNeumorphicDecoration(VisualEffectMode.BLUR)).isTrue()
    }

    @Test
    fun transparentGlassFallbackUsesTokenControlledFill() {
        val themeSurface = Color.White

        assertThat(
            resolveGlassFallbackFill(
                backgroundColor = Color.Transparent,
                themeSurface = themeSurface,
                tokenAlpha = 0.24f,
                ambientColor = Color.Transparent,
                environmentTintStrength = 0f
            )
        ).isEqualTo(themeSurface.copy(alpha = 0.24f))
    }

    @Test
    fun glassFallbackBlendsEnvironmentColorIntoFill() {
        val themeSurface = Color.White
        val ambient = Color.Black

        val plain = resolveGlassFallbackFill(
            backgroundColor = Color.Transparent,
            themeSurface = themeSurface,
            tokenAlpha = 0.5f,
            ambientColor = Color.Transparent,
            environmentTintStrength = 0f
        )
        val tinted = resolveGlassFallbackFill(
            backgroundColor = Color.Transparent,
            themeSurface = themeSurface,
            tokenAlpha = 0.5f,
            ambientColor = ambient,
            environmentTintStrength = 0.5f
        )

        assertThat(tinted.red).isLessThan(plain.red)
        assertThat(tinted.alpha).isEqualTo(plain.alpha)
        // 混合后的颜色亮度应介于纯白与纯黑之间
        assertThat(tinted.red).isGreaterThan(0f)
        assertThat(tinted.red).isLessThan(1f)
    }

    @Test
    fun glassFallbackKeepsCallingAlphaAfterEnvironmentBlend() {
        val result = resolveGlassFallbackFill(
            backgroundColor = Color.White.copy(alpha = 0.4f),
            themeSurface = Color.Black,
            tokenAlpha = 0.5f,
            ambientColor = Color.Blue,
            environmentTintStrength = 0.3f
        )

        assertThat(result.alpha).isWithin(0.0001f).of(0.2f)
        assertThat(result.blue).isGreaterThan(0f)
    }

    @Test
    fun glassAmbientColorFallsBackToThemeBackgroundWhenTransparent() {
        val sceneAmbient = Color.Transparent
        val themeBackground = Color(0xFF101820)

        assertThat(
            resolveGlassAmbientColor(
                sceneAmbient = sceneAmbient,
                themeBackground = themeBackground
            )
        ).isEqualTo(themeBackground)

        assertThat(
            resolveGlassAmbientColor(
                sceneAmbient = Color(0xFF223344),
                themeBackground = themeBackground
            )
        ).isEqualTo(Color(0xFF223344))
    }

    @Test
    fun posterCacheCandidatesCoverTheKnownTmdbSizes() {
        // 期望值从 TmdbImageUrls 取 base：图片走网关代理，base 由 local.properties 的
        // gateway.base.url 决定，写死主机名会让测试随环境红。尺寸清单仍逐个列出，
        // POSTER_CACHE_SIZES 增删或改序时本用例仍会失败。
        assertThat(posterCacheKeyCandidates("/poster.jpg"))
            .containsExactly(
                "${TmdbImageUrls.W185}/poster.jpg",
                "${TmdbImageUrls.W342}/poster.jpg",
                "${TmdbImageUrls.W500}/poster.jpg",
                "${TmdbImageUrls.W780}/poster.jpg",
                "${TmdbImageUrls.H632}/poster.jpg"
            )
            .inOrder()
    }

    @Test
    fun contentSceneNormalizesCountAndReadability() {
        val scene = glassSceneForContent(
            contentCount = 12,
            readabilityDemand = 1.4f,
            ambientColor = Color.Black,
            contentCapacity = 24
        )

        assertThat(scene.contentLoad).isWithin(0.0001f).of(0.5f)
        assertThat(scene.readabilityDemand).isEqualTo(1f)
        assertThat(scene.ambientColor).isEqualTo(Color.Black)
    }

    @Test
    fun contentSceneClampsInvalidInputs() {
        val scene = glassSceneForContent(
            contentCount = -4,
            readabilityDemand = -1f,
            contentCapacity = 0
        )

        assertThat(scene.contentLoad).isEqualTo(0f)
        assertThat(scene.readabilityDemand).isEqualTo(0f)
    }

    @Test
    fun loadingSlotsContributeToContentLoadBeforeItemsArrive() {
        val scene = glassSceneForContent(
            contentCount = 0,
            loadingCount = 3,
            loadingItemWeight = 4,
            contentCapacity = 12
        )

        assertThat(scene.contentLoad).isWithin(0.0001f).of(1f)
    }

    @Test
    fun focusedHasStrongerTopBarTokenThanClear() {
        val clear = glassToken(GlassSurfaceRole.TopBar, GlassVariant.CLEAR, false)
        val focused = glassToken(GlassSurfaceRole.TopBar, GlassVariant.FOCUSED, false)

        assertThat(clear.tintAlpha).isLessThan(focused.tintAlpha)
        assertThat(clear.specularIntensity).isLessThan(focused.specularIntensity)
        assertThat(clear.ambientResponse).isLessThan(focused.ambientResponse)
    }

    @Test
    fun lightCircularControlGetsAWhiterFillThanTopBar() {
        val circularControl = glassToken(
            GlassSurfaceRole.CircularControl,
            GlassVariant.CLEAR,
            isDark = false
        )
        val topBar = glassToken(GlassSurfaceRole.TopBar, GlassVariant.CLEAR, isDark = false)

        assertThat(circularControl.tintAlpha).isAtLeast(0.16f)
        assertThat(circularControl.tintAlpha).isGreaterThan(topBar.tintAlpha)
    }

    @Test
    fun lightCircularControlUsesAVisibleSpecularHighlight() {
        val circularControl = glassToken(
            GlassSurfaceRole.CircularControl,
            GlassVariant.CLEAR,
            isDark = false
        )

        assertThat(circularControl.specularIntensity).isAtLeast(0.48f)
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

    @Test
    fun denseContentPrioritizesReadabilityAndReducesSpecularGlare() {
        val sparse = glassToken(
            role = GlassSurfaceRole.TopBar,
            variant = GlassVariant.CLEAR,
            isDark = false,
            scene = GlassScene(
                contentLoad = 0f,
                readabilityDemand = 0f,
                ambientColor = Color.White
            )
        )
        val dense = glassToken(
            role = GlassSurfaceRole.TopBar,
            variant = GlassVariant.CLEAR,
            isDark = false,
            scene = GlassScene(
                contentLoad = 1f,
                readabilityDemand = 1f,
                ambientColor = Color.White
            )
        )

        assertThat(dense.tintAlpha).isGreaterThan(sparse.tintAlpha)
        assertThat(dense.borderAlpha).isGreaterThan(sparse.borderAlpha)
        assertThat(dense.specularIntensity).isLessThan(sparse.specularIntensity)
    }

    @Test
    fun highContrastAmbientColorAddsProtectionWithoutChangingGlassLanguage() {
        val calm = glassToken(
            role = GlassSurfaceRole.SearchField,
            variant = GlassVariant.CLEAR,
            isDark = false,
            scene = GlassScene(
                contentLoad = 0.35f,
                readabilityDemand = 0.35f,
                ambientColor = Color(0xFF777777)
            )
        )
        val highContrast = glassToken(
            role = GlassSurfaceRole.SearchField,
            variant = GlassVariant.CLEAR,
            isDark = false,
            scene = GlassScene(
                contentLoad = 0.35f,
                readabilityDemand = 0.35f,
                ambientColor = Color.Black
            )
        )

        assertThat(highContrast.tintAlpha).isGreaterThan(calm.tintAlpha)
        assertThat(highContrast.chromaticAberrationStrength).isEqualTo(0f)
        assertThat(highContrast.pressScale).isAtMost(1f)
    }

    @Test
    fun sceneInputsAreClampedToStableGlassRanges() {
        val token = glassToken(
            role = GlassSurfaceRole.BottomNavigation,
            variant = GlassVariant.FOCUSED,
            isDark = true,
            scene = GlassScene(
                contentLoad = 4f,
                readabilityDemand = -2f,
                ambientColor = Color.White
            )
        )

        assertThat(token.tintAlpha).isAtLeast(0f)
        assertThat(token.tintAlpha).isAtMost(1f)
        assertThat(token.borderAlpha).isAtLeast(0f)
        assertThat(token.borderAlpha).isAtMost(1f)
        assertThat(token.specularIntensity).isAtLeast(0f)
        assertThat(token.specularIntensity).isAtMost(1f)
        assertThat(token.ambientResponse).isAtLeast(0f)
        assertThat(token.ambientResponse).isAtMost(1f)
    }

    @Test
    fun cachedPosterColorsBlendIntoTheEnvironmentColor() {
        val ambient = resolveCachedPosterAmbientColor(
            cachedColors = listOf(Color.Red, Color.Blue),
            fallback = Color.White
        )

        assertThat(ambient.red).isWithin(0.0001f).of(128f / 255f)
        assertThat(ambient.green).isWithin(0.0001f).of(0f)
        assertThat(ambient.blue).isWithin(0.0001f).of(128f / 255f)
    }

    @Test
    fun missingPosterColorsUseTheThemeFallback() {
        val fallback = Color(0xFF123456)

        assertThat(resolveCachedPosterAmbientColor(emptyList(), fallback))
            .isEqualTo(fallback)
    }

    @Test
    fun searchBarKeepsBaseFillWhenHazeInactive() {
        val fallback = Color(0xFF112233)

        assertThat(
            searchBarBaseColor(
                mode = VisualEffectMode.GLASS,
                hazeActive = false,
                fallbackColor = fallback
            )
        ).isEqualTo(fallback)
        assertThat(
            searchBarBaseColor(
                mode = VisualEffectMode.BLUR,
                hazeActive = false,
                fallbackColor = fallback
            )
        ).isEqualTo(fallback)
    }

    @Test
    fun searchBarKeepsBaseFillInBlurModeWithHaze() {
        val fallback = Color(0xFF112233)

        assertThat(
            searchBarBaseColor(
                mode = VisualEffectMode.BLUR,
                hazeActive = true,
                fallbackColor = fallback
            )
        ).isEqualTo(fallback)
    }

    @Test
    fun searchBarDropsBaseFillInGlassModeWithHaze() {
        assertThat(
            searchBarBaseColor(
                mode = VisualEffectMode.GLASS,
                hazeActive = true,
                fallbackColor = Color.Red
            )
        ).isEqualTo(Color.Transparent)
    }

    @Test
    fun backdropRolesUseApprovedLensTable() {
        val bottom = backdropGlassToken(GlassSurfaceRole.BottomNavigation, GlassVariant.CLEAR, false)
        val top = backdropGlassToken(GlassSurfaceRole.TopBar, GlassVariant.CLEAR, false)
        val search = backdropGlassToken(GlassSurfaceRole.SearchField, GlassVariant.CLEAR, false)
        val card = backdropGlassToken(GlassSurfaceRole.Card, GlassVariant.CLEAR, false)
        val circular = backdropGlassToken(GlassSurfaceRole.CircularControl, GlassVariant.CLEAR, false)
        val detail = backdropGlassToken(GlassSurfaceRole.DetailAction, GlassVariant.CLEAR, false)

        assertThat(bottom.blurRadius).isEqualTo(20.dp)
        assertThat(bottom.refractionHeight).isEqualTo(8.dp)
        assertThat(bottom.refractionAmount).isEqualTo(36.dp)
        assertThat(top.blurRadius).isEqualTo(20.dp)
        assertThat(top.refractionHeight).isEqualTo(8.dp)
        assertThat(top.refractionAmount).isEqualTo(36.dp)
        assertThat(search.blurRadius).isEqualTo(14.dp)
        assertThat(search.refractionHeight).isEqualTo(8.dp)
        assertThat(search.refractionAmount).isEqualTo(26.dp)
        assertThat(card.blurRadius).isEqualTo(14.dp)
        assertThat(card.refractionHeight).isEqualTo(8.dp)
        assertThat(card.refractionAmount).isEqualTo(26.dp)
        assertThat(card.depthEffect).isTrue()
        assertThat(card.chromaticAberration).isTrue()
        assertThat(card.highlightWidth).isEqualTo(search.highlightWidth)
        assertThat(card.highlightAlpha).isEqualTo(search.highlightAlpha)
        assertThat(card.shadowRadius).isEqualTo(search.shadowRadius)
        assertThat(card.shadowAlpha).isEqualTo(search.shadowAlpha)
        assertThat(circular.blurRadius).isEqualTo(12.dp)
        assertThat(circular.refractionHeight).isEqualTo(6.dp)
        assertThat(circular.refractionAmount).isEqualTo(18.dp)
        assertThat(detail.blurRadius).isEqualTo(12.dp)
        assertThat(detail.refractionHeight).isEqualTo(6.dp)
        assertThat(detail.refractionAmount).isEqualTo(18.dp)
    }

    @Test
    fun backdropChromaticFlagsMatchApprovedSceneTable() {
        assertThat(backdropGlassToken(GlassSurfaceRole.BottomNavigation, GlassVariant.CLEAR, false).chromaticAberration).isTrue()
        assertThat(backdropGlassToken(GlassSurfaceRole.TopBar, GlassVariant.CLEAR, false).chromaticAberration).isTrue()
        assertThat(backdropGlassToken(GlassSurfaceRole.SearchField, GlassVariant.CLEAR, false).chromaticAberration).isTrue()
        assertThat(backdropGlassToken(GlassSurfaceRole.Card, GlassVariant.CLEAR, false).chromaticAberration).isTrue()
        assertThat(backdropGlassToken(GlassSurfaceRole.CircularControl, GlassVariant.CLEAR, false).chromaticAberration).isFalse()
        assertThat(backdropGlassToken(GlassSurfaceRole.DetailAction, GlassVariant.CLEAR, false).chromaticAberration).isFalse()
    }

    @Test
    fun navigationSelectionUsesAConcentratedWaterDropLensAndStrongerEdge() {
        val selection = backdropNavigationSelectionToken(
            variant = GlassVariant.CLEAR,
            isDark = false
        )
        val panel = backdropGlassToken(
            role = GlassSurfaceRole.BottomNavigation,
            variant = GlassVariant.CLEAR,
            isDark = false
        )

        assertThat(selection.blurRadius).isEqualTo(10.dp)
        assertThat(selection.refractionHeight).isEqualTo(10.dp)
        assertThat(selection.refractionAmount).isEqualTo(30.dp)
        assertThat(selection.depthEffect).isTrue()
        assertThat(selection.chromaticAberration).isTrue()
        assertThat(selection.borderAlpha).isGreaterThan(panel.borderAlpha)
        assertThat(selection.highlightAlpha).isGreaterThan(panel.highlightAlpha)
    }

    @Test
    fun focusedVariantDoesNotChangeLensValues() {
        GlassSurfaceRole.entries.forEach { role ->
            val clear = backdropGlassToken(role, GlassVariant.CLEAR, false)
            val focused = backdropGlassToken(role, GlassVariant.FOCUSED, false)
            assertThat(focused.blurRadius).isEqualTo(clear.blurRadius)
            assertThat(focused.refractionHeight).isEqualTo(clear.refractionHeight)
            assertThat(focused.refractionAmount).isEqualTo(clear.refractionAmount)
        }
    }
}
