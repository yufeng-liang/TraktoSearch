package com.tracktosearch.ui.screen.help

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 纸面编号与命中高亮的单测。
 *
 * 编号是四语言各一套：壹贰叁只在中文成立，日韩要一二三，拉丁语系要罗马数字。三张表
 * 都写死在代码里，最容易犯的错是目录长到第十五段却忘了补表——`编号表覆盖整本目录`
 * 就是守这个的。
 */
class HelpPaperTest {

    private val seal = Color(0xFFB4472F)

    @Test
    fun `中文段落编号用大写数字`() {
        assertThat(helpSectionNumeral("zh", 0)).isEqualTo("壹")
        assertThat(helpSectionNumeral("zh", 9)).isEqualTo("拾")
        assertThat(helpSectionNumeral("zh", 13)).isEqualTo("拾肆")
    }

    @Test
    fun `日韩段落编号用汉数字`() {
        assertThat(helpSectionNumeral("ja", 0)).isEqualTo("一")
        assertThat(helpSectionNumeral("ko", 0)).isEqualTo("一")
        assertThat(helpSectionNumeral("ja", 13)).isEqualTo("十四")
        assertThat(helpSectionNumeral("ko", 10)).isEqualTo("十一")
    }

    @Test
    fun `英文段落编号用罗马数字`() {
        assertThat(helpSectionNumeral("en", 0)).isEqualTo("I")
        assertThat(helpSectionNumeral("en", 3)).isEqualTo("IV")
        assertThat(helpSectionNumeral("en", 13)).isEqualTo("XIV")
    }

    @Test
    fun `没预置的语言退回罗马数字`() {
        assertThat(helpSectionNumeral("de", 4)).isEqualTo("V")
    }

    @Test
    fun `编号表覆盖整本目录`() {
        // 退回值是阿拉伯数字，三张表都不含阿拉伯数字，所以「等于下标加一」就等于漏了
        listOf("zh", "ja", "ko", "en").forEach { lang ->
            HelpCatalog.indices.forEach { index ->
                assertThat(helpSectionNumeral(lang, index)).isNotEqualTo("${index + 1}")
            }
        }
    }

    @Test
    fun `超出编号表时退回阿拉伯数字而不是崩掉`() {
        assertThat(helpSectionNumeral("zh", 14)).isEqualTo("15")
        assertThat(helpSectionNumeral("en", 99)).isEqualTo("100")
    }

    @Test
    fun `条目编号中日韩用汉数字`() {
        assertThat(helpItemNumeral("zh", 0)).isEqualTo("一")
        assertThat(helpItemNumeral("ja", 9)).isEqualTo("十")
        assertThat(helpItemNumeral("ko", 10)).isEqualTo("十一")
    }

    @Test
    fun `条目编号拉丁语系用阿拉伯数字`() {
        // 条目不用罗马数字：正文里的 I II III 会和段落编号撞辈分
        assertThat(helpItemNumeral("en", 0)).isEqualTo("1")
        assertThat(helpItemNumeral("en", 9)).isEqualTo("10")
        assertThat(helpItemNumeral("de", 2)).isEqualTo("3")
    }

    @Test
    fun `命中的词标成朱砂`() {
        val result = helpHighlight("聚合四个搜索源", "搜索", seal)
        assertThat(result.text).isEqualTo("聚合四个搜索源")
        assertThat(result.spanStyles).hasSize(1)
        assertThat(result.spanStyles.first().item.color).isEqualTo(seal)
        assertThat(result.spanStyles.first().start).isEqualTo(4)
        assertThat(result.spanStyles.first().end).isEqualTo(6)
    }

    @Test
    fun `一句里多处命中都标上`() {
        val result = helpHighlight("搜索源和搜索历史", "搜索", seal)
        assertThat(result.spanStyles).hasSize(2)
        assertThat(result.text).isEqualTo("搜索源和搜索历史")
    }

    @Test
    fun `大小写不同也标`() {
        val result = helpHighlight("parseMode 决定解析方式", "PARSEMODE", seal)
        assertThat(result.spanStyles).hasSize(1)
        // 原文大小写不能被搜索词改写
        assertThat(result.text).isEqualTo("parseMode 决定解析方式")
    }

    @Test
    fun `空词不做任何标记`() {
        assertThat(helpHighlight("想看与已看", "", seal).spanStyles).isEmpty()
        assertThat(helpHighlight("想看与已看", "   ", seal).spanStyles).isEmpty()
    }

    @Test
    fun `搜不到时原样返回`() {
        val result = helpHighlight("想看与已看", "热力图", seal)
        assertThat(result.text).isEqualTo("想看与已看")
        assertThat(result.spanStyles).isEmpty()
    }

    @Test
    fun `命中在句尾也标得出来`() {
        // 循环边界：命中落在最后 length 个字符上时不能被 cursor 条件挡掉
        val result = helpHighlight("导出为 CSV", "CSV", seal)
        assertThat(result.spanStyles).hasSize(1)
        assertThat(result.spanStyles.first().start).isEqualTo(4)
        assertThat(result.spanStyles.first().end).isEqualTo(7)
    }
}
