@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
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
            // 40dp 远高于 Haze 默认 24dp，模糊算子成本随半径上升；30dp 观感仍是明确的
            // 磨砂底栏，实测模糊子树占比可再降一档。
            hazeBlurRadius = 30.dp,
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
 * 列表快速滚动期间的绘制降级开关。
 *
 * 只由页面在「开始滚动/停止滚动」边沿更新，避免把每个 pointer delta 变成整页重组。
 * 卡片在该模式下跳过昂贵的拟态阴影，停止滚动后恢复完整视觉。
 */
val LocalFastScrollMode = staticCompositionLocalOf { false }

/**
 * 统一按用途和全局视觉模式分发表面。
 * Glass 通过 Backdrop 采样；Content 与 Modal 分支不采样、不复用拟态阴影。
 */
@Composable
fun AppVisualSurface(
    kind: VisualSurfaceKind,
    modifier: Modifier = Modifier,
    shape: Shape,
    hazeState: HazeState? = null,
    backdropOverride: Backdrop? = null,
    /** Glass 分支专用：把本表面的玻璃成品导出成一层，供子级控件对齐采样（如底栏选中水滴）。 */
    exportedBackdrop: LayerBackdrop? = null,
    role: GlassSurfaceRole = GlassSurfaceRole.TopBar,
    sourceSelection: HazeSourceSelection = HazeSourceSelection.Behind,
    interactionSource: MutableInteractionSource? = null,
    scene: GlassScene = GlassScene(),
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

        // 列表快速滚动时，内容卡片的拟态外阴影/内阴影会随每一帧移动重复绘制。
        // 保留裁剪、背景和边框，暂时降为轻量内容表面；停下后由 CompositionLocal 边沿恢复。
        kind == VisualSurfaceKind.Content && LocalFastScrollMode.current -> {
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(backgroundColor, shape)
                    .border(1.dp, borderColor, shape)
            ) {
                content()
            }
        }

        LocalVisualEffectMode.current == VisualEffectMode.GLASS && kind == VisualSurfaceKind.Glass -> {
            GlassSurfaceImpl(
                modifier = modifier,
                hazeState = hazeState,
                backdropOverride = backdropOverride,
                exportedBackdrop = exportedBackdrop,
                role = role,
                shape = requireRoundedGlassShape(shape, role),
                sourceSelection = sourceSelection,
                scene = scene,
                interactionSource = interactionSource,
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
                interactionSource = interactionSource,
                scene = scene,
                elevation = config.elevation,
                blurRadius = config.blurRadius,
                shadowOffset = config.shadowOffset,
                darkShadowAlpha = config.darkShadowAlpha,
                lightShadowAlpha = config.lightShadowAlpha,
                showHighlight = config.showHighlight,
                // 发现页卡片（Content）去掉外投影，仅保留内高光/边框；玻璃/顶栏等 Glass 表面保持原样
                castOuterShadow = kind != VisualSurfaceKind.Content,
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
    lightBorderAlpha: Float = 0.55f,
    scene: GlassScene = GlassScene(),
    buttonStyle: NeumorphicIconButtonStyle = NeumorphicIconButtonStyle.Default,
    content: @Composable () -> Unit
) {
    // 触感不在这里发。两条分支各自转给一个已经拥有可点面的组件（GlassIconButton 与
    // NeumorphicIconButton），那两处内部已经各发一记 LIGHT_TAP；这里再包一层就是双震。
    when (LocalVisualEffectMode.current) {
        VisualEffectMode.GLASS -> {
            GlassIconButton(
                onClick = onClick,
                modifier = modifier,
                size = size,
                hazeState = hazeState,
                role = role,
                interactionSource = interactionSource,
                enabled = enabled,
                scene = scene,
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
            lightBorderAlpha = lightBorderAlpha,
            scene = scene,
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
