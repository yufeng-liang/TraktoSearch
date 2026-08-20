package com.tracktosearch.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.theme.GlassVariant
import com.tracktosearch.ui.theme.LocalGlassVariant
import com.tracktosearch.ui.theme.VisualEffectMode

/** Glass 表面的语义角色，页面不应自行组合光学参数。 */
enum class GlassSurfaceRole {
    TopBar,
    CircularControl,
    BottomNavigation,
    SearchField,
    Card,
    DetailAction,
    LoginSurface
}
/** 当前表面所处的内容环境。数值会被限制在 0..1，避免页面状态把光学参数推到非法范围。 */
data class GlassScene(
    val contentLoad: Float = 0f,
    val readabilityDemand: Float = 0f,
    val ambientColor: Color = Color.Transparent
)

/** 组件装饰层的统一分发，确保 Glass 不会意外复用 Blur 的拟态阴影。 */
internal enum class SurfaceTreatment {
    GLASS,
    NEUMORPHIC
}

internal fun surfaceTreatmentFor(mode: VisualEffectMode): SurfaceTreatment {
    return when (mode) {
        VisualEffectMode.GLASS -> SurfaceTreatment.GLASS
        VisualEffectMode.BLUR -> SurfaceTreatment.NEUMORPHIC
    }
}

internal fun usesNeumorphicDecoration(mode: VisualEffectMode): Boolean {
    return surfaceTreatmentFor(mode) == SurfaceTreatment.NEUMORPHIC
}

/** 将页面内容状态归一化为 Glass 可消费的场景参数。 */
internal fun glassSceneForContent(
    contentCount: Int,
    readabilityDemand: Float = 0f,
    ambientColor: Color = Color.Transparent,
    contentCapacity: Int = 48,
    loadingCount: Int = 0,
    loadingItemWeight: Int = 1
): GlassScene {
    val safeCapacity = contentCapacity.coerceAtLeast(1)
    val effectiveContentCount = contentCount.coerceAtLeast(0) +
        loadingCount.coerceAtLeast(0) * loadingItemWeight.coerceAtLeast(1)
    return GlassScene(
        contentLoad = (effectiveContentCount.toFloat() / safeCapacity).coerceIn(0f, 1f),
        readabilityDemand = readabilityDemand.coerceIn(0f, 1f),
        ambientColor = ambientColor
    )
}

/**
 * 组件 fallback 使用的光学 token。正式 Glass 渲染由 BackdropGlassToken 负责，
 * 这里保留纯数据计算，避免没有 Backdrop host 时退化成完全不透明的普通卡片。
 */
enum class GlassSurfaceProfile {
    Circle,
    Lip,
    Squircle
}
data class GlassToken(
    val tintAlpha: Float,
    val borderAlpha: Float,
    val specularIntensity: Float,
    val ambientResponse: Float,
    val environmentTintStrength: Float,
    val edgeSoftness: Dp,
    val surfaceProfile: GlassSurfaceProfile,
    val chromaticAberrationStrength: Float = 0f,
    val hoverLighting: Float,
    val pressLighting: Float,
    val pressRefractionMultiplier: Float,
    val pressWhitePointDelta: Float,
    val pressScale: Float
)

/**
 * 根据表面角色、Glass 风格和明暗主题读取纯 token。
 * 清透/聚焦仅改变材质强度，不改变角色的几何 profile。
 */
