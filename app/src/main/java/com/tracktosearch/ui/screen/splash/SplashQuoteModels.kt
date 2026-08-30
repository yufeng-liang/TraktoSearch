package com.tracktosearch.ui.screen.splash

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 开屏台词层要显示的一切，已在后台线程准备完毕。
 *
 * [poster] 是解好的位图而不是 URL：台词层只活几秒，中途再去异步加载图片
 * 必然出现「先空着后跳出来」的观感，而开屏的要求正是永远不出现占位状态。
 * 拿不到海报的台词根本不会走到这里（见 SplashQuoteRepository.todayQuote）。
 */
@Immutable
data class SplashQuoteUi(
    /** 台词库里的 id，开屏结束后拿它记当天的日签，保证日历翻回来是同一条 */
    val quoteId: String,
    val lines: List<String>,
    val title: String,
    val year: Int,
    val poster: ImageBitmap?,
    /** 英文台词用斜体衬线，字号也更小一档，排版规则和 CJK 不同 */
    val isEnglish: Boolean,
    /** 片名的书名号：中韩《》、日『』、英文不加 */
    val titleWrap: Pair<String, String>,
    /** 印章上的日签关键词，跟界面语言 */
    val keyword: String,
    /**
     * 刻在印面上的字形：中文是繁体，其余语言与 [keyword] 相同。
     *
     * 和 [keyword] 分开存而不是就地替换：读屏念的、日历格子上写的都该是界面语言的字形，
     * 只有印面走繁体。见 SplashQuote.sealKeywordFor。
     */
    val sealKeyword: String,
    /**
     * [sealKeyword] 那个字形所属的语言，印章用它选字体。
     *
     * 中文是 zh 而不是 zh-Hant：繁体只是字形，选的还是那份中文隸書。见 QuoteSeal.sealTypeface。
     */
    val sealLang: String,
    /**
     * 印在关键词下方的英文小字。
     *
     * 界面本来就是英文时为 null——同一个词印两遍不是设计，是重复。
     * CJK 语言下这行小字是印章的一部分：方块字在上、拉丁小字在下，像老书的中英书名页。
     */
    val keywordLatin: String?,
    /**
     * 当天是不是第一次看到开屏台词，决定这一层怎么演：逐行浮现还是整页一次摊开，
     * 以及停留多久（见 [SplashQuoteTiming.stay] 与 SplashQuoteOverlay 的 instant）。
     *
     * 这个值由加载阶段一并算好塞进来，而不是让台词层自己去问存储：这一层只活几秒，
     * 演法和时长必须在第一帧就定下来。若改成 Overlay 里异步查，查得慢一点计时早就跑掉了，
     * 「读得完」和「别挡路」两档会撞成竞态——同一台机器上两次启动都可能不一样，
     * 更糟的是逐行浮现可能演到一半才知道今天已经看过。
     */
    val isFirstToday: Boolean,
)

/**
 * 台词层专用的暖纸色板，不跟随主题强调色。
 *
 * 这一层是「电影票根 + 相纸」的固定气质，跟着用户选的强调色变会失去统一感；
 * 只区分明暗两套，与系统场记板 splash 的配色同源。
 */
