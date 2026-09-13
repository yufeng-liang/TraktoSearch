package com.tracktosearch.ui.screen.splash

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 日签页版面里那几个纯函数：海报尺寸与台词排版。
 *
 * 它们现在是开屏、日签卡、导出图三处共用的一套数（见 StampPage），所以「几行台词配多大的
 * 海报」「英文原文走哪一档字号」这些规矩得钉住——改坏了不会崩，只会让导出的和开屏的看起来
 * 不是同一页。
 */
class StampPageTest {

    @Test
    fun `三行以内用全尺寸海报`() {
        assertThat(stampPosterSize(lineCount = 3, compact = false))
            .isEqualTo(DpSize(238.dp, 356.dp))
    }

    @Test
    fun `四行台词把海报收一档`() {
        // 收的比例直接写在这一档里：0.92 是「省下 28dp、正好保住顶部安全边」的那个值
        assertThat(stampPosterSize(lineCount = 4, compact = false))
            .isEqualTo(DpSize(238.dp * 0.92f, 356.dp * 0.92f))
    }

    @Test
    fun `矮屏整块回落一档`() {
        // 矮屏那一档不跟着四行的比例走：760dp 上再收一次会把海报缩得没有画面
        assertThat(stampPosterSize(lineCount = 4, compact = true))
            .isEqualTo(DpSize(176.dp, 264.dp))
    }

    @Test
    fun `台词字号随行数递减`() {
        val one = stampLineSpec(isEnglish = false, lineCount = 1).fontSize
        val three = stampLineSpec(isEnglish = false, lineCount = 3).fontSize
        val four = stampLineSpec(isEnglish = false, lineCount = 4).fontSize
        assertThat(one).isEqualTo(23f)
        assertThat(three).isEqualTo(21f)
        assertThat(four).isEqualTo(19f)
    }

    @Test
    fun `英文原文走斜体且字号小一档`() {
        val english = stampLineSpec(isEnglish = true, lineCount = 2)
        val chinese = stampLineSpec(isEnglish = false, lineCount = 2)
        assertThat(english.italic).isTrue()
        assertThat(chinese.italic).isFalse()
        assertThat(english.fontSize).isLessThan(chinese.fontSize)
    }

    @Test
    fun `字距只分中英两档`() {
        assertThat(stampLineSpec(isEnglish = false, lineCount = 2).letterSpacing).isEqualTo(0.012f)
        assertThat(stampLineSpec(isEnglish = true, lineCount = 2).letterSpacing).isEqualTo(0.006f)
    }
}
