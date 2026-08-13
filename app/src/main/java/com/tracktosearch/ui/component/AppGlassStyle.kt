@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.theme.GlassVariant
import com.tracktosearch.ui.theme.LocalGlassVariant
import dev.chrisbanes.haze.glass.GlassOptics
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.SurfaceProfile

/** Glass 表面的语义角色，页面不应自行组合光学参数。 */
enum class GlassSurfaceRole {
    TopBar,
    CircularControl,
    BottomNavigation,
    SearchField,
    DetailAction,
    LoginSurface
}

/** Glass 光学 token。所有正式角色都禁用色散，避免页面间出现不可控的色差。 */
data class GlassToken(
    val tintAlpha: Float,
    val specularIntensity: Float,
    val ambientResponse: Float,
    val edgeSoftness: Dp,
    val surfaceProfile: SurfaceProfile,
    val chromaticAberrationStrength: Float = 0f,
    val hoverLighting: Float,
    val pressLighting: Float,
    val pressRefractionMultiplier: Float,
    val pressWhitePointDelta: Float,
    val pressScale: Float
)

/**
 * 根据表面角色、Glass 风格和明暗主题读取纯 token。
 * 清透/聚焦仅改变材质强度，不改变角色的几何 profile。
 */
fun glassToken(
    role: GlassSurfaceRole,
    variant: GlassVariant,
    isDark: Boolean
): GlassToken {
    val tintAlpha = when (role) {
        GlassSurfaceRole.TopBar -> if (isDark) {
            if (variant == GlassVariant.FOCUSED) 0.20f else 0.13f
        } else {
            if (variant == GlassVariant.FOCUSED) 0.13f else 0.08f
        }

        GlassSurfaceRole.CircularControl,
        GlassSurfaceRole.DetailAction -> if (isDark) {
            if (variant == GlassVariant.FOCUSED) 0.25f else 0.15f
        } else {
            if (variant == GlassVariant.FOCUSED) 0.18f else 0.11f
        }

        GlassSurfaceRole.BottomNavigation -> if (isDark) {
            if (variant == GlassVariant.FOCUSED) 0.34f else 0.25f
        } else {
            if (variant == GlassVariant.FOCUSED) 0.49f else 0.38f
        }

        GlassSurfaceRole.SearchField -> if (isDark) {
            if (variant == GlassVariant.FOCUSED) 0.28f else 0.18f
        } else {
            if (variant == GlassVariant.FOCUSED) 0.23f else 0.14f
        }

        GlassSurfaceRole.LoginSurface -> if (isDark) {
            if (variant == GlassVariant.FOCUSED) 0.43f else 0.34f
        } else {
            if (variant == GlassVariant.FOCUSED) 0.53f else 0.42f
        }
    }

    val focused = variant == GlassVariant.FOCUSED
    return GlassToken(
        tintAlpha = tintAlpha,
        specularIntensity = if (focused) 0.62f else 0.38f,
        ambientResponse = if (focused) 0.60f else 0.42f,
        edgeSoftness = when (role) {
            GlassSurfaceRole.TopBar -> 2.dp
            GlassSurfaceRole.CircularControl,
            GlassSurfaceRole.DetailAction -> 3.dp
            GlassSurfaceRole.BottomNavigation -> 8.dp
            GlassSurfaceRole.SearchField -> 4.dp
            GlassSurfaceRole.LoginSurface -> 8.dp
        },
        surfaceProfile = when (role) {
            GlassSurfaceRole.CircularControl,
            GlassSurfaceRole.DetailAction -> SurfaceProfile.Circle
            GlassSurfaceRole.BottomNavigation -> SurfaceProfile.Lip
            else -> SurfaceProfile.Squircle
        },
        chromaticAberrationStrength = 0f,
        hoverLighting = if (focused) 0.48f else 0.28f,
        pressLighting = if (focused) 1.0f else 0.82f,
        pressRefractionMultiplier = if (focused) 1.10f else 1.06f,
        pressWhitePointDelta = if (focused) 0.05f else 0.03f,
        pressScale = if (focused) 0.985f else 0.99f
    )
}

internal fun resolveGlassTintAlpha(callingAlpha: Float, tokenAlpha: Float): Float {
    return (callingAlpha * tokenAlpha).coerceIn(0f, 1f)
}

/** TrackToSearch 的集中 Glass 样式入口。 */
object AppGlassStyles {
    @Composable
    fun style(
        role: GlassSurfaceRole,
        shape: RoundedCornerShape,
        tint: Color = MaterialTheme.colorScheme.surface,
        interactive: Boolean = false
    ): GlassStyle {
        val variant = LocalGlassVariant.current
        val isDark = isAppDarkTheme()
        val token = glassToken(role, variant, isDark)

        return GlassStyle {
            tint(tint.copy(alpha = resolveGlassTintAlpha(tint.alpha, token.tintAlpha)))
            optics(GlassOptics.Adaptive)
            specularIntensity(token.specularIntensity)
            ambientResponse(token.ambientResponse)
            edgeSoftness(token.edgeSoftness)
            shape(shape)
            surfaceProfile(token.surfaceProfile)
            chromaticAberrationStrength(token.chromaticAberrationStrength)
            if (interactive) {
                hovered {
                    lightingIntensity(token.hoverLighting)
                }
                focused {
                    lightingIntensity(token.hoverLighting)
                }
                pressed {
                    lightingIntensity(token.pressLighting)
                    refractionMultiplier(token.pressRefractionMultiplier)
                    whitePointDelta(token.pressWhitePointDelta)
                    scale(token.pressScale.coerceIn(Float.MIN_VALUE, 1f))
                }
            }
        }
    }

    @Composable
    fun topBar(tint: Color = MaterialTheme.colorScheme.surface): GlassStyle {
        return style(
            role = GlassSurfaceRole.TopBar,
            shape = RoundedCornerShape(0.dp),
            tint = tint
        )
    }

    @Composable
    fun searchField(
        tint: Color = MaterialTheme.colorScheme.surface,
        shape: RoundedCornerShape = RoundedCornerShape(16.dp),
        interactive: Boolean = true
    ): GlassStyle {
        return style(
            role = GlassSurfaceRole.SearchField,
            shape = shape,
            tint = tint,
            interactive = interactive
        )
    }

    @Composable
    fun circularControl(
        tint: Color = MaterialTheme.colorScheme.surface,
        interactive: Boolean = true
    ): GlassStyle {
        return style(
            role = GlassSurfaceRole.CircularControl,
            shape = RoundedCornerShape(50),
            tint = tint,
            interactive = interactive
        )
    }

    @Composable
    fun bottomNavigation(
        tint: Color,
        shape: RoundedCornerShape
    ): GlassStyle {
        return style(
            role = GlassSurfaceRole.BottomNavigation,
            shape = shape,
            tint = tint
        )
    }

    @Composable
    fun detailAction(
        tint: Color = MaterialTheme.colorScheme.surface,
        shape: RoundedCornerShape = RoundedCornerShape(50),
        interactive: Boolean = true
    ): GlassStyle {
        return style(
            role = GlassSurfaceRole.DetailAction,
            shape = shape,
            tint = tint,
            interactive = interactive
        )
    }

    @Composable
    fun loginSurface(
        tint: Color = MaterialTheme.colorScheme.surface,
        shape: RoundedCornerShape = RoundedCornerShape(24.dp)
    ): GlassStyle {
        return style(
            role = GlassSurfaceRole.LoginSurface,
            shape = shape,
            tint = tint
        )
    }
}
