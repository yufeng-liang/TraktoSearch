package com.tracktosearch.ui.screen.help

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 说明书编号与命中高亮的单测。
 *
 * 章节编号不再按语言分支；正文圆圈数字必须覆盖 1 至 20，超出后安全回退普通数字。
 */
class HelpPaperTest {

    private val seal = Color(0xFFB4472F)

    @Test
    fun `章节统一使用阿拉伯数字`() {
        assertThat(helpSectionNumeral(0)).isEqualTo("1")
        assertThat(helpSectionNumeral(9)).isEqualTo("10")
        assertThat(helpSectionNumeral(13)).isEqualTo("14")
    }

    @Test
    fun `章节编号可安全增长`() {
        assertThat(helpSectionNumeral(99)).isEqualTo("100")
    }

    @Test
    fun `正文前二十条使用圆圈数字`() {
        assertThat(helpItemNumeral(0)).isEqualTo("①")
        assertThat(helpItemNumeral(9)).isEqualTo("⑩")
        assertThat(helpItemNumeral(10)).isEqualTo("⑪")
        assertThat(helpItemNumeral(19)).isEqualTo("⑳")
    }

    @Test
    fun `正文超过二十条回退普通数字`() {
        assertThat(helpItemNumeral(20)).isEqualTo("21")
        assertThat(helpItemNumeral(99)).isEqualTo("100")
    }

    @Test
    fun `命中的词标成主题强调色`() {
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