fun glassToken(
    role: GlassSurfaceRole,
    variant: GlassVariant,
    isDark: Boolean,
    scene: GlassScene = GlassScene()
): GlassToken {
    val contentLoad = scene.contentLoad.coerceIn(0f, 1f)
    val readabilityDemand = scene.readabilityDemand.coerceIn(0f, 1f)
    val ambientContrast = scene.ambientColor
        .takeIf { it.alpha > 0f }
        ?.let { kotlin.math.abs(it.luminance() - if (isDark) 0.16f else 0.88f) }
        ?.coerceIn(0f, 1f)
        ?: 0f
    val protection = (
        contentLoad * 0.46f +
            readabilityDemand * 0.38f +
            ambientContrast * 0.16f
        ).coerceIn(0f, 1f)
    val focused = variant == GlassVariant.FOCUSED
    // 圆形控件沿用 iOS 控制中心的镜面边缘：保持明显高光，但让内容密度继续压低眩光。
    val baseSpecularIntensity = when (role) {
        GlassSurfaceRole.CircularControl,
        GlassSurfaceRole.DetailAction -> if (focused) 0.76f else 0.58f

        GlassSurfaceRole.TopBar -> if (focused) 0.56f else 0.34f
        GlassSurfaceRole.BottomNavigation -> if (focused) 0.50f else 0.34f
        GlassSurfaceRole.SearchField,
        GlassSurfaceRole.Card -> if (focused) 0.52f else 0.32f
        GlassSurfaceRole.LoginSurface -> if (focused) 0.58f else 0.40f
    }
    val specularProtection = when (role) {
        GlassSurfaceRole.CircularControl,
        GlassSurfaceRole.DetailAction -> 0.42f

        else -> 0.55f
    }
    val baseTintAlpha = when (role) {
        GlassSurfaceRole.TopBar -> if (isDark) {
            if (focused) 0.20f else 0.13f
        } else {
            if (focused) 0.13f else 0.08f
        }

        GlassSurfaceRole.CircularControl,
        GlassSurfaceRole.DetailAction -> if (isDark) {
            if (focused) 0.25f else 0.15f
        } else {
            if (focused) 0.24f else 0.16f
        }

        GlassSurfaceRole.BottomNavigation -> if (isDark) {
            if (focused) 0.34f else 0.25f
        } else {
            if (focused) 0.49f else 0.38f
        }

        GlassSurfaceRole.SearchField -> if (isDark) {
            if (focused) 0.28f else 0.18f
        } else {
            if (focused) 0.23f else 0.14f
        }

        GlassSurfaceRole.Card -> if (isDark) {
            if (focused) 0.28f else 0.18f
        } else {
            if (focused) 0.23f else 0.14f
        }

        GlassSurfaceRole.LoginSurface -> if (isDark) {
            if (focused) 0.43f else 0.34f
        } else {
            if (focused) 0.53f else 0.42f
        }
    }

    return GlassToken(
        tintAlpha = (baseTintAlpha + protection * if (focused) 0.10f else 0.14f)
            .coerceIn(0f, 1f),
        borderAlpha = ((if (focused) 1.0f else 0.76f) + protection * 0.24f)
            .coerceIn(0f, 1f),
        // 内容越密，镜面高光越弱，避免海报/标题在玻璃边缘产生白色噪点。
        specularIntensity = (baseSpecularIntensity * (1f - protection * specularProtection))
            .coerceIn(0f, 1f),
        ambientResponse = ((if (focused) 0.60f else 0.42f) + protection * 0.08f)
            .coerceIn(0f, 1f),
        environmentTintStrength = (0.10f + protection * 0.08f).coerceIn(0f, 1f),
        edgeSoftness = when (role) {
            GlassSurfaceRole.TopBar -> 2.dp
            GlassSurfaceRole.CircularControl,
            GlassSurfaceRole.DetailAction -> 3.dp
            GlassSurfaceRole.BottomNavigation -> 8.dp
            GlassSurfaceRole.SearchField,
            GlassSurfaceRole.Card -> 4.dp
            GlassSurfaceRole.LoginSurface -> 8.dp
        },
        surfaceProfile = when (role) {
            GlassSurfaceRole.CircularControl,
            GlassSurfaceRole.DetailAction -> GlassSurfaceProfile.Circle
            GlassSurfaceRole.BottomNavigation -> GlassSurfaceProfile.Lip
            else -> GlassSurfaceProfile.Squircle
        },
        chromaticAberrationStrength = 0f,
        hoverLighting = if (focused) 0.48f else 0.28f,
        pressLighting = if (focused) 1.0f else 0.82f,
        pressRefractionMultiplier = if (focused) 1.10f else 1.06f,
        pressWhitePointDelta = if (focused) 0.05f else 0.03f,
        pressScale = if (focused) 0.985f else 0.99f
    )
}

internal fun resolveGlassTintAlpha(callingAlpha: Float, tokenAlpha: Float): Float {
    return (callingAlpha * tokenAlpha).coerceIn(0f, 1f)
}

internal fun resolveGlassFallbackFill(
    backgroundColor: Color,
    themeSurface: Color,
    tokenAlpha: Float,
    ambientColor: Color,
    environmentTintStrength: Float
): Color {
    val base = backgroundColor.takeIf { it.alpha > 0f } ?: themeSurface
    val tinted = resolveGlassEnvironmentTint(
        tint = base,
        ambientColor = ambientColor,
        strength = environmentTintStrength
    )
    return tinted.copy(alpha = resolveGlassTintAlpha(base.alpha, tokenAlpha))
}

@Composable
internal fun glassBorderColor(
    role: GlassSurfaceRole,
    color: Color,
    scene: GlassScene = GlassScene()
): Color {
    val token = glassToken(role, LocalGlassVariant.current, isAppDarkTheme(), scene)
    return color.copy(alpha = resolveGlassTintAlpha(color.alpha, token.borderAlpha))
}

/** 解析 Glass 表面的有效环境色：页面无环境色时回退到主题背景。 */
internal fun resolveGlassAmbientColor(
    sceneAmbient: Color,
    themeBackground: Color
): Color {
    return sceneAmbient.takeIf { it.alpha > 0f } ?: themeBackground
}

internal fun resolveGlassEnvironmentTint(
    tint: Color,
    ambientColor: Color,
    strength: Float
): Color {
    if (ambientColor.alpha <= 0f || strength <= 0f) return tint
    return lerp(
        start = tint.copy(alpha = 1f),
        stop = ambientColor.copy(alpha = 1f),
        fraction = strength.coerceIn(0f, 1f)
    ).copy(alpha = tint.alpha)
}

/** 将已缓存的海报主色合成为当前页面的环境色。未命中时由调用方提供主题背景回退。 */
internal fun resolveCachedPosterAmbientColor(
    cachedColors: List<Color>,
    fallback: Color
): Color {
    val usableColors = cachedColors.filter { it.alpha > 0f }
    if (usableColors.isEmpty()) return fallback

    val totalWeight = usableColors.sumOf { it.alpha.toDouble() }.toFloat()
    if (totalWeight <= 0f) return fallback

    val red = usableColors.sumOf { (it.red * it.alpha).toDouble() }.toFloat() / totalWeight
    val green = usableColors.sumOf { (it.green * it.alpha).toDouble() }.toFloat() / totalWeight
    val blue = usableColors.sumOf { (it.blue * it.alpha).toDouble() }.toFloat() / totalWeight
    return Color(red = red, green = green, blue = blue, alpha = 1f)
}
