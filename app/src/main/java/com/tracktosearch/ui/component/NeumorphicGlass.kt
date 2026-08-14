@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSampling
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.glass.GlassStyle
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode

/** 初始位置保持沉浸透明，列表发生位移后启用 Haze。 */
internal fun hasListScrolled(
    firstVisibleItemIndex: Int?,
    firstVisibleItemScrollOffsetPx: Int?
): Boolean {
    return firstVisibleItemIndex != null && firstVisibleItemScrollOffsetPx != null &&
        (firstVisibleItemIndex > 0 || firstVisibleItemScrollOffsetPx > 0)
}

@Composable
fun Modifier.hazeTopBar(
    state: HazeState,
    style: HazeBlurStyle = HazeMaterials.thin(),
    blurRadius: Dp = 24.dp,
    isContentUnderTopBar: Boolean = true,
    scene: GlassScene = GlassScene()
): Modifier {
    if (!isContentUnderTopBar) return this
    // 将调用方传入的 blurRadius 实际写入 HazeBlurStyle，避免参数失效
    val resolvedStyle = style.then { blurRadius(blurRadius) }
    return appVisualEffect(
        input = HazeInput.Sources(state),
        hazeStyle = resolvedStyle,
        glassStyle = AppGlassStyles.topBar(scene = scene),
        // 渲染降采样：Haze 官方基准显示可降低 5-20% 开销，肉眼几乎不可见
        blurSampling = HazeSampling.Adaptive
    )
}

/**
 * C方案拟态外阴影：dropShadow 画右下暗投影（位于组件外部，不受clip影响）
 */
fun Modifier.neumorphicOuterShadow(
    shape: Shape,
    isDark: Boolean,
    elevation: Dp,
    darkAlpha: Float,
    blurRadius: Dp? = null,
    shadowOffset: Dp? = null,
    darkColor: Color? = null
): Modifier {
    val blur = blurRadius ?: elevation
    val offset = shadowOffset ?: (elevation * 0.7f)
    val darkShadowColor = (darkColor ?: if (isDark) Color.Black else Color(0xFF6478B4))
        .copy(alpha = darkAlpha)
    return this.dropShadow(
        shape = shape,
        shadow = Shadow(
            radius = blur,
            color = darkShadowColor,
            offset = DpOffset(offset, offset)
        )
    )
}

/**
 * C方案拟态内阴影：innerShadow 画左上高光（位于组件内部，需在clip之后）
 */
fun Modifier.neumorphicInnerShadow(
    shape: Shape,
    isDark: Boolean,
    elevation: Dp,
    lightAlpha: Float,
    blurRadius: Dp? = null,
    shadowOffset: Dp? = null,
    lightColor: Color? = null
): Modifier {
    val blur = blurRadius ?: elevation
    val offset = shadowOffset ?: (elevation * 0.7f)
    val lightShadowColor = (lightColor ?: Color.White).copy(alpha = lightAlpha)
    return this.innerShadow(
        shape = shape,
        shadow = Shadow(
            radius = blur * 0.35f,
            color = lightShadowColor,
            offset = DpOffset(-offset * 0.6f, -offset * 0.6f)
        )
    )
}

/**
 * C方案拟态双向阴影：dropShadow 画右下暗阴影 + innerShadow 画左上高光
 */
fun Modifier.neumorphicShadow(
    shape: Shape,
    isDark: Boolean,
    elevation: Dp,
    darkAlpha: Float,
    lightAlpha: Float,
    blurRadius: Dp? = null,
    shadowOffset: Dp? = null,
    darkColor: Color? = null,
    lightColor: Color? = null
): Modifier {
    return this
        .neumorphicOuterShadow(
            shape = shape,
            isDark = isDark,
            elevation = elevation,
            darkAlpha = darkAlpha,
            blurRadius = blurRadius,
            shadowOffset = shadowOffset,
            darkColor = darkColor
        )
        .neumorphicInnerShadow(
            shape = shape,
            isDark = isDark,
            elevation = elevation,
            lightAlpha = lightAlpha,
            blurRadius = blurRadius,
            shadowOffset = shadowOffset,
            lightColor = lightColor
        )
}

/**
 * 顶部玻璃高光渐变（C方案 ::before 效果）
 */
@Composable
fun BoxScope.GlassHighlight(
    isDark: Boolean,
    shape: Shape = RoundedCornerShape(32.dp),
    alphaScale: Float = 1f
) {
    val resolvedScale = alphaScale.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = (if (isDark) 0.08f else 0.60f) * resolvedScale),
                    0.25f to Color.White.copy(alpha = (if (isDark) 0.03f else 0.25f) * resolvedScale),
                    0.50f to Color.White.copy(alpha = (if (isDark) 0.01f else 0.08f) * resolvedScale),
                    1.0f to Color.Transparent
                )
            )
    )
}

enum class NeumorphicIconButtonStyle {
    Default,
    DetailTopBar
}

