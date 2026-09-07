package com.tracktosearch.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.LocalHazeBlurStyle

internal const val AppHazeDefaultNoiseFactor = 0.10f

private val AppHazeDefaultBlurStyle = HazeBlurStyle {
    noiseFactor(AppHazeDefaultNoiseFactor)
}

/**
 * 统一的 Switch 颜色配置：关闭态轨道/边框用主题色透明度，确保关闭态颜色统一跟随主题色。
 * 所有 Switch 都应使用此函数，避免每处重复声明。
 * 使用方式：Switch(..., colors = appSwitchColors())
 */
@Composable
fun appSwitchColors(): SwitchColors = SwitchDefaults.colors(
    uncheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
    uncheckedThumbColor = MaterialTheme.colorScheme.surface,
    uncheckedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
    // 选中态 thumb 固定 surface 白：不跟 onPrimary（onColorFor 按亮度可给纯黑，
    // 粉色系主题上黑圆观感突兀，白圆在 primary 轨道上对比度仍够用）。
    checkedThumbColor = MaterialTheme.colorScheme.surface
)

/**
 * 主界面专用 ColorScheme：background 保留主题染色（monet 主题 = 种子色相 tone 95/5，
 * 动态壁纸 = 系统底色，票根 = 牛皮纸），而 [TraktoSearchTheme] 对外层 MaterialTheme
 * 用的是纯中性 background（见 NeutralPageBackground*）。
 *
 * MainScreen 路由用它把整棵主界面子树包回染色 scheme，达到「主屏带染色、其他目的地
 * 页面纯中性」。其余 NavHost 目的地不包这层，自然吃到外层中性底。
 */
val LocalMainColorScheme =
    staticCompositionLocalOf<androidx.compose.material3.ColorScheme?> { null }

/**
 * 复古票根：唯一手写而非生成的主题，因为它要整套换纸。
 *
 * 其余主题共用一套底色层级、只换强调色（主界面底色保留染色，目的地页底色
 * 另有中性覆盖，见 LocalMainColorScheme）；票根的主题身份本身就是牛皮纸，
 * 所以 background/surface 一起走暖，主色（赭红墨）才不必独自扛全部气质。
 * 主色取自 [MonetAccent.VINTAGE_TICKET]，不在这里重写字面量 ——
 * 设置页色块和桌面小组件都读那两个值，写两遍必然哪天对不上。
 *
 * 十一个 surface 层级槽位必须显式设满：Material 3 的基线中性色是紫调的
 * （Lab 色相 302°-315°），漏一个就有一块冷紫补丁落在暖纸上。
 * 填充输入框吃 surfaceContainerHighest、底部弹层吃 surfaceContainerLow、
 * Snackbar 吃 inverseSurface，这几个都没在代码里显式指定过，全靠这里兜住。
 */
