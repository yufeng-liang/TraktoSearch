@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.component

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSampling
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.GlassReducedMotionPolicy
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.GlassTransformPivot
import dev.chrisbanes.haze.glass.GlassTransformTarget
import dev.chrisbanes.haze.glass.hazeGlass
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode

/** 根据全局视觉模式选择 Haze 模糊或官方 Glass 光学效果。 */
@Composable
fun Modifier.appVisualEffect(
    input: HazeInput,
    hazeStyle: HazeBlurStyle,
    glassStyle: GlassStyle? = null,
    blurSampling: HazeSampling = HazeSampling.Adaptive,
    interactionSource: InteractionSource? = null,
    interactionTransformTarget: GlassTransformTarget = GlassTransformTarget.MaterialAndContent,
    interactionTransformPivot: GlassTransformPivot = GlassTransformPivot.Pointer
): Modifier {
    return when (LocalVisualEffectMode.current) {
        VisualEffectMode.BLUR -> hazeBlur(
            input = input,
            style = hazeStyle,
            sampling = blurSampling
        )

        VisualEffectMode.GLASS -> hazeGlass(
            input = input,
            style = glassStyle ?: AppGlassStyles.topBar(),
            // Glass 统一使用 alpha04 的默认采样；Blur 才接受调用点的降采样策略。
            sampling = HazeSampling.Default,
            interactionSource = interactionSource,
            interactionTransformTarget = interactionTransformTarget,
            interactionTransformPivot = interactionTransformPivot,
            interactionReducedMotionPolicy = GlassReducedMotionPolicy.System
        )
    }
}

@Composable
fun Modifier.appVisualEffect(
    state: HazeState,
    hazeStyle: HazeBlurStyle,
    glassStyle: GlassStyle? = null,
    blurSampling: HazeSampling = HazeSampling.Adaptive,
    interactionSource: InteractionSource? = null,
    interactionTransformTarget: GlassTransformTarget = GlassTransformTarget.MaterialAndContent,
    interactionTransformPivot: GlassTransformPivot = GlassTransformPivot.Pointer
): Modifier = appVisualEffect(
    input = HazeInput.Sources(state),
    hazeStyle = hazeStyle,
    glassStyle = glassStyle,
    blurSampling = blurSampling,
    interactionSource = interactionSource,
    interactionTransformTarget = interactionTransformTarget,
    interactionTransformPivot = interactionTransformPivot
)