@Immutable
internal data class SplashPalette(
    val paper: Color,
    /**
     * 正文墨色，台词本身用它。
     *
     * 明色这一档从 #996345 压到了 #7F5137：老色号实心压在 [paper] 上只有 4.35:1，
     * 意思是不管透明度怎么调都到不了 WCAG AA 要的 4.5:1，只能把墨本身调深。
     * 同色相往下一档的 #7F5137 是 5.87:1——够用，又还看得出是暖褐钢笔字；
     * 再深就成了黑字，纸也不像纸了。暗色的 #F0E2CE 压在深棕上是 13.45:1，不用动。
     */
    val ink: Color,
    val caramel: Color,
    val ochre: Color,
    val cream: Color,
    /**
     * 次级墨色：出处、片名、年份这类注解性文字。
     *
     * 明色是 ink@90%，4.72:1。老值 0.62 只有 2.31:1——「弱化一档」弱到了读不出来；
     * 而 [ink] 定在 #7F5137 之后，想够 4.5:1 至少要 0.88，能留给「弱」的余量本来就只剩一成，
     * 注解感只好交给字号和括号去表达。暗色的 0.60 已经是 5.62:1，保持原样。
     */
    val inkSoft: Color,
    /**
     * 最淡的一档，画线用：分隔线、边框、虚线、禁用态图标。
     *
     * 明色 1.60:1、暗色 2.23:1，都远在可读线之下——这是线的颜色，不是字的颜色。
     * 日签页拿它画撕口虚线和日历格线，为了某一行字把它压深，那一屏的细线会立刻变成描边；
     * 台词层顶部那行日期要够 3:1，是在 SplashQuoteOverlay 里按 [ink] 另兑的，没走这里。
     */
    val inkFaint: Color,
    /**
     * 印章的朱色。
     *
     * 整套色板本来只有暖褐一族，这是唯一的另一个色相：日签的关键词要刻成印，
     * 而印必须是朱红才读得出「印」的意思，用褐色画方框只会看成一个盒子。
     * 取的是陈年印泥的偏暖低饱和红，落在暖纸上不跳出去。
     */
    val seal: Color,
    /**
     * 铺在纸上的另一张纸。
     *
     * 日签卡片用它做卡面：比 [paper] 亮（暗色下比 paper 浅）一档，卡片才像叠在页面上，
     * 而不是页面上挖出来的一块。只差一档，差多了就成了对话框。
     */
    val sheet: Color,
    val grainAlpha: Float,
    /**
     * 四个漏光斑的基准透明度，见 SplashQuoteOverlay 的 LeakGlow。
     *
     * 比原先的 0.74/0.58 各低一档：光斑改成手摆的四个椭圆之后彼此有重叠，
     * 沿用老数值会把中央糊成一片亮，纸的质地就没了——光该是漏进来的，不是打上来的。
     */
    val glowAlpha: Float,
    val isDark: Boolean,
) {
    companion object {
        val Light = SplashPalette(
            paper = Color(0xFFF7EFE2),
            ink = Color(0xFF7F5137),
            caramel = Color(0xFFC98A4B),
            ochre = Color(0xFF8A5A2B),
            cream = Color(0xFFEFE0C8),
            inkSoft = Color(0xFF7F5137).copy(alpha = 0.90f),
            inkFaint = Color(0xFF7F5137).copy(alpha = 0.32f),
            seal = Color(0xFFB4472F),
            sheet = Color(0xFFFDF8EF),
            grainAlpha = 0.16f,
            glowAlpha = 0.48f,
            isDark = false,
        )
        val Dark = SplashPalette(
            paper = Color(0xFF241914),
            ink = Color(0xFFF0E2CE),
            caramel = Color(0xFF8A5A2B),
            ochre = Color(0xFF4E301C),
            cream = Color(0xFF33241A),
            inkSoft = Color(0xFFF0E2CE).copy(alpha = 0.60f),
            inkFaint = Color(0xFFF0E2CE).copy(alpha = 0.28f),
            seal = Color(0xFFC85A3E),
            sheet = Color(0xFF2E211A),
            grainAlpha = 0.22f,
            glowAlpha = 0.40f,
            isDark = true,
        )
    }
}

/**
 * 台词层的时间轴，单位毫秒，与 docs/previews/splash-daily-quote.html 的 T 常量同源。
 *
 * 停留时长按「今天是不是第一次看」分两档，见 [stay]：原型里只有一个 2000，实测四行台词读不完，
 * 提到 3000 也只够「已经读过、再扫一眼」。当天第一次看的人得先认海报再从头念，给 5000 才读得完整层；
 * 同一天再进 App 的仍是 3000——那时候多留一秒都是在挡路。
 *
 * 前面这些浮现时刻只用在当天第一次看那一遍。同一天再进 App 时整页从第一帧就是全的
 * （见 SplashQuoteOverlay 的 instant），[BLOOM_MS] 到 [SKIP_AT_MS] 这一段一个都不用等，
 * 整层就是 [STAY_REPEAT_MS] 加 [EXIT_MS]。
 */
internal object SplashQuoteTiming {
    const val BLOOM_MS = 400L
    const val POSTER_AT_MS = 120L
    const val LINE_STEP_MS = 100L
    const val LINE_DUR_MS = 400L
    const val SOURCE_AT_MS = 900L
    /**
     * 印章落下的时刻。
     *
     * 排在出处之后：先有画面、有台词、有出处，最后才盖印——顺序反了就像先盖章再写字。
     * 落在停留期开头，不占额外时长，整层总时长不变。
     */
    const val SEAL_AT_MS = 1250L
    const val SKIP_AT_MS = 1400L
    /** 当天第一次看这条台词的停留时长 */
    const val STAY_FIRST_MS = 5000L
    /** 同一天再进 App 的停留时长 */
    const val STAY_REPEAT_MS = 3000L
    const val EXIT_MS = 400L

    /** 台词全部浮现完毕的时刻：停留计时从这之后才开始，行多的台词自动多给时间 */
    fun linesEnd(lineCount: Int): Long =
        BLOOM_MS + (lineCount - 1).coerceAtLeast(0) * LINE_STEP_MS + LINE_DUR_MS

    fun stayStart(lineCount: Int): Long = maxOf(linesEnd(lineCount), SOURCE_AT_MS)

    /** 读完这一条要留多久，取决于今天见过没有，见 [SplashQuoteUi.isFirstToday] */
    fun stay(isFirstToday: Boolean): Long = if (isFirstToday) STAY_FIRST_MS else STAY_REPEAT_MS
}
