package com.tracktosearch.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow as ComposeShadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.tracktosearch.ui.theme.LocalGlassVariant
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.materials.HazeMaterials

/** 选中水滴切换时收缩、放大，再回到常规体积。 */
@Composable
internal fun rememberGlassSelectionBounceScale(selectionKey: Any): Float {
    val scale = remember { Animatable(1f) }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(selectionKey) {
        if (!initialized) {
            initialized = true
            return@LaunchedEffect
        }
        scale.snapTo(0.94f)
        scale.animateTo(
            targetValue = 1.06f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }
    return scale.value
}

/** 悬浮 Glass 控件：右下主阴影承载高度，左上弱高光分离边缘层次。 */
internal fun Modifier.floatingGlassControlShadow(
    shape: Shape,
    isDark: Boolean
): Modifier {
    return this
        .dropShadow(
            shape = shape,
            shadow = ComposeShadow(
                radius = if (isDark) 10.2.dp else 8.5.dp,
                color = Color.Black.copy(alpha = if (isDark) 0.42f else 0.17f),
                offset = DpOffset(5.dp, 7.dp)
            )
        )
        .dropShadow(
            shape = shape,
            shadow = ComposeShadow(
                radius = if (isDark) 5.dp else 6.dp,
                color = Color.White.copy(alpha = if (isDark) 0.04f else 0.34f),
                offset = DpOffset((-2).dp, (-3).dp)
            )
        )
}

/** Glass 表面只负责 Backdrop 采样、边框和内容，不叠加拟态阴影。 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
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
 * Glass 图标按钮。交互源只控制内容层的按压反馈，Backdrop 外壳的尺寸和采样坐标保持稳定。
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    hazeState: HazeState? = null,
    hazeStyle: HazeBlurStyle? = null,
    role: GlassSurfaceRole = GlassSurfaceRole.CircularControl,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    scene: GlassScene = GlassScene(),
    content: @Composable () -> Unit
) {
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by resolvedInteractionSource.collectIsPressedAsState()
    val isDark = isAppDarkTheme()
    val token = backdropGlassToken(
        role = role,
        variant = LocalGlassVariant.current,
        isDark = isDark,
        scene = scene
    )
    val contentScale by animateFloatAsState(
        targetValue = if (pressed) token.pressScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "backdrop_${role.name}_press_scale"
    )
    val shape = RoundedCornerShape(50)
    val borderColor = if (isDark) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.30f else 0.16f)
    } else {
        Color.White.copy(alpha = if (enabled) 0.92f else 0.55f)
    }
    val surfaceColor = backdropSurfaceColor(
        tint = MaterialTheme.colorScheme.surface,
        token = token,
        scene = scene
    )
    val floatingShadow = if (role == GlassSurfaceRole.CircularControl || role == GlassSurfaceRole.DetailAction) {
        Modifier.floatingGlassControlShadow(shape, isDark)
    } else {
        Modifier
    }
    val backdrop = LocalBackdrop.current
    val resolvedHazeStyle = hazeStyle ?: HazeMaterials.thin()
    // Glass 采样源：优先 Backdrop 镜头光效；无 Backdrop 源且可实时采 Haze 时退化
    // 为 Haze 实时模糊（悬浮按钮采不到滚动内容导致白底/透明的饱和度场景用此降级）。
    val effectModifier = when {
        backdrop != null -> Modifier.backdropGlass(
            backdrop = backdrop,
            shape = shape,
            token = token,
            surfaceColor = surfaceColor,
            isDark = isDark,
            pressed = pressed
        )
        hazeState != null -> Modifier.appVisualEffect(
            input = HazeInput.Sources(hazeState),
            hazeStyle = resolvedHazeStyle,
            glassRole = role,
            glassShape = shape,
            glassTint = surfaceColor,
            scene = scene,
            blurPerformanceMode = HazePerformanceMode.Adaptive,
            interactionSource = resolvedInteractionSource
        )
        else -> Modifier.background(surfaceColor, shape)
    }
    val surfaceModifier = modifier
        .alpha(if (enabled) 1f else 0.55f)
        .size(size)
        .then(floatingShadow)
        .clip(shape)
        .then(effectModifier)
        .border(1.dp, backdropBorderColor(borderColor, token), shape)
        .clickable(
            interactionSource = resolvedInteractionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )

    Box(
        modifier = surfaceModifier,
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.scale(contentScale), contentAlignment = Alignment.Center) {
            content()
        }
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
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    }
    val borderColor = if (isDark) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor, shape)
            .border(1.dp, borderColor, shape)
    )
}

/** 底栏专用的选中水滴，显式采样主内容 source，避免把外层导航录回 source。 */
@Composable
fun GlassNavigationTabIndicator(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    isDark: Boolean,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    scene: GlassScene = GlassScene()
) {
    val token = backdropNavigationSelectionToken(
        variant = LocalGlassVariant.current,
        isDark = isDark,
        scene = scene
    )
    val surfaceColor = backdropSurfaceColor(
        tint = MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.76f else 0.70f),
        token = token,
        scene = scene
    )
    Box(
        modifier = modifier
            .clip(shape)
            .backdropGlass(
                backdrop = backdrop,
                shape = shape,
                token = token,
                surfaceColor = surfaceColor,
                isDark = isDark,
                pressed = false
            )
            .strongGlassSelectionBorder(
                shape = shape,
                token = token,
                isDark = isDark,
                primaryColor = MaterialTheme.colorScheme.primary,
                borderWidth = if (isDark) 1.8.dp else 2.4.dp
            )
    )
}

