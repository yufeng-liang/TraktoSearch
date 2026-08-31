package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.graphics.Color

/**
 * 卡片文字配色的对比度校正。
 *
 * Spec §6.2 原本要求「曲目名用该时代主色」，但 12 个时代主色里有 6 个是浅色
 * （`1989` 的 `#A8CDE0`、`Lover` 的 `#F7A8C4`、`Fearless` 的 `#D4AF37`……），
 * 直接印在半透明白卡上只有 1.57–2.97:1，12sp 的曲目名根本读不出来 —— 而这一段
 * 94 秒的全部意义就是让人**看清**整个历程。
 *
 * 因此保留主色的**色相与饱和度**，只压低明度到刚好满足 WCAG AA 4.5:1。
 * `1989` 会从淡天蓝变成深天蓝，`Lover` 从淡粉变成玫红 —— 时代辨识度还在，
 * 但字读得出来了。色带、母题、卡片薄底仍用**未压暗的原主色**，装饰不受影响。
 */
internal object SwiftieEraContrast {

    /** WCAG AA 对小字号的要求。12sp 与 11sp 都算小字号，没有 3:1 的宽免。 */
    const val AA_SMALL: Float = 4.5f

    /** 序号相对曲目名压一档透明度做层级，仍要求压暗后满足 AA。 */
    const val NUMBER_ALPHA: Float = 0.85f

    /** 发行日期同样是信息而非装饰，只比曲目名淡一点。 */
    const val DATE_ALPHA: Float = 0.85f

    /**
     * 卡片上文字实际压着的底色。
     *
     * 叠了三层：水彩天空 → `Color.White.copy(alpha = 0.86f)` 的白纸 →
     * `drawRect(mainColor, alpha = 0.10f)` 的主色薄底。天空取 Lover 配色里最亮的
     * 粉白 `#FBE4EE`：底色越亮，浅色文字的对比度越差，按最坏情况算才安全。
     */
    fun cardBackground(mainColor: Color): Color {
        val paper = composite(Color.White, 0.86f, Color(0xFFFBE4EE))
        return composite(mainColor, 0.10f, paper)
    }

    /** [fg] 以 [alpha] 压在 [bg] 上之后的实色。 */
    fun composite(fg: Color, alpha: Float, bg: Color): Color = Color(
        red = fg.red * alpha + bg.red * (1f - alpha),
        green = fg.green * alpha + bg.green * (1f - alpha),
        blue = fg.blue * alpha + bg.blue * (1f - alpha)
    )

    /** WCAG 相对亮度。 */
    fun luminance(color: Color): Float {
        fun channel(c: Float): Float =
            if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4)
                .toFloat()
        return 0.2126f * channel(color.red) +
            0.7152f * channel(color.green) +
            0.0722f * channel(color.blue)
    }

    /** WCAG 对比度，1f..21f。 */
    fun contrastRatio(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /**
     * 把 [color] 压暗到「以 [alpha] 压在 [background] 上后对比度 ≥ [target]」。
     *
     * 已达标就原样返回（`reputation` 的 `#111111`、`Midnights` 的 `#1B2A5B`、
     * TTPD 覆写的 `#4A453E` 都不会被动）。达不到就在 HSL 明度上二分。
     */
    fun readable(
        color: Color,
        background: Color,
        target: Float = AA_SMALL,
        alpha: Float = 1f
    ): Color {
        if (contrastRatio(composite(color, alpha, background), background) >= target) return color
        val (hue, saturation, lightness) = toHsl(color)
        var lo = 0f
        var hi = lightness
        repeat(HSL_BISECTION_STEPS) {
            val mid = (lo + hi) / 2f
            val candidate = fromHsl(hue, saturation, mid)
            if (contrastRatio(composite(candidate, alpha, background), background) >= target) {
                lo = mid
            } else {
                hi = mid
            }
        }
        return fromHsl(hue, saturation, lo)
    }

    /** 24 次二分把明度定位到 1/16777216，远细于 8bit 通道能表达的精度。 */
    private const val HSL_BISECTION_STEPS = 24

    private data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

    private fun toHsl(color: Color): Hsl {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        val lightness = (max + min) / 2f
        if (max == min) return Hsl(0f, 0f, lightness)
        val delta = max - min
        val saturation =
            if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
        val hue = when (max) {
            color.red -> (color.green - color.blue) / delta + if (color.green < color.blue) 6f else 0f
            color.green -> (color.blue - color.red) / delta + 2f
            else -> (color.red - color.green) / delta + 4f
        } / 6f
        return Hsl(hue, saturation, lightness)
    }

    private fun fromHsl(hue: Float, saturation: Float, lightness: Float): Color {
        if (saturation == 0f) return Color(lightness, lightness, lightness)
        val q = if (lightness < 0.5f) {
            lightness * (1f + saturation)
        } else {
            lightness + saturation - lightness * saturation
        }
        val p = 2f * lightness - q
        return Color(
            red = hueToChannel(p, q, hue + 1f / 3f),
            green = hueToChannel(p, q, hue),
            blue = hueToChannel(p, q, hue - 1f / 3f)
        )
    }

    private fun hueToChannel(p: Float, q: Float, rawT: Float): Float {
        var t = rawT
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
}

/** 这个时代在卡片上用的一组文字色。12 张各算一次，算完 `remember` 住。 */
internal class SwiftieEraTextColors(era: SwiftieEra) {
    private val background = SwiftieEraContrast.cardBackground(era.mainColor)

    /** 专辑名与曲目名。 */
    val body: Color = SwiftieEraContrast.readable(era.textColor, background)

    /** 曲目序号，压一档透明度做层级。 */
    val number: Color = SwiftieEraContrast.readable(
        color = era.textColor,
        background = background,
        alpha = SwiftieEraContrast.NUMBER_ALPHA
    )

    /** 发行日期。 */
    val date: Color = SwiftieEraContrast.readable(
        color = era.textColor,
        background = background,
        alpha = SwiftieEraContrast.DATE_ALPHA
    )
}
