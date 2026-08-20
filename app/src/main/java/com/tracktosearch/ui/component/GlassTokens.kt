package com.tracktosearch.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.theme.GlassVariant

/** 顶栏 blur 半径；source 外扩同步覆盖该半径，保证窗口左右边界有完整采样邻域。 */
val TopBarBackdropBlurRadius = 20.dp
val TopBarBackdropSourcePadding = TopBarBackdropBlurRadius

/** Backdrop 玻璃的独立场景 token，不依赖旧 Haze Glass 的样式类型。 */
data class BackdropGlassToken(
    val tintAlpha: Float,
    val borderAlpha: Float,
    val blurRadius: Dp,
    val blurEdgeTreatment: TileMode,
    val refractionHeight: Dp,
    val refractionAmount: Dp,
    val depthEffect: Boolean,
    val chromaticAberration: Boolean,
    val highlightWidth: Dp,
    val highlightAlpha: Float,
    val shadowRadius: Dp,
    val shadowAlpha: Float,
    val innerShadowRadius: Dp,
    val pressLighting: Float,
    val pressScale: Float
)

private data class LensDefinition(
    val blurRadius: Dp,
    val refractionHeight: Dp,
    val refractionAmount: Dp,
    val depthEffect: Boolean,
    val chromaticAberration: Boolean,
    val highlightWidth: Dp,
    val highlightAlpha: Float,
    val shadowRadius: Dp,
    val shadowAlpha: Float
)

private fun lens(
    blurRadius: Dp,
    refractionHeight: Dp,
    refractionAmount: Dp,
    depthEffect: Boolean,
    chromaticAberration: Boolean,
    highlightWidth: Dp,
    highlightAlpha: Float,
    shadowRadius: Dp,
    shadowAlpha: Float
): LensDefinition {
    return LensDefinition(
        blurRadius = blurRadius,
        refractionHeight = refractionHeight,
        refractionAmount = refractionAmount,
        depthEffect = depthEffect,
        chromaticAberration = chromaticAberration,
        highlightWidth = highlightWidth,
        highlightAlpha = highlightAlpha,
        shadowRadius = shadowRadius,
        shadowAlpha = shadowAlpha
    )
}

/** 登录表单使用独立的可读性 profile，不复用五类控件的 lens 行。 */
private fun loginFormLens(): LensDefinition {
    return lens(
        blurRadius = 10.dp,
        refractionHeight = 5.dp,
        refractionAmount = 14.dp,
        depthEffect = false,
        chromaticAberration = false,
        highlightWidth = 0.6.dp,
        highlightAlpha = 0.24f,
        shadowRadius = 12.dp,
        shadowAlpha = 0.18f
    )
}

private fun lensForRole(role: GlassSurfaceRole): LensDefinition {
    return when (role) {
        GlassSurfaceRole.BottomNavigation -> lens(20.dp, 8.dp, 36.dp, true, true, 1.2.dp, 0.52f, 26.dp, 0.38f)
        // 顶栏是矩形（0.dp 圆角），按官方约束不使用 lens；lens 在矩形左右边缘会产生不连续。
        // 顶栏只做完整 blur，真实内容边缘也随采样一起模糊。水滴等圆角控件仍保留 lens。
        GlassSurfaceRole.TopBar -> lens(TopBarBackdropSourcePadding, 0.dp, 0.dp, false, false, 0.9.dp, 0.36f, 18.dp, 0.28f)
        GlassSurfaceRole.SearchField -> lens(14.dp, 8.dp, 26.dp, true, true, 0.9.dp, 0.46f, 12.dp, 0.22f)
        GlassSurfaceRole.Card -> lens(14.dp, 8.dp, 26.dp, true, true, 0.9.dp, 0.46f, 12.dp, 0.22f)
        // 详情页顶部圆形按钮与试点 button 场景使用同一组镜头参数。
        GlassSurfaceRole.CircularControl,
        GlassSurfaceRole.DetailAction -> lens(12.dp, 6.dp, 18.dp, true, false, 0.7.dp, 0.76f, 11.9.dp, 0.32f)
        GlassSurfaceRole.LoginSurface -> loginFormLens()
    }
}

private fun baseTintAlpha(role: GlassSurfaceRole, isDark: Boolean): Float {
    return when (role) {
        GlassSurfaceRole.BottomNavigation -> if (isDark) 0.22f else 0.34f
        // 吸顶栏承载稳定标题。滚动内容即使采样首帧尚未完成，也不能让底层文字清晰透出。
        GlassSurfaceRole.TopBar -> if (isDark) 0.70f else 0.62f
        GlassSurfaceRole.SearchField,
        GlassSurfaceRole.Card -> if (isDark) 0.18f else 0.14f
        GlassSurfaceRole.CircularControl,
        GlassSurfaceRole.DetailAction -> if (isDark) 0.20f else 0.18f
        GlassSurfaceRole.LoginSurface -> if (isDark) 0.34f else 0.42f
    }
}

