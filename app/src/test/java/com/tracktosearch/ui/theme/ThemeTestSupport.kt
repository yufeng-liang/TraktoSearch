package com.tracktosearch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 三个配色测试类共用的取值工具。
 *
 * 抽出来是因为「怎么拿到某个色调的 ColorScheme」这件事有一处例外
 * （[MonetAccent.VINTAGE_TICKET] 走手写 scheme 而不是生成器），
 * 抄三遍的话哪天再加一个例外主题就会漏改其中一两处。
 */
internal object ThemeTestSupport {

    /** 所有内置色调 × 明暗两档，测试遍历的标准笛卡尔积。 */
    val allSchemes: List<SchemeCase> =
        MonetAccent.entries.flatMap { accent ->
            listOf(false, true).map { dark -> SchemeCase(accent, dark, schemeFor(accent, dark)) }
        }

    fun schemeFor(accent: MonetAccent, dark: Boolean): ColorScheme =
        if (accent == MonetAccent.VINTAGE_TICKET) {
            vintageTicketColorScheme(dark)
        } else {
            monetColorScheme(if (dark) accent.dark else accent.light, dark)
        }

    /**
     * WCAG 对比度。这里刻意不复用生产代码的 [contrastRatio] ——
     * 测试要能独立验出生产实现算错的情况，共用一份就变成自己验自己。
     */
    fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** WCAG AA 普通字号。按钮文字多是 labelLarge 14sp 不加粗，按普通字号要求。 */
    const val AA_NORMAL = 4.5

    data class SchemeCase(
        val accent: MonetAccent,
        val dark: Boolean,
        val scheme: ColorScheme
    ) {
        /** 断言消息前缀，红的时候一眼看出是哪个色调哪一档。 */
        val label: String get() = "${accent.name} dark=$dark"
    }
}
