@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.theme.isDarkScheme
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
    isContentUnderTopBar: Boolean? = null,
    backdropOverride: Backdrop? = null,
    scene: GlassScene = GlassScene(),
    sourceSelection: HazeSourceSelection = HazeSourceSelection.Behind
): Modifier {
    // Blur 效果节点常驻、用透明度插值，避免布尔硬切换导致的新节点首帧未就绪闪透明。
    // Backdrop 在初始位置不注册采样；滚动后再恢复完整的 Glass 光学效果。
    // isContentUnderTopBar == null 时保持恒模糊（不参与滚动判定的页面，如列表详情标题栏）。
    val visible by animateFloatAsState(
        targetValue = when (isContentUnderTopBar) {
            null -> 1f
            else -> if (isContentUnderTopBar) 1f else 0f
        },
        animationSpec = tween(durationMillis = 220),
        label = "topBarHazeAlpha"
    )
    // 将调用方传入的 blurRadius 实际写入 HazeBlurStyle，避免参数失效
    val resolvedStyle = style.then {
        blurRadius(blurRadius)
        // blur=0 时 Haze 输出为空，alpha=0 作为双重保障，避免效果未就绪露出未模糊内容
        alpha(visible)
    }
    return appVisualEffect(
        input = HazeInput.Sources(state, selection = sourceSelection),
        hazeStyle = resolvedStyle,
        glassRole = GlassSurfaceRole.TopBar,
        glassShape = RoundedCornerShape(0.dp),
        // Glass 填充 alpha 由 TopBar token 统一控制；预乘 0.12 会让滚动后的实际填充
        // 只剩约 2-3%，视觉上仍像透明。初始透明仍由 glassEffectEnabled 控制。
        glassTint = MaterialTheme.colorScheme.surface,
        backdropOverride = backdropOverride,
        scene = scene,
        glassEffectEnabled = isContentUnderTopBar != false,
        // 渲染降采样：Haze 官方基准显示可降低 5-20% 开销，肉眼几乎不可见
        // 顶栏是大面积、持续随列表更新的消费者；Performance 档降低输入分辨率，
        // 文本与边框仍以原分辨率绘制，仅模糊背景轻微降采样。
        blurPerformanceMode = HazePerformanceMode.Performance
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
    darkColor: Color? = null,
    offsetX: Dp? = null,
    offsetY: Dp? = null
): Modifier {
    val blur = blurRadius ?: elevation
    val baseOffset = shadowOffset ?: (elevation * 0.7f)
    val dx = offsetX ?: baseOffset
    val dy = offsetY ?: baseOffset
    val darkShadowColor = (darkColor ?: if (isDark) Color.Black else Color(0xFF6478B4))
        .copy(alpha = darkAlpha)
    return this.dropShadow(
        shape = shape,
        shadow = Shadow(
            radius = blur,
            color = darkShadowColor,
            offset = DpOffset(dx, dy)
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
 * 上沿和左上角的镜面高光。保持中部和底部通透，避免整块泛白。
 */
@Composable
fun BoxScope.GlassHighlight(
    isDark: Boolean,
    shape: Shape = RoundedCornerShape(32.dp),
    alphaScale: Float = 1f,
    compact: Boolean = false
) {
    val resolvedScale = alphaScale.coerceIn(0f, 1f)
    val topAlpha = (if (isDark) 0.055f else if (compact) 0.22f else 0.28f) * resolvedScale
    val edgeAlpha = (if (isDark) 0.20f else if (compact) 0.78f else 0.52f) * resolvedScale
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = topAlpha),
                    0.035f to Color.White.copy(alpha = topAlpha * 0.72f),
                    0.10f to Color.White.copy(alpha = topAlpha * 0.24f),
                    0.18f to Color.White.copy(alpha = topAlpha * 0.07f),
                    0.34f to Color.Transparent,
                    1.0f to Color.Transparent
                )
            )
            .border(
                width = if (compact) 0.75.dp else 1.dp,
                brush = Brush.linearGradient(
                    0.0f to Color.White.copy(alpha = edgeAlpha),
                    0.08f to Color.White.copy(alpha = edgeAlpha * 0.62f),
                    0.28f to Color.White.copy(alpha = edgeAlpha * 0.20f),
                    0.65f to Color.White.copy(alpha = edgeAlpha * 0.08f),
                    1.0f to Color.White.copy(alpha = edgeAlpha * 0.22f)
                ),
                shape = shape
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

/** 详情页顶部图标：主题色前景叠加反色偏移阴影，增强海报暗部采样时的边缘对比。 */
@Composable
fun DetailTopBarIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = detailTopBarIconColor()
) {
    val isDark = isAppDarkTheme()
    val inverseShadow = Color(
        red = 1f - tint.red,
        green = 1f - tint.green,
        blue = 1f - tint.blue,
        alpha = if (isDark) 0.82f else 0.62f
    )
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = inverseShadow,
            modifier = Modifier
                .size(size)
                .offset(x = 0.8.dp, y = 0.8.dp)
        )
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size)
        )
    }
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
    hazeBlurRadius: Dp? = null,
    showHighlight: Boolean = true,
    // 可选：过滤参与模糊的源区域。底部导航等"自身既作 source 又作 effect"的场景
    // 应传入 Behind.where { source -> source.zIndex < 自身 zIndex } 排除自采样。
    sourceSelection: HazeSourceSelection = HazeSourceSelection.Behind,
    // 旧页面默认只保留普通表面；需要 Glass 采样时由页面显式声明语义角色。
    glassRole: GlassSurfaceRole? = null,
    useNavigationSelectionGlassStyle: Boolean = false,
    selectionBorderWidth: Dp? = null,
    showGlassBorder: Boolean = true,
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
        if (glassRole != null) {
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
                useNavigationSelectionStyle = useNavigationSelectionGlassStyle,
                selectionBorderWidth = selectionBorderWidth,
                showGlassBorder = showGlassBorder,
                content = content
            )
        } else {
            // 走到这里 glassRole 必为 null（上方 if 的另一支），直接用调用方传入的颜色。
            // 原先这里还有一段 glassRole 判空取 token 的分支，在该位置恒不成立（死代码），已删除。
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(backgroundColor, shape)
                    .border(1.dp, borderColor, shape),
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
            blurPerformanceMode = HazePerformanceMode.Default,
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
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    lightBorderAlpha: Float = 0.55f,
    scene: GlassScene = GlassScene(),
    buttonStyle: NeumorphicIconButtonStyle = NeumorphicIconButtonStyle.Default,
    sourceSelection: HazeSourceSelection = HazeSourceSelection.Behind,
    content: @Composable () -> Unit
) {
    if (LocalVisualEffectMode.current == VisualEffectMode.GLASS) {
        // 触感不在这里发：GlassIconButton 内部的可点面已经发了一记 LIGHT_TAP，
        // 这里再包一层就是双震。BLUR 那条分支自绘 Box，触感在下面那个 hapticClickable 上。
        GlassIconButton(
            onClick = onClick,
            modifier = modifier,
            size = size,
            hazeState = hazeState,
            hazeStyle = hazeStyle,
            role = if (buttonStyle == NeumorphicIconButtonStyle.DetailTopBar) {
                GlassSurfaceRole.DetailAction
            } else {
                GlassSurfaceRole.CircularControl
            },
            interactionSource = interactionSource,
            enabled = enabled,
            scene = scene,
            sourceSelection = sourceSelection,
            content = content
        )
        return
    }

    val isDetailTopBar = buttonStyle == NeumorphicIconButtonStyle.DetailTopBar
    val shape = CircleShape
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val blurTintAlpha = when {
        isDetailTopBar -> 0.10f
        isDark -> 0.18f
        else -> 0.72f
    }
    val resolvedHazeStyle = hazeStyle ?: HazeMaterials.thin(
        MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.08f else 0.18f)
    )
    val blurFill = when {
        isDark -> Color.Transparent
        hazeState == null -> Color.White
        else -> Color.White.copy(alpha = blurTintAlpha)
    }
    val hazeModifier = if (hazeState != null) {
        Modifier.appVisualEffect(
            input = HazeInput.Sources(hazeState, selection = sourceSelection),
            hazeStyle = resolvedHazeStyle,
            glassRole = if (isDetailTopBar) {
                GlassSurfaceRole.DetailAction
            } else {
                GlassSurfaceRole.CircularControl
            },
            glassShape = RoundedCornerShape(50),
            glassTint = MaterialTheme.colorScheme.surface.copy(alpha = blurTintAlpha),
            scene = scene,
            blurPerformanceMode = HazePerformanceMode.Default,
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
                elevation = 9.dp,
                darkAlpha = if (isDark) 0.42f else 0.38f,
                blurRadius = 18.dp,
                offsetX = 5.dp,
                offsetY = 7.dp,
                darkColor = if (isDark) Color.Black else Color(0xFF68707C)
            )
            .clip(shape)
            .then(hazeModifier)
            .background(blurFill, shape)
            .neumorphicInnerShadow(
                shape = shape,
                isDark = isDark,
                elevation = 4.dp,
                lightAlpha = if (isDark) {
                    if (isDetailTopBar) 0.04f else 0.08f
                } else {
                    if (isDetailTopBar) 0.33f else 0.35f
                },
                blurRadius = 10.dp,
                shadowOffset = 4.dp
            )
            .border(
                width = 1.dp,
                color = if (isDark) {
                    Color.White.copy(alpha = if (isDetailTopBar) 0.06f else 0.12f)
                } else {
                    Color.White.copy(
                        alpha = (
                            lightBorderAlpha * if (isDetailTopBar) 0.5f else 0.45f
                        ).coerceIn(0f, 1f)
                    )
                },
                shape = shape
            )
            .hapticClickable(
                interactionSource = resolvedInteractionSource,
                indication = null,
                semantic = HapticSemantic.LIGHT_TAP,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        GlassHighlight(
            isDark = isDark,
            shape = shape,
            alphaScale = if (isDetailTopBar) 0.5f else 1f,
            compact = true
        )
        // 与 GlassIconButton 对齐：图标不传 tint 时拿到 onSurface。Blur 模式不做亮度自适应，
        // 这里显式提供是为了让调用点可以统一省掉 tint，而两种模式的静止观感保持一致。
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

/**
 * 基于 MaterialTheme.colorScheme.background 亮度判断暗色模式
 *
 * 列表滚动时该方法在每张卡片组合中被高频调用（全局 40+ 处调用点），
 * 用 remember(colorScheme) 缓存结果：colorScheme 实例未变化（主题未切换）
 * 时直接返回缓存值，避免每帧重复执行 sRGB luminance() 浮点计算。
 * 主题切换（如深色模式、动态取色）时 colorScheme 引用变化，自动重算。
 */
@Composable
fun isAppDarkTheme(): Boolean {
    val colorScheme = MaterialTheme.colorScheme
    return remember(colorScheme) { colorScheme.isDarkScheme }
}
