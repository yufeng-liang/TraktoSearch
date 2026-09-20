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
     * TV 标签底**压深时的锚点**对比度。
     *
     * 先把时代主色压深到对纸色达到这个比值（保留色相、只降明度），
     * 再按 [TV_BADGE_FILL_DILUTION] 朝纸色退回来。
     *
     * 为什么要有这个锚点：直接拿主色原色当底，太浅的时代（Fearless 的金 1.96:1、
     * 1989 的淡天蓝 1.62:1）标的底与纸几乎同色；先压到 4.5:1 再退，
     * 每个时代的底色深浅就都落在同一档，不会有的清楚有的看不见。
     *
     * 压在**纸色**上算而不是压在最坏情况底色上：标签是一块不透明实心图元，
     * 它盖住底下的母题与天空，字只跟标签自己的底发生关系。
     */
    const val TV_BADGE_FILL_CONTRAST: Float = AA_SMALL

    /**
     * 标签底朝纸色退回的比例。0f = 用压深后的实色，1f = 完全退回纸色（看不见）。
     *
     * 2026-09-20 需求方在真机上过了三轮：先是「浅底 + 深描边空心字」（描边太重，
     * 像一圈红线圈着两个字），改成压到 4.5:1 的实色块之后**又嫌太深**，
     * 定在「一半浓度」。
     *
     * 取 0.5 之后四张有 TV 的专辑落在 `#C2B384` / `#B69CCF` / `#EB8087` / `#8ABBD0`，
     * 镂空纸色字对底约 1.95~2.29:1 —— **低于正文 AA 的 4.5:1**，这是需求方看着真机
     * 定下的观感取舍：标签是标记而不是正文，读得出来即可，要的是柔和不抢曲目名。
     * 想再调浓度只改这一个数：调大更浅、调小更深。
     */
    const val TV_BADGE_FILL_DILUTION: Float = 0.5f

    /**
     * 标签的圆角，按**标签高度**的比例给。
     *
     * 需求方看过三档渲染（8px / 12px / 全胶囊）后定了 12px。现行标签高
     * （见 [TV_BADGE_HEIGHT_RATIO]）在 Red 那张卡上是 33px，12px 即 0.364。
     * 用比例而不是绝对值，是为了让标签在小屏压矮时圆角跟着收，
     * 既不会退化成胶囊，也不会在小尺寸上显得过方。
     */
    const val TV_BADGE_CORNER_RATIO: Float = 0.364f

    /**
     * 标签高度占行高的比例。
     *
     * 33 / 59（Red 实测行高）= 0.559，与 [TV_BADGE_FONT_RATIO] 同比例缩放。
     *
     * 2026-09-20 需求方在真机上指出标签「太显眼、和曲目名不协调」，
     * 于是把字号从 0.75 收到 0.52、标签高也按同一比例从 47px 收到 33px ——
     * **只缩字不缩盒会让字在盒子里吊着**，两者必须一起动。
     */
    const val TV_BADGE_HEIGHT_RATIO: Float = 0.559f

    /**
     * 标签左右内边距占**标签高度**的比例。
     *
     * 9px 内边距 / 33px 标签高 = 0.273。按标签高而不是字号给，
     * 是因为胶囊的饱满度取决于内边距与高之比，不随字宽变。
     */
    const val TV_BADGE_PAD_RATIO: Float = 0.273f

    /**
     * 标签内字号占行高的比例，**比曲目名小一号**。
     *
     * 曲目名是行高的 0.75（见 `SwiftieEraTracklist.TRACK_TITLE_FONT_RATIO`），
     * 标签取 0.52 —— 在 Red 那张卡上就是曲名 12sp / 标签 8.3sp。
     *
     * 这个差距是有意的：标签里的字与曲目名同大时（原先也是 0.75），
     * 字被底色围住、视觉权重反而压过曲名，需求方在真机上读作「太显眼，
     * 和曲目名不协调」。缩小之后曲目名重新成为这一行的主体。
     */
    const val TV_BADGE_FONT_RATIO: Float = 0.52f

    /**
     * 标签左沿与曲名墨迹之间至少留的间隙，占行高的比例。
     *
     * 曲名是 `weight(1f, fill = false)` 先让出标签的固有宽度再省略，中间的间隙要
     * 由标签自己带成一个 Spacer 的宽度 —— 否则长曲名会把标签顶到紧贴墨迹的位置。
     *
     * 按行高给而不是按字号给：这个间隙要跟着**整行**的呼吸走。短歌名（`Run`）
     * 那边会有 weight 列的余量垫着，实际间距比这里大，不会显得散。
     */
    const val TV_BADGE_GAP_RATIO: Float = 0.16f

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
     * 标签底色：时代实色，压深到**纸色字能读出来**（见
     * [SwiftieEraContrast.TV_BADGE_FILL_CONTRAST]）。
     */
    val badgeFill: Color = SwiftieEraContrast.tvBadgeFill(era.mainColor)

    /**
     * 标签的镂空字身：就是卡片填充色，字被「挖」出来露出纸面。
     *
     * 它对 [badgeFill] 的比值就是字本身的可读性（无描边）。这个比值由
     * [SwiftieEraContrast.TV_BADGE_FILL_CONTRAST] 与
     * [SwiftieEraContrast.TV_BADGE_FILL_DILUTION] 两个常量共同定出来，
     * 当前定在约 2:1 的观感档（需求方在真机上选定）。
     */
    val badgeKnockout: Color = SwiftieEraContrast.cardFill(era.mainColor)
}

/**
 * TV 标签底色：时代主色先压深到 [SwiftieEraContrast.TV_BADGE_FILL_CONTRAST]，
 * 再按 [SwiftieEraContrast.TV_BADGE_FILL_DILUTION] 朝纸色退回。
 *
 * 两步各有分工：
 * 1. [readable] 保留色相与饱和度、只降明度，把各时代的标签底先统一到同一档深浅 ——
 *    否则原色浅的时代（1989 的淡天蓝 1.62:1）标的底与纸几乎同色，深的又太抢眼。
 * 2. 再朝纸色按比例退回来，得到需求方在真机上定下的「一半浓度」。
 *
 * 四张有 TV 的专辑最终落在 `#C2B384` 金 / `#B69CCF` 紫 / `#EB8087` 红 / `#8ABBD0` 蓝，
 * 各自仍认得出时代色。
 *
 * **注意这里是压在纸色 [cardFill] 上算**，不是压在 `cardBackground`（最坏情况底色）
 * 上 —— 标签是不透明实心块，它盖住底下的母题与天空，字只跟标签自己的底发生关系。
 */
private fun SwiftieEraContrast.tvBadgeFill(mainColor: Color): Color =
    composite(
        fg = readable(
            color = mainColor,
            background = cardFill(mainColor),
            target = TV_BADGE_FILL_CONTRAST
        ),
        alpha = 1f - TV_BADGE_FILL_DILUTION,
        bg = cardFill(mainColor)
    )
