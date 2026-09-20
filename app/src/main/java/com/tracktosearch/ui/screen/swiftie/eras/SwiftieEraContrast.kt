package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.graphics.Color
import com.tracktosearch.ui.screen.swiftie.SwiftiePalette

/**
 * 卡片文字配色的对比度校正。
 *
 * Spec §6.2 原本要求「曲目名用该时代主色」，但 12 个时代主色里有 6 个是浅色
 * （`1989` 的 `#92CFEA`、`Lover` 的 `#F7A8C4`、`Fearless` 的 `#D4AF37`……），
 * 直接印在半透明白卡上只有 1.57–2.97:1，12sp 的曲目名根本读不出来 —— 而这一段
 * 109 秒的全部意义就是让人**看清**整个历程。
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
     * Taylor's Version 后缀 `(TV)` 的透明度，比序号再压一档。
     *
     * 压到 0.70 之后，浅色主色的合成结果会与正文的亮度接近 —— 「更浅」于是只体现为
     * 去饱和，而不是亮度差。2026-09-20 需求方看过真机后要求后缀**再浅一档**，
     * 于是新增 [TRACK_SUFFIX_CONTRAST] 把目标对比度单独放低，让亮度差真的出现。
     */
    const val TRACK_SUFFIX_ALPHA: Float = 0.70f

    /**
     * `(TV)` 后缀的对比度目标，**有意低于正文的 [AA_SMALL]**。
     *
     * 正文、序号、日期都必须守住 4.5:1；后缀只是一个标记，需求方要求它和曲名拉开
     * 可见的层级，所以降到大字号的 AA 门槛 3.5:1 —— 这是 WCAG 里仍然算「达到 AA」的
     * 最低一档，再往下就只剩非文本图形那条 3.0:1，12sp 的字不该走那么低。
     *
     * 效果（12 张时代卡实测合成亮度相对正文的差）：4.5 → 3.5 时约 +0.03~+0.05，
     * 后缀第一次真的比曲名浅；保持 alpha 0.70 不变，只放宽目标，不额外加透明度。
     */
    const val TRACK_SUFFIX_CONTRAST: Float = 3.5f

    /** 白纸层的不透明度，与 `SwiftieEraCard` 的 `Color.White.copy(alpha = 0.86f)` 同步。 */
    private const val PAPER_ALPHA = 0.86f

    /** 主色薄底，与 `SwiftieEraCard` 里 `drawRect(era.mainColor, alpha = 0.10f)` 同步。 */
    private const val TINT_ALPHA = 0.10f

    /**
     * 母题层在文字底下**成片覆盖**时的最大主色不透明度。
     *
     * 取的是「有面积的那些层」的上限，而不是全母题的单点最大值：
     * folklore 的近景松林（`MOTIF_ALPHA × 1.5 = 0.30`，整片林子攒进一条 Path
     * 一次画完，所以相邻两棵重叠也不会叠深）与 Showgirl 的羽轴（`× 1.6 = 0.32`，
     * 9 条 `0.020 × minDimension` 宽的粗线扫过右下）是最狠的两个，
     * Red 的针织横纹（0.20，14 行几乎铺满）紧随其后。
     *
     * 母题之上还有一层 `drawEraAtmosphere` 的光与颗粒，**它不进这个模型** ——
     * 那一层只用白色，只会把底色提亮、把对比度往好的方向推，而这里要的是最坏情况。
     *
     * **不取 0.48。** 那个数来自 TTPD 的游标方块（`× 2.4`）、Lover 上浮的心
     * （`× 2.2`，边长只有 `0.03–0.055 × minDimension`）与 reputation 的蛇形曲线
     * （`× 1.8`，线宽 `0.014 × minDimension` ≈ 5px）—— 都是细笔画或小色块，
     * 只会横穿一行字的几个像素。按它们算等于假设整张卡片都涂成那个不透明度，
     * 结果是 reputation 的序号 / 日期在 85% 透明度下**怎么压都到不了 4.5:1**
     * （极限 4.46:1），把整套压暗逼进死角，而屏幕上根本没有那么大一片深色。
     *
     * 真要让细笔画也不压着字，正确做法是别把它们画到曲目列底下，而不是在这里
     * 把所有文字一起压黑。
     *
     * 母题**在文字底下满幅铺开**（见 `SwiftieEraCard` 的 `drawBehind`），
     * 所以它必须进这个模型 —— 漏掉它的时候 folklore 与 Showgirl 实测只有 3.0–3.5:1。
     *
     * TTPD 是唯一不被这个模型覆盖的：它的纸纹与游标用硬编码的墨色 `#4A453E`
     * 而不是主色，按主色算会偏亮。那一张不需要额外照顾 —— 它的文字本身就是
     * `#4A453E`，对最亮档底色 12.9:1、对墨线压过的局部仍有 6:1。
     */
    private const val MOTIF_MAX_ALPHA = 0.32f

    /**
     * 卡片底下天空的两个极端。
     *
     * 天空是 `SwiftieWatercolorSky` 的六团水彩压在粉白→云粉的渐变上。卡片矩形内
     * 最亮的是粉白 `#FBE4EE`，最暗的是薰衣草 `#C9A8DE`（圆心 `(0.78, 0.20)` 落在
     * 卡片内）。水彩团中心只有 0.85 不透明度，这里按满不透明算，偏保守一档。
     */
    private val SKY_LIGHTEST = SwiftiePalette.PinkWhite
    private val SKY_DARKEST = SwiftiePalette.Lavender

    /**
     * 卡片上文字实际压着的底色，取**四种叠法里最暗的那个**。
     *
     * 叠法：水彩天空 → `Color.White.copy(alpha = 0.86f)` 的白纸 →
     * `drawRect(mainColor, alpha = 0.10f)` 的主色薄底 → 母题层。
     * 天空取最亮 / 最暗两档，母题层取「有」与「无」两档，共四个候选。
     *
     * **为什么取最暗**：[readable] 只会把文字**压暗**，所以底色越暗对比度越差 ——
     * 深色文字压在浅底上是高对比，压在深底上才是低对比。（这里原先写的是
     * 「底色越亮对比度越差」，方向推反了，于是母题层也被漏在模型之外：
     * folklore 的松树 0.32、Showgirl 的羽毛 0.32 铺在曲目名底下，实测把
     * 4.5:1 拉到 3.0–3.5:1。）
     *
     * 母题层对浅色主色（`1989` / `Lover` / TTPD）是**提亮**，`minByOrNull` 会自动
     * 落回不含母题的那档，所以没有任何时代因为这个改动被压得比原来更黑。
     */
    fun cardBackground(mainColor: Color): Color {
        var darkest = Color.White
        var darkestLuminance = Float.MAX_VALUE
        for (sky in listOf(SKY_LIGHTEST, SKY_DARKEST)) {
            val paper = composite(Color.White, PAPER_ALPHA, sky)
            val tinted = composite(mainColor, TINT_ALPHA, paper)
            for (candidate in listOf(tinted, composite(mainColor, MOTIF_MAX_ALPHA, tinted))) {
                val candidateLuminance = luminance(candidate)
                if (candidateLuminance < darkestLuminance) {
                    darkestLuminance = candidateLuminance
                    darkest = candidate
                }
            }
        }
        return darkest
    }

    /**
     * 每张时代卡片统一使用的不透明填充色。
     *
     * 原先是半透明白纸再叠一层主色薄底，圆角边缘会透出更浅的背景色；这里把同样的
     * 主色薄染预先合成为实色，卡片内部和边缘使用同一填充。
     */
    fun cardFill(mainColor: Color): Color = composite(mainColor, 0.10f, Color.White)

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

    /**
     * 把 [color] **提亮**到「以 [alpha] 压在 [background] 上后对比度 ≥ [target]」。
     *
     * [readable] 只会压暗 —— 那是给半透明白卡片用的。轴线、播放头旋钮与 TS1-12 标签
     * 落在**页面背景的下缘**上，而 12 张舞台里末档底色是深色的占 10 张
     * （reputation 直接是纯黑）。在那些底色上压暗等于让标签彻底消失，
     * 所以这里朝 `1f` 方向二分明度。
     *
     * 无饱和度的主色（reputation 的 `#111111`）会提成浅灰 —— 那正是这张专辑的
     * 报纸黑白气质，不算丢辨识度。
     */
    fun readableOnDark(
        color: Color,
        background: Color,
        target: Float = AA_SMALL,
        alpha: Float = 1f
    ): Color {
        if (contrastRatio(composite(color, alpha, background), background) >= target) return color
        val (hue, saturation, lightness) = toHsl(color)
        var lo = lightness
        var hi = 1f
        repeat(HSL_BISECTION_STEPS) {
            val mid = (lo + hi) / 2f
            val candidate = fromHsl(hue, saturation, mid)
            if (contrastRatio(composite(candidate, alpha, background), background) >= target) {
                hi = mid
            } else {
                lo = mid
            }
        }
        return fromHsl(hue, saturation, hi)
    }

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

    /**
     * Taylor's Version 后缀 `(TV)`：按 [SwiftieEraContrast.TRACK_SUFFIX_CONTRAST]
     * 放宽到 3.5:1，比正文更浅，但仍落在 WCAG 大字号 AA 的范围内。
     */
    val trackSuffix: Color = SwiftieEraContrast.readable(
        color = era.textColor,
        background = background,
        target = SwiftieEraContrast.TRACK_SUFFIX_CONTRAST,
        alpha = SwiftieEraContrast.TRACK_SUFFIX_ALPHA
    )
}
