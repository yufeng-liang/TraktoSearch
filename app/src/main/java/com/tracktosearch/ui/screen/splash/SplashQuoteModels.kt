package com.tracktosearch.ui.screen.splash

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 开屏台词层要显示的一切，已在后台线程准备完毕。
 *
 * [poster] 是解好的位图而不是 URL：台词层只活 4 秒多，中途再去异步加载图片
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
     * 印在关键词下方的英文小字。
     *
     * 界面本来就是英文时为 null——同一个词印两遍不是设计，是重复。
     * CJK 语言下这行小字是印章的一部分：方块字在上、拉丁小字在下，像老书的中英书名页。
     */
    val keywordLatin: String?,
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
    val ink: Color,
    val caramel: Color,
    val ochre: Color,
    val cream: Color,
    val inkSoft: Color,
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
    val glowAlpha: Float,
    val isDark: Boolean,
) {
    companion object {
        val Light = SplashPalette(
            paper = Color(0xFFF7EFE2),
            ink = Color(0xFF996345),
            caramel = Color(0xFFC98A4B),
            ochre = Color(0xFF8A5A2B),
            cream = Color(0xFFEFE0C8),
            inkSoft = Color(0xFF996345).copy(alpha = 0.62f),
            inkFaint = Color(0xFF996345).copy(alpha = 0.32f),
            seal = Color(0xFFB4472F),
            sheet = Color(0xFFFDF8EF),
            grainAlpha = 0.16f,
            glowAlpha = 0.74f,
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
            glowAlpha = 0.58f,
            isDark = true,
        )
    }
}

/**
 * 台词层的时间轴，单位毫秒，与 docs/previews/splash-daily-quote.html 的 T 常量一致。
 *
 * [STAY_MS] 是「读完一句话」的停留时间。原型里是 2000，实测四行台词读不完，
 * 提到 3000：整层总时长 4.3-4.5 秒，仍在「一次点击就能跳过」的容忍范围内。
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
    const val STAY_MS = 3000L
    const val EXIT_MS = 400L

    /** 台词全部浮现完毕的时刻：停留计时从这之后才开始，行多的台词自动多给时间 */
    fun linesEnd(lineCount: Int): Long =
        BLOOM_MS + (lineCount - 1).coerceAtLeast(0) * LINE_STEP_MS + LINE_DUR_MS

    fun stayStart(lineCount: Int): Long = maxOf(linesEnd(lineCount), SOURCE_AT_MS)

    fun total(lineCount: Int): Long = stayStart(lineCount) + STAY_MS + EXIT_MS
}
