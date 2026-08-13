@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSampling
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.hazeGlass

/** Glass 表面只负责裁剪、采样、边框和内容，不叠加拟态阴影。 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    role: GlassSurfaceRole,
    shape: RoundedCornerShape,
    sourceSelection: HazeSourceSelection = HazeSourceSelection.Behind,
    interactionSource: MutableInteractionSource? = null,
    scene: GlassScene = GlassScene(),
    content: @Composable () -> Unit
) {
    GlassSurfaceImpl(
        modifier = modifier,
        hazeState = hazeState,
        role = role,
        shape = shape,
        sourceSelection = sourceSelection,
        scene = scene,
        interactionSource = interactionSource,
        tint = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
        content = content
    )
}

/**
 * Glass 图标按钮。interactionSource 同时供 clickable 和 Haze Glass 交互层使用，
 * 从而保留按压反馈、禁用语义和稳定的交互状态。
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    hazeState: HazeState?,
    role: GlassSurfaceRole = GlassSurfaceRole.CircularControl,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    scene: GlassScene = GlassScene(),
    content: @Composable () -> Unit
) {
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    val buttonModifier = modifier
            .alpha(if (enabled) 1f else 0.55f)
            .clickable(
                interactionSource = resolvedInteractionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .size(size)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 0.35f else 0.20f)
    if (hazeState == null) {
        val token = glassToken(
            role = role,
            variant = com.tracktosearch.ui.theme.LocalGlassVariant.current,
            isDark = isAppDarkTheme(),
            scene = scene
        )
        Box(
            modifier = buttonModifier
                .clip(shape)
                .background(
                    resolveGlassFallbackFill(
                        backgroundColor = Color.Transparent,
                        themeSurface = MaterialTheme.colorScheme.surface,
                        tokenAlpha = token.tintAlpha
                    ),
                    shape
                )
                .border(1.dp, glassBorderColor(role, borderColor, scene), shape),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    } else {
        GlassSurfaceImpl(
            modifier = buttonModifier,
            hazeState = hazeState,
            role = role,
            shape = shape,
            sourceSelection = HazeSourceSelection.Behind,
            scene = scene,
            interactionSource = resolvedInteractionSource,
            tint = MaterialTheme.colorScheme.surface,
            borderColor = borderColor,
            content = content
        )
    }
}

/** 玻璃模式的底部导航选中态，只绘制普通半透明背景和边框，不采样背景。 */
@Composable
fun GlassTabIndicator(
    modifier: Modifier = Modifier,
    isDark: Boolean,
    shape: Shape = RoundedCornerShape(24.dp)
) {
    val backgroundColor = if (isDark) {
        Color.White.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    }
    val borderColor = if (isDark) {
        Color.White.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .backgroundWithoutSampling(backgroundColor, shape)
            .border(1.dp, borderColor, shape)
    )
}

@Composable
internal fun GlassSurfaceImpl(
    modifier: Modifier,
    hazeState: HazeState,
    role: GlassSurfaceRole,
    shape: RoundedCornerShape,
    sourceSelection: HazeSourceSelection,
    scene: GlassScene,
    interactionSource: MutableInteractionSource?,
    tint: Color,
    borderColor: Color,
    content: @Composable () -> Unit
) {
    val style = AppGlassStyles.style(
        role = role,
        shape = shape,
        tint = tint.takeIf { it.alpha > 0f } ?: MaterialTheme.colorScheme.surface,
        interactive = interactionSource != null,
        scene = scene
    )
    Box(
        modifier = modifier
            .clip(shape)
            .hazeGlass(
                input = HazeInput.Sources(
                    state = hazeState,
                    selection = sourceSelection
                ),
                style = style,
                sampling = HazeSampling.Default,
                interactionSource = interactionSource
            )
            .border(1.dp, glassBorderColor(role, borderColor, scene), shape),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun Modifier.backgroundWithoutSampling(
    color: Color,
    shape: Shape
): Modifier = this.background(color, shape)