internal fun vintageTicketColorScheme(dark: Boolean): androidx.compose.material3.ColorScheme = if (dark) {
    darkColorScheme(
        primary = MonetAccent.VINTAGE_TICKET.dark,
        onPrimary = Color(0xFF46200F),
        primaryContainer = Color(0xFF7A3A22),
        onPrimaryContainer = Color(0xFFFFDBCB),
        inversePrimary = Color(0xFFB85030),
        // 深牛皮，不是原先那个和棕色串味的粉（#E5B5C1）
        secondary = Color(0xFFD8B79A),
        onSecondary = Color(0xFF3D2617),
        // 显式覆盖 secondaryContainer，避免 Material3 baseline 紫色 fallback 导致 FilterChip 选中态变紫
        secondaryContainer = Color(0xFF6B4A32),
        onSecondaryContainer = Color(0xFFF5DFCB),
        // 票根上那枚褪色青绿副印。原先整个 tertiary 族没设，
        // 基线粉（#EFB8C8）漏进了 12 处，其中包括氛围渐变背景的光斑
        tertiary = Color(0xFF92C4B2),
        onTertiary = Color(0xFF0B3229),
        tertiaryContainer = Color(0xFF2E5449),
        onTertiaryContainer = Color(0xFFADE1CE),
        background = TicketPaperDarkBackground,
        onBackground = TicketInkDark,
        surface = TicketPaperDarkSurface,
        onSurface = TicketInkDark,
        surfaceVariant = TicketPaperDarkVariant,
        onSurfaceVariant = TicketInkDarkMuted,
        surfaceContainerLowest = TicketPaperDarkLowest,
        surfaceContainerLow = TicketPaperDarkLow,
        surfaceContainer = TicketPaperDark,
        surfaceContainerHigh = TicketPaperDarkHigh,
        surfaceContainerHighest = TicketPaperDarkHighest,
        surfaceBright = TicketPaperDarkBright,
        surfaceDim = TicketPaperDarkBackground,
        surfaceTint = MonetAccent.VINTAGE_TICKET.dark,
        inverseSurface = TicketInkDark,
        inverseOnSurface = Color(0xFF3A2E24),
        outline = Color(0xFFA98A74),
        outlineVariant = Color(0xFF5A4638),
        scrim = Color.Black,
        error = ErrorDark,
        onError = OnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark,
    )
} else {
    lightColorScheme(
        primary = MonetAccent.VINTAGE_TICKET.light,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFF5E0CC),
        onPrimaryContainer = Color(0xFF5C2413),
        inversePrimary = Color(0xFFE5906A),
        // 旧木色。原先是 #D9D2C6 配白字，对比度 1.50 —— 白字压在浅米上等于没有
        secondary = Color(0xFF8A6A4E),
        onSecondary = Color.White,
        // 显式覆盖 secondaryContainer，避免 Material3 baseline 紫色 fallback 导致 FilterChip 选中态变紫
        secondaryContainer = Color(0xFFEFE2D0),
        onSecondaryContainer = Color(0xFF4A3524),
        tertiary = Color(0xFF3F6B60),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFD3E5DD),
        onTertiaryContainer = Color(0xFF16302A),
        background = TicketPaperLightBackground,
        onBackground = TicketInkLight,
        surface = TicketPaperLightSurface,
        onSurface = TicketInkLight,
        surfaceVariant = TicketPaperLightVariant,
        onSurfaceVariant = TicketInkLightMuted,
        surfaceContainerLowest = TicketPaperLightLowest,
        surfaceContainerLow = TicketPaperLightLow,
        surfaceContainer = TicketPaperLight,
        surfaceContainerHigh = TicketPaperLightHigh,
        surfaceContainerHighest = TicketPaperLightHighest,
        surfaceBright = TicketPaperLightSurface,
        surfaceDim = TicketPaperLightDim,
        surfaceTint = MonetAccent.VINTAGE_TICKET.light,
        inverseSurface = Color(0xFF3A2E24),
        inverseOnSurface = Color(0xFFF7F0E6),
        outline = Color(0xFFA08B76),
        outlineVariant = Color(0xFFDBCDBA),
        scrim = Color.Black,
        error = ErrorLight,
        onError = OnErrorLight,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight,
    )
}

/**
 * 根据种子色生成 Material You 标准 TonalSpot colorScheme。
 *
 * primary 直接用种子色本身而不是 tone 40/80，为的是「所见即所得」——
 * 设置页弹窗里那个色块就是实际主色。代价是 primary 的亮度不可控，
 * 所以前景色必须按亮度算，见 [onColorFor]。
 *
 * 整条 surface 阶梯（11 个槽位）都从种子色相的中性色板推。**不能漏**：
 * Material 3 的基线中性色是紫调的（Lab 色相 302°-315°），
 * 漏设的槽位会拿到那套紫灰。这些槽位大多没人显式指定过 ——
 * 填充输入框吃 surfaceContainerHighest（20 处）、底部弹层吃 surfaceContainerLow（16 处）、
 * Snackbar 吃 inverseSurface（15 处）、顶栏滚动后吃 surfaceContainerHigh（13 处）。
 *
 * 阶梯的 tone 值对齐了改造前 background/surface 的明度（浅色 95/99，深色 5/10），
 * 所以观感上只是中性色染上了主题色相，层级关系没动。
 *
 * 这套染色 background 目前只服务主界面（MainScreen）子树；其余目的地页面的底色
 * 在 [TraktoSearchTheme] 会被覆盖成纯中性（NeutralPageBackground*），不走这里的染色。
 */