/** 详情页顶部图标色：保持主题色相，适度提亮并增加饱和度。 */
@Composable
fun detailTopBarIconColor(): Color {
    val hsl = FloatArray(3)
    val primary = MaterialTheme.colorScheme.primary
    ColorUtils.colorToHSL(primary.toArgb(), hsl)
    return Color.hsl(
        hue = hsl[0],
        saturation = (hsl[1] * 1.12f).coerceAtMost(1f),
        lightness = (hsl[2] + 0.06f).coerceAtMost(0.94f),
        alpha = primary.alpha
    )
}

/**
 * C方案底部导航选中项：凹陷药丸，内阴影暗部 + 左上高光
 */
@Composable
fun NeumorphicActiveTab(
    modifier: Modifier = Modifier,
    isDark: Boolean,
    shape: Shape = RoundedCornerShape(24.dp)
) {
    if (LocalVisualEffectMode.current == VisualEffectMode.GLASS) {
        GlassTabIndicator(
            modifier = modifier,
            isDark = isDark,
            shape = shape
        )
        return
    }

    val darkColor = if (isDark) {
        Color.Black.copy(alpha = 0.25f)
    } else {
        Color(0xFF6478B4).copy(alpha = 0.15f)
    }
    val lightColor = Color.White.copy(alpha = if (isDark) 0.06f else 0.90f)
    val backgroundModifier = if (isDark) {
        Modifier.background(Color(0xFF3949AB).copy(alpha = 0.18f), shape)
    } else {
        Modifier.background(
            Brush.linearGradient(
                0.0f to Color(0xFFDCE4FF).copy(alpha = 0.65f),
                1.0f to Color.White.copy(alpha = 0.65f)
            ),
            shape
        )
    }
    Box(
        modifier = modifier
            .innerShadow(
                shape = shape,
                shadow = Shadow(
                    radius = 7.dp,
                    color = darkColor,
                    offset = DpOffset(3.dp, 3.dp)
                )
            )
            .innerShadow(
                shape = shape,
                shadow = Shadow(
                    radius = 7.dp,
                    color = lightColor,
                    offset = DpOffset((-3).dp, (-3).dp)
                )
            )
            .clip(shape)
            .then(backgroundModifier)
    )
}

/**
 * C方案拟态毛玻璃表面：外阴影 + clip + 毛玻璃 + 内阴影 + 顶部高光
 * 注意：clip 必须在 hazeBlur 之前，否则毛玻璃效果是矩形的，不会被裁成圆角
 */
@Composable
fun NeumorphicFrostedSurface(
    modifier: Modifier = Modifier,
    isDark: Boolean,
    shape: Shape = RoundedCornerShape(32.dp),
    elevation: Dp = 14.dp,
    blurRadius: Dp? = null,
    shadowOffset: Dp? = null,
    backgroundColor: Color = if (isDark) Color(0xFF1E1E3A).copy(alpha = 0.65f) else Color.White.copy(alpha = 0.70f),
    borderColor: Color = if (isDark) Color.White.copy(alpha = 0.15f) else Color(0xFFE0E5EC).copy(alpha = 0.9f),
    darkShadowAlpha: Float = if (isDark) 0.5f else 0.12f,
    lightShadowAlpha: Float = if (isDark) 0.10f else 0.85f,
    hazeState: HazeState? = null,
    hazeStyle: HazeBlurStyle? = null,
    glassStyle: GlassStyle? = null,
    hazeBlurRadius: Dp? = null,
    showHighlight: Boolean = true,
    // 可选：过滤参与模糊的源区域。底部导航等"自身既作 source 又作 effect"的场景
    // 应传入 Behind.where { source -> source.zIndex < 自身 zIndex } 排除自采样。
    sourceSelection: HazeSourceSelection = HazeSourceSelection.Behind,
    // 旧页面默认只保留普通表面；需要 Glass 采样时由页面显式声明语义角色。
    glassRole: GlassSurfaceRole? = null,
    interactionSource: MutableInteractionSource? = null,
    scene: GlassScene = GlassScene(),
    content: @Composable () -> Unit
) {
    if (LocalVisualEffectMode.current == VisualEffectMode.GLASS) {
        val roundedShape = if (glassRole != null) {
            shape as? RoundedCornerShape
                ?: error("Glass role $glassRole requires RoundedCornerShape")
        } else {
            null
        }
        if (hazeState != null && glassRole != null) {
            GlassSurfaceImpl(
                modifier = modifier,
                hazeState = hazeState,
                role = glassRole,
                shape = roundedShape!!,
                sourceSelection = sourceSelection,
                scene = scene,
                interactionSource = interactionSource,
                tint = backgroundColor,
                borderColor = borderColor,
                content = content
            )
        } else {
            val fallbackToken = if (glassRole != null) {
                glassToken(
                    role = glassRole,
                    variant = com.tracktosearch.ui.theme.LocalGlassVariant.current,
                    isDark = isDark,
                    scene = scene
                )
            } else {
                null
            }
            val fallbackBackground = if (glassRole != null && fallbackToken != null) {
                resolveGlassFallbackFill(
                    backgroundColor = backgroundColor,
                    themeSurface = MaterialTheme.colorScheme.surface,
                    tokenAlpha = fallbackToken.tintAlpha,
                    ambientColor = resolveGlassAmbientColor(
                        sceneAmbient = scene.ambientColor,
                        themeBackground = MaterialTheme.colorScheme.background
                    ),
                    environmentTintStrength = fallbackToken.environmentTintStrength
                )
            } else {
                backgroundColor
            }
            val fallbackBorder = if (glassRole != null) {
                glassBorderColor(glassRole, borderColor, scene)
            } else {
                borderColor
            }
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(fallbackBackground, shape)
                    .border(1.dp, fallbackBorder, shape),
            ) {
                content()
            }
        }
        return
    }

    val resolvedHazeStyle = hazeStyle ?: HazeMaterials.thin()
    val hazeModifier = if (hazeState != null) {
        Modifier.appVisualEffect(
            input = HazeInput.Sources(
                state = hazeState,
                selection = sourceSelection
            ),
            hazeStyle = resolvedHazeStyle,
            glassStyle = glassStyle,
            blurSampling = HazeSampling.Adaptive,
            interactionSource = interactionSource
        )
    } else Modifier

    Box(
        modifier = modifier
            .neumorphicOuterShadow(
                shape = shape,
                isDark = isDark,
                elevation = elevation,
                darkAlpha = darkShadowAlpha,
                blurRadius = blurRadius,
                shadowOffset = shadowOffset
            )
            .clip(shape)
            .then(hazeModifier)
            .neumorphicInnerShadow(
                shape = shape,
                isDark = isDark,
                elevation = elevation,
                lightAlpha = lightShadowAlpha,
                blurRadius = blurRadius,
                shadowOffset = shadowOffset
            )
            .background(backgroundColor, shape)
            .border(1.dp, borderColor, shape)
    ) {
        if (showHighlight) {
            GlassHighlight(isDark = isDark, shape = shape)
        }
        content()
    }
}

