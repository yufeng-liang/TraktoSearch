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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode

enum class VisualSurfaceKind {
    Glass,
    Content,
    Modal
}

/** Blur 分支的有限内部配置，页面只能选择语义角色。 */
internal data class BlurSurfaceConfig(
    val elevation: Dp,
    val blurRadius: Dp?,
    val shadowOffset: Dp?,
    val hazeStyle: HazeBlurStyle,
    val hazeBlurRadius: Dp?,
    val darkShadowAlpha: Float,
    val lightShadowAlpha: Float,
    val showHighlight: Boolean
)

@Composable
internal fun blurSurfaceConfig(
    role: GlassSurfaceRole,
    isDark: Boolean
): BlurSurfaceConfig {
    return when (role) {
        GlassSurfaceRole.BottomNavigation -> BlurSurfaceConfig(
            elevation = 8.dp,
            blurRadius = 22.dp,
            shadowOffset = 6.dp,
            hazeStyle = HazeMaterials.thin(
                MaterialTheme.colorScheme.surface.copy(
                    alpha = if (isDark) 0.08f else 0.03f
                )
            ),
            hazeBlurRadius = 40.dp,
            darkShadowAlpha = if (isDark) 0.38f else 0.16f,
            lightShadowAlpha = 0f,
            showHighlight = false
        )

        else -> BlurSurfaceConfig(
            elevation = 14.dp,
            blurRadius = null,
            shadowOffset = null,
            hazeStyle = HazeMaterials.thin(),
            hazeBlurRadius = null,
            darkShadowAlpha = if (isDark) 0.5f else 0.12f,
            lightShadowAlpha = if (isDark) 0.10f else 0.85f,
            showHighlight = true
        )
    }
}

/**
 * 统一按用途和全局视觉模式分发表面。
 * Glass 内容与 Modal 分支不采样、不复用拟态阴影。
 */
@Composable
fun AppVisualSurface(
    kind: VisualSurfaceKind,
    modifier: Modifier = Modifier,
    shape: Shape,
    hazeState: HazeState? = null,
    role: GlassSurfaceRole = GlassSurfaceRole.TopBar,
    sourceSelection: HazeSourceSelection = HazeSourceSelection.Behind,
    backgroundColor: Color,
    borderColor: Color,
    content: @Composable () -> Unit
) {
    val isDark = isAppDarkTheme()
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
                role = role,
                shape = requireRoundedGlassShape(shape, role),
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

        else -> {
            val config = blurSurfaceConfig(role = role, isDark = isDark)
            NeumorphicFrostedSurface(
                modifier = modifier,
                isDark = isDark,
                shape = shape,
                backgroundColor = backgroundColor,
                borderColor = borderColor,
                hazeState = hazeState,
                sourceSelection = sourceSelection,
                hazeStyle = config.hazeStyle,
                hazeBlurRadius = config.hazeBlurRadius,
                elevation = config.elevation,
                blurRadius = config.blurRadius,
                shadowOffset = config.shadowOffset,
                darkShadowAlpha = config.darkShadowAlpha,
                lightShadowAlpha = config.lightShadowAlpha,
                showHighlight = config.showHighlight,
                content = content
            )
        }
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
    shape: Shape,
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
    shape: Shape,
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

private fun requireRoundedGlassShape(
    shape: Shape,
    role: GlassSurfaceRole
): RoundedCornerShape {
    require(shape is RoundedCornerShape) {
        "Glass surface role $role requires RoundedCornerShape, " +
            "but received ${shape::class.simpleName}."
    }
    return shape
}