internal fun monetColorScheme(seed: Color, dark: Boolean): androidx.compose.material3.ColorScheme {
    val seedArgb = seed.toArgb()
    val seedHue = com.tracktosearch.data.util.mcu.hct.Hct.fromInt(seedArgb).hue
    val tonal = com.tracktosearch.data.util.mcu.palettes.TonalPalette.fromInt(seedArgb)
    // neutral 染彩度 4.0（标准 6.0）：只管主界面 background/surface/容器底，用户要求底色染色更淡。
    // 子页面底色已另行在 TraktoSearchTheme 覆盖为纯中性，这里只决定主屏与各级容器的染色深度。
    // neutralVariant 保持 8.0：surfaceVariant（卡片/对话框）不变，且与 neutral 拉开彩度差。
    val neutral = com.tracktosearch.data.util.mcu.palettes.TonalPalette
        .fromHueAndChroma(seedHue, 4.0)
    val neutralVariant = com.tracktosearch.data.util.mcu.palettes.TonalPalette
        .fromHueAndChroma(seedHue, 8.0)
    // 对话框底色专用：比页面底色（neutral 4.0）再淡一档，见下方 surface 阶梯注释
    val neutralFaint = com.tracktosearch.data.util.mcu.palettes.TonalPalette
        .fromHueAndChroma(seedHue, 2.0)

    fun neutralTone(darkTone: Double, lightTone: Double) =
        Color(neutral.tone(if (dark) darkTone else lightTone))
    fun faintTone(darkTone: Double, lightTone: Double) =
        Color(neutralFaint.tone(if (dark) darkTone else lightTone))
    fun variantTone(darkTone: Double, lightTone: Double) =
        Color(neutralVariant.tone(if (dark) darkTone else lightTone))
    fun accentTone(darkTone: Double, lightTone: Double) =
        Color(tonal.tone(if (dark) darkTone else lightTone))

    val primary = seed
    val onPrimary = onColorFor(seed)
    // container 深色档用 tone 30，浅色档用同一 hue 的浅变体 tone 90（light 更贴近原色，dark 保持深容器）
    val primaryContainer = accentTone(30.0, 90.0)
    val onPrimaryContainer = accentTone(90.0, 10.0)
    val inversePrimary = accentTone(40.0, 80.0)
    // secondary 提升到同一 hue 较亮一档，与新 primary 协调（原为中性灰 tone 40，过暗）
    val secondary = accentTone(70.0, 45.0)
    val secondaryContainer = accentTone(30.0, 90.0)
    val onSecondaryContainer = accentTone(90.0, 10.0)
    val tertiary = accentTone(80.0, 40.0)
    val onTertiary = accentTone(20.0, 100.0)
    val tertiaryContainer = accentTone(30.0, 90.0)
    val onTertiaryContainer = accentTone(90.0, 10.0)

    val onBackground = neutralTone(90.0, 10.0)
    val surfaceVar = variantTone(30.0, 90.0)
    val onSurfaceVar = variantTone(80.0, 30.0)
    val outline = variantTone(60.0, 50.0)
    val outlineVariant = variantTone(30.0, 80.0)

    // surface 阶梯：深色档层级越高越亮，浅色档反之。tone 值对齐改造前的
    // background 95 / surface 99（浅）和 5 / 10（深），层级关系不变、只染上主题色相。
    // containerHigh 单独用更淡的 neutralFaint（彩度 2.0）：它喂 AlertDialog 的默认底，
    // 对话框压在页面正中、染色最扎眼，比页面底再淡一档；顶栏滚动态同槽位一起变淡。
    val background = neutralTone(5.0, 95.0)
    val surface = neutralTone(10.0, 99.0)
    val containerLowest = neutralTone(3.0, 100.0)
    val containerLow = neutralTone(7.0, 94.0)
    val container = neutralTone(12.0, 93.0)
    val containerHigh = faintTone(16.0, 91.0)
    // 浅色档 containerHighest 压到 tone 84 而不是 M3 的 90：填充输入框和未选中 chip 吃这一级，
    // 而本项目的卡片/对话框是 surfaceVariant（tone 90）。两者同 tone 时对比度只有 1.08，
    // 输入框压在卡片上看不出边界。84 给到 1.18，surfaceDim 跟着退到 81 让出位置。
    val containerHighest = neutralTone(21.0, 84.0)
    val surfaceBright = neutralTone(24.0, 99.0)
    val surfaceDim = neutralTone(5.0, 81.0)
    val inverseSurface = neutralTone(90.0, 20.0)
    val inverseOnSurface = neutralTone(20.0, 95.0)

    return if (dark) {
        darkColorScheme(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary, onSecondary = onColorFor(secondary),
            secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary, onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = background, onBackground = onBackground,
            surface = surface, onSurface = onBackground,
            surfaceVariant = surfaceVar, onSurfaceVariant = onSurfaceVar,
            surfaceContainerLowest = containerLowest,
            surfaceContainerLow = containerLow,
            surfaceContainer = container,
            surfaceContainerHigh = containerHigh,
            surfaceContainerHighest = containerHighest,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceTint = primary,
            inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
            outline = outline, outlineVariant = outlineVariant,
            scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary, onSecondary = onColorFor(secondary),
            secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary, onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = background, onBackground = onBackground,
            surface = surface, onSurface = onBackground,
            surfaceVariant = surfaceVar, onSurfaceVariant = onSurfaceVar,
            surfaceContainerLowest = containerLowest,
            surfaceContainerLow = containerLow,
            surfaceContainer = container,
            surfaceContainerHigh = containerHigh,
            surfaceContainerHighest = containerHighest,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceTint = primary,
            inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
            outline = outline, outlineVariant = outlineVariant,
            scrim = Color.Black,
        )
    }
}