/**
 * C方案拟态玻璃圆形图标按钮：外阴影 + 内高光 + 毛玻璃底色
 */
@Composable
fun NeumorphicIconButton(
    onClick: () -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    hazeState: HazeState? = null,
    hazeStyle: HazeBlurStyle? = null,
    glassStyle: GlassStyle? = null,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    lightBorderAlpha: Float = 0.55f,
    scene: GlassScene = GlassScene(),
    buttonStyle: NeumorphicIconButtonStyle = NeumorphicIconButtonStyle.Default,
    content: @Composable () -> Unit
) {
    if (LocalVisualEffectMode.current == VisualEffectMode.GLASS) {
        GlassIconButton(
            onClick = onClick,
            modifier = modifier,
            size = size,
            hazeState = hazeState,
            role = if (buttonStyle == NeumorphicIconButtonStyle.DetailTopBar) {
                GlassSurfaceRole.DetailAction
            } else {
                GlassSurfaceRole.CircularControl
            },
            interactionSource = interactionSource,
            enabled = enabled,
            scene = scene,
            content = content
        )
        return
    }

    val isDetailTopBar = buttonStyle == NeumorphicIconButtonStyle.DetailTopBar
    val shape = CircleShape
    val resolvedHazeStyle = hazeStyle ?: HazeMaterials.thin()
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val hazeModifier = if (hazeState != null) {
        Modifier.appVisualEffect(
            input = HazeInput.Sources(hazeState),
            hazeStyle = resolvedHazeStyle,
            glassStyle = glassStyle ?: AppGlassStyles.circularControl(
                tint = MaterialTheme.colorScheme.surface.copy(
                    alpha = if (isDetailTopBar) 0.10f else 0.18f
                ),
                interactive = enabled,
                scene = scene
            ),
            blurSampling = HazeSampling.Adaptive,
            interactionSource = resolvedInteractionSource
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.55f)
            .size(size)
            .neumorphicOuterShadow(
                shape = shape,
                isDark = isDark,
                elevation = 5.dp,
                darkAlpha = if (isDark) 0.35f else 0.12f,
                blurRadius = 12.dp,
                shadowOffset = 5.dp
            )
            .clip(shape)
            .then(hazeModifier)
            .background(Color.Transparent, shape)
            .neumorphicInnerShadow(
                shape = shape,
                isDark = isDark,
                elevation = 4.dp,
                lightAlpha = if (isDark) {
                    if (isDetailTopBar) 0.04f else 0.08f
                } else {
                    if (isDetailTopBar) 0.33f else 0.65f
                },
                blurRadius = 10.dp,
                shadowOffset = 4.dp
            )
            .border(
                width = 1.dp,
                color = if (isDark) {
                    Color.White.copy(alpha = if (isDetailTopBar) 0.06f else 0.12f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = if (isDetailTopBar) lightBorderAlpha * 0.5f else lightBorderAlpha)
                },
                shape = shape
            )
            .clickable(
                interactionSource = resolvedInteractionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        GlassHighlight(
            isDark = isDark,
            shape = shape,
            alphaScale = if (isDetailTopBar) 0.5f else 1f
        )
        content()
    }
}

/**
 * 基于 MaterialTheme.colorScheme.background 亮度判断暗色模式
 */
@Composable
fun isAppDarkTheme(): Boolean {
    return MaterialTheme.colorScheme.background.luminance() < 0.5f
}
