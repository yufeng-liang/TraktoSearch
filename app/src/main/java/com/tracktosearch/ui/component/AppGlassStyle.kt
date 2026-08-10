@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.glass.ChromaticAberrationMode
import dev.chrisbanes.haze.glass.GlassOptics
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.SurfaceProfile

/**
 * TrackToSearch 的 Glass 光学样式。
 *
 * 统一的材质参数放在这里，页面只负责提供当前表面的 tint 和几何形状，
 * 避免每个调用点自行调节折射、高光和色差，导致视觉强度失控。
 */
object AppGlassStyles {
    @Composable
    fun surface(
        tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.16f),
        shape: RoundedCornerShape = RoundedCornerShape(16.dp),
        edgeSoftness: Dp = 2.dp,
        specularIntensity: Float = 0.40f,
        ambientResponse: Float = 0.46f,
        surfaceProfile: SurfaceProfile = SurfaceProfile.Squircle,
        chromaticAberrationStrength: Float = 0f,
        interactive: Boolean = false
    ): GlassStyle {
        return GlassStyle {
            tint(tint)
            optics(GlassOptics.Adaptive)
            specularIntensity(specularIntensity)
            ambientResponse(ambientResponse)
            edgeSoftness(edgeSoftness)
            shape(shape)
            surfaceProfile(surfaceProfile)
            if (chromaticAberrationStrength > 0f) {
                chromaticAberrationMode(ChromaticAberrationMode.Simple)
                chromaticAberrationStrength(chromaticAberrationStrength)
            }
            if (interactive) {
                hovered {
                    lightingIntensity(0.35f)
                }
                focused {
                    lightingIntensity(0.35f)
                }
                pressed {
                    lightingIntensity(1f)
                    refractionMultiplier(1.08f)
                    whitePointDelta(0.04f)
                    scale(0.98f)
                }
            }
        }
    }

    @Composable
    fun topBar(tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.12f)): GlassStyle {
        return surface(
            tint = tint,
            shape = RoundedCornerShape(0.dp),
            edgeSoftness = 2.dp,
            surfaceProfile = SurfaceProfile.Squircle
        )
    }

    @Composable
    fun control(
        tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f),
        shape: RoundedCornerShape = RoundedCornerShape(16.dp),
        interactive: Boolean = true
    ): GlassStyle {
        return surface(
            tint = tint,
            shape = shape,
            edgeSoftness = 4.dp,
            specularIntensity = 0.52f,
            ambientResponse = 0.52f,
            surfaceProfile = SurfaceProfile.Squircle,
            interactive = interactive
        )
    }

    @Composable
    fun circularControl(
        tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f),
        interactive: Boolean = true
    ): GlassStyle {
        return surface(
            tint = tint,
            shape = RoundedCornerShape(50),
            edgeSoftness = 3.dp,
            specularIntensity = 0.52f,
            ambientResponse = 0.52f,
            surfaceProfile = SurfaceProfile.Circle,
            interactive = interactive
        )
    }

    @Composable
    fun bottomNavigation(
        tint: Color,
        shape: RoundedCornerShape
    ): GlassStyle {
        return surface(
            tint = tint,
            shape = shape,
            edgeSoftness = 8.dp,
            specularIntensity = 0.68f,
            ambientResponse = 0.58f,
            surfaceProfile = SurfaceProfile.Lip,
            chromaticAberrationStrength = 0.10f
        )
    }

    @Composable
    fun bottomNavigationItem(
        tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.04f)
    ): GlassStyle {
        return surface(
            tint = tint,
            shape = RoundedCornerShape(24.dp),
            edgeSoftness = 4.dp,
            specularIntensity = 0.58f,
            ambientResponse = 0.54f,
            surfaceProfile = SurfaceProfile.Squircle,
            interactive = true
        )
    }
}