@Composable
fun TraktoSearchTheme(
    themeMode: String = "system",
    accentColor: MonetAccent? = null,
    customAccentArgb: Long? = null,
    visualEffectMode: VisualEffectMode = VisualEffectMode.BLUR,
    glassVariant: GlassVariant = GlassVariant.CLEAR,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val baseColorScheme = when {
        customAccentArgb != null -> monetColorScheme(
            seed = Color(customAccentArgb.toInt()),
            dark = darkTheme
        )
        accentColor == MonetAccent.VINTAGE_TICKET -> vintageTicketColorScheme(darkTheme)
        // 自定义莫奈主题色：直接用种子色生成 scheme，忽略动态壁纸
        accentColor != null -> monetColorScheme(
            seed = if (darkTheme) accentColor.dark else accentColor.light,
            dark = darkTheme
        )
        // 默认：Android 12+ 使用壁纸动态颜色。
        // 不再把 background/surface 覆盖成固定的冷灰 —— 动态方案自带整条
        // 跟壁纸同色相的 surface 阶梯，覆盖掉两级只会让它跟自己的 container 阶梯打架。
        Build.VERSION.SDK_INT >= 31 -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // 低版本拿不到壁纸取色：用 Trakt 品牌红当种子走同一套生成器。
        // 原先这里是两份手写 scheme，只设到 outline 就停了，
        // 整条 container 阶梯落回 Material 3 的紫调基线。
        else -> monetColorScheme(seed = if (darkTheme) Red500 else Red700, dark = darkTheme)
    }

    // 页面底色分层：baseColorScheme.background 是「主界面染色底」。子页面（NavHost 里
    // 除 MAIN 外的目的地）统一覆盖成纯中性灰；票根主题的牛皮纸本身就是底色而非染色，
    // 整站保持一致，不做覆盖。LocalMainColorScheme 保留 baseColorScheme 供 MainScreen 子树使用。
    val isVintageTheme = accentColor == MonetAccent.VINTAGE_TICKET
    val pageColorScheme = if (isVintageTheme) {
        baseColorScheme
    } else {
        baseColorScheme.copy(
            background = if (darkTheme) NeutralPageBackgroundDark else NeutralPageBackgroundLight,
            onBackground = if (darkTheme) NeutralPageInkDark else NeutralPageInkLight,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = pageColorScheme,
        typography = Typography,
    ) {
        // 统一覆盖 Haze 未显式设置时的噪点默认值，显式 noiseFactor(0f) 仍然优先。
        CompositionLocalProvider(
            LocalMainColorScheme provides baseColorScheme,
            LocalHazeBlurStyle provides AppHazeDefaultBlurStyle,
            LocalVisualEffectMode provides visualEffectMode,
            LocalGlassVariant provides glassVariant,
            content = content
        )
    }
}
