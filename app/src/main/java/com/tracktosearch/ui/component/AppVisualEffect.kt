@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.component

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSampling
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/** 根据全局视觉模式选择成熟 Haze 模糊或 Backdrop Glass。 */
@Composable
fun Modifier.appVisualEffect(
    input: HazeInput,
    hazeStyle: HazeBlurStyle,
    glassRole: GlassSurfaceRole = GlassSurfaceRole.TopBar,
    glassShape: RoundedCornerShape = RoundedCornerShape(0.dp),
    glassTint: Color = Color.Transparent,
    backdropOverride: Backdrop? = null,
    scene: GlassScene = GlassScene(),
    glassEffectEnabled: Boolean = true,
    blurSampling: HazeSampling = HazeSampling.Adaptive,
    interactionSource: InteractionSource? = null
): Modifier {
    return when (LocalVisualEffectMode.current) {
        VisualEffectMode.BLUR -> hazeBlur(
            input = input,
            style = hazeStyle,
            sampling = blurSampling
        )

        VisualEffectMode.GLASS -> if (glassEffectEnabled) {
            then(
                backdropEffectModifier(
                    role = glassRole,
                    shape = glassShape,
                    tint = glassTint,
                    backdropOverride = backdropOverride,
                    scene = scene,
                    interactionSource = interactionSource
                )
            )
        } else {
            this
        }
    }
}

@Composable
fun Modifier.appVisualEffect(
    state: HazeState,
    hazeStyle: HazeBlurStyle,
    glassRole: GlassSurfaceRole = GlassSurfaceRole.TopBar,
    glassShape: RoundedCornerShape = RoundedCornerShape(0.dp),
    glassTint: Color = Color.Transparent,
    backdropOverride: Backdrop? = null,
    scene: GlassScene = GlassScene(),
    glassEffectEnabled: Boolean = true,
    blurSampling: HazeSampling = HazeSampling.Adaptive,
    interactionSource: InteractionSource? = null
): Modifier = appVisualEffect(
    input = HazeInput.Sources(state),
    hazeStyle = hazeStyle,
    glassRole = glassRole,
    glassShape = glassShape,
    glassTint = glassTint,
    backdropOverride = backdropOverride,
    scene = scene,
    glassEffectEnabled = glassEffectEnabled,
    blurSampling = blurSampling,
    interactionSource = interactionSource
)

@Composable
private fun backdropEffectModifier(
    role: GlassSurfaceRole,
    shape: RoundedCornerShape,
    tint: Color,
    backdropOverride: Backdrop?,
    scene: GlassScene,
    interactionSource: InteractionSource?
): Modifier {
    val backdrop = backdropOverride ?: LocalBackdrop.current ?: return Modifier
    return rememberBackdropGlassEffectModifier(
        backdrop = backdrop,
        role = role,
        shape = shape,
        tint = tint.takeIf { it.alpha > 0f } ?: MaterialTheme.colorScheme.surface,
        scene = scene,
        interactionSource = interactionSource
    )
}