private fun baseBorderAlpha(role: GlassSurfaceRole, isDark: Boolean): Float {
    return when (role) {
        GlassSurfaceRole.BottomNavigation -> if (isDark) 0.24f else 0.30f
        GlassSurfaceRole.TopBar -> if (isDark) 0.20f else 0.24f
        GlassSurfaceRole.SearchField,
        GlassSurfaceRole.Card -> if (isDark) 0.24f else 0.30f
        GlassSurfaceRole.CircularControl,
        GlassSurfaceRole.DetailAction -> if (isDark) 0.28f else 0.34f
        GlassSurfaceRole.LoginSurface -> if (isDark) 0.30f else 0.38f
    }
}

private fun basePressLighting(role: GlassSurfaceRole): Float {
    return when (role) {
        GlassSurfaceRole.BottomNavigation -> 0.76f
        GlassSurfaceRole.TopBar -> 0.68f
        GlassSurfaceRole.SearchField,
        GlassSurfaceRole.Card -> 0.72f
        GlassSurfaceRole.CircularControl,
        GlassSurfaceRole.DetailAction -> 0.88f
        GlassSurfaceRole.LoginSurface -> 0.64f
    }
}

private fun baseInnerShadowRadius(role: GlassSurfaceRole): Dp {
    return when (role) {
        GlassSurfaceRole.BottomNavigation -> 8.dp
        GlassSurfaceRole.TopBar -> 4.dp
        GlassSurfaceRole.SearchField -> 0.dp
        GlassSurfaceRole.Card -> 4.dp
        GlassSurfaceRole.CircularControl,
        GlassSurfaceRole.DetailAction -> 3.dp
        GlassSurfaceRole.LoginSurface -> 6.dp
    }
}

/**
 * 根据角色、变体和当前内容环境解析 Backdrop token。
 * 场景保护只提高填充/边界并压低高光，不会改动 lens 三元组或色散开关。
 */
fun backdropGlassToken(
    role: GlassSurfaceRole,
    variant: GlassVariant,
    isDark: Boolean,
    scene: GlassScene = GlassScene()
): BackdropGlassToken {
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
    val lens = lensForRole(role)

    return BackdropGlassToken(
        tintAlpha = (
            baseTintAlpha(role, isDark) +
                protection * 0.12f +
                if (focused) 0.08f else 0f
            ).coerceIn(0f, 1f),
        borderAlpha = (
            baseBorderAlpha(role, isDark) +
                protection * 0.14f +
                if (focused) 0.08f else 0f
            ).coerceIn(0f, 1f),
        blurRadius = lens.blurRadius,
        // 顶栏贴齐窗口顶部时，Clamp 会复制状态栏首行像素，令边缘文字仍然锐利。
        // Decal 让越界采样透明，由表面填充接管，确保整个状态栏都参与模糊。
        blurEdgeTreatment = if (role == GlassSurfaceRole.TopBar) TileMode.Decal else TileMode.Clamp,
        refractionHeight = lens.refractionHeight,
        refractionAmount = lens.refractionAmount,
        depthEffect = lens.depthEffect,
        chromaticAberration = lens.chromaticAberration,
        highlightWidth = lens.highlightWidth,
        highlightAlpha = (
            lens.highlightAlpha * (1f - protection * 0.35f) +
                if (focused) 0.10f else 0f
            ).coerceIn(0f, 1f),
        shadowRadius = lens.shadowRadius,
        shadowAlpha = lens.shadowAlpha,
        innerShadowRadius = baseInnerShadowRadius(role),
        pressLighting = (
            basePressLighting(role) +
                if (focused) 0.16f else 0f
            ).coerceIn(0f, 1f),
        pressScale = if (focused) 0.985f else 0.99f
    )
}

/** 底栏选中水滴：更集中的凸透镜和更强边缘，独立于外层导航面板。 */
fun backdropNavigationSelectionToken(
    variant: GlassVariant,
    isDark: Boolean,
    scene: GlassScene = GlassScene()
): BackdropGlassToken {
    val panel = backdropGlassToken(
        role = GlassSurfaceRole.BottomNavigation,
        variant = variant,
        isDark = isDark,
        scene = scene
    )
    return panel.copy(
        tintAlpha = (panel.tintAlpha + if (isDark) 0.10f else 0.12f).coerceIn(0f, 1f),
        borderAlpha = (panel.borderAlpha + 0.32f).coerceIn(0f, 1f),
        blurRadius = 10.dp,
        refractionHeight = 10.dp,
        refractionAmount = 30.dp,
        depthEffect = true,
        chromaticAberration = true,
        highlightWidth = 1.4.dp,
        highlightAlpha = (panel.highlightAlpha + 0.22f).coerceIn(0f, 1f),
        shadowRadius = 10.dp,
        shadowAlpha = 0.26f,
        innerShadowRadius = 6.dp,
        pressLighting = 0.90f,
        pressScale = 0.99f
    )
}
