package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

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
    shadowOffset: Dp? = null
): Modifier {
    val blur = blurRadius ?: elevation
    val offset = shadowOffset ?: (elevation * 0.7f)
    val darkColor = if (isDark) {
        Color.Black.copy(alpha = darkAlpha)
    } else {
        Color(0xFF6478B4).copy(alpha = darkAlpha)
    }
    val lightColor = Color.White.copy(alpha = lightAlpha)
    return this
        .dropShadow(
            shape = shape,
            shadow = Shadow(
                radius = blur,
                color = darkColor,
                offset = DpOffset(offset, offset)
            )
        )
        .innerShadow(
            shape = shape,
            shadow = Shadow(
                radius = blur * 0.35f,
                color = lightColor,
                offset = DpOffset(-offset * 0.6f, -offset * 0.6f)
            )
        )
}

/**
 * 顶部玻璃高光渐变（C方案 ::before 效果）
 */
@Composable
fun BoxScope.GlassHighlight(
    isDark: Boolean,
    shape: Shape = RoundedCornerShape(32.dp)
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (isDark) 0.08f else 0.60f),
                    0.25f to Color.White.copy(alpha = if (isDark) 0.03f else 0.25f),
                    0.50f to Color.White.copy(alpha = if (isDark) 0.01f else 0.08f),
                    1.0f to Color.Transparent
                )
            )
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
    val darkColor = if (isDark) {
        Color.Black.copy(alpha = 0.25f)
    } else {
        Color(0xFF6478B4).copy(alpha = 0.15f)
    }
    val lightColor = Color.White.copy(alpha = if (isDark) 0.06f else 0.90f)
    val backgroundModifier = if (isDark) {
        Modifier.background(Color(0xFF3949AB).copy(alpha = 0.20f), shape)
    } else {
        Modifier.background(
            Brush.linearGradient(
                0.0f to Color(0xFFDCE4FF).copy(alpha = 0.90f),
                1.0f to Color.White.copy(alpha = 0.90f)
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
 * C方案拟态毛玻璃表面：双向阴影 + 毛玻璃 + 顶部高光
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun NeumorphicFrostedSurface(
    modifier: Modifier = Modifier,
    isDark: Boolean,
    shape: Shape = RoundedCornerShape(32.dp),
    elevation: Dp = 14.dp,
    blurRadius: Dp? = null,
    shadowOffset: Dp? = null,
    backgroundColor: Color = if (isDark) Color(0xFF1E1E3A).copy(alpha = 0.65f) else Color.White.copy(alpha = 0.65f),
    borderColor: Color = if (isDark) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.8f),
    darkShadowAlpha: Float = if (isDark) 0.5f else 0.18f,
    lightShadowAlpha: Float = if (isDark) 0.10f else 0.70f,
    hazeState: HazeState? = null,
    content: @Composable () -> Unit
) {
    val hazeModifier = if (hazeState != null) {
        Modifier.hazeEffect(state = hazeState, style = HazeMaterials.thin())
    } else Modifier

    Box(
        modifier = modifier
            .neumorphicShadow(
                shape = shape,
                isDark = isDark,
                elevation = elevation,
                darkAlpha = darkShadowAlpha,
                lightAlpha = lightShadowAlpha,
                blurRadius = blurRadius,
                shadowOffset = shadowOffset
            )
            .then(hazeModifier)
            .clip(shape)
            .background(backgroundColor, shape)
            .border(1.dp, borderColor, shape)
    ) {
        GlassHighlight(isDark = isDark, shape = shape)
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
