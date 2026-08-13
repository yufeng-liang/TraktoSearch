@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.theme.GlassVariant
import com.tracktosearch.ui.theme.LocalGlassVariant
import com.tracktosearch.ui.theme.VisualEffectMode
import dev.chrisbanes.haze.glass.GlassOptics
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.SurfaceProfile

/** Glass 表面的语义角色，页面不应自行组合光学参数。 */
enum class GlassSurfaceRole {
    TopBar,
    CircularControl,
    BottomNavigation,
    SearchField,
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

/** Glass 光学 token。所有正式角色都禁用色散，避免页面间出现不可控的色差。 */
data class GlassToken(
    val tintAlpha: Float,
    val borderAlpha: Float,
    val specularIntensity: Float,
    val ambientResponse: Float,
    val environmentTintStrength: Float,
    val edgeSoftness: Dp,
    val surfaceProfile: SurfaceProfile,
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
            if (focused) 0.18f else 0.11f
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
        specularIntensity = ((if (focused) 0.62f else 0.38f) * (1f - protection * 0.55f))
            .coerceIn(0f, 1f),
        ambientResponse = ((if (focused) 0.60f else 0.42f) + protection * 0.08f)
            .coerceIn(0f, 1f),
        environmentTintStrength = (0.10f + protection * 0.08f).coerceIn(0f, 1f),
        edgeSoftness = when (role) {
            GlassSurfaceRole.TopBar -> 2.dp
            GlassSurfaceRole.CircularControl,
            GlassSurfaceRole.DetailAction -> 3.dp
            GlassSurfaceRole.BottomNavigation -> 8.dp
            GlassSurfaceRole.SearchField -> 4.dp
            GlassSurfaceRole.LoginSurface -> 8.dp
        },
        surfaceProfile = when (role) {
            GlassSurfaceRole.CircularControl,
            GlassSurfaceRole.DetailAction -> SurfaceProfile.Circle
            GlassSurfaceRole.BottomNavigation -> SurfaceProfile.Lip
            else -> SurfaceProfile.Squircle
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
    tokenAlpha: Float
): Color {
    val base = backgroundColor.takeIf { it.alpha > 0f } ?: themeSurface
    return base.copy(alpha = resolveGlassTintAlpha(base.alpha, tokenAlpha))
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

/** TrackToSearch 的集中 Glass 样式入口。 */
object AppGlassStyles {
    @Composable
    fun style(
        role: GlassSurfaceRole,
        shape: RoundedCornerShape,
        tint: Color = MaterialTheme.colorScheme.surface,
        interactive: Boolean = false,
        scene: GlassScene = GlassScene()
    ): GlassStyle {
        val variant = LocalGlassVariant.current
        val isDark = isAppDarkTheme()
        val token = glassToken(role, variant, isDark, scene)
        val resolvedTint = tint.takeIf { it.alpha > 0f } ?: MaterialTheme.colorScheme.surface
        val ambientColor = scene.ambientColor.takeIf { it.alpha > 0f }
            ?: MaterialTheme.colorScheme.background
        val callingAlpha = if (tint.alpha > 0f) tint.alpha else 1f

        return GlassStyle {
            tint(
                resolveGlassEnvironmentTint(
                    tint = resolvedTint,
                    ambientColor = ambientColor,
                    strength = token.environmentTintStrength
                ).copy(alpha = resolveGlassTintAlpha(callingAlpha, token.tintAlpha))
            )
            optics(GlassOptics.Adaptive)
            specularIntensity(token.specularIntensity)
            ambientResponse(token.ambientResponse)
            edgeSoftness(token.edgeSoftness)
            shape(shape)
            surfaceProfile(token.surfaceProfile)
            chromaticAberrationStrength(token.chromaticAberrationStrength)
            if (interactive) {
                hovered {
                    lightingIntensity(token.hoverLighting)
                }
                focused {
                    lightingIntensity(token.hoverLighting)
                }
                pressed {
                    lightingIntensity(token.pressLighting)
                    refractionMultiplier(token.pressRefractionMultiplier)
                    whitePointDelta(token.pressWhitePointDelta)
                    scale(token.pressScale.coerceIn(Float.MIN_VALUE, 1f))
                }
            }
        }
    }

    @Composable
    fun topBar(
        tint: Color = MaterialTheme.colorScheme.surface,
        scene: GlassScene = GlassScene()
    ): GlassStyle {
        return style(
            role = GlassSurfaceRole.TopBar,
            shape = RoundedCornerShape(0.dp),
            tint = tint,
            scene = scene
        )
    }

    @Composable
    fun searchField(
        tint: Color = MaterialTheme.colorScheme.surface,
        shape: RoundedCornerShape = RoundedCornerShape(16.dp),
        interactive: Boolean = true,
        scene: GlassScene = GlassScene()
    ): GlassStyle {
        return style(
            role = GlassSurfaceRole.SearchField,
            shape = shape,
            tint = tint,
            interactive = interactive,
            scene = scene
        )
    }

    @Composable
    fun circularControl(
        tint: Color = MaterialTheme.colorScheme.surface,
        interactive: Boolean = true,
        scene: GlassScene = GlassScene()
    ): GlassStyle {
        return style(
            role = GlassSurfaceRole.CircularControl,
            shape = RoundedCornerShape(50),
            tint = tint,
            interactive = interactive,
            scene = scene
        )
    }

    @Composable
    fun bottomNavigation(
        tint: Color,
        shape: RoundedCornerShape,
        scene: GlassScene = GlassScene()
    ): GlassStyle {
        return style(
            role = GlassSurfaceRole.BottomNavigation,
            shape = shape,
            tint = tint,
            scene = scene
        )
    }

    @Composable
    fun detailAction(
        tint: Color = MaterialTheme.colorScheme.surface,
        shape: RoundedCornerShape = RoundedCornerShape(50),
        interactive: Boolean = true,
        scene: GlassScene = GlassScene()
    ): GlassStyle {
        return style(
            role = GlassSurfaceRole.DetailAction,
            shape = shape,
            tint = tint,
            interactive = interactive,
            scene = scene
        )
    }

    @Composable
    fun loginSurface(
        tint: Color = MaterialTheme.colorScheme.surface,
        shape: RoundedCornerShape = RoundedCornerShape(24.dp),
        scene: GlassScene = GlassScene()
    ): GlassStyle {
        return style(
            role = GlassSurfaceRole.LoginSurface,
            shape = shape,
            tint = tint,
            scene = scene
        )
    }
}
