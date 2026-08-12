@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.glass.GlassStyle
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode

enum class VisualSurfaceKind {
    Glass,
    Content,
    Modal
}

/**
 * 统一按用途和全局视觉模式分发表面。
 * Glass 内容与 Modal 分支不采样、不复用拟态阴影。
 */
@Composable
fun AppVisualSurface(
    kind: VisualSurfaceKind,
    modifier: Modifier = Modifier,
    isDark: Boolean,
    shape: RoundedCornerShape,
    hazeState: HazeState? = null,
    glassRole: GlassSurfaceRole = GlassSurfaceRole.TopBar,
    sourceSelection: HazeSourceSelection = HazeSourceSelection.Behind,
    backgroundColor: Color,
    borderColor: Color,
    elevation: Dp = 14.dp,
    blurRadius: Dp? = null,
    shadowOffset: Dp? = null,
    hazeStyle: HazeBlurStyle? = null,
    glassStyle: GlassStyle? = null,
    hazeBlurRadius: Dp? = null,
    darkShadowAlpha: Float? = null,
    lightShadowAlpha: Float? = null,
    showHighlight: Boolean = true,
    content: @Composable () -> Unit
) {
    when {
        kind == VisualSurfaceKind.Modal -> ModalSurface(
            modifier = modifier,
            shape = shape,
            borderColor = borderColor,
            content = content
        )

        LocalVisualEffectMode.current == VisualEffectMode.GLASS && kind == VisualSurfaceKind.Glass -> {
            val state = hazeState ?: remember { HazeState() }
            GlassSurfaceImpl(
                modifier = modifier,
                hazeState = state,
                role = glassRole,
                shape = shape,
                sourceSelection = sourceSelection,
                interactionSource = null,
                tint = backgroundColor,
                borderColor = borderColor,
                content = content
            )
        }

        LocalVisualEffectMode.current == VisualEffectMode.GLASS -> PlainGlassSurface(
            modifier = modifier,
            shape = shape,
            backgroundColor = backgroundColor,
            borderColor = borderColor,
            content = content
        )

        else -> NeumorphicFrostedSurface(
            modifier = modifier,
            isDark = isDark,
            shape = shape,
            backgroundColor = backgroundColor,
            borderColor = borderColor,
            hazeState = hazeState,
            sourceSelection = sourceSelection,
            hazeStyle = hazeStyle,
            glassStyle = glassStyle,
            hazeBlurRadius = hazeBlurRadius,
            elevation = elevation,
            blurRadius = blurRadius,
            shadowOffset = shadowOffset,
            darkShadowAlpha = darkShadowAlpha ?: if (isDark) 0.5f else 0.12f,
            lightShadowAlpha = lightShadowAlpha ?: if (isDark) 0.10f else 0.85f,
            showHighlight = showHighlight,
            content = content
        )
    }
}

/** Glass/Blur 分支的图标按钮入口。 */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    hazeState: HazeState? = null,
    role: GlassSurfaceRole = GlassSurfaceRole.CircularControl,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    buttonStyle: NeumorphicIconButtonStyle = NeumorphicIconButtonStyle.Default,
    content: @Composable () -> Unit
) {
    when (LocalVisualEffectMode.current) {
        VisualEffectMode.GLASS -> {
            val state = hazeState ?: remember { HazeState() }
            GlassIconButton(
                onClick = onClick,
                modifier = modifier,
                size = size,
                hazeState = state,
                role = role,
                interactionSource = interactionSource,
                enabled = enabled,
                content = content
            )
        }

        VisualEffectMode.BLUR -> NeumorphicIconButton(
            onClick = onClick,
            isDark = isDark,
            modifier = modifier,
            size = size,
            hazeState = hazeState,
            interactionSource = interactionSource,
            enabled = enabled,
            buttonStyle = buttonStyle,
            content = content
        )
    }
}

@Composable
private fun PlainGlassSurface(
    modifier: Modifier,
    shape: RoundedCornerShape,
    backgroundColor: Color,
    borderColor: Color,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor, shape)
            .border(1.dp, borderColor, shape),
    ) {
        content()
    }
}

@Composable
private fun ModalSurface(
    modifier: Modifier,
    shape: RoundedCornerShape,
    borderColor: Color,
    content: @Composable () -> Unit
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .clip(shape)
            .background(surface, shape)
            .border(1.dp, borderColor, shape),
    ) {
        content()
    }
}