/** 统一的 Backdrop surface，旧的 hazeState/sourceSelection 参数仅为 Blur 兼容保留且不参与 Glass 采样。 */
@Composable
internal fun GlassSurfaceImpl(
    modifier: Modifier,
    hazeState: HazeState? = null,
    backdropOverride: Backdrop? = null,
    role: GlassSurfaceRole,
    shape: RoundedCornerShape,
    sourceSelection: HazeSourceSelection = HazeSourceSelection.Behind,
    scene: GlassScene,
    interactionSource: MutableInteractionSource?,
    tint: Color,
    borderColor: Color,
    useNavigationSelectionStyle: Boolean = false,
    selectionBorderWidth: Dp? = null,
    showGlassBorder: Boolean = true,
    showSpecularHighlight: Boolean = false,
    specularHighlightAlphaScale: Float = 1f,
    content: @Composable () -> Unit
) {
    @Suppress("UNUSED_VARIABLE")
    val compatibilityHazeState = hazeState
    @Suppress("UNUSED_VARIABLE")
    val compatibilitySourceSelection = sourceSelection
    val isDark = isAppDarkTheme()
    val token = if (useNavigationSelectionStyle) {
        backdropNavigationSelectionToken(
            variant = LocalGlassVariant.current,
            isDark = isDark,
            scene = scene
        )
    } else {
        backdropGlassToken(
            role = role,
            variant = LocalGlassVariant.current,
            isDark = isDark,
            scene = scene
        )
    }
    val pressed by (interactionSource ?: remember { MutableInteractionSource() })
        .collectIsPressedAsState()
    val contentScale by animateFloatAsState(
        targetValue = if (pressed && interactionSource != null) token.pressScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "backdrop_${role.name}_surface_press_scale"
    )
    val resolvedTint = if (useNavigationSelectionStyle) {
        backdropSurfaceColor(
            tint = MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.76f else 0.70f),
            token = token,
            scene = scene
        )
    } else {
        backdropSurfaceColor(tint, token, scene)
    }
    val resolvedBorder = backdropBorderColor(borderColor, token)
    val backdrop = backdropOverride ?: LocalBackdrop.current
    val surfaceModifier = modifier
        .clip(shape)
        .then(
            if (backdrop != null) {
                Modifier.backdropGlass(
                    backdrop = backdrop,
                    shape = shape,
                    token = token,
                    surfaceColor = resolvedTint,
                    isDark = isDark,
                    pressed = pressed,
                    highlightScale = if (showSpecularHighlight) {
                        specularHighlightAlphaScale
                    } else {
                        1f
                    }
                )
            } else {
                Modifier.background(resolvedTint, shape)
            }
        )
        .then(
            if (useNavigationSelectionStyle) {
                Modifier.strongGlassSelectionBorder(
                    shape = shape,
                    token = token,
                    isDark = isDark,
                    primaryColor = MaterialTheme.colorScheme.primary,
                    borderWidth = selectionBorderWidth
                )
            } else if (showGlassBorder) {
                Modifier.border(1.dp, resolvedBorder, shape)
            } else {
                Modifier
            }
        )

    Box(
        modifier = surfaceModifier,
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.scale(contentScale), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/** 与底栏选中水滴一致的强调边缘，深色避免整圈纯白描边。 */
private fun Modifier.strongGlassSelectionBorder(
    shape: RoundedCornerShape,
    token: BackdropGlassToken,
    isDark: Boolean,
    primaryColor: Color,
    borderWidth: Dp? = null
): Modifier {
    return if (isDark) {
        val edgeAlpha = token.borderAlpha * 0.70f
        val edgeBrush = Brush.linearGradient(
            colors = listOf(
                primaryColor.copy(alpha = edgeAlpha),
                primaryColor.copy(alpha = edgeAlpha * 0.48f),
                primaryColor.copy(alpha = edgeAlpha * 0.10f),
                primaryColor.copy(alpha = edgeAlpha * 0.30f)
            )
        )
        border(borderWidth ?: 0.8.dp, edgeBrush, shape)
    } else {
        border(borderWidth ?: 1.2.dp, backdropBorderColor(primaryColor, token), shape)
    }
}

private fun backdropSurfaceColor(
    tint: Color,
    token: BackdropGlassToken,
    scene: GlassScene
): Color {
    val base = tint.takeIf { it.alpha > 0f } ?: Color.White
    val environment = resolveGlassAmbientColor(
        sceneAmbient = scene.ambientColor,
        themeBackground = base
    )
    return resolveGlassEnvironmentTint(
        tint = base,
        ambientColor = environment,
        strength = 0.10f
    ).copy(alpha = (base.alpha * token.tintAlpha).coerceIn(0f, 1f))
}

private fun backdropBorderColor(color: Color, token: BackdropGlassToken): Color {
    return color.copy(alpha = (color.alpha * token.borderAlpha).coerceIn(0f, 1f))
}

internal fun Modifier.backdropGlass(
    backdrop: Backdrop,
    shape: RoundedCornerShape,
    token: BackdropGlassToken,
    surfaceColor: Color,
    isDark: Boolean,
    pressed: Boolean,
    highlightScale: Float = 1f
): Modifier {
    val highlightAlpha = (
        token.highlightAlpha * highlightScale * if (pressed) token.pressLighting else 1f
        ).coerceIn(0f, 1f)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (token.chromaticAberration) {
                vibrancy()
            } else {
                colorControls(contrast = 1.03f, saturation = 1.05f)
            }
            blur(
                radius = token.blurRadius.toPx(),
                edgeTreatment = token.blurEdgeTreatment
            )
            lens(
                refractionHeight = token.refractionHeight.toPx(),
                refractionAmount = token.refractionAmount.toPx(),
                depthEffect = token.depthEffect,
                chromaticAberration = token.chromaticAberration
            )
        },
        highlight = {
            Highlight(
                width = token.highlightWidth,
                alpha = highlightAlpha,
                style = HighlightStyle.Default
            )
        },
        shadow = {
            Shadow(
                radius = token.shadowRadius,
                offset = DpOffset(0.dp, token.shadowRadius / 4f),
                color = Color.Black.copy(alpha = token.shadowAlpha)
            )
        },
        innerShadow = {
            InnerShadow(
                radius = token.innerShadowRadius,
                offset = DpOffset(0.dp, -token.innerShadowRadius / 4f),
                color = Color.White.copy(alpha = if (isDark) 0.05f else 0.28f)
            )
        },
        onDrawSurface = {
            drawRect(surfaceColor)
        }
    )
}

@Composable
internal fun rememberBackdropGlassEffectModifier(
    backdrop: Backdrop,
    role: GlassSurfaceRole,
    shape: RoundedCornerShape,
    tint: Color,
    scene: GlassScene,
    interactionSource: InteractionSource?
): Modifier {
    val isDark = isAppDarkTheme()
    val token = backdropGlassToken(
        role = role,
        variant = LocalGlassVariant.current,
        isDark = isDark,
        scene = scene
    )
    val fallbackInteractionSource = remember { MutableInteractionSource() }
    val resolvedInteractionSource = interactionSource ?: fallbackInteractionSource
    val pressed by resolvedInteractionSource.collectIsPressedAsState()
    return Modifier.backdropGlass(
        backdrop = backdrop,
        shape = shape,
        token = token,
        surfaceColor = backdropSurfaceColor(tint, token, scene),
        isDark = isDark,
        pressed = pressed
    )
}
